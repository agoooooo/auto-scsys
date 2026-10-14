import hashlib
import runpy
import time
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from server.app import _connect, _ensure_demo_douyin_login_task, create_app


SCRIPT_PATH = Path(__file__).parents[1] / "demo_scripts" / "douyin_login_assist.py"


class FakeBridge:
    def __init__(self, screen_texts, click_result=True):
        self.screen_texts = iter(screen_texts)
        self.clicked_texts = []
        self.click_result = click_result

    def open_douyin(self):
        return True

    def wait_for_douyin(self, timeout_seconds):
        return timeout_seconds == 20

    def ocr(self):
        return next(self.screen_texts)

    def click_text(self, text):
        self.clicked_texts.append(text)
        return self.click_result

    def click_text_exact(self, text):
        self.clicked_texts.append(text)
        return self.click_result

    def is_stopped(self):
        return False

    def log(self, _message):
        pass


def run_login_script(screen_texts, click_result=True):
    bridge = FakeBridge(screen_texts, click_result)
    with patch.object(time, "sleep"):
        namespace = runpy.run_path(str(SCRIPT_PATH), init_globals={"bridge": bridge})
    return namespace["result"], bridge


def test_login_script_opens_login_entry_and_stops_for_manual_authentication():
    result, bridge = run_login_script(
        ["首页 朋友 消息 我", "登录后，体验完整功能 请输入手机号 验证并登录"]
    )

    assert bridge.clicked_texts == ["我"]
    assert "手动输入手机号" in result


def test_login_script_does_not_click_when_login_form_is_already_open():
    result, bridge = run_login_script(["登录后，体验完整功能 请输入手机号 密码登录"])

    assert bridge.clicked_texts == []
    assert "手动输入手机号" in result


def test_login_script_reports_unconfirmed_login_form():
    result, bridge = run_login_script(["首页 朋友 消息 我"] + ["首页"] * 10)

    assert bridge.clicked_texts == ["我"]
    assert "未识别到登录表单" in result


def test_login_script_reports_profile_tab_click_failure():
    with pytest.raises(RuntimeError, match="未能打开抖音"):
        run_login_script(["首页 朋友 消息 我"], click_result=False)


def test_login_task_is_seeded_published_and_its_source_is_dispatchable(tmp_path):
    app = create_app(tmp_path / "demo.sqlite3", b"demo-secret-must-be-at-least-32-bytes")
    with TestClient(app) as client:
        login = client.post(
            "/auth/login",
            json={"email": "chen.xiao@example.com", "password": "user-demo"},
        )
        assert login.status_code == 200
        headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

        tasks = client.get("/tasks", headers=headers)
        assert tasks.status_code == 200
        task = next(item for item in tasks.json() if item["id"] == "t-003")
        assert task["status"] == "published"
        assert task["name"] == "抖音登录页面引导（手动认证）"
        assert task["active_version"] == "v1.0.2"
        assert set(task["capabilities"]) == {
            "屏幕捕获（需系统授权）",
            "OCR 文字识别",
            "无障碍点击（需系统授权）",
        }

        source = client.get(
            "/tasks/t-003/versions/v1.0.2/source",
            headers=headers,
        )
        assert source.status_code == 200
        assert source.content == SCRIPT_PATH.read_bytes()
        assert b"password" not in source.content.lower()

        dispatch = client.post(
            "/remote-runs",
            headers=headers,
            json={"task_id": "t-003", "device_id": "d-001"},
        )
        assert dispatch.status_code == 201
        assert dispatch.json()["status"] in {"pending", "queued"}


def test_douyin_task_upgrade_preserves_published_version_content(tmp_path):
    database_path = tmp_path / "demo.sqlite3"
    app = create_app(database_path, b"demo-secret-must-be-at-least-32-bytes")
    historical_source = b"# Previously published v1.0.1 source\n"

    with TestClient(app) as client:
        with _connect(database_path) as db:
            db.begin_immediate()
            db.execute(
                """INSERT INTO task_versions(id,task_id,version,filename,content,sha256,notes,created_at)
                   VALUES(?,?,?,?,?,?,?,?)""",
                (
                    "tv-legacy",
                    "t-003",
                    "v1.0.1",
                    "douyin_login_assist.py",
                    historical_source,
                    hashlib.sha256(historical_source).hexdigest(),
                    "此前发布的版本",
                    "2026-10-14T00:00:00Z",
                ),
            )
            db.execute("DELETE FROM task_versions WHERE task_id='t-003' AND version='v1.0.2'")
            db.execute("UPDATE tasks SET active_version='v1.0.1' WHERE id='t-003'")
            _ensure_demo_douyin_login_task(db)

        login = client.post(
            "/auth/login",
            json={"email": "chen.xiao@example.com", "password": "user-demo"},
        )
        headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
        old_source = client.get("/tasks/t-003/versions/v1.0.1/source", headers=headers)
        task = next(item for item in client.get("/tasks", headers=headers).json() if item["id"] == "t-003")
        assert old_source.content == historical_source
        assert task["active_version"] == "v1.0.2"
