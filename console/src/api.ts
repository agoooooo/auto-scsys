export type Session = {
  baseUrl: string;
  token: string;
  accessTokenExpiresAt: number;
  rememberMe: boolean;
  user: User;
};

export type User = {
  id: string;
  name: string;
  email: string;
  role: "admin" | "user";
  status: "active" | "disabled";
  device_limit: number;
  joined_at?: string;
};

export type Device = {
  id: string;
  user_id: string;
  name: string;
  model: string;
  status: "online" | "offline" | "disabled";
  app_version: string;
  last_active: string;
  user_name: string;
  account_status: string;
};

export type TaskVersion = {
  version: string;
  filename: string;
  sha256: string;
  notes: string;
  created_at: string;
  size?: number;
};

export type Task = {
  id: string;
  name: string;
  description: string;
  category: string;
  status: "draft" | "published" | "paused";
  owner_id: string;
  source: string;
  capabilities: string[];
  active_version: string | null;
  versions: TaskVersion[];
  assigned_device_count?: number;
};

export type ScriptDraft = {
  task_id: string;
  source: string;
  filename: string;
  notes: string;
  revision: number;
  updated_at: string | null;
  saved: boolean;
};

export type RemoteRun = {
  id: string;
  device_id: string;
  status: string;
  run_id: string | null;
  run_status: string | null;
  result_summary: string;
  stop_requested: boolean;
  expires_at: number;
};

export type RunRecord = {
  id: string;
  task_id: string;
  task_name: string;
  version: string;
  device_id: string;
  remote_run_id: string | null;
  device_name: string;
  user_id: string;
  user_name: string;
  status: string;
  started_at: string;
  finished_at: string | null;
  result_summary: string;
};

export type AuditEvent = {
  id: string;
  actor_id: string;
  actor: string;
  action: string;
  resource_type: string;
  resource_id: string;
  created_at: string;
};

const SESSION_KEY = "flowpilot-console-session";

export function defaultApiBaseUrl(location: Pick<Location, "origin"> = window.location): string {
  return `${location.origin}/api`;
}

export function readSession(storage: Storage = window.sessionStorage): Session | null {
  try {
    const encoded = storage.getItem(SESSION_KEY);
    if (!encoded) return null;
    const session = JSON.parse(encoded) as Session;
    return session.token && session.user?.id ? session : null;
  } catch {
    return null;
  }
}

