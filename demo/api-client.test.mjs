import test from "node:test";
import assert from "node:assert/strict";
import {
  apiRequest,
  clearSession,
  createRemoteRunRequest,
  defaultApiBaseUrl,
  loadSnapshot,
  login,
  passwordRecoveryStatus,
  readSession,
  revokeSession,
  requestPasswordResetCode,
  resetPasswordWithCode,
  requestRemoteRunStop,
  saveSession,
  uploadScriptVersion,
  waitForRemoteRun,
} from "./api-client.mjs";

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

test("API URL defaults to same-origin HTTPS proxy in production and local API in development", () => {
  assert.equal(
    defaultApiBaseUrl({ protocol: "https:", origin: "https://console.example.test", hostname: "console.example.test" }),
    "https://console.example.test/api",
  );
  assert.equal(
    defaultApiBaseUrl({ protocol: "http:", origin: "http://localhost:8000", hostname: "localhost" }),
    "http://localhost:8001",
  );
  assert.equal(defaultApiBaseUrl({ protocol: "http:", hostname: "" }), "http://127.0.0.1:8001");
});

test("API session storage round-trips and clears the session", () => {
  const storage = memoryStorage();
  const session = { baseUrl: "http://localhost:8001", token: "token", user: { id: "u-1" }, rememberMe: false };
  saveSession(session, storage);
  assert.deepEqual(readSession(storage), session);
  clearSession(storage);
  assert.equal(readSession(storage), null);
});

test("remembered Web sessions use persistent storage while default sessions stay tab-scoped", () => {
  const local = memoryStorage();
  const tab = memoryStorage();
  const previousLocal = globalThis.localStorage;
  const previousTab = globalThis.sessionStorage;
  globalThis.localStorage = local;
  globalThis.sessionStorage = tab;
  try {
    const persistent = { baseUrl: "https://api.example.test", token: "remembered", rememberMe: true };
    saveSession(persistent);
    assert.equal(JSON.parse(local.getItem("flowpilot-api-session")).token, "remembered");
    assert.equal(tab.getItem("flowpilot-api-session"), null);
    assert.equal(readSession().rememberMe, true);

    const temporary = { baseUrl: "https://api.example.test", token: "temporary", rememberMe: false };
    saveSession(temporary);
    assert.equal(local.getItem("flowpilot-api-session"), null);
    assert.equal(JSON.parse(tab.getItem("flowpilot-api-session")).token, "temporary");
    assert.equal(readSession().rememberMe, false);
    clearSession();
    assert.equal(tab.getItem("flowpilot-api-session"), null);
  } finally {
    if (previousLocal === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = previousLocal;
    if (previousTab === undefined) delete globalThis.sessionStorage;
    else globalThis.sessionStorage = previousTab;
  }
});

test("API request refreshes an expired access token once and retries the request", async () => {
  const calls = [];
  const session = {
    baseUrl: "https://api.example.test",
    token: "expired-token",
    rememberMe: false,
  };
  const result = await apiRequest(session, "/devices", {}, async (url, options) => {
    calls.push({ path: new URL(url).pathname, authorization: options.headers.Authorization });
    if (calls.length === 1) return new Response(JSON.stringify({ detail: "token expired" }), { status: 401 });
    if (calls.length === 2) {
      return new Response(JSON.stringify({ access_token: "fresh-token", expires_in: 3600 }), { status: 200 });
    }
    return new Response(JSON.stringify([{ id: "device-1" }]), { status: 200 });
  });
  assert.deepEqual(result, [{ id: "device-1" }]);
  assert.deepEqual(calls.map((call) => call.path), ["/devices", "/auth/refresh", "/devices"]);
  assert.equal(calls[2].authorization, "Bearer fresh-token");
  assert.equal(session.token, "fresh-token");
  assert.ok(session.accessTokenExpiresAt > Date.now());
});

test("login rejects non-HTTP service URLs before sending credentials", async () => {
  await assert.rejects(login("javascript:alert(1)", "user@example.com", "password"), /必须使用 HTTP 或 HTTPS/);
});

test("password recovery checks availability and submits reset requests without a session", async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ path: new URL(url).pathname, options });
    return new Response(JSON.stringify({ enabled: true, message: "如果该邮箱对应可用账号，已发送验证码。" }), { status: 200 });
  };
  assert.deepEqual(
    await passwordRecoveryStatus("https://api.example.test", fetchImpl),
    { enabled: true, message: "如果该邮箱对应可用账号，已发送验证码。" },
  );
  await requestPasswordResetCode("https://api.example.test", "user@example.test", fetchImpl);
  await resetPasswordWithCode(
    "https://api.example.test",
    "user@example.test",
    "123456",
    "replacement-password",
    fetchImpl,
  );
  assert.deepEqual(calls.map(({ path }) => path), [
    "/auth/recovery-status",
    "/auth/forgot-password",
    "/auth/reset-password",
  ]);
  assert.deepEqual(JSON.parse(calls[1].options.body), { email: "user@example.test" });
  assert.deepEqual(JSON.parse(calls[2].options.body), {
    email: "user@example.test",
    code: "123456",
    new_password: "replacement-password",
  });
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

