const SESSION_KEY = "flowpilot-api-session";
const ACCESS_TOKEN_SKEW_MS = 30_000;

export function defaultApiBaseUrl(location = globalThis.window?.location) {
  if (location?.protocol === "https:") return `${location.origin}/api`;
  return `http://${location?.hostname || "127.0.0.1"}:8001`;
}

export function readSession(storage) {
  try {
    const explicitStorage = storage != null;
    storage ??= globalThis.localStorage;
    const value = storage?.getItem(SESSION_KEY);
    if (value) {
      const session = JSON.parse(value);
      return { ...session, rememberMe: explicitStorage ? session.rememberMe === true : true };
    }
    if (explicitStorage) return null;
    storage = globalThis.sessionStorage;
    const sessionValue = storage?.getItem(SESSION_KEY);
    return sessionValue ? { ...JSON.parse(sessionValue), rememberMe: false } : null;
  } catch {
    return null;
  }
}

export function saveSession(session, storage) {
  if (storage) {
    storage.setItem(SESSION_KEY, JSON.stringify(session));
    return;
  }
  const target = session.rememberMe ? globalThis.localStorage : globalThis.sessionStorage;
  target?.setItem(SESSION_KEY, JSON.stringify(session));
  (session.rememberMe ? globalThis.sessionStorage : globalThis.localStorage)?.removeItem(SESSION_KEY);
}

export function clearSession(storage) {
  if (storage) {
    storage.removeItem(SESSION_KEY);
    return;
  }
  globalThis.sessionStorage?.removeItem(SESSION_KEY);
  globalThis.localStorage?.removeItem(SESSION_KEY);
}

export function revokeSession(session, fetchImpl = fetch) {
  return apiRequest(session, "/auth/logout", { method: "POST" }, fetchImpl);
}

export function passwordRecoveryStatus(baseUrl, fetchImpl = fetch) {
  return apiRequest({ baseUrl }, "/auth/recovery-status", {}, fetchImpl);
}

export function requestPasswordResetCode(baseUrl, email, fetchImpl = fetch) {
  return apiRequest({ baseUrl }, "/auth/forgot-password", {
    method: "POST",
    body: { email },
  }, fetchImpl);
}

export function resetPasswordWithCode(baseUrl, email, code, newPassword, fetchImpl = fetch) {
  return apiRequest({ baseUrl }, "/auth/reset-password", {
    method: "POST",
    body: { email, code, new_password: newPassword },
  }, fetchImpl);
}

export async function apiRequest(session, path, { method = "GET", body, formData, headers: extraHeaders = {} } = {}, fetchImpl = fetch) {
  const baseUrl = session.baseUrl.replace(/\/+$/, "");
  const send = async (token = session?.token) => {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    Object.assign(headers, extraHeaders);
    try {
      return await fetchImpl(`${baseUrl}${path}`, {
        method,
        headers,
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        ...(formData ? { body: formData } : {}),
      });
    } catch (error) {
      throw new Error(`无法连接服务端：${error.message}`);
    }
  };
  let response = await send();
  if (
    response.status === 401 &&
    session?.token &&
    path !== "/auth/login" &&
    path !== "/auth/refresh"
  ) {
    let refreshResponse;
    try {
      refreshResponse = await fetchImpl(`${baseUrl}/auth/refresh`, {
        method: "POST",
        headers: { Authorization: `Bearer ${session.token}` },
      });
    } catch (error) {
      throw new Error(`无法连接服务端：${error.message}`);
    }
    if (refreshResponse.ok) {
      const refreshed = await refreshResponse.json();
      session.token = refreshed.access_token;
      session.accessTokenExpiresAt = Date.now() + refreshed.expires_in * 1000;
      saveSession(session);
      response = await send();
    } else if (refreshResponse.status === 401) {
      clearSession();
      globalThis.window?.dispatchEvent(new CustomEvent("flowpilot:session-expired"));
      throw new Error("登录会话已失效，请重新登录");
    } else {
      const refreshError = await refreshResponse.json().catch(() => null);
      const detail = refreshError?.detail;
      throw new Error(typeof detail === "string" ? detail : `登录会话续期失败（HTTP ${refreshResponse.status}）`);
    }
  }
  const payload = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && session?.token) {
      clearSession();
      globalThis.window?.dispatchEvent(new CustomEvent("flowpilot:session-expired"));
    }
    const detail = payload?.detail;
    throw new Error(typeof detail === "string" ? detail : `服务端请求失败（HTTP ${response.status}）`);
  }
  return payload;
}

