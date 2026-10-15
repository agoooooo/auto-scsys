from __future__ import annotations

from io import BytesIO
from urllib.parse import parse_qs, urlparse

import pytest
from botocore.response import StreamingBody
from botocore.stub import Stubber

from server.storage import S3ScriptStorage, create_script_storage


def _s3_storage(monkeypatch) -> S3ScriptStorage:
    monkeypatch.setenv("FLOWPILOT_S3_BUCKET", "flowpilot-test")
    monkeypatch.setenv("FLOWPILOT_S3_ENDPOINT_URL", "https://objects.example.test")
    monkeypatch.setenv("FLOWPILOT_S3_PUBLIC_ENDPOINT_URL", "https://public-objects.example.test")
    monkeypatch.setenv("FLOWPILOT_S3_REGION", "us-east-1")
    monkeypatch.setenv("FLOWPILOT_S3_ACCESS_KEY", "test-access-key")
    monkeypatch.setenv("FLOWPILOT_S3_SECRET_KEY", "test-secret-key")
    storage = create_script_storage(required=True)
    assert isinstance(storage, S3ScriptStorage)
    return storage


def test_s3_storage_put_get_and_delete_use_private_object_keys(monkeypatch):
    storage = _s3_storage(monkeypatch)
    key = "scripts/task-id/version-id.py"
    content = b"print('stored')\n"
    with Stubber(storage.client) as stubber:
        stubber.add_response(
            "put_object",
            {"ETag": '"content-etag"'},
            {"Bucket": "flowpilot-test", "Key": key, "Body": content, "ContentType": "text/x-python"},
        )
        stubber.add_response(
            "get_object",
            {
                "Body": StreamingBody(BytesIO(content), len(content)),
                "ContentLength": len(content),
                "ETag": '"content-etag"',
            },
            {"Bucket": "flowpilot-test", "Key": key},
        )
        stubber.add_response("delete_object", {}, {"Bucket": "flowpilot-test", "Key": key})

        storage.put(key, content)
        assert storage.get(key) == content
        storage.delete(key)
        stubber.assert_no_pending_responses()


def test_s3_storage_preserves_android_apk_content_type(monkeypatch):
    storage = _s3_storage(monkeypatch)
    key = "apps/controlled/2.apk"
    content = b"binary APK"
    with Stubber(storage.client) as stubber:
        stubber.add_response(
            "put_object",
            {"ETag": '"apk-etag"'},
            {
                "Bucket": "flowpilot-test",
                "Key": key,
                "Body": content,
                "ContentType": "application/vnd.android.package-archive",
            },
        )
        storage.put(key, content, content_type="application/vnd.android.package-archive")
        stubber.assert_no_pending_responses()


def test_s3_storage_initializes_bucket_only_when_missing(monkeypatch):
    storage = _s3_storage(monkeypatch)
    with Stubber(storage.client) as stubber:
        stubber.add_client_error(
            "head_bucket",
            service_error_code="NoSuchBucket",
            http_status_code=404,
            expected_params={"Bucket": "flowpilot-test"},
        )
        stubber.add_response("create_bucket", {}, {"Bucket": "flowpilot-test"})
        storage.ensure_bucket()
        stubber.assert_no_pending_responses()


def test_s3_storage_health_check_confirms_bucket_access(monkeypatch):
    storage = _s3_storage(monkeypatch)
    with Stubber(storage.client) as stubber:
        stubber.add_response("head_bucket", {}, {"Bucket": "flowpilot-test"})
        storage.check_ready()
        stubber.assert_no_pending_responses()


def test_presigned_transfer_urls_use_public_endpoint_and_short_expiry(monkeypatch):
    storage = _s3_storage(monkeypatch)
    upload_url, headers = storage.presign_upload("scripts/t1/u1.py")
    download_url = storage.presign_download("scripts/t1/u1.py", filename="sample.py")
    parsed_upload = urlparse(upload_url)
    parsed_download = urlparse(download_url)
    assert parsed_upload.netloc == "public-objects.example.test"
    assert parsed_download.netloc == "public-objects.example.test"
    assert parse_qs(parsed_upload.query)["X-Amz-Expires"] == ["300"]
    assert parse_qs(parsed_download.query)["X-Amz-Expires"] == ["300"]
    assert headers == {"Content-Type": "text/x-python"}
    assert "response-content-disposition=" in download_url


def test_s3_storage_configures_browser_cors_for_signed_transfers(monkeypatch):
    storage = _s3_storage(monkeypatch)
    with Stubber(storage.client) as stubber:
        stubber.add_response(
            "put_bucket_cors",
            {},
            {
                "Bucket": "flowpilot-test",
                "CORSConfiguration": {
                    "CORSRules": [{
                        "AllowedOrigins": ["https://console.example.test"],
                        "AllowedMethods": ["GET", "HEAD", "PUT"],
                        "AllowedHeaders": ["*"],
                        "ExposeHeaders": ["ETag", "Content-Length", "Content-Type"],
                        "MaxAgeSeconds": 300,
                    }]
                },
            },
        )
        storage.configure_cors(["https://console.example.test"])
        stubber.assert_no_pending_responses()


def test_s3_storage_skips_bucket_cors_when_no_browser_origins_are_configured(monkeypatch):
    storage = _s3_storage(monkeypatch)
    storage.configure_cors([])


def test_s3_storage_rejects_partial_configuration(monkeypatch):
    monkeypatch.delenv("FLOWPILOT_S3_BUCKET", raising=False)
    monkeypatch.setenv("FLOWPILOT_S3_ENDPOINT_URL", "https://objects.example.test")
    monkeypatch.delenv("FLOWPILOT_S3_ACCESS_KEY", raising=False)
    monkeypatch.delenv("FLOWPILOT_S3_SECRET_KEY", raising=False)
    with pytest.raises(ValueError, match="S3 storage requires"):
        create_script_storage()


def test_s3_storage_rejects_non_http_endpoints(monkeypatch):
    _s3_storage(monkeypatch)
    monkeypatch.setenv("FLOWPILOT_S3_PUBLIC_ENDPOINT_URL", "file:///tmp/bucket")
    with pytest.raises(ValueError, match="HTTP or HTTPS"):
        create_script_storage()
