from __future__ import annotations

import os
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
        for key in ("FLOWPILOT_S3_BUCKET", "FLOWPILOT_S3_ENDPOINT_URL", "FLOWPILOT_S3_ACCESS_KEY", "FLOWPILOT_S3_SECRET_KEY"):
            monkeypatch.delenv(key, raising=False)
        test_s3_settings = {
            "FLOWPILOT_S3_BUCKET": os.environ.get("FLOWPILOT_TEST_S3_BUCKET"),
            "FLOWPILOT_S3_ENDPOINT_URL": os.environ.get("FLOWPILOT_TEST_S3_ENDPOINT_URL"),
            "FLOWPILOT_S3_ACCESS_KEY": os.environ.get("FLOWPILOT_TEST_S3_ACCESS_KEY"),
            "FLOWPILOT_S3_SECRET_KEY": os.environ.get("FLOWPILOT_TEST_S3_SECRET_KEY"),
        }
        if any(test_s3_settings.values()):
            assert all(test_s3_settings.values()), "All FLOWPILOT_TEST_S3_* settings must be provided together"
            for key, value in test_s3_settings.items():
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
