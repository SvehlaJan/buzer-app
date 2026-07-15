export const BUZER_ACTIVITY_DELIMITER = "--- Buzer activity (managed) ---";
export const TODOIST_DESCRIPTION_MAX_CHARS = 16_384;

export interface ActivityLogEntry {
  timestamp: string;
  timeZone: string;
  message: string;
}

export interface DecodedTaskDescription {
  userDescription: string;
  activityLogEntries: ActivityLogEntry[];
  isManaged: boolean;
  managedSuffix: string | null;
  delimiterSeparator: "" | "\n" | "\r\n";
}

export interface AppendExtensionInput {
  description: string | null;
  timestamp: string;
  timeZone: string | null;
  oldDurationMinutes: number;
  newDurationMinutes: number;
}

export function decodeTaskDescription(description: string | null): DecodedTaskDescription {
  const source = description ?? "";
  const managedSuffix = managedSuffixAtLineBoundary(source);
  if (managedSuffix === null) {
    return unmanaged(source);
  }

  return {
    userDescription: source.slice(0, managedSuffix.delimiterStart - managedSuffix.separator.length),
    activityLogEntries: managedSuffix.entries,
    isManaged: true,
    managedSuffix: managedSuffix.value,
    delimiterSeparator: managedSuffix.separator,
  };
}

export function reconstructTaskDescription(decoded: DecodedTaskDescription): string {
  if (!decoded.isManaged || decoded.managedSuffix === null) {
    return decoded.userDescription;
  }

  return `${decoded.userDescription}${decoded.userDescription === "" ? "" : decoded.delimiterSeparator || "\n"}${decoded.managedSuffix}`;
}

export function appendExtension(input: AppendExtensionInput): string {
  validateExtensionDurations(input.oldDurationMinutes, input.newDurationMinutes);

  const timeZone = supportedTimeZoneOrUtc(input.timeZone);
  const entry = formatActivityEntry({
    timestamp: formatActivityTimestamp(input.timestamp, timeZone),
    timeZone,
    message: `Extended +30 min (${input.oldDurationMinutes} → ${input.newDurationMinutes} min)`,
  });
  const existing = input.description ?? "";
  const decoded = decodeTaskDescription(existing);
  const result = decoded.isManaged
    ? `${existing}${lineEndingFor(existing)}${entry}`
    : appendFreshManagedSuffix(existing, entry);

  if (result.length > TODOIST_DESCRIPTION_MAX_CHARS) {
    throw new Error("activity_log_full");
  }
  return result;
}

function unmanaged(userDescription: string): DecodedTaskDescription {
  return {
    userDescription,
    activityLogEntries: [],
    isManaged: false,
    managedSuffix: null,
    delimiterSeparator: "",
  };
}

function managedSuffixAtLineBoundary(
  source: string,
): { delimiterStart: number; separator: "" | "\n" | "\r\n"; value: string; entries: ActivityLogEntry[] } | null {
  const delimiter = escapeRegExp(BUZER_ACTIVITY_DELIMITER);
  const pattern = new RegExp(`(^|\\r?\\n)${delimiter}(?=\\r?\\n|$)`, "gm");
  let selected: { delimiterStart: number; separator: "" | "\n" | "\r\n"; value: string; entries: ActivityLogEntry[] } | null = null;

  for (const match of source.matchAll(pattern)) {
    const separator = (match[1] ?? "") as "" | "\n" | "\r\n";
    const delimiterStart = match.index + separator.length;
    const value = source.slice(delimiterStart);
    const entries = parseManagedSuffix(value);
    if (entries !== null && entries.length > 0) {
      selected = { delimiterStart, separator, value, entries };
    }
  }

  return selected;
}

function parseManagedSuffix(managedSuffix: string): ActivityLogEntry[] | null {
  const activityLines = managedSuffix.slice(BUZER_ACTIVITY_DELIMITER.length).split(/\r?\n/);
  const entries: ActivityLogEntry[] = [];
  for (const line of activityLines) {
    if (line === "") {
      continue;
    }

    const entry = parseActivityEntry(line);
    if (entry === null) {
      return null;
    }
    entries.push(entry);
  }
  return entries;
}

