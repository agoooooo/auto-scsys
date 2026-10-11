function escapeCell(value) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

export function toCsv(rows, columns) {
  const lines = [
    columns.map((column) => escapeCell(column.label)).join(","),
    ...rows.map((row) => columns.map((column) => escapeCell(row[column.key])).join(",")),
  ];
  return `\uFEFF${lines.join("\r\n")}\r\n`;
}
