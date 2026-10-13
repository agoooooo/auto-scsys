import assert from "node:assert/strict";
import test from "node:test";
import { formatRunTimestamp, summarizeRuns } from "./run-metrics.mjs";

test("summarizes current and previous seven calendar days", () => {
  const now = new Date(2026, 9, 13, 12);
  const runs = [
    { id: "current-1", task_id: "t1", started_at: new Date(2026, 9, 13, 9).toISOString(), status: "success" },
    { id: "current-2", taskId: "t1", startedAt: new Date(2026, 9, 12, 9).toISOString(), status: "failure" },
    { id: "running", task_id: "t2", started_at: new Date(2026, 9, 11, 9).toISOString(), status: "running" },
    { id: "previous", task_id: "t1", started_at: new Date(2026, 9, 6, 9).toISOString(), status: "success" },
    { id: "too-old", task_id: "t1", started_at: new Date(2026, 8, 29, 23).toISOString(), status: "success" },
  ];

  const summary = summarizeRuns(runs, now);
  assert.equal(summary.currentTotal, 3);
  assert.equal(summary.previousTotal, 1);
  assert.equal(summary.successRate, 50);
  assert.equal(summary.currentDaily.at(-1).count, 1);
  assert.equal(summary.currentDaily.at(-2).count, 1);
  assert.equal(summary.previousDaily.at(-1).count, 1);
  assert.deepEqual(summary.tasks.t1, {
    weeklyRuns: 2,
    latestRun: new Date(2026, 9, 13, 9).toISOString(),
    successRate: 50,
  });
  assert.equal(summary.tasks.t2.successRate, null);
});

test("empty and invalid run timestamps produce zero-count metrics without a fake success rate", () => {
  const summary = summarizeRuns([
    { task_id: "t1", started_at: "not-a-date", status: "success" },
    { task_id: "t1", started_at: "2026-10-13T08:00:00Z", status: "running" },
  ], new Date(2026, 9, 13, 12));

  assert.equal(summary.currentTotal, 1);
  assert.equal(summary.successRate, null);
  assert.equal(summary.currentDaily.reduce((total, day) => total + day.count, 0), 1);
  assert.equal(summary.tasks.t1.weeklyRuns, 1);
  assert.equal(formatRunTimestamp("尚未运行"), "尚未运行");
});

test("calendar windows remain aligned across daylight-saving transitions", () => {
  const summary = summarizeRuns([
    { task_id: "t1", started_at: new Date(2024, 2, 10, 12).toISOString(), status: "success" },
    { task_id: "t1", started_at: new Date(2024, 2, 5, 12).toISOString(), status: "failure" },
    { task_id: "t1", started_at: new Date(2024, 1, 27, 12).toISOString(), status: "success" },
  ], new Date(2024, 2, 11, 12));

  assert.equal(summary.currentTotal, 2);
  assert.equal(summary.previousTotal, 1);
  assert.equal(summary.currentDaily.find((day) => day.date === "2024-03-10").count, 1);
});

test("rejects an invalid reference date", () => {
  assert.throws(() => summarizeRuns([], new Date("invalid")), {
    name: "TypeError",
    message: "now must be a valid date",
  });
});