function parseActivityEntry(line: string): ActivityLogEntry | null {
  const match = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}) ([A-Za-z_+-]+(?:\/[A-Za-z0-9._+-]+)*) — (.+)$/.exec(line);
  if (match === null) {
    return null;
  }

  const timestamp = match[1] ?? "";
  const timeZone = match[2] ?? "";
  const message = match[3] ?? "";
  return isValidLocalTimestamp(timestamp) && isSupportedTimeZone(timeZone) && message.trim() !== ""
    ? { timestamp, timeZone, message }
    : null;
}

function formatActivityEntry(entry: ActivityLogEntry): string {
  return `${entry.timestamp} ${entry.timeZone} — ${entry.message}`;
}

function appendFreshManagedSuffix(userDescription: string, entry: string): string {
  const separator = userDescription === "" || userDescription.endsWith("\n") ? "" : lineEndingFor(userDescription);
  return `${userDescription}${separator}${BUZER_ACTIVITY_DELIMITER}${lineEndingFor(userDescription)}${entry}`;
}

function formatActivityTimestamp(timestamp: string, timeZone: string): string {
  const parsed = parseStrictIsoInstant(timestamp, "timestamp");

  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(parsed);
  return `${requiredDatePart(parts, "year")}-${requiredDatePart(parts, "month")}-${requiredDatePart(parts, "day")} ${requiredDatePart(parts, "hour").padStart(2, "0")}:${requiredDatePart(parts, "minute").padStart(2, "0")}`;
}

function parseStrictIsoInstant(value: string, label: string): Date {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (match === null) {
    throw new Error(`${label} must be a valid ISO timestamp`);
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6] ?? "0");
  const offset = match[7] ?? "";
  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > daysInMonth(year, month) ||
    hour > 23 ||
    minute > 59 ||
    second > 59 ||
    !isValidOffset(offset)
  ) {
    throw new Error(`${label} must be a valid ISO timestamp`);
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    throw new Error(`${label} must be a valid ISO timestamp`);
  }
  return parsed;
}

function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28;
  }
  return [31, 0, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] ?? 0;
}

function isValidOffset(offset: string): boolean {
  if (offset === "Z") {
    return true;
  }
  const match = /^[+-](\d{2}):(\d{2})$/.exec(offset);
  return match !== null && Number(match[1]) <= 23 && Number(match[2]) <= 59;
}

function requiredDatePart(parts: Intl.DateTimeFormatPart[], type: Intl.DateTimeFormatPartTypes): string {
  const value = parts.find((part) => part.type === type)?.value;
  if (value === undefined) {
    throw new Error(`missing ${type} while formatting activity`);
  }
  return value;
}

function supportedTimeZoneOrUtc(timeZone: string | null): string {
  return timeZone !== null && isSupportedTimeZone(timeZone) ? timeZone : "UTC";
}

function isSupportedTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone }).format();
    return true;
  } catch {
    return false;
  }
}

function isValidLocalTimestamp(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})$/.exec(value);
  if (match === null) {
    return false;
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  if (month < 1 || month > 12 || day < 1 || hour > 23 || minute > 59) {
    return false;
  }

  const date = new Date(Date.UTC(year, month - 1, day, hour, minute));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day &&
    date.getUTCHours() === hour &&
    date.getUTCMinutes() === minute
  );
}

function validateExtensionDurations(oldDurationMinutes: number, newDurationMinutes: number): void {
  if (!Number.isInteger(oldDurationMinutes) || oldDurationMinutes <= 0) {
    throw new Error("oldDurationMinutes must be a positive integer");
  }
  if (newDurationMinutes !== oldDurationMinutes + 30) {
    throw new Error("newDurationMinutes must be exactly 30 minutes longer");
  }
}

function lineEndingFor(value: string): "\n" | "\r\n" {
  return value.includes("\r\n") ? "\r\n" : "\n";
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
