import { isIanaTimeZone } from "./taskRecurrence.js";

export const DEFAULT_OWNER_TIME_ZONE = "Europe/Bratislava";

export interface CollaboratorTimeZoneSource {
  getTimeZone(todoistUserId: string): string | null;
}

/**
 * The owner's Todoist timezone decides what a floating due time means. It falls back to
 * OWNER_TIMEZONE, then Europe/Bratislava (decision 2026-09-29-reminder-reliability).
 */
export function resolveOwnerTimeZone(
  source: CollaboratorTimeZoneSource | null,
  currentUserId: string,
  fallback: string = process.env.OWNER_TIMEZONE ?? DEFAULT_OWNER_TIME_ZONE
): string {
  const candidate = source?.getTimeZone(currentUserId) ?? null;
  if (candidate !== null && isIanaTimeZone(candidate)) {
    return candidate;
  }
  return isIanaTimeZone(fallback) ? fallback : DEFAULT_OWNER_TIME_ZONE;
}
