export function filterTasks(tasks, query, status) {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  return tasks.filter((task) => {
    const searchableText = [
      task.name,
      task.description,
      task.category,
      task.source,
      task.activeVersion,
      ...(task.capabilities ?? []),
    ].join(" ").toLocaleLowerCase();
    return searchableText.includes(normalizedQuery) && (status === "all" || task.status === status);
  });
}
