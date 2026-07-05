export function parseIsoTimestamp(value: string, fieldName: string): Date {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    throw new Error(`${fieldName} must be a valid ISO timestamp`);
  }
  return parsed;
}

export function computeStartAt(dueDatetime: string, durationMinutes: number): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  // Todoist-native semantics (decision 2026-07-05): due.datetime IS the start of the
  // committed block. Kept as a function so the semantic boundary stays explicit and
  // validated. durationMinutes is still validated here so a duration-less task can
  // never yield a start time.
  return parseIsoTimestamp(dueDatetime, "dueDatetime").toISOString();
}

export function computeDeadlineAt(dueDatetime: string, durationMinutes: number): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  const due = parseIsoTimestamp(dueDatetime, "dueDatetime");
  return new Date(due.getTime() + durationMinutes * 60_000).toISOString();
}

export function addMinutes(timestamp: string, minutes: number): string {
  if (!Number.isInteger(minutes)) {
    throw new Error("minutes must be an integer");
  }

  const date = parseIsoTimestamp(timestamp, "timestamp");
  return new Date(date.getTime() + minutes * 60_000).toISOString();
}
