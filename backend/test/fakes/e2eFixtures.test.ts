import { describe, expect, it } from "vitest";
import { describeRecurrence } from "../../src/domain/taskRecurrence.js";
import {
  e2eFixtureSeed,
  e2eMorningReviewScenarioSeed,
  type E2EMorningReviewScenario
} from "./e2eFixtures.js";

const now = new Date("2026-07-18T10:00:00.000Z");
const seed = e2eFixtureSeed(now);
const byId = new Map(seed.tasks.map((task) => [task.id, task.mirror]));

function localDateTimeParts(value: string, timeZone = "Europe/Bratislava") {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    weekday: "long",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23"
  }).formatToParts(new Date(value));
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value;

  return {
    weekday: part("weekday"),
    date: `${part("year")}-${part("month")}-${part("day")}`,
    time: `${part("hour")}:${part("minute")}`
  };
}

describe("e2eFixtureSeed", () => {
  it("exposes named deterministic clean, invalid, overdue, and today Morning Review scenarios", () => {
    const roots = (scenario: E2EMorningReviewScenario) => e2eMorningReviewScenarioSeed(now, scenario)
      .tasks
      .filter((task) => task.mirror.parentId === null)
      .map((task) => task.id);

    expect(roots("clean")).toEqual([]);
    expect(roots("invalid")).toEqual(["task-invalid"]);
    expect(roots("overdue")).toEqual([
      "task-overdue-1",
      "task-overdue-2",
      "task-overdue-3",
      "task-overdue-4"
    ]);
    expect(roots("today")).toEqual([
      "task-current-overlapping",
      "task-future-tomorrow"
    ]);
  });

  it("defines the 28 fixture roots plus 8 checklist children with pinned Todoist ids", () => {
    const roots = seed.tasks.filter((task) => task.mirror.parentId === null);
    const children = seed.tasks.filter((task) => task.mirror.parentId !== null);

    expect(roots).toHaveLength(28);
    expect(children).toHaveLength(8);
    expect(roots.map((task) => task.id)).toEqual([
      "task-start",
      "task-deadline",
      "task-reschedule",
      "task-start-fail",
      "task-deadline-fail",
      "task-reschedule-fail",
      "task-invalid",
      "task-invalid-blocker",
      "task-floating-time",
      "task-one-time-empty",
      "task-one-time-partial",
      "task-recurring-all-checked",
      "task-recurring-extend-disabled",
      "task-custom-recurrence",
      "task-unsupported-hierarchy",
      "task-reconciliation-pending",
      "task-current-overlapping",
      "task-overdue-1",
      "task-overdue-2",
      "task-overdue-3",
      "task-overdue-4",
      "task-future-tomorrow",
      "task-future-later",
      "task-future-next-year",
      "task-future-midnight",
      "task-unassigned-root",
      "task-assigned-other",
      "task-incomplete-checklist"
    ]);
    expect(byId.get("task-start")?.title).toBe("Start blocker fixture");
    for (const { id, mirror } of seed.tasks) {
      expect(mirror.todoistTaskId).toBe(`todoist-${id}`);
    }
  });

  it("expresses invalid, hierarchy, and recurrence outcomes as Todoist facts", () => {
    expect(byId.get("task-invalid")?.dueDatetime).toBeNull();
    expect(byId.get("task-invalid")?.durationMinutes).toBeNull();
    expect(byId.get("task-invalid-blocker")?.dueDatetime).not.toBeNull();
    expect(byId.get("task-invalid-blocker")?.durationMinutes).toBeNull();
    expect(byId.get("child-unsupported-hierarchy")?.dueDatetime).not.toBeNull();
    expect(byId.get("child-unsupported-hierarchy")?.parentId).toBe(
      "todoist-task-unsupported-hierarchy"
    );

    const daily = byId.get("task-recurring-all-checked");
    expect(describeRecurrence({
      isRecurring: daily?.isRecurring ?? false,
      rule: daily?.dueString ?? null,
      language: daily?.dueLang ?? null,
      timeZone: daily?.dueTimezone ?? null
    })).toEqual({ kind: "supported", preset: "daily" });

    const custom = byId.get("task-custom-recurrence");
    expect(describeRecurrence({
      isRecurring: custom?.isRecurring ?? false,
      rule: custom?.dueString ?? null,
      language: custom?.dueLang ?? null,
      timeZone: custom?.dueTimezone ?? null
    })).toEqual({ kind: "custom", preset: null });
  });

  it("keeps each scheduled Todoist dueDate aligned with its dueDatetime", () => {
    for (const { mirror } of seed.tasks) {
      if (mirror.dueDatetime !== null) {
        expect(mirror.dueDate).toBe(mirror.dueDatetime);
      }
    }
  });

  it("derives recurring due instants at their Bratislava rule times across DST", () => {
    expect(localDateTimeParts(
      byId.get("task-recurring-all-checked")?.dueDatetime ?? ""
    )).toEqual({
      weekday: "Sunday",
      date: "2026-07-19",
      time: "10:00"
    });
    expect(localDateTimeParts(
      byId.get("task-recurring-extend-disabled")?.dueDatetime ?? ""
    )).toEqual({
      weekday: "Monday",
      date: "2026-07-20",
      time: "15:00"
    });
    expect(localDateTimeParts(
      byId.get("task-custom-recurrence")?.dueDatetime ?? ""
    )).toEqual({
      weekday: "Monday",
      date: "2026-07-20",
      time: "10:00"
    });

    const dstSeed = e2eFixtureSeed(new Date("2026-03-28T12:00:00.000Z"));
    const dstById = new Map(dstSeed.tasks.map((task) => [task.id, task.mirror]));
    expect(localDateTimeParts(
      dstById.get("task-recurring-all-checked")?.dueDatetime ?? ""
    )).toEqual({
      weekday: "Sunday",
      date: "2026-03-29",
      time: "10:00"
    });
    expect(localDateTimeParts(
      dstById.get("task-recurring-extend-disabled")?.dueDatetime ?? ""
    )).toEqual({
      weekday: "Monday",
      date: "2026-03-30",
      time: "15:00"
    });
    expect(localDateTimeParts(
      dstById.get("task-custom-recurrence")?.dueDatetime ?? ""
    )).toEqual({
      weekday: "Monday",
      date: "2026-03-30",
      time: "10:00"
    });
  });

  it("marks checklist children and pins an occurrence key for every root", () => {
    expect(byId.get("child-one-time-partial-done")?.completedAt).toBe(now.toISOString());
    expect(byId.get("child-one-time-partial-open")?.completedAt).toBeNull();
    expect(byId.get("child-recurring-all-checked-a")?.completedAt).toBe(now.toISOString());
    expect(byId.get("child-recurring-all-checked-b")?.completedAt).toBe(now.toISOString());
    expect(byId.get("child-recurring-extend-disabled")?.completedAt).toBe(now.toISOString());

    const rootIds = seed.tasks
      .filter((task) => task.mirror.parentId === null)
      .map((task) => task.id);
    expect(seed.occurrencePins.map((pin) => pin.id).sort()).toEqual([...rootIds].sort());
    expect(seed.occurrencePins.find((pin) => pin.id === "task-start")?.occurrenceKey).toBe(
      "occurrence-task-start-1"
    );
  });

  it("nudges exactly the started and reconciliation-pending roots", () => {
    expect(seed.nudges).toEqual([
      { id: "task-deadline", kind: "started", startedAt: "2026-07-18T09:31:00.000Z" },
      { id: "task-current-overlapping", kind: "started", startedAt: "2026-07-18T09:46:00.000Z" },
      { id: "task-reschedule", kind: "started", startedAt: "2026-07-18T09:31:00.000Z" },
      { id: "task-deadline-fail", kind: "started", startedAt: "2026-07-18T09:31:00.000Z" },
      { id: "task-reschedule-fail", kind: "started", startedAt: "2026-07-18T09:31:00.000Z" },
      { id: "task-reconciliation-pending", kind: "reconciliation_pending" }
    ]);
  });

  it("defines deterministic Smart Home timing, ownership, and checklist relationships", () => {
    const endAt = (id: string) => {
      const task = byId.get(id);
      if (task?.dueDatetime === null || task?.dueDatetime === undefined || task.durationMinutes === null) {
        throw new Error(`missing schedule for ${id}`);
      }
      return Date.parse(task.dueDatetime) + task.durationMinutes * 60_000;
    };

    expect(endAt("task-incomplete-checklist")).toBeGreaterThan(now.getTime());
    expect(endAt("task-current-overlapping")).toBeGreaterThan(now.getTime());
    expect(Date.parse(byId.get("task-incomplete-checklist")?.dueDatetime ?? "")).toBeLessThan(now.getTime());
    expect(Date.parse(byId.get("task-current-overlapping")?.dueDatetime ?? "")).toBeLessThan(now.getTime());

    expect([
      "task-overdue-1",
      "task-overdue-2",
      "task-overdue-3",
      "task-overdue-4"
    ].map(endAt)).toEqual([
      Date.parse("2026-07-18T07:30:00.000Z"),
      Date.parse("2026-07-18T08:30:00.000Z"),
      Date.parse("2026-07-18T09:20:00.000Z"),
      Date.parse("2026-07-18T09:35:00.000Z")
    ]);
    expect(localDateTimeParts(byId.get("task-future-tomorrow")?.dueDatetime ?? "").date)
      .toBe("2026-07-19");
    expect(localDateTimeParts(byId.get("task-future-later")?.dueDatetime ?? "").date)
      .toBe("2026-07-21");
    expect(localDateTimeParts(byId.get("task-future-next-year")?.dueDatetime ?? "").date)
      .toBe("2027-01-02");
    expect(localDateTimeParts(byId.get("task-future-midnight")?.dueDatetime ?? "").time)
      .toBe("23:30");
    expect(new Date(endAt("task-future-midnight")).toISOString()).toBe("2026-07-20T23:00:00.000Z");

    expect(byId.get("task-unassigned-root")).toMatchObject({
      projectId: "project-test",
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null
    });
    expect(byId.get("child-unassigned-1")).toMatchObject({
      parentId: "todoist-task-unassigned-root",
      assigneeId: null,
      completedAt: null
    });
    expect(byId.get("task-assigned-other")?.assigneeId).toBe("user-wife");
    expect(byId.get("child-incomplete-open")).toMatchObject({
      parentId: "todoist-task-incomplete-checklist",
      completedAt: null
    });
  });

  it("pins next-year content to an explicit date in the next local calendar year", () => {
    const leapNight = new Date("2028-02-29T22:59:00.000Z");
    const leapSeed = e2eFixtureSeed(leapNight);
    const leapById = new Map(leapSeed.tasks.map((task) => [task.id, task.mirror]));
    const nextYear = localDateTimeParts(
      leapById.get("task-future-next-year")?.dueDatetime ?? ""
    );

    expect(nextYear).toMatchObject({ date: "2029-01-02", time: "10:00" });
    expect(Date.parse(leapById.get("task-future-next-year")?.dueDatetime ?? ""))
      .toBeGreaterThan(leapNight.getTime());
  });

  it("keeps the cross-midnight fixture future at the local year-end edge", () => {
    const localYearEnd = new Date("2028-12-31T22:59:00.000Z");
    const edgeSeed = e2eFixtureSeed(localYearEnd);
    const edgeById = new Map(edgeSeed.tasks.map((task) => [task.id, task.mirror]));
    const midnightTask = edgeById.get("task-future-midnight");
    const start = midnightTask?.dueDatetime ?? "";
    const end = new Date(Date.parse(start) + (midnightTask?.durationMinutes ?? 0) * 60_000)
      .toISOString();

    expect(localDateTimeParts(start)).toMatchObject({ date: "2029-01-02", time: "23:30" });
    expect(Date.parse(start)).toBeGreaterThan(localYearEnd.getTime());
    expect(localDateTimeParts(end)).toMatchObject({ date: "2029-01-03", time: "01:00" });
  });

  it("defines the two fixture projects the AddTask flow selects by name", () => {
    expect(seed.projects.map((project) => [
      project.todoistProjectId,
      project.name,
      project.shared,
      project.canAssignTasks,
      project.isDeleted,
      project.isArchived
    ])).toEqual([
      ["project-test", "Test Shared", true, true, false, false],
      ["project-personal", "Test Personal", false, false, false, false]
    ]);
  });

  it("includes the active shared-project collaborator mirror used by the editor", () => {
    expect(seed.collaborators.map((collaborator) => [
      collaborator.todoistUserId,
      collaborator.fullName,
      collaborator.email,
      collaborator.isDeleted
    ])).toEqual([
      ["user-me", "Me", "me@example.test", false],
      ["user-wife", "Wife", "wife@example.test", false]
    ]);
    expect(seed.collaboratorStates).toEqual([
      expect.objectContaining({
        todoistProjectId: "project-test",
        todoistUserId: "user-me",
        state: "active",
        isDeleted: false
      }),
      expect.objectContaining({
        todoistProjectId: "project-test",
        todoistUserId: "user-wife",
        state: "active",
        isDeleted: false
      })
    ]);
  });

  it("derives ISO dates from the supplied time on every call", () => {
    const later = e2eFixtureSeed(new Date("2026-07-19T12:00:00.000Z"));
    const laterById = new Map(later.tasks.map((task) => [task.id, task.mirror]));

    expect(byId.get("task-start")?.dueDatetime).toBe("2026-07-18T12:00:00.000Z");
    expect(laterById.get("task-start")?.dueDatetime).toBe("2026-07-19T14:00:00.000Z");
    expect(later.nudges.find((nudge) => nudge.id === "task-deadline")?.startedAt).toBe(
      "2026-07-19T11:31:00.000Z"
    );
  });

  it("returns fresh mirror and project objects on every call", () => {
    const first = e2eFixtureSeed(now);
    const firstTask = first.tasks[0];
    const firstProject = first.projects[0];
    if (firstTask === undefined || firstProject === undefined) {
      throw new Error("missing fixture");
    }
    firstTask.mirror.rawJson = { changed: true };
    firstProject.rawJson = { changed: true };

    const second = e2eFixtureSeed(now);
    expect(second.tasks[0]?.mirror.rawJson).toEqual({});
    expect(second.projects[0]?.rawJson).toEqual({});
  });
});
