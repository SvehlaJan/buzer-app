export type OccurrenceDecision =
  | { kind: "unchanged" }
  | {
      kind: "rotate";
      reason: "initialized" | "external_schedule" | "recurring_advance" | "editor_replan" | "ownership_returned";
    }
  | { kind: "reset_children_then_rotate"; checkedChildIds: string[] }
  | { kind: "leave_ownership" };

export interface OccurrenceScheduleFacts {
  dueDatetime: string | null;
  isRecurring: boolean;
  dueString: string | null;
  dueLang: string | null;
  dueTimezone: string | null;
}

export interface OccurrenceAnchors {
  occurrenceKey: string | null;
  occurrenceDueDatetime: string | null;
  occurrenceRecurrenceFingerprint: string | null;
}

export interface OccurrenceReconciliationInput {
  current: OccurrenceAnchors;
  incoming: OccurrenceScheduleFacts;
  isOwned: boolean;
  wasOwned: boolean;
  source: "sync" | "editor_replan" | "extend";
  checkedChildIds: string[];
}

export class OccurrenceReconciler {
  decide(input: OccurrenceReconciliationInput): OccurrenceDecision {
    if (!input.isOwned) {
      return { kind: "leave_ownership" };
    }
    if (!input.wasOwned) {
      return { kind: "rotate", reason: "ownership_returned" };
    }
    if (input.current.occurrenceKey === null || input.current.occurrenceKey.trim() === "") {
      return { kind: "rotate", reason: "initialized" };
    }
    if (input.source === "extend") {
      return { kind: "unchanged" };
    }

    const fingerprint = recurrenceFingerprint(input.incoming);
    const dueChanged = input.current.occurrenceDueDatetime !== input.incoming.dueDatetime;
    const recurrenceChanged = input.current.occurrenceRecurrenceFingerprint !== fingerprint;
    if (!dueChanged && !recurrenceChanged) {
      return { kind: "unchanged" };
    }
    if (input.source === "editor_replan") {
      return { kind: "rotate", reason: "editor_replan" };
    }
    if (input.incoming.isRecurring && dueChanged) {
      if (input.checkedChildIds.length > 0) {
        return { kind: "reset_children_then_rotate", checkedChildIds: input.checkedChildIds };
      }
      return { kind: "rotate", reason: "recurring_advance" };
    }
    return { kind: "rotate", reason: "external_schedule" };
  }
}

export function recurrenceFingerprint(input: Pick<
  OccurrenceScheduleFacts,
  "isRecurring" | "dueString" | "dueLang" | "dueTimezone"
>): string {
  return JSON.stringify({
    isRecurring: input.isRecurring,
    dueString: normalizeFingerprintText(input.dueString, true),
    dueLang: normalizeFingerprintText(input.dueLang, true),
    dueTimezone: normalizeFingerprintText(input.dueTimezone, false)
  });
}

function normalizeFingerprintText(value: string | null, lowerCase: boolean): string | null {
  const normalized = value?.trim().replace(/\s+/g, " ") ?? "";
  if (normalized.length === 0) {
    return null;
  }
  return lowerCase ? normalized.toLowerCase() : normalized;
}
