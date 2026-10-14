# FlowPilot API

这是 FlowPilot 的 FastAPI 服务端。默认开发模式在本机使用 SQLite，并预置本地测试账号、设备和任务；生产部署使用 PostgreSQL 和 S3 兼容对象存储，Web 管理后台由 React/TypeScript/Vite 构建并以 Nginx 静态托管。遗留的有限预授权数据和 API 为旧客户端保留，不参与当前 Android 客户端的远程运行判断。脚本上传后只做 `.py` 格式、大小和 SHA-256 校验，不会执行脚本。

**当前实现尚不代表已具备全部生产运营保障。**登录及密码重置失败限制目前依赖数据库窗口计数，不替代边缘/API 网关限流；管理员 MFA、加密异地备份与恢复演练、监控告警和安全评审仍待落实。邮箱密码找回接口已实现，但只有完整配置 SMTP 后才开放；未配置时会明确返回 `503`。验证码仅保存服务端 HMAC 摘要、10 分钟有效、最多尝试 5 次，成功后撤销所有现有会话。登录创建 30 天绝对有效的服务端会话，访问令牌每小时过期并可在会话有效期间续期；用户退出会撤销当前会话，管理员可撤销普通用户所有会话，停用账号也会撤销其会话。生产 Web 上传及 Android 下载使用短时预签名 URL；API 在上传完成时核验内容大小及 SHA-256。SQLite 开发及兼容接口仍通过 API 上传/下载，单脚本限制为 2 MiB。Android Release 客户端必须使用 HTTPS API 地址。

## Deployment phases

域名及 HTTPS 公网入口按项目要求推迟至生产上线的最后阶段。当前优先在隔离的开发/预生产环境验证 PostgreSQL、S3 兼容对象存储、Web/API 集成、Android 派发、备份和恢复；这些环境不得开放到公网，且不应使用真实生产凭据。

当前 Docker Compose “production” profile **不是无域名/HTTP 的早期预生产配置**：它要求三个域名，Caddy 自动配置 HTTPS，API 的生产模式也拒绝非 HTTPS 的 CORS origin。不要为了绕过此保护而把生产模式改成接受公网 HTTP。若需要在域名和 HTTPS 接入前验证 PostgreSQL/S3，应先实现并验证受限的私有预生产配置，或用隔离集成测试；只允许在可信私有网络使用 HTTP 测试账号和测试数据。对外开放 Web/API、使用真实账号或 Android Release APK 前，必须完成最终域名、HTTPS、CORS 与安全配置。

当前已完成 Compose 静态配置校验和核心业务自动化测试，但尚未接入生产域名、真实 PostgreSQL/S3、外部备份目的地或监控告警。域名和 HTTPS 的实际配置/验收属于最后上线阶段，不作为当前应用与存储集成开发的前置事项；备份恢复、凭据管理、访问控制和测试环境隔离仍是相应阶段必须解决的工作。

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

首次启动会初始化本地测试账号、设备和任务。普通用户的订单 OCR 演示任务发布了 `v1.7.0`；另有可远程派发的“抖音登录页面引导（手动认证）”任务 `v1.0.2`：从抖音首页精确点击“我”标签进入个人页，等待并确认登录表单出现，然后由设备用户手动输入信息、完成认证。抖音若未安装，用户需从官方应用商店手动安装；脚本不静默安装应用、不读取或填写密码/验证码，也不绕过平台验证。两项任务均在设备本地执行，服务端不接收 OCR 文本或账号凭据。交互式 API 文档位于 <http://127.0.0.1:8001/docs>。

| 角色 | 本地演示账号邮箱 |
| --- | --- |
| 管理员 | `admin@example.com` |
| 普通用户（真机测试账号） | `chen.xiao@example.com` |
| 普通用户 | `zhou.ke@example.com` |

以上账号只适用于本地演示数据库；密码不记录在文档或版本库中，请通过受控的本地开发凭据渠道获取或使用密码重置流程。真机测试前确认两款 Android App 使用同一账号及当前 API 地址；会话可能过期，不能假设设备仍保持登录。token secret 未设置时服务会在每次进程启动生成新的随机密钥（重启后旧 token 失效）。迁移到 PostgreSQL 与对象存储、配置密码找回邮件、部署 HTTPS 和边缘速率限制之前，不得把此服务暴露到公网。

