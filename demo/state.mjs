export const initialDemoState = {
  users: [
    { id: "u-1001", name: "陈晓", email: "chen.xiao@example.com", status: "active", deviceLimit: 5, joined: "2026-09-18" },
    { id: "u-1002", name: "周可", email: "zhou.ke@example.com", status: "active", deviceLimit: 3, joined: "2026-09-21" },
    { id: "u-1003", name: "许一帆", email: "yifan.xu@example.com", status: "disabled", deviceLimit: 2, joined: "2026-09-28" },
  ],
  devices: [
    { id: "d-001", name: "Pixel 8 Pro", userId: "u-1001", userName: "陈晓", model: "Google · Android 15", status: "online", appVersion: "1.2.0", lastActive: "刚刚", initial: "P", color: "lavender" },
    { id: "d-002", name: "小米 14", userId: "u-1001", userName: "陈晓", model: "Xiaomi · Android 14", status: "online", appVersion: "1.2.0", lastActive: "3 分钟前", initial: "M", color: "mint" },
    { id: "d-003", name: "Samsung S24", userId: "u-1002", userName: "周可", model: "Samsung · Android 15", status: "offline", appVersion: "1.1.4", lastActive: "昨天 18:42", initial: "S", color: "peach" },
    { id: "d-004", name: "Pixel 7", userId: "u-1003", userName: "许一帆", model: "Google · Android 14", status: "disabled", appVersion: "1.1.4", lastActive: "9 月 29 日", initial: "P", color: "blue" },
  ],
  tasks: [
    { id: "t-001", name: "订单状态检查", description: "识别订单页面状态并记录结果", category: "OCR 识别", status: "published", activeVersion: "v1.4.0", versions: ["v1.4.0", "v1.3.0"], devices: 2, lastRun: "2 分钟前", successRate: 96, source: "陈晓上传", capabilities: ["屏幕捕获（需系统授权）", "OCR 文字识别"] },
    { id: "t-002", name: "每日签到提醒", description: "检查签到状态并发送提醒", category: "页面识别", status: "draft", activeVersion: "v0.2.0", versions: ["v0.2.0"], devices: 0, lastRun: "尚未运行", successRate: 0, source: "林管理员发布", capabilities: ["屏幕捕获（需系统授权）", "OCR 文字识别"] },
  ],
  remoteRuns: [],
  runs: [],
  runAuthorizations: [],
  activity: [
    { id: "a-001", kind: "success", task: "订单状态检查", user: "陈晓", device: "Pixel 8 Pro", time: "2 分钟前", detail: "识别成功 · 已完成 3 项检查" },
    { id: "a-002", kind: "success", task: "订单状态检查", user: "陈晓", device: "小米 14", time: "18 分钟前", detail: "识别成功 · 已完成 3 项检查" },
    { id: "a-003", kind: "warning", task: "订单状态检查", user: "周可", device: "Samsung S24", time: "昨天 18:42", detail: "设备离线 · 任务未启动" },
    { id: "a-004", kind: "success", task: "订单状态检查", user: "陈晓", device: "Pixel 8 Pro", time: "昨天 14:16", detail: "识别成功 · 已完成 3 项检查" },
  ],
};

export function addUser(state, user) {
  const email = user.email.trim().toLowerCase();
  if (!user.name.trim() || !email) throw new Error("姓名和邮箱不能为空");
  if (state.users.some((item) => item.email.toLowerCase() === email)) throw new Error("该邮箱已存在");
  const nextUser = {
    id: `u-${Date.now()}`,
    name: user.name.trim(),
    email,
    status: "active",
    deviceLimit: Number(user.deviceLimit) || 2,
    joined: new Date().toISOString().slice(0, 10),
  };
  return { ...state, users: [nextUser, ...state.users] };
}

export function toggleUser(state, userId) {
  return {
    ...state,
    users: state.users.map((user) => user.id === userId
      ? { ...user, status: user.status === "active" ? "disabled" : "active" }
      : user),
  };
}

