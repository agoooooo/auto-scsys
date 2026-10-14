import time


PROFILE_TAB_LABEL = "我"
LOGIN_FORM_MARKERS = ("登录后，体验完整功能", "请输入手机号", "验证并登录", "密码登录")
MAX_LOGIN_SCREEN_CHECKS = 10


def is_login_form_visible(text):
    return any(marker in text for marker in LOGIN_FORM_MARKERS)


if not bridge.open_douyin():
    raise RuntimeError("无法打开抖音")

if not bridge.wait_for_douyin(20):
    raise RuntimeError("等待抖音界面启动超时")

screen_text = bridge.ocr()
if not is_login_form_visible(screen_text):
    if not bridge.click_text_exact(PROFILE_TAB_LABEL):
        raise RuntimeError("未能打开抖音“我”页面；请确认抖音前台显示且无障碍服务已启用")

    for _ in range(MAX_LOGIN_SCREEN_CHECKS):
        if bridge.is_stopped():
            raise RuntimeError("任务已停止")
        time.sleep(1)
        screen_text = bridge.ocr()
        if is_login_form_visible(screen_text):
            break

if is_login_form_visible(screen_text):
    result = "已打开抖音登录页面。请在设备上手动输入手机号并完成验证。"
else:
    result = "已打开抖音“我”页面，但未识别到登录表单；请在设备上检查当前状态。"

bridge.log("Douyin login page navigation finished; credentials were not accessed")
