# FlowPilot API MVP

这是供当前产品原型和后续 Android 客户端验证使用的 FastAPI 服务端。默认开发模式在本机使用 SQLite，并预置演示账号、设备和任务；遗留的有限预授权数据和 API 为旧客户端保留，不参与当前 Android 客户端的远程运行判断。脚本上传后只做 `.py` 格式、大小和 SHA-256 校验，不会执行脚本。

**当前服务仍不是生产系统。**`FLOWPILOT_ENV=production` 只启用启动保护（要求明确配置、禁用演示数据和 API 文档），并不代表 SQLite、脚本存储、账号恢复、全 API 速率限制、自动化备份策略、监控或高可用已达到生产标准。登录接口会按来源地址和邮箱组合限制连续失败尝试（15 分钟内最多 5 次），但这不替代边缘/API 网关层的分布式限流与防拒绝服务措施。此模式仅用于部署配置验证或单实例、持久卷上的受控内部验证；公网部署前仍须完成托管 PostgreSQL/对象存储、HTTPS 反向代理、账户生命周期与恢复、全 API 速率限制、定期加密异地备份及恢复演练、监控告警和安全评审。Android 客户端必须使用 HTTPS API 地址。

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

## Production-mode startup guard

Production mode fails closed unless it receives an absolute database path, a stable secret of at least 32 bytes, and one or more exact HTTPS browser origins. It disables the demo CORS origins, OpenAPI/Swagger/ReDoc, and demo account seeding; startup also rejects known demo accounts in the database. Use a fresh, persistent database and create the first admin interactively:

```bash
export FLOWPILOT_ENV=production
export FLOWPILOT_DB_PATH=/var/lib/flowpilot/flowpilot.sqlite3
export FLOWPILOT_TOKEN_SECRET='<provide a randomly generated secret from your secret manager>'
export FLOWPILOT_CORS_ORIGINS='https://console.example.com'
python -m server.bootstrap_admin
uvicorn server.app:app --host 127.0.0.1 --port 8001
```

The bootstrap command prompts for an admin name, email, and 12+ character password without echoing the password. It refuses to run when the database already contains any user, and must be run only once against a fresh database. Keep the API behind a correctly configured HTTPS reverse proxy; do not publish the local SQLite file or secret. The production-mode guard does not make this SQLite-based prototype production-ready.

## SQLite backup and restore

The maintenance CLI makes a consistent online SQLite backup, verifies it with `PRAGMA integrity_check`, and publishes it with owner-only permissions. It never overwrites an existing file; restore must target a new path:

```bash
python -m server.database_backup backup --database "$FLOWPILOT_DB_PATH" --output /secure-backups/flowpilot-$(date +%Y%m%d-%H%M%S).sqlite3
python -m server.database_backup verify --backup /secure-backups/flowpilot-20261012-001500.sqlite3
python -m server.database_backup restore --backup /secure-backups/flowpilot-20261012-001500.sqlite3 --database /var/lib/flowpilot/restore-check.sqlite3
```

Keep backup destinations on protected storage; this database contains account data and uploaded script contents. This CLI does not schedule, encrypt, upload, rotate, or monitor backups. Put backups in an encrypted off-host system and periodically rehearse restores before relying on them.

## 已实现接口

- `POST /auth/login`、`POST /auth/refresh`、`GET /me`
- `GET/POST /users`、`PATCH /users/{id}`（管理员调整用户状态及设备数量上限）
- `GET/POST /devices`、`PATCH/DELETE /devices/{id}`、`POST /devices/{id}/sync`、`POST /devices/{id}/heartbeat`（只有设备所属用户可同步或发送心跳）
- `POST /devices/{id}/runs`（设备用户确认后上报本地启动）
- `GET/POST /tasks`、`PATCH /tasks/{id}`（管理员暂停/恢复）、`GET /tasks/{id}/versions`、`POST /tasks/{id}/versions`、`PATCH /tasks/{id}/versions/{version}/activate`
- `GET /tasks/{id}/grants`、`POST/DELETE /tasks/{id}/grants/{user_id}`（管理员管理任务访问权限）
- `GET/POST /tasks/{id}/publications`、`DELETE /tasks/{id}/publications/{publication_id}`（管理员将指定版本分发给具体设备或撤销分发）
- `GET /devices/{id}/tasks/{task_id}/versions/{version}/content`
- `POST /devices/{id}/publications/{publication_id}/accept`（设备用户确认接收指定脚本版本）
- `POST /remote-runs`、`GET /devices/{id}/remote-runs`、`PATCH /remote-runs/{id}`；设备轮询结果包含待确认请求、运行中的请求及最近 50 条过期请求，方便设备端显示过期状态；运行中的请求包含 `run_id`
- `POST /runs/{id}`、`GET /runs`
- `GET/POST/DELETE /run-authorizations`、`GET /admin/audit`

管理员将已发布版本分发到具体设备后，设备用户先看到版本来源、更新说明和能力列表；确认接收后，服务端才允许该设备同步和下载该版本。服务端对运行请求和下载分别校验账号、设备、任务授权及已确认的版本。请求由 Android 客户端在服务端鉴权后自动批准并启动，不需要逐次任务确认或有限预授权；这不等同于授予 Android 系统权限。请求需要的无障碍能力必须已启用；OCR/屏幕捕获仍要求 Android 系统授权。如果设备已主动开启持续 OCR 前台服务且会话有效，可在会话期间复用授权，否则客户端须先通过系统授权后运行。会话结束、系统回收或撤销共享后，必须重新授权。运行 API 只记录客户端回报状态，不在服务端或浏览器里执行 Python。

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