## Private staging (HTTP, loopback-only by default)

Use the separate `compose.staging.yaml` to test PostgreSQL, RustFS/S3, Web/API, and script presigned transfers before configuring public domains or HTTPS. RustFS runs with its disk-layout check bypassed because all staging volumes share one host disk; this is strictly for single-host staging and must never be enabled for production storage. RustFS is an S3-compatible staging backend; it does not replace the MinIO/S3 backend configured for the final production Compose, so run a separate compatibility check against the actual production storage provider before launch. Staging runs with `FLOWPILOT_ENV=staging`: it requires PostgreSQL, S3-compatible storage, a stable test-only token secret, and exact CORS origins; it disables demo account seeding and API documentation and rejects seeded demo accounts. Host ports bind to `127.0.0.1` by default, and the Caddy/public proxy is not included.

Create a private environment file with unique staging-only credentials. Generate random values locally; use URL-encoded credentials in `FLOWPILOT_DATABASE_URL` if they contain reserved characters.

```bash
cp .env.staging.example .env.staging
chmod 600 .env.staging
# Replace every "replace-with-..." value; use the same DB password in both settings.
docker compose --env-file .env.staging -p flowpilot-staging -f compose.staging.yaml up -d --build
docker compose --env-file .env.staging -p flowpilot-staging -f compose.staging.yaml ps
curl --fail http://127.0.0.1:18001/health/ready
```

The production console build is served at `http://127.0.0.1:18000/`; `/api/*` is proxied to the private staging API at port `18001`. The browser login only accepts a server-verified administrator account. On a fresh database, initialize a dedicated staging administrator once:

```bash
docker compose --env-file .env.staging -p flowpilot-staging -f compose.staging.yaml exec api python -m server.bootstrap_admin
```

For the already initialized staging database in this workspace, the generated admin email and password are stored locally in `.env.staging`, which is git-ignored and mode `600`.

For an Android device or another trusted LAN client, explicitly set `FLOWPILOT_STAGING_BIND_ADDRESS` to the host's private LAN IP and set `FLOWPILOT_STAGING_WEB_ORIGIN` and `FLOWPILOT_STAGING_S3_PUBLIC_ENDPOINT` to that same IP with their configured ports. Restrict host firewall ingress to the intended test clients. Do not bind to `0.0.0.0`, forward router ports, connect real accounts, or expose this HTTP setup to the internet. Android staging tests require a Debug APK because Release rejects cleartext HTTP.

Staging data is persisted in the `flowpilot-staging` Compose project's database and object volumes. `docker compose ... down` stops the services but preserves these volumes; deleting them is destructive. Staging does not substitute for encrypted off-host backups, production recovery drills, testing against the production object-storage provider, or the final HTTPS/CORS/domain configuration.

## Docker production deployment (final HTTPS ingress phase)

This profile is for the final HTTPS ingress phase, not the earlier isolated database/object-storage integration work. Compose runs PostgreSQL, MinIO, the API, static web console, and Caddy. Point the API, web-console, and object-storage domain names at the host and allow inbound TCP 80/443 for automatic HTTPS certificates. Create a private environment file from the template, set strong unique credentials (the PostgreSQL URL password must be URI-encoded), and start the stack:

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

Configure the Android app with `https://<FLOWPILOT_API_DOMAIN>`. The web console is at `https://<FLOWPILOT_WEB_DOMAIN>` and uses its same-origin `/api` proxy by default; the API domain remains available as a direct login URL. Presigned URLs use `https://<FLOWPILOT_S3_DOMAIN>`. Check `docker compose ps` and `https://<FLOWPILOT_API_DOMAIN>/health/ready` after deployment. `docker compose down` preserves named volumes; do not use `down -v` unless intentionally destroying the database, scripts, and TLS state.

## Production-mode startup guard

