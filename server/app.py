from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sqlite3
import smtplib
import ssl
import time
import uuid
from contextlib import asynccontextmanager, contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from email.message import EmailMessage
from pathlib import Path
from typing import Literal
from urllib.parse import quote, urlsplit

import psycopg
from botocore.exceptions import BotoCoreError, ClientError
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

from server.database import POSTGRES_SCHEMES, connect_database
from server.storage import ScriptStorage, create_script_storage


DEFAULT_DB_PATH = Path(__file__).with_name("flowpilot.sqlite3")
LOGGER = logging.getLogger(__name__)
TOKEN_TTL_SECONDS = 60 * 60
SESSION_TTL_SECONDS = 30 * 24 * 60 * 60
LOGIN_FAILURE_LIMIT = 5
LOGIN_FAILURE_WINDOW_SECONDS = 15 * 60
LOGIN_SOURCE_FAILURE_LIMIT = 30
PASSWORD_RESET_CODE_TTL_SECONDS = 10 * 60
PASSWORD_RESET_MAX_ATTEMPTS = 5
PASSWORD_RESET_WINDOW_SECONDS = 15 * 60
REMOTE_REQUEST_TTL_SECONDS = 15 * 60
PREAUTHORIZATION_TTL_SECONDS = 30 * 24 * 60 * 60
DEVICE_OFFLINE_AFTER_SECONDS = 2 * 60
MAX_SCRIPT_BYTES = 2 * 1024 * 1024
VERSION_PATTERN = re.compile(r"^v\d+\.\d+\.\d+$")
DEMO_OCR_SCRIPT_PATH = Path(__file__).with_name("demo_scripts") / "order_status_ocr.py"
DEMO_DOUYIN_LOGIN_SCRIPT_PATH = Path(__file__).with_name("demo_scripts") / "douyin_login_assist.py"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _version_key(version: str) -> tuple[int, int, int]:
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("版本号格式无效")
    return tuple(int(part) for part in version[1:].split("."))


def _device_is_recent(device: sqlite3.Row, now: datetime | None = None) -> bool:
    try:
        last_active = datetime.fromisoformat(device["last_active"])
        if last_active.tzinfo is None:
            last_active = last_active.replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return False
    current = now or datetime.now(timezone.utc)
    return (current - last_active.astimezone(timezone.utc)).total_seconds() <= DEVICE_OFFLINE_AFTER_SECONDS


def _refresh_device_statuses(db: sqlite3.Connection, device_id: str | None = None) -> None:
    now = datetime.now(timezone.utc)
    query = "SELECT id,last_active FROM devices WHERE status='online'"
    parameters = ()
    if device_id is not None:
        query += " AND id=?"
        parameters = (device_id,)
    for device in db.execute(query, parameters).fetchall():
        if not _device_is_recent(device, now):
            db.execute("UPDATE devices SET status='offline' WHERE id=?", (device["id"],))


def _touch_device(db: sqlite3.Connection, device_id: str) -> None:
    db.execute(
        "UPDATE devices SET status='online',last_active=? WHERE id=?",
        (utc_now(), device_id),
    )


def _password_hash(password: str, salt: bytes) -> str:
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 180_000).hex()


@dataclass(frozen=True)
class SMTPSettings:
    host: str
    port: int
    username: str
    password: str
    sender: str
    use_ssl: bool


def _smtp_settings_from_env() -> SMTPSettings | None:
    names = (
        "FLOWPILOT_SMTP_HOST",
        "FLOWPILOT_SMTP_USERNAME",
        "FLOWPILOT_SMTP_PASSWORD",
        "FLOWPILOT_SMTP_FROM",
    )
    values = {name: os.environ.get(name, "").strip() for name in names}
    configured = [value for value in values.values() if value]
    if not configured:
        return None
    if len(configured) != len(names):
        raise ValueError("Password recovery requires all FLOWPILOT_SMTP_HOST, USERNAME, PASSWORD, and FROM settings")
    if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", values["FLOWPILOT_SMTP_FROM"]):
        raise ValueError("FLOWPILOT_SMTP_FROM must be a valid email address")
    try:
        port = int(os.environ.get("FLOWPILOT_SMTP_PORT", "587"))
    except ValueError as error:
        raise ValueError("FLOWPILOT_SMTP_PORT must be an integer") from error
    if not 1 <= port <= 65535:
        raise ValueError("FLOWPILOT_SMTP_PORT must be between 1 and 65535")
    use_ssl_value = os.environ.get("FLOWPILOT_SMTP_USE_SSL", "false").strip().lower()
    if use_ssl_value not in {"true", "false"}:
        raise ValueError("FLOWPILOT_SMTP_USE_SSL must be true or false")
    return SMTPSettings(
        host=values["FLOWPILOT_SMTP_HOST"],
        port=port,
        username=values["FLOWPILOT_SMTP_USERNAME"],
        password=os.environ["FLOWPILOT_SMTP_PASSWORD"],
        sender=values["FLOWPILOT_SMTP_FROM"],
        use_ssl=use_ssl_value == "true",
    )


def _send_password_reset_email(settings: SMTPSettings, recipient: str, code: str) -> None:
    message = EmailMessage()
    message["Subject"] = "FlowPilot 密码重置验证码"
    message["From"] = settings.sender
    message["To"] = recipient
    message.set_content(
        f"你的 FlowPilot 密码重置验证码是：{code}\n"
        f"验证码将在 {PASSWORD_RESET_CODE_TTL_SECONDS // 60} 分钟后失效。"
        "如果你没有申请重置密码，请忽略此邮件。"
    )
    context = ssl.create_default_context()
    if settings.use_ssl:
        with smtplib.SMTP_SSL(settings.host, settings.port, timeout=10, context=context) as server:
            server.login(settings.username, settings.password)
            server.send_message(message)
    else:
        with smtplib.SMTP(settings.host, settings.port, timeout=10) as server:
            server.ehlo()
            server.starttls(context=context)
            server.ehlo()
            server.login(settings.username, settings.password)
            server.send_message(message)


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
def _connect(db_path: str | Path):
    with connect_database(db_path) as connection:
        yield connection


