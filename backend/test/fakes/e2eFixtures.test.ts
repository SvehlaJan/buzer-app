import { describe, expect, it } from "vitest";
import { describeRecurrence } from "../../src/domain/taskRecurrence.js";
import { e2eFixtureSeed } from "./e2eFixtures.js";

const now = new Date("2026-07-18T10:00:00.000Z");
const seed = e2eFixtureSeed(now);
const byId = new Map(seed.tasks.map((task) => [task.id, task.mirror]));

describe("e2eFixtureSeed", () => {
  it("defines the 15 fixture roots plus 6 checklist children with pinned Todoist ids", () => {
    const roots = seed.tasks.filter((task) => task.mirror.parentId === null);
    const children = seed.tasks.filter((task) => task.mirror.parentId !== null);

    expect(roots).toHaveLength(15);
    expect(children).toHaveLength(6);
    expect(roots.map((task) => task.id)).toEqual([
      "task-start",
      "task-deadline",
      "task-reschedule",
      "task-start-fail",
      "task-deadline-fail",
      "task-reschedule-fail",
      "task-invalid",
      "task-floating-time",
      "task-one-time-empty",
      "task-one-time-partial",
      "task-recurring-all-checked",
      "task-recurring-extend-disabled",
      "task-custom-recurrence",
      "task-unsupported-hierarchy",
      "task-reconciliation-pending"
    ]);
    expect(byId.get("task-start")?.title).toBe("Start blocker fixture");
    for (const { id, mirror } of seed.tasks) {
      expect(mirror.todoistTaskId).toBe(`todoist-${id}`);
    }
  });

  it("expresses invalid, hierarchy, and recurrence outcomes as Todoist facts", () => {
    expect(byId.get("task-invalid")?.dueDatetime).toBeNull();
    expect(byId.get("task-invalid")?.durationMinutes).toBeNull();
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
      { id: "task-reschedule", kind: "started", startedAt: "2026-07-18T09:31:00.000Z" },
      { id: "task-deadline-fail", kind: "started", startedAt: "2026-07-18T09:31:00.000Z" },
      { id: "task-reschedule-fail", kind: "started", startedAt: "2026-07-18T09:31:00.000Z" },
      { id: "task-reconciliation-pending", kind: "reconciliation_pending" }
    ]);
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

  it("derives ISO dates from the supplied time on every call", () => {
    const later = e2eFixtureSeed(new Date("2026-07-19T12:00:00.000Z"));
    const laterById = new Map(later.tasks.map((task) => [task.id, task.mirror]));

    expect(byId.get("task-start")?.dueDatetime).toBe("2026-07-18T12:00:00.000Z");
    expect(laterById.get("task-start")?.dueDatetime).toBe("2026-07-19T14:00:00.000Z");
    expect(later.nudges.find((nudge) => nudge.id === "task-deadline")?.startedAt).toBe(
      "2026-07-19T11:31:00.000Z"
    );
  });
});
