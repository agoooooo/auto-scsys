import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const appSource = await readFile(new URL("./app.mjs", import.meta.url), "utf8");

test("user-session revocation click action stays outside the form-submit listener", () => {
  const clickListener = appSource.indexOf('document.addEventListener("click", async (event) => {');
  const submitListener = appSource.indexOf('document.addEventListener("submit", async (event) => {');
  const revokeAction = appSource.indexOf('action === "revoke-user-sessions"');
  const remoteRunForm = appSource.indexOf('event.target.id === "remote-run-form"');

  assert.ok(clickListener >= 0);
  assert.ok(submitListener > clickListener);
  assert.ok(revokeAction > clickListener && revokeAction < submitListener);
  assert.ok(remoteRunForm > submitListener);
});

test("authenticated Web stop actions send stop signals instead of changing run results", () => {
  assert.match(appSource, /await requestRemoteRunStop\(session, run\.remoteRunId\)/);
  assert.match(appSource, /真实设备运行不能由 Web 模拟完成/);
  assert.match(appSource, /activeRun\.remoteRunId && canRun/);
});

test("Web exposes profile editing, password change, and opt-in remembered sessions", () => {
  assert.match(appSource, /id="profile-form"/);
  assert.match(appSource, /id="password-form"/);
  assert.match(appSource, /name="rememberMe" type="checkbox"/);
  assert.match(appSource, /在此设备保持登录/);
  assert.match(appSource, /await apiRequest\(session, "\/auth\/change-password"/);
});

test("device script publication no longer waits for a device confirmation", () => {
  assert.match(appSource, /授权并发布到设备/);
  assert.match(appSource, /发布后设备自动同步，控制端派发后自动运行/);
  assert.doesNotMatch(appSource, /发布并请求设备确认|设备用户确认后才能同步脚本/);
});
