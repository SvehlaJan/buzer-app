import type {
  TodoistCollaboratorMirror,
  TodoistCollaboratorStateMirror,
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
  collaborators: TodoistCollaboratorMirror[];
  collaboratorStates: TodoistCollaboratorStateMirror[];
  nudges: E2ECommitmentNudge[];
  occurrencePins: E2EOccurrencePin[];
}

/** Stable, small projections of the Change 1 fixture model for Morning Review E2E cases. */
export type E2EMorningReviewScenario = "clean" | "invalid" | "overdue" | "today" | "blockingMix" | "pendingOnly" | "unassigned" | "priorityStart" | "priorityDeadline";

const MORNING_REVIEW_SCENARIO_ROOT_IDS: Record<E2EMorningReviewScenario, readonly string[]> = {
  clean: [],
  invalid: ["task-invalid"],
  overdue: ["task-overdue-1", "task-overdue-2", "task-overdue-3", "task-overdue-4"],
  today: ["task-current-overlapping", "task-future-tomorrow"],
  blockingMix: ["task-invalid", "task-reconciliation-pending", "task-unassigned-root"],
  pendingOnly: ["task-reconciliation-pending"],
  unassigned: ["task-unassigned-root"],
  priorityStart: ["task-current-overlapping"],
  priorityDeadline: ["task-overdue-1"]
};

const TIME_ZONE = "Europe/Bratislava";

interface LocalDate {
  year: number;
  month: number;
  day: number;
}

function localDateAt(now: Date, timeZone: string): LocalDate {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value;

  return {
    year: Number(part("year")),
    month: Number(part("month")),
    day: Number(part("day"))
  };
}

function addCalendarDays(date: LocalDate, days: number): LocalDate {
  const next = new Date(Date.UTC(date.year, date.month - 1, date.day + days));
  return {
    year: next.getUTCFullYear(),
    month: next.getUTCMonth() + 1,
    day: next.getUTCDate()
  };
}

function timeZoneOffsetMilliseconds(instant: Date, timeZone: string): number {
  const offset = new Intl.DateTimeFormat("en-US", {
    timeZone,
    timeZoneName: "longOffset"
  }).formatToParts(instant).find((part) => part.type === "timeZoneName")?.value;
  const match = /^GMT([+-])(\d{2}):(\d{2})$/.exec(offset ?? "");
  if (match === null) throw new Error(`Unable to determine ${timeZone} offset`);

  const minutes = Number(match[2]) * 60 + Number(match[3]);
  return (match[1] === "+" ? 1 : -1) * minutes * 60_000;
}

function zonedIso(date: LocalDate, hour: number, minute: number, timeZone: string): string {
  // Let Intl resolve the IANA-zone offset on this calendar date so wall-clock recurrence
  // times remain stable when Bratislava crosses DST; never add a fixed day in UTC.
  const utcWallTime = Date.UTC(date.year, date.month - 1, date.day, hour, minute);
  const offset = timeZoneOffsetMilliseconds(new Date(utcWallTime), timeZone);
  return new Date(utcWallTime - offset).toISOString();
}

function nextDailyOccurrence(now: Date, hour: number, minute: number): string {
  let date = localDateAt(now, TIME_ZONE);
  let occurrence = zonedIso(date, hour, minute, TIME_ZONE);
  if (Date.parse(occurrence) <= now.getTime()) {
    date = addCalendarDays(date, 1);
    occurrence = zonedIso(date, hour, minute, TIME_ZONE);
  }
  return occurrence;
}

function nextWeeklyOccurrence(now: Date, weekday: number, hour: number, minute: number): string {
  let date = localDateAt(now, TIME_ZONE);
  const currentWeekday = new Date(Date.UTC(date.year, date.month - 1, date.day)).getUTCDay();
  date = addCalendarDays(date, (weekday - currentWeekday + 7) % 7);
  let occurrence = zonedIso(date, hour, minute, TIME_ZONE);
  if (Date.parse(occurrence) <= now.getTime()) {
    date = addCalendarDays(date, 7);
    occurrence = zonedIso(date, hour, minute, TIME_ZONE);
  }
  return occurrence;
}

function nextEveryTwoDaysOccurrence(now: Date, hour: number, minute: number): string {
  let date = localDateAt(now, TIME_ZONE);
  let occurrence = zonedIso(date, hour, minute, TIME_ZONE);
  if (Date.parse(occurrence) <= now.getTime()) {
    date = addCalendarDays(date, 2);
    occurrence = zonedIso(date, hour, minute, TIME_ZONE);
  }
  return occurrence;
}

