import {
  addUser,
  createRemoteRun,
  expireRemoteRuns,
  finishRun,
  initialDemoState,
  publishTaskVersion,
  resolveRemoteRun,
  revokeRunAuthorization,
  setTaskPaused,
  startLocalRun,
  toggleDevice,
  toggleUser,
} from "./state.mjs";
import {
  apiRequest,
  clearSession,
  loadSnapshot,
  login,
  readSession,
} from "./api-client.mjs";

let state = structuredClone(initialDemoState);
let session = readSession();
let demoMode = false;
let currentView = "admin";
let currentPage = "overview";
let phoneTab = "home";
let currentPhoneDeviceId = "d-001";
let userQuery = "";
let userFilter = "all";
let auditEvents = [];
let auditLoading = false;

const workspace = document.querySelector("#workspace");
const appShell = document.querySelector("#app-shell");
const loginGate = document.querySelector("#login-gate");
const modalRoot = document.querySelector("#modal-root");
const toastRegion = document.querySelector("#toast-region");
const breadcrumb = document.querySelector("#breadcrumb-current");

const pageNames = {
  overview: "总览",
  users: "用户管理",
  devices: "设备管理",
  tasks: "自动化任务",
  activity: "运行记录",
  audit: "操作审计",
};

const auditActionLabels = {
  "user.created": "创建用户",
  "user.active": "启用用户",
  "user.disabled": "停用用户",
  "device.bound": "绑定设备",
  "device.unbound": "解绑设备",
  "device.status_changed": "修改设备状态",
  "task.created": "创建任务",
  "task.granted": "授予任务访问权",
  "task.grant_revoked": "撤销任务访问权",
  "task.version_published": "发布任务版本",
  "task.paused": "暂停任务",
  "task.published": "恢复任务发布",
  "remote_run.requested": "请求远程运行",
  "remote_run.rejected": "拒绝远程运行",
  "remote_run.approved": "确认远程运行",
  "run.local_started": "启动本地运行",
  "run_authorization.created": "创建运行预授权",
  "run_authorization.revoked": "撤销运行预授权",
};

const auditResourceLabels = {
  user: "用户",
  device: "设备",
  task: "任务",
  task_version: "任务版本",
  remote_run: "远程运行请求",
  run: "运行记录",
  run_authorization: "运行预授权",
};

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[character]);
}

function statusPill(status) {
  const labels = {
    active: "正常",
    online: "在线",
    offline: "离线",
    disabled: "已停用",
    published: "已发布",
    draft: "草稿",
    success: "成功",
    warning: "需关注",
    failure: "失败",
    release: "版本发布",
    running: "运行中",
    pending: "等待确认",
    rejected: "已拒绝",
    expired: "已过期",
    stopped: "已停止",
  };
  return `<span class="status-pill ${escapeHtml(status)}">${labels[status] ?? escapeHtml(status)}</span>`;
}

function pageHeading(title, description, actions = "") {
  return `<div class="page-heading">
    <div><div class="eyebrow">FLOWPILOT CONSOLE</div><h1>${title}</h1><p>${description}</p></div>
    ${actions ? `<div class="heading-actions">${actions}</div>` : ""}
  </div>`;
}

function taskRows() {
  if (!state.tasks.length) return `<div class="empty-state">还没有自动化任务，创建并发布第一个任务吧。</div>`;
  return state.tasks.map((task, index) => `<div class="task-row">
    <div class="task-symbol ${index % 2 ? "mint" : ""}">${index % 2 ? "◌" : "⌕"}</div>
    <div class="task-main"><strong>${escapeHtml(task.name)}</strong><small>${escapeHtml(task.category)} · ${escapeHtml(task.activeVersion)}</small></div>
    <div class="task-meta">${statusPill(task.status)}<span>${task.devices} 台设备</span></div>
  </div>`).join("");
}

function activityRows(items = state.activity.slice(0, 4)) {
  if (!items.length) return `<div class="empty-state">暂无运行记录</div>`;
  return items.map((item) => `<div class="activity-row">
    <div class="activity-icon ${escapeHtml(item.kind)}">${item.kind === "warning" ? "!" : item.kind === "publish" ? "↑" : item.kind === "success" ? "✓" : "■"}</div>
    <div class="activity-copy"><strong>${escapeHtml(item.task)} · ${escapeHtml(item.user)}</strong><p>${escapeHtml(item.detail)} · ${escapeHtml(item.device)}</p></div>
    <span class="activity-time">${escapeHtml(item.time)}</span>
  </div>`).join("");
}

function chartMarkup() {
  return `<div class="mini-chart">
    <div class="chart-top"><span>过去 7 天</span><span><strong>128</strong> 次运行</span></div>
    <svg class="chart" viewBox="0 0 460 130" role="img" aria-label="最近七天任务运行趋势">
      <defs><linearGradient id="chart-fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stop-color="#7666e9" stop-opacity=".2"/><stop offset="100%" stop-color="#7666e9" stop-opacity="0"/></linearGradient></defs>
      <path class="chart-gridline" d="M0 20H460M0 60H460M0 100H460"/>
      <path class="chart-area" d="M0 91 C24 88 34 75 62 79 S104 62 130 70 S170 38 197 52 S238 65 263 46 S304 59 328 35 S371 52 395 27 S437 38 460 15 V115H0Z"/>
      <path class="chart-line secondary" d="M0 103 C32 101 36 94 63 97 S104 84 130 91 S172 73 197 81 S238 85 263 69 S304 76 328 63 S371 69 395 56 S437 62 460 49"/>
      <path class="chart-line" d="M0 91 C24 88 34 75 62 79 S104 62 130 70 S170 38 197 52 S238 65 263 46 S304 59 328 35 S371 52 395 27 S437 38 460 15"/>
    </svg>
    <div class="chart-labels"><span>周一</span><span>周二</span><span>周三</span><span>周四</span><span>周五</span><span>周六</span><span>周日</span></div>
    <div class="chart-legend"><span><i></i>任务运行</span><span><i></i>上周同期</span></div>
  </div>`;
}

