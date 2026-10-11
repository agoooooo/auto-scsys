import hashlib
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from server.app import create_app


@pytest.fixture
def client(tmp_path):
    app = create_app(tmp_path / "test.sqlite3", b"test-secret-must-be-at-least-32-bytes")
    with TestClient(app) as test_client:
        yield test_client


def login(client, email="chen.xiao@example.com", password="user-demo"):
    response = client.post("/auth/login", json={"email": email, "password": password})
    assert response.status_code == 200
    return response.json()["access_token"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_login_roles_and_disabled_accounts(client):
    assert client.post("/auth/login", json={"email": "admin@example.com", "password": "wrong"}).status_code == 401
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    assert client.get("/users", headers=auth(user)).status_code == 403
    assert client.get("/users", headers=auth(admin)).status_code == 200
    assert client.get("/admin/audit", headers=auth(user)).status_code == 403
    assert client.get("/admin/audit", headers=auth(admin)).status_code == 200
    assert client.get("/devices").status_code == 401
    refreshed = client.post("/auth/refresh", headers=auth(user))
    assert refreshed.status_code == 200
    assert client.get("/me", headers=auth(refreshed.json()["access_token"])).status_code == 200

    client.patch("/users/u-1001", headers=auth(admin), json={"status": "disabled"})
    assert client.get("/me", headers=auth(user)).status_code == 401


def test_admin_audit_contains_key_actions_without_sensitive_account_data(client):
    admin = login(client, "admin@example.com", "admin-demo")
    created = client.post(
        "/users",
        headers=auth(admin),
        json={"name": "审计用户", "email": "audit@example.com", "password": "audit-password"},
    )
    assert created.status_code == 201
    events = client.get("/admin/audit", headers=auth(admin)).json()
    event = next(item for item in events if item["resource_id"] == created.json()["id"])
    assert event["action"] == "user.created"
    assert event["actor"] == "林管理员"
    assert set(event) == {"id", "actor_id", "actor", "action", "resource_type", "resource_id", "created_at"}
    assert all("password" not in key for item in events for key in item)


def test_configured_cors_origin_is_allowed_but_unconfigured_origin_is_rejected(tmp_path, monkeypatch):
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "http://192.168.0.101:8000, http://devbox.local:8000")
    app = create_app(tmp_path / "cors.sqlite3", b"cors-secret-must-be-at-least-32-bytes")
    with TestClient(app) as test_client:
        allowed = test_client.options(
            "/auth/login",
            headers={
                "Origin": "http://192.168.0.101:8000",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        assert allowed.status_code == 200
        assert allowed.headers["access-control-allow-origin"] == "http://192.168.0.101:8000"
        rejected = test_client.options(
            "/auth/login",
            headers={
                "Origin": "http://192.168.1.21:8000",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        assert "access-control-allow-origin" not in rejected.headers


def test_user_devices_and_task_access_are_scoped(client):
    user = login(client)
    devices = client.get("/devices", headers=auth(user)).json()
    assert {device["id"] for device in devices} == {"d-001", "d-002"}
    tasks = client.get("/tasks", headers=auth(user)).json()
    assert [task["id"] for task in tasks] == ["t-001"]
    assert tasks[0]["active_version"] == "v1.7.0"
    assert client.post("/devices/d-002/sync", headers=auth(user)).status_code == 200
    assert client.post("/devices/d-003/sync", headers=auth(user)).status_code == 404
    assert client.get("/devices/d-001/tasks/t-002/versions/v0.2.0/content", headers=auth(user)).status_code == 404


def test_device_liveness_expires_and_device_activity_restores_online_status(client):
    import server.app as api

    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    stale_activity = (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat()
    with api._connect(client.app.state.db_path) as db:
        db.execute("UPDATE devices SET last_active=? WHERE id='d-001'", (stale_activity,))

    devices = client.get("/devices", headers=auth(admin))
    assert devices.status_code == 200
    assert next(device for device in devices.json() if device["id"] == "d-001")["status"] == "offline"
    assert client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).status_code == 409

    synced = client.post("/devices/d-001/sync", headers=auth(user))
    assert synced.status_code == 200
    assert next(
        device for device in client.get("/devices", headers=auth(admin)).json()
        if device["id"] == "d-001"
    )["status"] == "online"

    with api._connect(client.app.state.db_path) as db:
        db.execute("UPDATE devices SET last_active=? WHERE id='d-001'", (stale_activity,))
    polled = client.get("/devices/d-001/remote-runs", headers=auth(user))
    assert polled.status_code == 200
    assert next(
        device for device in client.get("/devices", headers=auth(admin)).json()
        if device["id"] == "d-001"
    )["status"] == "online"

    request = client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    )
    assert request.status_code == 201


def test_seeded_ocr_demo_script_is_versioned_hashed_and_does_not_click(client):
    user = login(client)
    downloaded = client.get(
        "/devices/d-001/tasks/t-001/versions/v1.7.0/content",
        headers=auth(user),
    )

    assert downloaded.status_code == 200
    assert downloaded.headers["x-content-sha256"] == hashlib.sha256(downloaded.content).hexdigest()
    source = downloaded.content.decode("utf-8")
    compile(source, "order_status_ocr.py", "exec")
    previous = client.get(
        "/devices/d-001/tasks/t-001/versions/v1.6.0/content",
        headers=auth(user),
    )
    assert previous.status_code == 200
    assert 'result = f"界面 OCR 完成；匹配到 {len(matched_statuses)} 项订单状态"' in previous.text
    assert "bridge.open_douyin()" in source
    assert "bridge.wait_for_douyin(20)" in source
    assert "bridge.ocr()" in source
    assert "bridge.click_text" not in source
    assert "bridge.log(" in source
    assert "screen_text" in source
    logged_messages = []
    waited = []
    def run_demo_script(ocr_text):
        bridge = SimpleNamespace(
            open_douyin=lambda: True,
            wait_for_douyin=lambda timeout: waited.append(timeout) or True,
            ocr=lambda: ocr_text,
            log=logged_messages.append,
        )
        namespace = {"bridge": bridge}
        exec(compile(source, "order_status_ocr.py", "exec"), namespace)
        return namespace["result"]

    assert run_demo_script("订单已完成，等待收货") == "界面 OCR 完成；识别到订单状态：待收货、已完成"
    assert run_demo_script("欢迎使用抖音") == "界面 OCR 完成；未匹配到常见订单状态"
    assert waited == [20, 20]
    assert logged_messages == [
        "OCR completed; chars=10; order_status_matches=2",
        "OCR completed; chars=6; order_status_matches=0",
    ]
    assert all("订单已完成" not in message for message in logged_messages)


def test_user_can_bind_devices_up_to_account_limit(client):
    user = login(client)
    created_ids = []
    for index in range(3):
        response = client.post(
            "/devices",
            headers=auth(user),
            json={"name": f"Extra {index}", "model": "Android 15", "app_version": "1.2.0"},
        )
        assert response.status_code == 201
        created_ids.append(response.json()["id"])
    assert client.post(
        "/devices",
        headers=auth(user),
        json={"name": "Over limit", "model": "Android"},
    ).status_code == 409
    assert client.delete(f"/devices/{created_ids[0]}", headers=auth(user)).status_code == 200
    assert client.post(
        "/devices",
        headers=auth(user),
        json={"name": "Replacement", "model": "Android"},
    ).status_code == 201
    assert client.get(f"/devices/{created_ids[0]}/remote-runs", headers=auth(login(client, "zhou.ke@example.com", "user-demo"))).status_code == 404


def test_admin_can_update_user_device_limit_with_validation_and_audit(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)

    assert client.patch(
        "/users/u-1001",
        headers=auth(user),
        json={"device_limit": 10},
    ).status_code == 403
    assert client.patch("/users/u-1001", headers=auth(admin), json={}).status_code == 422
    assert client.patch(
        "/users/u-1001",
        headers=auth(admin),
        json={"device_limit": 1},
    ).status_code == 409
    assert client.patch(
        "/users/u-1001",
        headers=auth(admin),
        json={"device_limit": 51},
    ).status_code == 422

    updated = client.patch(
        "/users/u-1001",
        headers=auth(admin),
        json={"device_limit": 4},
    )
    assert updated.status_code == 200
    assert updated.json() == {"id": "u-1001", "status": "active", "device_limit": 4}
    assert client.post(
        "/devices",
        headers=auth(user),
        json={"name": "Within new limit", "model": "Android"},
    ).status_code == 201
    assert any(
        event["action"] == "user.device_limit_updated" and event["resource_id"] == "u-1001"
        for event in client.get("/admin/audit", headers=auth(admin)).json()
    )


def test_user_can_create_and_publish_owned_task_but_not_another_users_task(client):
    user = login(client)
    created = client.post(
        "/tasks",
        headers=auth(user),
        json={
            "name": "我的 OCR 任务",
            "description": "个人任务",
            "capabilities": ["OCR 文字识别"],
        },
    )
    assert created.status_code == 201
    task_id = created.json()["id"]
    assert created.json()["status"] == "draft"
    assert task_id in {task["id"] for task in client.get("/tasks", headers=auth(user)).json()}

    content = b"print('owner only')\n"
    published = client.post(
        f"/tasks/{task_id}/versions",
        headers=auth(user),
        data={"version": "v1.0.0"},
        files={"script": ("my_task.py", content, "text/x-python")},
    )
    assert published.status_code == 201
    assert next(task for task in client.get("/tasks", headers=auth(user)).json() if task["id"] == task_id)["status"] == "published"
    assert next(task for task in client.get("/tasks", headers=auth(user)).json() if task["id"] == task_id)["active_version"] == "v1.0.0"

    other_user = login(client, "zhou.ke@example.com", "user-demo")
    forbidden_upload = client.post(
        f"/tasks/{task_id}/versions",
        headers=auth(other_user),
        data={"version": "v1.1.0"},
        files={"script": ("stolen.py", b"pass", "text/x-python")},
    )
    assert forbidden_upload.status_code == 404


def test_publish_script_validates_and_checks_content_hash(client):
    admin = login(client, "admin@example.com", "admin-demo")
    invalid_version = client.post(
        "/tasks/t-002/versions",
        headers=auth(admin),
        data={"version": "latest"},
        files={"script": ("task.py", b"print(1)", "text/x-python")},
    )
    assert invalid_version.status_code == 422
    invalid_file = client.post(
        "/tasks/t-002/versions",
        headers=auth(admin),
        data={"version": "v1.0.0"},
        files={"script": ("task.txt", b"print(1)", "text/plain")},
    )
    assert invalid_file.status_code == 422

    content = b"print('hello')\n"
    published = client.post(
        "/tasks/t-002/versions",
        headers=auth(admin),
        data={"version": "v1.0.0", "notes": "first"},
        files={"script": ("task.py", content, "text/x-python")},
    )
    assert published.status_code == 201
    assert published.json()["sha256"] == hashlib.sha256(content).hexdigest()
    assert client.post("/tasks/t-002/grants/u-1001", headers=auth(admin)).status_code == 201
    user = login(client)
    downloaded = client.get(
        "/devices/d-001/tasks/t-002/versions/v1.0.0/content",
        headers=auth(user),
    )
    assert downloaded.content == content
    assert downloaded.headers["x-content-sha256"] == hashlib.sha256(content).hexdigest()


def test_admin_can_list_grant_and_revoke_task_access(client):
    admin = login(client, "admin@example.com", "admin-demo")
    chen = login(client)
    zhou = login(client, "zhou.ke@example.com", "user-demo")
    content = b"print('shared')\n"
    assert client.post(
        "/tasks/t-002/versions",
        headers=auth(admin),
        data={"version": "v1.0.0"},
        files={"script": ("shared.py", content, "text/x-python")},
    ).status_code == 201
    assert client.get("/tasks/t-002/grants", headers=auth(chen)).status_code == 403
    assert client.get("/tasks/missing/grants", headers=auth(admin)).status_code == 404

    granted = client.post("/tasks/t-002/grants/u-1001", headers=auth(admin))
    assert granted.status_code == 201
    grant_list = client.get("/tasks/t-002/grants", headers=auth(admin))
    assert grant_list.status_code == 200
    assert {grant["id"]: grant["is_owner"] for grant in grant_list.json()} == {
        "u-admin": True,
        "u-1001": False,
    }
    assert {task["id"] for task in client.get("/tasks", headers=auth(chen)).json()} == {"t-001", "t-002"}
    assert client.get(
        "/devices/d-001/tasks/t-002/versions/v1.0.0/content",
        headers=auth(chen),
    ).content == content

    pending = client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-002", "device_id": "d-001"},
    ).json()
    authorized = client.post(
        "/run-authorizations",
        headers=auth(chen),
        json={"task_id": "t-002", "version": "v1.0.0", "device_id": "d-001"},
    )
    assert authorized.status_code == 201

    revoked = client.delete("/tasks/t-002/grants/u-1001", headers=auth(admin))
    assert revoked.status_code == 200
    remaining_grants = client.get("/tasks/t-002/grants", headers=auth(admin)).json()
    assert [(grant["id"], grant["is_owner"]) for grant in remaining_grants] == [("u-admin", True)]
    assert {task["id"] for task in client.get("/tasks", headers=auth(chen)).json()} == {"t-001"}
    assert client.get(
        "/devices/d-001/tasks/t-002/versions/v1.0.0/content",
        headers=auth(chen),
    ).status_code == 404
    assert client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-002", "device_id": "d-001"},
    ).status_code == 404
    assert client.patch(
        f"/remote-runs/{pending['id']}",
        headers=auth(chen),
        json={"decision": "approve"},
    ).status_code == 410
    assert client.get("/run-authorizations", headers=auth(chen)).json() == []
    assert client.delete("/tasks/t-002/grants/u-1001", headers=auth(admin)).status_code == 404
    assert client.delete("/tasks/t-002/grants/u-admin", headers=auth(admin)).status_code == 409

    assert client.post("/tasks/t-002/grants/u-1002", headers=auth(admin)).status_code == 201
    assert any(task["id"] == "t-002" for task in client.get("/tasks", headers=auth(zhou)).json())
    inactive_grant = client.post("/tasks/t-002/grants/u-1003", headers=auth(admin))
    assert inactive_grant.status_code == 409


def test_admin_can_pause_and_resume_tasks_with_access_and_run_controls(client):
    import server.app as api

    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    pending = client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    approved = client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-002"},
    ).json()
    started = client.patch(
        f"/remote-runs/{approved['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    ).json()
    assert client.get("/run-authorizations", headers=auth(user)).json()

    paused = client.patch("/tasks/t-001", headers=auth(admin), json={"status": "paused"})
    assert paused.status_code == 200
    assert paused.json()["status"] == "paused"
    assert next(task for task in client.get("/tasks", headers=auth(user)).json() if task["id"] == "t-001")["status"] == "paused"
    assert client.post("/devices/d-001/sync", headers=auth(user)).json()["tasks"] == []
    assert client.get(
        "/devices/d-001/tasks/t-001/versions/v1.4.0/content",
        headers=auth(user),
    ).status_code == 409
    assert client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).status_code == 409
    assert client.post(
        "/devices/d-001/runs",
        headers=auth(user),
        json={"task_id": "t-001", "version": "v1.4.0"},
    ).status_code == 409
    assert client.get("/run-authorizations", headers=auth(user)).json() == []
    with api._connect(client.app.state.db_path) as db:
        assert db.execute("SELECT status FROM remote_runs WHERE id=?", (pending["id"],)).fetchone()["status"] == "expired"
        assert db.execute("SELECT status FROM runs WHERE id=?", (started["run_id"],)).fetchone()["status"] == "running"
    assert client.patch(
        f"/remote-runs/{pending['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).status_code == 410

    resumed = client.patch("/tasks/t-001", headers=auth(admin), json={"status": "published"})
    assert resumed.status_code == 200
    assert [task["id"] for task in client.post("/devices/d-001/sync", headers=auth(user)).json()["tasks"]] == ["t-001"]
    assert client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()["status"] == "pending"


def test_task_status_changes_require_admin_and_published_task_version(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    assert client.patch("/tasks/t-001", headers=auth(user), json={"status": "paused"}).status_code == 403
    assert client.patch("/tasks/missing", headers=auth(admin), json={"status": "paused"}).status_code == 404
    draft = client.post("/tasks", headers=auth(admin), json={"name": "No version"})
    assert draft.status_code == 201
    assert client.patch(
        f"/tasks/{draft.json()['id']}",
        headers=auth(admin),
        json={"status": "published"},
    ).status_code == 409
    assert client.patch("/tasks/t-001", headers=auth(admin), json={"status": "draft"}).status_code == 422


def test_publishing_new_version_does_not_resume_paused_task(client):
    admin = login(client, "admin@example.com", "admin-demo")
    assert client.patch("/tasks/t-001", headers=auth(admin), json={"status": "paused"}).status_code == 200
    published = client.post(
        "/tasks/t-001/versions",
        headers=auth(admin),
        data={"version": "v1.8.0"},
        files={"script": ("orders.py", b"print('new version')", "text/x-python")},
    )
    assert published.status_code == 201
    task = next(task for task in client.get("/tasks", headers=auth(admin)).json() if task["id"] == "t-001")
    assert task["active_version"] == "v1.8.0"
    assert task["status"] == "paused"


def test_task_version_can_be_activated_without_changing_immutable_content(client):
    admin = login(client, "admin@example.com", "admin-demo")
    owner = login(client)
    another_user = login(client, "zhou.ke@example.com", "user-demo")

    current_script = client.get(
        "/devices/d-001/tasks/t-001/versions/v1.7.0/content",
        headers=auth(owner),
    )
    assert client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()["version"] == "v1.7.0"
    assert client.patch(
        "/tasks/t-001",
        headers=auth(admin),
        json={"status": "paused"},
    ).status_code == 200

    assert client.patch(
        "/tasks/t-001/versions/v1.3.0/activate",
        headers=auth(another_user),
    ).status_code == 404
    activated = client.patch(
        "/tasks/t-001/versions/v1.3.0/activate",
        headers=auth(owner),
    )
    assert activated.status_code == 200
    assert activated.json()["active_version"] == "v1.3.0"
    task = next(task for task in client.get("/tasks", headers=auth(admin)).json() if task["id"] == "t-001")
    assert task["active_version"] == "v1.3.0"
    assert task["status"] == "paused"
    assert client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).status_code == 409
    assert client.patch(
        "/tasks/t-001",
        headers=auth(admin),
        json={"status": "published"},
    ).status_code == 200
    assert client.get(
        "/devices/d-001/tasks/t-001/versions/v1.7.0/content",
        headers=auth(owner),
    ).content == current_script.content
    assert client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()["version"] == "v1.3.0"
    assert client.patch(
        "/tasks/t-001/versions/v9.9.9/activate",
        headers=auth(admin),
    ).status_code == 404
    assert any(
        event["action"] == "task.version_activated" and event["actor"] == "陈晓"
        for event in client.get("/admin/audit", headers=auth(admin)).json()
    )


def test_existing_database_migrates_active_task_version(client):
    db_path = client.app.state.db_path
    with sqlite3.connect(db_path) as db:
        db.execute("ALTER TABLE tasks DROP COLUMN active_version")

    app = create_app(db_path, b"test-secret-must-be-at-least-32-bytes")
    with TestClient(app) as migrated_client:
        admin = login(migrated_client, "admin@example.com", "admin-demo")
        tasks = migrated_client.get("/tasks", headers=auth(admin)).json()
        task = next(task for task in tasks if task["id"] == "t-001")
        assert task["active_version"] == "v1.7.0"


def test_remote_run_confirmation_rejection_and_run_record(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    created = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"})
    assert created.status_code == 201
    request_id = created.json()["id"]
    assert created.json()["status"] == "pending"
    pending = client.get("/devices/d-001/remote-runs", headers=auth(user))
    assert [request["id"] for request in pending.json()] == [request_id]
    approved = client.patch(
        f"/remote-runs/{request_id}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    )
    assert approved.status_code == 200
    run_id = approved.json()["run_id"]
    assert client.get("/runs", headers=auth(user)).json()[0]["status"] == "running"
    completed = client.post(
        f"/runs/{run_id}",
        headers=auth(user),
        json={"status": "success", "result_summary": "OCR result"},
    )
    assert completed.status_code == 200
    assert client.get("/runs", headers=auth(admin)).json()[0]["result_summary"] == "OCR result"

    rejected_request = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-002"}).json()
    rejected = client.patch(
        f"/remote-runs/{rejected_request['id']}",
        headers=auth(user),
        json={"decision": "reject"},
    )
    assert rejected.json()["status"] == "rejected"


def test_device_can_poll_preauthorized_remote_run_until_execution_finishes(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post(
        "/remote-runs",
        headers=auth(admin),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    approved = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    ).json()

    polled = client.get("/devices/d-001/remote-runs", headers=auth(user))
    assert polled.status_code == 200
    assert len(polled.json()) == 1
    active_request = polled.json()[0]
    assert active_request["id"] == request["id"]
    assert active_request["task_id"] == "t-001"
    assert active_request["device_id"] == "d-001"
    assert active_request["status"] == "running"
    assert active_request["run_id"] == approved["run_id"]
    assert active_request["version"] == request["version"]

    completed = client.post(
        f"/runs/{approved['run_id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "完成"},
    )
    assert completed.status_code == 200
    assert client.get("/devices/d-001/remote-runs", headers=auth(user)).json() == []


def test_device_user_must_confirm_remote_runs_and_can_report_local_runs(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).json()
    forbidden = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(admin),
        json={"decision": "approve"},
    )
    assert forbidden.status_code == 403

    local = client.post(
        "/devices/d-001/runs",
        headers=auth(user),
        json={"task_id": "t-001", "version": "v1.4.0"},
    )
    assert local.status_code == 201
    result = client.post(
        f"/runs/{local.json()['id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "本地 OCR"},
    )
    assert result.status_code == 200
    assert client.post(
        f"/runs/{local.json()['id']}",
        headers=auth(admin),
        json={"status": "stopped"},
    ).status_code == 403


def test_preauthorization_is_limited_to_device_and_version_and_revocable(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    first = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).json()
    approved = client.patch(
        f"/remote-runs/{first['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    ).json()
    grants = client.get("/run-authorizations", headers=auth(user)).json()
    assert len(grants) == 1
    assert grants[0]["device_id"] == "d-001"
    next_run = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).json()
    assert next_run["status"] == "running"
    other_device = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-002"}).json()
    assert other_device["status"] == "pending"

    revoked = client.delete(f"/run-authorizations/{grants[0]['id']}", headers=auth(user))
    assert revoked.status_code == 200
    after_revoke = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).json()
    assert after_revoke["status"] == "pending"
    assert client.delete(f"/run-authorizations/{grants[0]['id']}", headers=auth(user)).status_code == 404


def test_user_can_create_only_own_device_and_granted_version_authorization(client):
    user = login(client)
    response = client.post(
        "/run-authorizations",
        headers=auth(user),
        json={"task_id": "t-001", "version": "v1.4.0", "device_id": "d-001"},
    )
    assert response.status_code == 201
    assert response.json()["device_id"] == "d-001"
    assert client.post(
        "/run-authorizations",
        headers=auth(user),
        json={"task_id": "t-001", "version": "v1.4.0", "device_id": "d-003"},
    ).status_code == 404
    assert client.post(
        "/run-authorizations",
        headers=auth(user),
        json={"task_id": "t-001", "version": "v1.3.0", "device_id": "d-001"},
    ).status_code == 201
    assert client.post(
        "/run-authorizations",
        headers=auth(user),
        json={"task_id": "t-001", "version": "missing", "device_id": "d-001"},
    ).status_code == 404


def test_expired_requests_cannot_be_approved(client):
    import server.app as api

    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).json()
    with api._connect(client.app.state.db_path) as db:
        db.execute("UPDATE remote_runs SET expires_at=? WHERE id=?", (int(time.time()) - 1, request["id"]))
    assert client.get("/devices/d-001/remote-runs", headers=auth(user)).json() == []
    response = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    )
    assert response.status_code == 410


