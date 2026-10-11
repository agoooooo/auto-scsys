import test from "node:test";
import assert from "node:assert/strict";
import { filterUsers, paginateUsers } from "./user-list.mjs";

const users = Array.from({ length: 23 }, (_, index) => ({
  id: `user-${index + 1}`,
  name: `User ${index + 1}`,
  email: `user${index + 1}@example.com`,
  status: index % 2 ? "disabled" : "active",
}));

test("user filtering matches name or email and status together", () => {
  assert.deepEqual(
    filterUsers(users, " USER2@EXAMPLE ", "disabled").map((user) => user.id),
    ["user-2"],
  );
  assert.deepEqual(filterUsers(users, "not-found", "all"), []);
});

test("user pagination returns the requested slice and clamps invalid pages", () => {
  const filtered = filterUsers(users, "", "all");
  const secondPage = paginateUsers(filtered, 2, 10);
  assert.equal(secondPage.page, 2);
  assert.equal(secondPage.pageCount, 3);
  assert.equal(secondPage.start, 10);
  assert.deepEqual(secondPage.items.map((user) => user.id), Array.from(
    { length: 10 },
    (_, index) => `user-${index + 11}`,
  ));
  const lastPage = paginateUsers(filtered, 99, 10);
  assert.equal(lastPage.page, 3);
  assert.deepEqual(lastPage.items.map((user) => user.id), ["user-21", "user-22", "user-23"]);
  assert.deepEqual(paginateUsers([], 3, 10), { page: 1, pageCount: 1, start: 0, items: [] });
});