For non-Compose deployments set a PostgreSQL URL via `FLOWPILOT_DATABASE_URL`, a stable `FLOWPILOT_TOKEN_SECRET`, and exact HTTPS `FLOWPILOT_CORS_ORIGINS`; configure `FLOWPILOT_S3_BUCKET`, `FLOWPILOT_S3_REGION`, `FLOWPILOT_S3_ACCESS_KEY`, `FLOWPILOT_S3_SECRET_KEY`, and optionally `FLOWPILOT_S3_ENDPOINT_URL` / `FLOWPILOT_S3_PUBLIC_ENDPOINT_URL` for an S3-compatible service. Configure bucket CORS to allow the exact web-console origin for `PUT` uploads, or set `FLOWPILOT_S3_CORS_ORIGINS` and run `python -m server.init_storage` with bucket-administration permission. `FLOWPILOT_DB_PATH` remains available for SQLite development and compatibility. Production disables demo CORS origins, OpenAPI/Swagger/ReDoc, and demo account seeding; startup also rejects known demo accounts. The bootstrap command prompts for an admin name, email, and password of at least 6 characters without echoing the password. It refuses to run when the database already contains any user and must only be run once against a fresh database.

## Backup and restore operations

The SQLite maintenance CLI makes an online backup, verifies `PRAGMA integrity_check`, and publishes with owner-only permissions. It is only for SQLite development databases:

```bash
python -m server.database_backup backup --database "$FLOWPILOT_DB_PATH" --output /secure-backups/flowpilot.sqlite3
python -m server.database_backup verify --backup /secure-backups/flowpilot.sqlite3
```

For Compose PostgreSQL, configure an existing encrypted off-host directory (for example, an encrypted mounted backup volume), then schedule the repository backup helper. It writes owner-only files, verifies the custom-format archive with `pg_restore --list`, and creates a SHA-256 sidecar:

```bash
scripts/backup-postgres.sh /mnt/encrypted-backups/flowpilot
```

To re-verify a saved backup before a recovery drill, pass its absolute dump path to the read-only verifier. It checks that the adjacent `.sha256` sidecar names the selected dump, verifies the digest, and validates the PostgreSQL custom-format archive using the running Compose `db` service:

```bash
scripts/verify-postgres-backup.sh /mnt/encrypted-backups/flowpilot/flowpilot-postgres-20261014T020000Z.dump
```

Run the verifier from the matching Compose project and environment. A successful archive check does not prove that the dump can be restored or that database and object-storage backups are mutually consistent; restore into a separate disposable database and verify application behavior before relying on a recovery point.

For example, a host cron entry can run it daily after replacing the checkout and backup paths with the host's real paths:

```cron
15 2 * * * cd /opt/flowpilot && /opt/flowpilot/scripts/backup-postgres.sh /mnt/encrypted-backups/flowpilot
```

The helper intentionally does not create the destination, encrypt, upload, rotate, or monitor backups; the destination must already be protected encrypted off-host storage, and the operator must alert on scheduler failures and manage retention. It backs up PostgreSQL only. Back up the MinIO object volume independently or use a managed S3 provider with versioning/replication, and coordinate database/object recovery so version metadata does not outlive its script objects. Before restoring production data, rehearse on a separate disposable host/database, validate the archive checksum, restore the database and objects, and verify `/health/ready`, login, script downloads, and execution records. This repository cannot perform a real restore drill without the operator's production-like backup destination and object store.

## Health probes

`GET /health/live` is a process liveness probe and does not depend on external services. `GET /health/ready` (also available at `/health`) verifies that the configured database and (when enabled) S3 bucket are reachable; it returns `503` and logs the underlying failure if either is unavailable. Use liveness to restart a stuck process and readiness to stop routing traffic until dependencies recover.

## 密码找回邮件配置

配置 SMTP 后，Web、控制端 Android 和受控端 Android 登录页通过 `GET /auth/recovery-status` 探测能力并显示找回入口。未设置 SMTP 时，入口保持隐藏，`POST /auth/forgot-password` 和 `POST /auth/reset-password` 返回 `503`，不会模拟成功。配置以下环境变量即可启用；所有账号恢复邮件凭据仅供 API 容器读取，不要提交到仓库：

```text
FLOWPILOT_SMTP_HOST=smtp.example.com
FLOWPILOT_SMTP_PORT=587
FLOWPILOT_SMTP_USERNAME=flowpilot@example.com
FLOWPILOT_SMTP_PASSWORD=<secret from a secret manager>
FLOWPILOT_SMTP_FROM=flowpilot@example.com
FLOWPILOT_SMTP_USE_SSL=false
```

