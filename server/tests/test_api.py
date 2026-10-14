import concurrent.futures
import hashlib
import os
import sqlite3
import stat
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

import server.app as server_app
from server.app import create_app, create_initial_admin


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


def password_recovery_client(tmp_path, monkeypatch, send_email=None):
    monkeypatch.setenv("FLOWPILOT_SMTP_HOST", "smtp.example.test")
    monkeypatch.setenv("FLOWPILOT_SMTP_USERNAME", "flowpilot")
    monkeypatch.setenv("FLOWPILOT_SMTP_PASSWORD", "smtp-test-password")
    monkeypatch.setenv("FLOWPILOT_SMTP_FROM", "noreply@example.test")
    app = create_app(tmp_path / "password-recovery.sqlite3", b"test-secret-must-be-at-least-32-bytes")
    if send_email is not None:
        monkeypatch.setattr(server_app, "_send_password_reset_email", send_email)
    return TestClient(app)


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


def test_public_registration_creates_only_a_normalized_ordinary_user(client):
    response = client.post(
        "/auth/register",
        json={
            "name": "  New User  ",
            "email": "  New.User@Example.COM  ",
            "password": "abc123",
            "role": "admin",
            "status": "disabled",
        },
    )
    assert response.status_code == 201
    assert response.json()["name"] == "New User"
    assert response.json()["email"] == "new.user@example.com"
    assert response.json()["role"] == "user"
    assert response.json()["status"] == "active"

    token = login(client, "NEW.USER@example.com", "abc123")
    profile = client.get("/me", headers=auth(token))
    assert profile.status_code == 200
    assert profile.json()["id"] == response.json()["id"]
    assert profile.json()["role"] == "user"
    assert client.get("/users", headers=auth(token)).status_code == 403


def test_user_can_update_own_profile_and_email_requires_password_confirmation(client):
    token = login(client)
    profile = client.patch(
        "/me",
        headers=auth(token),
        json={"name": "  Updated Name  "},
    )
    assert profile.status_code == 200
    assert profile.json()["name"] == "Updated Name"
    assert profile.json()["email"] == "chen.xiao@example.com"
    assert "joined_at" in profile.json()

    missing_confirmation = client.patch(
        "/me",
        headers=auth(token),
        json={"email": "updated@example.com"},
    )
    assert missing_confirmation.status_code == 403
    invalid_confirmation = client.patch(
        "/me",
        headers=auth(token),
        json={"email": "updated@example.com", "current_password": "wrong-password"},
    )
    assert invalid_confirmation.status_code == 403

    updated = client.patch(
        "/me",
        headers=auth(token),
        json={
            "email": "  UPDATED@example.com ",
            "current_password": "user-demo",
        },
    )
    assert updated.status_code == 200
    assert updated.json()["email"] == "updated@example.com"
    assert client.get("/me", headers=auth(token)).status_code == 200
    assert client.post(
        "/auth/login",
        json={"email": "updated@example.com", "password": "user-demo"},
    ).status_code == 200


def test_user_cannot_update_another_profile_or_claim_an_existing_email(client):
    user = login(client)
    other_user = login(client, "zhou.ke@example.com", "user-demo")
    assert client.patch(
        "/users/u-1002",
        headers=auth(user),
        json={"name": "Changed"},
    ).status_code == 403
    duplicate_email = client.patch(
        "/me",
        headers=auth(other_user),
        json={
            "email": "chen.xiao@example.com",
            "current_password": "user-demo",
        },
    )
    assert duplicate_email.status_code == 409
    assert client.patch("/me", headers=auth(user), json={"name": "  "}).status_code == 422


def test_password_change_verifies_current_password_and_revokes_other_sessions(client):
    first_session = login(client)
    second_session = login(client)
    wrong_current = client.post(
        "/auth/change-password",
        headers=auth(first_session),
        json={
            "current_password": "wrong-password",
            "new_password": "replacement-password-123",
        },
    )
    assert wrong_current.status_code == 403
    short_new_password = client.post(
        "/auth/change-password",
        headers=auth(first_session),
        json={"current_password": "user-demo", "new_password": "short"},
    )
    assert short_new_password.status_code == 422
    same_password = client.post(
        "/auth/change-password",
        headers=auth(first_session),
        json={"current_password": "user-demo", "new_password": "user-demo"},
    )
    assert same_password.status_code == 422
    changed = client.post(
        "/auth/change-password",
        headers=auth(first_session),
        json={
            "current_password": "user-demo",
            "new_password": "new123",
        },
    )
    assert changed.status_code == 200
    assert changed.json() == {"status": "changed", "other_sessions_revoked": True}
    assert client.get("/me", headers=auth(first_session)).status_code == 200
    assert client.get("/me", headers=auth(second_session)).status_code == 401
    assert client.post(
        "/auth/login",
        json={"email": "chen.xiao@example.com", "password": "new123"},
    ).status_code == 200
    admin = login(client, "admin@example.com", "admin-demo")
    assert any(
        event["action"] == "user.password_changed" and event["resource_id"] == "u-1001"
        for event in client.get("/admin/audit", headers=auth(admin)).json()
    )


def test_password_recovery_is_disabled_without_smtp_configuration(client):
    assert client.get("/auth/recovery-status").json() == {"enabled": False}
    request = client.post("/auth/forgot-password", json={"email": "chen.xiao@example.com"})
    reset = client.post(
        "/auth/reset-password",
        json={
            "email": "chen.xiao@example.com",
            "code": "123456",
            "new_password": "replacement-password-123",
        },
    )
    assert request.status_code == 503
    assert reset.status_code == 503


def test_smtp_configuration_is_all_or_nothing_and_validated(monkeypatch):
    names = (
        "FLOWPILOT_SMTP_HOST",
        "FLOWPILOT_SMTP_PORT",
        "FLOWPILOT_SMTP_USERNAME",
        "FLOWPILOT_SMTP_PASSWORD",
        "FLOWPILOT_SMTP_FROM",
        "FLOWPILOT_SMTP_USE_SSL",
    )
    for name in names:
        monkeypatch.delenv(name, raising=False)
    assert server_app._smtp_settings_from_env() is None

    monkeypatch.setenv("FLOWPILOT_SMTP_HOST", "smtp.example.test")
    with pytest.raises(ValueError, match="requires all"):
        server_app._smtp_settings_from_env()

    monkeypatch.setenv("FLOWPILOT_SMTP_USERNAME", "flowpilot")
    monkeypatch.setenv("FLOWPILOT_SMTP_PASSWORD", "smtp-test-password")
    monkeypatch.setenv("FLOWPILOT_SMTP_FROM", "noreply@example.test")
    monkeypatch.setenv("FLOWPILOT_SMTP_PORT", "465")
    monkeypatch.setenv("FLOWPILOT_SMTP_USE_SSL", "true")
    settings = server_app._smtp_settings_from_env()
    assert settings.port == 465
    assert settings.use_ssl is True

    monkeypatch.setenv("FLOWPILOT_SMTP_FROM", "invalid sender")
    with pytest.raises(ValueError, match="valid email address"):
        server_app._smtp_settings_from_env()
    monkeypatch.setenv("FLOWPILOT_SMTP_FROM", "noreply@example.test")
    monkeypatch.setenv("FLOWPILOT_SMTP_PORT", "invalid")
    with pytest.raises(ValueError, match="must be an integer"):
        server_app._smtp_settings_from_env()


