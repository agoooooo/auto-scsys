import test from "node:test";
import assert from "node:assert/strict";
import {
  addUser,
  authorizationCoversVersion,
  createRemoteRun,
  expireRemoteRuns,
  finishRun,
  initialDemoState,
  publishTaskVersion,
  resolveRemoteRun,
  requiresRemoteSystemAuthorization,
  remoteRunFollowUpView,
  revokeRunAuthorization,
  setUserDeviceLimit,
  setTaskPaused,
  startLocalRun,
  toggleDevice,
  toggleUser,
} from "./state.mjs";

test("adding a user creates an active account with normalized email", () => {
  const next = addUser(structuredClone(initialDemoState), {
    name: "  林小满 ",
    email: "  LIN@example.com ",
    deviceLimit: 4,
  });
  assert.equal(next.users[0].name, "林小满");
  assert.equal(next.users[0].email, "lin@example.com");
  assert.equal(next.users[0].deviceLimit, 4);
  assert.equal(initialDemoState.users.length, 3);
});

test("adding a duplicate email is rejected", () => {
  assert.throws(() => addUser(initialDemoState, {
    name: "重复用户",
    email: "CHEN.XIAO@example.com",
  }), /该邮箱已存在/);
});

test("user and device status actions update only the selected record", () => {
  const disabledUser = toggleUser(initialDemoState, "u-1001");
  assert.equal(disabledUser.users[0].status, "disabled");
  assert.equal(disabledUser.users[1].status, "active");

  const disabledDevice = toggleDevice(initialDemoState, "d-001");
  assert.equal(disabledDevice.devices[0].status, "disabled");
  assert.equal(disabledDevice.devices[1].status, "online");
  assert.equal(toggleDevice(disabledDevice, "d-001").devices[0].status, "offline");
});

test("updating a user's device limit validates active devices and preserves other users", () => {
  const updated = setUserDeviceLimit(initialDemoState, "u-1001", 2);
  assert.equal(updated.users[0].deviceLimit, 2);
  assert.equal(updated.users[1].deviceLimit, 3);
  assert.throws(() => setUserDeviceLimit(initialDemoState, "u-1001", 1), /设备上限必须/);
  assert.throws(() => setUserDeviceLimit(initialDemoState, "u-1001", 51), /50 台之间/);
  assert.throws(() => setUserDeviceLimit(initialDemoState, "missing", 3), /用户不存在/);
});

test("publishing a valid task version activates it and records the release", () => {
  const next = publishTaskVersion(initialDemoState, {
    taskId: "t-001",
    version: "v1.5.0",
    notes: "优化识别规则",
  });
  assert.equal(next.tasks[0].activeVersion, "v1.5.0");
  assert.equal(next.tasks[0].versions[0], "v1.5.0");
  assert.match(next.activity[0].detail, /优化识别规则/);
  assert.throws(() => publishTaskVersion(next, {
    taskId: "t-001",
    version: "latest",
  }), /版本号格式/);
});

test("pausing a task expires pending requests and revokes authorizations", () => {
  const pending = createRemoteRun(initialDemoState, { taskId: "t-001", deviceId: "d-001" });
  const authorized = {
    ...pending,
    runAuthorizations: [{
      id: "grant-1",
      taskId: "t-001",
      deviceId: "d-001",
      version: "v1.4.0",
      expiresAt: Date.now() + 1000,
    }],
  };
  const paused = setTaskPaused(authorized, "t-001", true);
  assert.equal(paused.tasks[0].status, "paused");
  assert.equal(paused.remoteRuns[0].status, "expired");
  assert.equal(paused.runAuthorizations.length, 0);
  assert.throws(() => createRemoteRun(paused, { taskId: "t-001", deviceId: "d-001" }), /不可运行/);

  const resumed = setTaskPaused(paused, "t-001", false);
  assert.equal(resumed.tasks[0].status, "published");
  assert.equal(resumed.remoteRuns[0].status, "expired");
});

test("publishing a Python script records its filename, size, and SHA-256 metadata", () => {
  const published = publishTaskVersion(initialDemoState, {
    taskId: "t-001",
    version: "v1.5.0",
    scriptFile: { name: "orders.py", size: 128, sha256: "a".repeat(64) },
  });
  assert.deepEqual(published.tasks[0].scriptFile, {
    name: "orders.py",
    size: 128,
    sha256: "a".repeat(64),
  });
  assert.throws(() => publishTaskVersion(initialDemoState, {
    taskId: "t-001",
    version: "v1.5.0",
    scriptFile: { name: "script.txt", size: 128, sha256: "a".repeat(64) },
  }), /Python 文件/);
  assert.equal(
    publishTaskVersion(setTaskPaused(initialDemoState, "t-001", true), {
      taskId: "t-001",
      version: "v1.5.0",
    }).tasks[0].status,
    "paused",
  );
});