默认使用 SMTP STARTTLS；将 `FLOWPILOT_SMTP_USE_SSL=true` 用于隐式 TLS（通常端口 465）。邮箱请求响应不会说明账号是否存在，且按邮箱和来源地址分别限流。邮件投递失败会使刚签发的验证码立即失效并返回 `503`。上线启用前应使用专用测试邮箱确认 SMTP 凭据、收件可达性及垃圾邮件策略。

## 已实现接口

- `POST /auth/register`（公开创建普通用户；姓名、唯一邮箱和至少 6 位密码；不强制邮箱验证，不接受管理员角色）
- `POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout`、`POST /auth/change-password`
- `POST /auth/forgot-password`、`POST /auth/reset-password`（需要完整配置 SMTP；6 位验证码 10 分钟有效，最多 5 次错误尝试，成功后撤销所有现有会话）
- `GET/PATCH /me`：读取或更新当前用户个人资料；更改邮箱须提供当前密码。改密时校验当前密码并撤销该用户其他会话，当前会话保持有效。
- `POST /auth/logout`（撤销当前服务端会话）
- `GET/POST /users`、`PATCH /users/{id}`（管理员调整用户状态及设备数量上限）
- `POST /users/{id}/sessions/revoke`（管理员撤销指定普通用户的全部登录会话并记录审计事件）
- `GET/POST /devices`、`PATCH/DELETE /devices/{id}`、`POST /devices/{id}/sync`、`POST /devices/{id}/heartbeat`（只有设备所属用户可同步或发送心跳；心跳可附带更新后的 App 版本，旧客户端省略时保留原版本）
- `POST /devices/{id}/runs`（设备用户确认后上报本地启动）
- `GET/POST /tasks`、`PATCH /tasks/{id}`（管理员暂停/恢复）、`GET /tasks/{id}/versions`、`POST /tasks/{id}/versions`、`PATCH /tasks/{id}/versions/{version}/activate`
- `POST /tasks/{id}/versions/upload-url`、`POST /tasks/{id}/versions/complete-upload`（生产对象存储直传及服务端内容校验）
- `GET /tasks/{id}/grants`、`POST/DELETE /tasks/{id}/grants/{user_id}`（管理员管理任务访问权限）
- `GET/POST /tasks/{id}/publications`、`DELETE /tasks/{id}/publications/{publication_id}`（兼容旧管理端的设备分发记录接口；不是任务同步、下载或运行的前置条件）
- `GET /devices/{id}/tasks/{task_id}/versions/{version}/content-url`（授权后签发 5 分钟脚本下载地址；SQLite 返回直连兼容模式）
- `GET /devices/{id}/tasks/{task_id}/versions/{version}/content`（SQLite/旧客户端兼容下载）
- `POST /devices/{id}/publications/{publication_id}/accept`（兼容旧客户端的确认接口；新客户端不需要设备调用）
- `POST /remote-runs`、`GET /devices/{id}/remote-runs`、`GET /remote-runs/{id}`、`PATCH /remote-runs/{id}`、`POST /remote-runs/{id}/stop`；普通用户只能为自己的设备创建运行请求，管理员可为任一启用账号下的在线设备创建请求。管理员派发的任务会自动授权给目标设备所属用户，以便设备同步并下载脚本；该授权会记录审计。普通用户仍只能请求停止自己设备上的任务。新请求一律保持待处理状态，Android 客户端须在服务端重新校验并批准后才会建立运行记录；历史预授权不会自动启动当前客户端的请求。创建请求可带 `Idempotency-Key`（1–128 个字母、数字、点、下划线、冒号或连字符）；同一用户、设备和幂等键重试同一任务时返回原请求，复用该键派发其他任务返回 `409`。Web 和控制端 App 都会发送幂等键。服务端按目标设备锁定派发/批准/本地启动及完成事务，并以数据库唯一部分索引保证每台设备至多一个运行中的任务；若升级时发现同设备已有多条运行中记录，服务端会明确拒绝启动，须先核实并修复记录。设备轮询结果包含待处理请求、运行中的请求及最近 50 条过期请求，方便设备端显示过期状态；运行中的请求包含 `run_id` 和 `stop_requested`。运行中的停止请求以幂等标记传给受控端，受控端执行期间每 5 秒查询，脚本在下一个停止检查点退出；此信令不强行终止 Android 进程或任意阻塞代码。Web/App 查询请求状态时也会看到停止标记，结束状态仍由受控端上报。设备只向服务端上报固定的安全诊断摘要，不会上传任意脚本异常或屏幕内容
- `POST /runs/{id}`、`GET /runs`（返回任务/版本、设备、状态、开始/结束时间、结果摘要及可空的 `remote_run_id`；两个 Android App 展示最近 10 条，并支持点击记录查看详情）
- `GET/POST/DELETE /run-authorizations`、`GET /admin/audit`

