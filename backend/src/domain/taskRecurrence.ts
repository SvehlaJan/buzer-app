export type RecurrencePreset = "never" | "daily" | "weekdays" | "weekly" | "monthly" | "yearly";
export type RecurrenceKind = "none" | "supported" | "custom";

export interface TodoistRecurrence {
  string: string;
  lang: "en";
  timezone: string;
}

export interface TranslateRecurrencePresetInput {
  preset: RecurrencePreset;
  selectedStart: string;
  timeZone: string;
}

export interface DescribeRecurrenceInput {
  isRecurring: boolean;
  rule: string | null;
  language: string | null;
  timeZone: string | null;
}

export interface RecurrenceDescription {
  kind: RecurrenceKind;
  preset: RecurrencePreset | null;
}

const weekdays = new Set([
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
]);

const monthDayLimits: Record<string, number> = {
  January: 31,
  February: 29,
  March: 31,
  April: 30,
  May: 31,
  June: 30,
  July: 31,
  August: 31,
  September: 30,
  October: 31,
  November: 30,
  December: 31,
};

const timePattern = "(?:[01]\\d|2[0-3]):[0-5]\\d";

export function translateRecurrencePreset(input: TranslateRecurrencePresetInput): TodoistRecurrence | null {
  if (input.preset === "never") {
    return null;
  }

  const local = localStartParts(input.selectedStart, input.timeZone);
  const time = `${local.hour}:${local.minute}`;
  const rule = recurrenceRule(input.preset, local, time);

  return {
    string: rule,
    lang: "en",
    timezone: input.timeZone,
  };
}

export function describeRecurrence(input: DescribeRecurrenceInput): RecurrenceDescription {
  if (!input.isRecurring) {
    return { kind: "none", preset: "never" };
  }

  if (input.language !== "en" || input.rule === null || input.timeZone === null || !isIanaTimeZone(input.timeZone)) {
    return { kind: "custom", preset: null };
  }

  const preset = presetForExactRule(input.rule);
  return preset === null ? { kind: "custom", preset: null } : { kind: "supported", preset };
}

export function formatOrdinalDay(day: number): string {
  if (!Number.isInteger(day) || day < 1 || day > 31) {
    throw new Error("day must be an integer between 1 and 31");
  }

  const lastTwoDigits = day % 100;
  if (lastTwoDigits >= 11 && lastTwoDigits <= 13) {
    return `${day}th`;
  }

  switch (day % 10) {
    case 1:
      return `${day}st`;
    case 2:
      return `${day}nd`;
    case 3:
      return `${day}rd`;
    default:
      return `${day}th`;
  }
}

function recurrenceRule(
  preset: Exclude<RecurrencePreset, "never">,
  local: LocalStartParts,
  time: string,
): string {
  switch (preset) {
    case "daily":
      return `every day at ${time}`;
    case "weekdays":
      return `every weekday at ${time}`;
    case "weekly":
      return `every ${local.weekday} at ${time}`;
    case "monthly":
      return `every month on the ${formatOrdinalDay(local.day)} at ${time}`;
    case "yearly":
      return `every year on ${local.month} ${formatOrdinalDay(local.day)} at ${time}`;
  }
}

function presetForExactRule(rule: string): Exclude<RecurrencePreset, "never"> | null {
  if (new RegExp(`^every day at ${timePattern}$`).test(rule)) {
    return "daily";
  }
  if (new RegExp(`^every weekday at ${timePattern}$`).test(rule)) {
    return "weekdays";
  }

  const weekly = new RegExp(`^every ([A-Za-z]+) at ${timePattern}$`).exec(rule);
  if (weekly !== null && weekdays.has(weekly[1] ?? "")) {
    return "weekly";
  }

  const monthly = new RegExp(`^every month on the (\\d+)(st|nd|rd|th) at ${timePattern}$`).exec(rule);
  if (monthly !== null && isExactOrdinal(monthly[1] ?? "", monthly[2] ?? "")) {
    return "monthly";
  }

  const yearly = new RegExp(`^every year on ([A-Za-z]+) (\\d+)(st|nd|rd|th) at ${timePattern}$`).exec(rule);
  if (
    yearly !== null &&
    isExactOrdinal(yearly[2] ?? "", yearly[3] ?? "") &&
    isValidCalendarDay(yearly[1] ?? "", Number(yearly[2]))
  ) {
    return "yearly";
  }

  return null;
}

function isExactOrdinal(dayText: string, suffix: string): boolean {
  const day = Number(dayText);
  return Number.isInteger(day) && day >= 1 && day <= 31 && formatOrdinalDay(day) === `${dayText}${suffix}`;
}

function isValidCalendarDay(month: string, day: number): boolean {
  const maxDay = monthDayLimits[month];
  return maxDay !== undefined && day <= maxDay;
}

interface LocalStartParts {
  weekday: string;
  month: string;
  day: number;
  hour: string;
  minute: string;
}

function localStartParts(selectedStart: string, timeZone: string): LocalStartParts {
  const start = parseStrictIsoInstant(selectedStart, "selectedStart");

  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone,
    weekday: "long",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
  const parts = formatter.formatToParts(start);

  return {
    weekday: requiredPart(parts, "weekday"),
    month: requiredPart(parts, "month"),
    day: Number(requiredPart(parts, "day")),
    hour: requiredPart(parts, "hour").padStart(2, "0"),
    minute: requiredPart(parts, "minute").padStart(2, "0"),
  };
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

function requiredPart(parts: Intl.DateTimeFormatPart[], type: Intl.DateTimeFormatPartTypes): string {
  const value = parts.find((part) => part.type === type)?.value;
  if (value === undefined) {
    throw new Error(`missing ${type} while formatting recurrence`);
  }
  return value;
}

function isIanaTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone }).format();
    return true;
  } catch {
    return false;
  }
}