function renderOverview() {
  const onlineDevices = state.devices.filter((device) => device.status === "online").length;
  const activeUsers = state.users.filter((user) => user.status === "active").length;
  return `${pageHeading("早上好，林管理员 👋", "这是你的自动化工作空间。以下是当前运行概况。", `<button class="date-pill">◷　最近 7 天　⌄</button>`)}
    <div class="stats-grid">
      <article class="stat-card"><div class="stat-top">总用户数<span class="stat-icon">♙</span></div><div class="stat-value">${state.users.length}</div><div class="stat-foot"><span class="positive">↗ ${activeUsers} 位正常</span><span>· 账号</span></div></article>
      <article class="stat-card"><div class="stat-top">已绑定设备<span class="stat-icon">▣</span></div><div class="stat-value">${state.devices.length}</div><div class="stat-foot"><span class="positive">● ${onlineDevices} 台在线</span><span>· 实时状态</span></div></article>
      <article class="stat-card"><div class="stat-top">自动化任务<span class="stat-icon">⌘</span></div><div class="stat-value">${state.tasks.length}</div><div class="stat-foot"><span class="positive">${state.tasks.filter((task) => task.status === "published").length} 个已发布</span><span>· 可用任务</span></div></article>
      <article class="stat-card"><div class="stat-top">任务成功率<span class="stat-icon">↗</span></div><div class="stat-value">96.8<span style="font-size:13px">%</span></div><div class="stat-foot"><span class="positive">↑ 2.4%</span><span>· 较上周</span></div></article>
    </div>
    <div class="dashboard-grid">
      <section class="panel">
        <div class="panel-header"><div><div class="panel-title">任务运行趋势</div><div class="panel-subtitle">所有设备上的自动化运行情况</div></div><button class="text-button" data-page="activity">查看记录 →</button></div>
        ${chartMarkup()}
      </section>
      <section class="panel">
        <div class="panel-header"><div><div class="panel-title">最近活动</div><div class="panel-subtitle">设备和任务的最新动态</div></div><button class="text-button" data-page="activity">全部 →</button></div>
        <div class="activity-list">${activityRows()}</div>
      </section>
      <section class="panel">
        <div class="panel-header"><div><div class="panel-title">自动化任务</div><div class="panel-subtitle">当前版本及分配状态</div></div><button class="text-button" data-page="tasks">管理任务 →</button></div>
        <div class="task-list">${taskRows()}</div>
      </section>
      <section class="panel">
        <div class="panel-header"><div><div class="panel-title">设备在线状态</div><div class="panel-subtitle">${onlineDevices} 台设备当前在线</div></div><button class="text-button" data-page="devices">查看设备 →</button></div>
        <div class="task-list">${state.devices.slice(0, 3).map((device) => `<div class="task-row">
          <div class="task-symbol ${escapeHtml(device.color)}">▯</div><div class="task-main"><strong>${escapeHtml(device.name)}</strong><small>${escapeHtml(device.userName)} · ${escapeHtml(device.lastActive)}</small></div><div class="task-meta">${statusPill(device.status)}</div>
        </div>`).join("")}</div>
      </section>
    </div>`;
}

function userRows() {
  const filtered = state.users.filter((user) => {
    const matchesQuery = `${user.name} ${user.email}`.toLowerCase().includes(userQuery.toLowerCase());
    return matchesQuery && (userFilter === "all" || user.status === userFilter);
  });
  if (!filtered.length) return `<tr><td colspan="6"><div class="empty-state">没有找到匹配的用户</div></td></tr>`;
  return filtered.map((user, index) => {
    const count = state.devices.filter((device) => device.userId === user.id && device.status !== "disabled").length;
    return `<tr>
      <td><div class="person-cell"><span class="avatar a${(index % 4) + 1}">${escapeHtml(user.name.slice(0, 1))}</span><span class="person-details"><strong>${escapeHtml(user.name)}</strong><small>${escapeHtml(user.email)}</small></span></div></td>
      <td>${statusPill(user.status)}</td><td>${count} / ${user.deviceLimit} 台</td><td>${escapeHtml(user.joined)}</td>
      <td><span style="color:#9aa2b1">基础用户</span></td>
      <td><button class="table-action" data-action="toggle-user" data-id="${escapeHtml(user.id)}" title="${user.status === "active" ? "停用用户" : "启用用户"}">${user.status === "active" ? "···" : "↻"}</button></td>
    </tr>`;
  }).join("");
}

function renderUsers() {
  const actions = `<button class="primary-button" data-action="new-user"><span class="button-icon">＋</span>添加用户</button>`;
  return `${pageHeading("用户管理", "管理账号状态、设备额度和用户访问权限。", actions)}
    <div class="toolbar">
      <label class="search-box"><span>⌕</span><input id="user-search" type="search" placeholder="搜索姓名或邮箱…" value="${escapeHtml(userQuery)}" /></label>
      <div class="toolbar-right"><select class="filter-select" id="user-filter" aria-label="筛选用户状态">
        <option value="all" ${userFilter === "all" ? "selected" : ""}>所有状态</option><option value="active" ${userFilter === "active" ? "selected" : ""}>正常</option><option value="disabled" ${userFilter === "disabled" ? "selected" : ""}>已停用</option>
      </select><button class="secondary-button" data-action="export-users">↓ 导出</button></div>
    </div>
    <section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr><th>用户</th><th>状态</th><th>设备使用量</th><th>创建日期</th><th>账号类型</th><th></th></tr></thead><tbody>${userRows()}</tbody></table></div>
      <div class="table-footer"><span>显示 ${state.users.filter((user) => (user.name + user.email).toLowerCase().includes(userQuery.toLowerCase()) && (userFilter === "all" || user.status === userFilter)).length} 位用户</span><div class="pagination"><button class="current">1</button><button aria-label="下一页">→</button></div></div></section>`;
}

function renderDevices() {
  const devices = state.devices;
  const actions = `<button class="secondary-button" data-action="sync-devices">↻ 同步状态</button>`;
  return `${pageHeading("设备管理", "查看用户绑定的手机及其在线状态。", actions)}
    <div class="stats-grid">
      <article class="stat-card"><div class="stat-top">全部设备<span class="stat-icon">▣</span></div><div class="stat-value">${devices.length}</div><div class="stat-foot">所有已绑定设备</div></article>
      <article class="stat-card"><div class="stat-top">在线<span class="stat-icon">●</span></div><div class="stat-value">${devices.filter((device) => device.status === "online").length}</div><div class="stat-foot"><span class="positive">可接收任务</span></div></article>
      <article class="stat-card"><div class="stat-top">离线<span class="stat-icon">◌</span></div><div class="stat-value">${devices.filter((device) => device.status === "offline").length}</div><div class="stat-foot">等待设备重新连接</div></article>
      <article class="stat-card"><div class="stat-top">已停用<span class="stat-icon">⊘</span></div><div class="stat-value">${devices.filter((device) => device.status === "disabled").length}</div><div class="stat-foot">不会接收任务</div></article>
    </div>
    <div class="toolbar"><div class="eyebrow" style="margin:0">已绑定设备 · ${devices.length}</div><div class="toolbar-right"><select class="filter-select" id="device-filter" aria-label="筛选设备"><option value="all">所有状态</option><option value="online">在线</option><option value="offline">离线</option><option value="disabled">已停用</option></select></div></div>
    <div class="device-grid">${devices.map((device) => `<article class="device-card" data-device-status="${escapeHtml(device.status)}">
      <div class="device-top"><div class="device-avatar ${escapeHtml(device.color)}">${escapeHtml(device.initial)}</div><div class="device-title"><strong>${escapeHtml(device.name)}</strong><small>${escapeHtml(device.model)}</small></div>${statusPill(device.status)}</div>
      <div class="device-info"><span>所属用户<strong>${escapeHtml(device.userName)}</strong></span><span>App 版本<strong>${escapeHtml(device.appVersion)}</strong></span><span>最近活跃<strong>${escapeHtml(device.lastActive)}</strong></span><span>设备 ID<strong>${escapeHtml(device.id)}</strong></span></div>
      <div class="device-footer"><small style="color:#9aa2b1;font-size:9px">任务：订单状态检查</small><button class="text-button" data-action="toggle-device" data-id="${escapeHtml(device.id)}">${device.status === "disabled" ? "重新启用" : "停用设备"}</button></div>
    </article>`).join("")}</div>`;
}