任务所有者和已获任务访问权限的用户，其设备会自动同步当前已发布版本；不需要逐台设备发布、设备确认接收或单独的设备版本授权。受控端收到控制端派发后自动启动，不需要逐次确认任务。服务端仍分别校验账号、设备归属、任务访问权限、已发布版本和请求有效期；撤销旧设备分发记录不会阻止该用户继续运行有权访问的任务，需撤销任务访问权限或暂停任务。此行为不等同于授予 Android 系统权限：请求需要的无障碍能力必须已启用；OCR/屏幕捕获仍要求 Android 系统授权。如果设备已主动开启持续 OCR 前台服务且会话有效，可在会话期间复用授权，否则客户端须先通过系统授权后运行。会话结束、系统回收或撤销共享后，必须重新授权。运行 API 只记录客户端回报状态，不在服务端或浏览器里执行 Python。

普通用户只能向本人设备发起远程运行；管理员可在任务列表选择已发布任务，并向任一启用账号的在线设备派发。管理员首次向某用户设备派发任务时，系统会自动授予该用户该任务的访问权，以便设备同步和下载脚本；管理员可在任务访问授权中撤销访问。只有目标设备所属用户可请求停止任务。

**生产脚本发布治理：**服务端只验证 Python 文件扩展名、大小和 SHA-256，不静态审查或沙箱执行脚本。任务访问权限内的用户可直接派发，受控端会自动运行脚本；生产管理员必须在授予任务访问权限前通过可信代码审查流程检查完整脚本和能力声明，并只向明确受信任的用户授权。任务访问权限、暂停任务和审计日志是管理控制，但不能代替代码审查。首轮试点应预先指定暂停任务、撤销访问权限和停用账号的响应责任人。

管理员可在后台“操作审计”查看最近 500 条关键操作；普通用户无权访问审计 API。审计记录只包含操作人、动作、资源类型/ID 和时间，不包含密码、脚本正文或运行结果。

管理员暂停任务后，设备同步和脚本下载、新运行都会被拒绝；未处理的远程请求会过期，相关预授权会撤销。已开始的运行不会被强行中止，仍可正常上报完成或停止状态。恢复发布不会恢复旧预授权。

管理员可查看、添加或撤销任务的用户授权。撤销后该用户无法再同步、下载或新运行此任务，相关待确认请求和预授权会失效；任务所有者的访问权不能撤销，已开始的运行不受影响。

管理员或任务所有者可将已有版本重新设为当前版本。版本内容仍保持不可变；后续远程运行请求会使用切换后的当前版本，暂停任务仍保持暂停。切换操作会写入审计记录。

管理员可调整普通用户的设备数量上限（1–50 台）。新上限不能低于该用户当前已启用的设备数量；更改会写入审计记录。

## 测试

`make test` runs the SQLite server regression suite and frontend tests. PostgreSQL integration coverage is also available when `FLOWPILOT_TEST_DATABASE_URL` is set to a disposable PostgreSQL database. To exercise S3 storage, set `FLOWPILOT_TEST_S3_BUCKET`, `FLOWPILOT_TEST_S3_ENDPOINT_URL`, `FLOWPILOT_TEST_S3_ACCESS_KEY` and `FLOWPILOT_TEST_S3_SECRET_KEY`; `FLOWPILOT_TEST_S3_PUBLIC_ENDPOINT_URL` and `FLOWPILOT_TEST_S3_REGION` are optional. The PostgreSQL integration suite exercises concurrent idempotent dispatch and approvals using isolated schemas; S3-backed runs additionally create, retrieve and delete a test script object.
