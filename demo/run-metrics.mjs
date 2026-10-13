const DAY_MILLIS = 24 * 60 * 60 * 1000;
const DAY_COUNT = 7;

function runStartedAt(run) {
  return run.started_at ?? run.startedAt ?? run.time;
}

function dayStart(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  date.setHours(0, 0, 0, 0);
  return date;
}

function shiftDay(date, amount) {
  const shifted = new Date(date);
  shifted.setDate(shifted.getDate() + amount);
  return shifted;
}

function calendarOffset(date, start) {
  return (
    Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) -
    Date.UTC(start.getFullYear(), start.getMonth(), start.getDate())
  ) / DAY_MILLIS;
}

function dateKey(date) {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, "0"),
    String(date.getDate()).padStart(2, "0"),
  ].join("-");
}

function successRate(runs) {
  const completed = runs.filter((run) => run.status === "success" || run.status === "failure");
  return completed.length
    ? (completed.filter((run) => run.status === "success").length / completed.length) * 100
    : null;
}

export function summarizeRuns(runs, now = new Date()) {
  const today = dayStart(now);
  if (!today) throw new TypeError("now must be a valid date");

  const currentStart = shiftDay(today, -(DAY_COUNT - 1));
  const previousStart = shiftDay(today, -(DAY_COUNT * 2 - 1));
  const currentByDate = new Map();
  const previousByDate = new Map();
  const currentRuns = [];
  const previousRuns = [];
  const taskStats = {};

  for (const run of runs) {
    const timestamp = new Date(runStartedAt(run));
    if (Number.isNaN(timestamp.getTime())) continue;
    const date = dayStart(timestamp);
    const offset = calendarOffset(date, currentStart);
    const taskId = run.task_id ?? run.taskId;
    if (taskId) {
      const task = taskStats[taskId] ??= { weeklyRuns: 0, completed: [], latestRun: null, latestTimestamp: -Infinity };
      if (timestamp.getTime() > task.latestTimestamp) {
        task.latestTimestamp = timestamp.getTime();
        task.latestRun = runStartedAt(run);
      }
      if (offset >= 0 && offset < DAY_COUNT) {
        task.weeklyRuns++;
        task.completed.push(run);
      }
    }
    if (offset >= 0 && offset < DAY_COUNT) {
      currentRuns.push(run);
      const key = dateKey(date);
      currentByDate.set(key, (currentByDate.get(key) ?? 0) + 1);
    } else {
      const previousOffset = calendarOffset(date, previousStart);
      if (previousOffset >= 0 && previousOffset < DAY_COUNT) {
        previousRuns.push(run);
        const key = dateKey(date);
        previousByDate.set(key, (previousByDate.get(key) ?? 0) + 1);
      }
    }
  }

  const dates = Array.from({ length: DAY_COUNT }, (_, index) => shiftDay(currentStart, index));
  const currentDaily = dates.map((date) => ({
    date: dateKey(date),
    label: date.toLocaleDateString("zh-CN", { weekday: "short" }),
    count: currentByDate.get(dateKey(date)) ?? 0,
  }));
  const previousDaily = dates.map((date, index) => {
    const previousDate = shiftDay(previousStart, index);
    return {
      date: dateKey(previousDate),
      label: date.toLocaleDateString("zh-CN", { weekday: "short" }),
      count: previousByDate.get(dateKey(previousDate)) ?? 0,
    };
  });

  for (const task of Object.values(taskStats)) {
    task.successRate = successRate(task.completed);
    delete task.completed;
    delete task.latestTimestamp;
  }

  return {
    currentTotal: currentRuns.length,
    previousTotal: previousRuns.length,
    successRate: successRate(currentRuns),
    currentDaily,
    previousDaily,
    tasks: taskStats,
  };
}

export function formatRunTimestamp(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value ?? "—") : date.toLocaleString("zh-CN");
}