export function toggleDevice(state, deviceId) {
  return {
    ...state,
    devices: state.devices.map((device) => device.id === deviceId
      ? { ...device, status: device.status === "disabled" ? "offline" : "disabled" }
      : device),
  };
}

export function setTaskPaused(state, taskId, paused) {
  const task = state.tasks.find((item) => item.id === taskId);
  if (!task || !["published", "paused"].includes(task.status)) throw new Error("任务当前无法暂停或恢复");
  const status = paused ? "paused" : "published";
  return {
    ...state,
    tasks: state.tasks.map((item) => item.id === taskId ? { ...item, status } : item),
    remoteRuns: state.remoteRuns.map((request) =>
      request.taskId === taskId && request.status === "pending" && paused
        ? { ...request, status: "expired" }
        : request),
    runAuthorizations: paused
      ? state.runAuthorizations.filter((authorization) => authorization.taskId !== taskId)
      : state.runAuthorizations,
  };
}

export function publishTaskVersion(state, { taskId, version, notes, scriptFile }) {
  if (!/^v\d+\.\d+\.\d+$/.test(version)) throw new Error("版本号格式应为 v1.0.0");
  if (scriptFile && (!/\.py$/i.test(scriptFile.name) || scriptFile.size < 1 || scriptFile.size > 2 * 1024 * 1024 || !/^[a-f\d]{64}$/i.test(scriptFile.sha256))) {
    throw new Error("请选择 2 MB 以内的非空 Python 文件并提供有效 SHA-256");
  }
  return {
    ...state,
    tasks: state.tasks.map((task) => task.id === taskId
      ? { ...task, status: task.status === "paused" ? "paused" : "published", activeVersion: version, versions: [version, ...task.versions.filter((item) => item !== version)], ...(scriptFile ? { scriptFile: { ...scriptFile } } : {}) }
      : task),
    activity: [{
      id: `a-${Date.now()}`,
      kind: "publish",
      task: state.tasks.find((task) => task.id === taskId)?.name ?? "自动化任务",
      user: "林管理员",
      device: "任务发布",
      time: "刚刚",
      detail: `${version} 已发布${notes ? ` · ${notes}` : ""}`,
    }, ...state.activity],
  };
}

