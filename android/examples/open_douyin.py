if not bridge.open_douyin():
    raise RuntimeError("无法打开抖音")

if not bridge.wait_for_douyin(20):
    raise RuntimeError("等待抖音界面启动超时")

screen_text = bridge.ocr()
if not screen_text:
    raise RuntimeError("没有识别到抖音界面文字")

# Set this to one visible, clickable label if this task should tap it.
target_text = ""
if target_text and not bridge.click_text(target_text):
    raise RuntimeError(f"未找到可点击文字: {target_text}")

bridge.log(f"OCR completed: {len(screen_text)} characters")
result = f"已打开抖音并识别到 {len(screen_text)} 个字符"