test("logout revokes the active server session", async () => {
  let request;
  await revokeSession(
    { baseUrl: "https://api.example.test", token: "active-session-token" },
    async (url, options) => {
      request = { url, options };
      return new Response(null, { status: 204 });
    },
  );
  assert.equal(request.url, "https://api.example.test/auth/logout");
  assert.equal(request.options.method, "POST");
  assert.match(request.options.headers.Authorization, /^Bearer /);
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
  const requests = [];
  const response = await createRemoteRunRequest({
    baseUrl: "http://localhost:8001",
    token: "access-token",
    user: { id: "user-1" },
  }, "task-1", "device-2", async (url, options) => {
    requests.push({ url, options });
    if (new URL(url).pathname === "/devices") {
      return new Response(JSON.stringify([
        { id: "device-2", user_id: "user-1", status: "online" },
      ]), { status: 200 });
    }
    return new Response(JSON.stringify({ id: "request-1", status: "pending" }), { status: 201 });
  }, "web-dispatch-001");
  assert.equal(requests.length, 2);
  assert.equal(requests[0].url, "http://localhost:8001/devices");
  assert.equal(requests[1].url, "http://localhost:8001/remote-runs");
  assert.equal(requests[1].options.method, "POST");
  assert.equal(requests[1].options.headers["Idempotency-Key"], "web-dispatch-001");
  assert.deepEqual(JSON.parse(requests[1].options.body), {
    task_id: "task-1",
    device_id: "device-2",
  });
  assert.deepEqual(response, { id: "request-1", status: "pending" });
});

test("remote-run submission refuses an offline or foreign device before creating a request", async () => {
  const calls = [];
  await assert.rejects(
    createRemoteRunRequest(
      { baseUrl: "http://localhost:8001", token: "access-token", user: { id: "user-1" } },
      "task-1",
      "stale-device",
      async (url) => {
        const path = new URL(url).pathname;
        calls.push(path);
        return new Response(JSON.stringify([
          { id: "stale-device", user_id: "user-1", status: "offline" },
        ]), { status: 200 });
      },
    ),
    /已离线，未发送请求/,
  );
  assert.deepEqual(calls, ["/devices"]);
});

test("remote-run stop sends a request-scoped idempotent stop command", async () => {
  const calls = [];
  const result = await requestRemoteRunStop(
    { baseUrl: "http://localhost:8001", token: "access-token" },
    "request/1",
    async (url, options) => {
      calls.push({ url: new URL(url).pathname, options });
      return new Response(JSON.stringify({ status: "running", stop_requested: true }), { status: 200 });
    },
  );
  assert.equal(calls[0].url, "/remote-runs/request%2F1/stop");
  assert.equal(calls[0].options.method, "POST");
  assert.ok(calls[0].options.headers.Authorization.startsWith("Bearer "));
  assert.equal(result.stop_requested, true);
});

test("remote-run monitoring polls until completion and returns the safe result", async () => {
  const statuses = [
    { id: "request-1", status: "running", result_summary: "" },
    { id: "request-1", status: "failure", result_summary: "设备未安装抖音或抖音极速版" },
  ];
  const paths = [];
  const observed = [];
  const result = await waitForRemoteRun(
    { baseUrl: "http://localhost:8001", token: "access-token" },
    "request-1",
    {
      pollIntervalMs: 1,
      sleep: async () => {},
      onStatus: (status) => observed.push(status.status),
      fetchImpl: async (url) => {
        paths.push(new URL(url).pathname);
        return new Response(JSON.stringify(statuses.shift()), { status: 200 });
      },
    },
  );
  assert.deepEqual(paths, ["/remote-runs/request-1", "/remote-runs/request-1"]);
  assert.deepEqual(observed, ["running", "failure"]);
  assert.equal(result.result_summary, "设备未安装抖音或抖音极速版");
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
      id: "r2", task_id: "t1", task_name: "OCR", version: "v1.0.0", device_id: "d1", remote_run_id: "q1",
      device_name: "Pixel", user_name: "Test User", status: "running", started_at: "now",
      result_summary: "",
    }],
    "/run-authorizations": [],
    "/devices/d1/remote-runs": [{
      id: "q1", task_id: "t1", task: "OCR", version: "v1.0.0", device_id: "d1",
      requested_by: "u-admin", requested_by_name: "Admin", status: "running", run_id: "r2", stop_requested: true, created_at: "now",
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
  assert.equal(snapshot.runs[0].remoteRunId, "q1");
  assert.equal(snapshot.runs[0].stopRequested, true);
  assert.equal(snapshot.remoteRuns[0].requestedBy, "Admin");
  assert.equal(snapshot.remoteRuns[0].runId, "r2");
  assert.equal(snapshot.remoteRuns[0].stopRequested, true);
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