function renderTasks() {
  const actions = `${session ? `<button class="secondary-button" data-action="new-task">＋ 新建脚本</button>` : ""}<button class="primary-button" data-action="publish-task"><span class="button-icon">↑</span>发布新版本</button>`;
  return `${pageHeading("自动化任务", session ? "管理脚本版本、发布状态和设备授权。" : "管理任务版本、发布状态和设备分配。所有更改为演示操作。", actions)}
    <div class="stats-grid">
      <article class="stat-card"><div class="stat-top">任务总数<span class="stat-icon">⌘</span></div><div class="stat-value">${state.tasks.length}</div><div class="stat-foot">工作空间内的任务</div></article>
      <article class="stat-card"><div class="stat-top">已发布<span class="stat-icon">↑</span></div><div class="stat-value">${state.tasks.filter((task) => task.status === "published").length}</div><div class="stat-foot"><span class="positive">用户可获取</span></div></article>
      <article class="stat-card"><div class="stat-top">本周运行<span class="stat-icon">▶</span></div><div class="stat-value">128</div><div class="stat-foot"><span class="positive">↑ 12%</span><span>· 较上周</span></div></article>
      <article class="stat-card"><div class="stat-top">平均成功率<span class="stat-icon">✓</span></div><div class="stat-value">96.8%</div><div class="stat-foot">按最近运行统计</div></article>
    </div>
    <div class="toolbar"><div class="eyebrow" style="margin:0">所有任务 · ${state.tasks.length}</div><div class="toolbar-right"><button class="secondary-button" data-action="task-info">ⓘ 版本说明</button></div></div>
    <div class="task-grid">${state.tasks.map((task, index) => `<article class="task-card">
      <div class="task-card-top"><div class="task-symbol ${index % 2 ? "mint" : ""}">${index % 2 ? "◌" : "⌕"}</div><div class="task-main"><h3>${escapeHtml(task.name)}</h3><small>${escapeHtml(task.category)}</small></div>${statusPill(task.status)}</div>
      <p>${escapeHtml(task.description)}</p>${task.scriptFile ? `<div class="file-meta">${escapeHtml(task.scriptFile.name)} · ${Math.ceil(task.scriptFile.size / 1024)} KB · SHA-256 ${escapeHtml(task.scriptFile.sha256.slice(0, 12))}…</div>` : ""}<div class="task-card-meta"><span>当前版本<strong>${escapeHtml(task.activeVersion ?? "尚未发布")}</strong></span><span>已分配设备<strong>${task.devices} 台设备</strong></span><span>最近运行<strong>${escapeHtml(task.lastRun)}</strong></span><span>成功率<strong>${task.successRate ? `${task.successRate}%` : "—"}</strong></span></div>
      <div class="task-card-footer"><button class="text-button" data-action="switch-version" data-id="${escapeHtml(task.id)}">版本管理 ⌄</button>${session?.user.role === "admin" ? `<button class="text-button" data-action="manage-task-access" data-id="${escapeHtml(task.id)}">访问授权</button>` : ""}${(!session || session.user.role === "admin") && ["published", "paused"].includes(task.status) ? `<button class="text-button" data-action="toggle-task-status" data-id="${escapeHtml(task.id)}">${task.status === "paused" ? "恢复发布" : "暂停任务"}</button>` : ""}<button class="run-button" data-action="remote-run" data-id="${escapeHtml(task.id)}" ${task.status !== "published" ? "disabled" : ""}>▶　请求远程运行</button></div>
    </article>`).join("")}</div>`;
}

function renderActivity() {
  return `${pageHeading("运行记录", "追踪任务、用户和设备的执行情况。", `<button class="secondary-button" data-action="export-activity">↓ 导出记录</button>`)}
    <div class="stats-grid">
      <article class="stat-card"><div class="stat-top">最近运行<span class="stat-icon">◷</span></div><div class="stat-value">${state.activity.filter((item) => item.kind !== "publish").length}</div><div class="stat-foot">当前演示记录</div></article>
      <article class="stat-card"><div class="stat-top">成功<span class="stat-icon">✓</span></div><div class="stat-value">${state.activity.filter((item) => item.kind === "success").length}</div><div class="stat-foot"><span class="positive">运行正常</span></div></article>
      <article class="stat-card"><div class="stat-top">需关注<span class="stat-icon">!</span></div><div class="stat-value">${state.activity.filter((item) => item.kind === "warning").length}</div><div class="stat-foot">设备离线或任务异常</div></article>
      <article class="stat-card"><div class="stat-top">任务发布<span class="stat-icon">↑</span></div><div class="stat-value">${state.activity.filter((item) => item.kind === "publish").length}</div><div class="stat-foot">版本变更记录</div></article>
    </div>
    <section class="panel"><div class="panel-header"><div><div class="panel-title">最近活动</div><div class="panel-subtitle">任务运行与版本发布记录</div></div><span class="demo-indicator"><i></i>实时模拟</span></div>
      <div class="activity-list">${activityRows(state.activity)}</div></section>`;
}

function renderAudit() {
  if (!session || session.user.role !== "admin") {
    return `${pageHeading("操作审计", "查看需要连接管理员 API 会话的关键操作记录。")}
      <section class="panel"><div class="empty-state">请以管理员账号登录后查看审计记录。</div></section>`;
  }
  const actions = `<button class="secondary-button" data-action="refresh-audit" ${auditLoading ? "disabled" : ""}>↻ 刷新记录</button>`;
  const rows = auditEvents.length
    ? auditEvents.map((item) => `<tr>
      <td>${escapeHtml(new Date(item.created_at).toLocaleString("zh-CN"))}</td>
      <td>${escapeHtml(item.actor)}</td>
      <td>${escapeHtml(auditActionLabels[item.action] ?? item.action)}</td>
      <td>${escapeHtml(auditResourceLabels[item.resource_type] ?? item.resource_type)}</td>
      <td class="audit-resource-id">${escapeHtml(item.resource_id)}</td>
    </tr>`).join("")
    : `<tr><td colspan="5"><div class="empty-state">${auditLoading ? "正在加载审计记录…" : "暂无操作审计记录"}</div></td></tr>`;
  return `${pageHeading("操作审计", "记录账号、设备、任务和授权等关键管理操作。", actions)}
    <section class="panel"><div class="table-wrap"><table class="data-table audit-table">
      <thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>资源类型</th><th>资源 ID</th></tr></thead>
      <tbody>${rows}</tbody>
    </table></div><div class="table-footer"><span>最多显示最近 500 条关键操作</span><span>${auditEvents.length} 条记录</span></div></section>`;
}

function phoneTaskCard(task) {
  const canRun = !session || session.user.role === "user";
  const grants = state.runAuthorizations.filter((item) => item.taskId === task.id && item.deviceId === currentPhoneDeviceId && item.expiresAt > Date.now());
  const activeRun = state.runs.find((item) => item.taskId === task.id && item.deviceId === currentPhoneDeviceId && item.status === "running");
  return `<article class="phone-task"><div class="phone-task-top"><div class="task-symbol">⌕</div><div style="flex:1"><strong>${escapeHtml(task.name)}</strong><small>${escapeHtml(task.category)} · ${escapeHtml(task.activeVersion)}</small></div>${statusPill(task.status)}</div>
    <p>${escapeHtml(task.description)}</p>
    ${activeRun ? `<div class="run-live"><span class="status-pill running">设备上运行中 · ${escapeHtml(activeRun.version)}</span>${canRun ? `<button class="phone-run" data-action="finish-run" data-id="${escapeHtml(activeRun.id)}">模拟完成 OCR</button><button class="secondary-button" data-action="stop-run" data-id="${escapeHtml(activeRun.id)}">停止任务</button>` : `<small class="form-hint">请以设备所属用户账号登录以更新运行状态。</small>`}</div>` : `<button class="phone-run" data-action="local-run" data-id="${escapeHtml(task.id)}" ${task.status !== "published" || !canRun ? "disabled" : ""}>▶　查看说明并运行</button>`}
    ${grants.map((grant) => `<div class="phone-result">已预授权此设备运行 ${escapeHtml(grant.version)} · ${new Date(grant.expiresAt).toLocaleDateString("zh-CN")} 到期</div>`).join("")}</article>`;
}

