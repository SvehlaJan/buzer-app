import { describe, expect, it } from "vitest";
import { validateTaskStrictness } from "../../src/domain/taskValidation.js";

describe("validateTaskStrictness", () => {
  const currentUserId = "user-self";

  it("requires assignee for tasks created by the user", () => {
    const result = validateTaskStrictness({
      title: "Buy batteries",
      creatorId: currentUserId,
      assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      currentUserId
    });

    expect(result.valid).toBe(false);
    expect(result.errors).toContain("assignee_required_for_created_task");
  });

  it("requires deadline and duration for tasks assigned to the user", () => {
    const result = validateTaskStrictness({
      title: "Fix router",
      creatorId: "wife",
      assigneeId: currentUserId,
      dueDatetime: null,
      durationMinutes: null,
      currentUserId
    });

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual(["due_datetime_required", "duration_required"]);
  });

  it("allows tasks assigned to someone else without user commitment fields", () => {
    const result = validateTaskStrictness({
      title: "Call school",
      creatorId: currentUserId,
      assigneeId: "wife",
      dueDatetime: null,
      durationMinutes: null,
      currentUserId
    });

    expect(result.valid).toBe(true);
    expect(result.errors).toEqual([]);
  });
});