/**
 * Fixture tasks expressed as Todoist mirrors so the real backend derives every state
 * (invalid, unsupported hierarchy, recurrence kind, checklist counts) instead of being
 * handed it. Formerly mirrored by the now-removed tools/fake-backend/src/fixtures.ts
 * (see docs/decisions/2026-07-25-remove-fake-backend-tool.md).
 */
export function e2eFixtureSeed(now: Date): E2EFixtureSeed {
  const iso = (minutes: number) => new Date(now.getTime() + minutes * 60_000).toISOString();
  const nowIso = now.toISOString();
  const localToday = localDateAt(now, TIME_ZONE);
  const nextYearDate: LocalDate = { year: localToday.year + 1, month: 1, day: 2 };
  const futureMidnightDate = addCalendarDays(localToday, 2);

  const root = (
    id: string,
    overrides: Partial<TodoistTaskMirror> & Pick<TodoistTaskMirror, "title">
  ): E2EFixtureTask => {
    const {
      dueDate: _dueDate,
      dueDatetime: overrideDueDatetime,
      ...rest
    } = overrides;
    const dueDatetime = overrideDueDatetime ?? null;
    return {
      id,
      mirror: {
        todoistTaskId: `todoist-${id}`,
        description: null,
        projectId: "project-test",
        parentId: null,
        childOrder: null,
        assigneeId: "user-me",
        creatorId: "user-me",
        dueDate: dueDatetime,
        dueDatetime,
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
        ...rest
      }
    };
  };

  const child = (
    id: string,
    parentFixtureId: string,
    title: string,
    order: number,
    checked: boolean,
    overrides: Partial<TodoistTaskMirror> = {}
  ): E2EFixtureTask => {
    const {
      dueDate: _dueDate,
      dueDatetime: overrideDueDatetime,
      ...rest
    } = overrides;
    const dueDatetime = overrideDueDatetime ?? null;
    return {
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
        dueDate: dueDatetime,
        dueDatetime,
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
        ...rest
      }
    };
  };

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
    // Deliberately overdue but missing only duration: the deadline-blocker Update host must
    // expose its entry validation while still receiving a current, editable root snapshot.
    root("task-invalid-blocker", {
      title: "Invalid deadline blocker fixture",
      description: "Restore the missing duration from deadline recovery.",
      dueDatetime: iso(-30),
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
      dueDatetime: iso(-31)
    }),
    root("task-one-time-partial", {
      title: "One-time partially checked",
      dueDatetime: iso(180)
    }),
    child("child-one-time-partial-done", "task-one-time-partial", "Already done", 1, true),
    child("child-one-time-partial-open", "task-one-time-partial", "Still open", 2, false),
    root("task-recurring-all-checked", {
      title: "Recurring complete checklist",
      dueDatetime: nextDailyOccurrence(now, 10, 0),
      ...recurring("every day at 10:00")
    }),
    child("child-recurring-all-checked-a", "task-recurring-all-checked", "First", 1, true),
    child("child-recurring-all-checked-b", "task-recurring-all-checked", "Second", 2, true),
    root("task-recurring-extend-disabled", {
      title: "Recurring extend disabled",
      dueDatetime: nextWeeklyOccurrence(now, 1, 15, 0),
      ...recurring("every Monday at 15:00")
    }),
    child("child-recurring-extend-disabled", "task-recurring-extend-disabled", "Complete", 1, true),
    root("task-custom-recurrence", {
      title: "Custom recurring task",
      dueDatetime: nextEveryTwoDaysOccurrence(now, 10, 0),
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
    }),
    root("task-current-overlapping", {
      title: "Overlapping current fixture",
      description: "Overlapping current task block",
      dueDatetime: iso(-15),
      durationAmount: 75,
      durationMinutes: 75
    }),
    root("task-overdue-1", {
      title: "Overdue task 1",
      dueDatetime: iso(-180),
      durationAmount: 30,
      durationMinutes: 30
    }),
    root("task-overdue-2", {
      title: "Overdue task 2",
      dueDatetime: iso(-120),
      durationAmount: 30,
      durationMinutes: 30
    }),
    root("task-overdue-3", {
      title: "Overdue task 3",
      dueDatetime: iso(-70),
      durationAmount: 30,
      durationMinutes: 30
    }),
    root("task-overdue-4", {
      title: "Overdue task 4",
      dueDatetime: iso(-45),
      durationAmount: 20,
      durationMinutes: 20
    }),
    root("task-future-tomorrow", {
      title: "Tomorrow task",
      dueDatetime: iso(24 * 60),
      durationAmount: 30,
      durationMinutes: 30
    }),
    root("task-future-later", {
      title: "Later task",
      dueDatetime: iso(3 * 24 * 60),
      durationAmount: 30,
      durationMinutes: 30
    }),
    root("task-future-next-year", {
      title: "Next year task",
      dueDatetime: zonedIso(nextYearDate, 10, 0, TIME_ZONE),
      durationAmount: 30,
      durationMinutes: 30
    }),
    root("task-future-midnight", {
      title: "Midnight crossing task",
      dueDatetime: zonedIso(futureMidnightDate, 23, 30, TIME_ZONE),
      durationAmount: 90,
      durationMinutes: 90
    }),
    root("task-unassigned-root", {
      title: "Unassigned root fixture",
      projectId: "project-test",
      assigneeId: null,
      dueDatetime: null,
      durationAmount: null,
      durationUnit: null,
      durationMinutes: null
    }),
    child("child-unassigned-1", "task-unassigned-root", "Unassigned child task", 1, false),
    root("task-assigned-other", {
      title: "Wife task assigned to other",
      projectId: "project-test",
      assigneeId: "user-wife",
      dueDatetime: iso(60),
      durationAmount: 30,
      durationMinutes: 30
    }),
    root("task-incomplete-checklist", {
      title: "Incomplete checklist task",
      dueDatetime: iso(-20),
      durationAmount: 35,
      durationMinutes: 35
    }),
    child("child-incomplete-open", "task-incomplete-checklist", "Incomplete child item", 1, false)
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

  const collaborators: TodoistCollaboratorMirror[] = [
    {
      todoistUserId: "user-me",
      fullName: "Me",
      email: "me@example.test",
      timeZone: TIME_ZONE,
      imageId: null,
      isDeleted: false,
      rawJson: {},
      updatedAt: nowIso
    },
    {
      todoistUserId: "user-wife",
      fullName: "Wife",
      email: "wife@example.test",
      timeZone: TIME_ZONE,
      imageId: null,
      isDeleted: false,
      rawJson: {},
      updatedAt: nowIso
    }
  ];

  const collaboratorStates: TodoistCollaboratorStateMirror[] = [
    {
      todoistProjectId: "project-test",
      todoistUserId: "user-me",
      state: "active",
      role: "admin",
      isDeleted: false,
      rawJson: {},
      updatedAt: nowIso
    },
    {
      todoistProjectId: "project-test",
      todoistUserId: "user-wife",
      state: "active",
      role: "member",
      isDeleted: false,
      rawJson: {},
      updatedAt: nowIso
    }
  ];

  const nudges: E2ECommitmentNudge[] = [
    { id: "task-deadline", kind: "started", startedAt: iso(-29) },
    { id: "task-current-overlapping", kind: "started", startedAt: iso(-14) },
    { id: "task-reschedule", kind: "started", startedAt: iso(-29) },
    { id: "task-deadline-fail", kind: "started", startedAt: iso(-29) },
    { id: "task-reschedule-fail", kind: "started", startedAt: iso(-29) },
    { id: "task-reconciliation-pending", kind: "reconciliation_pending" },
    // Without this nudge the task is "planned" while already inside its due window, which makes
    // AlarmPlanCalculator's catch-up policy (decision 2026-07-06-catchup-start-blocker) re-arm an
    // immediate START_BLOCKER alarm on every MainActivity reconcile -- harmless before foreground
    // readiness could grant real Overlay permission (OverlayBlockerService no-op'd with
    // reason=no_permission), but a real focus-stealing overlay once it can. Marking it already
    // "started" matches its already-started siblings above and keeps it out of that catch-up path
    // while leaving its due window, checklist state, and Home visibility unchanged.
    { id: "task-incomplete-checklist", kind: "started", startedAt: iso(-19) }
  ];

  const occurrencePins: E2EOccurrencePin[] = tasks
    .filter((task) => task.mirror.parentId === null)
    .map((task) => ({ id: task.id, occurrenceKey: `occurrence-${task.id}-1` }));

  return { tasks, projects, collaborators, collaboratorStates, nudges, occurrencePins };
}

export function e2eMorningReviewScenarioSeed(
  now: Date,
  scenario: E2EMorningReviewScenario
): E2EFixtureSeed {
  const seed = e2eFixtureSeed(now);
  const rootIds = new Set(MORNING_REVIEW_SCENARIO_ROOT_IDS[scenario]);
  const selectedTodoistIds = new Set(
    [...rootIds].map((id) => `todoist-${id}`)
  );
  const tasks = seed.tasks.filter((task) =>
    rootIds.has(task.id) || (task.mirror.parentId !== null && selectedTodoistIds.has(task.mirror.parentId))
  );

  const omitPriorityAuthority = scenario === "priorityStart" || scenario === "priorityDeadline";
  return {
    ...seed,
    tasks,
    nudges: seed.nudges.filter((nudge) => rootIds.has(nudge.id) && !omitPriorityAuthority),
    occurrencePins: seed.occurrencePins.filter((pin) => rootIds.has(pin.id) && !omitPriorityAuthority)
  };
}