test("remote runs wait for device confirmation and record completion or refusal", () => {
  const pending = createRemoteRun(initialDemoState, { taskId: "t-001", deviceId: "d-001" });
  assert.equal(pending.remoteRuns[0].status, "pending");
  assert.equal(pending.runs.length, 0);

  const started = resolveRemoteRun(pending, pending.remoteRuns[0].id, { approved: true });
  assert.equal(started.remoteRuns[0].status, "running");
  assert.equal(started.runs[0].status, "running");
  const finished = finishRun(started, started.runs[0].id);
  assert.equal(finished.runs[0].status, "success");
  assert.match(finished.activity[0].detail, /OCR 识别到/);

  const refused = createRemoteRun(initialDemoState, { taskId: "t-001", deviceId: "d-001" });
  const rejected = resolveRemoteRun(refused, refused.remoteRuns[0].id, { approved: false });
  assert.equal(rejected.remoteRuns[0].status, "rejected");
  assert.equal(rejected.runs.length, 0);
});

test("remote-run follow-up does not show admins a phone preview for another user's device", () => {
  assert.equal(remoteRunFollowUpView("admin"), "admin");
  assert.equal(remoteRunFollowUpView("user"), "mobile");
  assert.equal(remoteRunFollowUpView(undefined), "mobile");
});

test("preauthorization covers the reviewed and newer versions on one device and can be revoked", () => {
  const authorized = startLocalRun(initialDemoState, {
    taskId: "t-001",
    deviceId: "d-001",
    preauthorize: true,
  });
  assert.equal(authorized.runAuthorizations[0].version, "v1.4.0");
  assert.equal(authorized.runAuthorizations[0].allVersions, true);

  const sameDevice = createRemoteRun(authorized, { taskId: "t-001", deviceId: "d-001" });
  assert.equal(sameDevice.remoteRuns[0].status, "running");
  const otherDevice = createRemoteRun(authorized, { taskId: "t-001", deviceId: "d-002" });
  assert.equal(otherDevice.remoteRuns[0].status, "pending");

  const newerVersion = publishTaskVersion(authorized, {
    taskId: "t-001",
    version: "v1.5.0",
  });
  const newRequest = createRemoteRun(newerVersion, { taskId: "t-001", deviceId: "d-001" });
  assert.equal(newRequest.remoteRuns[0].version, "v1.5.0");
  assert.equal(newRequest.remoteRuns[0].status, "running");

  const olderVersion = {
    ...authorized,
    tasks: authorized.tasks.map((task) => task.id === "t-001" ? { ...task, activeVersion: "v1.3.0" } : task),
  };
  const olderRequest = createRemoteRun(olderVersion, { taskId: "t-001", deviceId: "d-001" });
  assert.equal(olderRequest.remoteRuns[0].status, "pending");
  assert.equal(authorizationCoversVersion(authorized.runAuthorizations[0], "v1.10.0"), true);
  assert.equal(authorizationCoversVersion(authorized.runAuthorizations[0], "v1.3.0"), false);
  assert.equal(requiresRemoteSystemAuthorization(["OCR 文字识别"]), true);
  assert.equal(requiresRemoteSystemAuthorization(["无障碍操作"]), false);

  const revoked = revokeRunAuthorization(authorized, authorized.runAuthorizations[0].id);
  const afterRevoke = createRemoteRun(revoked, { taskId: "t-001", deviceId: "d-001" });
  assert.equal(afterRevoke.remoteRuns[0].status, "pending");
});

test("unavailable devices and non-running tasks cannot be started or stopped", () => {
  assert.throws(() => createRemoteRun(initialDemoState, { taskId: "t-001", deviceId: "d-003" }), /在线设备/);
  assert.throws(() => createRemoteRun(initialDemoState, { taskId: "t-002", deviceId: "d-001" }), /不可运行/);
  const started = startLocalRun(initialDemoState, { taskId: "t-001", deviceId: "d-001" });
  const stopped = finishRun(started, started.runs[0].id, "stopped");
  assert.equal(stopped.runs[0].status, "stopped");
  assert.throws(() => finishRun(stopped, stopped.runs[0].id), /未在运行/);
});

test("expired remote requests are marked and cannot be approved", () => {
  const pending = createRemoteRun(initialDemoState, { taskId: "t-001", deviceId: "d-001" });
  const expiredState = {
    ...pending,
    remoteRuns: pending.remoteRuns.map((request) => ({ ...request, expiresAt: 0 })),
  };
  const expired = expireRemoteRuns(expiredState, 1);
  assert.equal(expired.remoteRuns[0].status, "expired");
  assert.throws(() => resolveRemoteRun(expiredState, expired.remoteRuns[0].id, { approved: true }), /已过期/);
});
