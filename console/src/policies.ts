import type { Task } from "./api";

export function nextPatchVersion(
  activeVersion: string | null | undefined,
  existingVersions: string[] = [],
): string {
  const match = activeVersion ? /^v(\d+)\.(\d+)\.(\d+)$/.exec(activeVersion) : null;
  const major = match?.[1] ?? "1";
  const minor = match?.[2] ?? "0";
  const existing = new Set(existingVersions);
  let patch = match ? Number(match[3]) + 1 : 0;
  let candidate = `v${major}.${minor}.${patch}`;
  while (existing.has(candidate)) {
    patch += 1;
    candidate = `v${major}.${minor}.${patch}`;
  }
  return candidate;
}

export function matchesTaskSearch(
  task: Pick<Task, "name" | "description" | "category" | "status" | "active_version">,
  query: string,
): boolean {
  const normalized = query.trim().toLocaleLowerCase();
  if (!normalized) return true;
  return [
    task.name,
    task.description,
    task.category,
    task.status,
    task.active_version,
  ].some((value) => value?.toLocaleLowerCase().includes(normalized));
}

export function draftKey(taskId: string): string {
  return `flowpilot-script-draft:${taskId}`;
}
