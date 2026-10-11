import {
  addUser,
  createRemoteRun,
  expireRemoteRuns,
  finishRun,
  initialDemoState,
  publishTaskVersion,
  resolveRemoteRun,
  revokeRunAuthorization,
  startLocalRun,
  toggleDevice,
  toggleUser,
} from "./state.mjs";

let state = structuredClone(initialDemoState);
let currentView = "admin";
let currentPage = "overview";
let phoneTab = "home";
let currentPhoneDeviceId = "d-001";
let userQuery = "";
let userFilter = "all";

const workspace = document.querySelector("#workspace");
const modalRoot = document.querySelector("#modal-root");
const toastRegion = document.querySelector("#toast-region");
const breadcrumb = document.querySelector("#breadcrumb-current");

const pageNames = {
  overview: "总览",
  users: "用户管理",
  devices: "设备管理",
  tasks: "自动化任务",
  activity: "运行记录",
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
  const actions = `<button class="primary-button" data-action="publish-task"><span class="button-icon">↑</span>发布新版本</button>`;
  return `${pageHeading("自动化任务", "管理任务版本、发布状态和设备分配。所有更改为演示操作。", actions)}
    <div class="stats-grid">
      <article class="stat-card"><div class="stat-top">任务总数<span class="stat-icon">⌘</span></div><div class="stat-value">${state.tasks.length}</div><div class="stat-foot">工作空间内的任务</div></article>
      <article class="stat-card"><div class="stat-top">已发布<span class="stat-icon">↑</span></div><div class="stat-value">${state.tasks.filter((task) => task.status === "published").length}</div><div class="stat-foot"><span class="positive">用户可获取</span></div></article>
      <article class="stat-card"><div class="stat-top">本周运行<span class="stat-icon">▶</span></div><div class="stat-value">128</div><div class="stat-foot"><span class="positive">↑ 12%</span><span>· 较上周</span></div></article>
      <article class="stat-card"><div class="stat-top">平均成功率<span class="stat-icon">✓</span></div><div class="stat-value">96.8%</div><div class="stat-foot">按最近运行统计</div></article>
    </div>
    <div class="toolbar"><div class="eyebrow" style="margin:0">所有任务 · ${state.tasks.length}</div><div class="toolbar-right"><button class="secondary-button" data-action="task-info">ⓘ 版本说明</button></div></div>
    <div class="task-grid">${state.tasks.map((task, index) => `<article class="task-card">
      <div class="task-card-top"><div class="task-symbol ${index % 2 ? "mint" : ""}">${index % 2 ? "◌" : "⌕"}</div><div class="task-main"><h3>${escapeHtml(task.name)}</h3><small>${escapeHtml(task.category)}</small></div>${statusPill(task.status)}</div>
      <p>${escapeHtml(task.description)}</p>${task.scriptFile ? `<div class="file-meta">${escapeHtml(task.scriptFile.name)} · ${Math.ceil(task.scriptFile.size / 1024)} KB · SHA-256 ${escapeHtml(task.scriptFile.sha256.slice(0, 12))}…</div>` : ""}<div class="task-card-meta"><span>当前版本<strong>${escapeHtml(task.activeVersion)}</strong></span><span>已分配设备<strong>${task.devices} 台设备</strong></span><span>最近运行<strong>${escapeHtml(task.lastRun)}</strong></span><span>成功率<strong>${task.successRate ? `${task.successRate}%` : "—"}</strong></span></div>
      <div class="task-card-footer"><button class="text-button" data-action="switch-version" data-id="${escapeHtml(task.id)}">版本管理 ⌄</button><button class="run-button" data-action="remote-run" data-id="${escapeHtml(task.id)}" ${task.status !== "published" ? "disabled" : ""}>▶　请求远程运行</button></div>
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

function phoneTaskCard(task) {
  const grants = state.runAuthorizations.filter((item) => item.taskId === task.id && item.deviceId === currentPhoneDeviceId && item.expiresAt > Date.now());
  const activeRun = state.runs.find((item) => item.taskId === task.id && item.deviceId === currentPhoneDeviceId && item.status === "running");
  return `<article class="phone-task"><div class="phone-task-top"><div class="task-symbol">⌕</div><div style="flex:1"><strong>${escapeHtml(task.name)}</strong><small>${escapeHtml(task.category)} · ${escapeHtml(task.activeVersion)}</small></div>${statusPill(task.status)}</div>
    <p>${escapeHtml(task.description)}</p>
    ${activeRun ? `<div class="run-live"><span class="status-pill running">设备上运行中 · ${escapeHtml(activeRun.version)}</span><button class="phone-run" data-action="finish-run" data-id="${escapeHtml(activeRun.id)}">模拟完成 OCR</button><button class="secondary-button" data-action="stop-run" data-id="${escapeHtml(activeRun.id)}">停止任务</button></div>` : `<button class="phone-run" data-action="local-run" data-id="${escapeHtml(task.id)}" ${task.status !== "published" ? "disabled" : ""}>▶　查看说明并运行</button>`}
    ${grants.map((grant) => `<div class="phone-result">已预授权此设备运行 ${escapeHtml(grant.version)} · ${new Date(grant.expiresAt).toLocaleDateString("zh-CN")} 到期</div>`).join("")}</article>`;
}

function renderPhoneContent() {
  if (phoneTab === "devices") {
    const myDevices = state.devices.filter((device) => device.userId === "u-1001");
    const grants = state.runAuthorizations.filter((item) => myDevices.some((device) => device.id === item.deviceId) && item.expiresAt > Date.now());
    return `<div class="phone-section-title">我的设备 <small>${myDevices.length} 台已绑定</small></div><div class="phone-devices">${myDevices.map((device) => `<div class="phone-device"><div class="device-avatar ${escapeHtml(device.color)}">${escapeHtml(device.initial)}</div><span><strong>${escapeHtml(device.name)}</strong><small>${escapeHtml(device.model)}</small></span>${statusPill(device.status)}</div>`).join("")}</div>
      ${grants.length ? `<div class="phone-section-title">远程运行预授权 <small>仅限指定设备和版本</small></div><div class="phone-devices">${grants.map((grant) => `<div class="phone-device"><span><strong>${escapeHtml(state.tasks.find((task) => task.id === grant.taskId)?.name ?? "已删除任务")} · ${escapeHtml(grant.version)}</strong><small>${escapeHtml(state.devices.find((device) => device.id === grant.deviceId)?.name ?? "")} · 30 天内有效</small></span><button class="text-button" data-action="revoke-grant" data-id="${escapeHtml(grant.id)}">撤销</button></div>`).join("")}</div>` : ""}
      <button class="secondary-button" style="width:100%;margin-top:12px" data-action="phone-bind-device">＋ 绑定新设备</button>`;
  }
  if (phoneTab === "activity") {
    const recent = state.activity.filter((item) => item.user === "陈晓").slice(0, 4);
    return `<div class="phone-section-title">最近运行 <small>查看全部</small></div><div class="phone-devices">${recent.length ? recent.map((item) => `<div class="phone-device"><div class="activity-icon ${escapeHtml(item.kind)}">${item.kind === "warning" ? "!" : "✓"}</div><span><strong>${escapeHtml(item.task)}</strong><small>${escapeHtml(item.detail)}</small></span><small style="margin-left:auto;color:#a0a7b4;font-size:7px">${escapeHtml(item.time)}</small></div>`).join("") : `<div class="empty-state">暂无运行记录</div>`}</div>`;
  }
  const tasks = state.tasks.filter((task) => task.status === "published");
  const myDevices = state.devices.filter((device) => device.userId === "u-1001");
  const pending = state.remoteRuns.filter((request) => request.deviceId === currentPhoneDeviceId && ["pending", "expired"].includes(request.status));
  const running = state.runs.filter((run) => run.deviceId === currentPhoneDeviceId && run.status === "running");
  return `<div class="phone-device-picker"><label for="phone-device-select">当前设备</label><select id="phone-device-select">${myDevices.map((device) => `<option value="${escapeHtml(device.id)}" ${device.id === currentPhoneDeviceId ? "selected" : ""}>${escapeHtml(device.name)} · ${device.status === "online" ? "在线" : "离线"}</option>`).join("")}</select></div>
    ${pending.length ? `<div class="phone-section-title">远程运行请求 <small>需要你的确认</small></div>${pending.map((request) => {
    const task = state.tasks.find((item) => item.id === request.taskId);
    return `<article class="request-card"><strong>${escapeHtml(request.task)} ${request.status === "expired" ? statusPill("expired") : ""}</strong><small>${escapeHtml(request.version)} · ${escapeHtml(request.requestedBy)} 发起 · ${escapeHtml(request.device)}</small><p>脚本来源：${escapeHtml(task?.source ?? "未知")}<br>能力：${(task?.capabilities ?? []).map(escapeHtml).join("、")}</p>${request.status === "pending" ? `<label class="grant-choice"><input type="checkbox" data-grant-request="${escapeHtml(request.id)}" /> 预授权此脚本版本与设备 30 天</label><div class="request-actions"><button class="secondary-button" data-action="reject-request" data-id="${escapeHtml(request.id)}">拒绝</button><button class="primary-button" data-action="approve-request" data-id="${escapeHtml(request.id)}">确认并运行</button></div>` : `<small>请求已超过有效时间，未启动。</small>`}</article>`;
  }).join("")}` : ""}
    ${running.length ? `<div class="phone-section-title">前台运行 <small>可随时停止</small></div>${running.map((run) => `<div class="phone-device"><div class="activity-icon">▶</div><span><strong>${escapeHtml(run.task)} · ${escapeHtml(run.version)}</strong><small>${escapeHtml(run.device)} · 持续运行中</small></span><button class="text-button" data-action="stop-run" data-id="${escapeHtml(run.id)}">停止</button></div>`).join("")}` : ""}
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
      <div class="phone-header"><div><h2>${phoneTab === "devices" ? "我的设备" : phoneTab === "activity" ? "运行记录" : "工作台"}</h2><p>早上好，陈晓</p></div><div class="phone-user">陈</div></div>
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
  state = expireRemoteRuns(state);
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
    }[currentPage]());
}

function showToast(message) {
  const toast = document.createElement("div");
  toast.className = "toast";
  toast.textContent = message;
  toastRegion.append(toast);
  window.setTimeout(() => toast.remove(), 2800);
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
      <div class="form-hint">此 Demo 仅模拟创建账号，不会发送邀请邮件。</div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">创建用户</button></div></form>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
  modalRoot.querySelector("#user-name").focus();
}

function showRemoteRunModal(taskId) {
  const task = state.tasks.find((item) => item.id === taskId);
  const devices = state.devices.filter((device) => device.userId === "u-1001" && device.status === "online");
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

function showPublishModal(taskId = state.tasks[0]?.id) {
  const task = state.tasks.find((item) => item.id === taskId) ?? state.tasks[0];
  modalRoot.innerHTML = `<div class="modal-root-inner"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
    <div class="modal-header"><div><h2 id="modal-title">发布任务版本</h2><p>${escapeHtml(task.name)} · 当前版本 ${escapeHtml(task.activeVersion)}</p></div><button class="modal-close" data-action="close-modal" aria-label="关闭">×</button></div>
    <form id="publish-form" data-task-id="${escapeHtml(task.id)}"><div class="modal-body">
      <div class="form-field"><label for="task-version">新版本号</label><input id="task-version" name="version" required pattern="v\\d+\\.\\d+\\.\\d+" value="${suggestVersion(task.activeVersion)}" /><span class="form-hint">使用语义化版本格式，例如 v1.5.0。</span></div>
      <div class="form-field"><label for="task-script">Python 脚本文件</label><input id="task-script" name="script" type="file" accept=".py,text/x-python" required /><span class="form-hint">单个 .py 文件，最大 2 MB；仅在浏览器中校验 SHA-256。</span></div>
      <div class="form-field"><label for="release-notes">更新说明</label><input id="release-notes" name="notes" placeholder="简要说明本次更新内容" /></div>
      <div class="form-hint">此 Demo 不会将文件上传或执行；发布结果仅保留文件名、大小和哈希元数据。</div>
    </div><div class="modal-footer"><button type="button" class="secondary-button" data-action="close-modal">取消</button><button class="primary-button" type="submit">确认发布</button></div></form>
  </section></div>`;
  modalRoot.querySelector(".modal-root-inner").addEventListener("click", (event) => {
    if (event.target.classList.contains("modal-root-inner")) closeModal();
  });
  modalRoot.querySelector("#task-version").focus();
}

function showVersionModal(taskId) {
  const task = state.tasks.find((item) => item.id === taskId);
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
  const match = version.match(/^v(\d+)\.(\d+)\.(\d+)$/);
  return match ? `v${match[1]}.${match[2]}.${Number(match[3]) + 1}` : "v1.0.0";
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

document.addEventListener("click", (event) => {
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
    return;
  }

  const actionButton = event.target.closest("[data-action]");
  if (!actionButton) return;
  const { action, id } = actionButton.dataset;
  if (action === "new-user") showUserModal();
  else if (action === "publish-task") showPublishModal();
  else if (action === "switch-version") showVersionModal(id);
  else if (action === "remote-run") showRemoteRunModal(id);
  else if (action === "local-run") showConsentModal(id);
  else if (action === "close-modal") closeModal();
  else if (action === "go-tasks") { currentPage = "tasks"; currentView = "admin"; render(); }
  else if (action === "toggle-user") {
    state = toggleUser(state, id);
    render();
    showToast("用户状态已更新");
  } else if (action === "toggle-device") {
    state = toggleDevice(state, id);
    render();
    showToast("设备状态已更新");
  } else if (action === "approve-request" || action === "reject-request") {
    try {
      const checkbox = workspace.querySelector(`[data-grant-request="${CSS.escape(id)}"]`);
      state = resolveRemoteRun(state, id, {
        approved: action === "approve-request",
        preauthorize: checkbox?.checked ?? false,
      });
      render();
      showToast(action === "approve-request" ? "已确认；任务正在设备上可见运行" : "已拒绝远程运行请求");
    } catch (error) {
      showToast(error.message);
    }
  } else if (action === "finish-run" || action === "stop-run") {
    try {
      state = finishRun(state, id, action === "finish-run" ? "success" : "stopped");
      render();
      showToast(action === "finish-run" ? "演示运行完成，结果已记录" : "任务已停止");
    } catch (error) {
      showToast(error.message);
    }
  } else if (action === "revoke-grant") {
    state = revokeRunAuthorization(state, id);
    render();
    showToast("预授权已撤销");
  } else if (action === "sync-devices") showToast("设备状态已同步（演示）");
  else if (action === "phone-bind-device") showToast("请在真实 App 中扫描绑定码（演示）");
  else if (action === "task-info") showToast("任务版本会经过校验后下发至授权设备（演示）");
  else if (action === "export-users" || action === "export-activity") showToast("演示数据暂不支持下载");
});

document.addEventListener("submit", async (event) => {
  if (event.target.id === "user-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    try {
      state = addUser(state, {
        name: formData.get("name"),
        email: formData.get("email"),
        deviceLimit: formData.get("deviceLimit"),
      });
      closeModal();
      currentPage = "users";
      currentView = "admin";
      userQuery = "";
      userFilter = "all";
      render();
      showToast("用户已创建（演示）");
    } catch (error) {
      showToast(error.message);
    }
  } else if (event.target.id === "remote-run-form") {
    event.preventDefault();
    try {
      state = createRemoteRun(state, {
        taskId: event.target.dataset.taskId,
        deviceId: new FormData(event.target).get("deviceId"),
        requestedBy: "林管理员",
      });
      const request = state.remoteRuns[0];
      currentPhoneDeviceId = request.deviceId;
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
      state = startLocalRun(state, {
        taskId: event.target.dataset.taskId,
        deviceId: currentPhoneDeviceId,
        preauthorize: formData.get("preauthorize") === "on",
      });
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
      if (!globalThis.crypto?.subtle) throw new Error("当前浏览器不支持安全的 SHA-256 校验");
      const digest = await globalThis.crypto.subtle.digest("SHA-256", await file.arrayBuffer());
      const sha256 = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
      state = publishTaskVersion(state, {
        taskId: event.target.dataset.taskId,
        version: formData.get("version"),
        notes: formData.get("notes"),
        scriptFile: { name: file.name, size: file.size, sha256 },
      });
      closeModal();
      currentPage = "tasks";
      currentView = "admin";
      render();
      showToast("任务版本已发布（演示）");
    } catch (error) {
      showToast(error.message);
    }
  } else if (event.target.id === "version-form") {
    event.preventDefault();
    const formData = new FormData(event.target);
    switchVersion(event.target.dataset.taskId, formData.get("version"));
    closeModal();
    render();
    showToast("已切换至选中版本（演示）");
  }
  if (event.target.id === "phone-device-select") {
    currentPhoneDeviceId = event.target.value;
    render();
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

document.querySelector("#mobile-menu").addEventListener("click", () => {
  document.querySelector("#sidebar").classList.toggle("open");
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeModal();
});

render();
