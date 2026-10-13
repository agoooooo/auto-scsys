# FlowPilot API

这是 FlowPilot 的 FastAPI 服务端。默认开发模式在本机使用 SQLite，并预置演示账号、设备和任务；生产部署可使用 PostgreSQL 和 S3 兼容对象存储。遗留的有限预授权数据和 API 为旧客户端保留，不参与当前 Android 客户端的远程运行判断。脚本上传后只做 `.py` 格式、大小和 SHA-256 校验，不会执行脚本。

**这不代表服务已具备所有生产运营保障。**登录失败限制目前依赖数据库窗口计数，不替代边缘/API 网关限流；仍需账号恢复、管理员 MFA、加密异地备份与恢复演练、监控告警和安全评审。登录创建 30 天绝对有效的服务端会话，访问令牌每小时过期并可在会话有效期间续期；用户退出会撤销当前会话，管理员可撤销普通用户所有会话，停用账号也会撤销其会话。Compose 提供 PostgreSQL、MinIO、API、Web 和自动 HTTPS 反向代理，适合单机/小型受控部署起步；应按组织要求配置独立托管数据库与对象存储、密钥管理和备份。生产 Web 上传及 Android 下载使用短时预签名 URL；API 在上传完成时核验内容大小及 SHA-256。SQLite 开发及兼容接口仍通过 API 上传/下载，单脚本限制为 2 MiB。Android 客户端必须使用 HTTPS API 地址。

服务端在启动时会补建会话表；升级后此前签发的无服务端会话令牌不再有效，用户需重新登录。Android 和 Web 登出会请求撤销当前会话；如果设备离线，Web 会提示服务端撤销失败，Android 会保留本机退出并显示错误，未撤销会话最迟在绝对有效期结束后失效。

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

## Docker production deployment

Compose runs PostgreSQL, MinIO, the API, static web console, and Caddy. Point the API, web-console, and object-storage domain names at the host and allow inbound TCP 80/443 for automatic HTTPS certificates. Create a private environment file from the template, set strong unique credentials (the PostgreSQL URL password must be URI-encoded), and start the stack:

```bash
cp .env.example .env
chmod 600 .env
# Edit .env: set the real domains, database URL, random secrets, and matching DB/S3 credentials.
docker compose up -d --build
```

The PostgreSQL and MinIO volumes persist independently; Compose does not publish their ports to the host. The API requires a stable token secret (32+ characters), exact HTTPS console CORS origin, and known-demo-account-free database; it disables demo seeding and API documentation. The browser uploads scripts directly to the private bucket via 5-minute presigned URLs; the API verifies object size and SHA-256 before publishing. Android downloads through authorized 5-minute presigned URLs and verifies the SHA-256 before execution. The object domain must route to MinIO without rewriting the request path or `Host`; Compose's Caddy route preserves both. Once the stack is healthy, initialize the first administrator interactively:

```bash
docker compose exec api python -m server.bootstrap_admin
```

Configure the Android app with `https://<FLOWPILOT_API_DOMAIN>`. The web console is at `https://<FLOWPILOT_WEB_DOMAIN>`; enter the API domain on its login screen. Presigned URLs use `https://<FLOWPILOT_S3_DOMAIN>`. Check `docker compose ps` and `https://<FLOWPILOT_API_DOMAIN>/health/ready` after deployment. `docker compose down` preserves named volumes; do not use `down -v` unless intentionally destroying the database, scripts, and TLS state.

## Production-mode startup guard

For non-Compose deployments set a PostgreSQL URL via `FLOWPILOT_DATABASE_URL`, a stable `FLOWPILOT_TOKEN_SECRET`, and exact HTTPS `FLOWPILOT_CORS_ORIGINS`; configure `FLOWPILOT_S3_BUCKET`, `FLOWPILOT_S3_REGION`, `FLOWPILOT_S3_ACCESS_KEY`, `FLOWPILOT_S3_SECRET_KEY`, and optionally `FLOWPILOT_S3_ENDPOINT_URL` / `FLOWPILOT_S3_PUBLIC_ENDPOINT_URL` for an S3-compatible service. Configure bucket CORS to allow the exact web-console origin for `PUT` uploads, or set `FLOWPILOT_S3_CORS_ORIGINS` and run `python -m server.init_storage` with bucket-administration permission. `FLOWPILOT_DB_PATH` remains available for SQLite development and compatibility. Production disables demo CORS origins, OpenAPI/Swagger/ReDoc, and demo account seeding; startup also rejects known demo accounts. The bootstrap command prompts for an admin name, email, and 12+ character password without echoing the password. It refuses to run when the database already contains any user and must only be run once against a fresh database.

## Backup and restore operations

The SQLite maintenance CLI makes an online backup, verifies `PRAGMA integrity_check`, and publishes with owner-only permissions. It is only for SQLite development databases:

```bash
python -m server.database_backup backup --database "$FLOWPILOT_DB_PATH" --output /secure-backups/flowpilot.sqlite3
python -m server.database_backup verify --backup /secure-backups/flowpilot.sqlite3
```

For Compose PostgreSQL, run a custom-format dump from the database container and send it directly to encrypted off-host storage:

```bash
umask 077
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > /secure-backups/flowpilot.dump
```

Back up the MinIO object volume independently (or use a managed S3 provider with versioning/replication); the Compose volume is persistence, not a backup. Restrict backup access, encrypt it off-host, monitor completion, and regularly rehearse restoring both the database and object data together. This repository does not schedule, encrypt, rotate, upload, or monitor backups.

## Health probes

