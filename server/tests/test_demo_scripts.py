import runpy
import time
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from server.app import create_app


SCRIPT_PATH = Path(__file__).parents[1] / "demo_scripts" / "douyin_login_assist.py"


class FakeBridge:
    def __init__(self, screen_texts):
        self.screen_texts = iter(screen_texts)
        self.clicked_texts = []

    def open_douyin(self):
        return True

    def wait_for_douyin(self, timeout_seconds):
        return timeout_seconds == 20

    def ocr(self):
        return next(self.screen_texts)

    def click_text(self, text):
        self.clicked_texts.append(text)
        return True

    def is_stopped(self):
        return False

    def log(self, _message):
        pass


def run_login_script(screen_texts):
    bridge = FakeBridge(screen_texts)
    with patch.object(time, "sleep"):
        namespace = runpy.run_path(str(SCRIPT_PATH), init_globals={"bridge": bridge})
    return namespace["result"], bridge


def test_login_script_opens_login_entry_and_stops_for_manual_authentication():
    result, bridge = run_login_script(["推荐 立即登录", "请输入手机号 验证码登录"])

    assert bridge.clicked_texts == ["立即登录"]
    assert "手动输入账号信息" in result


def test_login_script_reports_missing_login_entry_without_clicking():
    result, bridge = run_login_script(["首页 推荐 关注"])

    assert bridge.clicked_texts == []
    assert "未识别到抖音登录入口" in result


def test_login_script_reports_unconfirmed_login_form():
    result, bridge = run_login_script(["立即登录"] + ["首页"] * 10)

    assert bridge.clicked_texts == ["立即登录"]
    assert "未确认登录界面" in result


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
        assert task["active_version"] == "v1.0.0"
        assert set(task["capabilities"]) == {
            "屏幕捕获（需系统授权）",
            "OCR 文字识别",
            "无障碍点击（需系统授权）",
        }

        source = client.get(
            "/tasks/t-003/versions/v1.0.0/source",
            headers=headers,
        )
        assert source.status_code == 200
        assert source.content == SCRIPT_PATH.read_bytes()

        dispatch = client.post(
            "/remote-runs",
            headers=headers,
            json={"task_id": "t-003", "device_id": "d-001"},
        )
        assert dispatch.status_code == 201
        assert dispatch.json()["status"] in {"pending", "queued"}
