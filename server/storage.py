from __future__ import annotations

import os
from urllib.parse import quote
from typing import Protocol


class ScriptStorage(Protocol):
    def put(self, key: str, content: bytes) -> None: ...

    def get(self, key: str) -> bytes: ...

    def delete(self, key: str) -> None: ...

    def ensure_bucket(self) -> None: ...

    def check_ready(self) -> None: ...

    def configure_cors(self, origins: list[str]) -> None: ...

    def presign_upload(self, key: str, *, expires_seconds: int = 300) -> tuple[str, dict[str, str]]: ...

    def presign_download(self, key: str, *, filename: str, expires_seconds: int = 300) -> str: ...

    def get_info(self, key: str) -> tuple[int, bytes]: ...


class S3ScriptStorage:
    def __init__(
        self,
        *,
        bucket: str,
        endpoint_url: str | None,
        public_endpoint_url: str | None,
        region: str,
        access_key: str,
        secret_key: str,
    ):
        import boto3
        from botocore.config import Config

        self.bucket = bucket
        client_options = {
            "region_name": region,
            "aws_access_key_id": access_key,
            "aws_secret_access_key": secret_key,
            "config": Config(signature_version="s3v4", s3={"addressing_style": "path"}),
        }
        self.client = boto3.client("s3", endpoint_url=endpoint_url, **client_options)
        self.signing_client = boto3.client(
            "s3",
            endpoint_url=public_endpoint_url or endpoint_url,
            **client_options,
        )

    def put(self, key: str, content: bytes) -> None:
        self.client.put_object(Bucket=self.bucket, Key=key, Body=content, ContentType="text/x-python")

    def get(self, key: str) -> bytes:
        response = self.client.get_object(Bucket=self.bucket, Key=key)
        try:
            return response["Body"].read()
        finally:
            response["Body"].close()

    def delete(self, key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=key)

    def ensure_bucket(self) -> None:
        from botocore.exceptions import ClientError

        try:
            self.client.head_bucket(Bucket=self.bucket)
        except ClientError as error:
            if error.response.get("ResponseMetadata", {}).get("HTTPStatusCode") != 404:
                raise
            self.client.create_bucket(Bucket=self.bucket)

    def check_ready(self) -> None:
        self.client.head_bucket(Bucket=self.bucket)

    def configure_cors(self, origins: list[str]) -> None:
        if origins:
            self.client.put_bucket_cors(
                Bucket=self.bucket,
                CORSConfiguration={
                    "CORSRules": [{
                        "AllowedOrigins": origins,
                        "AllowedMethods": ["GET", "HEAD", "PUT"],
                        "AllowedHeaders": ["*"],
                        "ExposeHeaders": ["ETag", "Content-Length", "Content-Type"],
                        "MaxAgeSeconds": 300,
                    }]
                },
            )

    def presign_upload(self, key: str, *, expires_seconds: int = 300) -> tuple[str, dict[str, str]]:
        headers = {"Content-Type": "text/x-python"}
        url = self.signing_client.generate_presigned_url(
            "put_object",
            Params={"Bucket": self.bucket, "Key": key, "ContentType": headers["Content-Type"]},
            ExpiresIn=expires_seconds,
        )
        return url, headers

    def presign_download(self, key: str, *, filename: str, expires_seconds: int = 300) -> str:
        return self.signing_client.generate_presigned_url(
            "get_object",
            Params={
                "Bucket": self.bucket,
                "Key": key,
                "ResponseContentType": "text/x-python",
                "ResponseContentDisposition": f"attachment; filename*=UTF-8''{quote(filename, safe='')}",
                "ResponseCacheControl": "no-store",
            },
            ExpiresIn=expires_seconds,
        )

    def get_info(self, key: str) -> tuple[int, bytes]:
        response = self.client.get_object(Bucket=self.bucket, Key=key)
        try:
            content = response["Body"].read()
            return response["ContentLength"], content
        finally:
            response["Body"].close()


def create_script_storage(*, required: bool = False) -> ScriptStorage | None:
    bucket = os.environ.get("FLOWPILOT_S3_BUCKET", "").strip()
    endpoint = os.environ.get("FLOWPILOT_S3_ENDPOINT_URL", "").strip() or None
    public_endpoint = os.environ.get("FLOWPILOT_S3_PUBLIC_ENDPOINT_URL", "").strip() or None
    region = os.environ.get("FLOWPILOT_S3_REGION", "us-east-1").strip()
    access_key = os.environ.get("FLOWPILOT_S3_ACCESS_KEY", "").strip()
    secret_key = os.environ.get("FLOWPILOT_S3_SECRET_KEY", "")

    configured = any((bucket, endpoint, public_endpoint, access_key, secret_key))
    if not configured and not required:
        return None
    if not bucket or not access_key or not secret_key or not region:
        raise ValueError("S3 storage requires FLOWPILOT_S3_BUCKET, FLOWPILOT_S3_ACCESS_KEY and FLOWPILOT_S3_SECRET_KEY")
    for endpoint_url in (endpoint, public_endpoint):
        if endpoint_url and not endpoint_url.startswith(("http://", "https://")):
            raise ValueError("S3 endpoint URLs must use HTTP or HTTPS")
    return S3ScriptStorage(
        bucket=bucket,
        endpoint_url=endpoint,
        public_endpoint_url=public_endpoint,
        region=region,
        access_key=access_key,
        secret_key=secret_key,
    )
