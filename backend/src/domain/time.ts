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

  const due = parseIsoTimestamp(dueDatetime, "dueDatetime");
  return new Date(due.getTime() - durationMinutes * 60_000).toISOString();
}

export function addMinutes(timestamp: string, minutes: number): string {
  if (!Number.isInteger(minutes)) {
    throw new Error("minutes must be an integer");
  }

  const date = parseIsoTimestamp(timestamp, "timestamp");
  return new Date(date.getTime() + minutes * 60_000).toISOString();
}
