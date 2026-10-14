import { afterEach, describe, expect, it, vi } from "vitest";
import { clearSession, login, readSession, saveScriptDraft } from "./api";
import { canManageTask, draftKey, matchesTaskSearch, nextPatchVersion } from "./policies";

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe("script version policies", () => {
  it("starts the first script at version 1.0.0", () => {
    expect(nextPatchVersion(null)).toBe("v1.0.0");
  });

  it("increments the patch number for the next immutable version", () => {
    expect(nextPatchVersion("v2.9.12")).toBe("v2.9.13");
    expect(nextPatchVersion("v2.9.12", ["v2.9.13", "v2.9.14"])).toBe("v2.9.15");
    expect(nextPatchVersion("invalid")).toBe("v1.0.0");
  });

  it("isolates local drafts by task id", () => {
    expect(draftKey("task-1")).not.toBe(draftKey("task-2"));
  });

  it("searches task metadata and active script versions case-insensitively", () => {
    const task = {
      name: "Order check",
      description: "Inspect recent orders",
      category: "Automation",
      status: "published" as const,
      active_version: "v1.2.0",
    };
    expect(matchesTaskSearch(task, "RECENT")).toBe(true);
    expect(matchesTaskSearch(task, "v1.2")).toBe(true);
    expect(matchesTaskSearch(task, "missing")).toBe(false);
    expect(matchesTaskSearch(task, "   ")).toBe(true);
  });

  it("limits task editing and release controls to owners and administrators", () => {
    const task = { owner_id: "owner-1" };
    expect(canManageTask(task, { id: "owner-1", role: "user" })).toBe(true);
    expect(canManageTask(task, { id: "granted-user", role: "user" })).toBe(false);
    expect(canManageTask(task, { id: "admin-1", role: "admin" })).toBe(true);
  });

  it("stores a server-confirmed administrator session", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      access_token: "signed-token",
      expires_in: 3600,
      user: {
        id: "admin-1",
        name: "Admin",
        email: "admin@example.test",
        role: "admin",
        status: "active",
        device_limit: 0,
      },
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    window.localStorage.clear();
    window.sessionStorage.clear();

    const session = await login("admin@example.test", "secret", true, "https://console.example.test/api");

    expect(session.user.role).toBe("admin");
    expect(readSession(window.localStorage)?.token).toBe("signed-token");
    expect(readSession(window.sessionStorage)).toBeNull();
    clearSession();
  });

  it("allows ordinary users into their role-specific workspace", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      access_token: "user-token",
      expires_in: 3600,
      user: {
        id: "user-1",
        name: "User",
        email: "user@example.test",
        role: "user",
        status: "active",
        device_limit: 2,
      },
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    window.localStorage.clear();
    window.sessionStorage.clear();

    const session = await login("user@example.test", "secret", false, "https://console.example.test/api");
    expect(session.user.role).toBe("user");
    expect(readSession(window.sessionStorage)?.user.role).toBe("user");
  });

  it("preserves structured draft conflict messages and status codes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      detail: { message: "草稿已在其他会话中更新", current_revision: 3 },
    }), { status: 409, headers: { "Content-Type": "application/json" } })));
    const session = {
      baseUrl: "https://console.example.test/api",
      token: "token",
      accessTokenExpiresAt: Date.now() + 60_000,
      rememberMe: false,
      user: {
        id: "user-1",
        name: "User",
        email: "user@example.test",
        role: "user" as const,
        status: "active" as const,
        device_limit: 2,
      },
    };

    await expect(saveScriptDraft(session, "task-1", {
      source: "print('draft')",
      filename: "main.py",
      notes: "",
      expected_revision: 2,
    })).rejects.toMatchObject({
      message: "草稿已在其他会话中更新",
      status: 409,
    });
  });
});
