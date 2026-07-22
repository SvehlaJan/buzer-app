export type FakeChecklistItem = {
  id: string;
  title: string;
  description: string | null;
  checked: boolean;
  childRevision: number;
  order: number;
};

export type FakeTask = {
  id: string;
  todoistTaskId: string;
  title: string;
  description: string | null;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  state: "planned" | "started" | "invalid";
  occurrenceNumber: number;
  occurrenceKey: string;
  taskRevision: number;
  recurrence: { kind: "none" | "supported" | "custom"; preset: string | null; rule: string | null; language: string | null; timeZone: string | null };
  hierarchyStatus: "valid" | "unsupported";
  hierarchyViolation: string | null;
  reconciliationStatus: "none" | "pending";
  checklistItems: FakeChecklistItem[];
  startedAt: string | null;
  startDismissCount: number;
  deadlineExtensionCount: number;
  isCompleted: boolean;
};

export type FakeState = { scheduleVersion: number; snapshotGeneration: number; tasks: FakeTask[] };

const FIXED_NOW = "2026-06-01T10:00:00.000Z";

export function createFixtureState(): FakeState {
  const legacyNow = new Date();
  const legacyIsoAt = (minutes: number) => new Date(legacyNow.getTime() + minutes * 60_000).toISOString();
  return {
    scheduleVersion: 1,
    snapshotGeneration: 1,
    tasks: [
      // Preserve the focused Android activity fixtures while the broader recurrent/checklist
      // scenarios below exercise the new contract.
      task("task-start", {
        title: "Start blocker fixture", description: "Bring the printed form and check opening hours.",
        dueDatetime: legacyIsoAt(120), occurrenceKey: "occurrence-task-start-1"
      }),
      task("task-deadline", {
        title: "Deadline blocker fixture", description: "Share the final numbers before closing the block.",
        dueDatetime: legacyIsoAt(-30), occurrenceKey: "occurrence-task-deadline-1",
        state: "started", startedAt: legacyIsoAt(-29), durationMinutes: 60
      }),
      task("task-reschedule", {
        title: "Reschedule blocker fixture", description: null,
        dueDatetime: legacyIsoAt(-30), occurrenceKey: "occurrence-task-reschedule-1",
        state: "started", startedAt: legacyIsoAt(-29), durationMinutes: 60
      }),
      task("task-start-fail", {
        title: "Start failure fixture", dueDatetime: legacyIsoAt(150), occurrenceKey: "occurrence-task-start-fail-1"
      }),
      task("task-deadline-fail", {
        title: "Deadline failure fixture", dueDatetime: legacyIsoAt(-30), occurrenceKey: "occurrence-task-deadline-fail-1",
        state: "started", startedAt: legacyIsoAt(-29), durationMinutes: 60
      }),
      task("task-reschedule-fail", {
        title: "Reschedule failure fixture", dueDatetime: legacyIsoAt(-30), occurrenceKey: "occurrence-task-reschedule-fail-1",
        state: "started", startedAt: legacyIsoAt(-29), durationMinutes: 60
      }),
      task("task-invalid", {
        title: "Invalid assigned fixture", description: "Clarify the missing commitment fields.",
        dueDatetime: null, durationMinutes: null, occurrenceKey: "occurrence-task-invalid-1", state: "invalid"
      }),
      task("task-floating-time", {
        title: "Canonical datetime fixture", description: "Snapshot datetimes are normalized to RFC3339 instants.",
        dueDatetime: legacyIsoAt(180), occurrenceKey: "occurrence-task-floating-time-1"
      }),
      task("task-one-time-empty", { title: "One-time empty checklist", dueDatetime: legacyIsoAt(-31), occurrenceKey: "occurrence-one-time-empty-1" }),
      task("task-one-time-partial", {
        title: "One-time partially checked", dueDatetime: "2026-06-01T13:00:00.000Z", occurrenceKey: "occurrence-one-time-partial-1",
        checklistItems: [child("child-one-time-partial-done", "Already done", true, 1), child("child-one-time-partial-open", "Still open", false, 2)]
      }),
      task("task-recurring-all-checked", {
        title: "Recurring complete checklist", dueDatetime: "2026-06-01T10:00:00.000Z", occurrenceKey: "occurrence-recurring-all-checked-1",
        recurrence: recurrence("daily", "every day at 10:00"), checklistItems: [child("child-recurring-all-checked-a", "First", true, 1), child("child-recurring-all-checked-b", "Second", true, 2)]
      }),
      task("task-recurring-extend-disabled", {
        title: "Recurring extend disabled", dueDatetime: "2026-06-01T15:00:00.000Z", occurrenceKey: "occurrence-recurring-extend-disabled-1",
        recurrence: recurrence("weekly", "every Monday at 15:00"), checklistItems: [child("child-recurring-extend-disabled", "Complete", true, 1)]
      }),
      task("task-custom-recurrence", {
        title: "Custom recurring task", dueDatetime: "2026-06-02T10:00:00.000Z", occurrenceKey: "occurrence-custom-recurrence-1",
        recurrence: { kind: "custom", preset: null, rule: "every 2 days at 10:00", language: "en", timeZone: "Europe/Bratislava" }
      }),
      task("task-unsupported-hierarchy", {
        title: "Unsupported hierarchy", dueDatetime: "2026-06-02T12:00:00.000Z", occurrenceKey: "occurrence-unsupported-hierarchy-1", state: "invalid",
        hierarchyStatus: "unsupported", hierarchyViolation: "child_has_schedule", checklistItems: [child("child-unsupported-hierarchy", "Scheduled child", false, 1)]
      }),
      task("task-reconciliation-pending", {
        title: "Pending reconciliation", dueDatetime: "2026-06-02T14:00:00.000Z", occurrenceKey: "occurrence-reconciliation-pending-1", reconciliationStatus: "pending"
      })
    ]
  };
}

