export function filterUsers(users, query, status) {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  return users.filter((user) => {
    const matchesQuery = `${user.name} ${user.email}`.toLocaleLowerCase().includes(normalizedQuery);
    return matchesQuery && (status === "all" || user.status === status);
  });
}

export function paginateUsers(users, requestedPage, pageSize = 10) {
  const pageCount = Math.max(1, Math.ceil(users.length / pageSize));
  const page = Math.min(Math.max(1, requestedPage), pageCount);
  const start = (page - 1) * pageSize;
  return {
    page,
    pageCount,
    start,
    items: users.slice(start, start + pageSize),
  };
}
