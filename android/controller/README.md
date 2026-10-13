# FlowPilot 控制端 Android App

此模块构建独立控制端 APK（包名 `com.flowpilot.controller`），与受控端 `:app` 可分别安装和启动。控制端仅调用 FlowPilot 服务端 API，不包含 Python/Chaquopy 执行运行时，也不会将受控端做成模式切换。

## 当前 MVP

- 普通用户注册和登录；注册沿用服务端至少 12 位密码与普通用户角色约束。
- 加密保存 API 地址、会话令牌及账号资料；访问令牌过期时自动续期，服务端会话失效时清除本地会话并提示重新登录。
- 编辑个人姓名和邮箱（邮箱变更需验证当前密码）；登录后修改密码，撤销其他会话并保留当前设备会话。
- 查看账号可访问的设备和任务，选择在线设备及已发布版本。
- 创建远程运行请求，并在页面打开期间轮询请求状态和安全结果摘要。
- 对仍处于等待状态的请求发送拒绝/取消。
- 请求停止运行中的远程任务；受控端每 5 秒轮询一次停止标记。
- 服务端继续校验设备归属、设备在线、任务授权、已分发版本和设备空闲状态。

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
