import sqlite3

import pytest
from fastapi.testclient import TestClient

from server.app import create_app


@pytest.fixture
def client(tmp_path):
    app = create_app(tmp_path / "remote-sessions.sqlite3", b"remote-session-tests-secret-value")
    with TestClient(app) as test_client:
        yield test_client


def login(client, email="chen.xiao@example.com", password="user-demo"):
    response = client.post("/auth/login", json={"email": email, "password": password})
    assert response.status_code == 200
    return response.json()["access_token"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def open_session(client, token, device_id="d-001"):
    return client.post(
        "/remote-sessions",
        headers=auth(token),
        json={"device_id": device_id, "duration_seconds": 300},
    )


def accept_session(client, token, session_id):
    return client.post(
        f"/remote-sessions/{session_id}/decision",
        headers=auth(token),
        json={"decision": "accept"},
    )


def test_session_requires_device_consent_and_uses_scoped_ephemeral_tickets(client):
    owner = login(client)
    other_user = login(client, "zhou.ke@example.com")

    created = open_session(client, owner)
    assert created.status_code == 201
    session = created.json()
    session_id = session["id"]
    assert session["status"] == "pending"

    assert client.get(f"/devices/d-001/remote-sessions", headers=auth(owner)).json()[0]["id"] == session_id
    assert client.get(f"/remote-sessions/{session_id}", headers=auth(other_user)).status_code == 404
    assert client.post(
        f"/remote-sessions/{session_id}/controller-ticket",
        headers=auth(owner),
    ).status_code == 409

    accepted = accept_session(client, owner, session_id)
    assert accepted.status_code == 200
    assert accepted.json()["status"] == "active"
    device_ticket = accepted.json()["device_ticket"]
    assert len(device_ticket) >= 40

    controller_ticket_response = client.post(
        f"/remote-sessions/{session_id}/controller-ticket",
        headers=auth(owner),
    )
    assert controller_ticket_response.status_code == 200
    controller_ticket = controller_ticket_response.json()["ticket"]
    assert controller_ticket != device_ticket

    bad_ticket_frame = client.post(
        f"/remote-sessions/{session_id}/frame",
        headers={"Authorization": f"Bearer {owner}", "Content-Type": "image/jpeg"},
        content=b"\xff\xd8fake-jpeg",
    )
    assert bad_ticket_frame.status_code == 401

    frame = b"\xff\xd8flowpilot-test-frame"
    uploaded = client.post(
        f"/remote-sessions/{session_id}/frame",
        headers={"Authorization": f"Bearer {device_ticket}", "Content-Type": "image/jpeg"},
        content=frame,
    )
    assert uploaded.status_code == 204
    oversized = client.post(
        f"/remote-sessions/{session_id}/frame",
        headers={"Authorization": f"Bearer {device_ticket}", "Content-Type": "image/jpeg"},
        content=iter([b"x" * (1024 * 1024 + 1)]),
    )
    assert oversized.status_code == 413
    frame_response = client.get(
        f"/remote-sessions/{session_id}/frame?after_seq=0",
        headers={"Authorization": f"Bearer {controller_ticket}"},
    )
    assert frame_response.status_code == 200
    assert frame_response.content == frame
    assert frame_response.headers["cache-control"] == "no-store"
    assert client.get(
        f"/remote-sessions/{session_id}/frame?after_seq=1",
        headers={"Authorization": f"Bearer {controller_ticket}"},
    ).status_code == 204

    malformed_input = client.post(
        f"/remote-sessions/{session_id}/input",
        headers={"Authorization": f"Bearer {controller_ticket}"},
        json={"type": "tap"},
    )
    assert malformed_input.status_code == 422
    tap = client.post(
        f"/remote-sessions/{session_id}/input",
        headers={"Authorization": f"Bearer {controller_ticket}"},
        json={"type": "tap", "x": 0.25, "y": 0.75},
    )
    assert tap.status_code == 200
    swipe = client.post(
        f"/remote-sessions/{session_id}/input",
        headers={"Authorization": f"Bearer {controller_ticket}"},
        json={"type": "swipe", "x": 0.1, "y": 0.2, "end_x": 0.8, "end_y": 0.9, "duration_ms": 500},
    )
    assert swipe.status_code == 200
    assert client.post(
        f"/remote-sessions/{session_id}/input",
        headers={"Authorization": f"Bearer {controller_ticket}"},
        json={"type": "back"},
    ).status_code == 200
    commands = client.get(
        f"/remote-sessions/{session_id}/input?after_seq=0",
        headers={"Authorization": f"Bearer {device_ticket}"},
    ).json()["events"]
    assert [item["type"] for item in commands] == ["tap", "swipe", "back"]
    assert commands[0]["x"] == 0.25

    with sqlite3.connect(client.app.state.db_path) as db:
        tables = {
            row[0]
            for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
        }
        columns = {
            row[1]
            for row in db.execute("PRAGMA table_info(remote_sessions)").fetchall()
        }
    assert "remote_frames" not in tables
    assert not {"frame", "frame_bytes", "frame_data"} & columns
    assert frame not in client.app.state.db_path.read_bytes()

    ended = client.post(
        f"/remote-sessions/{session_id}/device-end",
        headers={"Authorization": f"Bearer {device_ticket}"},
    )
    assert ended.status_code == 200
    assert ended.json()["status"] == "ended"
    assert client.get(
        f"/remote-sessions/{session_id}/frame",
        headers={"Authorization": f"Bearer {controller_ticket}"},
    ).status_code == 409


def test_sessions_are_authorized_time_limited_and_exclusive_with_task_runs(client):
    owner = login(client)
    other_user = login(client, "zhou.ke@example.com")
    admin = login(client, "admin@example.com", "admin-demo")

    assert open_session(client, other_user).status_code == 404
    assert client.post(
        "/remote-sessions",
        headers=auth(owner),
        json={"device_id": "d-001", "duration_seconds": 30},
    ).status_code == 422
    assert open_session(client, admin, "d-003").status_code == 409

    session = open_session(client, owner).json()
    assert open_session(client, owner).status_code == 409
    assert client.post(
        "/remote-runs",
        headers=auth(owner),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).status_code == 409
    assert client.post(
        "/devices/d-001/runs",
        headers=auth(owner),
        json={"task_id": "t-001", "version": "v1.7.0"},
    ).status_code == 409

    assert client.post(
        f"/remote-sessions/{session['id']}/decision",
        headers=auth(other_user),
        json={"decision": "accept"},
    ).status_code == 404
    assert client.post(
        f"/remote-sessions/{session['id']}/decision",
        headers=auth(owner),
        json={"decision": "reject"},
    ).json()["status"] == "rejected"
    assert client.post(
        f"/remote-sessions/{session['id']}/decision",
        headers=auth(owner),
        json={"decision": "accept"},
    ).status_code == 409

    admin_session = open_session(client, admin).json()
    assert admin_session["requested_by"] == "u-admin"
    assert client.post(
        f"/remote-sessions/{admin_session['id']}/end",
        headers=auth(admin),
    ).json()["status"] == "ended"

    expires = open_session(client, owner).json()
    with sqlite3.connect(client.app.state.db_path) as db:
        db.execute("UPDATE remote_sessions SET expires_at=0 WHERE id=?", (expires["id"],))
    expired = client.get(f"/remote-sessions/{expires['id']}", headers=auth(owner))
    assert expired.status_code == 200
    assert expired.json()["status"] == "expired"


def test_device_revocation_ends_active_remote_session_and_audits_consent(client):
    owner = login(client)
    admin = login(client, "admin@example.com", "admin-demo")
    session = open_session(client, owner).json()
    accepted = accept_session(client, owner, session["id"]).json()
    device_ticket = accepted["device_ticket"]
    assert client.patch(
        "/devices/d-001",
        headers=auth(admin),
        json={"status": "disabled"},
    ).status_code == 200

    details = client.get(f"/remote-sessions/{session['id']}", headers=auth(owner))
    assert details.status_code == 200
    assert details.json()["status"] == "ended"
    assert client.post(
        f"/remote-sessions/{session['id']}/frame",
        headers={"Authorization": f"Bearer {device_ticket}", "Content-Type": "image/jpeg"},
        content=b"\xff\xd8frame",
    ).status_code == 409
    audit = client.get("/admin/audit", headers=auth(admin)).json()
    assert any(item["action"] == "remote_session.requested" and item["resource_id"] == session["id"] for item in audit)
    assert any(item["action"] == "remote_session.accepted" and item["resource_id"] == session["id"] for item in audit)
