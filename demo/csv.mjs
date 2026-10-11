function escapeCell(value) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

export const userCsvColumns = [
  { key: "name", label: "姓名" },
  { key: "email", label: "邮箱" },
  { key: "statusLabel", label: "状态" },
  { key: "deviceUsage", label: "设备使用量" },
  { key: "joined", label: "创建日期" },
  { key: "accountType", label: "账号类型" },
];

export function mapUsersForCsv(users, devices) {
  return users.map((user) => ({
    name: user.name,
    email: user.email,
    statusLabel: user.status === "active" ? "正常" : "已停用",
    deviceUsage: `${devices.filter((device) => device.userId === user.id && device.status !== "disabled").length} / ${user.deviceLimit} 台`,
    joined: user.joined,
    accountType: user.role === "admin" ? "管理员" : "基础用户",
  }));
}

export function toCsv(rows, columns) {
  const lines = [
    columns.map((column) => escapeCell(column.label)).join(","),
    ...rows.map((row) => columns.map((column) => escapeCell(row[column.key])).join(",")),
  ];
  return `\uFEFF${lines.join("\r\n")}\r\n`;
}
