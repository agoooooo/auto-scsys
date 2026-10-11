# FlowPilot Android 客户端

原生 Kotlin/Jetpack Compose 客户端可登录普通用户账号、绑定设备、同步任务版本、检查脚本 SHA-256、查看完整脚本后再明确确认运行，并显示运行结果和本地日志。Python 3.12 由 Chaquopy 在 App 进程内运行。演示后端仍只负责账号、授权、脚本分发和运行记录，不会执行 Python。

## 构建

- Android Studio（Android Gradle Plugin 8.7.3）
- JDK 17 或更新版本
- Android SDK Platform 35 和 Build Tools 35

在 Android Studio 中打开 `android/`，或执行：

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。当前配置为私有 APK，支持 `arm64-v8a` 真机和 `x86_64` 模拟器。

## 首次使用

1. 启动仓库的演示服务：`make dev`。模拟器 API 地址为 `http://10.0.2.2:8001`；真机需在可信任的同一局域网，并输入开发机 LAN 地址。该服务无 TLS，只能用于可信任的本地网络，不要暴露到公网。
2. 使用普通用户账号登录并绑定设备。管理员账号只能用于 Web 管理后台。
3. 在演示后台发布 `.py` 任务版本并授权给用户/设备。在 App 中可以先查看发布来源、声明能力、脚本内容和 SHA-256；脚本审阅不需要启用无障碍服务。
4. 首次运行前，在 Android 系统设置中手动启用 **FlowPilot 自动化操作** 无障碍服务。服务的可访问包名限制为抖音 `com.ss.android.ugc.aweme`，只可读取抖音界面文字，并最多点击每次运行指定的一处可点击文字。检查脚本后，再勾选确认运行。
5. 每次运行都由 Android 系统弹出屏幕捕获授权对话框。App 会启动前台运行服务并显示可停止的通知。运行期间 OCR 文本保留在设备本地；上报到演示 API 的运行摘要仅包含通用状态。
6. 如需从后台发起请求，在 App 中开启“远程运行待命”并保留持续通知。待命服务每 15 秒轮询一次。手机可逐次确认，也可在某次确认时勾选“为此任务和设备预授权 30 天”；预授权任务到达时仍需要用户打开通知，并且 Android 屏幕捕获系统授权仍逐次执行。关闭待命通知可停止轮询。

## Python 桥接接口

脚本在全局变量 `bridge` 上可用以下方法：

- `bridge.open_douyin()`：打开已安装的抖音。
- `bridge.ocr()`：OCR 当前屏幕并返回识别文本；仅在抖音处于前台时允许。
- `bridge.click_text(text)`：按无障碍节点文字执行一次点击；每次任务最多一次，且只能作用于抖音。
- `bridge.log(message)`：将最多 4 KB 的日志保留在本机。
- `bridge.is_stopped()`：查询用户是否已请求停止。脚本应在循环中检查该值，并及时退出。

最小脚本示例见 [`examples/open_douyin.py`](examples/open_douyin.py)。若任务名称包含“抖音”，客户端会在执行前启动抖音；脚本仍应调用 `bridge.open_douyin()`，以便在远程恢复或 App 切换场景下确保目标应用已打开。

## 权限和安全边界

- Android 不允许 App 静默授予 MediaProjection 屏幕捕获权限，也不允许 App 代替用户启用无障碍服务；本客户端不会绕过这些系统限制。
- Python 脚本在 App 进程内运行，**不是安全沙箱**。运行确认和 SHA-256 仅帮助用户审阅代码/检测下载内容变化，不隔离恶意脚本。只运行你信任并检查过的代码。
- 当前 OCR 使用设备端 ML Kit；截图和 OCR 文本不上传。用户脚本本身具有 App 进程权限，可能访问 App 可访问的数据或网络。
- 演示 API 默认使用无 TLS HTTP 和演示账号。不要在不可信网络、生产账号或公网部署中使用。
- 目前只面向中国版抖音包名 `com.ss.android.ugc.aweme`，不包含无障碍手势、多次点击或其他应用自动化。