function newId(prefix) {
  return `${prefix}-${globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`}`;
}

function createRun(state, { taskId, deviceId, version, remoteRunId = null }) {
  const task = state.tasks.find((item) => item.id === taskId);
  const device = state.devices.find((item) => item.id === deviceId);
  const user = device && state.users.find((item) => item.id === device.userId);
  if (!task || task.status !== "published" || !task.versions.includes(version)) throw new Error("任务版本不可运行");
  if (!device || device.status !== "online" || !user || user.status !== "active") throw new Error("目标设备或账号当前不可用");
  const run = {
    id: newId("run"),
    taskId,
    task: task.name,
    version,
    user: user.name,
    device: device.name,
    deviceId,
    status: "running",
    startedAt: new Date().toISOString(),
    remoteRunId,
  };
  return { ...state, runs: [run, ...state.runs] };
}

export function createRemoteRun(state, { taskId, deviceId, requestedBy = "林管理员" }) {
  const task = state.tasks.find((item) => item.id === taskId);
  const device = state.devices.find((item) => item.id === deviceId);
  if (!task || task.status !== "published") throw new Error("当前任务不可运行");
  if (!device || device.status !== "online") throw new Error("只能向在线设备发起运行请求");
  const request = {
    id: newId("request"),
    taskId,
    task: task.name,
    version: task.activeVersion,
    deviceId,
    device: device.name,
    user: device.userName,
    requestedBy,
    requestedAt: Date.now(),
    expiresAt: Date.now() + 15 * 60 * 1000,
    status: "pending",
    runId: null,
  };
  const next = { ...state, remoteRuns: [request, ...state.remoteRuns] };
  const grant = state.runAuthorizations.find((item) =>
    item.taskId === taskId && item.deviceId === deviceId && item.version === task.activeVersion && item.expiresAt > Date.now());
  if (!grant) return next;
  const started = createRun(next, { taskId, deviceId, version: request.version, remoteRunId: request.id });
  return {
    ...started,
    remoteRuns: started.remoteRuns.map((item) => item.id === request.id ? { ...item, status: "running", runId: started.runs[0].id } : item),
  };
}

export function resolveRemoteRun(state, requestId, { approved, preauthorize = false }) {
  const request = state.remoteRuns.find((item) => item.id === requestId);
  if (!request || request.status !== "pending") throw new Error("此运行请求已处理或不存在");
  if (request.expiresAt <= Date.now()) throw new Error("此运行请求已过期");
  if (!approved) {
    return {
      ...state,
      remoteRuns: state.remoteRuns.map((item) => item.id === requestId ? { ...item, status: "rejected" } : item),
    };
  }
  let next = state;
  if (preauthorize) {
    next = {
      ...state,
      runAuthorizations: [{
        id: newId("grant"),
        taskId: request.taskId,
        deviceId: request.deviceId,
        version: request.version,
        expiresAt: Date.now() + 30 * 24 * 60 * 60 * 1000,
      }, ...state.runAuthorizations.filter((item) =>
        !(item.taskId === request.taskId && item.deviceId === request.deviceId && item.version === request.version))],
    };
  }
  const started = createRun(next, {
    taskId: request.taskId,
    deviceId: request.deviceId,
    version: request.version,
    remoteRunId: request.id,
  });
  return {
    ...started,
    remoteRuns: started.remoteRuns.map((item) => item.id === requestId ? { ...item, status: "running", runId: started.runs[0].id } : item),
  };
}

export function expireRemoteRuns(state, now = Date.now()) {
  return {
    ...state,
    remoteRuns: state.remoteRuns.map((item) =>
      item.status === "pending" && item.expiresAt <= now ? { ...item, status: "expired" } : item),
  };
}

export function startLocalRun(state, { taskId, deviceId, preauthorize = false }) {
  const task = state.tasks.find((item) => item.id === taskId);
  if (!task) throw new Error("任务不存在");
  let next = state;
  if (preauthorize) {
    next = {
      ...state,
      runAuthorizations: [{
        id: newId("grant"),
        taskId,
        deviceId,
        version: task.activeVersion,
        expiresAt: Date.now() + 30 * 24 * 60 * 60 * 1000,
      }, ...state.runAuthorizations.filter((item) =>
        !(item.taskId === taskId && item.deviceId === deviceId && item.version === task.activeVersion))],
    };
  }
  return createRun(next, { taskId, deviceId, version: task.activeVersion });
}

export function finishRun(state, runId, status = "success") {
  if (!["success", "stopped"].includes(status)) throw new Error("不支持的运行结果");
  const run = state.runs.find((item) => item.id === runId);
  if (!run || run.status !== "running") throw new Error("任务当前未在运行");
  const task = state.tasks.find((item) => item.id === run.taskId);
  const detail = status === "success"
    ? "演示运行成功 · OCR 识别到「已发货」"
    : "用户已停止任务";
  const updatedRun = { ...run, status, finishedAt: new Date().toISOString(), result: detail };
  return {
    ...state,
    runs: state.runs.map((item) => item.id === runId ? updatedRun : item),
    remoteRuns: state.remoteRuns.map((item) => item.runId === runId ? { ...item, status } : item),
    tasks: state.tasks.map((item) => item.id === run.taskId && status === "success" ? { ...item, lastRun: "刚刚" } : item),
    activity: [{
      id: newId("activity"),
      kind: status === "success" ? "success" : "warning",
      task: run.task,
      user: run.user,
      device: run.device,
      time: "刚刚",
      detail,
    }, ...state.activity],
  };
}

export function revokeRunAuthorization(state, grantId) {
  return { ...state, runAuthorizations: state.runAuthorizations.filter((item) => item.id !== grantId) };
}
