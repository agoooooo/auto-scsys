import test from "node:test";
import assert from "node:assert/strict";
import { mapUsersForCsv, toCsv, userCsvColumns } from "./csv.mjs";

test("CSV output escapes delimiters, quotes, and line breaks", () => {
  const csv = toCsv([
    { name: 'Chen, "Xiao"', result: "first line\nsecond line", empty: null },
  ], [
    { key: "name", label: "Name" },
    { key: "result", label: "Result" },
    { key: "empty", label: "Empty" },
  ]);
  assert.equal(csv, '\uFEFFName,Result,Empty\r\n"Chen, ""Xiao""","first line\nsecond line",\r\n');
});

test("CSV output contains only requested columns and a header for empty data", () => {
  assert.equal(toCsv([], [{ key: "status", label: "Status" }]), "\uFEFFStatus\r\n");
  assert.equal(toCsv([{ status: "success", secret: "not exported" }], [{ key: "status", label: "Status" }]), "\uFEFFStatus\r\nsuccess\r\n");
});

test("user export includes safe account and device data without private fields", () => {
  const rows = mapUsersForCsv([
    {
      id: "user-1",
      name: 'Chen, "Xiao"',
      email: "chen@example.com",
      role: "user",
      status: "active",
      deviceLimit: 3,
      joined: "2026-10-01",
      password_hash: "must not export",
    },
  ], [
    { userId: "user-1", status: "online" },
    { userId: "user-1", status: "offline" },
    { userId: "user-1", status: "disabled" },
  ]);
  assert.deepEqual(rows, [{
    name: 'Chen, "Xiao"',
    email: "chen@example.com",
    statusLabel: "正常",
    deviceUsage: "2 / 3 台",
    joined: "2026-10-01",
    accountType: "基础用户",
  }]);
  const csv = toCsv(rows, userCsvColumns);
  assert.match(csv, /姓名,邮箱,状态,设备使用量,创建日期,账号类型/);
  assert.match(csv, /"Chen, ""Xiao"""/);
  assert.doesNotMatch(csv, /must not export|password_hash/);
});