function renderPhoneContent() {
  const phoneUser = session?.user ?? { id: "u-1001", name: "陈晓" };
  if (phoneTab === "devices") {
    const myDevices = state.devices.filter((device) => device.userId === phoneUserId());
    const grants = state.runAuthorizations.filter((item) => myDevices.some((device) => device.id === item.deviceId) && item.expiresAt > Date.now());
    return `<div class="phone-section-title">我的设备 <small>${myDevices.length} 台已绑定</small></div><div class="phone-devices">${myDevices.map((device) => `<div class="phone-device"><div class="device-avatar ${escapeHtml(device.color)}">${escapeHtml(device.initial)}</div><span><strong>${escapeHtml(device.name)}</strong><small>${escapeHtml(device.model)}</small></span>${statusPill(device.status)}</div>`).join("")}</div>
      ${grants.length ? `<div class="phone-section-title">远程运行预授权 <small>仅限指定设备和版本</small></div><div class="phone-devices">${grants.map((grant) => `<div class="phone-device"><span><strong>${escapeHtml(state.tasks.find((task) => task.id === grant.taskId)?.name ?? "已删除任务")} · ${escapeHtml(grant.version)}</strong><small>${escapeHtml(state.devices.find((device) => device.id === grant.deviceId)?.name ?? "")} · 30 天内有效</small></span><button class="text-button" data-action="revoke-grant" data-id="${escapeHtml(grant.id)}">撤销</button></div>`).join("")}</div>` : ""}
      <button class="secondary-button" style="width:100%;margin-top:12px" data-action="phone-bind-device">＋ 绑定新设备</button>`;
  }
  if (phoneTab === "activity") {
    const recent = state.activity.filter((item) => item.user === phoneUser.name).slice(0, 4);
    return `<div class="phone-section-title">最近运行 <small>查看全部</small></div><div class="phone-devices">${recent.length ? recent.map((item) => `<div class="phone-device"><div class="activity-icon ${escapeHtml(item.kind)}">${item.kind === "warning" ? "!" : "✓"}</div><span><strong>${escapeHtml(item.task)}</strong><small>${escapeHtml(item.detail)}</small></span><small style="margin-left:auto;color:#a0a7b4;font-size:7px">${escapeHtml(item.time)}</small></div>`).join("") : `<div class="empty-state">暂无运行记录</div>`}</div>`;
  }
  const tasks = state.tasks.filter((task) => task.status === "published");
  const myDevices = state.devices.filter((device) => device.userId === phoneUserId());
  const pending = state.remoteRuns.filter((request) => request.deviceId === currentPhoneDeviceId && ["pending", "expired"].includes(request.status));
  const running = state.runs.filter((run) => run.deviceId === currentPhoneDeviceId && run.status === "running");
  return `<div class="phone-device-picker"><label for="phone-device-select">当前设备</label><select id="phone-device-select">${myDevices.map((device) => `<option value="${escapeHtml(device.id)}" ${device.id === currentPhoneDeviceId ? "selected" : ""}>${escapeHtml(device.name)} · ${device.status === "online" ? "在线" : "离线"}</option>`).join("")}</select></div>
    ${session?.user.role === "admin" ? `<div class="permission-summary"><strong>管理员预览模式</strong><span>设备确认必须由设备所属用户登录完成；请在另一个标签页使用该用户账号。</span></div>` : ""}
    ${pending.length ? `<div class="phone-section-title">远程运行请求 <small>需要你的确认</small></div>${pending.map((request) => {
    const task = state.tasks.find((item) => item.id === request.taskId);
    const canConfirm = session?.user.role === "user";
    return `<article class="request-card"><strong>${escapeHtml(request.task)} ${request.status === "expired" ? statusPill("expired") : ""}</strong><small>${escapeHtml(request.version)} · ${escapeHtml(request.requestedBy)} 发起 · ${escapeHtml(request.device)}</small><p>脚本来源：${escapeHtml(task?.source ?? "未知")}<br>能力：${(task?.capabilities ?? []).map(escapeHtml).join("、")}</p>${request.status === "pending" && canConfirm ? `<label class="grant-choice"><input type="checkbox" data-grant-request="${escapeHtml(request.id)}" /> 预授权此脚本版本与设备 30 天</label><div class="request-actions"><button class="secondary-button" data-action="reject-request" data-id="${escapeHtml(request.id)}">拒绝</button><button class="primary-button" data-action="approve-request" data-id="${escapeHtml(request.id)}">确认并运行</button></div>` : request.status === "pending" ? `<small>请由设备所属用户账号确认。</small>` : `<small>请求已超过有效时间，未启动。</small>`}</article>`;
  }).join("")}` : ""}
    ${running.length ? `<div class="phone-section-title">前台运行 <small>可随时停止</small></div>${running.map((run) => `<div class="phone-device"><div class="activity-icon">▶</div><span><strong>${escapeHtml(run.task)} · ${escapeHtml(run.version)}</strong><small>${escapeHtml(run.device)} · 持续运行中</small></span>${session?.user.role === "user" ? `<button class="text-button" data-action="stop-run" data-id="${escapeHtml(run.id)}">停止</button>` : ""}</div>`).join("")}` : ""}
    <div class="phone-section-title">可用任务 <small>${tasks.length} 个</small></div>${tasks.length ? tasks.map(phoneTaskCard).join("") : `<div class="empty-state">暂时没有可用任务</div>`}
    <div class="phone-section-title">最近运行 <small data-phone-tab="activity">全部记录 →</small></div>
    ${state.runs.filter((run) => run.deviceId === currentPhoneDeviceId && run.status !== "running").slice(0, 2).map((run) => `<div class="phone-device"><div class="activity-icon ${run.status === "success" ? "" : "warning"}">${run.status === "success" ? "✓" : "!"}</div><span><strong>${escapeHtml(run.task)} · ${statusPill(run.status)}</strong><small>${escapeHtml(run.result ?? "")}</small></span></div>`).join("") || `<div class="empty-state">暂无运行记录</div>`}`;
}

function renderPhone() {
  const tabs = [
    ["home", "⌂", "任务"],
    ["devices", "▯", "设备"],
    ["activity", "◷", "记录"],
  ];
  return `<div class="phone-stage">
    <div class="phone-frame"><div class="phone-screen">
      <div class="phone-status"><span>9:41</span><span class="status-icons">● ▮ ▰</span></div>
      <div class="phone-header"><div><h2>${phoneTab === "devices" ? "我的设备" : phoneTab === "activity" ? "运行记录" : "工作台"}</h2><p>早上好，${escapeHtml(session?.user.name ?? "陈晓")}</p></div><div class="phone-user">${escapeHtml((session?.user.name ?? "陈").slice(0, 1))}</div></div>
      <div class="phone-content">
        ${phoneTab === "home" ? `<div class="phone-welcome"><span>ACME STUDIO · 个人空间</span><h3>一切运行正常 ✨</h3><p>2 台设备在线 · 任务已同步</p></div>` : ""}
        ${renderPhoneContent()}
      </div>
      <nav class="phone-bottom-nav" aria-label="手机端导航">${tabs.map(([tab, icon, label]) => `<button class="phone-tab ${phoneTab === tab ? "active" : ""}" data-phone-tab="${tab}"><span>${icon}</span>${label}</button>`).join("")}</nav>
    </div></div>
    <div class="phone-description"><div class="eyebrow">ANDROID APP · 可交互预览</div><h2>在手机上，轻松运行自动化任务</h2><p>这是用户看到的移动端界面预览。点击手机底部标签浏览设备和运行记录，或运行已发布任务体验完整操作流程。</p>
      <div class="flow-steps"><div class="flow-step"><span class="step-number">1</span><span>查看管理员分配的任务</span></div><div class="flow-step"><span class="step-number">2</span><span>授权所需能力并运行</span></div><div class="flow-step"><span class="step-number">3</span><span>查看执行结果和设备状态</span></div></div>
    </div>
  </div>`;
}

