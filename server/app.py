from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal
from urllib.parse import quote

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel, Field


DEFAULT_DB_PATH = Path(__file__).with_name("flowpilot.sqlite3")
TOKEN_TTL_SECONDS = 60 * 60
REMOTE_REQUEST_TTL_SECONDS = 15 * 60
PREAUTHORIZATION_TTL_SECONDS = 30 * 24 * 60 * 60
MAX_SCRIPT_BYTES = 2 * 1024 * 1024
VERSION_PATTERN = re.compile(r"^v\d+\.\d+\.\d+$")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _password_hash(password: str, salt: bytes) -> str:
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 180_000).hex()


def _token_encode(payload: dict, secret: bytes) -> str:
    body = base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).rstrip(b"=")
    signature = hmac.new(secret, body, hashlib.sha256).digest()
    return f"{body.decode()}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode()}"


def _token_decode(token: str, secret: bytes) -> dict:
    try:
        body_part, signature_part = token.split(".", 1)
        body = body_part.encode()
        signature = base64.urlsafe_b64decode(signature_part + "=" * (-len(signature_part) % 4))
        expected = hmac.new(secret, body, hashlib.sha256).digest()
        payload = json.loads(base64.urlsafe_b64decode(body + b"=" * (-len(body) % 4)))
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=401, detail="无效的访问令牌") from exc
    if not hmac.compare_digest(signature, expected) or payload.get("exp", 0) <= int(time.time()):
        raise HTTPException(status_code=401, detail="访问令牌无效或已过期")
    return payload


def _content_disposition(filename: str) -> str:
    fallback = re.sub(r"[^A-Za-z0-9._-]", "_", filename)
    return f'attachment; filename="{fallback}"; filename*=UTF-8\'\'{quote(filename, safe="")}'