def test_disabling_a_device_revokes_its_preauthorizations_and_blocks_new_runs(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).json()
    client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    )
    assert client.get("/run-authorizations", headers=auth(user)).json()
    assert client.patch("/devices/d-001", headers=auth(admin), json={"status": "disabled"}).status_code == 200
    assert client.get("/run-authorizations", headers=auth(user)).json() == []
    assert client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).status_code == 409
    assert client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).status_code == 409


def test_disabling_user_revokes_grants_and_stops_active_remote_runs(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).json()
    started = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    ).json()
    assert client.patch("/users/u-1001", headers=auth(admin), json={"status": "disabled"}).status_code == 200
    assert client.get("/run-authorizations", headers=auth(admin)).json() == []
    admin_runs = client.get("/runs", headers=auth(admin)).json()
    assert next(run for run in admin_runs if run["id"] == started["run_id"])["status"] == "stopped"
    assert client.post("/remote-runs", headers=auth(admin), json={"task_id": "t-001", "device_id": "d-001"}).status_code == 409


def test_database_persists_between_app_restarts(tmp_path):
    database = tmp_path / "persistent.sqlite3"
    app = create_app(database, b"shared-secret-must-be-at-least-32-bytes")
    with TestClient(app) as first_client:
        admin = login(first_client, "admin@example.com", "admin-demo")
        response = first_client.post(
            "/users",
            headers=auth(admin),
            json={"name": "持久用户", "email": "persist@example.com", "password": "long-password"},
        )
        assert response.status_code == 201
    restarted = create_app(database, b"shared-secret-must-be-at-least-32-bytes")
    with TestClient(restarted) as second_client:
        assert second_client.post(
            "/auth/login",
            json={"email": "persist@example.com", "password": "long-password"},
        ).status_code == 200
