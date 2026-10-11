import test from "node:test";
import assert from "node:assert/strict";
import { toCsv } from "./csv.mjs";

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