def test_password_recovery_sends_generic_response_and_limits_requests(tmp_path, monkeypatch):
    messages = []
    with password_recovery_client(
        tmp_path,
        monkeypatch,
        lambda _settings, recipient, code: messages.append((recipient, code)),
    ) as recovery:
        assert recovery.get("/auth/recovery-status").json() == {"enabled": True}
        known = recovery.post("/auth/forgot-password", json={"email": "CHEN.XIAO@example.com"})
        unknown = recovery.post("/auth/forgot-password", json={"email": "unknown@example.com"})
        assert known.status_code == unknown.status_code == 202
        assert known.json() == unknown.json()
        assert len(messages) == 1
        assert messages[0][0] == "chen.xiao@example.com"
        assert len(messages[0][1]) == 6 and messages[0][1].isdigit()

        for _ in range(4):
            assert recovery.post(
                "/auth/forgot-password",
                json={"email": "chen.xiao@example.com"},
            ).status_code == 202
        limited = recovery.post(
            "/auth/forgot-password",
            json={"email": "chen.xiao@example.com"},
        )
        assert limited.status_code == 429
        assert "Retry-After" in limited.headers


def test_password_recovery_resets_password_once_and_revokes_all_sessions(tmp_path, monkeypatch):
    messages = []
    with password_recovery_client(
        tmp_path,
        monkeypatch,
        lambda _settings, recipient, code: messages.append((recipient, code)),
    ) as recovery:
        old_session = login(recovery)
        another_session = login(recovery)
        requested = recovery.post("/auth/forgot-password", json={"email": "chen.xiao@example.com"})
        assert requested.status_code == 202

        short_password = recovery.post(
            "/auth/reset-password",
            json={
                "email": "chen.xiao@example.com",
                "code": messages[0][1],
                "new_password": "short",
            },
        )
        assert short_password.status_code == 422

        invalid = recovery.post(
            "/auth/reset-password",
            json={
                "email": "chen.xiao@example.com",
                "code": "000000" if messages[0][1] != "000000" else "000001",
                "new_password": "new123",
            },
        )
        assert invalid.status_code == 400

        changed = recovery.post(
            "/auth/reset-password",
            json={
                "email": "chen.xiao@example.com",
                "code": messages[0][1],
                "new_password": "new123",
            },
        )
        assert changed.status_code == 200
        assert changed.json() == {"status": "changed", "sessions_revoked": True}
        assert recovery.get("/me", headers=auth(old_session)).status_code == 401
        assert recovery.get("/me", headers=auth(another_session)).status_code == 401
        assert recovery.post(
            "/auth/login",
            json={"email": "chen.xiao@example.com", "password": "user-demo"},
        ).status_code == 401
        assert recovery.post(
            "/auth/login",
            json={
                "email": "chen.xiao@example.com",
                "password": "new123",
            },
        ).status_code == 200
        reused = recovery.post(
            "/auth/reset-password",
            json={
                "email": "chen.xiao@example.com",
                "code": messages[0][1],
                "new_password": "another-replacement-password",
            },
        )
        assert reused.status_code == 400
        admin = login(recovery, "admin@example.com", "admin-demo")
        events = recovery.get("/admin/audit", headers=auth(admin)).json()
        assert any(
            event["action"] == "user.password_reset" and event["resource_id"] == "u-1001"
            for event in events
        )


def test_password_recovery_locks_code_after_five_incorrect_attempts(tmp_path, monkeypatch):
    messages = []
    with password_recovery_client(
        tmp_path,
        monkeypatch,
        lambda _settings, recipient, code: messages.append((recipient, code)),
    ) as recovery:
        assert recovery.post("/auth/forgot-password", json={"email": "chen.xiao@example.com"}).status_code == 202
        for _ in range(server_app.PASSWORD_RESET_MAX_ATTEMPTS):
            response = recovery.post(
                "/auth/reset-password",
                json={
                    "email": "chen.xiao@example.com",
                    "code": "000000" if messages[0][1] != "000000" else "000001",
                    "new_password": "replacement-password-123",
                },
            )
            assert response.status_code == 400
        assert recovery.post(
            "/auth/reset-password",
            json={
                "email": "chen.xiao@example.com",
                "code": messages[0][1],
                "new_password": "replacement-password-123",
            },
        ).status_code == 400


def test_password_recovery_rejects_expired_code(tmp_path, monkeypatch):
    messages = []
    with password_recovery_client(
        tmp_path,
        monkeypatch,
        lambda _settings, recipient, code: messages.append((recipient, code)),
    ) as recovery:
        assert recovery.post("/auth/forgot-password", json={"email": "chen.xiao@example.com"}).status_code == 202
        with sqlite3.connect(recovery.app.state.db_path) as db:
            db.execute(
                "UPDATE auth_password_reset_codes SET expires_at=?",
                (int(time.time()) - 1,),
            )
        expired = recovery.post(
            "/auth/reset-password",
            json={
                "email": "chen.xiao@example.com",
                "code": messages[0][1],
                "new_password": "replacement-password-123",
            },
        )
        assert expired.status_code == 400
        assert expired.json() == {"detail": "验证码无效或已过期"}


def test_password_recovery_mail_failure_invalidates_issued_code(tmp_path, monkeypatch):
    def fail_delivery(_settings, _recipient, _code):
        raise server_app.smtplib.SMTPException("mail server unavailable")

    with password_recovery_client(tmp_path, monkeypatch, fail_delivery) as recovery:
        failed_delivery = recovery.post(
            "/auth/forgot-password",
            json={"email": "chen.xiao@example.com"},
        )
        assert failed_delivery.status_code == 503
        assert recovery.post(
            "/auth/reset-password",
            json={
                "email": "chen.xiao@example.com",
                "code": "123456",
                "new_password": "replacement-password-123",
            },
        ).status_code == 400


def test_public_registration_rejects_invalid_and_duplicate_accounts(client):
    invalid_name = client.post(
        "/auth/register",
        json={"name": "  ", "email": "person@example.com", "password": "valid-registration-password"},
    )
    assert invalid_name.status_code == 422
    invalid_email = client.post(
        "/auth/register",
        json={"name": "Person", "email": "not-an-email", "password": "valid-registration-password"},
    )
    assert invalid_email.status_code == 422
    short_password = client.post(
        "/auth/register",
        json={"name": "Person", "email": "person@example.com", "password": "short"},
    )
    assert short_password.status_code == 422

    first = client.post(
        "/auth/register",
        json={"name": "Person", "email": "new@example.com", "password": "valid-registration-password"},
    )
    assert first.status_code == 201
    duplicate = client.post(
        "/auth/register",
        json={"name": "Other Person", "email": "NEW@example.com", "password": "another-valid-password"},
    )
    assert duplicate.status_code == 409


def test_logout_revokes_session_and_refresh_preserves_absolute_session(client):
    logged_in = client.post(
        "/auth/login",
        json={"email": "chen.xiao@example.com", "password": "user-demo"},
    )
    assert logged_in.status_code == 200
    original_token = logged_in.json()["access_token"]
    refreshed = client.post("/auth/refresh", headers=auth(original_token))
    assert refreshed.status_code == 200
    assert 0 < refreshed.json()["session_expires_in"] <= server_app.SESSION_TTL_SECONDS
    refreshed_token = refreshed.json()["access_token"]
    assert client.get("/me", headers=auth(refreshed_token)).status_code == 200

    assert client.post("/auth/logout", headers=auth(original_token)).status_code == 204
    assert client.get("/me", headers=auth(original_token)).status_code == 401
    assert client.get("/me", headers=auth(refreshed_token)).status_code == 401
    assert client.post("/auth/refresh", headers=auth(refreshed_token)).status_code == 401

    final_token = login(client)
    session_id = server_app._token_decode(final_token, client.app.state.token_secret)["sid"]
    with sqlite3.connect(client.app.state.db_path) as db:
        db.execute("UPDATE auth_sessions SET expires_at=? WHERE id=?", (int(time.time()) - 1, session_id))
    assert client.get("/me", headers=auth(final_token)).status_code == 401
    assert client.post("/auth/refresh", headers=auth(final_token)).status_code == 401


