import test from "node:test";
import assert from "node:assert/strict";
import { filterTasks } from "./task-list.mjs";
import { paginateItems } from "./user-list.mjs";

const tasks = Array.from({ length: 23 }, (_, index) => ({
  id: `task-${index + 1}`,
  name: `Automation ${index + 1}`,
  description: index === 4 ? "Extract order status" : "Routine workflow",
  category: index % 2 ? "OCR" : "Web",
  source: "Admin",
  activeVersion: `v1.0.${index}`,
  capabilities: ["Screen capture"],
  status: index % 3 === 0 ? "published" : index % 3 === 1 ? "draft" : "paused",
}));

test("task search covers metadata and combines with status filter", () => {
  assert.deepEqual(filterTasks(tasks, "ORDER status", "all").map((task) => task.id), ["task-5"]);
  assert.deepEqual(filterTasks(tasks, "ocr", "draft").map((task) => task.id), [
    "task-2", "task-8", "task-14", "task-20",
  ]);
  assert.deepEqual(filterTasks(tasks, "unmatched", "all"), []);
});

test("filtered task results can be paginated and return to valid pages", () => {
  const filtered = filterTasks(tasks, "", "all");
  const page = paginateItems(filtered, 2, 10);
  assert.deepEqual(page.items.map((task) => task.id), Array.from(
    { length: 10 },
    (_, index) => `task-${index + 11}`,
  ));
  const reduced = paginateItems(filtered.slice(0, 3), page.page, 10);
  assert.equal(reduced.page, 1);
  assert.equal(reduced.items.length, 3);
});
