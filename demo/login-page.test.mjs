import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("login page does not prefill or disclose demo passwords", async () => {
  const source = await readFile(new URL("./app.mjs", import.meta.url), "utf8");
  const loginPage = source.slice(source.indexOf("function renderLogin("), source.indexOf("function phoneUserId("));

  assert.doesNotMatch(loginPage, /value=["'](?:admin-demo|user-demo)["']/);
  assert.doesNotMatch(loginPage, /admin@example\.com\s*\/\s*admin-demo/);
  assert.doesNotMatch(loginPage, /chen\.xiao@example\.com\s*\/\s*user-demo/);
  assert.match(loginPage, /autocomplete="current-password"/);
  assert.match(loginPage, /本地演示账号仅在开发模式启用/);
  assert.match(loginPage, /recovery-toggle/);
  assert.match(loginPage, /passwordRecoveryStatus/);
  assert.match(loginPage, /id="recovery-code-form"/);
  assert.match(loginPage, /id="recovery-reset-form"/);
  assert.match(source, /resetPasswordWithCode/);
});