def test_admin_can_revoke_user_sessions_and_disabling_user_revokes_sessions(client):
    admin = login(client, "admin@example.com", "admin-demo")
    first_session_token = login(client)
    revoked = client.post(
        "/users/u-1001/sessions/revoke",
        headers=auth(admin),
    )
    assert revoked.status_code == 200
    assert revoked.json()["revoked_sessions"] == 1
    assert client.get("/me", headers=auth(first_session_token)).status_code == 401
    events = client.get("/admin/audit", headers=auth(admin)).json()
    assert any(
        event["action"] == "user.sessions_revoked" and event["resource_id"] == "u-1001"
        for event in events
    )

    second_session_token = login(client)
    disabled = client.patch(
        "/users/u-1001",
        headers=auth(admin),
        json={"status": "disabled"},
    )
    assert disabled.status_code == 200
    assert client.get("/me", headers=auth(second_session_token)).status_code == 401


def test_health_readiness_checks_database_and_liveness_stays_available(client, monkeypatch):
    assert client.get("/health/live").status_code == 200
    assert client.get("/health/live").json() == {"status": "ok"}
    assert client.get("/health/ready").status_code == 200
    assert client.get("/health").json() == {"status": "ok"}

    def unavailable_database(_):
        raise sqlite3.OperationalError("database is unavailable")

    monkeypatch.setattr(server_app, "_connect", unavailable_database)
    assert client.get("/health/live").status_code == 200
    unavailable = client.get("/health/ready")
    assert unavailable.status_code == 503
    assert unavailable.json() == {"detail": "database unavailable"}
    assert client.get("/health").status_code == 503


def test_login_throttles_failed_credentials_per_email_and_source_and_recovers(tmp_path):
    app = create_app(tmp_path / "login-limit.sqlite3", b"test-secret-must-be-at-least-32-bytes")
    with TestClient(app, client=("192.0.2.10", 50000)) as client:
        payload = {"email": "chen.xiao@example.com", "password": "wrong"}
        for _ in range(5):
            assert client.post("/auth/login", json=payload).status_code == 401

        limited = client.post(
            "/auth/login",
            json={"email": "CHEN.XIAO@EXAMPLE.COM", "password": "wrong"},
        )
        assert limited.status_code == 429
        assert 1 <= int(limited.headers["retry-after"]) <= 900

        other_email = client.post(
            "/auth/login",
            json={"email": "zhou.ke@example.com", "password": "wrong"},
        )
        assert other_email.status_code == 401

        with TestClient(app, client=("192.0.2.11", 50000)) as other_source:
            same_email_other_source = other_source.post("/auth/login", json=payload)
            assert same_email_other_source.status_code == 401

        with sqlite3.connect(app.state.db_path) as db:
            assert db.execute("SELECT COUNT(*) FROM auth_login_attempts").fetchone()[0] == 5
            db.execute(
                "UPDATE auth_login_attempts SET window_started=?",
                (int(time.time()) - 900,),
            )

        recovered = client.post(
            "/auth/login",
            json={"email": "chen.xiao@example.com", "password": "user-demo"},
        )
        assert recovered.status_code == 200
        with sqlite3.connect(app.state.db_path) as db:
            assert db.execute("SELECT COUNT(*) FROM auth_login_attempts").fetchone()[0] == 4


def test_login_source_limit_cannot_be_evaded_by_rotating_emails(client):
    for index in range(30):
        response = client.post(
            "/auth/login",
            json={"email": f"unknown-{index}@example.com", "password": "wrong"},
        )
        assert response.status_code == 401

    limited = client.post(
        "/auth/login",
        json={"email": "another-unknown@example.com", "password": "wrong"},
    )
    assert limited.status_code == 429


def test_valid_credentials_are_not_locked_out_by_account_or_source_failure_limits(tmp_path):
    app = create_app(tmp_path / "login-lockout.sqlite3", b"test-secret-must-be-at-least-32-bytes")
    with TestClient(app, client=("192.0.2.20", 50000)) as client:
        for _ in range(5):
            assert client.post(
                "/auth/login",
                json={"email": "chen.xiao@example.com", "password": "wrong"},
            ).status_code == 401

        valid_account = client.post(
            "/auth/login",
            json={"email": "chen.xiao@example.com", "password": "user-demo"},
        )
        assert valid_account.status_code == 200
        assert client.post(
            "/auth/login",
            json={"email": "chen.xiao@example.com", "password": "wrong"},
        ).status_code == 401

        with TestClient(app, client=("192.0.2.21", 50000)) as source_limited_client:
            for index in range(30):
                assert source_limited_client.post(
                    "/auth/login",
                    json={"email": f"other-{index}@example.com", "password": "wrong"},
                ).status_code == 401

            valid_source = source_limited_client.post(
                "/auth/login",
                json={"email": "zhou.ke@example.com", "password": "user-demo"},
            )
            assert valid_source.status_code == 200
            assert source_limited_client.post(
                "/auth/login",
                json={"email": "last-unknown@example.com", "password": "wrong"},
            ).status_code == 429


def test_successful_login_clears_prior_failures(client):
    payload = {"email": "chen.xiao@example.com", "password": "wrong"}
    for _ in range(4):
        assert client.post("/auth/login", json=payload).status_code == 401
    assert client.post(
        "/auth/login",
        json={"email": "chen.xiao@example.com", "password": "user-demo"},
    ).status_code == 200
    for _ in range(5):
        assert client.post("/auth/login", json=payload).status_code == 401
    assert client.post("/auth/login", json=payload).status_code == 429


def test_admin_audit_contains_key_actions_without_sensitive_account_data(client):
    admin = login(client, "admin@example.com", "admin-demo")
    short_password = client.post(
        "/users",
        headers=auth(admin),
        json={"name": "短密码用户", "email": "short-password@example.com", "password": "short"},
    )
    assert short_password.status_code == 422

    created = client.post(
        "/users",
        headers=auth(admin),
        json={"name": "审计用户", "email": "audit@example.com", "password": "abc123"},
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
                "Access-Control-Request-Headers": "content-type,idempotency-key",
            },
        )
        assert allowed.status_code == 200
        assert allowed.headers["access-control-allow-origin"] == "http://192.168.0.101:8000"
        assert "idempotency-key" in allowed.headers["access-control-allow-headers"].lower()
        rejected = test_client.options(
            "/auth/login",
            headers={
                "Origin": "http://192.168.1.21:8000",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        assert "access-control-allow-origin" not in rejected.headers


def test_production_mode_requires_explicit_persistent_config(tmp_path, monkeypatch):
    monkeypatch.setenv("FLOWPILOT_ENV", "production")
    monkeypatch.delenv("FLOWPILOT_DB_PATH", raising=False)
    monkeypatch.delenv("FLOWPILOT_TOKEN_SECRET", raising=False)
    monkeypatch.delenv("FLOWPILOT_CORS_ORIGINS", raising=False)
    with pytest.raises(ValueError, match="FLOWPILOT_DB_PATH"):
        create_app()

    monkeypatch.setenv("FLOWPILOT_DB_PATH", str(tmp_path / "production.sqlite3"))
    with pytest.raises(ValueError, match="FLOWPILOT_TOKEN_SECRET"):
        create_app()

    monkeypatch.setenv("FLOWPILOT_TOKEN_SECRET", "production-secret-that-is-long-enough")
    with pytest.raises(ValueError, match="FLOWPILOT_CORS_ORIGINS"):
        create_app()

    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "http://console.example.com")
    with pytest.raises(ValueError, match="exact HTTPS origins"):
        create_app()
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "https://console.example.com/admin")
    with pytest.raises(ValueError, match="exact HTTPS origins"):
        create_app()
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "https://console.example.com")
    with pytest.raises(ValueError, match="absolute persistent path"):
        create_app("relative.sqlite3")


def test_production_postgresql_requires_s3_script_storage(monkeypatch):
    monkeypatch.setenv("FLOWPILOT_ENV", "production")
    monkeypatch.setenv("FLOWPILOT_TOKEN_SECRET", "production-secret-that-is-long-enough")
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "https://console.example.com")
    for key in (
        "FLOWPILOT_S3_BUCKET",
        "FLOWPILOT_S3_ENDPOINT_URL",
        "FLOWPILOT_S3_PUBLIC_ENDPOINT_URL",
        "FLOWPILOT_S3_ACCESS_KEY",
        "FLOWPILOT_S3_SECRET_KEY",
    ):
        monkeypatch.delenv(key, raising=False)
    with pytest.raises(ValueError, match="requires an S3-compatible"):
        create_app("postgresql://user:password@db/flowpilot")


