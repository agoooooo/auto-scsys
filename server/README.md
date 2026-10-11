# FlowPilot API MVP

这是供当前产品原型和后续 Android 客户端验证使用的 FastAPI 服务端。服务在本机使用 SQLite 持久化账号、设备、任务/版本、远程运行请求、有限预授权、运行记录及管理审计；脚本上传后只做 `.py` 格式、大小和 SHA-256 校验，不会执行脚本。

## 本地启动

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements.txt
FLOWPILOT_TOKEN_SECRET="$(python -c 'import secrets; print(secrets.token_hex(32))')" \
  FLOWPILOT_DB_PATH='./server/flowpilot.sqlite3' \
  uvicorn server.app:app --host 127.0.0.1 --port 8001
```

首次启动会初始化演示账号、设备和任务。普通用户的订单 OCR 演示任务发布了 `v1.7.0`：脚本会打开抖音、等待目标应用进入前台，在设备本地识别界面并显示匹配到的常见订单状态；不执行点击、不上传 OCR 文本，服务端只接收通用运行状态。交互式 API 文档位于 <http://127.0.0.1:8001/docs>。

| 角色 | 邮箱 | 演示密码 |
| --- | --- | --- |
| 管理员 | `admin@example.com` | `admin-demo` |
| 普通用户 | `chen.xiao@example.com` | `user-demo` |
| 普通用户 | `zhou.ke@example.com` | `user-demo` |

以上凭据只适用于本地演示数据库。token secret 未设置时服务会在每次进程启动生成新的随机密钥（重启后旧 token 失效）。迁移到 PostgreSQL 与对象存储、完善密码重置/refresh-token 生命周期、部署 HTTPS 和速率限制之前，不得把此服务暴露到公网。

## 已实现接口

- `POST /auth/login`、`POST /auth/refresh`、`GET /me`
- `GET/POST /users`、`PATCH /users/{id}`（管理员调整用户状态及设备数量上限）
- `GET/POST /devices`、`PATCH/DELETE /devices/{id}`、`POST /devices/{id}/sync`、`POST /devices/{id}/heartbeat`（只有设备所属用户可同步或发送心跳）
- `POST /devices/{id}/runs`（设备用户确认后上报本地启动）
- `GET/POST /tasks`、`PATCH /tasks/{id}`（管理员暂停/恢复）、`GET /tasks/{id}/versions`、`POST /tasks/{id}/versions`、`PATCH /tasks/{id}/versions/{version}/activate`
- `GET /tasks/{id}/grants`、`POST/DELETE /tasks/{id}/grants/{user_id}`（管理员管理任务访问权限）
- `GET /devices/{id}/tasks/{task_id}/versions/{version}/content`
- `POST /remote-runs`、`GET /devices/{id}/remote-runs`、`PATCH /remote-runs/{id}`；设备轮询结果包含待确认请求、运行中的请求及最近 50 条过期请求，方便设备端显示过期状态；运行中的请求包含 `run_id`
- `POST /runs/{id}`、`GET /runs`
- `GET/POST/DELETE /run-authorizations`、`GET /admin/audit`

脚本只向已绑定、已授权的用户设备提供；远程请求默认需设备用户逐次确认，过期请求不执行。首次确认时可授权指定任务、设备及当前版本之后的版本 30 天，并可随时撤销；回退到旧版本不会沿用后续版本授权。授权列表返回授权起始版本和后续版本范围。Android 客户端前台同步到远程 OCR 请求时会弹出审阅对话框；App 在后台时请求保留待处理，用户打开 App 后弹窗。用户需在手机端审阅请求并同意 Android 系统屏幕捕获；如果用户已主动开启持续 OCR 前台服务且会话有效，可在会话期间复用当前授权，否则通过系统授权后运行。会话结束、系统回收或撤销共享后，必须重新授权；预授权不能代替系统授权。运行 API 只记录客户端回报状态，不在服务端或浏览器里执行 Python。

管理员可在后台“操作审计”查看最近 500 条关键操作；普通用户无权访问审计 API。审计记录只包含操作人、动作、资源类型/ID 和时间，不包含密码、脚本正文或运行结果。

管理员暂停任务后，设备同步和脚本下载、新运行都会被拒绝；未处理的远程请求会过期，相关预授权会撤销。已开始的运行不会被强行中止，仍可正常上报完成或停止状态。恢复发布不会恢复旧预授权。

管理员可查看、添加或撤销任务的用户授权。撤销后该用户无法再同步、下载或新运行此任务，相关待确认请求和预授权会失效；任务所有者的访问权不能撤销，已开始的运行不受影响。

管理员或任务所有者可将已有版本重新设为当前版本。版本内容仍保持不可变；后续远程运行请求会使用切换后的当前版本，暂停任务仍保持暂停。切换操作会写入审计记录。

管理员可调整普通用户的设备数量上限（1–50 台）。新上限不能低于该用户当前已启用的设备数量；更改会写入审计记录。

## 测试

```bash
python -m pytest server/tests -q
node --test demo/state.test.mjs
```
