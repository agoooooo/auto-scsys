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
    { id: "t-001", name: "订单状态检查", description: "识别订单页面状态并记录结果", category: "OCR 识别", status: "published", activeVersion: "v1.4.0", versions: ["v1.4.0", "v1.3.0"], devices: 2, lastRun: "2 分钟前", successRate: 96 },
    { id: "t-002", name: "每日签到提醒", description: "检查签到状态并发送提醒", category: "页面识别", status: "draft", activeVersion: "v0.2.0", versions: ["v0.2.0"], devices: 0, lastRun: "尚未运行", successRate: 0 },
  ],
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

export function publishTaskVersion(state, { taskId, version, notes }) {
  if (!/^v\d+\.\d+\.\d+$/.test(version)) throw new Error("版本号格式应为 v1.0.0");
  return {
    ...state,
    tasks: state.tasks.map((task) => task.id === taskId
      ? { ...task, status: "published", activeVersion: version, versions: [version, ...task.versions.filter((item) => item !== version)] }
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

export function runTask(state, taskId) {
  const task = state.tasks.find((item) => item.id === taskId);
  if (!task || task.status !== "published") throw new Error("当前任务不可运行");
  const activity = {
    id: `a-${Date.now()}`,
    kind: "success",
    task: task.name,
    user: "陈晓",
    device: "Pixel 8 Pro",
    time: "刚刚",
    detail: "演示运行成功 · OCR 识别到「已发货」",
  };
  return {
    ...state,
    tasks: state.tasks.map((item) => item.id === taskId ? { ...item, lastRun: "刚刚" } : item),
    activity: [activity, ...state.activity],
  };
}
