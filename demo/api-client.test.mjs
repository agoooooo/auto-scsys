import test from "node:test";
import assert from "node:assert/strict";
import {
  apiRequest,
  clearSession,
  loadSnapshot,
  login,
  readSession,
  saveSession,
} from "./api-client.mjs";

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

test("API session storage round-trips and clears the session", () => {
  const storage = memoryStorage();
  const session = { baseUrl: "http://localhost:8001", token: "token", user: { id: "u-1" } };
  saveSession(session, storage);
  assert.deepEqual(readSession(storage), session);
  clearSession(storage);
  assert.equal(readSession(storage), null);
});

test("login rejects non-HTTP service URLs before sending credentials", async () => {
  await assert.rejects(login("javascript:alert(1)", "user@example.com", "password"), /必须使用 HTTP 或 HTTPS/);
});

test("API requests send JSON and surface structured server errors", async () => {
  const session = { baseUrl: "http://localhost:8001/", token: "access-token" };
  let request;
  const fetchStub = async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify({ created: true }), { status: 201 });
  };
  const result = await apiRequest(session, "/devices", {
    method: "POST",
    body: { name: "Pixel" },
  }, fetchStub);
  assert.equal(result.created, true);
  assert.equal(request.url, "http://localhost:8001/devices");
  assert.equal(request.options.headers.Authorization, "Bearer access-token");
  assert.deepEqual(JSON.parse(request.options.body), { name: "Pixel" });

  await assert.rejects(
    apiRequest(session, "/users", {}, async () => new Response(JSON.stringify({ detail: "需要管理员权限" }), { status: 403 })),
    /需要管理员权限/,
  );
});

test("API requests send multipart bodies without setting a JSON content type", async () => {
  const formData = new FormData();
  formData.set("version", "v1.0.0");
  let request;
  await apiRequest({ baseUrl: "http://localhost:8001" }, "/tasks/t1/versions", {
    method: "POST",
    formData,
  }, async (_url, options) => {
    request = options;
    return new Response(JSON.stringify({ ok: true }), { status: 201 });
  });
  assert.equal(request.body, formData);
  assert.equal(request.headers["Content-Type"], undefined);
});

test("snapshot loading maps API resources and requests only online devices", async () => {
  const calls = [];
  const payloads = {
    "/devices": [{
      id: "d1", user_id: "u1", user_name: "Test User", name: "Pixel", model: "Android",
      status: "online", app_version: "1.0.0", last_active: "now",
    }, {
      id: "d2", user_id: "u1", user_name: "Test User", name: "Old phone", model: "Android",
      status: "offline", app_version: "1.0.0", last_active: "yesterday",
    }],
    "/tasks": [{
      id: "t1", name: "OCR", description: "Read screen", category: "OCR", status: "published",
      owner_id: "u1", source: "Owner", capabilities: ["OCR"],
      active_version: "v1.0.0", versions: [{ version: "v1.0.0", filename: "task.py", sha256: "hash" }],
    }],
    "/runs": [{
      id: "r1", task_id: "t1", task_name: "OCR", version: "v1.0.0", device_id: "d1",
      device_name: "Pixel", user_name: "Test User", status: "running", started_at: "now",
      result_summary: "",
    }],
    "/run-authorizations": [],
    "/devices/d1/remote-runs": [{
      id: "q1", task_id: "t1", task: "OCR", version: "v1.0.0", device_id: "d1",
      requested_by: "u-admin", requested_by_name: "Admin", status: "pending", created_at: "now",
      expires_at: 9999999999, source: "Owner", capabilities: ["OCR"],
    }],
  };
  const fetchStub = async (url) => {
    const path = new URL(url).pathname;
    calls.push(path);
    return new Response(JSON.stringify(payloads[path] ?? []), { status: 200 });
  };
  const snapshot = await loadSnapshot({
    baseUrl: "http://localhost:8001",
    token: "token",
    user: { id: "u1", name: "Test User", role: "user" },
  }, fetchStub);
  assert.equal(snapshot.devices[0].userId, "u1");
  assert.equal(snapshot.tasks[0].activeVersion, "v1.0.0");
  assert.equal(snapshot.runs[0].taskId, "t1");
  assert.equal(snapshot.runs[0].deviceId, "d1");
  assert.equal(snapshot.runs[0].task, "OCR");
  assert.equal(snapshot.remoteRuns[0].requestedBy, "Admin");
  assert.equal(calls.includes("/devices/d2/remote-runs"), false);
});