def test_staging_requires_postgresql_storage_and_exact_private_http_origins(monkeypatch):
    monkeypatch.setenv("FLOWPILOT_ENV", "staging")
    monkeypatch.setenv("FLOWPILOT_DATABASE_URL", "postgresql://user:pass@db/flowpilot")
    monkeypatch.setenv("FLOWPILOT_TOKEN_SECRET", "staging-secret-that-is-long-enough-123")
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "http://127.0.0.1:18000")
    monkeypatch.setenv("FLOWPILOT_S3_BUCKET", "flowpilot-staging")
    monkeypatch.setenv("FLOWPILOT_S3_ENDPOINT_URL", "http://minio:9000")
    monkeypatch.setenv("FLOWPILOT_S3_PUBLIC_ENDPOINT_URL", "http://127.0.0.1:19000")
    monkeypatch.setenv("FLOWPILOT_S3_ACCESS_KEY", "staging-access")
    monkeypatch.setenv("FLOWPILOT_S3_SECRET_KEY", "staging-secret")

    app = create_app()
    assert app.state.environment == "staging"
    assert app.openapi_url is None
    cors = next(middleware for middleware in app.user_middleware if middleware.cls.__name__ == "CORSMiddleware")
    assert cors.kwargs["allow_origins"] == ["http://127.0.0.1:18000"]
    seeded = {}

    def initialize_database(_database, *, seed_demo):
        seeded["seed_demo"] = seed_demo

    @contextmanager
    def connect(_database):
        database = sqlite3.connect(":memory:")
        database.execute("CREATE TABLE users (email TEXT)")
        try:
            yield database
        finally:
            database.close()

    monkeypatch.setattr(server_app, "_initialize_database", initialize_database)
    monkeypatch.setattr(server_app, "_connect", connect)
    app.state.script_storage.check_ready = lambda: None
    with TestClient(app) as staging_client:
        assert staging_client.get("/health").json() == {"status": "ok"}
        assert staging_client.get("/docs").status_code == 404
        allowed = staging_client.options(
            "/auth/login",
            headers={
                "Origin": "http://127.0.0.1:18000",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        assert allowed.headers["access-control-allow-origin"] == "http://127.0.0.1:18000"
    assert seeded == {"seed_demo": False}

    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "http://127.0.0.1:18000/path")
    with pytest.raises(ValueError, match=r"exact HTTP\(S\) origins"):
        create_app()
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "*")
    with pytest.raises(ValueError, match=r"exact HTTP\(S\) origins"):
        create_app()


def test_staging_requires_postgresql_and_s3(monkeypatch):
    monkeypatch.setenv("FLOWPILOT_ENV", "staging")
    monkeypatch.setenv("FLOWPILOT_TOKEN_SECRET", "staging-secret-that-is-long-enough-123")
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "http://127.0.0.1:18000")
    for key in (
        "FLOWPILOT_DATABASE_URL",
        "FLOWPILOT_DB_PATH",
        "FLOWPILOT_S3_BUCKET",
        "FLOWPILOT_S3_ENDPOINT_URL",
        "FLOWPILOT_S3_PUBLIC_ENDPOINT_URL",
        "FLOWPILOT_S3_ACCESS_KEY",
        "FLOWPILOT_S3_SECRET_KEY",
    ):
        monkeypatch.delenv(key, raising=False)

    with pytest.raises(ValueError, match="PostgreSQL FLOWPILOT_DATABASE_URL"):
        create_app()

    with pytest.raises(ValueError, match="PostgreSQL FLOWPILOT_DATABASE_URL"):
        create_app("/tmp/staging.sqlite3")

    monkeypatch.setenv("FLOWPILOT_DATABASE_URL", "postgresql://user:pass@db/flowpilot")
    with pytest.raises(ValueError, match="requires an S3-compatible"):
        create_app()