export function snapshot(state: FakeState, serverTime = FIXED_NOW) {
  return {
    scheduleVersion: state.scheduleVersion,
    snapshotGeneration: state.snapshotGeneration,
    serverTime,
    health: [],
    tasks: state.tasks.filter((task) => !task.isCompleted).map((task) => ({
      id: task.id,
      todoistTaskId: task.todoistTaskId,
      title: task.title,
      description: task.description,
      projectId: task.projectId,
      assigneeId: task.assigneeId,
      creatorId: "user-me",
      dueDatetime: task.dueDatetime,
      durationMinutes: task.durationMinutes,
      startAt: task.dueDatetime,
      state: task.state,
      isAssignedToUser: task.assigneeId === "user-me" || (task.projectId === "project-personal" && task.assigneeId === null),
      isCreatedByUser: true,
      isCompleted: task.isCompleted,
      isDeleted: false,
      validationErrors: task.hierarchyStatus === "unsupported" ? [task.hierarchyViolation] : [
        ...((task.assigneeId === "user-me" || (task.projectId === "project-personal" && task.assigneeId === null)) && task.dueDatetime === null ? ["missing_due_datetime"] : []),
        ...((task.assigneeId === "user-me" || (task.projectId === "project-personal" && task.assigneeId === null)) && task.durationMinutes === null ? ["missing_duration"] : [])
      ],
      startedAt: task.startedAt,
      startDismissCount: task.startDismissCount,
      deadlineExtensionCount: task.deadlineExtensionCount,
      escalationSentAt: null,
      updatedAt: FIXED_NOW,
      occurrenceKey: task.occurrenceKey,
      taskRevision: `r:${task.taskRevision}`,
      activityLogEntries: [],
      recurrence: {
        ...task.recurrence,
        displayLabel: recurrenceDisplayLabel(task.recurrence)
      },
      checklistItems: task.checklistItems.map((item) => ({ ...item, childRevision: `r:${item.childRevision}` })),
      checkedCount: task.checklistItems.filter((item) => item.checked).length,
      totalCount: task.checklistItems.length,
      hierarchyStatus: task.hierarchyStatus,
      hierarchyViolation: task.hierarchyViolation,
      reconciliationStatus: task.reconciliationStatus
    }))
  };
}

function recurrenceDisplayLabel(recurrence: FakeTask["recurrence"]): string {
  if (recurrence.kind === "custom") return recurrence.rule ?? "Custom Todoist recurrence";
  if (recurrence.preset === null || recurrence.preset === "never") return "Never";
  return recurrence.preset.slice(0, 1).toUpperCase() + recurrence.preset.slice(1);
}

function task(id: string, overrides: Partial<FakeTask> & Pick<FakeTask, "title" | "dueDatetime" | "occurrenceKey">): FakeTask {
  const { title, dueDatetime, occurrenceKey, ...rest } = overrides;
  return {
    id, todoistTaskId: `todoist-${id}`, title, description: null, projectId: "project-test", assigneeId: "user-me", dueDatetime, durationMinutes: 30,
    state: "planned", occurrenceNumber: 1, occurrenceKey, taskRevision: 1,
    recurrence: { kind: "none", preset: "never", rule: null, language: null, timeZone: null }, hierarchyStatus: "valid", hierarchyViolation: null,
    reconciliationStatus: "none", checklistItems: [], startedAt: null, startDismissCount: 0, deadlineExtensionCount: 0, isCompleted: false, ...rest
  };
}

function child(id: string, title: string, checked: boolean, order: number): FakeChecklistItem {
  return { id, title, description: null, checked, childRevision: 1, order };
}

function recurrence(preset: string, rule: string): FakeTask["recurrence"] {
  return { kind: "supported", preset, rule, language: "en", timeZone: "Europe/Bratislava" };
}
