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

首次启动会初始化演示账号、设备和任务。交互式 API 文档位于 <http://127.0.0.1:8001/docs>。

| 角色 | 邮箱 | 演示密码 |
| --- | --- | --- |
| 管理员 | `admin@example.com` | `admin-demo` |
| 普通用户 | `chen.xiao@example.com` | `user-demo` |
| 普通用户 | `zhou.ke@example.com` | `user-demo` |

以上凭据只适用于本地演示数据库。token secret 未设置时服务会在每次进程启动生成新的随机密钥（重启后旧 token 失效）。迁移到 PostgreSQL 与对象存储、完善密码重置/refresh-token 生命周期、部署 HTTPS 和速率限制之前，不得把此服务暴露到公网。

## 已实现接口

- `POST /auth/login`、`POST /auth/refresh`、`GET /me`
- `GET/POST /users`、`PATCH /users/{id}`（管理员）
- `GET/POST /devices`、`PATCH/DELETE /devices/{id}`、`POST /devices/{id}/sync`
- `POST /devices/{id}/runs`（设备用户确认后上报本地启动）
- `GET/POST /tasks`、`GET /tasks/{id}/versions`、`POST /tasks/{id}/versions`、`POST /tasks/{id}/grants/{user_id}`
- `GET /devices/{id}/tasks/{task_id}/versions/{version}/content`
- `POST /remote-runs`、`GET /devices/{id}/remote-runs`、`PATCH /remote-runs/{id}`
- `POST /runs/{id}`、`GET /runs`
- `GET/POST/DELETE /run-authorizations`、`GET /admin/audit`

脚本只向已绑定、已授权的用户设备提供；远程请求默认需设备用户逐次确认，过期请求不执行。预授权限定在任务版本和设备上，有效期 30 天并可撤销。运行 API 只记录客户端回报状态，不在服务端或浏览器里执行 Python。

## 测试

```bash
python -m pytest server/tests -q
node --test demo/state.test.mjs
```