@contextmanager
def _connect(db_path: Path):
    connection = sqlite3.connect(db_path, timeout=10, check_same_thread=False)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    try:
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def _initialize_database(db_path: Path) -> None:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with _connect(db_path) as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                email TEXT NOT NULL UNIQUE COLLATE NOCASE,
                role TEXT NOT NULL CHECK(role IN ('admin', 'user')),
                status TEXT NOT NULL CHECK(status IN ('active', 'disabled')),
                device_limit INTEGER NOT NULL CHECK(device_limit > 0),
                password_salt BLOB NOT NULL,
                password_hash TEXT NOT NULL,
                joined_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                name TEXT NOT NULL,
                model TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('online', 'offline', 'disabled')),
                app_version TEXT NOT NULL,
                last_active TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                category TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('draft', 'published', 'paused')),
                owner_id TEXT NOT NULL REFERENCES users(id),
                source TEXT NOT NULL,
                capabilities_json TEXT NOT NULL,
                active_version TEXT
            );
            CREATE TABLE IF NOT EXISTS task_versions (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                version TEXT NOT NULL,
                filename TEXT NOT NULL,
                content BLOB NOT NULL,
                sha256 TEXT NOT NULL,
                notes TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(task_id, version)
            );
            CREATE TABLE IF NOT EXISTS task_grants (
                task_id TEXT NOT NULL REFERENCES tasks(id),
                user_id TEXT NOT NULL REFERENCES users(id),
                created_at TEXT NOT NULL,
                PRIMARY KEY(task_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS remote_runs (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                version TEXT NOT NULL,
                device_id TEXT NOT NULL REFERENCES devices(id),
                requested_by TEXT NOT NULL REFERENCES users(id),
                status TEXT NOT NULL CHECK(status IN ('pending', 'running', 'rejected', 'expired', 'success', 'failure', 'stopped')),
                created_at TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                run_id TEXT
            );
            CREATE TABLE IF NOT EXISTS run_authorizations (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                version TEXT NOT NULL,
                device_id TEXT NOT NULL REFERENCES devices(id),
                user_id TEXT NOT NULL REFERENCES users(id),
                expires_at INTEGER NOT NULL,
                revoked_at TEXT,
                UNIQUE(task_id, version, device_id)
            );
            CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                version TEXT NOT NULL,
                device_id TEXT NOT NULL REFERENCES devices(id),
                remote_run_id TEXT REFERENCES remote_runs(id),
                status TEXT NOT NULL CHECK(status IN ('running', 'success', 'failure', 'stopped')),
                started_at TEXT NOT NULL,
                finished_at TEXT,
                result_summary TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS audit_events (
                id TEXT PRIMARY KEY,
                actor_id TEXT NOT NULL REFERENCES users(id),
                action TEXT NOT NULL,
                resource_type TEXT NOT NULL,
                resource_id TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
            CREATE INDEX IF NOT EXISTS idx_runs_device ON runs(device_id, started_at);
            CREATE INDEX IF NOT EXISTS idx_remote_device ON remote_runs(device_id, status, expires_at);
            """
        )
        task_columns = {row["name"] for row in db.execute("PRAGMA table_info(tasks)")}
        if "active_version" not in task_columns:
            db.execute("ALTER TABLE tasks ADD COLUMN active_version TEXT")
        if db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0:
            _seed_database(db)
        db.execute(
            """UPDATE tasks SET active_version=(
                   SELECT version FROM task_versions WHERE task_id=tasks.id ORDER BY rowid DESC LIMIT 1
               ) WHERE active_version IS NULL
                 AND EXISTS (SELECT 1 FROM task_versions WHERE task_id=tasks.id)"""
        )


def _seed_database(db: sqlite3.Connection) -> None:
    now = utc_now()
    users = [
        ("u-admin", "林管理员", "admin@example.com", "admin", "active", 50, "admin-demo"),
        ("u-1001", "陈晓", "chen.xiao@example.com", "user", "active", 5, "user-demo"),
        ("u-1002", "周可", "zhou.ke@example.com", "user", "active", 3, "user-demo"),
        ("u-1003", "许一帆", "yifan.xu@example.com", "user", "disabled", 2, "user-demo"),
    ]
    for user_id, name, email, role, status, device_limit, password in users:
        salt = secrets.token_bytes(16)
        db.execute(
            """INSERT INTO users(id,name,email,role,status,device_limit,password_salt,password_hash,joined_at)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            (user_id, name, email, role, status, device_limit, salt, _password_hash(password, salt), now),
        )

    devices = [
        ("d-001", "u-1001", "Pixel 8 Pro", "Google · Android 15", "online", "1.2.0"),
        ("d-002", "u-1001", "小米 14", "Xiaomi · Android 14", "online", "1.2.0"),
        ("d-003", "u-1002", "Samsung S24", "Samsung · Android 15", "offline", "1.1.4"),
        ("d-004", "u-1003", "Pixel 7", "Google · Android 14", "disabled", "1.1.4"),
    ]
    db.executemany(
        "INSERT INTO devices(id,user_id,name,model,status,app_version,last_active) VALUES(?,?,?,?,?,?,?)",
        [(*device, now) for device in devices],
    )
    db.executemany(
        """INSERT INTO tasks(id,name,description,category,status,owner_id,source,capabilities_json)
           VALUES(?,?,?,?,?,?,?,?)""",
        [
            ("t-001", "订单状态检查", "识别订单页面状态并记录结果", "OCR 识别", "published", "u-1001", "陈晓上传", json.dumps(["屏幕捕获（需系统授权）", "OCR 文字识别"], ensure_ascii=False)),
            ("t-002", "每日签到提醒", "检查签到状态并发送提醒", "页面识别", "draft", "u-admin", "林管理员发布", json.dumps(["屏幕捕获（需系统授权）", "OCR 文字识别"], ensure_ascii=False)),
        ],
    )
    db.executemany(
        """INSERT INTO task_versions(id,task_id,version,filename,content,sha256,notes,created_at)
           VALUES(?,?,?,?,?,?,?,?)""",
        [
            ("tv-002", "t-001", "v1.3.0", "orders.py", b"# Previous demo script placeholder; not executed.\n", hashlib.sha256(b"# Previous demo script placeholder; not executed.\n").hexdigest(), "历史演示版本", now),
            ("tv-001", "t-001", "v1.4.0", "orders.py", b"# Demo script placeholder; not executed.\n", hashlib.sha256(b"# Demo script placeholder; not executed.\n").hexdigest(), "初始演示版本", now),
            ("tv-003", "t-002", "v0.2.0", "checkin.py", b"# Draft demo script placeholder; not executed.\n", hashlib.sha256(b"# Draft demo script placeholder; not executed.\n").hexdigest(), "草稿", now),
        ],
    )
    db.execute("INSERT INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?)", ("t-001", "u-1001", now))


class LoginInput(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=256)


class CreateUserInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    email: str = Field(min_length=3, max_length=254)
    device_limit: int = Field(default=2, ge=1, le=50)
    password: str = Field(min_length=8, max_length=256)


class CreateTaskInput(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    description: str = Field(default="", max_length=500)
    category: str = Field(default="OCR 识别", max_length=60)
    capabilities: list[str] = Field(default_factory=lambda: ["OCR 文字识别"], max_length=10)


class RegisterDeviceInput(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    model: str = Field(min_length=1, max_length=160)
    app_version: str = Field(default="1.0.0", max_length=32)


class UserStatusInput(BaseModel):
    status: Literal["active", "disabled"]


class DeviceStatusInput(BaseModel):
    status: Literal["online", "offline", "disabled"]


class TaskStatusInput(BaseModel):
    status: Literal["published", "paused"]


class RemoteRunInput(BaseModel):
    task_id: str
    device_id: str


class LocalRunInput(BaseModel):
    task_id: str
    version: str


class RemoteRunDecision(BaseModel):
    decision: Literal["approve", "reject"]
    preauthorize: bool = False


class RunAuthorizationInput(BaseModel):
    task_id: str
    version: str
    device_id: str


class RunUpdate(BaseModel):
    status: Literal["success", "failure", "stopped"]
    result_summary: str = Field(default="", max_length=500)


def create_app(db_path: str | Path | None = None, token_secret: bytes | None = None) -> FastAPI:
    database_path = Path(db_path or os.environ.get("FLOWPILOT_DB_PATH", DEFAULT_DB_PATH))
    configured_secret = token_secret
    if configured_secret is None and os.environ.get("FLOWPILOT_TOKEN_SECRET"):
        configured_secret = os.environ["FLOWPILOT_TOKEN_SECRET"].encode()
    if configured_secret is not None and len(configured_secret) < 32:
        raise ValueError("FLOWPILOT_TOKEN_SECRET must contain at least 32 bytes")
    secret = configured_secret if configured_secret is not None else secrets.token_bytes(32)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        _initialize_database(database_path)
        yield

    app = FastAPI(title="FlowPilot API", version="0.1.0", lifespan=lifespan)
    app.state.db_path = database_path
    app.state.token_secret = secret
    configured_origins = [
        origin.strip()
        for origin in os.environ.get("FLOWPILOT_CORS_ORIGINS", "").split(",")
        if origin.strip()
    ]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(dict.fromkeys([
            "http://localhost:8000",
            "http://127.0.0.1:8000",
            "http://localhost:8765",
            "http://127.0.0.1:8765",
            *configured_origins,
        ])),
        allow_credentials=False,
        allow_methods=["GET", "POST", "PATCH", "DELETE"],
        allow_headers=["Authorization", "Content-Type"],
    )

    def connection():
        with _connect(database_path) as db:
            yield db

    def current_user(authorization: str | None = Header(default=None), db: sqlite3.Connection = Depends(connection)):
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="需要登录")
        payload = _token_decode(authorization[7:], secret)
        user = db.execute(
            "SELECT id,name,email,role,status,device_limit FROM users WHERE id=?",
            (payload.get("sub"),),
        ).fetchone()
        if not user or user["status"] != "active":
            raise HTTPException(status_code=401, detail="账号不可用")
        return dict(user)

    def require_admin(user: dict = Depends(current_user)) -> dict:
        if user["role"] != "admin":
            raise HTTPException(status_code=403, detail="需要管理员权限")
        return user

    def audit(db: sqlite3.Connection, actor_id: str, action: str, resource_type: str, resource_id: str) -> None:
        db.execute(
            "INSERT INTO audit_events(id,actor_id,action,resource_type,resource_id,created_at) VALUES(?,?,?,?,?,?)",
            (str(uuid.uuid4()), actor_id, action, resource_type, resource_id, utc_now()),
        )

    def disable_device(db: sqlite3.Connection, device_id: str) -> None:
        now = utc_now()
        db.execute("UPDATE devices SET status='disabled' WHERE id=?", (device_id,))
        db.execute(
            "UPDATE run_authorizations SET revoked_at=? WHERE device_id=? AND revoked_at IS NULL",
            (now, device_id),
        )
        db.execute(
            "UPDATE remote_runs SET status='expired' WHERE device_id=? AND status='pending'",
            (device_id,),
        )

    def _device_for_user(db: sqlite3.Connection, device_id: str, user: dict) -> sqlite3.Row:
        device = db.execute(
            """SELECT d.*,u.status AS account_status FROM devices d
               JOIN users u ON u.id=d.user_id WHERE d.id=?""",
            (device_id,),
        ).fetchone()
        if not device:
            raise HTTPException(status_code=404, detail="设备不存在")
        if user["role"] != "admin" and device["user_id"] != user["id"]:
            raise HTTPException(status_code=404, detail="设备不存在")
        if device["account_status"] != "active":
            raise HTTPException(status_code=409, detail="设备所属账号已停用")
        if device["status"] == "disabled":
            raise HTTPException(status_code=409, detail="设备已停用")
        return device

    def _task_for_user(db: sqlite3.Connection, task_id: str, user: dict, require_published: bool = True) -> sqlite3.Row:
        task = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        if user["role"] != "admin":
            grant = db.execute("SELECT 1 FROM task_grants WHERE task_id=? AND user_id=?", (task_id, user["id"])).fetchone()
            if not grant:
                raise HTTPException(status_code=404, detail="任务不存在")
        if require_published and task["status"] != "published":
            raise HTTPException(status_code=409, detail="任务当前不可运行")
        return task

    def _expire_requests(db: sqlite3.Connection) -> None:
        db.execute(
            "UPDATE remote_runs SET status='expired' WHERE status='pending' AND expires_at<=?",
            (int(time.time()),),
        )

    def _start_run(db: sqlite3.Connection, request: sqlite3.Row) -> str:
        run_id = str(uuid.uuid4())
        db.execute(
            """INSERT INTO runs(id,task_id,version,device_id,remote_run_id,status,started_at)
               VALUES(?,?,?,?,?,'running',?)""",
            (run_id, request["task_id"], request["version"], request["device_id"], request["id"], utc_now()),
        )
        db.execute("UPDATE remote_runs SET status='running',run_id=? WHERE id=?", (run_id, request["id"]))
        return run_id

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.post("/auth/login")
    def login(body: LoginInput, db: sqlite3.Connection = Depends(connection)):
        user = db.execute("SELECT * FROM users WHERE email=? COLLATE NOCASE", (body.email.strip(),)).fetchone()
        if not user or user["status"] != "active" or not hmac.compare_digest(
            _password_hash(body.password, user["password_salt"]), user["password_hash"]
        ):
            raise HTTPException(status_code=401, detail="邮箱或密码错误")
        token = _token_encode({"sub": user["id"], "exp": int(time.time()) + TOKEN_TTL_SECONDS}, secret)
        return {
            "access_token": token,
            "token_type": "bearer",
            "expires_in": TOKEN_TTL_SECONDS,
            "user": {key: user[key] for key in ("id", "name", "email", "role", "status", "device_limit")},
        }

    @app.get("/me")
    def me(user: dict = Depends(current_user)):
        return user

    @app.post("/auth/refresh")
    def refresh_access_token(user: dict = Depends(current_user)):
        token = _token_encode({"sub": user["id"], "exp": int(time.time()) + TOKEN_TTL_SECONDS}, secret)
        return {"access_token": token, "token_type": "bearer", "expires_in": TOKEN_TTL_SECONDS}

    @app.get("/users")
    def list_users(user: dict = Depends(require_admin), db: sqlite3.Connection = Depends(connection)):
        return [dict(row) for row in db.execute(
            "SELECT id,name,email,role,status,device_limit,joined_at FROM users ORDER BY joined_at DESC"
        )]

    @app.post("/users", status_code=201)
    def create_user(body: CreateUserInput, user: dict = Depends(require_admin), db: sqlite3.Connection = Depends(connection)):
        email = body.email.strip().lower()
        name = body.name.strip()
        if not name or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
            raise HTTPException(status_code=422, detail="请提供有效的姓名和邮箱")
        user_id = str(uuid.uuid4())
        salt = secrets.token_bytes(16)
        try:
            db.execute(
                """INSERT INTO users(id,name,email,role,status,device_limit,password_salt,password_hash,joined_at)
                   VALUES(?,?,?,'user','active',?,?,?,?)""",
                (user_id, name, email, body.device_limit, salt, _password_hash(body.password, salt), utc_now()),
            )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="该邮箱已存在") from exc
        audit(db, user["id"], "user.created", "user", user_id)
        return {"id": user_id, "name": name, "email": email, "role": "user", "status": "active", "device_limit": body.device_limit}

    @app.patch("/users/{user_id}")
    def set_user_status(user_id: str, body: UserStatusInput, user: dict = Depends(require_admin), db: sqlite3.Connection = Depends(connection)):
        target = db.execute("SELECT id FROM users WHERE id=? AND role='user'", (user_id,)).fetchone()
        if not target:
            raise HTTPException(status_code=404, detail="用户不存在")
        db.execute("UPDATE users SET status=? WHERE id=?", (body.status, user_id))
        if body.status == "disabled":
            now = utc_now()
            db.execute("UPDATE run_authorizations SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", (now, user_id))
            db.execute("UPDATE remote_runs SET status='expired' WHERE status='pending' AND device_id IN (SELECT id FROM devices WHERE user_id=?)", (user_id,))
            db.execute(
                """UPDATE runs SET status='stopped',finished_at=?,result_summary='账号已停用'
                   WHERE status='running' AND device_id IN (SELECT id FROM devices WHERE user_id=?)""",
                (now, user_id),
            )
            db.execute(
                """UPDATE remote_runs SET status='stopped'
                   WHERE run_id IN (SELECT id FROM runs WHERE device_id IN
                     (SELECT id FROM devices WHERE user_id=?) AND status='stopped') AND status='running'""",
                (user_id,),
            )
        audit(db, user["id"], f"user.{body.status}", "user", user_id)
        return {"id": user_id, "status": body.status}

    @app.get("/devices")
    def list_devices(user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        query = """SELECT d.id,d.user_id,d.name,d.model,d.status,d.app_version,d.last_active,
                          u.name AS user_name,u.status AS account_status
                   FROM devices d JOIN users u ON u.id=d.user_id"""
        rows = db.execute(query + (" ORDER BY d.id" if user["role"] == "admin" else " WHERE d.user_id=? ORDER BY d.id"),
                          () if user["role"] == "admin" else (user["id"],))
        return [dict(row) for row in rows]

    @app.post("/devices", status_code=201)
    def register_device(body: RegisterDeviceInput, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        count = db.execute(
            "SELECT COUNT(*) FROM devices WHERE user_id=? AND status!='disabled'",
            (user["id"],),
        ).fetchone()[0]
        if count >= user["device_limit"]:
            raise HTTPException(status_code=409, detail="已达到账号设备数量上限")
        device_id = str(uuid.uuid4())
        name = body.name.strip()
        model = body.model.strip()
        if not name or not model:
            raise HTTPException(status_code=422, detail="设备名称和型号不能为空")
        db.execute(
            """INSERT INTO devices(id,user_id,name,model,status,app_version,last_active)
               VALUES(?,?,?,?,'online',?,?)""",
            (device_id, user["id"], name, model, body.app_version, utc_now()),
        )
        audit(db, user["id"], "device.bound", "device", device_id)
        return {"id": device_id, "user_id": user["id"], "name": name, "model": model, "status": "online"}

    @app.delete("/devices/{device_id}")
    def unbind_device(device_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = db.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
        if not device or (user["role"] != "admin" and device["user_id"] != user["id"]):
            raise HTTPException(status_code=404, detail="设备不存在")
        disable_device(db, device_id)
        audit(db, user["id"], "device.unbound", "device", device_id)
        return {"id": device_id, "status": "disabled"}

    @app.patch("/devices/{device_id}")
    def set_device_status(device_id: str, body: DeviceStatusInput, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        if user["role"] != "admin" and body.status == "disabled":
            disable_device(db, device_id)
        elif user["role"] == "admin":
            if body.status == "disabled":
                disable_device(db, device_id)
            else:
                db.execute("UPDATE devices SET status=? WHERE id=?", (body.status, device_id))
        elif body.status != device["status"]:
            raise HTTPException(status_code=403, detail="用户只能解绑自己的设备")
        audit(db, user["id"], "device.status_changed", "device", device_id)
        return {"id": device_id, "status": body.status}

    def _task_dict(db: sqlite3.Connection, row: sqlite3.Row) -> dict:
        versions = db.execute(
            "SELECT version,filename,sha256,notes,created_at FROM task_versions WHERE task_id=? ORDER BY rowid DESC",
            (row["id"],),
        ).fetchall()
        return {
            "id": row["id"],
            "name": row["name"],
            "description": row["description"],
            "category": row["category"],
            "status": row["status"],
            "owner_id": row["owner_id"],
            "source": row["source"],
            "capabilities": json.loads(row["capabilities_json"]),
            "active_version": row["active_version"],
            "versions": [dict(item) for item in versions],
        }

    @app.get("/tasks")
    def list_tasks(user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        if user["role"] == "admin":
            rows = db.execute("SELECT * FROM tasks ORDER BY name")
        else:
            rows = db.execute(
                """SELECT DISTINCT t.* FROM tasks t LEFT JOIN task_grants g ON g.task_id=t.id
                   WHERE (g.user_id=? AND t.status='published') OR t.owner_id=?
                   ORDER BY t.name""",
                (user["id"], user["id"]),
            )
        return [_task_dict(db, row) for row in rows]

    @app.post("/tasks", status_code=201)
    def create_task(body: CreateTaskInput, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        name = body.name.strip()
        if not name or any(not capability.strip() or len(capability) > 100 for capability in body.capabilities):
            raise HTTPException(status_code=422, detail="任务名称及能力说明必须有效")
        task_id = str(uuid.uuid4())
        source = "林管理员发布" if user["role"] == "admin" else f"{user['name']} 上传"
        db.execute(
            """INSERT INTO tasks(id,name,description,category,status,owner_id,source,capabilities_json)
               VALUES(?,?,?,?,'draft',?,?,?)""",
            (task_id, name, body.description.strip(), body.category.strip(), user["id"], source,
             json.dumps([item.strip() for item in body.capabilities], ensure_ascii=False)),
        )
        db.execute("INSERT INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?)", (task_id, user["id"], utc_now()))
        audit(db, user["id"], "task.created", "task", task_id)
        row = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        return _task_dict(db, row)

    @app.get("/tasks/{task_id}/versions")
    def list_task_versions(task_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        _task_for_user(db, task_id, user, require_published=False)
        rows = db.execute(
            "SELECT version,filename,length(content) AS size,sha256,notes,created_at FROM task_versions WHERE task_id=? ORDER BY rowid DESC",
            (task_id,),
        )
        return [dict(row) for row in rows]

    @app.patch("/tasks/{task_id}")
    def set_task_status(
        task_id: str,
        body: TaskStatusInput,
        user: dict = Depends(require_admin),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = db.execute("SELECT id,status FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        if body.status == "published" and not db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? LIMIT 1", (task_id,)
        ).fetchone():
            raise HTTPException(status_code=409, detail="任务没有可发布的版本")
        db.execute("UPDATE tasks SET status=? WHERE id=?", (body.status, task_id))
        if body.status == "paused":
            now = utc_now()
            db.execute(
                "UPDATE remote_runs SET status='expired' WHERE task_id=? AND status='pending'",
                (task_id,),
            )
            db.execute(
                "UPDATE run_authorizations SET revoked_at=? WHERE task_id=? AND revoked_at IS NULL",
                (now, task_id),
            )
        audit(db, user["id"], f"task.{body.status}", "task", task_id)
        return {"id": task_id, "status": body.status}

    @app.patch("/tasks/{task_id}/versions/{version}/activate")
    def activate_task_version(
        task_id: str,
        version: str,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task or (user["role"] != "admin" and task["owner_id"] != user["id"]):
            raise HTTPException(status_code=404, detail="任务不存在")
        version_row = db.execute(
            "SELECT id FROM task_versions WHERE task_id=? AND version=?",
            (task_id, version),
        ).fetchone()
        if not version_row:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        if task["active_version"] != version:
            db.execute("UPDATE tasks SET active_version=? WHERE id=?", (version, task_id))
            audit(db, user["id"], "task.version_activated", "task_version", version_row["id"])
        return {"id": task_id, "active_version": version, "status": task["status"]}

    @app.post("/tasks/{task_id}/grants/{user_id}", status_code=201)
    def grant_task(task_id: str, user_id: str, user: dict = Depends(require_admin), db: sqlite3.Connection = Depends(connection)):
        task = db.execute("SELECT owner_id FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        target = db.execute("SELECT id,status,role FROM users WHERE id=?", (user_id,)).fetchone()
        if not target or target["role"] != "user":
            raise HTTPException(status_code=404, detail="用户不存在")
        if target["status"] != "active":
            raise HTTPException(status_code=409, detail="不能向已停用用户授权任务")
        db.execute("INSERT OR IGNORE INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?)", (task_id, user_id, utc_now()))
        audit(db, user["id"], "task.granted", "task", task_id)
        return {"task_id": task_id, "user_id": user_id}

    @app.get("/tasks/{task_id}/grants")
    def list_task_grants(
        task_id: str,
        user: dict = Depends(require_admin),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = db.execute("SELECT owner_id FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        rows = db.execute(
            """SELECT u.id,u.name,u.email,u.status,u.role
               FROM task_grants g JOIN users u ON u.id=g.user_id
               WHERE g.task_id=? ORDER BY u.name""",
            (task_id,),
        )
        return [{**dict(row), "is_owner": row["id"] == task["owner_id"]} for row in rows]

    @app.delete("/tasks/{task_id}/grants/{user_id}")
    def revoke_task_grant(
        task_id: str,
        user_id: str,
        user: dict = Depends(require_admin),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = db.execute("SELECT owner_id FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        if task["owner_id"] == user_id:
            raise HTTPException(status_code=409, detail="任务所有者始终保留访问权限")
        grant = db.execute(
            "SELECT 1 FROM task_grants WHERE task_id=? AND user_id=?",
            (task_id, user_id),
        ).fetchone()
        if not grant:
            raise HTTPException(status_code=404, detail="任务授权不存在")
        db.execute("DELETE FROM task_grants WHERE task_id=? AND user_id=?", (task_id, user_id))
        now = utc_now()
        db.execute(
            """UPDATE remote_runs SET status='expired'
               WHERE task_id=? AND device_id IN (SELECT id FROM devices WHERE user_id=?) AND status='pending'""",
            (task_id, user_id),
        )
        db.execute(
            """UPDATE run_authorizations SET revoked_at=?
               WHERE task_id=? AND user_id=? AND revoked_at IS NULL""",
            (now, task_id, user_id),
        )
        audit(db, user["id"], "task.grant_revoked", "task", task_id)
        return {"task_id": task_id, "user_id": user_id}

    @app.post("/tasks/{task_id}/versions", status_code=201)
    async def publish_version(
        task_id: str,
        version: str = Form(...),
        script: UploadFile = File(...),
        notes: str = Form(""),
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task or (user["role"] != "admin" and task["owner_id"] != user["id"]):
            raise HTTPException(status_code=404, detail="任务不存在")
        if not VERSION_PATTERN.fullmatch(version):
            raise HTTPException(status_code=422, detail="版本号格式应为 v1.0.0")
        filename = Path((script.filename or "").replace("\\", "/")).name
        if not filename.lower().endswith(".py"):
            raise HTTPException(status_code=422, detail="仅支持 .py 文件")
        if len(filename) > 180 or any(ord(character) < 32 or ord(character) == 127 for character in filename):
            raise HTTPException(status_code=422, detail="脚本文件名无效")
        content = await script.read(MAX_SCRIPT_BYTES + 1)
        if not content or len(content) > MAX_SCRIPT_BYTES:
            raise HTTPException(status_code=413, detail="Python 脚本必须非空且不超过 2 MB")
        digest = hashlib.sha256(content).hexdigest()
        version_id = str(uuid.uuid4())
        try:
            db.execute(
                """INSERT INTO task_versions(id,task_id,version,filename,content,sha256,notes,created_at)
                   VALUES(?,?,?,?,?,?,?,?)""",
                (version_id, task_id, version, filename, content, digest, notes[:500], utc_now()),
            )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=409, detail="该任务版本号已存在") from exc
        if task["status"] != "paused":
            db.execute(
                "UPDATE tasks SET status='published',active_version=? WHERE id=?",
                (version, task_id),
            )
        else:
            db.execute("UPDATE tasks SET active_version=? WHERE id=?", (version, task_id))
        db.execute(
            "INSERT OR IGNORE INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?)",
            (task_id, task["owner_id"], utc_now()),
        )
        audit(db, user["id"], "task.version_published", "task_version", version_id)
        return {"id": version_id, "task_id": task_id, "version": version, "filename": filename, "size": len(content), "sha256": digest, "notes": notes[:500]}

    @app.post("/devices/{device_id}/sync")
    def sync_device(device_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        if device["status"] != "online":
            raise HTTPException(status_code=409, detail="设备离线，无法同步")
        db.execute("UPDATE devices SET last_active=? WHERE id=?", (utc_now(), device_id))
        rows = db.execute(
            """SELECT t.* FROM tasks t JOIN task_grants g ON g.task_id=t.id
               WHERE g.user_id=? AND t.status='published' ORDER BY t.name""",
            (device["user_id"],),
        )
        return {"device_id": device_id, "synced_at": utc_now(), "tasks": [_task_dict(db, row) for row in rows]}

    @app.get("/devices/{device_id}/tasks/{task_id}/versions/{version}/content")
    def download_script(device_id: str, task_id: str, version: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        _task_for_user(db, task_id, {"id": device["user_id"], "role": "user"})
        row = db.execute(
            "SELECT filename,content,sha256 FROM task_versions WHERE task_id=? AND version=?",
            (task_id, version),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        return Response(
            content=row["content"],
            media_type="text/x-python",
            headers={
                "Content-Disposition": _content_disposition(row["filename"]),
                "X-Content-SHA256": row["sha256"],
                "Cache-Control": "no-store",
            },
        )

    @app.post("/remote-runs", status_code=201)
    def create_remote_run(body: RemoteRunInput, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, body.device_id, user)
        if device["status"] != "online":
            raise HTTPException(status_code=409, detail="只能向在线设备发起运行请求")
        task = _task_for_user(db, body.task_id, {"id": device["user_id"], "role": "user"})
        if not task["active_version"]:
            raise HTTPException(status_code=409, detail="任务没有可运行版本")
        request_id = str(uuid.uuid4())
        now = int(time.time())
        expires_at = now + REMOTE_REQUEST_TTL_SECONDS
        db.execute(
            """INSERT INTO remote_runs(id,task_id,version,device_id,requested_by,status,created_at,expires_at)
               VALUES(?,?,?,?,?,'pending',?,?)""",
            (request_id, task["id"], task["active_version"], body.device_id, user["id"], utc_now(), expires_at),
        )
        _expire_requests(db)
        grant = db.execute(
            """SELECT id FROM run_authorizations WHERE task_id=? AND version=? AND device_id=?
               AND user_id=? AND revoked_at IS NULL AND expires_at>?""",
            (task["id"], task["active_version"], body.device_id, device["user_id"], now),
        ).fetchone()
        run_id = None
        status = "pending"
        if grant:
            pending = db.execute("SELECT * FROM remote_runs WHERE id=?", (request_id,)).fetchone()
            run_id = _start_run(db, pending)
            status = "running"
        audit(db, user["id"], "remote_run.requested", "remote_run", request_id)
        return {"id": request_id, "task_id": task["id"], "version": task["active_version"], "device_id": body.device_id, "status": status, "created_at": utc_now(), "expires_at": expires_at, "run_id": run_id}

    @app.post("/devices/{device_id}/runs", status_code=201)
    def start_local_run(device_id: str, body: LocalRunInput, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只能在自己的设备上确认本地运行")
        task = _task_for_user(db, body.task_id, user)
        version = db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? AND version=?",
            (task["id"], body.version),
        ).fetchone()
        if not version:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        run_id = str(uuid.uuid4())
        db.execute(
            """INSERT INTO runs(id,task_id,version,device_id,status,started_at)
               VALUES(?,?,?,?,'running',?)""",
            (run_id, task["id"], body.version, device_id, utc_now()),
        )
        audit(db, user["id"], "run.local_started", "run", run_id)
        return {"id": run_id, "task_id": task["id"], "version": body.version, "device_id": device_id, "status": "running"}

    @app.get("/devices/{device_id}/remote-runs")
    def pending_remote_runs(device_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        _expire_requests(db)
        rows = db.execute(
            """SELECT r.*,t.name AS task_name,t.source,t.capabilities_json,u.name AS requested_by_name
               FROM remote_runs r JOIN tasks t ON t.id=r.task_id
               JOIN users u ON u.id=r.requested_by
               WHERE r.device_id=? AND r.status='pending' ORDER BY r.created_at""",
            (device["id"],),
        )
        return [{
            "id": row["id"], "task_id": row["task_id"], "task": row["task_name"], "version": row["version"],
            "device_id": row["device_id"], "status": row["status"], "requested_by": row["requested_by"],
            "requested_by_name": row["requested_by_name"],
            "created_at": row["created_at"], "expires_at": row["expires_at"], "source": row["source"],
            "capabilities": json.loads(row["capabilities_json"]),
        } for row in rows]

    @app.patch("/remote-runs/{request_id}")
    def decide_remote_run(request_id: str, body: RemoteRunDecision, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        _expire_requests(db)
        request = db.execute("SELECT * FROM remote_runs WHERE id=?", (request_id,)).fetchone()
        if not request:
            raise HTTPException(status_code=404, detail="运行请求不存在")
        device = _device_for_user(db, request["device_id"], user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只有目标设备所属用户可以处理运行请求")
        if request["status"] == "expired":
            raise HTTPException(status_code=410, detail="运行请求已过期")
        if request["status"] != "pending":
            raise HTTPException(status_code=409, detail="运行请求已处理")
        if body.decision == "reject":
            db.execute("UPDATE remote_runs SET status='rejected' WHERE id=?", (request_id,))
            audit(db, user["id"], "remote_run.rejected", "remote_run", request_id)
            return {"id": request_id, "status": "rejected", "run_id": None}
        if device["status"] != "online":
            raise HTTPException(status_code=409, detail="设备当前不可用")
        task = _task_for_user(db, request["task_id"], {"id": device["user_id"], "role": "user"})
        version_exists = db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? AND version=?",
            (task["id"], request["version"]),
        ).fetchone()
        if not version_exists:
            raise HTTPException(status_code=409, detail="请求脚本版本不可用")
        if body.preauthorize:
            grant_id = str(uuid.uuid4())
            db.execute(
                """INSERT INTO run_authorizations(id,task_id,version,device_id,user_id,expires_at)
                   VALUES(?,?,?,?,?,?)
                   ON CONFLICT(task_id,version,device_id) DO UPDATE SET
                     id=excluded.id,user_id=excluded.user_id,expires_at=excluded.expires_at,revoked_at=NULL""",
                (grant_id, task["id"], request["version"], device["id"], device["user_id"], int(time.time()) + PREAUTHORIZATION_TTL_SECONDS),
            )
        run_id = _start_run(db, request)
        audit(db, user["id"], "remote_run.approved", "remote_run", request_id)
        return {"id": request_id, "status": "running", "run_id": run_id}

    @app.post("/runs/{run_id}")
    def update_run(run_id: str, body: RunUpdate, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        run = db.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
        if not run:
            raise HTTPException(status_code=404, detail="运行记录不存在")
        device = _device_for_user(db, run["device_id"], user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只有目标设备所属用户可以更新运行状态")
        if run["status"] != "running":
            raise HTTPException(status_code=409, detail="运行记录已结束")
        db.execute(
            "UPDATE runs SET status=?,finished_at=?,result_summary=? WHERE id=?",
            (body.status, utc_now(), body.result_summary.strip(), run_id),
        )
        if run["remote_run_id"]:
            db.execute("UPDATE remote_runs SET status=? WHERE id=?", (body.status, run["remote_run_id"]))
        audit(db, user["id"], f"run.{body.status}", "run", run_id)
        return {"id": run_id, "status": body.status, "device_id": device["id"]}

    @app.get("/runs")
    def list_runs(user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        query = """SELECT r.id,r.task_id,t.name AS task_name,r.version,r.device_id,d.name AS device_name,
                          d.user_id,u.name AS user_name,r.status,r.started_at,r.finished_at,r.result_summary
                   FROM runs r JOIN tasks t ON t.id=r.task_id JOIN devices d ON d.id=r.device_id
                   JOIN users u ON u.id=d.user_id"""
        rows = db.execute(query + (" ORDER BY r.started_at DESC" if user["role"] == "admin" else " WHERE d.user_id=? ORDER BY r.started_at DESC"),
                          () if user["role"] == "admin" else (user["id"],))
        return [dict(row) for row in rows]

    @app.get("/run-authorizations")
    def list_run_authorizations(user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        if user["role"] == "admin":
            rows = db.execute(
                """SELECT a.*,t.name AS task_name,d.name AS device_name FROM run_authorizations a
                   JOIN tasks t ON t.id=a.task_id JOIN devices d ON d.id=a.device_id
                   WHERE a.revoked_at IS NULL AND a.expires_at>?""",
                (int(time.time()),),
            )
        else:
            rows = db.execute(
                """SELECT a.*,t.name AS task_name,d.name AS device_name FROM run_authorizations a
                   JOIN tasks t ON t.id=a.task_id JOIN devices d ON d.id=a.device_id
                   WHERE a.user_id=? AND a.revoked_at IS NULL AND a.expires_at>?""",
                (user["id"], int(time.time())),
            )
        return [dict(row) for row in rows]

    @app.post("/run-authorizations", status_code=201)
    def create_run_authorization(body: RunAuthorizationInput, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, body.device_id, user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只能为自己的设备创建运行预授权")
        task = _task_for_user(db, body.task_id, user)
        version = db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? AND version=?",
            (task["id"], body.version),
        ).fetchone()
        if not version:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        authorization_id = str(uuid.uuid4())
        expires_at = int(time.time()) + PREAUTHORIZATION_TTL_SECONDS
        db.execute(
            """INSERT INTO run_authorizations(id,task_id,version,device_id,user_id,expires_at)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(task_id,version,device_id) DO UPDATE SET
                 id=excluded.id,user_id=excluded.user_id,expires_at=excluded.expires_at,revoked_at=NULL""",
            (authorization_id, task["id"], body.version, device["id"], user["id"], expires_at),
        )
        row = db.execute(
            "SELECT id FROM run_authorizations WHERE task_id=? AND version=? AND device_id=?",
            (task["id"], body.version, device["id"]),
        ).fetchone()
        audit(db, user["id"], "run_authorization.created", "run_authorization", row["id"])
        return {"id": row["id"], "task_id": task["id"], "version": body.version, "device_id": device["id"], "expires_at": expires_at}

    @app.delete("/run-authorizations/{authorization_id}")
    def revoke_authorization(authorization_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        grant = db.execute("SELECT * FROM run_authorizations WHERE id=? AND revoked_at IS NULL", (authorization_id,)).fetchone()
        if not grant:
            raise HTTPException(status_code=404, detail="预授权不存在")
        if user["role"] != "admin" and grant["user_id"] != user["id"]:
            raise HTTPException(status_code=404, detail="预授权不存在")
        revoked_at = utc_now()
        db.execute("UPDATE run_authorizations SET revoked_at=? WHERE id=?", (revoked_at, authorization_id))
        audit(db, user["id"], "run_authorization.revoked", "run_authorization", authorization_id)
        return {"id": authorization_id, "revoked_at": revoked_at}

    @app.get("/admin/audit")
    def audit_log(user: dict = Depends(require_admin), db: sqlite3.Connection = Depends(connection)):
        return [dict(row) for row in db.execute(
            """SELECT a.id,a.actor_id,u.name AS actor,a.action,a.resource_type,a.resource_id,a.created_at
               FROM audit_events a JOIN users u ON u.id=a.actor_id ORDER BY a.created_at DESC LIMIT 500"""
        )]

    return app


app = create_app()
