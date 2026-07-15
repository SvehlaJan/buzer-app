import { describe, expect, it } from "vitest";
import {
  TaskEnforcementPolicy,
  type EnforceableTask
} from "../../src/domain/taskEnforcementPolicy.js";

const currentUserId = "user-self";

function task(overrides: Partial<EnforceableTask> = {}): EnforceableTask {
  return {
    id: "root",
    todoistTaskId: "todoist-root",
    title: "Prepare lunch",
    projectId: "project-personal",
    parentId: null,
    assigneeId: currentUserId,
    creatorId: "wife",
    dueDatetime: "2026-07-15T12:00:00Z",
    durationMinutes: 30,
    isRecurring: false,
    commitmentOwnerId: null,
    ...overrides
  };
}

function policy() {
  return new TaskEnforcementPolicy({
    currentUserId,
    projectsById: new Map([["project-personal", { canAssignTasks: false }]])
  });
}

describe("TaskEnforcementPolicy", () => {
  it("classifies an eligible owned root as a commitment", () => {
    const root = task();

    expect(policy().classify(root, [root])).toEqual({
      classification: "ROOT_COMMITMENT",
      violation: null,
      isCompanionOwned: false
    });
  });

  it("keeps an owned root with missing commitment fields repairable", () => {
    const root = task({ dueDatetime: null, durationMinutes: null });

    expect(policy().classify(root, [root])).toMatchObject({
      classification: "ROOT_CANDIDATE_INVALID",
      violation: null,
      validationErrors: ["due_datetime_required", "duration_required"]
    });
  });

  it("classifies a schedule-free direct child of an eligible root", () => {
    const root = task();
    const child = task({
      id: "child",
      todoistTaskId: "todoist-child",
      parentId: root.todoistTaskId,
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: null,
      durationMinutes: null
    });

    expect(policy().classify(child, [root, child])).toMatchObject({
      classification: "CHECKLIST_CHILD",
      violation: null
    });
  });

  it("preserves configured commitment ownership for a self-created personal-project task", () => {
    const root = task({
      assigneeId: null,
      creatorId: currentUserId,
      commitmentOwnerId: currentUserId
    });

    expect(policy().classify(root, [root])).toMatchObject({
      classification: "ROOT_COMMITMENT",
      isCompanionOwned: true
    });
  });

  it("normalizes a self-created task in a non-assignable personal project as companion-owned", () => {
    const root = task({ assigneeId: null, creatorId: currentUserId });

    expect(policy().classify(root, [root])).toMatchObject({
      classification: "ROOT_COMMITMENT",
      isCompanionOwned: true
    });
  });

  it("does not own a task assigned to another person", () => {
    const root = task({ assigneeId: "wife" });

    expect(policy().classify(root, [root])).toEqual({
      classification: "NOT_OWNED",
      violation: null,
      isCompanionOwned: false
    });
  });

  it.each([
    ["schedule", { dueDatetime: "2026-07-15T13:00:00Z" }, "child_has_schedule"],
    ["recurrence", { isRecurring: true }, "child_has_recurrence"],
    ["assignment", { assigneeId: currentUserId }, "child_has_assignment"],
    ["duration", { durationMinutes: 5 }, "child_has_duration"]
  ] as const)("rejects a child with %s", (_name, childFields, violation) => {
    const root = task();
    const child = task({
      id: "child",
      todoistTaskId: "todoist-child",
      parentId: root.todoistTaskId,
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: null,
      durationMinutes: null,
      ...childFields
    });

    expect(policy().classify(root, [root, child])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation
    });
  });

  it("rejects a direct child in a different project", () => {
    const root = task();
    const child = task({
      id: "child",
      todoistTaskId: "todoist-child",
      parentId: root.todoistTaskId,
      projectId: "project-shared",
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null
    });

    expect(policy().classify(root, [root, child])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "child_project_mismatch"
    });
  });

  it("rejects a grandchild instead of treating it as a checklist item", () => {
    const root = task();
    const child = task({
      id: "child",
      todoistTaskId: "todoist-child",
      parentId: root.todoistTaskId,
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null
    });
    const grandchild = task({
      id: "grandchild",
      todoistTaskId: "todoist-grandchild",
      parentId: child.todoistTaskId,
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null
    });

    expect(policy().classify(root, [root, child, grandchild])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "nested_child"
    });
  });

  it("rejects a root with more than thirty direct children", () => {
    const root = task();
    const children = Array.from({ length: 31 }, (_, index) => task({
      id: `child-${index}`,
      todoistTaskId: `todoist-child-${index}`,
      parentId: root.todoistTaskId,
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null
    }));

    expect(policy().classify(root, [root, ...children])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "too_many_children"
    });
  });

  it("does not count a deleted thirty-first child", () => {
    const root = task();
    const liveChildren = Array.from({ length: 30 }, (_, index) => task({
      id: `child-${index}`,
      todoistTaskId: `todoist-child-${index}`,
      parentId: root.todoistTaskId,
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null
    }));
    const deletedChild = task({
      id: "deleted-child",
      todoistTaskId: "todoist-deleted-child",
      parentId: root.todoistTaskId,
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null,
      deletedAt: "2026-07-15T11:00:00Z"
    });

    expect(policy().classify(root, [root, ...liveChildren, deletedChild])).toMatchObject({
      classification: "ROOT_COMMITMENT",
      violation: null
    });
  });

  it("ignores a deleted child with invalid checklist fields", () => {
    const root = task();
    const deletedChild = task({
      id: "deleted-child",
      todoistTaskId: "todoist-deleted-child",
      parentId: root.todoistTaskId,
      projectId: "project-shared",
      assigneeId: currentUserId,
      dueDatetime: "2026-07-15T13:00:00Z",
      durationMinutes: 15,
      deletedAt: "2026-07-15T11:00:00Z"
    });

    expect(policy().classify(root, [root, deletedChild])).toMatchObject({
      classification: "ROOT_COMMITMENT",
      violation: null
    });
  });

  it("accepts a valid root tree with thirty direct checklist children", () => {
    const root = task();
    const children = Array.from({ length: 30 }, (_, index) => task({
      id: `child-${index}`,
      todoistTaskId: `todoist-child-${index}`,
      parentId: root.todoistTaskId,
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null
    }));

    expect(policy().classify(root, [root, ...children])).toMatchObject({
      classification: "ROOT_COMMITMENT",
      violation: null
    });
  });
});
