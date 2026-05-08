export type CommitmentState =
  | "planned"
  | "pre_start_warning"
  | "start_blocker_active"
  | "started"
  | "deadline_blocker_active"
  | "completed"
  | "invalid"
  | "deleted";

export interface CommitmentRecord {
  state: CommitmentState;
  startDismissCount: number;
  deadlineExtensionCount: number;
  startedAt: string | null;
  escalationSentAt: string | null;
}

export type CommitmentAction =
  | { type: "start"; now: string }
  | { type: "postpone_start"; now: string }
  | { type: "deadline_active"; now: string }
  | { type: "complete"; now: string }
  | { type: "extend_deadline"; now: string }
  | { type: "reschedule"; now: string }
  | { type: "mark_invalid"; now: string }
  | { type: "mark_deleted"; now: string };

export interface CommitmentTransitionResult extends CommitmentRecord {
  escalationRequired: boolean;
}

export function applyAction(record: CommitmentRecord, action: CommitmentAction): CommitmentTransitionResult {
  switch (action.type) {
    case "start":
      return { ...record, state: "started", startedAt: action.now, escalationRequired: false };
    case "postpone_start": {
      const count = record.startDismissCount + 1;
      return {
        ...record,
        startDismissCount: count,
        escalationRequired: count >= 2 && record.escalationSentAt === null
      };
    }
    case "deadline_active":
      return { ...record, state: "deadline_blocker_active", escalationRequired: false };
    case "complete":
      return { ...record, state: "completed", escalationRequired: false };
    case "extend_deadline":
      return {
        ...record,
        state: "started",
        deadlineExtensionCount: record.deadlineExtensionCount + 1,
        escalationRequired: false
      };
    case "reschedule":
      return { ...record, state: "planned", startDismissCount: 0, startedAt: null, escalationRequired: false };
    case "mark_invalid":
      return { ...record, state: "invalid", escalationRequired: false };
    case "mark_deleted":
      return { ...record, state: "deleted", escalationRequired: false };
  }
}