function render() {
  if (!session && !demoMode) {
    appShell.hidden = true;
    loginGate.hidden = false;
    renderLogin();
    return;
  }
  appShell.hidden = false;
  loginGate.hidden = true;
  state = expireRemoteRuns(state);
  const userNav = document.querySelector('[data-page="users"]');
  const auditNav = document.querySelector('[data-page="audit"]');
  if (userNav) userNav.hidden = Boolean(session && session.user.role !== "admin");
  if (auditNav) auditNav.hidden = !session || session.user.role !== "admin";
  const sessionName = document.querySelector(".profile-copy strong");
  const sessionRole = document.querySelector(".profile-copy small");
  if (sessionName) sessionName.textContent = session?.user.name ?? "演示管理员";
  if (sessionRole) sessionRole.textContent = session ? (session.user.role === "admin" ? "系统管理员" : "普通用户") : "演示模式";
  const indicator = document.querySelector(".demo-indicator");
  if (indicator) indicator.innerHTML = session ? "<i></i>服务端数据" : "<i></i>演示数据";
  document.querySelectorAll(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.page === currentPage));
  document.querySelectorAll(".view-option").forEach((item) => item.classList.toggle("selected", item.dataset.view === currentView));
  breadcrumb.textContent = currentView === "mobile" ? "手机 App 预览" : pageNames[currentPage];
  document.querySelector("#user-count").textContent = String(state.users.length).padStart(2, "0");
  workspace.innerHTML = currentView === "mobile"
    ? renderPhone()
    : ({
      overview: renderOverview,
      users: renderUsers,
      devices: renderDevices,
      tasks: renderTasks,
      activity: renderActivity,
      audit: renderAudit,
    }[currentPage]());
}

function renderLogin(error = "") {
  loginGate.innerHTML = `<div class="login-card">
    <a class="brand" href="#" aria-label="FlowPilot"><span class="brand-mark"><span></span><span></span><span></span></span><span class="brand-name">flow<span>pilot</span></span></a>
    <h1>登录 FlowPilot</h1>
    <p>连接本地 API 管理账号、设备与任务。手机端视图供交互验证使用，不代表真实 Android 执行。</p>
    <form id="login-form">
      <div class="form-field"><label for="api-url">服务端地址</label><input id="api-url" name="baseUrl" type="url" value="http://127.0.0.1:8001" required /></div>
      <div class="form-field"><label for="login-email">邮箱</label><input id="login-email" name="email" type="email" value="admin@example.com" autocomplete="username" required /></div>
      <div class="form-field"><label for="login-password">密码</label><input id="login-password" name="password" type="password" value="admin-demo" autocomplete="current-password" required /></div>
      <div class="login-error" role="alert">${escapeHtml(error)}</div>
      <button class="primary-button" type="submit">连接并登录</button>
    </form>
    <div class="login-presets"><strong>本地演示账号</strong><span>管理员：admin@example.com / admin-demo</span><span>用户：chen.xiao@example.com / user-demo</span></div>
    <button class="secondary-button demo-login" data-action="demo-mode">仅浏览离线演示</button>
  </div>`;
}

function phoneUserId() {
  return session?.user.role === "user" ? session.user.id : "u-1001";
}

function showToast(message) {
  const toast = document.createElement("div");
  toast.className = "toast";
  toast.textContent = message;
  toastRegion.append(toast);
  window.setTimeout(() => toast.remove(), 2800);
}

async function refreshFromApi() {
  if (!session) return;
  state = await loadSnapshot(session);
  const devices = state.devices.filter((device) => device.userId === phoneUserId());
  if (!devices.some((device) => device.id === currentPhoneDeviceId) && devices.length) {
    currentPhoneDeviceId = devices[0].id;
  }
  render();
}

function signOut() {
  clearSession();
  session = null;
  demoMode = false;
  state = structuredClone(initialDemoState);
  currentView = "admin";
  currentPage = "overview";
  auditEvents = [];
  auditLoading = false;
  render();
}

async function refreshAudit() {
  if (!session || session.user.role !== "admin") {
    auditEvents = [];
    auditLoading = false;
    render();
    return;
  }
  const token = session.token;
  auditLoading = true;
  render();
  try {
    const events = await apiRequest(session, "/admin/audit");
    if (session?.token === token) auditEvents = events;
  } catch (error) {
    showToast(error.message);
  } finally {
    auditLoading = false;
    render();
  }
}

function closeModal() {
  modalRoot.innerHTML = "";
}

function showUserModal() {
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">添加用户</h2><p>创建一个新账号，并设置可绑定设备数量。</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="user-form"><div class="modal-body">
      <div class="form-field"><label for="user-name">用户姓名</label><input id="user-name" name="name" required placeholder="例如：林小满" /></div>
      <div class="form-field"><label for="user-email">邮箱地址</label><input id="user-email" name="email" type="email" required placeholder="name@example.com" /></div>
      <div class="form-field"><label for="device-limit">设备数量上限</label><select id="device-limit" name="deviceLimit"><option value="2">2 台设备</option><option value="3">3 台设备</option><option value="5" selected>5 台设备</option><option value="10">10 台设备</option></select></div>
      ${session ? `<div class="form-field"><label for="user-password">初始密码</label><input id="user-password" name="password" type="password" minlength="8" required placeholder="至少 8 个字符" /></div>` : ""}
      <div class="form-hint">${session ? "创建后用户可使用此邮箱和初始密码登录。" : "此 Demo 仅模拟创建账号，不会发送邀请邮件。"}</div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">创建用户</button></div></form>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
  modalRoot.querySelector("#user-name").focus();
}

function showRemoteRunModal(taskId) {
  const task = state.tasks.find((item) => item.id === taskId);
  const devices = state.devices.filter((device) =>
    device.status === "online" &&
    (!session || session.user.role === "admin" || device.userId === session.user.id));
  if (!devices.length) {
    showToast("当前没有在线设备可接收请求");
    return;
  }
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">请求远程运行</h2><p>${escapeHtml(task.name)} · ${escapeHtml(task.activeVersion)}</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="remote-run-form" data-task-id="${escapeHtml(task.id)}"><div class="modal-body">
      <div class="form-field"><label for="target-device">目标设备</label><select id="target-device" name="deviceId">${devices.map((device) => `<option value="${escapeHtml(device.id)}">${escapeHtml(device.userName)} · ${escapeHtml(device.name)}</option>`).join("")}</select></div>
      <div class="permission-summary"><strong>脚本来源：${escapeHtml(task.source ?? "用户脚本")}</strong><span>运行请求将在设备端等待用户确认，不会静默执行。</span><span>所需能力：${(task.capabilities ?? []).map(escapeHtml).join("、")}</span></div>
      <div class="form-hint">请求 15 分钟后过期。发布或请求不会自动运行脚本。</div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">发送请求</button></div></form>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
}

