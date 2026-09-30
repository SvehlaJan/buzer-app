const OFFSET_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i;
const FLOATING_DATETIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/;

export function parseIsoTimestamp(value: string, fieldName: string): Date {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    throw new Error(`${fieldName} must be a valid ISO timestamp`);
  }
  return parsed;
}

/**
 * Turns a Todoist due datetime into an instant. Fixed values end in `Z` or an offset.
 * Floating values (no offset, `due.timezone === null`) are wall-clock times in the owner's
 * timezone (decision 2026-09-29-reminder-reliability). This is the only place that may
 * parse a due datetime.
 */
export function resolveDueInstant(dueDatetime: string, timeZone: string): Date {
  if (OFFSET_SUFFIX.test(dueDatetime)) {
    return parseIsoTimestamp(dueDatetime, "dueDatetime");
  }
  const match = FLOATING_DATETIME.exec(dueDatetime);
  if (match === null) {
    throw new Error("dueDatetime must be a valid ISO timestamp");
  }
  const [, year, month, day, hour, minute, second] = match;
  const wallClockAsUtc = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second ?? "0")
  );
  const firstGuess = wallClockAsUtc - zoneOffsetMs(timeZone, wallClockAsUtc);
  return new Date(wallClockAsUtc - zoneOffsetMs(timeZone, firstGuess));
}

/** Snapshot form of a due datetime: fixed values pass through, floating ones become UTC ISO. */
export function normalizeDueDatetime(dueDatetime: string | null, timeZone: string): string | null {
  if (dueDatetime === null || OFFSET_SUFFIX.test(dueDatetime)) {
    return dueDatetime;
  }
  return resolveDueInstant(dueDatetime, timeZone).toISOString();
}

export function sameDueInstant(left: string | null, right: string | null, timeZone: string): boolean {
  if (left === null || right === null) {
    return left === right;
  }
  return resolveDueInstant(left, timeZone).getTime() === resolveDueInstant(right, timeZone).getTime();
}

export function computeStartAt(dueDatetime: string, durationMinutes: number, timeZone: string): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  // Todoist-native semantics (decision 2026-07-05): due.datetime IS the start of the
  // committed block. Kept as a function so the semantic boundary stays explicit and
  // validated. durationMinutes is still validated here so a duration-less task can
  // never yield a start time.
  return resolveDueInstant(dueDatetime, timeZone).toISOString();
}

export function computeDeadlineAt(dueDatetime: string, durationMinutes: number, timeZone: string): string {
  if (!Number.isInteger(durationMinutes) || durationMinutes <= 0) {
    throw new Error("durationMinutes must be positive");
  }

  const due = resolveDueInstant(dueDatetime, timeZone);
  return new Date(due.getTime() + durationMinutes * 60_000).toISOString();
}

export function addMinutes(timestamp: string, minutes: number): string {
  if (!Number.isInteger(minutes)) {
    throw new Error("minutes must be an integer");
  }

  const date = parseIsoTimestamp(timestamp, "timestamp");
  return new Date(date.getTime() + minutes * 60_000).toISOString();
}

function zoneOffsetMs(timeZone: string, epochMs: number): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).formatToParts(new Date(epochMs));
  const part = (type: Intl.DateTimeFormatPartTypes): number =>
    Number(parts.find((entry) => entry.type === type)?.value);
  const wallClockAsUtc = Date.UTC(
    part("year"),
    part("month") - 1,
    part("day"),
    part("hour"),
    part("minute"),
    part("second")
  );
  return wallClockAsUtc - Math.floor(epochMs / 1000) * 1000;
}
