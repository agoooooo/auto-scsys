import test from "node:test";
import assert from "node:assert/strict";
import {
  apiRequest,
  clearSession,
  createRemoteRunRequest,
  loadSnapshot,
  login,
  readSession,
  saveSession,
  uploadScriptVersion,
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

test("remote-run submission posts the selected task and device", async () => {
  let request;
  const response = await createRemoteRunRequest({
    baseUrl: "http://localhost:8001",
    token: "access-token",
  }, "task-1", "device-2", async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify({ id: "request-1", status: "pending" }), { status: 201 });
  });
  assert.equal(request.url, "http://localhost:8001/remote-runs");
  assert.equal(request.options.method, "POST");
  assert.deepEqual(JSON.parse(request.options.body), {
    task_id: "task-1",
    device_id: "device-2",
  });
  assert.deepEqual(response, { id: "request-1", status: "pending" });
});

test("script publishing uploads directly to the signed object URL and finalizes metadata", async () => {
  const requests = [];
  const session = { baseUrl: "https://api.example.test", token: "access-token" };
  const bytes = new TextEncoder().encode("print('signed')\n");
  const file = {
    name: "hello.py",
    size: bytes.length,
    arrayBuffer: async () => bytes.buffer,
  };
  const result = await uploadScriptVersion(session, "task/1", {
    version: "v1.0.0",
    notes: "release notes",
    file,
  }, async (url, options) => {
    requests.push({ url, options });
    if (url.endsWith("/upload-url")) {
      return new Response(JSON.stringify({
        upload_id: "upload-1",
        upload_url: "https://objects.example.test/presigned",
        headers: { "Content-Type": "text/x-python" },
      }), { status: 201 });
    }
    if (url === "https://objects.example.test/presigned") {
      assert.equal(options.method, "PUT");
      assert.equal(options.headers["Content-Type"], "text/x-python");
      assert.deepEqual(new Uint8Array(options.body), bytes);
      return new Response(null, { status: 200 });
    }
    return new Response(JSON.stringify({ version: "v1.0.0" }), { status: 201 });
  });
  assert.equal(requests[0].url, "https://api.example.test/tasks/task%2F1/versions/upload-url");
  assert.equal(requests[0].options.headers.Authorization.startsWith("Bearer "), true);
  const payload = JSON.parse(requests[0].options.body);
  assert.equal(payload.filename, "hello.py");
  assert.equal(payload.content_size, bytes.length);
  assert.equal(payload.sha256.length, 64);
  assert.equal(requests[2].url, "https://api.example.test/tasks/task%2F1/versions/complete-upload");
  assert.deepEqual(JSON.parse(requests[2].options.body), { upload_id: "upload-1" });
  assert.deepEqual(result, { version: "v1.0.0" });
});

test("script publishing falls back to the authenticated API for local SQLite development", async () => {
  const requests = [];
  const blob = new Blob(["print('local')\n"], { type: "text/x-python" });
  Object.defineProperty(blob, "name", { value: "local.py" });
  const result = await uploadScriptVersion({
    baseUrl: "http://localhost:8001",
    token: "access-token",
  }, "task-1", {
    version: "v1.0.0",
    notes: "",
    file: blob,
  }, async (url, options) => {
    requests.push({ url, options });
    if (url.endsWith("/upload-url")) {
      return new Response(JSON.stringify({ mode: "api" }), { status: 201 });
    }
    return new Response(JSON.stringify({ version: "v1.0.0" }), { status: 201 });
  });
  assert.equal(requests[1].url, "http://localhost:8001/tasks/task-1/versions");
  assert.equal(requests[1].options.body instanceof FormData, true);
  assert.equal(requests[1].options.headers["Content-Type"], undefined);
  assert.deepEqual(result, { version: "v1.0.0" });
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
      active_version: "v1.0.0", assigned_device_count: 2,
      versions: [{ version: "v1.0.0", filename: "task.py", sha256: "hash" }],
    }],
    "/runs": [{
      id: "r1", task_id: "t1", task_name: "OCR", version: "v1.0.0", device_id: "d1",
      device_name: "Pixel", user_name: "Test User", status: "running", started_at: "now",
      result_summary: "",
    }],
    "/run-authorizations": [],
    "/devices/d1/remote-runs": [{
      id: "q1", task_id: "t1", task: "OCR", version: "v1.0.0", device_id: "d1",
      requested_by: "u-admin", requested_by_name: "Admin", status: "running", run_id: "r2", created_at: "now",
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
  assert.equal(snapshot.tasks[0].devices, 2);
  assert.equal(snapshot.runs[0].taskId, "t1");
  assert.equal(snapshot.runs[0].deviceId, "d1");
  assert.equal(snapshot.runs[0].task, "OCR");
  assert.equal(snapshot.remoteRuns[0].requestedBy, "Admin");
  assert.equal(snapshot.remoteRuns[0].runId, "r2");
  assert.equal(calls.includes("/devices/d2/remote-runs"), false);
});

test("admin task device counts come from the task response without per-task publication requests", async () => {
  const calls = [];
  const payloads = {
    "/devices": [{
      id: "owner-phone", user_id: "u-admin", name: "Admin phone", model: "Android",
      status: "offline", app_version: "1.0.0", last_active: "yesterday",
    }, {
      id: "target-1", user_id: "u1", name: "User phone", model: "Android",
      status: "offline", app_version: "1.0.0", last_active: "yesterday",
    }],
    "/tasks": [{
      id: "admin-task", name: "Published task", description: "", category: "OCR",
      status: "published", owner_id: "u-admin", source: "Admin", capabilities: [],
      active_version: "v2.0.0", assigned_device_count: 2, versions: [{ version: "v2.0.0" }],
    }],
    "/runs": [],
    "/run-authorizations": [],
    "/users": [],
  };
  const snapshot = await loadSnapshot({
    baseUrl: "http://localhost:8001",
    token: "admin-token",
    user: { id: "u-admin", name: "Admin", role: "admin" },
  }, async (url) => {
    const path = new URL(url).pathname;
    calls.push(path);
    return new Response(JSON.stringify(payloads[path] ?? []), { status: 200 });
  });

  assert.equal(snapshot.tasks[0].devices, 2);
  assert.equal(calls.some((path) => path.endsWith("/publications")), false);
});
