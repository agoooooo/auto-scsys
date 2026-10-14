import runpy
import unittest
from unittest.mock import patch


SCRIPT = "android/examples/douyin_login_assist.py"


class FakeBridge:
    def __init__(self, screens):
        self.screens = iter(screens)
        self.clicked = []
        self.logs = []

    def open_douyin(self):
        return True

    def wait_for_douyin(self, timeout_seconds):
        return timeout_seconds == 20

    def ocr(self):
        return next(self.screens)

    def click_text(self, text):
        self.clicked.append(text)
        return True

    def is_stopped(self):
        return False

    def log(self, message):
        self.logs.append(message)


class DouyinLoginAssistTests(unittest.TestCase):
    def run_script(self, screens):
        bridge = FakeBridge(screens)
        with patch("time.sleep"):
            result = runpy.run_path(SCRIPT, init_globals={"bridge": bridge})
        return result["result"], bridge

    def test_clicks_login_entry_and_stops_at_login_form(self):
        result, bridge = self.run_script(["推荐 立即登录", "请输入手机号 验证码登录"])

        self.assertEqual(bridge.clicked, ["立即登录"])
        self.assertIn("手动输入账号信息", result)

    def test_reports_when_login_form_does_not_appear(self):
        result, bridge = self.run_script(["立即登录"] + ["首页"] * 10)

        self.assertEqual(bridge.clicked, ["立即登录"])
        self.assertIn("未确认登录界面", result)

    def test_does_not_click_when_no_login_entry_is_visible(self):
        result, bridge = self.run_script(["首页 推荐 关注"])

        self.assertEqual(bridge.clicked, [])
        self.assertIn("未识别到抖音登录入口", result)

    def test_recognizes_an_already_open_login_form(self):
        result, bridge = self.run_script(["手机号登录 获取验证码"])

        self.assertEqual(bridge.clicked, [])
        self.assertIn("手动输入账号信息", result)


if __name__ == "__main__":
    unittest.main()
