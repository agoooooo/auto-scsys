from __future__ import annotations

import concurrent.futures
import os
import threading
import uuid
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import psycopg
import pytest
from fastapi.testclient import TestClient
from psycopg import sql

from server.app import _connect, create_app, create_initial_admin


@pytest.mark.skipif(not os.environ.get("FLOWPILOT_TEST_DATABASE_URL"), reason="PostgreSQL integration URL not configured")
def test_postgres_runs_api_schema_migrations_and_script_download(monkeypatch):
    database_url = os.environ["FLOWPILOT_TEST_DATABASE_URL"]
    schema = f"flowpilot_test_{uuid.uuid4().hex}"
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))

    parts = urlsplit(database_url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["options"] = f"-csearch_path={schema}"
    isolated_url = urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))
    storage = None
    object_key = None
    try:
        monkeypatch.setenv("FLOWPILOT_ENV", "test")
        for key in (
            "FLOWPILOT_S3_BUCKET",
            "FLOWPILOT_S3_ENDPOINT_URL",
            "FLOWPILOT_S3_PUBLIC_ENDPOINT_URL",
            "FLOWPILOT_S3_REGION",
            "FLOWPILOT_S3_ACCESS_KEY",
            "FLOWPILOT_S3_SECRET_KEY",
        ):
            monkeypatch.delenv(key, raising=False)
        test_s3_settings = {
            "FLOWPILOT_S3_BUCKET": os.environ.get("FLOWPILOT_TEST_S3_BUCKET"),
            "FLOWPILOT_S3_ENDPOINT_URL": os.environ.get("FLOWPILOT_TEST_S3_ENDPOINT_URL"),
            "FLOWPILOT_S3_PUBLIC_ENDPOINT_URL": os.environ.get("FLOWPILOT_TEST_S3_PUBLIC_ENDPOINT_URL"),
            "FLOWPILOT_S3_REGION": os.environ.get("FLOWPILOT_TEST_S3_REGION"),
            "FLOWPILOT_S3_ACCESS_KEY": os.environ.get("FLOWPILOT_TEST_S3_ACCESS_KEY"),
            "FLOWPILOT_S3_SECRET_KEY": os.environ.get("FLOWPILOT_TEST_S3_SECRET_KEY"),
        }
        if any(test_s3_settings.values()):
            required_s3_settings = (
                "FLOWPILOT_S3_BUCKET",
                "FLOWPILOT_S3_ENDPOINT_URL",
                "FLOWPILOT_S3_ACCESS_KEY",
                "FLOWPILOT_S3_SECRET_KEY",
            )
            assert all(test_s3_settings[key] for key in required_s3_settings), (
                "FLOWPILOT_TEST_S3_BUCKET, FLOWPILOT_TEST_S3_ENDPOINT_URL, "
                "FLOWPILOT_TEST_S3_ACCESS_KEY and FLOWPILOT_TEST_S3_SECRET_KEY are required together"
            )
            for key, value in test_s3_settings.items():
                if value:
                    monkeypatch.setenv(key, value)
        app = create_app(isolated_url, b"postgres-integration-test-secret-32-bytes")
        storage = app.state.script_storage
        with TestClient(app) as client:
            token_response = client.post(
                "/auth/login",
                json={"email": "chen.xiao@example.com", "password": "user-demo"},
            )
            assert token_response.status_code == 200
            headers = {"Authorization": f"Bearer {token_response.json()['access_token']}"}
            assert client.get("/tasks", headers=headers).status_code == 200

            task = client.post("/tasks", headers=headers, json={"name": "PostgreSQL storage check"})
            assert task.status_code == 201
            content = b"print('postgresql')\n"
            published = client.post(
                f"/tasks/{task.json()['id']}/versions",
                headers=headers,
                data={"version": "v1.0.0"},
                files={"script": ("script.py", content, "text/x-python")},
            )
            assert published.status_code == 201
            with _connect(isolated_url) as db:
                version = db.execute(
                    "SELECT content,storage_key FROM task_versions WHERE id=?",
                    (published.json()["id"],),
                ).fetchone()
            object_key = version["storage_key"]
            if storage:
                assert version["content"] == b""
            else:
                assert version["content"] == content
            downloaded = client.get(
                f"/devices/d-001/tasks/{task.json()['id']}/versions/v1.0.0/content",
                headers=headers,
            )
            assert downloaded.status_code == 200
            assert downloaded.content == content
    finally:
        if storage and object_key:
            storage.delete(object_key)
        with psycopg.connect(database_url, autocommit=True) as admin:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


