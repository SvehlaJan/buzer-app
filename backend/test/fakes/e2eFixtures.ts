import type {
  TodoistProjectMirror,
  TodoistTaskMirror
} from "../../src/todoist/todoistTypes.js";

export interface E2EFixtureTask {
  id: string;
  mirror: TodoistTaskMirror;
}

export interface E2ECommitmentNudge {
  id: string;
  kind: "started" | "reconciliation_pending";
  startedAt?: string;
}

export interface E2EOccurrencePin {
  id: string;
  occurrenceKey: string;
}

export interface E2EFixtureSeed {
  tasks: E2EFixtureTask[];
  projects: TodoistProjectMirror[];
  nudges: E2ECommitmentNudge[];
  occurrencePins: E2EOccurrencePin[];
}

const TIME_ZONE = "Europe/Bratislava";

/**
 * The same 15 fixture tasks tools/fake-backend/src/fixtures.ts serves today, expressed
 * as Todoist mirrors so the real backend derives every state (invalid, unsupported
 * hierarchy, recurrence kind, checklist counts) instead of being handed it.
 */
export function e2eFixtureSeed(now: Date): E2EFixtureSeed {
  const iso = (minutes: number) => new Date(now.getTime() + minutes * 60_000).toISOString();
  const nowIso = now.toISOString();

  const root = (
    id: string,
    overrides: Partial<TodoistTaskMirror> & Pick<TodoistTaskMirror, "title">
  ): E2EFixtureTask => ({
    id,
    mirror: {
      todoistTaskId: `todoist-${id}`,
      description: null,
      projectId: "project-test",
      parentId: null,
      childOrder: null,
      assigneeId: "user-me",
      creatorId: "user-me",
      dueDate: null,
      dueDatetime: null,
      dueString: null,
      dueLang: null,
      dueTimezone: null,
      isRecurring: false,
      durationAmount: 30,
      durationUnit: "minute",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: {},
      updatedAt: nowIso,
      ...overrides
    }
  });

  const child = (
    id: string,
    parentFixtureId: string,
    title: string,
    order: number,
    checked: boolean,
    overrides: Partial<TodoistTaskMirror> = {}
  ): E2EFixtureTask => ({
    id,
    mirror: {
      todoistTaskId: `todoist-${id}`,
      title,
      description: null,
      projectId: "project-test",
      parentId: `todoist-${parentFixtureId}`,
      childOrder: order,
      assigneeId: null,
      creatorId: "user-me",
      dueDate: null,
      dueDatetime: null,
      dueString: null,
      dueLang: null,
      dueTimezone: null,
      isRecurring: false,
      durationAmount: null,
      durationUnit: null,
      durationMinutes: null,
      completedAt: checked ? nowIso : null,
      deletedAt: null,
      rawJson: {},
      updatedAt: nowIso,
      ...overrides
    }
  });

  const recurring = (rule: string): Partial<TodoistTaskMirror> => ({
    isRecurring: true,
    dueString: rule,
    dueLang: "en",
    dueTimezone: TIME_ZONE
  });

  const tasks: E2EFixtureTask[] = [
    root("task-start", {
      title: "Start blocker fixture",
      description: "Bring the printed form and check opening hours.",
      dueDatetime: iso(120)
    }),
    root("task-deadline", {
      title: "Deadline blocker fixture",
      description: "Share the final numbers before closing the block.",
      dueDatetime: iso(-30),
      durationAmount: 60,
      durationMinutes: 60
    }),
    root("task-reschedule", {
      title: "Reschedule blocker fixture",
      dueDatetime: iso(-30),
      durationAmount: 60,
      durationMinutes: 60
    }),
    root("task-start-fail", {
      title: "Start failure fixture",
      dueDatetime: iso(150)
    }),
    root("task-deadline-fail", {
      title: "Deadline failure fixture",
      dueDatetime: iso(-30),
      durationAmount: 60,
      durationMinutes: 60
    }),
    root("task-reschedule-fail", {
      title: "Reschedule failure fixture",
      dueDatetime: iso(-30),
      durationAmount: 60,
      durationMinutes: 60
    }),
    root("task-invalid", {
      title: "Invalid assigned fixture",
      description: "Clarify the missing commitment fields.",
      durationAmount: null,
      durationUnit: null,
      durationMinutes: null
    }),
    root("task-floating-time", {
      title: "Canonical datetime fixture",
      description: "Snapshot datetimes are normalized to RFC3339 instants.",
      dueDatetime: iso(180)
    }),
    root("task-one-time-empty", {
      title: "One-time empty checklist",
      dueDatetime: iso(120)
    }),
    root("task-one-time-partial", {
      title: "One-time partially checked",
      dueDatetime: iso(180)
    }),
    child("child-one-time-partial-done", "task-one-time-partial", "Already done", 1, true),
    child("child-one-time-partial-open", "task-one-time-partial", "Still open", 2, false),
    root("task-recurring-all-checked", {
      title: "Recurring complete checklist",
      dueDatetime: iso(60),
      ...recurring("every day at 10:00")
    }),
    child("child-recurring-all-checked-a", "task-recurring-all-checked", "First", 1, true),
    child("child-recurring-all-checked-b", "task-recurring-all-checked", "Second", 2, true),
    root("task-recurring-extend-disabled", {
      title: "Recurring extend disabled",
      dueDatetime: iso(300),
      ...recurring("every Monday at 15:00")
    }),
    child("child-recurring-extend-disabled", "task-recurring-extend-disabled", "Complete", 1, true),
    root("task-custom-recurrence", {
      title: "Custom recurring task",
      dueDatetime: iso(1440),
      ...recurring("every 2 days at 10:00")
    }),
    root("task-unsupported-hierarchy", {
      title: "Unsupported hierarchy",
      dueDatetime: iso(1560)
    }),
    child("child-unsupported-hierarchy", "task-unsupported-hierarchy", "Scheduled child", 1, false, {
      dueDatetime: iso(1560)
    }),
    root("task-reconciliation-pending", {
      title: "Pending reconciliation",
      dueDatetime: iso(1680)
    })
  ];

  const projects: TodoistProjectMirror[] = [
    {
      todoistProjectId: "project-test",
      name: "Test Shared",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: {},
      updatedAt: nowIso
    },
    {
      todoistProjectId: "project-personal",
      name: "Test Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: {},
      updatedAt: nowIso
    }
  ];

  const nudges: E2ECommitmentNudge[] = [
    { id: "task-deadline", kind: "started", startedAt: iso(-29) },
    { id: "task-reschedule", kind: "started", startedAt: iso(-29) },
    { id: "task-deadline-fail", kind: "started", startedAt: iso(-29) },
    { id: "task-reschedule-fail", kind: "started", startedAt: iso(-29) },
    { id: "task-reconciliation-pending", kind: "reconciliation_pending" }
  ];

  const occurrencePins: E2EOccurrencePin[] = tasks
    .filter((task) => task.mirror.parentId === null)
    .map((task) => ({ id: task.id, occurrenceKey: `occurrence-${task.id}-1` }));

  return { tasks, projects, nudges, occurrencePins };
}