`GET /health/live` is a process liveness probe and does not depend on external services. `GET /health/ready` (also available at `/health`) verifies that the configured database and (when enabled) S3 bucket are reachable; it returns `503` and logs the underlying failure if either is unavailable. Use liveness to restart a stuck process and readiness to stop routing traffic until dependencies recover.

## 已实现接口

- `POST /auth/login`、`POST /auth/refresh`、`GET /me`
- `POST /auth/logout`（撤销当前服务端会话）
- `GET/POST /users`、`PATCH /users/{id}`（管理员调整用户状态及设备数量上限）
- `POST /users/{id}/sessions/revoke`（管理员撤销指定普通用户的全部登录会话并记录审计事件）
- `GET/POST /devices`、`PATCH/DELETE /devices/{id}`、`POST /devices/{id}/sync`、`POST /devices/{id}/heartbeat`（只有设备所属用户可同步或发送心跳；心跳可附带更新后的 App 版本，旧客户端省略时保留原版本）
- `POST /devices/{id}/runs`（设备用户确认后上报本地启动）
- `GET/POST /tasks`、`PATCH /tasks/{id}`（管理员暂停/恢复）、`GET /tasks/{id}/versions`、`POST /tasks/{id}/versions`、`PATCH /tasks/{id}/versions/{version}/activate`
- `POST /tasks/{id}/versions/upload-url`、`POST /tasks/{id}/versions/complete-upload`（生产对象存储直传及服务端内容校验）
- `GET /tasks/{id}/grants`、`POST/DELETE /tasks/{id}/grants/{user_id}`（管理员管理任务访问权限）
- `GET/POST /tasks/{id}/publications`、`DELETE /tasks/{id}/publications/{publication_id}`（管理员将指定版本分发给具体设备或撤销分发）
- `GET /devices/{id}/tasks/{task_id}/versions/{version}/content-url`（授权后签发 5 分钟脚本下载地址；SQLite 返回直连兼容模式）
- `GET /devices/{id}/tasks/{task_id}/versions/{version}/content`（SQLite/旧客户端兼容下载）
- `POST /devices/{id}/publications/{publication_id}/accept`（设备用户确认接收指定脚本版本）
- `POST /remote-runs`、`GET /devices/{id}/remote-runs`、`PATCH /remote-runs/{id}`；只有普通用户可为自己的设备创建远程运行请求。新请求一律保持待处理状态，Android 客户端须在服务端重新校验并批准后才会建立运行记录；历史预授权不会自动启动当前客户端的请求。设备轮询结果包含待处理请求、运行中的请求及最近 50 条过期请求，方便设备端显示过期状态；运行中的请求包含 `run_id`
- `POST /runs/{id}`、`GET /runs`
- `GET/POST/DELETE /run-authorizations`、`GET /admin/audit`

管理员将已发布版本分发到具体设备后，设备用户先看到版本来源、更新说明和能力列表；确认接收后，服务端才允许该设备同步和下载该版本。服务端对运行请求和下载分别校验账号、设备、任务授权及已确认的版本。Android 客户端在服务端鉴权后自动批准并启动新请求，不需要逐次任务确认或有限预授权；旧预授权记录不会绕过当前客户端的设备端批准流程。这不等同于授予 Android 系统权限。请求需要的无障碍能力必须已启用；OCR/屏幕捕获仍要求 Android 系统授权。如果设备已主动开启持续 OCR 前台服务且会话有效，可在会话期间复用授权，否则客户端须先通过系统授权后运行。会话结束、系统回收或撤销共享后，必须重新授权。运行 API 只记录客户端回报状态，不在服务端或浏览器里执行 Python。

远程运行只能由普通用户发起到自己名下的设备；管理员可以分发脚本和查看记录，但不能直接启动其他用户设备上的任务。

**生产脚本发布治理：**服务端只验证 Python 文件扩展名、大小和 SHA-256，不静态审查或沙箱执行脚本。生产管理员必须在发布到他人设备前通过可信代码审查流程检查完整脚本和能力声明；管理员分发、设备用户确认接收、按版本授权、暂停任务、撤销分发及审计日志构成现有技术控制，但不能代替代码审查。首轮试点只应开放给明确受信任的发布者和用户，并预先指定暂停任务、撤销分发和停用账号的响应责任人。

管理员可在后台“操作审计”查看最近 500 条关键操作；普通用户无权访问审计 API。审计记录只包含操作人、动作、资源类型/ID 和时间，不包含密码、脚本正文或运行结果。

管理员暂停任务后，设备同步和脚本下载、新运行都会被拒绝；未处理的远程请求会过期，相关预授权会撤销。已开始的运行不会被强行中止，仍可正常上报完成或停止状态。恢复发布不会恢复旧预授权。

管理员可查看、添加或撤销任务的用户授权。撤销后该用户无法再同步、下载或新运行此任务，相关待确认请求和预授权会失效；任务所有者的访问权不能撤销，已开始的运行不受影响。

管理员或任务所有者可将已有版本重新设为当前版本。版本内容仍保持不可变；后续远程运行请求会使用切换后的当前版本，暂停任务仍保持暂停。切换操作会写入审计记录。

管理员可调整普通用户的设备数量上限（1–50 台）。新上限不能低于该用户当前已启用的设备数量；更改会写入审计记录。

## 测试

`make test` runs the SQLite server regression suite and frontend tests. PostgreSQL/S3 integration coverage is also available when `FLOWPILOT_TEST_DATABASE_URL` is set to a disposable PostgreSQL database; set all four `FLOWPILOT_TEST_S3_*` variables to exercise S3 storage too.