@pytest.mark.skipif(not os.environ.get("FLOWPILOT_TEST_DATABASE_URL"), reason="PostgreSQL integration URL not configured")
def test_postgres_bootstraps_a_single_initial_admin(monkeypatch):
    database_url = os.environ["FLOWPILOT_TEST_DATABASE_URL"]
    schema = f"flowpilot_bootstrap_{uuid.uuid4().hex}"
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))

    parts = urlsplit(database_url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["options"] = f"-csearch_path={schema}"
    isolated_url = urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))
    try:
        monkeypatch.setenv("FLOWPILOT_ENV", "test")
        user_id = create_initial_admin(
            isolated_url,
            "Initial Administrator",
            "initial@example.com",
            "a-strong-postgresql-password",
        )
        app = create_app(isolated_url, b"postgres-bootstrap-test-secret-32-bytes")
        with TestClient(app) as client:
            response = client.post(
                "/auth/login",
                json={"email": "initial@example.com", "password": "a-strong-postgresql-password"},
            )
            assert response.status_code == 200
            assert response.json()["user"]["id"] == user_id
        with pytest.raises(ValueError, match="已有用户"):
            create_initial_admin(
                isolated_url,
                "Second Administrator",
                "second@example.com",
                "another-strong-postgresql-password",
            )
    finally:
        with psycopg.connect(database_url, autocommit=True) as admin:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


@pytest.mark.skipif(not os.environ.get("FLOWPILOT_TEST_DATABASE_URL"), reason="PostgreSQL integration URL not configured")
def test_postgres_serializes_concurrent_dispatch_retries_and_approvals(monkeypatch):
    database_url = os.environ["FLOWPILOT_TEST_DATABASE_URL"]
    schema = f"flowpilot_dispatch_{uuid.uuid4().hex}"
    with psycopg.connect(database_url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))

    parts = urlsplit(database_url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query["options"] = f"-csearch_path={schema}"
    isolated_url = urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))
    try:
        monkeypatch.setenv("FLOWPILOT_ENV", "test")
        for key in (
            "FLOWPILOT_S3_BUCKET",
            "FLOWPILOT_S3_ENDPOINT_URL",
            "FLOWPILOT_S3_PUBLIC_ENDPOINT_URL",
            "FLOWPILOT_S3_REGION",
            "FLOWPILOT_S3_ACCESS_KEY",
            "FLOWPILOT_S3_SECRET_KEY",
        ):
            monkeypatch.delenv(key, raising=False)
        app = create_app(isolated_url, b"postgres-dispatch-test-secret-at-least-32-bytes")
        with TestClient(app) as client:
            login = client.post(
                "/auth/login",
                json={"email": "chen.xiao@example.com", "password": "user-demo"},
            )
            assert login.status_code == 200
            headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
            request_body = {"task_id": "t-001", "device_id": "d-001"}
            idempotent_headers = {**headers, "Idempotency-Key": "postgres-retry-001"}
            barrier = threading.Barrier(2)

            def create_retry():
                barrier.wait()
                return client.post("/remote-runs", headers=idempotent_headers, json=request_body)

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                duplicate_responses = list(executor.map(lambda _: create_retry(), range(2)))
            assert [response.status_code for response in duplicate_responses] == [201, 201]
            assert len({response.json()["id"] for response in duplicate_responses}) == 1
            assert sorted(response.json()["idempotent_replay"] for response in duplicate_responses) == [False, True]

            second_request = client.post(
                "/remote-runs",
                headers={**headers, "Idempotency-Key": "postgres-retry-002"},
                json=request_body,
            )
            assert second_request.status_code == 201
            requests = [duplicate_responses[0].json(), second_request.json()]
            barrier = threading.Barrier(2)

            def approve(request):
                barrier.wait()
                return client.patch(
                    f"/remote-runs/{request['id']}",
                    headers=headers,
                    json={"decision": "approve"},
                )

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                approval_responses = list(executor.map(approve, requests))
            assert sorted(response.status_code for response in approval_responses) == [200, 409]
            with _connect(isolated_url) as db:
                active_count = db.execute(
                    "SELECT COUNT(*) FROM runs WHERE device_id=? AND status='running'",
                    ("d-001",),
                ).fetchone()[0]
            assert active_count == 1
    finally:
        with psycopg.connect(database_url, autocommit=True) as admin:
            admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))
