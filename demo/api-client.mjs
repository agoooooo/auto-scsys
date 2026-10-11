const SESSION_KEY = "flowpilot-api-session";

export function readSession(storage) {
  try {
    storage ??= globalThis.sessionStorage;
    const value = storage?.getItem(SESSION_KEY);
    return value ? JSON.parse(value) : null;
  } catch {
    return null;
  }
}

export function saveSession(session, storage = globalThis.sessionStorage) {
  storage?.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession(storage = globalThis.sessionStorage) {
  storage?.removeItem(SESSION_KEY);
}

export async function apiRequest(session, path, { method = "GET", body, formData } = {}, fetchImpl = fetch) {
  const headers = {};
  if (session?.token) headers.Authorization = `Bearer ${session.token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetchImpl(`${session.baseUrl.replace(/\/+$/, "")}${path}`, {
      method,
      headers,
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      ...(formData ? { body: formData } : {}),
    });
  } catch (error) {
    throw new Error(`无法连接服务端：${error.message}`);
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

export async function login(baseUrl, email, password) {
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
    devices: devices.filter((device) => device.user_id === task.owner_id).length,
    lastRun: runs.find((run) => run.task_id === task.id)?.started_at ?? "尚未运行",
    successRate: 0,
    source: task.source,
    description: task.description,
    category: task.category,
    capabilities: task.capabilities,
    apiVersions: task.versions,
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
    runId: null,
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
