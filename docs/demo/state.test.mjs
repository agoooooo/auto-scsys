import test from "node:test";
import assert from "node:assert/strict";
import {
  addUser,
  initialDemoState,
  publishTaskVersion,
  runTask,
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

test("running a published task records a simulated result", () => {
  const next = runTask(initialDemoState, "t-001");
  assert.equal(next.tasks[0].lastRun, "刚刚");
  assert.match(next.activity[0].detail, /演示运行成功/);
  assert.throws(() => runTask(initialDemoState, "t-002"), /当前任务不可运行/);
});