export async function createRemoteRunRequest(
  session,
  taskId,
  deviceId,
  fetchImpl = fetch,
  idempotencyKey = globalThis.crypto?.randomUUID?.(),
) {
  if (!idempotencyKey) throw new Error("当前环境无法生成安全的派发请求标识");
  const devices = await apiRequest(session, "/devices", {}, fetchImpl);
  const device = devices.find((item) => item.id === deviceId);
  if (!device || device.user_id !== session.user.id) {
    throw new Error("目标设备不可用或不属于当前账号");
  }
  if (device.status !== "online") {
    throw new Error("目标设备已离线，未发送请求；刷新设备状态后重新选择在线设备");
  }
  return apiRequest(session, "/remote-runs", {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: { task_id: taskId, device_id: deviceId },
  }, fetchImpl);
}

export function requestRemoteRunStop(session, requestId, fetchImpl = fetch) {
  return apiRequest(
    session,
    `/remote-runs/${encodeURIComponent(requestId)}/stop`,
    { method: "POST" },
    fetchImpl,
  );
}

export async function waitForRemoteRun(session, requestId, {
  fetchImpl = fetch,
  pollIntervalMs = 5000,
  maxWaitMs = 30 * 60 * 1000,
  sleep = (duration) => new Promise((resolve) => setTimeout(resolve, duration)),
  onStatus = () => {},
} = {}) {
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() <= deadline) {
    const request = await apiRequest(
      session,
      `/remote-runs/${encodeURIComponent(requestId)}`,
      {},
      fetchImpl,
    );
    onStatus(request);
    if (["success", "failure", "stopped", "rejected", "expired"].includes(request.status)) {
      return request;
    }
    if (Date.now() + pollIntervalMs > deadline) break;
    await sleep(pollIntervalMs);
  }
  return null;
}

export async function uploadScriptVersion(session, taskId, { version, notes, file }, fetchImpl = fetch) {
  if (!globalThis.crypto?.subtle) throw new Error("当前浏览器不支持安全的 SHA-256 校验");
  const bytes = await file.arrayBuffer();
  const digest = await globalThis.crypto.subtle.digest("SHA-256", bytes);
  const sha256 = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const path = `/tasks/${encodeURIComponent(taskId)}/versions`;
  const upload = await apiRequest(session, `${path}/upload-url`, {
    method: "POST",
    body: {
      version,
      filename: file.name,
      content_size: file.size,
      sha256,
      notes,
    },
  }, fetchImpl);
  if (upload.mode === "api") {
    const formData = new FormData();
    formData.set("version", version);
    formData.set("notes", notes);
    formData.set("script", file);
    return apiRequest(session, path, { method: "POST", formData }, fetchImpl);
  }
  let response;
  try {
    response = await fetchImpl(upload.upload_url, {
      method: "PUT",
      headers: upload.headers,
      body: bytes,
    });
  } catch (error) {
    throw new Error(`脚本上传失败：${error.message}`);
  }
  if (!response.ok) throw new Error(`对象存储上传失败（HTTP ${response.status}）`);
  return apiRequest(session, `${path}/complete-upload`, {
    method: "POST",
    body: { upload_id: upload.upload_id },
  }, fetchImpl);
}

