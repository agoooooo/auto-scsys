export function filterUsers(users, query, status) {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  return users.filter((user) => {
    const matchesQuery = `${user.name} ${user.email}`.toLocaleLowerCase().includes(normalizedQuery);
    return matchesQuery && (status === "all" || user.status === status);
  });
}

export function paginateItems(items, requestedPage, pageSize = 10) {
  if (!Number.isInteger(pageSize) || pageSize < 1) throw new Error("pageSize must be a positive integer");
  const pageCount = Math.max(1, Math.ceil(items.length / pageSize));
  const page = Math.min(Math.max(1, requestedPage), pageCount);
  const start = (page - 1) * pageSize;
  return {
    page,
    pageCount,
    start,
    items: items.slice(start, start + pageSize),
  };
}

export function filterDevices(devices, status) {
  return status === "all" ? devices : devices.filter((device) => device.status === status);
}
