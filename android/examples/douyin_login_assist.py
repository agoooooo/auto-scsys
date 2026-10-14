import time


LOGIN_LABELS = ("立即登录", "注册/登录", "登录")
LOGIN_FORM_MARKERS = ("手机号登录", "手机号", "验证码", "密码登录")
MAX_LOGIN_SCREEN_CHECKS = 10


def is_login_form_visible(text):
    return any(marker in text for marker in LOGIN_FORM_MARKERS)


if not bridge.open_douyin():
    raise RuntimeError("无法打开抖音")

if not bridge.wait_for_douyin(20):
    raise RuntimeError("等待抖音界面启动超时")

screen_text = bridge.ocr()
if is_login_form_visible(screen_text):
    result = "已打开抖音登录界面。请在设备上手动输入账号信息并完成登录验证。"
else:
    login_label = next((label for label in LOGIN_LABELS if label in screen_text), None)
    if login_label is None:
        result = "未识别到抖音登录入口。请在设备上检查当前页面并手动继续。"
    elif not bridge.click_text(login_label):
        raise RuntimeError("识别到登录入口，但无法点击；请确认无障碍服务已启用")
    else:
        login_form_visible = False
        for _ in range(MAX_LOGIN_SCREEN_CHECKS):
            if bridge.is_stopped():
                raise RuntimeError("任务已停止")
            time.sleep(1)
            screen_text = bridge.ocr()
            if is_login_form_visible(screen_text):
                login_form_visible = True
                break
        if login_form_visible:
            result = "已打开抖音登录界面。请在设备上手动输入账号信息并完成登录验证。"
        else:
            result = "已点击抖音登录入口，但未确认登录界面；请在设备上检查并手动继续。"

bridge.log("Douyin login assistance finished; credentials were not accessed")