export async function login(baseUrl, email, password, rememberMe = false) {
  let parsedBaseUrl;
  try {
    parsedBaseUrl = new URL(baseUrl);
  } catch {
    throw new Error("服务端地址格式无效");
  }
  if (!["http:", "https:"].includes(parsedBaseUrl.protocol) || parsedBaseUrl.username || parsedBaseUrl.password) {
    throw new Error("服务端地址必须使用 HTTP 或 HTTPS");
  }
  const normalizedBaseUrl = parsedBaseUrl.toString().replace(/\/+$/, "");
  const result = await apiRequest(
    { baseUrl: normalizedBaseUrl },
    "/auth/login",
    { method: "POST", body: { email, password } },
  );
  const session = {
    baseUrl: normalizedBaseUrl,
    token: result.access_token,
    accessTokenExpiresAt: Date.now() + result.expires_in * 1000,
    rememberMe,
    user: result.user,
  };
  saveSession(session);
  return session;
}

export async function loadSnapshot(session, fetchImpl = fetch) {
  const [devices, tasks, runs, authorizations] = await Promise.all([
    apiRequest(session, "/devices", {}, fetchImpl),
    apiRequest(session, "/tasks", {}, fetchImpl),
    apiRequest(session, "/runs", {}, fetchImpl),
    apiRequest(session, "/run-authorizations", {}, fetchImpl),
  ]);
  let users = [];
  if (session.user.role === "admin") users = await apiRequest(session, "/users", {}, fetchImpl);
  const deviceRequests = await Promise.all(devices.filter((device) => device.status === "online").map(async (device) => {
    const requests = await apiRequest(session, `/devices/${encodeURIComponent(device.id)}/remote-runs`, {}, fetchImpl);
    return requests.map((request) => ({ ...request, device_name: device.name }));
  }));
  const mappedDevices = devices.map((device) => ({
    ...device,
    hardwareStatus: device.status,
    status: device.account_status === "disabled" ? "disabled" : device.status,
    userId: device.user_id,
    userName: device.user_name ?? session.user.name,
    model: device.model,
    appVersion: device.app_version,
    lastActive: device.last_active,
    initial: device.name.slice(0, 1),
    color: "lavender",
  }));
  const mappedTasks = tasks.map((task) => ({
    ...task,
    activeVersion: task.active_version,
    versions: task.versions.map((item) => item.version),
    devices: task.assigned_device_count,
    lastRun: runs.find((run) => run.task_id === task.id)?.started_at ?? "尚未运行",
    successRate: 0,
    source: task.source,
    description: task.description,
    category: task.category,
    capabilities: task.capabilities,
    apiVersions: task.versions,
  }));
  const mappedRequests = deviceRequests.flat().map((request) => ({
    ...request,
    taskId: request.task_id,
    task: request.task,
    deviceId: request.device_id,
    device: request.device_name,
    user: session.user.name,
    requestedBy: request.requested_by_name ?? request.requested_by,
    requestedAt: new Date(request.created_at).getTime(),
    expiresAt: request.expires_at * 1000,
    runId: request.run_id,
    stopRequested: request.stop_requested === true,
  }));
  const mappedRuns = runs.map((run) => ({
    ...run,
    taskId: run.task_id,
    deviceId: run.device_id,
    task: run.task_name,
    device: run.device_name,
    user: run.user_name,
    result: run.result_summary,
    remoteRunId: run.remote_run_id,
    stopRequested: mappedRequests.find((request) => request.id === run.remote_run_id)?.stopRequested ?? false,
  }));
  const mappedAuthorizations = authorizations.map((item) => ({
    ...item,
    taskId: item.task_id,
    deviceId: item.device_id,
    expiresAt: item.expires_at * 1000,
  }));
  const activity = mappedRuns.map((run) => ({
    id: run.id,
    kind: run.status === "success" ? "success" : run.status === "running" ? "running" : "warning",
    task: run.task,
    user: run.user,
    device: run.device,
    time: run.started_at,
    detail: run.result || run.status,
  }));
  return {
    users: users.map((user) => ({
      ...user,
      deviceLimit: user.device_limit,
      joined: user.joined_at,
    })),
    devices: mappedDevices,
    tasks: mappedTasks,
    runs: mappedRuns,
    remoteRuns: mappedRequests,
    runAuthorizations: mappedAuthorizations,
    activity,
  };
}