function showConsentModal(taskId) {
  const task = state.tasks.find((item) => item.id === taskId);
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">运行前确认</h2><p>${escapeHtml(task.name)} · ${escapeHtml(task.activeVersion)}</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="local-run-form" data-task-id="${escapeHtml(task.id)}"><div class="modal-body">
      <div class="permission-summary"><strong>来源：${escapeHtml(task.source ?? "用户脚本")}</strong><span>版本：${escapeHtml(task.activeVersion)}</span><span>能力说明：${(task.capabilities ?? []).map(escapeHtml).join("、")}</span><span>演示 OCR 场景会读取屏幕内容；真实 App 必须另行取得 Android 系统授权。</span></div>
      <label class="grant-choice"><input type="checkbox" name="preauthorize" /> 在此设备上预授权此明确版本 30 天（可撤销；不会授权新版本）</label>
      <div class="form-hint">脚本在 App 进程内运行并非安全沙箱。确认运行不代表脚本已被隔离或证明安全。</div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">同意并运行</button></div></form>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
}

function showBindDeviceModal() {
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">绑定设备</h2><p>此原型登记设备信息；真实 App 应使用一次性绑定码。</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="device-form"><div class="modal-body">
      <div class="form-field"><label for="device-name">设备名称</label><input id="device-name" name="name" required maxlength="120" placeholder="例如：Pixel 9" /></div>
      <div class="form-field"><label for="device-model">设备型号与 Android 版本</label><input id="device-model" name="model" required maxlength="160" placeholder="Google · Android 15" /></div>
      <div class="form-field"><label for="app-version">App 版本</label><input id="app-version" name="app_version" value="1.0.0" required maxlength="32" /></div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">确认绑定</button></div></form>
  </section></div>`;
}

function showPublishModal(taskId = state.tasks[0]?.id) {
  const task = state.tasks.find((item) => item.id === taskId) ?? state.tasks[0];
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">发布任务版本</h2><p>${escapeHtml(task.name)} · 当前版本 ${escapeHtml(task.activeVersion ?? "尚未发布")}</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="publish-form" data-task-id="${escapeHtml(task.id)}"><div class="modal-body">
      <div class="form-field"><label for="task-version">新版本号</label><input id="task-version" name="version" required pattern="v\\d+\\.\\d+\\.\\d+" value="${suggestVersion(task.activeVersion ?? "")}" /><span class="form-hint">使用语义化版本格式，例如 v1.5.0。</span></div>
      <div class="form-field"><label for="task-script">Python 脚本文件</label><input id="task-script" name="script" type="file" accept=".py,text/x-python" required /><span class="form-hint">单个 .py 文件，最大 2 MB。</span></div>
      <div class="form-field"><label for="release-notes">更新说明</label><input id="release-notes" name="notes" placeholder="简要说明本次更新内容" /></div>
      ${session?.user.role === "admin" ? `<div class="form-field"><label for="grant-users">发布后授权用户</label><select id="grant-users" name="grant_users" multiple size="3">${state.users.filter((user) => user.status === "active" && user.role === "user").map((user) => `<option value="${escapeHtml(user.id)}">${escapeHtml(user.name)} · ${escapeHtml(user.email)}</option>`).join("")}</select><span class="form-hint">不选择则只发布版本，不授予其他用户访问权限。</span></div>` : ""}
      <div class="form-hint">${session ? "文件将上传到本地开发服务并计算 SHA-256；服务端不会执行脚本。" : "此 Demo 不会将文件上传或执行；发布结果仅保留文件名、大小和哈希元数据。"}</div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">确认发布</button></div></form>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
  modalRoot.querySelector("#task-version").focus();
}

function showNewTaskModal() {
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">新建自动化脚本</h2><p>先创建任务元数据，再上传并发布第一个不可变版本。</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="task-form"><div class="modal-body">
      <div class="form-field"><label for="new-task-name">任务名称</label><input id="new-task-name" name="name" required maxlength="120" placeholder="例如：订单状态检查" /></div>
      <div class="form-field"><label for="new-task-description">任务说明</label><input id="new-task-description" name="description" maxlength="500" placeholder="描述任务用途和场景" /></div>
      <div class="form-field"><label for="new-task-capabilities">能力说明</label><input id="new-task-capabilities" name="capabilities" value="屏幕捕获（需系统授权）, OCR 文字识别" required /></div>
      <div class="form-hint">脚本执行时仍受 Android 系统权限及 App 能力边界限制。</div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">创建并添加版本</button></div></form>
  </section></div>`;
}