def _initialize_database(db_path: str | Path, seed_demo: bool = True) -> None:
    is_postgres = str(db_path).startswith(POSTGRES_SCHEMES)
    if not is_postgres:
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    with _connect(db_path) as db:
        db.begin_immediate()
        schema = (
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
                content BLOB,
                storage_key TEXT,
                content_size INTEGER NOT NULL DEFAULT 0,
                sha256 TEXT NOT NULL,
                notes TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(task_id, version)
            );
            CREATE TABLE IF NOT EXISTS task_drafts (
                task_id TEXT PRIMARY KEY REFERENCES tasks(id),
                source TEXT NOT NULL,
                filename TEXT NOT NULL,
                notes TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK(revision > 0),
                updated_at TEXT NOT NULL,
                deleted_at TEXT
            );
            CREATE TABLE IF NOT EXISTS pending_script_uploads (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                user_id TEXT NOT NULL REFERENCES users(id),
                version TEXT NOT NULL,
                filename TEXT NOT NULL,
                storage_key TEXT NOT NULL UNIQUE,
                content_size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                notes TEXT NOT NULL,
                expires_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_pending_upload_expiry ON pending_script_uploads(expires_at);
            CREATE TABLE IF NOT EXISTS task_grants (
                task_id TEXT NOT NULL REFERENCES tasks(id),
                user_id TEXT NOT NULL REFERENCES users(id),
                created_at TEXT NOT NULL,
                PRIMARY KEY(task_id, user_id)
            );
            CREATE TABLE IF NOT EXISTS task_publications (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                version TEXT NOT NULL,
                device_id TEXT NOT NULL REFERENCES devices(id),
                published_by TEXT NOT NULL REFERENCES users(id),
                status TEXT NOT NULL CHECK(status IN ('pending', 'accepted', 'revoked')),
                created_at TEXT NOT NULL,
                accepted_at TEXT,
                UNIQUE(task_id, version, device_id)
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
                run_id TEXT,
                stop_requested_at TEXT,
                idempotency_key TEXT
            );
            CREATE TABLE IF NOT EXISTS run_authorizations (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL REFERENCES tasks(id),
                version TEXT NOT NULL,
                device_id TEXT NOT NULL REFERENCES devices(id),
                user_id TEXT NOT NULL REFERENCES users(id),
                expires_at INTEGER NOT NULL,
                revoked_at TEXT,
                all_versions INTEGER NOT NULL DEFAULT 0,
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
            CREATE TABLE IF NOT EXISTS auth_login_attempts (
                attempt_key TEXT PRIMARY KEY,
                window_started INTEGER NOT NULL,
                failure_count INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS auth_sessions (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                revoked_at INTEGER
            );
            CREATE TABLE IF NOT EXISTS auth_password_reset_codes (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                code_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                consumed_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS idx_password_reset_user
                ON auth_password_reset_codes(user_id, created_at);
            CREATE TABLE IF NOT EXISTS auth_password_reset_limits (
                limit_key TEXT PRIMARY KEY,
                window_started INTEGER NOT NULL,
                request_count INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_auth_sessions_user ON auth_sessions(user_id, revoked_at);
            CREATE INDEX IF NOT EXISTS idx_auth_sessions_expiry ON auth_sessions(expires_at);
            CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id            );
            CREATE INDEX IF NOT EXISTS idx_runs_device ON runs(device_id, started_at);
            CREATE INDEX IF NOT EXISTS idx_remote_device ON remote_runs(device_id, status, expires_at);
            """
        )
        if is_postgres:
            schema = schema.replace("BLOB", "BYTEA").replace("COLLATE NOCASE", "")
        db.executescript(schema)
        task_columns = db.table_columns("tasks")
        if "active_version" not in task_columns:
            db.execute("ALTER TABLE tasks ADD COLUMN active_version TEXT")
        version_columns = db.table_columns("task_versions")
        if "storage_key" not in version_columns:
            db.execute("ALTER TABLE task_versions ADD COLUMN storage_key TEXT")
        if "content_size" not in version_columns:
            db.execute("ALTER TABLE task_versions ADD COLUMN content_size INTEGER NOT NULL DEFAULT 0")
        authorization_columns = db.table_columns("run_authorizations")
        if "all_versions" not in authorization_columns:
            db.execute("ALTER TABLE run_authorizations ADD COLUMN all_versions INTEGER NOT NULL DEFAULT 0")
        draft_columns = db.table_columns("task_drafts")
        if "deleted_at" not in draft_columns:
            db.execute("ALTER TABLE task_drafts ADD COLUMN deleted_at TEXT")
        remote_run_columns = db.table_columns("remote_runs")
        if "stop_requested_at" not in remote_run_columns:
            db.execute("ALTER TABLE remote_runs ADD COLUMN stop_requested_at TEXT")
        if "idempotency_key" not in remote_run_columns:
            db.execute("ALTER TABLE remote_runs ADD COLUMN idempotency_key TEXT")
        active_run_conflict = db.execute(
            """SELECT device_id FROM runs WHERE status='running'
               GROUP BY device_id HAVING COUNT(*)>1 LIMIT 1"""
        ).fetchone()
        if active_run_conflict:
            raise RuntimeError(
                "Cannot enforce one active run per device: "
                f"device {active_run_conflict['device_id']} already has multiple running records"
            )
        db.execute(
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_runs_one_active_per_device
               ON runs(device_id) WHERE status='running'"""
        )
        db.execute(
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_remote_run_idempotency
               ON remote_runs(requested_by,device_id,idempotency_key)
               WHERE idempotency_key IS NOT NULL"""
        )
        if seed_demo and db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0:
            _seed_database(db)
        if seed_demo:
            _ensure_demo_douyin_login_task(db)
        db.execute("UPDATE task_versions SET content_size=length(content) WHERE content_size=0 AND content IS NOT NULL")
        db.execute(
            """UPDATE tasks SET active_version=(
                   SELECT version FROM task_versions WHERE task_id=tasks.id
                   ORDER BY created_at DESC,version DESC LIMIT 1
               ) WHERE active_version IS NULL
                 AND EXISTS (SELECT 1 FROM task_versions WHERE task_id=tasks.id)"""
        )


def create_initial_admin(db_path: str | Path, name: str, email: str, password: str) -> str:
    database_path = str(db_path)
    if database_path.startswith(POSTGRES_SCHEMES):
        pass
    elif not Path(database_path).is_absolute():
        raise ValueError("管理员初始化数据库路径必须是绝对路径")
    normalized_email = email.strip().lower()
    normalized_name = name.strip()
    if not normalized_name or len(normalized_name) > 80:
        raise ValueError("管理员姓名必须为 1–80 个字符")
    if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", normalized_email):
        raise ValueError("管理员邮箱格式无效")
    if len(password) < 6 or len(password) > 256:
        raise ValueError("管理员密码必须为 6–256 个字符")

    _initialize_database(database_path, seed_demo=False)
    if not database_path.startswith(POSTGRES_SCHEMES):
        os.chmod(database_path, 0o600)
    user_id = str(uuid.uuid4())
    salt = secrets.token_bytes(16)
    with _connect(database_path) as db:
        db.begin_immediate()
        if db.execute("SELECT 1 FROM users LIMIT 1").fetchone():
            raise ValueError("数据库已有用户；不能通过此命令创建或重置管理员")
        db.execute(
            """INSERT INTO users(id,name,email,role,status,device_limit,password_salt,password_hash,joined_at)
               VALUES(?,?,?,'admin','active',50,?,?,?)""",
            (user_id, normalized_name, normalized_email, salt, _password_hash(password, salt), utc_now()),
        )
    return user_id


def _seed_database(db: sqlite3.Connection) -> None:
    now = utc_now()
    demo_ocr_script = DEMO_OCR_SCRIPT_PATH.read_bytes()
    demo_ocr_v1_6_script = '''ORDER_STATUS_LABELS = ("待支付", "待发货", "待收货", "已完成", "退款")

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
'''.encode("utf-8")
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
            ("t-001", "抖音订单状态 OCR 演示", "仅在设备本地 OCR 当前抖音界面并显示常见订单状态；不点击、不上传识别内容", "OCR 识别", "published", "u-1001", "陈晓上传", json.dumps(["屏幕捕获（需系统授权）", "OCR 文字识别"], ensure_ascii=False)),
            ("t-002", "每日签到提醒", "检查签到状态并发送提醒", "页面识别", "draft", "u-admin", "林管理员发布", json.dumps(["屏幕捕获（需系统授权）", "OCR 文字识别"], ensure_ascii=False)),
        ],
    )
    db.executemany(
        """INSERT INTO task_versions(id,task_id,version,filename,content,sha256,notes,created_at)
           VALUES(?,?,?,?,?,?,?,?)""",
        [
            ("tv-002", "t-001", "v1.3.0", "orders.py", b"# Previous demo script placeholder; not executed.\n", hashlib.sha256(b"# Previous demo script placeholder; not executed.\n").hexdigest(), "历史演示版本", now),
            ("tv-001", "t-001", "v1.4.0", "orders.py", b"# Previous demo script placeholder; not executed.\n", hashlib.sha256(b"# Previous demo script placeholder; not executed.\n").hexdigest(), "历史演示版本", now),
            ("tv-004", "t-001", "v1.5.0", "order_status_ocr.py", b"# Previous demo version; waits for app launch are added in v1.6.0.\n", hashlib.sha256(b"# Previous demo version; waits for app launch are added in v1.6.0.\n").hexdigest(), "历史演示版本", now),
            ("tv-005", "t-001", "v1.6.0", "order_status_ocr.py", demo_ocr_v1_6_script, hashlib.sha256(demo_ocr_v1_6_script).hexdigest(), "等待目标应用进入前台后在设备端 OCR；不点击且不上传 OCR 内容", now),
            ("tv-006", "t-001", "v1.7.0", "order_status_ocr.py", demo_ocr_script, hashlib.sha256(demo_ocr_script).hexdigest(), "等待目标应用进入前台后本地 OCR 并显示识别状态；不点击且不上传 OCR 内容", now),
            ("tv-003", "t-002", "v0.2.0", "checkin.py", b"# Draft demo script placeholder; not executed.\n", hashlib.sha256(b"# Draft demo script placeholder; not executed.\n").hexdigest(), "草稿", now),
        ],
    )
    db.execute("INSERT INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?)", ("t-001", "u-1001", now))


def _ensure_demo_douyin_login_task(db: sqlite3.Connection) -> None:
    demo_user = db.execute(
        "SELECT id FROM users WHERE id='u-1001' AND email='chen.xiao@example.com'"
    ).fetchone()
    if not demo_user:
        return

    now = utc_now()
    script = DEMO_DOUYIN_LOGIN_SCRIPT_PATH.read_bytes()
    task = db.execute("SELECT owner_id FROM tasks WHERE id='t-003'").fetchone()
    if task and task["owner_id"] != demo_user["id"]:
        return
    if not task:
        db.execute(
            """INSERT INTO tasks(id,name,description,category,status,owner_id,source,capabilities_json)
               VALUES(?,?,?,?,?,?,?,?)""",
            (
                "t-003",
                "抖音登录页面引导（手动认证）",
                "从抖音首页进入“我”页面并打开登录表单；用户自行输入手机号并完成验证。不会自动安装 App 或处理认证信息。",
                "OCR 识别",
                "published",
                demo_user["id"],
                "FlowPilot 演示任务",
                json.dumps(
                    ["屏幕捕获（需系统授权）", "OCR 文字识别", "无障碍点击（需系统授权）"],
                    ensure_ascii=False,
                ),
            ),
        )
    if not db.execute(
        "SELECT 1 FROM task_versions WHERE task_id='t-003' AND version='v1.0.2'"
    ).fetchone():
        db.execute(
            """INSERT INTO task_versions(id,task_id,version,filename,content,sha256,notes,created_at)
               VALUES(?,?,?,?,?,?,?,?)""",
            (
                "tv-009",
                "t-003",
                "v1.0.2",
                "douyin_login_assist.py",
                script,
                hashlib.sha256(script).hexdigest(),
                "通过无障碍精确匹配“我”标签进入个人页，再确认登录表单；不处理账号凭据",
                now,
            ),
        )
        db.execute(
            """UPDATE tasks SET active_version='v1.0.2'
               WHERE id='t-003' AND (active_version IS NULL OR active_version IN ('v1.0.0','v1.0.1'))"""
        )
        db.execute(
            """UPDATE tasks
               SET name='抖音登录页面引导（手动认证）',
                   description='从抖音首页进入“我”页面并打开登录表单；用户自行输入手机号并完成验证。不会自动安装 App 或处理认证信息。'
               WHERE id='t-003' AND owner_id=?""",
            (demo_user["id"],),
        )
    db.execute(
        "INSERT OR IGNORE INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?)",
        ("t-003", demo_user["id"], now),
    )


class LoginInput(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=256)


class RegisterInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=6, max_length=256)


class ProfileUpdateInput(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=80)
    email: str | None = Field(default=None, min_length=3, max_length=254)
    current_password: str | None = Field(default=None, min_length=1, max_length=256)


class ChangePasswordInput(BaseModel):
    current_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=6, max_length=256)


class ForgotPasswordInput(BaseModel):
    email: str = Field(min_length=3, max_length=254)


class ResetPasswordInput(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    code: str = Field(pattern=r"^\d{6}$")
    new_password: str = Field(min_length=6, max_length=256)


class CreateUserInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    email: str = Field(min_length=3, max_length=254)
    device_limit: int = Field(default=2, ge=1, le=50)
    password: str = Field(min_length=6, max_length=256)


class CreateTaskInput(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    description: str = Field(default="", max_length=500)
    category: str = Field(default="OCR 识别", max_length=60)
    capabilities: list[str] = Field(default_factory=lambda: ["OCR 文字识别"], max_length=10)


class RegisterDeviceInput(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    model: str = Field(min_length=1, max_length=160)
    app_version: str = Field(default="1.0.0", max_length=32)


class DeviceHeartbeatInput(BaseModel):
    app_version: str | None = Field(default=None, min_length=1, max_length=32)


class UserUpdateInput(BaseModel):
    status: Literal["active", "disabled"] | None = None
    device_limit: int | None = Field(default=None, ge=1, le=50)


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
    all_versions: bool = False


class TaskPublicationInput(BaseModel):
    device_id: str
    version: str


class RunUpdate(BaseModel):
    status: Literal["success", "failure", "stopped"]
    result_summary: str = Field(default="", max_length=500)


class ScriptUploadRequest(BaseModel):
    version: str = Field(max_length=32)
    filename: str = Field(max_length=180)
    content_size: int = Field(ge=1, le=MAX_SCRIPT_BYTES)
    sha256: str = Field(pattern=r"^[a-fA-F0-9]{64}$")
    notes: str = Field(default="", max_length=500)


class ScriptUploadCompletion(BaseModel):
    upload_id: str


class ScriptDraftInput(BaseModel):
    source: str = Field(max_length=MAX_SCRIPT_BYTES)
    filename: str = Field(min_length=1, max_length=180)
    notes: str = Field(default="", max_length=500)
    expected_revision: int = Field(ge=0)


def create_app(db_path: str | Path | None = None, token_secret: bytes | None = None) -> FastAPI:
    environment = os.environ.get("FLOWPILOT_ENV", "development").strip().lower()
    if environment not in {"development", "test", "staging", "production"}:
        raise ValueError("FLOWPILOT_ENV must be development, test, staging, or production")
    hardened_environment = environment in {"staging", "production"}

    configured_db_path = (
        db_path
        or os.environ.get("FLOWPILOT_DATABASE_URL")
        or os.environ.get("FLOWPILOT_DB_PATH")
    )
    if environment == "staging" and (
        not configured_db_path or not str(configured_db_path).startswith(POSTGRES_SCHEMES)
    ):
        raise ValueError("Staging requires a PostgreSQL FLOWPILOT_DATABASE_URL")
    if hardened_environment:
        if not configured_db_path:
            raise ValueError(f"{environment.title()} requires FLOWPILOT_DATABASE_URL or FLOWPILOT_DB_PATH")
        if str(configured_db_path).startswith(POSTGRES_SCHEMES):
            database_path: str | Path = str(configured_db_path)
        else:
            database_path = Path(configured_db_path)
            if not database_path.is_absolute() or database_path == DEFAULT_DB_PATH:
                raise ValueError(f"{environment.title()} FLOWPILOT_DB_PATH must be an explicit absolute persistent path")
        if not os.environ.get("FLOWPILOT_TOKEN_SECRET"):
            raise ValueError(f"{environment.title()} requires FLOWPILOT_TOKEN_SECRET")
        if not os.environ.get("FLOWPILOT_CORS_ORIGINS"):
            raise ValueError(f"{environment.title()} requires explicit FLOWPILOT_CORS_ORIGINS")
    else:
        database_path = (
            str(configured_db_path)
            if configured_db_path and str(configured_db_path).startswith(POSTGRES_SCHEMES)
            else Path(configured_db_path or DEFAULT_DB_PATH)
        )
    script_storage = create_script_storage(required=False)
    smtp_settings = _smtp_settings_from_env()
    if (
        hardened_environment
        and str(database_path).startswith(POSTGRES_SCHEMES)
        and not script_storage
    ):
        raise ValueError(f"{environment.title()} PostgreSQL requires an S3-compatible script storage configuration")

    configured_secret = token_secret
    if configured_secret is None and os.environ.get("FLOWPILOT_TOKEN_SECRET"):
        configured_secret = os.environ["FLOWPILOT_TOKEN_SECRET"].encode()
    if configured_secret is not None and len(configured_secret) < 32:
        raise ValueError("FLOWPILOT_TOKEN_SECRET must contain at least 32 bytes")
    secret = configured_secret if configured_secret is not None else secrets.token_bytes(32)
    if hardened_environment and token_secret is not None:
        raise ValueError(f"{environment.title()} token secret must be configured through FLOWPILOT_TOKEN_SECRET")

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        _initialize_database(database_path, seed_demo=not hardened_environment)
        if hardened_environment:
            with _connect(database_path) as db:
                demo_accounts = db.execute(
                    "SELECT email FROM users WHERE email IN ('admin@example.com','chen.xiao@example.com','zhou.ke@example.com','yifan.xu@example.com') LIMIT 1"
                ).fetchone()
            if demo_accounts:
                raise RuntimeError(f"{environment.title()} database contains demo accounts; use a fresh database and bootstrap a production administrator")
            if not str(database_path).startswith(POSTGRES_SCHEMES):
                os.chmod(database_path, 0o600)
        yield

    app = FastAPI(
        title="FlowPilot API",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None if hardened_environment else "/docs",
        redoc_url=None if hardened_environment else "/redoc",
        openapi_url=None if hardened_environment else "/openapi.json",
    )
    app.state.db_path = database_path
    app.state.script_storage = script_storage
    app.state.database_backend = "postgresql" if str(database_path).startswith(POSTGRES_SCHEMES) else "sqlite"
    app.state.token_secret = secret
    app.state.environment = environment
    app.state.smtp_settings = smtp_settings
    configured_origins = [
        origin.strip()
        for origin in os.environ.get("FLOWPILOT_CORS_ORIGINS", "").split(",")
        if origin.strip()
    ]
    if hardened_environment:
        if not configured_origins:
            raise ValueError(f"{environment.title()} requires at least one exact FLOWPILOT_CORS_ORIGINS entry")
        for origin in configured_origins:
            parsed = urlsplit(origin)
            permitted_scheme = parsed.scheme == "https" if environment == "production" else parsed.scheme in {"http", "https"}
            if (
                not permitted_scheme
                or not parsed.hostname
                or parsed.path
                or parsed.query
                or parsed.fragment
                or parsed.username
                or parsed.password
                or "*" in origin
            ):
                requirement = "HTTPS" if environment == "production" else "HTTP(S)"
                raise ValueError(f"{environment.title()} CORS origins must be exact {requirement} origins without paths or wildcards")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(dict.fromkeys(configured_origins if hardened_environment else [
            "http://localhost:8000",
            "http://127.0.0.1:8000",
            "http://localhost:8765",
            "http://127.0.0.1:8765",
            *configured_origins,
        ])),
        allow_credentials=False,
        allow_methods=["GET", "POST", "PATCH", "DELETE"],
        allow_headers=["Authorization", "Content-Type", "Idempotency-Key"],
    )

    def connection():
        with _connect(database_path) as db:
            yield db

    def current_user(
        request: Request,
        authorization: str | None = Header(default=None),
        db: sqlite3.Connection = Depends(connection),
    ):
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="需要登录")
        payload = _token_decode(authorization[7:], secret)
        session_id = payload.get("sid")
        if not isinstance(session_id, str):
            raise HTTPException(status_code=401, detail="登录会话无效，请重新登录")
        session = db.execute(
            "SELECT user_id,expires_at FROM auth_sessions WHERE id=? AND revoked_at IS NULL AND expires_at>?",
            (session_id, int(time.time())),
        ).fetchone()
        if not session or session["user_id"] != payload.get("sub"):
            raise HTTPException(status_code=401, detail="登录会话已失效，请重新登录")
        user = db.execute(
            "SELECT id,name,email,role,status,device_limit FROM users WHERE id=?",
            (payload.get("sub"),),
        ).fetchone()
        if not user or user["status"] != "active":
            raise HTTPException(status_code=401, detail="账号不可用")
        request.state.auth_session_id = session_id
        request.state.auth_session_expires_at = session["expires_at"]
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
            "UPDATE remote_runs SET status='expired' WHERE device_id=? AND status IN ('pending','queued')",
            (device_id,),
        )
        db.execute(
            "UPDATE task_publications SET status='revoked' WHERE device_id=? AND status!='revoked'",
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
            if task["owner_id"] != user["id"] and not grant:
                raise HTTPException(status_code=404, detail="任务不存在")
        if require_published and task["status"] != "published":
            raise HTTPException(status_code=409, detail="任务当前不可运行")
        return task

    def _editable_task(db: sqlite3.Connection, task_id: str, user: dict) -> sqlite3.Row:
        task = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task or (user["role"] != "admin" and task["owner_id"] != user["id"]):
            raise HTTPException(status_code=404, detail="任务不存在")
        return task

    def _expire_requests(db: sqlite3.Connection, request_id: str | None = None) -> None:
        query = "UPDATE remote_runs SET status='expired' WHERE status='pending' AND expires_at<=?"
        parameters: tuple = (int(time.time()),)
        if request_id is not None:
            query += " AND id=?"
            parameters += (request_id,)
        expired = db.execute(
            "SELECT DISTINCT device_id FROM remote_runs WHERE status='pending' AND expires_at<=?"
            + (" AND id=?" if request_id is not None else ""),
            parameters,
        ).fetchall()
        db.execute(query, parameters)
        for row in expired:
            _activate_next_remote_request(db, row["device_id"])

    def _has_earlier_remote_request(db: sqlite3.Connection, request: sqlite3.Row) -> bool:
        active_run = db.execute(
            "SELECT 1 FROM runs WHERE device_id=? AND status='running' LIMIT 1",
            (request["device_id"],),
        ).fetchone()
        if active_run:
            return True
        earlier_pending = db.execute(
            """SELECT 1 FROM remote_runs
               WHERE device_id=? AND status='pending'
                 AND (created_at<? OR (created_at=? AND id<?))
               LIMIT 1""",
            (request["device_id"], request["created_at"], request["created_at"], request["id"]),
        ).fetchone()
        return earlier_pending is not None

    def _remote_request_status(db: sqlite3.Connection, request: sqlite3.Row) -> str:
        if request["status"] == "pending" and _has_earlier_remote_request(db, request):
            return "queued"
        return request["status"]

    def _activate_next_remote_request(db: sqlite3.Connection, device_id: str) -> None:
        if db.execute(
            "SELECT 1 FROM runs WHERE device_id=? AND status='running' LIMIT 1",
            (device_id,),
        ).fetchone():
            return
        request = db.execute(
            """SELECT id,expires_at FROM remote_runs
               WHERE device_id=? AND status='pending'
               ORDER BY created_at,id LIMIT 1""",
            (device_id,),
        ).fetchone()
        if request and request["expires_at"] > int(time.time()) + REMOTE_REQUEST_TTL_SECONDS:
            db.execute(
                "UPDATE remote_runs SET expires_at=? WHERE id=? AND status='pending'",
                (int(time.time()) + REMOTE_REQUEST_TTL_SECONDS, request["id"]),
            )

    def _ensure_device_idle(db: sqlite3.Connection, device_id: str) -> None:
        active_run = db.execute(
            "SELECT 1 FROM runs WHERE device_id=? AND status='running' LIMIT 1",
            (device_id,),
        ).fetchone()
        if active_run:
            raise HTTPException(status_code=409, detail="设备当前有任务运行")

    def _start_run(db: sqlite3.Connection, request: sqlite3.Row) -> str:
        run_id = str(uuid.uuid4())
        try:
            db.execute(
                """INSERT INTO runs(id,task_id,version,device_id,remote_run_id,status,started_at)
                   VALUES(?,?,?,?,?,'running',?)""",
                (run_id, request["task_id"], request["version"], request["device_id"], request["id"], utc_now()),
            )
        except sqlite3.IntegrityError as error:
            raise HTTPException(status_code=409, detail="设备当前有任务运行") from error
        db.execute("UPDATE remote_runs SET status='running',run_id=? WHERE id=?", (run_id, request["id"]))
        return run_id

    @app.get("/health/live")
    def liveness():
        return {"status": "ok"}

    @app.get("/health")
    @app.get("/health/ready")
    def readiness():
        try:
            with _connect(database_path) as db:
                db.execute("SELECT 1 FROM users LIMIT 1").fetchone()
        except (OSError, sqlite3.Error, psycopg.Error) as error:
            LOGGER.exception("Readiness check failed")
            raise HTTPException(status_code=503, detail="database unavailable") from error
        if app.state.script_storage:
            try:
                app.state.script_storage.check_ready()
            except (BotoCoreError, ClientError) as error:
                LOGGER.exception("Script storage readiness check failed")
                raise HTTPException(status_code=503, detail="script storage unavailable") from error
        return {"status": "ok"}

    @app.post("/auth/login")
    def login(body: LoginInput, request: Request, db: sqlite3.Connection = Depends(connection)):
        email = body.email.strip().lower()
        source = request.client.host if request.client else "unknown"
        source_key = hmac.new(secret, f"login-source\0{source}".encode(), hashlib.sha256).hexdigest()
        account_key = hmac.new(
            secret,
            f"login-account\0{source}\0{email}".encode(),
            hashlib.sha256,
        ).hexdigest()
        now = int(time.time())
        attempt_keys = (account_key, source_key)

        user = db.execute("SELECT * FROM users WHERE email=?", (email,)).fetchone()
        credentials_valid = bool(
            user
            and user["status"] == "active"
            and hmac.compare_digest(
                _password_hash(body.password, user["password_salt"]),
                user["password_hash"],
            )
        )
        if not credentials_valid:
            db.begin_immediate()
            for attempt_key, failure_limit in zip(
                attempt_keys,
                (LOGIN_FAILURE_LIMIT, LOGIN_SOURCE_FAILURE_LIMIT),
            ):
                attempt = db.execute(
                    "SELECT window_started,failure_count FROM auth_login_attempts WHERE attempt_key=?",
                    (attempt_key,),
                ).fetchone()
                if (
                    attempt
                    and now - attempt["window_started"] < LOGIN_FAILURE_WINDOW_SECONDS
                    and attempt["failure_count"] >= failure_limit
                ):
                    retry_after = max(1, attempt["window_started"] + LOGIN_FAILURE_WINDOW_SECONDS - now)
                    raise HTTPException(
                        status_code=429,
                        detail="登录尝试过多，请稍后再试",
                        headers={"Retry-After": str(retry_after)},
                    )
            for attempt_key in attempt_keys:
                db.execute(
                    """INSERT INTO auth_login_attempts(attempt_key,window_started,failure_count)
                       VALUES(?,?,1)
                       ON CONFLICT(attempt_key) DO UPDATE SET
                         window_started=CASE
                           WHEN auth_login_attempts.window_started<=? THEN excluded.window_started
                           ELSE auth_login_attempts.window_started
                         END,
                         failure_count=CASE
                           WHEN auth_login_attempts.window_started<=? THEN 1
                           ELSE auth_login_attempts.failure_count+1
                         END""",
                    (
                        attempt_key,
                        now,
                        now - LOGIN_FAILURE_WINDOW_SECONDS,
                        now - LOGIN_FAILURE_WINDOW_SECONDS,
                    ),
                )
            db.execute(
                "DELETE FROM auth_login_attempts WHERE window_started<=?",
                (now - LOGIN_FAILURE_WINDOW_SECONDS * 2,),
            )
            db.commit()
            raise HTTPException(status_code=401, detail="邮箱或密码错误")
        db.begin_immediate()
        db.execute("DELETE FROM auth_login_attempts WHERE attempt_key=?", (account_key,))
        now = int(time.time())
        session_id = str(uuid.uuid4())
        session_expires_at = now + SESSION_TTL_SECONDS
        db.execute(
            "DELETE FROM auth_sessions WHERE user_id=? AND (expires_at<=? OR revoked_at IS NOT NULL)",
            (user["id"], now),
        )
        db.execute(
            "INSERT INTO auth_sessions(id,user_id,created_at,expires_at) VALUES(?,?,?,?)",
            (session_id, user["id"], now, session_expires_at),
        )
        db.commit()
        token = _token_encode(
            {"sub": user["id"], "sid": session_id, "exp": now + TOKEN_TTL_SECONDS},
            secret,
        )
        return {
            "access_token": token,
            "token_type": "bearer",
            "expires_in": TOKEN_TTL_SECONDS,
            "user": {key: user[key] for key in ("id", "name", "email", "role", "status", "device_limit")},
        }

    @app.post("/auth/register", status_code=201)
    def register(body: RegisterInput, db: sqlite3.Connection = Depends(connection)):
        email = body.email.strip().lower()
        name = body.name.strip()
        if not name or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
            raise HTTPException(status_code=422, detail="请提供有效的姓名和邮箱")
        user_id = str(uuid.uuid4())
        salt = secrets.token_bytes(16)
        try:
            db.begin_immediate()
            db.execute(
                """INSERT INTO users(id,name,email,role,status,device_limit,password_salt,password_hash,joined_at)
                   VALUES(?,?,?,'user','active',2,?,?,?)""",
                (user_id, name, email, salt, _password_hash(body.password, salt), utc_now()),
            )
        except (sqlite3.IntegrityError, psycopg.IntegrityError) as exc:
            raise HTTPException(status_code=409, detail="该邮箱已注册") from exc
        return {
            "id": user_id,
            "name": name,
            "email": email,
            "role": "user",
            "status": "active",
            "device_limit": 2,
        }

    @app.get("/me")
    def me(user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        row = db.execute(
            "SELECT id,name,email,role,status,device_limit,joined_at FROM users WHERE id=?",
            (user["id"],),
        ).fetchone()
        return dict(row)

    @app.patch("/me")
    def update_profile(
        body: ProfileUpdateInput,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        if body.name is None and body.email is None:
            raise HTTPException(status_code=422, detail="至少提供一个要更新的个人资料字段")
        changes = {}
        if body.name is not None:
            name = body.name.strip()
            if not name:
                raise HTTPException(status_code=422, detail="姓名不能为空")
            changes["name"] = name
        if body.email is not None:
            email = body.email.strip().lower()
            if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
                raise HTTPException(status_code=422, detail="请提供有效的邮箱")
            if email != user["email"]:
                account = db.execute(
                    "SELECT password_salt,password_hash FROM users WHERE id=?",
                    (user["id"],),
                ).fetchone()
                if not body.current_password or not hmac.compare_digest(
                    _password_hash(body.current_password, account["password_salt"]),
                    account["password_hash"],
                ):
                    raise HTTPException(status_code=403, detail="修改邮箱需要验证当前密码")
                changes["email"] = email
        if changes:
            assignments = ",".join(f"{column}=?" for column in changes)
            try:
                db.execute(
                    f"UPDATE users SET {assignments} WHERE id=?",
                    (*changes.values(), user["id"]),
                )
            except (sqlite3.IntegrityError, psycopg.IntegrityError) as error:
                raise HTTPException(status_code=409, detail="该邮箱已被其他账号使用") from error
            audit(db, user["id"], "user.profile_updated", "user", user["id"])
        row = db.execute(
            "SELECT id,name,email,role,status,device_limit,joined_at FROM users WHERE id=?",
            (user["id"],),
        ).fetchone()
        return dict(row)

    @app.post("/auth/change-password")
    def change_password(
        body: ChangePasswordInput,
        request: Request,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        account = db.execute(
            "SELECT password_salt,password_hash FROM users WHERE id=?",
            (user["id"],),
        ).fetchone()
        current_hash = _password_hash(body.current_password, account["password_salt"])
        if not hmac.compare_digest(current_hash, account["password_hash"]):
            raise HTTPException(status_code=403, detail="当前密码不正确")
        if hmac.compare_digest(
            _password_hash(body.new_password, account["password_salt"]),
            account["password_hash"],
        ):
            raise HTTPException(status_code=422, detail="新密码不能与当前密码相同")
        salt = secrets.token_bytes(16)
        db.begin_immediate()
        account = db.execute(
            "SELECT password_salt,password_hash FROM users WHERE id=?",
            (user["id"],),
        ).fetchone()
        if not hmac.compare_digest(
            _password_hash(body.current_password, account["password_salt"]),
            account["password_hash"],
        ):
            raise HTTPException(status_code=403, detail="当前密码不正确")
        db.execute(
            "UPDATE users SET password_salt=?,password_hash=? WHERE id=?",
            (salt, _password_hash(body.new_password, salt), user["id"]),
        )
        db.execute(
            """UPDATE auth_sessions SET revoked_at=?
               WHERE user_id=? AND id!=? AND revoked_at IS NULL""",
            (int(time.time()), user["id"], request.state.auth_session_id),
        )
        audit(db, user["id"], "user.password_changed", "user", user["id"])
        return {"status": "changed", "other_sessions_revoked": True}

    @app.get("/auth/recovery-status")
    def password_recovery_status():
        return {"enabled": app.state.smtp_settings is not None}

    @app.post("/auth/forgot-password", status_code=202)
    def forgot_password(
        body: ForgotPasswordInput,
        request: Request,
        db: sqlite3.Connection = Depends(connection),
    ):
        settings = app.state.smtp_settings
        if settings is None:
            raise HTTPException(status_code=503, detail="密码找回服务尚未配置")
        email = body.email.strip().lower()
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
            raise HTTPException(status_code=422, detail="请提供有效的邮箱")

        now = int(time.time())
        source = request.client.host if request.client else "unknown"
        rate_keys = (
            (
                hmac.new(secret, f"password-reset-email\0{email}".encode(), hashlib.sha256).hexdigest(),
                5,
            ),
            (
                hmac.new(secret, f"password-reset-source\0{source}".encode(), hashlib.sha256).hexdigest(),
                30,
            ),
        )
        db.begin_immediate()
        retry_after = 0
        for limit_key, limit in rate_keys:
            attempt = db.execute(
                "SELECT window_started,request_count FROM auth_password_reset_limits WHERE limit_key=?",
                (limit_key,),
            ).fetchone()
            if (
                attempt
                and now - attempt["window_started"] < PASSWORD_RESET_WINDOW_SECONDS
                and attempt["request_count"] >= limit
            ):
                retry_after = max(
                    retry_after,
                    attempt["window_started"] + PASSWORD_RESET_WINDOW_SECONDS - now,
                )
        if retry_after:
            db.commit()
            raise HTTPException(
                status_code=429,
                detail="请求过于频繁，请稍后重试",
                headers={"Retry-After": str(retry_after)},
            )
        for limit_key, _ in rate_keys:
            db.execute(
                """INSERT INTO auth_password_reset_limits(limit_key,window_started,request_count)
                   VALUES(?,?,1)
                   ON CONFLICT(limit_key) DO UPDATE SET
                     window_started=CASE
                       WHEN auth_password_reset_limits.window_started<=? THEN excluded.window_started
                       ELSE auth_password_reset_limits.window_started
                     END,
                     request_count=CASE
                       WHEN auth_password_reset_limits.window_started<=? THEN 1
                       ELSE auth_password_reset_limits.request_count+1
                     END""",
                (
                    limit_key,
                    now,
                    now - PASSWORD_RESET_WINDOW_SECONDS,
                    now - PASSWORD_RESET_WINDOW_SECONDS,
                ),
            )
        db.execute(
            "DELETE FROM auth_password_reset_limits WHERE window_started<=?",
            (now - PASSWORD_RESET_WINDOW_SECONDS * 2,),
        )
        db.execute(
            """DELETE FROM auth_password_reset_codes
               WHERE expires_at<=? OR consumed_at<=?""",
            (now, now - 24 * 60 * 60),
        )

        user = db.execute(
            "SELECT id FROM users WHERE email=? AND status='active'",
            (email,),
        ).fetchone()
        reset_code = None
        reset_code_id = None
        if user:
            reset_code = f"{secrets.randbelow(1_000_000):06d}"
            reset_code_id = str(uuid.uuid4())
            code_hash = hmac.new(
                secret,
                f"password-reset\0{user['id']}\0{reset_code}".encode(),
                hashlib.sha256,
            ).hexdigest()
            db.execute(
                """UPDATE auth_password_reset_codes SET consumed_at=?
                   WHERE user_id=? AND consumed_at IS NULL""",
                (now, user["id"]),
            )
            db.execute(
                """INSERT INTO auth_password_reset_codes(
                       id,user_id,code_hash,created_at,expires_at
                   ) VALUES(?,?,?,?,?)""",
                (
                    reset_code_id,
                    user["id"],
                    code_hash,
                    now,
                    now + PASSWORD_RESET_CODE_TTL_SECONDS,
                ),
            )
        db.commit()

        if user and reset_code:
            try:
                _send_password_reset_email(settings, email, reset_code)
            except (OSError, smtplib.SMTPException) as error:
                db.execute(
                    "UPDATE auth_password_reset_codes SET consumed_at=? WHERE id=?",
                    (int(time.time()), reset_code_id),
                )
                db.commit()
                LOGGER.error("Password reset email delivery failed: %s", type(error).__name__)
                raise HTTPException(status_code=503, detail="验证码邮件发送失败，请稍后重试") from error
        return {"message": "如果该邮箱对应可用账号，已发送验证码。"}

    @app.post("/auth/reset-password")
    def reset_password(body: ResetPasswordInput, db: sqlite3.Connection = Depends(connection)):
        if app.state.smtp_settings is None:
            raise HTTPException(status_code=503, detail="密码找回服务尚未配置")
        email = body.email.strip().lower()
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
            raise HTTPException(status_code=422, detail="请提供有效的邮箱")

        now = int(time.time())
        db.begin_immediate()
        user = db.execute(
            "SELECT id FROM users WHERE email=? AND status='active'",
            (email,),
        ).fetchone()
        reset = (
            db.execute(
                """SELECT id,code_hash,attempts FROM auth_password_reset_codes
                   WHERE user_id=? AND consumed_at IS NULL AND expires_at>?
                   ORDER BY created_at DESC LIMIT 1""",
                (user["id"], now),
            ).fetchone()
            if user
            else None
        )
        if not reset:
            raise HTTPException(status_code=400, detail="验证码无效或已过期")
        if reset["attempts"] >= PASSWORD_RESET_MAX_ATTEMPTS:
            db.execute(
                "UPDATE auth_password_reset_codes SET consumed_at=? WHERE id=?",
                (now, reset["id"]),
            )
            db.commit()
            raise HTTPException(status_code=400, detail="验证码无效或已过期")

        submitted_hash = hmac.new(
            secret,
            f"password-reset\0{user['id']}\0{body.code}".encode(),
            hashlib.sha256,
        ).hexdigest()
        if not hmac.compare_digest(submitted_hash, reset["code_hash"]):
            db.execute(
                "UPDATE auth_password_reset_codes SET attempts=attempts+1 WHERE id=?",
                (reset["id"],),
            )
            db.commit()
            raise HTTPException(status_code=400, detail="验证码无效或已过期")

        salt = secrets.token_bytes(16)
        db.execute(
            "UPDATE users SET password_salt=?,password_hash=? WHERE id=?",
            (salt, _password_hash(body.new_password, salt), user["id"]),
        )
        db.execute(
            """UPDATE auth_sessions SET revoked_at=?
               WHERE user_id=? AND revoked_at IS NULL""",
            (now, user["id"]),
        )
        db.execute(
            """UPDATE auth_password_reset_codes SET consumed_at=?
               WHERE user_id=? AND consumed_at IS NULL""",
            (now, user["id"]),
        )
        audit(db, user["id"], "user.password_reset", "user", user["id"])
        db.commit()
        return {"status": "changed", "sessions_revoked": True}

    @app.post("/auth/refresh")
    def refresh_access_token(request: Request, user: dict = Depends(current_user)):
        session = request.state.auth_session_id
        session_expires_in = max(0, request.state.auth_session_expires_at - int(time.time()))
        token = _token_encode(
            {"sub": user["id"], "sid": session, "exp": int(time.time()) + TOKEN_TTL_SECONDS},
            secret,
        )
        return {
            "access_token": token,
            "token_type": "bearer",
            "expires_in": TOKEN_TTL_SECONDS,
            "session_expires_in": session_expires_in,
        }

    @app.post("/auth/logout", status_code=204)
    def logout(request: Request, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        db.execute(
            "UPDATE auth_sessions SET revoked_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL",
            (int(time.time()), request.state.auth_session_id, user["id"]),
        )
        return Response(status_code=204)

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
    def update_user(user_id: str, body: UserUpdateInput, user: dict = Depends(require_admin), db: sqlite3.Connection = Depends(connection)):
        if body.status is None and body.device_limit is None:
            raise HTTPException(status_code=422, detail="至少提供一个要更新的用户字段")
        target = db.execute("SELECT id,status,device_limit FROM users WHERE id=? AND role='user'", (user_id,)).fetchone()
        if not target:
            raise HTTPException(status_code=404, detail="用户不存在")
        if body.device_limit is not None:
            active_devices = db.execute(
                "SELECT COUNT(*) FROM devices WHERE user_id=? AND status!='disabled'",
                (user_id,),
            ).fetchone()[0]
            if body.device_limit < active_devices:
                raise HTTPException(
                    status_code=409,
                    detail=f"用户当前有 {active_devices} 台已启用设备，设备上限不能低于此数量",
                )
            db.execute("UPDATE users SET device_limit=? WHERE id=?", (body.device_limit, user_id))
            if body.device_limit != target["device_limit"]:
                audit(db, user["id"], "user.device_limit_updated", "user", user_id)
        if body.status is not None:
            db.execute("UPDATE users SET status=? WHERE id=?", (body.status, user_id))
        if body.status == "disabled" and target["status"] != "disabled":
            now = utc_now()
            db.execute(
                "UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
                (int(time.time()), user_id),
            )
            db.execute("UPDATE run_authorizations SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", (now, user_id))
            db.execute(
                """UPDATE task_publications SET status='revoked'
                   WHERE device_id IN (SELECT id FROM devices WHERE user_id=?) AND status!='revoked'""",
                (user_id,),
            )
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
        if body.status is not None and body.status != target["status"]:
            audit(db, user["id"], f"user.{body.status}", "user", user_id)
        updated = db.execute(
            "SELECT id,status,device_limit FROM users WHERE id=?",
            (user_id,),
        ).fetchone()
        return dict(updated)

    @app.post("/users/{user_id}/sessions/revoke")
    def revoke_user_sessions(
        user_id: str,
        user: dict = Depends(require_admin),
        db: sqlite3.Connection = Depends(connection),
    ):
        target = db.execute(
            "SELECT id FROM users WHERE id=? AND role='user'",
            (user_id,),
        ).fetchone()
        if not target:
            raise HTTPException(status_code=404, detail="用户不存在")
        revoked_at = int(time.time())
        result = db.execute(
            "UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
            (revoked_at, user_id),
        )
        audit(db, user["id"], "user.sessions_revoked", "user", user_id)
        return {"revoked_sessions": result.rowcount}

    @app.get("/devices")
    def list_devices(user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        _refresh_device_statuses(db)
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

    def _task_dict(
        db: sqlite3.Connection,
        row: sqlite3.Row,
        viewer: dict | None = None,
    ) -> dict:
        versions = db.execute(
            "SELECT version,filename,sha256,notes,created_at FROM task_versions WHERE task_id=? ORDER BY created_at DESC,version DESC",
            (row["id"],),
        ).fetchall()
        task = {
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
        if viewer is not None:
            if viewer["role"] == "admin":
                assigned_device_count = db.execute(
                    """SELECT COUNT(*) FROM devices d JOIN users u ON u.id=d.user_id
                       LEFT JOIN task_grants g ON g.task_id=? AND g.user_id=u.id
                       WHERE (u.id=? OR g.user_id IS NOT NULL)
                         AND d.status!='disabled' AND u.status='active'""",
                    (row["id"], row["owner_id"]),
                ).fetchone()[0]
            else:
                assigned_device_count = db.execute(
                    "SELECT COUNT(*) FROM devices WHERE user_id=? AND status!='disabled'",
                    (viewer["id"],),
                ).fetchone()[0]
            task["assigned_device_count"] = assigned_device_count
        return task

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
        return [_task_dict(db, row, user) for row in rows]

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
        return _task_dict(db, row, user)

    @app.get("/tasks/{task_id}/draft")
    def get_task_draft(
        task_id: str,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = _editable_task(db, task_id, user)
        draft = db.execute("SELECT * FROM task_drafts WHERE task_id=?", (task_id,)).fetchone()
        if draft and not draft["deleted_at"]:
            return {
                "task_id": task_id,
                "source": draft["source"],
                "filename": draft["filename"],
                "notes": draft["notes"],
                "revision": draft["revision"],
                "updated_at": draft["updated_at"],
                "saved": True,
            }
        version = db.execute(
            "SELECT filename,notes FROM task_versions WHERE task_id=? AND version=?",
            (task_id, task["active_version"]),
        ).fetchone() if task["active_version"] else None
        return {
            "task_id": task_id,
            "source": "",
            "filename": version["filename"] if version else "main.py",
            "notes": "",
            "revision": draft["revision"] if draft else 0,
            "updated_at": None if not draft or draft["deleted_at"] else draft["updated_at"],
            "saved": False,
        }

    @app.put("/tasks/{task_id}/draft")
    def save_task_draft(
        task_id: str,
        body: ScriptDraftInput,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        _editable_task(db, task_id, user)
        filename = Path(body.filename.replace("\\", "/")).name
        content_size = len(body.source.encode("utf-8"))
        if content_size > MAX_SCRIPT_BYTES:
            raise HTTPException(status_code=413, detail="脚本草稿不得超过 2 MiB")
        if not filename.lower().endswith(".py") or any(
            ord(character) < 32 or ord(character) == 127 for character in filename
        ):
            raise HTTPException(status_code=422, detail="脚本文件名无效")

        db.lock_task(task_id)
        _editable_task(db, task_id, user)
        current = db.execute("SELECT revision FROM task_drafts WHERE task_id=?", (task_id,)).fetchone()
        current_revision = current["revision"] if current else 0
        if current_revision != body.expected_revision:
            raise HTTPException(
                status_code=409,
                detail={
                    "message": "草稿已在其他会话中更新，请先重新加载以避免覆盖",
                    "current_revision": current_revision,
                },
            )
        revision = current_revision + 1
        updated_at = utc_now()
        db.execute(
            """INSERT INTO task_drafts(task_id,source,filename,notes,revision,updated_at)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(task_id) DO UPDATE SET
                   source=excluded.source,filename=excluded.filename,notes=excluded.notes,
                   revision=excluded.revision,updated_at=excluded.updated_at,deleted_at=NULL""",
            (task_id, body.source, filename, body.notes, revision, updated_at),
        )
        audit(db, user["id"], "task.draft_saved", "task", task_id)
        return {
            "task_id": task_id,
            "source": body.source,
            "filename": filename,
            "notes": body.notes,
            "revision": revision,
            "updated_at": updated_at,
            "saved": True,
        }

    @app.delete("/tasks/{task_id}/draft", status_code=204)
    def delete_task_draft(
        task_id: str,
        revision: int = Query(ge=0),
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        _editable_task(db, task_id, user)
        db.lock_task(task_id)
        _editable_task(db, task_id, user)
        current = db.execute("SELECT revision,deleted_at FROM task_drafts WHERE task_id=?", (task_id,)).fetchone()
        if current and current["revision"] != revision:
            raise HTTPException(status_code=409, detail="草稿在发布过程中有更新，已保留最新草稿")
        if current and not current["deleted_at"]:
            db.execute(
                """UPDATE task_drafts SET source='',filename='main.py',notes='',revision=revision+1,
                       updated_at=?,deleted_at=? WHERE task_id=?""",
                (utc_now(), utc_now(), task_id),
            )
            audit(db, user["id"], "task.draft_deleted", "task", task_id)
        elif not current:
            now = utc_now()
            db.execute(
                """INSERT INTO task_drafts(task_id,source,filename,notes,revision,updated_at,deleted_at)
                   VALUES(?,'','main.py','',1,?,?)""",
                (task_id, now, now),
            )
        return Response(status_code=204)

    @app.get("/tasks/{task_id}/versions")
    def list_task_versions(task_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        _task_for_user(db, task_id, user, require_published=False)
        rows = db.execute(
            "SELECT version,filename,content_size AS size,sha256,notes,created_at FROM task_versions WHERE task_id=? ORDER BY created_at DESC,version DESC",
            (task_id,),
        )
        return [dict(row) for row in rows]

    @app.get("/tasks/{task_id}/versions/{version}/source")
    def get_task_version_source(
        task_id: str,
        version: str,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        _task_for_user(db, task_id, user, require_published=False)
        row = db.execute(
            "SELECT filename,content,storage_key,sha256 FROM task_versions WHERE task_id=? AND version=?",
            (task_id, version),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        script_content = (
            app.state.script_storage.get(row["storage_key"])
            if row["storage_key"] and app.state.script_storage
            else row["content"]
        )
        if not hmac.compare_digest(hashlib.sha256(script_content).hexdigest(), row["sha256"]):
            LOGGER.error("Stored script checksum mismatch for task %s version %s", task_id, version)
            raise HTTPException(status_code=500, detail="脚本存储校验失败")
        return Response(
            content=script_content,
            media_type="text/x-python",
            headers={
                "Content-Disposition": _content_disposition(row["filename"]),
                "X-Content-SHA256": row["sha256"],
                "Cache-Control": "no-store",
            },
        )

    @app.patch("/tasks/{task_id}")
    def set_task_status(
        task_id: str,
        body: TaskStatusInput,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = _editable_task(db, task_id, user)
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
        db.execute("INSERT INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?) ON CONFLICT DO NOTHING", (task_id, user_id, utc_now()))
        audit(db, user["id"], "task.granted", "task", task_id)
        return {"task_id": task_id, "user_id": user_id}

    @app.get("/tasks/{task_id}/publications")
    def list_task_publications(
        task_id: str,
        user: dict = Depends(require_admin),
        db: sqlite3.Connection = Depends(connection),
    ):
        if not db.execute("SELECT 1 FROM tasks WHERE id=?", (task_id,)).fetchone():
            raise HTTPException(status_code=404, detail="任务不存在")
        rows = db.execute(
            """SELECT p.id,p.task_id,p.version,p.device_id,p.status,p.created_at,p.accepted_at,
                      d.name AS device_name,d.user_id,u.name AS user_name,u.email AS user_email
               FROM task_publications p JOIN devices d ON d.id=p.device_id
               JOIN users u ON u.id=d.user_id
               WHERE p.task_id=? ORDER BY p.created_at DESC""",
            (task_id,),
        )
        return [dict(row) for row in rows]

    @app.post("/tasks/{task_id}/publications", status_code=201)
    def publish_to_device(
        task_id: str,
        body: TaskPublicationInput,
        user: dict = Depends(require_admin),
        db: sqlite3.Connection = Depends(connection),
    ):
        task = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task:
            raise HTTPException(status_code=404, detail="任务不存在")
        if task["status"] != "published":
            raise HTTPException(status_code=409, detail="只能分发已发布的任务")
        device = db.execute(
            """SELECT d.id,d.user_id,d.status,u.status AS account_status
               FROM devices d JOIN users u ON u.id=d.user_id WHERE d.id=?""",
            (body.device_id,),
        ).fetchone()
        if not device or device["status"] == "disabled":
            raise HTTPException(status_code=404, detail="可用设备不存在")
        if device["account_status"] != "active":
            raise HTTPException(status_code=409, detail="不能向已停用账号分发任务")
        version_exists = db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? AND version=?",
            (task_id, body.version),
        ).fetchone()
        if not version_exists:
            raise HTTPException(status_code=404, detail="脚本版本不存在")

        existing = db.execute(
            "SELECT id,status FROM task_publications WHERE task_id=? AND version=? AND device_id=?",
            (task_id, body.version, device["id"]),
        ).fetchone()
        now = utc_now()
        if existing and existing["status"] == "accepted":
            return {
                "id": existing["id"], "task_id": task_id, "version": body.version,
                "device_id": device["id"], "status": "accepted",
            }
        publication_id = existing["id"] if existing else str(uuid.uuid4())
        if existing:
            db.execute(
                """UPDATE task_publications SET status='accepted',published_by=?,created_at=?,accepted_at=?
                   WHERE id=?""",
                (user["id"], now, now, publication_id),
            )
        else:
            db.execute(
                """INSERT INTO task_publications
                   (id,task_id,version,device_id,published_by,status,created_at,accepted_at)
                   VALUES(?,?,?,?,?,'accepted',?,?)""",
                (publication_id, task_id, body.version, device["id"], user["id"], now, now),
            )
        audit(db, user["id"], "task.publication_created", "task_publication", publication_id)
        return {
            "id": publication_id, "task_id": task_id, "version": body.version,
            "device_id": device["id"], "status": "accepted",
        }

    @app.delete("/tasks/{task_id}/publications/{publication_id}")
    def revoke_task_publication(
        task_id: str,
        publication_id: str,
        user: dict = Depends(require_admin),
        db: sqlite3.Connection = Depends(connection),
    ):
        publication = db.execute(
            "SELECT * FROM task_publications WHERE id=? AND task_id=?",
            (publication_id, task_id),
        ).fetchone()
        if not publication:
            raise HTTPException(status_code=404, detail="任务分发记录不存在")
        if publication["status"] != "revoked":
            db.execute("UPDATE task_publications SET status='revoked' WHERE id=?", (publication_id,))
            db.execute(
                """UPDATE run_authorizations SET revoked_at=?
                   WHERE task_id=? AND version=? AND device_id=? AND revoked_at IS NULL""",
                (utc_now(), task_id, publication["version"], publication["device_id"]),
            )
            audit(db, user["id"], "task.publication_revoked", "task_publication", publication_id)
        return {"id": publication_id, "status": "revoked"}

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
        db.execute(
            """UPDATE task_publications SET status='revoked'
               WHERE task_id=? AND device_id IN (SELECT id FROM devices WHERE user_id=?)
                 AND status!='revoked'""",
            (task_id, user_id),
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
        storage: ScriptStorage | None = app.state.script_storage
        storage_key = f"scripts/{task_id}/{version_id}.py" if storage else None
        stored_content = b"" if storage else content
        try:
            db.execute(
                """INSERT INTO task_versions(id,task_id,version,filename,content,storage_key,content_size,sha256,notes,created_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (version_id, task_id, version, filename, stored_content, storage_key, len(content), digest, notes[:500], utc_now()),
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
            "INSERT INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?) ON CONFLICT DO NOTHING",
            (task_id, task["owner_id"], utc_now()),
        )
        audit(db, user["id"], "task.version_published", "task_version", version_id)
        if storage and storage_key:
            storage.put(storage_key, content)
        return {"id": version_id, "task_id": task_id, "version": version, "filename": filename, "size": len(content), "sha256": digest, "notes": notes[:500]}

    @app.post("/tasks/{task_id}/versions/upload-url", status_code=201)
    def create_script_upload_url(
        task_id: str,
        body: ScriptUploadRequest,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        storage: ScriptStorage | None = app.state.script_storage
        task = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not task or (user["role"] != "admin" and task["owner_id"] != user["id"]):
            raise HTTPException(status_code=404, detail="任务不存在")
        if not VERSION_PATTERN.fullmatch(body.version):
            raise HTTPException(status_code=422, detail="版本号格式应为 v1.0.0")
        filename = Path(body.filename.replace("\\", "/")).name
        if (
            not filename.lower().endswith(".py")
            or len(filename) > 180
            or any(ord(character) < 32 or ord(character) == 127 for character in filename)
        ):
            raise HTTPException(status_code=422, detail="脚本文件名无效")
        if db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? AND version=?",
            (task_id, body.version),
        ).fetchone():
            raise HTTPException(status_code=409, detail="该任务版本号已存在")
        if not storage:
            return {"mode": "api"}
        upload_id = str(uuid.uuid4())
        storage_key = f"scripts/{task_id}/{upload_id}.py"
        upload_url, headers = storage.presign_upload(storage_key)
        expires_at = int(time.time()) + 300
        db.execute(
            """INSERT INTO pending_script_uploads(
                   id,task_id,user_id,version,filename,storage_key,content_size,sha256,notes,expires_at
               ) VALUES(?,?,?,?,?,?,?,?,?,?)""",
            (
                upload_id, task_id, user["id"], body.version, filename, storage_key,
                body.content_size, body.sha256.lower(), body.notes[:500], expires_at,
            ),
        )
        return {
            "upload_id": upload_id,
            "upload_url": upload_url,
            "headers": headers,
            "expires_at": expires_at,
        }

    @app.post("/tasks/{task_id}/versions/complete-upload", status_code=201)
    def complete_script_upload(
        task_id: str,
        body: ScriptUploadCompletion,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        storage: ScriptStorage | None = app.state.script_storage
        if not storage:
            raise HTTPException(status_code=503, detail="对象存储上传未配置")
        pending = db.execute(
            """SELECT * FROM pending_script_uploads
               WHERE id=? AND task_id=? AND user_id=?""",
            (body.upload_id, task_id, user["id"]),
        ).fetchone()
        if not pending:
            raise HTTPException(status_code=404, detail="上传请求不存在")
        if pending["expires_at"] < int(time.time()):
            storage.delete(pending["storage_key"])
            db.execute("DELETE FROM pending_script_uploads WHERE id=?", (body.upload_id,))
            db.commit()
            raise HTTPException(status_code=410, detail="脚本上传地址已过期")
        content_size, content = storage.get_info(pending["storage_key"])
        if content_size != pending["content_size"] or len(content) != pending["content_size"]:
            storage.delete(pending["storage_key"])
            db.execute("DELETE FROM pending_script_uploads WHERE id=?", (body.upload_id,))
            db.commit()
            raise HTTPException(status_code=422, detail="上传脚本大小与申请不一致")
        if not hmac.compare_digest(hashlib.sha256(content).hexdigest(), pending["sha256"]):
            storage.delete(pending["storage_key"])
            db.execute("DELETE FROM pending_script_uploads WHERE id=?", (body.upload_id,))
            db.commit()
            raise HTTPException(status_code=422, detail="上传脚本 SHA-256 校验失败")
        version_id = str(uuid.uuid4())
        task = db.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        insert_result = db.execute(
            """INSERT INTO task_versions(
                   id,task_id,version,filename,content,storage_key,content_size,sha256,notes,created_at
               ) VALUES(?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(task_id,version) DO NOTHING""",
            (
                version_id, task_id, pending["version"], pending["filename"], b"",
                pending["storage_key"], pending["content_size"], pending["sha256"],
                pending["notes"], utc_now(),
            ),
        )
        if insert_result.rowcount == 0:
            storage.delete(pending["storage_key"])
            db.execute("DELETE FROM pending_script_uploads WHERE id=?", (body.upload_id,))
            db.commit()
            raise HTTPException(status_code=409, detail="该任务版本号已存在")
        if task["status"] != "paused":
            db.execute(
                "UPDATE tasks SET status='published',active_version=? WHERE id=?",
                (pending["version"], task_id),
            )
        else:
            db.execute("UPDATE tasks SET active_version=? WHERE id=?", (pending["version"], task_id))
        db.execute(
            "INSERT INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?) ON CONFLICT DO NOTHING",
            (task_id, task["owner_id"], utc_now()),
        )
        db.execute("DELETE FROM pending_script_uploads WHERE id=?", (body.upload_id,))
        audit(db, user["id"], "task.version_published", "task_version", version_id)
        return {
            "id": version_id, "task_id": task_id, "version": pending["version"],
            "filename": pending["filename"], "size": pending["content_size"],
            "sha256": pending["sha256"], "notes": pending["notes"],
        }

    @app.post("/devices/{device_id}/sync")
    def sync_device(device_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只有设备所属用户可以同步设备")
        _touch_device(db, device_id)
        available_tasks = db.execute(
            """SELECT DISTINCT t.*,t.active_version AS device_version FROM tasks t
               LEFT JOIN task_grants g ON g.task_id=t.id
               WHERE (t.owner_id=? OR g.user_id=?)
                 AND t.status='published' AND t.active_version IS NOT NULL
               ORDER BY t.name""",
            (device["user_id"], device["user_id"]),
        ).fetchall()
        return {
            "device_id": device_id,
            "synced_at": utc_now(),
            "tasks": [
                _task_dict(db, row) | {"active_version": row["device_version"]}
                for row in available_tasks
            ],
            "pending_publications": [],
        }

    @app.post("/devices/{device_id}/publications/{publication_id}/accept")
    def accept_task_publication(
        device_id: str,
        publication_id: str,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        device = _device_for_user(db, device_id, user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只有设备所属用户可以确认任务分发")
        publication = db.execute(
            """SELECT p.*,t.status AS task_status FROM task_publications p
               JOIN tasks t ON t.id=p.task_id
               WHERE p.id=? AND p.device_id=?""",
            (publication_id, device_id),
        ).fetchone()
        if not publication:
            raise HTTPException(status_code=404, detail="任务分发记录不存在")
        if publication["status"] == "revoked" or publication["task_status"] != "published":
            raise HTTPException(status_code=409, detail="此任务分发已撤销或暂停")
        if publication["status"] != "accepted":
            db.execute(
                "UPDATE task_publications SET status='accepted',accepted_at=? WHERE id=?",
                (utc_now(), publication_id),
            )
            audit(db, user["id"], "task.publication_accepted", "task_publication", publication_id)
        _touch_device(db, device_id)
        return {"id": publication_id, "task_id": publication["task_id"], "version": publication["version"], "status": "accepted"}

    @app.post("/devices/{device_id}/heartbeat")
    def device_heartbeat(
        device_id: str,
        body: DeviceHeartbeatInput | None = None,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        device = _device_for_user(db, device_id, user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只有设备所属用户可以发送设备心跳")
        app_version = body.app_version.strip() if body and body.app_version else None
        if body and body.app_version is not None and not app_version:
            raise HTTPException(status_code=422, detail="App 版本不能为空")
        db.execute(
            """UPDATE devices SET status='online',last_active=?,app_version=COALESCE(?,app_version)
               WHERE id=?""",
            (utc_now(), app_version, device_id),
        )
        return {"device_id": device_id, "status": "online", "last_active": utc_now()}

    @app.get("/devices/{device_id}/tasks/{task_id}/versions/{version}/content")
    def download_script(device_id: str, task_id: str, version: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        task = _task_for_user(db, task_id, {"id": device["user_id"], "role": "user"})
        row = db.execute(
            "SELECT filename,content,storage_key,sha256 FROM task_versions WHERE task_id=? AND version=?",
            (task_id, version),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        script_content = (
            app.state.script_storage.get(row["storage_key"])
            if row["storage_key"] and app.state.script_storage
            else row["content"]
        )
        if hashlib.sha256(script_content).hexdigest() != row["sha256"]:
            LOGGER.error("Stored script checksum mismatch for task %s version %s", task_id, version)
            raise HTTPException(status_code=500, detail="脚本存储校验失败")
        return Response(
            content=script_content,
            media_type="text/x-python",
            headers={
                "Content-Disposition": _content_disposition(row["filename"]),
                "X-Content-SHA256": row["sha256"],
                "Cache-Control": "no-store",
            },
        )

    @app.get("/devices/{device_id}/tasks/{task_id}/versions/{version}/content-url")
    def create_script_download_url(
        device_id: str,
        task_id: str,
        version: str,
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        device = _device_for_user(db, device_id, user)
        task = _task_for_user(db, task_id, {"id": device["user_id"], "role": "user"})
        row = db.execute(
            "SELECT filename,storage_key,sha256 FROM task_versions WHERE task_id=? AND version=?",
            (task_id, version),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        storage: ScriptStorage | None = app.state.script_storage
        url = storage.presign_download(row["storage_key"], filename=row["filename"]) if (
            storage and row["storage_key"]
        ) else None
        return JSONResponse(
            {
                "url": url,
                "filename": row["filename"],
                "sha256": row["sha256"],
                "expires_in": 300 if url else None,
            },
            headers={"Cache-Control": "no-store"},
        )

    @app.post("/remote-runs", status_code=201)
    def create_remote_run(
        body: RemoteRunInput,
        idempotency_key: str | None = Header(
            default=None,
            alias="Idempotency-Key",
            min_length=1,
            max_length=128,
            pattern=r"^[A-Za-z0-9._:-]+$",
        ),
        user: dict = Depends(current_user),
        db: sqlite3.Connection = Depends(connection),
    ):
        device = _device_for_user(db, body.device_id, user)
        if user["role"] == "user" and device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只能为自己的设备发起远程运行")
        db.lock_device(device["id"])
        device = _device_for_user(db, body.device_id, user)
        _refresh_device_statuses(db, device["id"])
        device = _device_for_user(db, body.device_id, user)
        if idempotency_key is not None:
            existing = db.execute(
                """SELECT * FROM remote_runs
                   WHERE requested_by=? AND device_id=? AND idempotency_key=?""",
                (user["id"], device["id"], idempotency_key),
            ).fetchone()
            if existing:
                if existing["task_id"] != body.task_id:
                    raise HTTPException(status_code=409, detail="幂等键已用于其他派发请求")
                return {
                    "id": existing["id"],
                    "task_id": existing["task_id"],
                    "version": existing["version"],
                    "device_id": existing["device_id"],
                    "status": _remote_request_status(db, existing),
                    "created_at": existing["created_at"],
                    "expires_at": existing["expires_at"],
                    "run_id": existing["run_id"],
                    "idempotent_replay": True,
                }
        if device["status"] != "online":
            raise HTTPException(status_code=409, detail="只能向在线设备发起运行请求")
        task = _task_for_user(db, body.task_id, user)
        if not task["active_version"]:
            raise HTTPException(status_code=409, detail="任务没有可运行版本")
        if user["role"] == "admin" and task["owner_id"] != device["user_id"]:
            grant = db.execute(
                "INSERT OR IGNORE INTO task_grants(task_id,user_id,created_at) VALUES(?,?,?)",
                (task["id"], device["user_id"], utc_now()),
            )
            if grant.rowcount:
                audit(db, user["id"], "task.grant_created", "task_grant", f"{task['id']}:{device['user_id']}")
        _expire_requests(db)
        has_predecessor = db.execute(
            """SELECT 1 FROM remote_runs WHERE device_id=? AND status='pending'
               UNION ALL
               SELECT 1 FROM runs WHERE device_id=? AND status='running'
               LIMIT 1""",
            (device["id"], device["id"]),
        ).fetchone() is not None
        request_id = str(uuid.uuid4())
        now = int(time.time())
        expires_at = (2**63 - 1) if has_predecessor else now + REMOTE_REQUEST_TTL_SECONDS
        created_at = utc_now()
        db.execute(
            """INSERT INTO remote_runs(
                   id,task_id,version,device_id,requested_by,status,created_at,expires_at,idempotency_key
               ) VALUES(?,?,?,?,?,'pending',?,?,?)""",
            (
                request_id, task["id"], task["active_version"], body.device_id, user["id"],
                created_at, expires_at, idempotency_key,
            ),
        )
        audit(db, user["id"], "remote_run.requested", "remote_run", request_id)
        return {
            "id": request_id,
            "task_id": task["id"],
            "version": task["active_version"],
            "device_id": body.device_id,
            "status": "queued" if has_predecessor else "pending",
            "created_at": created_at,
            "expires_at": expires_at,
            "run_id": None,
            "idempotent_replay": False,
        }

    @app.post("/devices/{device_id}/runs", status_code=201)
    def start_local_run(device_id: str, body: LocalRunInput, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只能在自己的设备上确认本地运行")
        db.lock_device(device["id"])
        device = _device_for_user(db, device_id, user)
        _touch_device(db, device_id)
        task = _task_for_user(db, body.task_id, user)
        version = db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? AND version=?",
            (task["id"], body.version),
        ).fetchone()
        if not version:
            raise HTTPException(status_code=404, detail="脚本版本不存在")
        _ensure_device_idle(db, device["id"])
        run_id = str(uuid.uuid4())
        try:
            db.execute(
                """INSERT INTO runs(id,task_id,version,device_id,status,started_at)
                   VALUES(?,?,?,?,'running',?)""",
                (run_id, task["id"], body.version, device_id, utc_now()),
            )
        except sqlite3.IntegrityError as error:
            raise HTTPException(status_code=409, detail="设备当前有任务运行") from error
        audit(db, user["id"], "run.local_started", "run", run_id)
        return {"id": run_id, "task_id": task["id"], "version": body.version, "device_id": device_id, "status": "running"}

    @app.get("/devices/{device_id}/remote-runs")
    def pending_remote_runs(device_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        device = _device_for_user(db, device_id, user)
        _expire_requests(db)
        _activate_next_remote_request(db, device["id"])
        rows = db.execute(
            """SELECT r.*,t.name AS task_name,t.source,t.capabilities_json,u.name AS requested_by_name,
                      (SELECT id FROM runs WHERE remote_run_id=r.id LIMIT 1) AS run_id
               FROM remote_runs r JOIN tasks t ON t.id=r.task_id
               JOIN users u ON u.id=r.requested_by
               WHERE r.device_id=? AND (
                       r.status='running'
                       OR (
                           r.status='pending'
                           AND NOT EXISTS (
                               SELECT 1 FROM runs active
                               WHERE active.device_id=r.device_id AND active.status='running'
                           )
                           AND r.id=(
                               SELECT first_request.id FROM remote_runs first_request
                               WHERE first_request.device_id=r.device_id AND first_request.status='pending'
                               ORDER BY first_request.created_at,first_request.id LIMIT 1
                           )
                       )
                       OR r.id IN (
                       SELECT id FROM remote_runs
                       WHERE device_id=? AND status='expired'
                       ORDER BY created_at DESC LIMIT 50
                   )
               )
               ORDER BY r.created_at""",
            (device["id"], device["id"]),
        )
        return [{
            "id": row["id"], "task_id": row["task_id"], "task": row["task_name"], "version": row["version"],
            "device_id": row["device_id"], "status": _remote_request_status(db, row), "requested_by": row["requested_by"],
            "run_id": row["run_id"],
            "requested_by_name": row["requested_by_name"],
            "created_at": row["created_at"], "expires_at": row["expires_at"], "source": row["source"],
            "capabilities": json.loads(row["capabilities_json"]),
            "stop_requested": bool(row["stop_requested_at"]) and row["status"] == "running",
        } for row in rows]

    @app.get("/remote-runs/{request_id}")
    def get_remote_run(request_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        _expire_requests(db)
        request = db.execute(
            """SELECT r.*,run.id AS run_id,run.status AS run_status,
                      run.result_summary AS result_summary
               FROM remote_runs r
               LEFT JOIN runs run ON run.remote_run_id=r.id
               WHERE r.id=?""",
            (request_id,),
        ).fetchone()
        if not request:
            raise HTTPException(status_code=404, detail="运行请求不存在")
        _device_for_user(db, request["device_id"], user)
        _activate_next_remote_request(db, request["device_id"])
        request = db.execute(
            """SELECT r.*,run.id AS run_id,run.status AS run_status,
                      run.result_summary AS result_summary
               FROM remote_runs r
               LEFT JOIN runs run ON run.remote_run_id=r.id
               WHERE r.id=?""",
            (request_id,),
        ).fetchone()
        return {
            "id": request["id"],
            "device_id": request["device_id"],
            "status": _remote_request_status(db, request),
            "run_id": request["run_id"],
            "run_status": request["run_status"],
            "result_summary": request["result_summary"] or "",
            "stop_requested": bool(request["stop_requested_at"]) and request["status"] == "running",
            "expires_at": request["expires_at"],
        }

    @app.post("/remote-runs/{request_id}/stop")
    def request_remote_run_stop(request_id: str, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        request = db.execute(
            """SELECT r.*,run.status AS run_status
               FROM remote_runs r
               LEFT JOIN runs run ON run.remote_run_id=r.id
               WHERE r.id=?""",
            (request_id,),
        ).fetchone()
        if not request:
            raise HTTPException(status_code=404, detail="运行请求不存在")
        device = _device_for_user(db, request["device_id"], user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只有目标设备所属用户可以停止运行")
        if request["status"] != "running" or request["run_status"] != "running":
            raise HTTPException(status_code=409, detail="任务当前不在运行状态")

        already_requested = request["stop_requested_at"] is not None
        if not already_requested:
            changed = db.execute(
                """UPDATE remote_runs SET stop_requested_at=?
                   WHERE id=? AND status='running' AND stop_requested_at IS NULL""",
                (utc_now(), request_id),
            ).rowcount
            updated = db.execute(
                "SELECT stop_requested_at,status FROM remote_runs WHERE id=?",
                (request_id,),
            ).fetchone()
            if updated["status"] != "running":
                raise HTTPException(status_code=409, detail="任务已结束，无法停止")
            if updated["stop_requested_at"] is None:
                raise HTTPException(status_code=409, detail="停止请求未能提交，请重试")
            if changed:
                audit(db, user["id"], "remote_run.stop_requested", "remote_run", request_id)
            else:
                already_requested = True
        return {
            "id": request_id,
            "status": "running",
            "stop_requested": True,
            "already_requested": already_requested,
        }

    @app.patch("/remote-runs/{request_id}")
    def decide_remote_run(request_id: str, body: RemoteRunDecision, user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        request = db.execute("SELECT * FROM remote_runs WHERE id=?", (request_id,)).fetchone()
        if not request:
            raise HTTPException(status_code=404, detail="运行请求不存在")
        device = _device_for_user(db, request["device_id"], user)
        if user["role"] != "user" or device["user_id"] != user["id"]:
            raise HTTPException(status_code=403, detail="只有目标设备所属用户可以处理运行请求")
        db.lock_device(device["id"])
        request = db.execute("SELECT * FROM remote_runs WHERE id=?", (request_id,)).fetchone()
        if not request:
            raise HTTPException(status_code=404, detail="运行请求不存在")
        _expire_requests(db)
        _activate_next_remote_request(db, device["id"])
        request = db.execute("SELECT * FROM remote_runs WHERE id=?", (request_id,)).fetchone()
        _touch_device(db, device["id"])
        if request["status"] == "expired":
            raise HTTPException(status_code=410, detail="运行请求已过期")
        if request["status"] != "pending":
            raise HTTPException(status_code=409, detail="运行请求已处理")
        if body.decision == "reject":
            db.execute("UPDATE remote_runs SET status='rejected' WHERE id=?", (request_id,))
            audit(db, user["id"], "remote_run.rejected", "remote_run", request_id)
            _activate_next_remote_request(db, device["id"])
            return {"id": request_id, "status": "rejected", "run_id": None}
        if _has_earlier_remote_request(db, request):
            raise HTTPException(status_code=409, detail="任务仍在派发队列中，前序任务完成后才能启动")
        task = _task_for_user(db, request["task_id"], {"id": device["user_id"], "role": "user"})
        version_exists = db.execute(
            "SELECT 1 FROM task_versions WHERE task_id=? AND version=?",
            (task["id"], request["version"]),
        ).fetchone()
        if not version_exists:
            raise HTTPException(status_code=409, detail="请求脚本版本不可用")
        _ensure_device_idle(db, device["id"])
        if body.preauthorize:
            grant_id = str(uuid.uuid4())
            db.execute(
                """INSERT INTO run_authorizations(id,task_id,version,device_id,user_id,expires_at,all_versions)
                   VALUES(?,?,?,?,?,?,1)
                   ON CONFLICT(task_id,version,device_id) DO UPDATE SET
                     id=excluded.id,user_id=excluded.user_id,expires_at=excluded.expires_at,revoked_at=NULL,
                     all_versions=excluded.all_versions""",
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
        db.lock_device(device["id"])
        run = db.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
        if not run:
            raise HTTPException(status_code=404, detail="运行记录不存在")
        _touch_device(db, device["id"])
        if run["status"] != "running":
            raise HTTPException(status_code=409, detail="运行记录已结束")
        db.execute(
            "UPDATE runs SET status=?,finished_at=?,result_summary=? WHERE id=?",
            (body.status, utc_now(), body.result_summary.strip(), run_id),
        )
        if run["remote_run_id"]:
            db.execute("UPDATE remote_runs SET status=? WHERE id=?", (body.status, run["remote_run_id"]))
        _activate_next_remote_request(db, device["id"])
        audit(db, user["id"], f"run.{body.status}", "run", run_id)
        return {"id": run_id, "status": body.status, "device_id": device["id"]}

    @app.get("/runs")
    def list_runs(user: dict = Depends(current_user), db: sqlite3.Connection = Depends(connection)):
        query = """SELECT r.id,r.task_id,t.name AS task_name,r.version,r.device_id,r.remote_run_id,d.name AS device_name,
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
                """SELECT a.id,a.task_id,a.version,
                          a.device_id,a.user_id,a.expires_at,a.revoked_at,a.all_versions,
                          t.name AS task_name,d.name AS device_name FROM run_authorizations a
                   JOIN tasks t ON t.id=a.task_id JOIN devices d ON d.id=a.device_id
                   WHERE a.revoked_at IS NULL AND a.expires_at>?""",
                (int(time.time()),),
            )
        else:
            rows = db.execute(
                """SELECT a.id,a.task_id,a.version,
                          a.device_id,a.user_id,a.expires_at,a.revoked_at,a.all_versions,
                          t.name AS task_name,d.name AS device_name FROM run_authorizations a
                   JOIN tasks t ON t.id=a.task_id JOIN devices d ON d.id=a.device_id
                   WHERE a.user_id=? AND a.revoked_at IS NULL AND a.expires_at>?""",
                (user["id"], int(time.time())),
            )
        return [{**dict(row), "all_versions": bool(row["all_versions"])} for row in rows]

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
            """INSERT INTO run_authorizations(id,task_id,version,device_id,user_id,expires_at,all_versions)
               VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(task_id,version,device_id) DO UPDATE SET
                 id=excluded.id,user_id=excluded.user_id,expires_at=excluded.expires_at,revoked_at=NULL,
                 all_versions=excluded.all_versions""",
            (authorization_id, task["id"], body.version, device["id"], user["id"], expires_at, int(body.all_versions)),
        )
        row = db.execute(
            "SELECT id FROM run_authorizations WHERE task_id=? AND version=? AND device_id=?",
            (task["id"], body.version, device["id"]),
        ).fetchone()
        audit(db, user["id"], "run_authorization.created", "run_authorization", row["id"])
        return {"id": row["id"], "task_id": task["id"], "version": body.version, "all_versions": body.all_versions, "device_id": device["id"], "expires_at": expires_at}

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
