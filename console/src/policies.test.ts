import { afterEach, describe, expect, it, vi } from "vitest";
import { clearSession, login, readSession } from "./api";
import { draftKey, matchesTaskSearch, nextPatchVersion } from "./policies";

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

  it("stores an administrator session only after the API confirms the role", async () => {
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

  it("does not persist a non-admin session", async () => {
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

    await expect(login("user@example.test", "secret", false, "https://console.example.test/api"))
      .rejects.toThrow("此账号没有后台管理员权限");
    expect(readSession(window.sessionStorage)).toBeNull();
  });
});