function showVersionModal(taskId) {
  const task = state.tasks.find((item) => item.id === taskId);
  if (session) {
    modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
      <div class="modal-header"><div><h2 id="modal-title">版本管理</h2><p>${escapeHtml(task.name)} · 服务器保存的不可变版本</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
      <div class="modal-body">${(task.apiVersions ?? []).map((item) => `<div class="version-item"><strong>${escapeHtml(item.version)}${item.version === task.activeVersion ? " · 当前版本" : ""}</strong><span>${escapeHtml(item.filename)} · ${escapeHtml(item.sha256)}</span><small>${escapeHtml(item.notes || "无更新说明")}</small></div>`).join("") || `<div class="empty-state">暂无版本</div>`}<div class="form-hint">发布新版本会保留历史版本；当前 API 尚未提供回滚操作。</div></div>
      <div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">关闭</button></div>
    </section></div>`;
    return;
  }
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">版本管理</h2><p>${escapeHtml(task.name)} · 回滚会将选中版本重新设为当前版本。</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="version-form" data-task-id="${escapeHtml(task.id)}"><div class="modal-body"><div class="form-field"><label for="rollback-version">历史版本</label><select id="rollback-version" name="version">${task.versions.map((version) => `<option value="${escapeHtml(version)}" ${version === task.activeVersion ? "selected" : ""}>${escapeHtml(version)}${version === task.activeVersion ? " · 当前" : ""}</option>`).join("")}</select></div><div class="form-hint">选择历史版本后确认，即模拟回滚操作。</div></div>
    <div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">设为当前版本</button></div></form>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
}

function suggestVersion(version) {
  if (typeof version !== "string" || !version) return "v1.0.0";
  const match = version.match(/^v(\d+)\.(\d+)\.(\d+)$/);
  return match ? `v${match[1]}.${match[2]}.${Number(match[3]) + 1}` : "v1.0.0";
}

async function showTaskAccessModal(taskId) {
  const task = state.tasks.find((item) => item.id === taskId);
  const grants = await apiRequest(session, `/tasks/${encodeURIComponent(taskId)}/grants`);
  const grantedIds = new Set(grants.map((grant) => grant.id));
  const eligibleUsers = state.users.filter((user) =>
    user.role === "user" && user.status === "active" && !grantedIds.has(user.id));
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">访问授权</h2><p>${escapeHtml(task?.name ?? "任务")} · 管理可以查看和运行此任务的用户</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <div class="modal-body">
      <div class="task-grant-list">${grants.length ? grants.map((grant) => `<div class="task-grant-row"><span><strong>${escapeHtml(grant.name)}</strong><small>${escapeHtml(grant.email)} · ${grant.status === "active" ? "正常" : "已停用"}${grant.is_owner ? " · 所有者" : ""}</small></span>${grant.is_owner ? `<span class="form-hint">所有者</span>` : `<button class="text-button" data-action="revoke-task-grant" data-task-id="${escapeHtml(taskId)}" data-user-id="${escapeHtml(grant.id)}">撤销</button>`}</div>`).join("") : `<div class="empty-state">尚未授权其他用户</div>`}</div>
      ${eligibleUsers.length ? `<form id="task-access-form" data-task-id="${escapeHtml(taskId)}"><div class="form-field"><label for="task-access-user">授权用户</label><select id="task-access-user" name="userId" required>${eligibleUsers.map((user) => `<option value="${escapeHtml(user.id)}">${escapeHtml(user.name)} · ${escapeHtml(user.email)}</option>`).join("")}</select></div><button class="primary-button" type="submit">添加授权</button></form>` : `<div class="form-hint">没有可添加的正常用户。</div>`}
      <div class="form-hint">撤销后用户将无法同步或下载任务；未处理的远程请求和此任务的预授权会失效，已开始的运行不受影响。</div>
    </div>
    <div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">完成</button></div>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
}

function switchVersion(taskId, version) {
  const task = state.tasks.find((item) => item.id === taskId);
  const nextVersions = [task.activeVersion, ...task.versions.filter((item) => item !== version && item !== task.activeVersion)];
  state = publishTaskVersion(state, { taskId, version, notes: "版本切换 / 回滚" });
  state = {
    ...state,
    tasks: state.tasks.map((item) => item.id === taskId ? { ...item, versions: nextVersions } : item),
  };
}

document.addEventListener("click", async (event) => {
  const viewButton = event.target.closest("[data-view]");
  if (viewButton) {
    currentView = viewButton.dataset.view;
    document.querySelector("#sidebar").classList.remove("open");
    render();
    return;
  }

  const phoneButton = event.target.closest("[data-phone-tab]");
  if (phoneButton) {
    phoneTab = phoneButton.dataset.phoneTab;
    render();
    return;
  }

  const pageButton = event.target.closest("[data-page]");
  if (pageButton) {
    currentPage = pageButton.dataset.page;
    currentView = "admin";
    document.querySelector("#sidebar").classList.remove("open");
    render();
    if (currentPage === "audit") await refreshAudit();
    return;
  }

  const actionButton = event.target.closest("[data-action]");
  if (!actionButton) return;
  const { action, id } = actionButton.dataset;
  if (action === "refresh-audit") await refreshAudit();
  else if (action === "new-user") showUserModal();
  else if (action === "new-task") showNewTaskModal();
  else if (action === "publish-task") showPublishModal();
  else if (action === "switch-version") showVersionModal(id);
  else if (action === "remote-run") showRemoteRunModal(id);
  else if (action === "local-run") showConsentModal(id);
  else if (action === "manage-task-access") {
    try { await showTaskAccessModal(id); }
    catch (error) { showToast(error.message); }
  } else if (action === "revoke-task-grant") {
    const { taskId, userId } = actionButton.dataset;
    try {
      await apiRequest(session, `/tasks/${encodeURIComponent(taskId)}/grants/${encodeURIComponent(userId)}`, {
        method: "DELETE",
      });
      await refreshFromApi();
      await showTaskAccessModal(taskId);
      showToast("任务访问权限已撤销");
    } catch (error) { showToast(error.message); }
  }
  else if (action === "toggle-task-status") {
    const task = state.tasks.find((item) => item.id === id);
    const nextStatus = task.status === "paused" ? "published" : "paused";
    try {
      if (session) {
        await apiRequest(session, `/tasks/${encodeURIComponent(id)}`, {
          method: "PATCH",
          body: { status: nextStatus },
        });
        await refreshFromApi();
      } else {
        state = setTaskPaused(state, id, nextStatus === "paused");
        render();
      }
      showToast(nextStatus === "paused" ? "任务已暂停；待处理请求和预授权已撤销" : "任务已恢复发布");
    } catch (error) { showToast(error.message); }
  }
  else if (action === "close-modal") closeModal();
  else if (action === "logout") signOut();
  else if (action === "demo-mode") {
    demoMode = true;
    state = structuredClone(initialDemoState);
    render();
  }
  else if (action === "go-tasks") { currentPage = "tasks"; currentView = "admin"; render(); }
  else if (action === "toggle-user") {
    try {
      if (session) {
        const user = state.users.find((item) => item.id === id);
        await apiRequest(session, `/users/${encodeURIComponent(id)}`, {
          method: "PATCH",
          body: { status: user.status === "active" ? "disabled" : "active" },
        });
        await refreshFromApi();
      } else {
        state = toggleUser(state, id);
        render();
      }
      showToast("用户状态已更新");
    } catch (error) { showToast(error.message); }
  } else if (action === "toggle-device") {
    try {
      if (session) {
        const device = state.devices.find((item) => item.id === id);
        await apiRequest(session, `/devices/${encodeURIComponent(id)}`, {
          method: "PATCH",
          body: { status: device.status === "disabled" ? "offline" : "disabled" },
        });
        await refreshFromApi();
      } else {
        state = toggleDevice(state, id);
        render();
      }
      showToast("设备状态已更新");
    } catch (error) { showToast(error.message); }
  } else if (action === "approve-request" || action === "reject-request") {
    try {
      const checkbox = workspace.querySelector(`[data-grant-request="${CSS.escape(id)}"]`);
      if (session) {
        await apiRequest(session, `/remote-runs/${encodeURIComponent(id)}`, {
          method: "PATCH",
          body: { decision: action === "approve-request" ? "approve" : "reject", preauthorize: checkbox?.checked ?? false },
        });
        await refreshFromApi();
      } else {
        state = resolveRemoteRun(state, id, {
          approved: action === "approve-request",
          preauthorize: checkbox?.checked ?? false,
        });
        render();
      }
      showToast(action === "approve-request" ? "已确认；任务正在设备上可见运行" : "已拒绝远程运行请求");
    } catch (error) { showToast(error.message); }
  } else if (action === "finish-run" || action === "stop-run") {
    try {
      if (session) {
        await apiRequest(session, `/runs/${encodeURIComponent(id)}`, {
          method: "POST",
          body: {
            status: action === "finish-run" ? "success" : "stopped",
            result_summary: action === "finish-run" ? "演示 OCR 识别到「已发货」" : "用户已停止任务",
          },
        });
        await refreshFromApi();
      } else {
        state = finishRun(state, id, action === "finish-run" ? "success" : "stopped");
        render();
      }
      showToast(action === "finish-run" ? "演示运行完成，结果已记录" : "任务已停止");
    } catch (error) { showToast(error.message); }
  } else if (action === "revoke-grant") {
    try {
      if (session) {
        await apiRequest(session, `/run-authorizations/${encodeURIComponent(id)}`, { method: "DELETE" });
        await refreshFromApi();
      } else {
        state = revokeRunAuthorization(state, id);
        render();
      }
      showToast("预授权已撤销");
    } catch (error) { showToast(error.message); }
  } else if (action === "sync-devices") {
    if (!session) showToast("设备状态已同步（演示）");
    else {
      try {
        const devices = state.devices.filter((device) => device.status === "online" && (session.user.role === "admin" || device.userId === session.user.id));
        await Promise.all(devices.map((device) => apiRequest(session, `/devices/${encodeURIComponent(device.id)}/sync`, { method: "POST", body: {} })));
        await refreshFromApi();
        showToast("设备状态已同步");
      } catch (error) { showToast(error.message); }
    }
  }
  else if (action === "phone-bind-device") {
    if (session?.user.role === "user") showBindDeviceModal();
    else if (session) showToast("请以普通用户账号登录后绑定自己的设备");
    else showToast("请在真实 App 中扫描绑定码（演示）");
  }
  else if (action === "task-info") showToast("任务版本会经过校验后下发至授权设备（演示）");
  else if (action === "export-users" || action === "export-activity") showToast("演示数据暂不支持下载");
});

document.addEventListener("submit", async (event) => {
  if (event.target.id === "task-access-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    const taskId = event.target.dataset.taskId;
    try {
      await apiRequest(session, `/tasks/${encodeURIComponent(taskId)}/grants/${encodeURIComponent(formData.get("userId"))}`, {
        method: "POST",
        body: {},
      });
      await refreshFromApi();
      await showTaskAccessModal(taskId);
      showToast("任务访问权限已添加");
    } catch (error) { showToast(error.message); }
  } else if (event.target.id === "login-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    try {
      session = await login(
        String(formData.get("baseUrl")).trim(),
        String(formData.get("email")).trim(),
        String(formData.get("password")),
      );
      demoMode = false;
      currentPage = "overview";
      currentView = "admin";
      phoneTab = "home";
      state = await loadSnapshot(session);
      const devices = state.devices.filter((device) => device.userId === phoneUserId());
      currentPhoneDeviceId = devices[0]?.id ?? "";
      render();
    } catch (error) {
      session = null;
      clearSession();
      renderLogin(error.message);
    }
  } else if (event.target.id === "user-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    try {
      if (session) {
        await apiRequest(session, "/users", {
          method: "POST",
          body: {
            name: formData.get("name"),
            email: formData.get("email"),
            device_limit: Number(formData.get("deviceLimit")),
            password: formData.get("password"),
          },
        });
        await refreshFromApi();
      } else {
        state = addUser(state, {
          name: formData.get("name"),
          email: formData.get("email"),
          deviceLimit: formData.get("deviceLimit"),
        });
      }
      closeModal();
      currentPage = "users";
      currentView = "admin";
      userQuery = "";
      userFilter = "all";
      render();
      showToast(session ? "用户已创建" : "用户已创建（演示）");
    } catch (error) {
      showToast(error.message);
    }
  } else if (event.target.id === "device-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    try {
      await apiRequest(session, "/devices", {
        method: "POST",
        body: {
          name: formData.get("name"),
          model: formData.get("model"),
          app_version: formData.get("app_version"),
        },
      });
      closeModal();
      await refreshFromApi();
      showToast("设备已绑定");
    } catch (error) { showToast(error.message); }
  } else if (event.target.id === "task-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    try {
      const created = await apiRequest(session, "/tasks", {
        method: "POST",
        body: {
          name: formData.get("name"),
          description: formData.get("description"),
          category: "OCR 识别",
          capabilities: String(formData.get("capabilities")).split(",").map((item) => item.trim()).filter(Boolean),
        },
      });
      await refreshFromApi();
      currentPage = "tasks";
      currentView = "admin";
      render();
      showPublishModal(created.id);
    } catch (error) { showToast(error.message); }
  } else if (event.target.id === "remote-run-form") {
    event.preventDefault();
    try {
      const deviceId = new FormData(event.target).get("deviceId");
      let request;
      if (session) {
        request = await apiRequest(session, "/remote-runs", {
          method: "POST",
          body: { task_id: event.target.dataset.taskId, device_id: deviceId },
        });
        await refreshFromApi();
      } else {
        state = createRemoteRun(state, {
          taskId: event.target.dataset.taskId,
          deviceId,
          requestedBy: "林管理员",
        });
        request = state.remoteRuns[0];
      }
      currentPhoneDeviceId = deviceId;
      closeModal();
      currentView = "mobile";
      phoneTab = "home";
      render();
      showToast(request.status === "running" ? "已按该设备预授权启动" : "请求已发送，等待手机用户确认");
    } catch (error) {
      showToast(error.message);
    }
  } else if (event.target.id === "local-run-form") {
    event.preventDefault();
    try {
      const formData = new FormData(event.target);
      if (session) {
        const task = state.tasks.find((item) => item.id === event.target.dataset.taskId);
        if (formData.get("preauthorize") === "on") {
          await apiRequest(session, "/run-authorizations", {
            method: "POST",
            body: { task_id: task.id, version: task.activeVersion, device_id: currentPhoneDeviceId },
          });
        }
        await apiRequest(session, `/devices/${encodeURIComponent(currentPhoneDeviceId)}/runs`, {
          method: "POST",
          body: { task_id: task.id, version: task.activeVersion },
        });
        await refreshFromApi();
      } else {
        state = startLocalRun(state, {
          taskId: event.target.dataset.taskId,
          deviceId: currentPhoneDeviceId,
          preauthorize: formData.get("preauthorize") === "on",
        });
      }
      closeModal();
      render();
      showToast("任务已在设备上可见运行");
    } catch (error) {
      showToast(error.message);
    }
  } else if (event.target.id === "publish-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    try {
      const file = formData.get("script");
      if (!(file instanceof File) || !file.name.toLowerCase().endsWith(".py") || file.size < 1 || file.size > 2 * 1024 * 1024) {
        throw new Error("请选择 2 MB 以内的非空 .py 文件");
      }
      if (session) {
        const upload = new FormData();
        upload.set("version", formData.get("version"));
        upload.set("notes", formData.get("notes"));
        upload.set("script", file);
        await apiRequest(session, `/tasks/${encodeURIComponent(event.target.dataset.taskId)}/versions`, {
          method: "POST",
          formData: upload,
        });
        await Promise.all(formData.getAll("grant_users").map((userId) =>
          apiRequest(session, `/tasks/${encodeURIComponent(event.target.dataset.taskId)}/grants/${encodeURIComponent(userId)}`, { method: "POST", body: {} })));
        await refreshFromApi();
      } else {
        if (!globalThis.crypto?.subtle) throw new Error("当前浏览器不支持安全的 SHA-256 校验");
        const digest = await globalThis.crypto.subtle.digest("SHA-256", await file.arrayBuffer());
        const sha256 = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
        state = publishTaskVersion(state, {
          taskId: event.target.dataset.taskId,
          version: formData.get("version"),
          notes: formData.get("notes"),
          scriptFile: { name: file.name, size: file.size, sha256 },
        });
      }
      closeModal();
      currentPage = "tasks";
      currentView = "admin";
      render();
      showToast(session ? "任务版本已发布并保存到服务端" : "任务版本已发布（演示）");
    } catch (error) {
      showToast(error.message);
    }
  } else if (event.target.id === "version-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    if (session) {
      closeModal();
      showToast("服务端版本为不可变记录，当前 API 不支持回滚");
    } else {
      switchVersion(event.target.dataset.taskId, formData.get("version"));
      closeModal();
      render();
      showToast("已切换至选中版本（演示）");
    }
  }
});

document.addEventListener("input", (event) => {
  if (event.target.id === "user-search") {
    const selectionStart = event.target.selectionStart;
    userQuery = event.target.value;
    const tbody = workspace.querySelector(".data-table tbody");
    if (tbody) tbody.innerHTML = userRows();
    const replacement = workspace.querySelector("#user-search");
    replacement?.focus();
    replacement?.setSelectionRange(selectionStart, selectionStart);
  }
});

document.addEventListener("change", (event) => {
  if (event.target.id === "phone-device-select") {
    currentPhoneDeviceId = event.target.value;
    render();
  }
  if (event.target.id === "user-filter") {
    userFilter = event.target.value;
    render();
  }
  if (event.target.id === "device-filter") {
    workspace.querySelectorAll("[data-device-status]").forEach((card) => {
      card.hidden = event.target.value !== "all" && card.dataset.deviceStatus !== event.target.value;
    });
  }
});

window.addEventListener("flowpilot:session-expired", () => {
  session = null;
  demoMode = false;
  render();
});

document.querySelector("#mobile-menu").addEventListener("click", () => {
  document.querySelector("#sidebar").classList.toggle("open");
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeModal();
});

render();
if (session) {
  apiRequest(session, "/me").then((user) => {
    session = { ...session, user };
    return refreshFromApi();
  }).catch((error) => {
    clearSession();
    session = null;
    renderLogin(error.message);
  });
}