def test_production_mode_disables_demo_seed_and_api_docs(tmp_path, monkeypatch):
    database = tmp_path / "production.sqlite3"
    monkeypatch.setenv("FLOWPILOT_ENV", "production")
    monkeypatch.setenv("FLOWPILOT_DB_PATH", str(database))
    monkeypatch.setenv("FLOWPILOT_TOKEN_SECRET", "production-secret-that-is-long-enough")
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "https://console.example.com")
    app = create_app()
    with TestClient(app) as production_client:
        assert production_client.get("/health").json() == {"status": "ok"}
        assert production_client.get("/docs").status_code == 404
        assert production_client.get("/openapi.json").status_code == 404
        allowed = production_client.options(
            "/auth/login",
            headers={
                "Origin": "https://console.example.com",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        assert allowed.headers["access-control-allow-origin"] == "https://console.example.com"
        rejected = production_client.options(
            "/auth/login",
            headers={
                "Origin": "http://localhost:8000",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        assert "access-control-allow-origin" not in rejected.headers
        assert production_client.post(
            "/auth/login",
            json={"email": "admin@example.com", "password": "admin-demo"},
        ).status_code == 401


def test_production_mode_refuses_database_with_demo_accounts(tmp_path, monkeypatch):
    database = tmp_path / "seeded.sqlite3"
    with TestClient(create_app(database, b"test-secret-must-be-at-least-32-bytes")):
        pass
    monkeypatch.setenv("FLOWPILOT_ENV", "production")
    monkeypatch.setenv("FLOWPILOT_DB_PATH", str(database))
    monkeypatch.setenv("FLOWPILOT_TOKEN_SECRET", "production-secret-that-is-long-enough")
    monkeypatch.setenv("FLOWPILOT_CORS_ORIGINS", "https://console.example.com")
    with pytest.raises(RuntimeError, match="contains demo accounts"):
        with TestClient(create_app()):
            pass


def test_initial_admin_bootstrap_creates_one_admin_without_demo_accounts(tmp_path):
    database = tmp_path / "fresh.sqlite3"
    with pytest.raises(ValueError, match="6–256"):
        create_initial_admin(database, "Admin", "admin@example.com", "short")
    user_id = create_initial_admin(database, "Initial Admin", "admin@example.com", "adm1n!")
    assert user_id
    with TestClient(create_app(database, b"test-secret-must-be-at-least-32-bytes")) as client:
        token = login(client, "admin@example.com", "adm1n!")
        assert client.get("/users", headers=auth(token)).status_code == 200
        assert client.post(
            "/auth/login",
            json={"email": "chen.xiao@example.com", "password": "user-demo"},
        ).status_code == 401
    with pytest.raises(ValueError, match="already has users|已有用户"):
        create_initial_admin(database, "Other Admin", "other@example.com", "another-strong-password")
    assert stat.S_IMODE(os.stat(database).st_mode) == 0o600

    environment = os.environ.copy()
    environment["FLOWPILOT_DB_PATH"] = str(tmp_path / "cli.sqlite3")
    result = subprocess.run(
        [sys.executable, "-m", "server.bootstrap_admin"],
        input="CLI Admin\ncli-admin@example.com\na-strong-cli-password\na-strong-cli-password\n",
        text=True,
        capture_output=True,
        check=False,
        env=environment,
    )
    assert result.returncode == 0, result.stderr
    assert "cli-admin@example.com" in result.stdout
    assert "a-strong-cli-password" not in result.stdout + result.stderr


def test_concurrent_initial_admin_bootstraps_create_only_one_admin(tmp_path):
    database = tmp_path / "concurrent.sqlite3"

    def bootstrap(index):
        return create_initial_admin(
            database,
            f"Initial Admin {index}",
            f"admin{index}@example.com",
            "a-strong-production-password",
        )

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(bootstrap, index) for index in range(2)]
    results = [future.exception() for future in futures]

    assert sum(error is None for error in results) == 1
    assert sum(isinstance(error, ValueError) for error in results) == 1
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT COUNT(*) FROM users WHERE role='admin'").fetchone()[0] == 1
    assert stat.S_IMODE(os.stat(database).st_mode) == 0o600


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
        headers=auth(user),
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
    )["status"] == "offline"

    heartbeat = client.post(
        "/devices/d-001/heartbeat",
        headers=auth(user),
        json={"app_version": "2.4.1"},
    )
    assert heartbeat.status_code == 200
    assert heartbeat.json()["status"] == "online"
    device = next(
        device for device in client.get("/devices", headers=auth(admin)).json()
        if device["id"] == "d-001"
    )
    assert device["status"] == "online"
    assert device["app_version"] == "2.4.1"

    legacy_heartbeat = client.post("/devices/d-001/heartbeat", headers=auth(user))
    assert legacy_heartbeat.status_code == 200
    assert next(
        device for device in client.get("/devices", headers=auth(admin)).json()
        if device["id"] == "d-001"
    )["app_version"] == "2.4.1"
    assert client.post(
        "/devices/d-001/heartbeat",
        headers=auth(user),
        json={"app_version": "   "},
    ).status_code == 422

    request = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    )
    assert request.status_code == 201


def test_admin_reads_do_not_refresh_device_heartbeat(client):
    import server.app as api

    admin = login(client, "admin@example.com", "admin-demo")
    stale_activity = (datetime.now(timezone.utc) - timedelta(seconds=90)).isoformat()
    with api._connect(client.app.state.db_path) as db:
        db.execute("UPDATE devices SET last_active=? WHERE id='d-001'", (stale_activity,))

    requests = client.get("/devices/d-001/remote-runs", headers=auth(admin))
    assert requests.status_code == 200
    with api._connect(client.app.state.db_path) as db:
        last_active = db.execute(
            "SELECT last_active FROM devices WHERE id='d-001'"
        ).fetchone()["last_active"]
    assert last_active == stale_activity
    assert client.post("/devices/d-001/sync", headers=auth(admin)).status_code == 403
    assert client.post("/devices/d-001/heartbeat", headers=auth(admin)).status_code == 403
    assert client.post("/devices/d-001/heartbeat", headers=auth(admin)).status_code == 403
    with api._connect(client.app.state.db_path) as db:
        assert db.execute(
            "SELECT last_active FROM devices WHERE id='d-001'"
        ).fetchone()["last_active"] == stale_activity


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
    assigned = client.post(
        "/tasks/t-002/publications",
        headers=auth(admin),
        json={"device_id": "d-001", "version": "v1.0.0"},
    )
    user = login(client)
    assert client.post(
        f"/devices/d-001/publications/{assigned.json()['id']}/accept",
        headers=auth(user),
    ).status_code == 200
    downloaded = client.get(
        "/devices/d-001/tasks/t-002/versions/v1.0.0/content",
        headers=auth(user),
    )
    assert downloaded.content == content
    assert downloaded.headers["x-content-sha256"] == hashlib.sha256(content).hexdigest()
    ticket = client.get(
        "/devices/d-001/tasks/t-002/versions/v1.0.0/content-url",
        headers=auth(user),
    )
    assert ticket.status_code == 200
    assert ticket.headers["cache-control"] == "no-store"
    assert ticket.json()["url"] is None
    assert ticket.json()["sha256"] == hashlib.sha256(content).hexdigest()


def test_s3_script_storage_keeps_content_out_of_database_and_verifies_download_hash(client):
    class MemoryStorage:
        def __init__(self):
            self.objects = {}

        def put(self, key, content):
            self.objects[key] = content

        def get(self, key):
            return self.objects[key]

        def delete(self, key):
            self.objects.pop(key, None)

        def ensure_bucket(self):
            pass

        def check_ready(self):
            pass

    storage = MemoryStorage()
    client.app.state.script_storage = storage
    owner = login(client)
    task = client.post("/tasks", headers=auth(owner), json={"name": "Stored outside the database"}).json()
    content = b"print('object storage')\n"
    published = client.post(
        f"/tasks/{task['id']}/versions",
        headers=auth(owner),
        data={"version": "v1.0.0"},
        files={"script": ("object.py", content, "text/x-python")},
    )
    assert published.status_code == 201
    with server_app._connect(client.app.state.db_path) as db:
        version = db.execute(
            "SELECT content,storage_key,content_size FROM task_versions WHERE id=?",
            (published.json()["id"],),
        ).fetchone()
    assert version["content"] == b""
    assert version["storage_key"] in storage.objects
    assert version["content_size"] == len(content)

    downloaded = client.get(
        f"/devices/d-001/tasks/{task['id']}/versions/v1.0.0/content",
        headers=auth(owner),
    )
    assert downloaded.status_code == 200
    assert downloaded.content == content
    assert downloaded.headers["x-content-sha256"] == hashlib.sha256(content).hexdigest()

    storage.objects[version["storage_key"]] = b"tampered"
    corrupt = client.get(
        f"/devices/d-001/tasks/{task['id']}/versions/v1.0.0/content",
        headers=auth(owner),
    )
    assert corrupt.status_code == 500
    assert corrupt.json()["detail"] == "脚本存储校验失败"


def test_presigned_script_transfer_checks_owner_size_and_digest(client):
    class MemoryStorage:
        def __init__(self):
            self.objects = {}

        def put(self, key, content):
            self.objects[key] = content

        def get(self, key):
            return self.objects[key]

        def get_info(self, key):
            content = self.objects[key]
            return len(content), content

        def delete(self, key):
            self.objects.pop(key, None)

        def presign_upload(self, key, expires_seconds=300):
            self.signed_upload_key = key
            return f"memory://uploads/{key}", {"Content-Type": "text/x-python"}

        def presign_download(self, key, filename, expires_seconds=300):
            return f"memory://downloads/{key}"

        def ensure_bucket(self):
            pass

        def configure_cors(self, origins):
            pass

        def check_ready(self):
            pass

    storage = MemoryStorage()
    client.app.state.script_storage = storage
    owner = login(client)
    other_user = login(client, "zhou.ke@example.com", "user-demo")
    task = client.post("/tasks", headers=auth(owner), json={"name": "Presigned transfer"}).json()
    content = b"print('presigned')\n"
    digest = hashlib.sha256(content).hexdigest()
    request = {
        "version": "v1.0.0",
        "filename": "script.py",
        "content_size": len(content),
        "sha256": digest,
        "notes": "test upload",
    }
    denied = client.post(
        f"/tasks/{task['id']}/versions/upload-url",
        headers=auth(other_user),
        json=request,
    )
    assert denied.status_code == 404

    upload = client.post(
        f"/tasks/{task['id']}/versions/upload-url",
        headers=auth(owner),
        json=request,
    )
    assert upload.status_code == 201
    storage.objects[storage.signed_upload_key] = content
    completed = client.post(
        f"/tasks/{task['id']}/versions/complete-upload",
        headers=auth(owner),
        json={"upload_id": upload.json()["upload_id"]},
    )
    assert completed.status_code == 201
    assert completed.json()["sha256"] == digest
    ticket = client.get(
        f"/devices/d-001/tasks/{task['id']}/versions/v1.0.0/content-url",
        headers=auth(owner),
    )
    assert ticket.status_code == 200
    assert ticket.json()["url"] == f"memory://downloads/{storage.signed_upload_key}"
    assert ticket.json()["sha256"] == digest

    second = client.post(
        f"/tasks/{task['id']}/versions/upload-url",
        headers=auth(owner),
        json={**request, "version": "v1.1.0"},
    )
    storage.objects[storage.signed_upload_key] = b"x" * len(content)
    rejected = client.post(
        f"/tasks/{task['id']}/versions/complete-upload",
        headers=auth(owner),
        json={"upload_id": second.json()["upload_id"]},
    )
    assert rejected.status_code == 422
    assert rejected.json()["detail"] == "上传脚本 SHA-256 校验失败"
    with server_app._connect(client.app.state.db_path) as db:
        assert db.execute(
            "SELECT 1 FROM pending_script_uploads WHERE id=?",
            (second.json()["upload_id"],),
        ).fetchone() is None


def test_readiness_reports_unavailable_script_storage(client):
    from botocore.exceptions import ClientError

    class UnavailableStorage:
        def check_ready(self):
            raise ClientError({"Error": {"Code": "ServiceUnavailable"}}, "HeadBucket")

    client.app.state.script_storage = UnavailableStorage()
    unavailable = client.get("/health/ready")
    assert unavailable.status_code == 503
    assert unavailable.json()["detail"] == "script storage unavailable"


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
    publication = client.post(
        "/tasks/t-002/publications",
        headers=auth(admin),
        json={"device_id": "d-001", "version": "v1.0.0"},
    )
    assert publication.status_code == 201
    grant_list = client.get("/tasks/t-002/grants", headers=auth(admin))
    assert grant_list.status_code == 200
    assert {grant["id"]: grant["is_owner"] for grant in grant_list.json()} == {
        "u-admin": True,
        "u-1001": False,
    }
    assert {task["id"] for task in client.get("/tasks", headers=auth(chen)).json()} == {"t-001", "t-002"}
    pending_sync = client.post("/devices/d-001/sync", headers=auth(chen)).json()
    assert {item["id"] for item in pending_sync["tasks"]} == {"t-001", "t-002"}
    assert pending_sync["pending_publications"] == []
    assert client.post(
        "/devices/d-001/publications/not-this-device/accept",
        headers=auth(chen),
    ).status_code == 404
    assert publication.json()["status"] == "accepted"
    assert any(
        item["id"] == "t-002" and item["active_version"] == "v1.0.0"
        for item in pending_sync["tasks"]
    )
    assert any(
        item["id"] == "t-002"
        for item in client.post("/devices/d-002/sync", headers=auth(chen)).json()["tasks"]
    )
    assert client.get(
        "/devices/d-001/tasks/t-002/versions/v1.0.0/content",
        headers=auth(chen),
    ).content == content

    pending = client.post(
        "/remote-runs",
        headers=auth(chen),
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
        headers=auth(chen),
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


def test_granted_task_can_sync_download_and_run_without_device_publication(client):
    admin = login(client, "admin@example.com", "admin-demo")
    zhou = login(client, "zhou.ke@example.com", "user-demo")
    content = b"result = 'published to one device'\n"
    assert client.post(
        "/tasks/t-002/versions",
        headers=auth(admin),
        data={"version": "v1.0.0", "notes": "首发版本"},
        files={"script": ("shared.py", content)},
    ).status_code == 201
    assert client.post("/tasks/t-002/grants/u-1002", headers=auth(admin)).status_code == 201

    assert client.get("/tasks/t-002/publications", headers=auth(admin)).json() == []
    synced = client.post("/devices/d-003/sync", headers=auth(zhou)).json()
    assert [(item["id"], item["active_version"]) for item in synced["tasks"]] == [("t-002", "v1.0.0")]
    assert synced["pending_publications"] == []
    assert client.get(
        "/devices/d-003/tasks/t-002/versions/v1.0.0/content",
        headers=auth(zhou),
    ).content == content
    local_run = client.post(
        "/devices/d-003/runs",
        headers=auth(zhou),
        json={"task_id": "t-002", "version": "v1.0.0"},
    )
    assert local_run.status_code == 201
    assert client.post(
        "/devices/d-003/publications/not-this-device/accept",
        headers=auth(zhou),
    ).status_code == 404

    assert client.post(
        f"/runs/{local_run.json()['id']}",
        headers=auth(zhou),
        json={"status": "success", "result_summary": "ok"},
    ).status_code == 200

    remote = client.post(
        "/remote-runs",
        headers=auth(zhou),
        json={"task_id": "t-002", "device_id": "d-003"},
    )
    assert remote.status_code == 201
    assert remote.json()["status"] == "pending"

    publication = client.post(
        "/tasks/t-002/publications",
        headers=auth(admin),
        json={"device_id": "d-003", "version": "v1.0.0"},
    )
    assert publication.status_code == 201

    revoked = client.delete(
        f"/tasks/t-002/publications/{publication.json()['id']}",
        headers=auth(admin),
    )
    assert revoked.status_code == 200
    assert next(
        item for item in client.get("/devices/d-003/remote-runs", headers=auth(zhou)).json()
        if item["id"] == remote.json()["id"]
    )["status"] == "pending"
    assert client.get(
        "/devices/d-003/tasks/t-002/versions/v1.0.0/content",
        headers=auth(zhou),
    ).content == content
    assert client.post(
        "/remote-runs",
        headers=auth(zhou),
        json={"task_id": "t-002", "device_id": "d-003"},
    ).status_code == 201

    chen = login(client)
    unrelated_publication = client.post(
        "/tasks/t-002/publications",
        headers=auth(admin),
        json={"device_id": "d-001", "version": "v1.0.0"},
    )
    assert unrelated_publication.status_code == 201
    assert all(
        task["id"] != "t-002"
        for task in client.post("/devices/d-001/sync", headers=auth(chen)).json()["tasks"]
    )
    assert client.get(
        "/devices/d-001/tasks/t-002/versions/v1.0.0/content",
        headers=auth(chen),
    ).status_code == 404
    assert client.get("/admin/audit", headers=auth(admin)).json()


def test_task_list_reports_viewer_scoped_distinct_assigned_device_counts(client):
    admin = login(client, "admin@example.com", "admin-demo")
    chen = login(client)

    for version in ("v1.0.0", "v1.1.0"):
        published = client.post(
            "/tasks/t-002/versions",
            headers=auth(admin),
            data={"version": version},
            files={"script": ("shared.py", b"pass\n")},
        )
        assert published.status_code == 201
    assert client.patch(
        "/tasks/t-002",
        headers=auth(admin),
        json={"status": "published"},
    ).status_code == 200
    assert client.post("/tasks/t-002/grants/u-1001", headers=auth(admin)).status_code == 201
    assert client.post("/tasks/t-002/grants/u-1002", headers=auth(admin)).status_code == 201

    publications = []
    for device_id, version in (
        ("d-001", "v1.0.0"),
        ("d-001", "v1.1.0"),
        ("d-002", "v1.0.0"),
        ("d-003", "v1.0.0"),
    ):
        response = client.post(
            "/tasks/t-002/publications",
            headers=auth(admin),
            json={"device_id": device_id, "version": version},
        )
        assert response.status_code == 201
        publications.append(response.json()["id"])
    assert client.delete(
        f"/tasks/t-002/publications/{publications[-1]}",
        headers=auth(admin),
    ).status_code == 200

    admin_tasks = {task["id"]: task for task in client.get("/tasks", headers=auth(admin)).json()}
    chen_tasks = {task["id"]: task for task in client.get("/tasks", headers=auth(chen)).json()}
    assert admin_tasks["t-002"]["assigned_device_count"] == 3
    assert chen_tasks["t-002"]["assigned_device_count"] == 2
    assert chen_tasks["t-001"]["assigned_device_count"] == 2

    assert client.patch(
        "/devices/d-002",
        headers=auth(admin),
        json={"status": "disabled"},
    ).status_code == 200
    admin_tasks = {task["id"]: task for task in client.get("/tasks", headers=auth(admin)).json()}
    chen_tasks = {task["id"]: task for task in client.get("/tasks", headers=auth(chen)).json()}
    assert admin_tasks["t-002"]["assigned_device_count"] == 2
    assert chen_tasks["t-002"]["assigned_device_count"] == 1
    assert chen_tasks["t-001"]["assigned_device_count"] == 1


def test_admin_can_pause_and_resume_tasks_with_access_and_run_controls(client):
    import server.app as api

    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    pending = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    approved = client.post(
        "/remote-runs",
        headers=auth(user),
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
        headers=auth(user),
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
        headers=auth(user),
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
        headers=auth(owner),
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
        headers=auth(owner),
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
        headers=auth(owner),
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


def test_seeded_script_versions_report_actual_content_sizes(client):
    user = login(client)
    versions = client.get("/tasks/t-001/versions", headers=auth(user))
    assert versions.status_code == 200
    by_version = {version["version"]: version for version in versions.json()}
    assert by_version["v1.7.0"]["size"] == server_app.DEMO_OCR_SCRIPT_PATH.stat().st_size
    assert by_version["v1.6.0"]["size"] > 0


def test_existing_database_migrates_active_version_and_authorization_scope(client):
    db_path = client.app.state.db_path
    with sqlite3.connect(db_path) as db:
        db.execute("ALTER TABLE tasks DROP COLUMN active_version")
        db.execute("ALTER TABLE run_authorizations DROP COLUMN all_versions")
        db.execute("DROP INDEX idx_remote_run_idempotency")
        db.execute("DROP INDEX idx_runs_one_active_per_device")
        db.execute("ALTER TABLE remote_runs DROP COLUMN idempotency_key")
        db.execute("ALTER TABLE remote_runs DROP COLUMN stop_requested_at")

    app = create_app(db_path, b"test-secret-must-be-at-least-32-bytes")
    with TestClient(app) as migrated_client:
        admin = login(migrated_client, "admin@example.com", "admin-demo")
        tasks = migrated_client.get("/tasks", headers=auth(admin)).json()
        task = next(task for task in tasks if task["id"] == "t-001")
        assert task["active_version"] == "v1.7.0"
        with sqlite3.connect(db_path) as db:
            authorization_columns = {
                row[1] for row in db.execute("PRAGMA table_info(run_authorizations)")
            }
            remote_run_columns = {
                row[1] for row in db.execute("PRAGMA table_info(remote_runs)")
            }
            index_names = {
                row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='index'")
            }
        assert "all_versions" in authorization_columns
        assert "stop_requested_at" in remote_run_columns
        assert "idempotency_key" in remote_run_columns
        assert "idx_runs_one_active_per_device" in index_names
        assert "idx_remote_run_idempotency" in index_names


def test_remote_run_confirmation_rejection_and_run_record(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    created = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"})
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
        json={"status": "failure", "result_summary": "设备未安装抖音或抖音极速版"},
    )
    assert completed.status_code == 200
    assert client.get("/runs", headers=auth(admin)).json()[0]["result_summary"] == "设备未安装抖音或抖音极速版"
    status = client.get(f"/remote-runs/{request_id}", headers=auth(user))
    assert status.status_code == 200
    assert status.json()["status"] == "failure"
    assert status.json()["run_status"] == "failure"
    assert status.json()["result_summary"] == "设备未安装抖音或抖音极速版"
    other_user = login(client, "zhou.ke@example.com", "user-demo")
    assert client.get(f"/remote-runs/{request_id}", headers=auth(other_user)).status_code == 404

    rejected_request = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-002"}).json()
    rejected = client.patch(
        f"/remote-runs/{rejected_request['id']}",
        headers=auth(user),
        json={"decision": "reject"},
    )
    assert rejected.json()["status"] == "rejected"


def test_only_users_can_request_remote_runs_for_their_own_devices(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    other_user = login(client, "zhou.ke@example.com", "user-demo")
    body = {"task_id": "t-001", "device_id": "d-001"}

    assert client.post("/remote-runs", headers=auth(admin), json=body).status_code == 403
    assert client.post("/remote-runs", headers=auth(other_user), json=body).status_code == 404

    requested = client.post("/remote-runs", headers=auth(user), json=body)
    assert requested.status_code == 201
    assert requested.json()["device_id"] == "d-001"
    assert requested.json()["status"] == "pending"


def test_device_can_poll_remote_run_until_execution_finishes(client):
    user = login(client)
    request = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    waiting = client.get("/devices/d-001/remote-runs", headers=auth(user)).json()[0]
    assert waiting["status"] == "pending"
    assert waiting["run_id"] is None
    approved = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).json()
    assert client.get("/runs", headers=auth(user)).json()[0]["remote_run_id"] == request["id"]

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


def test_remote_run_stop_request_is_authorized_idempotent_and_visible_to_device(client):
    user = login(client)
    admin = login(client, "admin@example.com", "admin-demo")
    other_user = login(client, "zhou.ke@example.com", "user-demo")
    request = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    run_id = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).json()["run_id"]

    path = f"/remote-runs/{request['id']}/stop"
    assert client.post(path, headers=auth(other_user)).status_code == 404
    assert client.post(path, headers=auth(admin)).status_code == 403

    stopped = client.post(path, headers=auth(user))
    assert stopped.status_code == 200
    assert stopped.json() == {
        "id": request["id"],
        "status": "running",
        "stop_requested": True,
        "already_requested": False,
    }
    repeated = client.post(path, headers=auth(user))
    assert repeated.status_code == 200
    assert repeated.json()["already_requested"] is True

    polled = client.get("/devices/d-001/remote-runs", headers=auth(user))
    assert polled.status_code == 200
    assert polled.json()[0]["stop_requested"] is True
    status = client.get(f"/remote-runs/{request['id']}", headers=auth(user))
    assert status.json()["stop_requested"] is True
    events = client.get("/admin/audit", headers=auth(admin)).json()
    assert sum(
        event["action"] == "remote_run.stop_requested" and event["resource_id"] == request["id"]
        for event in events
    ) == 1

    completed = client.post(
        f"/runs/{run_id}",
        headers=auth(user),
        json={"status": "stopped", "result_summary": "用户停止了任务"},
    )
    assert completed.status_code == 200
    assert client.get(f"/remote-runs/{request['id']}", headers=auth(user)).json()["stop_requested"] is False
    assert client.post(path, headers=auth(user)).status_code == 409


def test_remote_run_cannot_be_stopped_before_device_accepts_it(client):
    user = login(client)
    request = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    response = client.post(f"/remote-runs/{request['id']}/stop", headers=auth(user))
    assert response.status_code == 409


def test_device_user_must_confirm_remote_runs_and_can_report_local_runs(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).json()
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


def test_remote_requests_queue_behind_active_work_but_only_one_run_executes(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    first = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    queued = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    assert queued["status"] == "queued"
    assert queued["expires_at"] == 2**63 - 1
    assert client.get(f"/remote-runs/{queued['id']}", headers=auth(user)).json()["status"] == "queued"
    polled_before_start = client.get("/devices/d-001/remote-runs", headers=auth(user)).json()
    assert [request["id"] for request in polled_before_start if request["status"] == "pending"] == [first["id"]]

    started = client.patch(
        f"/remote-runs/{first['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    )
    assert started.status_code == 200
    queued_while_running = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    )
    assert queued_while_running.status_code == 201
    assert queued_while_running.json()["status"] == "queued"
    assert client.get(f"/remote-runs/{queued_while_running.json()['id']}", headers=auth(user)).json()["status"] == "queued"
    assert client.post(
        "/devices/d-001/runs",
        headers=auth(user),
        json={"task_id": "t-001", "version": "v1.7.0"},
    ).status_code == 409
    assert client.patch(
        f"/remote-runs/{queued['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    ).status_code == 409

    assert client.post(
        f"/runs/{started.json()['run_id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "完成"},
    ).status_code == 200
    ready = client.get("/devices/d-001/remote-runs", headers=auth(user)).json()
    assert [request["id"] for request in ready if request["status"] == "pending"] == [queued["id"]]
    assert next(request for request in ready if request["id"] == queued["id"])["expires_at"] < 2**63 - 1
    second_started = client.patch(
        f"/remote-runs/{queued['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    )
    assert second_started.status_code == 200
    assert client.patch(
        f"/remote-runs/{queued_while_running.json()['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).status_code == 409
    assert client.post(
        f"/runs/{second_started.json()['run_id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "完成"},
    ).status_code == 200
    next_request = client.get("/devices/d-001/remote-runs", headers=auth(user)).json()
    assert [request["id"] for request in next_request if request["status"] == "pending"] == [
        queued_while_running.json()["id"],
    ]


def test_remote_run_idempotency_key_replays_and_rejects_conflicting_payload(client):
    user = login(client)
    headers = {**auth(user), "Idempotency-Key": "dispatch-request-001"}
    first = client.post(
        "/remote-runs",
        headers=headers,
        json={"task_id": "t-001", "device_id": "d-001"},
    )
    replay = client.post(
        "/remote-runs",
        headers=headers,
        json={"task_id": "t-001", "device_id": "d-001"},
    )

    assert first.status_code == replay.status_code == 201
    assert first.json()["id"] == replay.json()["id"]
    assert first.json()["idempotent_replay"] is False
    assert replay.json()["idempotent_replay"] is True
    with sqlite3.connect(client.app.state.db_path) as db:
        assert db.execute(
            "SELECT COUNT(*) FROM remote_runs WHERE idempotency_key=?",
            ("dispatch-request-001",),
        ).fetchone()[0] == 1

    conflict = client.post(
        "/remote-runs",
        headers=headers,
        json={"task_id": "t-999", "device_id": "d-001"},
    )
    assert conflict.status_code == 409


def test_concurrent_remote_run_retries_with_same_key_create_one_request(client):
    user = login(client)
    headers = {**auth(user), "Idempotency-Key": "concurrent-dispatch-001"}
    barrier = threading.Barrier(2)

    def submit():
        barrier.wait()
        return client.post(
            "/remote-runs",
            headers=headers,
            json={"task_id": "t-001", "device_id": "d-001"},
        )

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(lambda _: submit(), range(2)))

    assert [response.status_code for response in responses] == [201, 201]
    assert len({response.json()["id"] for response in responses}) == 1
    assert sorted(response.json()["idempotent_replay"] for response in responses) == [False, True]


def test_concurrent_approvals_start_only_one_run_for_a_device(client):
    user = login(client)
    requests = [
        client.post(
            "/remote-runs",
            headers=auth(user),
            json={"task_id": "t-001", "device_id": "d-001"},
        ).json()
        for _ in range(2)
    ]
    barrier = threading.Barrier(2)

    def approve(request):
        barrier.wait()
        return client.patch(
            f"/remote-runs/{request['id']}",
            headers=auth(user),
            json={"decision": "approve"},
        )

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(approve, requests))

    assert sorted(response.status_code for response in responses) == [200, 409]
    with sqlite3.connect(client.app.state.db_path) as db:
        assert db.execute(
            "SELECT COUNT(*) FROM runs WHERE device_id='d-001' AND status='running'"
        ).fetchone()[0] == 1


def test_legacy_preauthorization_does_not_auto_start_current_remote_runs(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    first = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).json()
    approved = client.patch(
        f"/remote-runs/{first['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    ).json()
    grants = client.get("/run-authorizations", headers=auth(user)).json()
    assert len(grants) == 1
    assert grants[0]["device_id"] == "d-001"
    assert grants[0]["version"] == "v1.7.0"
    assert grants[0]["all_versions"] is True
    assert client.post(
        f"/runs/{approved['run_id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "完成"},
    ).status_code == 200
    next_run = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).json()
    assert next_run["status"] == "pending"
    assert next_run["run_id"] is None
    assert client.get("/run-authorizations", headers=auth(user)).json()
    next_approved = client.patch(
        f"/remote-runs/{next_run['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).json()
    assert client.post(
        f"/runs/{next_approved['run_id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "完成"},
    ).status_code == 200
    other_device = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-002"}).json()
    assert other_device["status"] == "pending"

    published = client.post(
        "/tasks/t-001/versions",
        headers=auth(admin),
        data={"version": "v1.8.0"},
        files={"script": ("orders.py", b"result = 'future release'")},
    )
    assert published.status_code == 201
    future_run = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    ).json()
    assert future_run["status"] == "pending"
    future_approved = client.patch(
        f"/remote-runs/{future_run['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).json()

    assert client.post(
        f"/runs/{future_approved['run_id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "完成"},
    ).status_code == 200
    revoked = client.delete(f"/run-authorizations/{grants[0]['id']}", headers=auth(user))
    assert revoked.status_code == 200
    after_revoke = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).json()
    assert after_revoke["status"] == "pending"
    assert client.delete(f"/run-authorizations/{grants[0]['id']}", headers=auth(user)).status_code == 404


def test_legacy_all_version_preauthorization_does_not_bypass_device_approval(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    created = client.post(
        "/run-authorizations",
        headers=auth(user),
        json={
            "task_id": "t-001",
            "version": "v1.7.0",
            "device_id": "d-001",
            "all_versions": True,
        },
    )
    assert created.status_code == 201
    assert created.json()["all_versions"] is True
    assert created.json()["version"] == "v1.7.0"

    published = client.post(
        "/tasks/t-001/versions",
        headers=auth(admin),
        data={"version": "v1.8.0"},
        files={"script": ("orders.py", b"result = 'future release'")},
    )
    assert published.status_code == 201
    remote_run = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    )
    assert remote_run.status_code == 201
    assert remote_run.json()["version"] == "v1.8.0"
    assert remote_run.json()["status"] == "pending"
    approved = client.patch(
        f"/remote-runs/{remote_run.json()['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    ).json()
    assert client.post(
        f"/runs/{approved['run_id']}",
        headers=auth(user),
        json={"status": "success", "result_summary": "完成"},
    ).status_code == 200

    assert client.patch(
        "/tasks/t-001/versions/v1.6.0/activate",
        headers=auth(admin),
    ).status_code == 200
    older_version = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    )
    assert older_version.status_code == 201
    assert older_version.json()["version"] == "v1.6.0"
    assert older_version.json()["status"] == "pending"

    grants = client.get("/run-authorizations", headers=auth(user)).json()
    grant = next(item for item in grants if item["all_versions"])
    assert client.delete(
        f"/run-authorizations/{grant['id']}",
        headers=auth(user),
    ).status_code == 200
    after_revoke = client.post(
        "/remote-runs",
        headers=auth(user),
        json={"task_id": "t-001", "device_id": "d-001"},
    )
    assert after_revoke.status_code == 201
    assert after_revoke.json()["status"] == "queued"


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

    user = login(client)
    request = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).json()
    with api._connect(client.app.state.db_path) as db:
        db.execute("UPDATE remote_runs SET expires_at=? WHERE id=?", (int(time.time()) - 1, request["id"]))
    polled = client.get("/devices/d-001/remote-runs", headers=auth(user))
    assert polled.status_code == 200
    assert [(item["id"], item["status"]) for item in polled.json()] == [(request["id"], "expired")]
    response = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve"},
    )
    assert response.status_code == 410


def test_disabling_a_device_revokes_its_preauthorizations_and_blocks_new_runs(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).json()
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
    assert client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).status_code == 409


def test_disabling_user_revokes_grants_and_stops_active_remote_runs(client):
    admin = login(client, "admin@example.com", "admin-demo")
    user = login(client)
    request = client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).json()
    started = client.patch(
        f"/remote-runs/{request['id']}",
        headers=auth(user),
        json={"decision": "approve", "preauthorize": True},
    ).json()
    assert client.patch("/users/u-1001", headers=auth(admin), json={"status": "disabled"}).status_code == 200
    assert client.get("/run-authorizations", headers=auth(admin)).json() == []
    admin_runs = client.get("/runs", headers=auth(admin)).json()
    assert next(run for run in admin_runs if run["id"] == started["run_id"])["status"] == "stopped"
    assert client.post("/remote-runs", headers=auth(user), json={"task_id": "t-001", "device_id": "d-001"}).status_code == 401


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
