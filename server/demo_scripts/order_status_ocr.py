ORDER_STATUS_LABELS = ("待支付", "待发货", "待收货", "已完成", "退款")

if not bridge.open_douyin():
    raise RuntimeError("无法打开抖音")

if not bridge.wait_for_douyin(20):
    raise RuntimeError("等待抖音界面启动超时")

screen_text = bridge.ocr()
if not screen_text.strip():
    raise RuntimeError("当前抖音界面未识别到文字")

matched_statuses = [label for label in ORDER_STATUS_LABELS if label in screen_text]
bridge.log(
    f"OCR completed; chars={len(screen_text)}; "
    f"order_status_matches={len(matched_statuses)}"
)
result = f"界面 OCR 完成；匹配到 {len(matched_statuses)} 项订单状态"
