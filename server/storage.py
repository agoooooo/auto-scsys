from __future__ import annotations

import os
from typing import Protocol


class ScriptStorage(Protocol):
    def put(self, key: str, content: bytes) -> None: ...

    def get(self, key: str) -> bytes: ...

    def delete(self, key: str) -> None: ...

    def ensure_bucket(self) -> None: ...

    def check_ready(self) -> None: ...


class S3ScriptStorage:
    def __init__(self, *, bucket: str, endpoint_url: str | None, region: str, access_key: str, secret_key: str):
        import boto3
        from botocore.config import Config

        self.bucket = bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=endpoint_url,
            region_name=region,
            aws_access_key_id=access_key,
            aws_secret_access_key=secret_key,
            config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
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


def create_script_storage(*, required: bool = False) -> ScriptStorage | None:
    bucket = os.environ.get("FLOWPILOT_S3_BUCKET", "").strip()
    endpoint = os.environ.get("FLOWPILOT_S3_ENDPOINT_URL", "").strip() or None
    region = os.environ.get("FLOWPILOT_S3_REGION", "us-east-1").strip()
    access_key = os.environ.get("FLOWPILOT_S3_ACCESS_KEY", "").strip()
    secret_key = os.environ.get("FLOWPILOT_S3_SECRET_KEY", "")

    configured = any((bucket, endpoint, access_key, secret_key))
    if not configured and not required:
        return None
    if not bucket or not access_key or not secret_key or not region:
        raise ValueError("S3 storage requires FLOWPILOT_S3_BUCKET, FLOWPILOT_S3_ACCESS_KEY and FLOWPILOT_S3_SECRET_KEY")
    return S3ScriptStorage(
        bucket=bucket,
        endpoint_url=endpoint,
        region=region,
        access_key=access_key,
        secret_key=secret_key,
    )