export function saveSession(session: Session, storage: Storage = window.sessionStorage): void {
  storage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession(storage?: Storage): void {
  storage?.removeItem(SESSION_KEY);
  window.sessionStorage.removeItem(SESSION_KEY);
  window.localStorage.removeItem(SESSION_KEY);
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
}

let refreshInFlight: Promise<void> | null = null;

async function refreshSession(session: Session): Promise<void> {
  refreshInFlight ??= (async () => {
    const response = await fetch(`${session.baseUrl}/auth/refresh`, {
      method: "POST",
      headers: { Authorization: `Bearer ${session.token}` },
    });
    if (!response.ok) {
      clearSession();
      window.dispatchEvent(new Event("flowpilot:session-expired"));
      throw new ApiError("登录会话已失效，请重新登录", response.status);
    }
    const payload = await response.json() as { access_token: string; expires_in: number };
    session.token = payload.access_token;
    session.accessTokenExpiresAt = Date.now() + payload.expires_in * 1000;
    saveSession(session, session.rememberMe ? window.localStorage : window.sessionStorage);
  })().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

async function apiResponse(session: Session, path: string, init: RequestInit = {}): Promise<Response> {
  const send = () => fetch(`${session.baseUrl}${path}`, {
    ...init,
    headers: {
      ...(init.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
      ...init.headers,
      Authorization: `Bearer ${session.token}`,
    },
  });
  let response = await send();
  if (response.status === 401 && !path.startsWith("/auth/")) {
    await refreshSession(session);
    response = await send();
  }
  return response;
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get("content-type") ?? "";
  const payload = contentType.includes("application/json")
    ? await response.json().catch(() => null) as { detail?: unknown } | null
    : await response.text();
  if (!response.ok) {
    const detail = typeof payload === "object" && payload && "detail" in payload
      ? payload.detail
      : null;
    const message = typeof detail === "string"
      ? detail
      : typeof detail === "object" && detail && "message" in detail && typeof detail.message === "string"
        ? detail.message
        : `请求失败（HTTP ${response.status}）`;
    throw new ApiError(message, response.status);
  }
  return payload as T;
}

export async function apiRequest<T>(
  session: Session,
  path: string,
  init: RequestInit = {},
): Promise<T> {
  try {
    return await parseResponse<T>(await apiResponse(session, path, init));
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new Error(`无法连接服务端：${error instanceof Error ? error.message : "网络错误"}`);
  }
}

export async function requestScriptSource(session: Session, taskId: string, version: string): Promise<string> {
  const response = await apiResponse(
    session,
    `/tasks/${encodeURIComponent(taskId)}/versions/${encodeURIComponent(version)}/source`,
  );
  if (!response.ok) return parseResponse<string>(response);
  return response.text();
}

export async function login(
  email: string,
  password: string,
  rememberMe: boolean,
  baseUrl = defaultApiBaseUrl(),
): Promise<Session> {
  const response = await fetch(`${baseUrl}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const payload = await parseResponse<{
    access_token: string;
    expires_in: number;
    user: User;
  }>(response);
  const session: Session = {
    baseUrl,
    token: payload.access_token,
    accessTokenExpiresAt: Date.now() + payload.expires_in * 1000,
    rememberMe,
    user: payload.user,
  };
  saveSession(
    session,
    rememberMe ? window.localStorage : window.sessionStorage,
  );
  return session;
}

export function getScriptDraft(session: Session, taskId: string): Promise<ScriptDraft> {
  return apiRequest(session, `/tasks/${encodeURIComponent(taskId)}/draft`);
}

export function saveScriptDraft(
  session: Session,
  taskId: string,
  draft: Pick<ScriptDraft, "source" | "filename" | "notes"> & { expected_revision: number },
): Promise<ScriptDraft> {
  return apiRequest(session, `/tasks/${encodeURIComponent(taskId)}/draft`, {
    method: "PUT",
    body: JSON.stringify(draft),
  });
}

export function deleteScriptDraft(session: Session, taskId: string, revision: number): Promise<void> {
  return apiRequest(session, `/tasks/${encodeURIComponent(taskId)}/draft?revision=${revision}`, {
    method: "DELETE",
  });
}

export function startRemoteRun(session: Session, taskId: string, deviceId: string): Promise<RemoteRun> {
  return apiRequest(session, "/remote-runs", {
    method: "POST",
    headers: { "Idempotency-Key": crypto.randomUUID() },
    body: JSON.stringify({ task_id: taskId, device_id: deviceId }),
  });
}

export function getRemoteRun(session: Session, requestId: string): Promise<RemoteRun> {
  return apiRequest(session, `/remote-runs/${encodeURIComponent(requestId)}`);
}

export function stopRemoteRun(session: Session, requestId: string): Promise<RemoteRun> {
  return apiRequest(session, `/remote-runs/${encodeURIComponent(requestId)}/stop`, {
    method: "POST",
  });
}

export async function publishScriptVersion(
  session: Session,
  taskId: string,
  input: { version: string; filename: string; notes: string; source: string },
): Promise<unknown> {
  const bytes = new TextEncoder().encode(input.source);
  if (bytes.byteLength === 0 || bytes.byteLength > 2 * 1024 * 1024) {
    throw new Error("Python 脚本必须非空且不超过 2 MiB");
  }
  if (!crypto.subtle) throw new Error("当前浏览器不支持安全的 SHA-256 校验");
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const sha256 = [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
  const basePath = `/tasks/${encodeURIComponent(taskId)}/versions`;
  const upload = await apiRequest<{
    mode?: string;
    upload_id?: string;
    upload_url?: string;
    headers?: Record<string, string>;
  }>(session, `${basePath}/upload-url`, {
    method: "POST",
    body: JSON.stringify({
      version: input.version,
      filename: input.filename,
      content_size: bytes.byteLength,
      sha256,
      notes: input.notes,
    }),
  });
  if (upload.mode === "api") {
    const form = new FormData();
    form.set("version", input.version);
    form.set("notes", input.notes);
    form.set("script", new File([bytes], input.filename, { type: "text/x-python" }));
    return apiRequest(session, basePath, { method: "POST", body: form });
  }
  if (!upload.upload_url || !upload.upload_id) throw new Error("服务端返回了无效的脚本上传地址");
  const transfer = await fetch(upload.upload_url, {
    method: "PUT",
    headers: upload.headers,
    body: bytes,
  });
  if (!transfer.ok) throw new Error(`对象存储上传失败（HTTP ${transfer.status}）`);
  return apiRequest(session, `${basePath}/complete-upload`, {
    method: "POST",
    body: JSON.stringify({ upload_id: upload.upload_id }),
  });
}
