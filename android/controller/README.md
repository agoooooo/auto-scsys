# FlowPilot 控制端 Android App

此模块构建独立控制端 APK（包名 `com.flowpilot.controller`），与受控端 `:app` 可分别安装和启动。控制端仅调用 FlowPilot 服务端 API，不包含 Python/Chaquopy 执行运行时，也不会将受控端做成模式切换。

## 当前 MVP

- 普通用户注册和登录；注册沿用服务端至少 6 位密码与普通用户角色约束。
- 加密保存 API 地址、会话令牌及账号资料；访问令牌过期时自动续期，服务端会话失效时清除本地会话并提示重新登录。
- “我的”页面与受控端保持一致；其唯一 UI 规范和账号页文案见 [`../README.md#android-客户端账号页面规范`](../README.md#android-客户端账号页面规范)，改动时只更新该处并同步两个客户端。
- 个人资料、账号安全、帮助与关于及退出登录入口按唯一规范呈现；账号操作由本控制端 API 和会话处理。
- 查看账号可访问的设备；按在线、离线、已停用和其他状态分组，在线组优先显示。设备独立成卡，点卡片或复选框即可多选在线设备；选中后可直接跳转到脚本选择。
- 脚本按独立卡片展示，可多选后与设备组合批量派发。
- 设备页提供快速开始指引：选在线设备、选择脚本并派发、在运行页查看结果。
- 派发后受控端自动运行，无需设备确认接收；首次所需的 Android 系统权限仍由设备用户按提示授予。
- 批量派发所选设备与脚本的组合；同一设备按任务列表顺序进入服务端持久队列，前一任务结束后受控端再接收下一项。控制端关闭后队列仍保存在服务端并继续执行。
- 在运行页查看当前派发队列、活动请求状态和安全结果摘要；打开页面时及手动刷新时读取最近 10 条历史执行记录。点击历史卡片可查看任务、状态、版本、设备、起止时间、结果和运行标识详情。已结束运行仅在历史列表中查看；“当前任务状态”仅用于仍活动的请求，保留取消/停止操作。
- 对仍处于排队/等待状态的请求发送拒绝/取消；请求停止运行中的远程任务，受控端每 5 秒轮询一次停止标记。
- 服务端继续校验设备归属、设备在线、任务授权和已分发版本；队列串行执行，仍保证每台设备同时最多一个运行实例。
- “帮助与关于”入口位于“我的”账号页，说明控制端使用流程、版本信息、职责边界及远程停止限制。

停止请求作为幂等标记记录在服务端，受控端在脚本下一个停止检查点观察到标记后收尾并上报 `stopped`；控制端不会直接伪造终态，也不会强行终止任意阻塞代码。重新打开控制端后会重新读取所选设备的等待/运行中请求并恢复状态轮询；但不提供退出页面后的后台跟踪保证。

## 构建和验证

```bash
cd android
./gradlew :controller:testDebugUnitTest :controller:assembleDebug
```

Debug 构建允许开发/可信局域网 HTTP API；Release 构建要求 HTTPS。登录页可修改 API 根地址，模拟器默认使用 `http://10.0.2.2:8001`。不要将无 TLS Debug APK 用于不可信网络或公网部署。

Release APK 需要单独配置签名密钥，四个环境变量须全部设置；未配置时 Gradle 不会生成可分发的签名 APK。签名密钥不得提交到仓库：

```bash
export FLOWPILOT_CONTROLLER_RELEASE_STORE_FILE=/secure/path/flowpilot-controller.jks
export FLOWPILOT_CONTROLLER_RELEASE_STORE_PASSWORD='从密钥管理器读取'
export FLOWPILOT_CONTROLLER_RELEASE_KEY_ALIAS=flowpilot-controller
export FLOWPILOT_CONTROLLER_RELEASE_KEY_PASSWORD='从密钥管理器读取'
cd android
./gradlew :controller:assembleRelease
```

使用独立密钥可分别管理控制端与受控端 APK 的升级签名。
