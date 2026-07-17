import { describe, expect, it } from "vitest";
import { TaskEnforcementPolicy, type EnforceableTask } from "../../src/domain/taskEnforcementPolicy.js";
import { TaskHierarchyProjector } from "../../src/services/taskHierarchyProjector.js";

const currentUserId = "user-self";

function task(overrides: Partial<EnforceableTask> = {}): EnforceableTask {
  return {
    id: "root",
    todoistTaskId: "todoist-root",
    title: "Prepare lunch",
    projectId: "project-personal",
    parentId: null,
    childOrder: null,
    assigneeId: currentUserId,
    creatorId: "wife",
    dueDatetime: "2026-07-15T12:00:00Z",
    durationMinutes: 30,
    isRecurring: false,
    completedAt: null,
    mirrorRevision: 7,
    commitmentOwnerId: null,
    ...overrides
  };
}

function projector() {
  return new TaskHierarchyProjector(new TaskEnforcementPolicy({
    currentUserId,
    projectsById: new Map([["project-personal", { canAssignTasks: false }]])
  }));
}

describe("TaskHierarchyProjector", () => {
  it("returns only root tasks at top level and orders direct children by order then stable id", () => {
    const rootB = task({ id: "root-b", todoistTaskId: "root-b" });
    const rootA = task({ id: "root-a", todoistTaskId: "root-a" });
    const childLater = task({ id: "child-z", todoistTaskId: "child-z", parentId: "root-a", childOrder: 2, assigneeId: null, dueDatetime: null, durationMinutes: null });
    const childEarlierB = task({ id: "child-b", todoistTaskId: "child-b", parentId: "root-a", childOrder: 1, assigneeId: null, dueDatetime: null, durationMinutes: null });
    const childEarlierA = task({ id: "child-a", todoistTaskId: "child-a", parentId: "root-a", childOrder: 1, assigneeId: null, dueDatetime: null, durationMinutes: null });

    const projected = projector().project([rootB, childLater, rootA, childEarlierB, childEarlierA]);

    expect(projected.map((item) => item.root.todoistTaskId)).toEqual(["root-b", "root-a"]);
    expect(projected[1]?.children.map((child) => child.todoistTaskId)).toEqual(["child-a", "child-b", "child-z"]);
  });

  it("derives checklist progress from direct child completion", () => {
    const root = task();
    const complete = task({ id: "complete", todoistTaskId: "complete", parentId: root.todoistTaskId, assigneeId: null, dueDatetime: null, durationMinutes: null, completedAt: "2026-07-15T11:00:00Z" });
    const incomplete = task({ id: "incomplete", todoistTaskId: "incomplete", parentId: root.todoistTaskId, assigneeId: null, dueDatetime: null, durationMinutes: null });

    expect(projector().project([root, complete, incomplete])[0]).toMatchObject({
      checkedCount: 1,
      totalCount: 2,
      classification: "ROOT_COMMITMENT",
      violation: null
    });
  });

  it("omits a deleted invalid child from projected progress", () => {
    const root = task();
    const complete = task({ id: "complete", todoistTaskId: "complete", parentId: root.todoistTaskId, assigneeId: null, dueDatetime: null, durationMinutes: null, completedAt: "2026-07-15T11:00:00Z" });
    const deletedInvalid = task({ id: "deleted", todoistTaskId: "deleted", parentId: root.todoistTaskId, projectId: "project-shared", dueDatetime: "2026-07-15T13:00:00Z", durationMinutes: 10, deletedAt: "2026-07-15T11:05:00Z" });

    expect(projector().project([root, complete, deletedInvalid])[0]).toMatchObject({
      children: [complete],
      checkedCount: 1,
      totalCount: 1,
      classification: "ROOT_COMMITMENT",
      violation: null
    });
  });

  it("detects grandchildren rather than flattening them into root children", () => {
    const root = task();
    const child = task({ id: "child", todoistTaskId: "child", parentId: root.todoistTaskId, assigneeId: null, dueDatetime: null, durationMinutes: null });
    const grandchild = task({ id: "grandchild", todoistTaskId: "grandchild", parentId: child.todoistTaskId, assigneeId: null, dueDatetime: null, durationMinutes: null });

    const projection = projector().project([root, child, grandchild])[0];

    expect(projection?.children.map((item) => item.todoistTaskId)).toEqual(["child"]);
    expect(projection).toMatchObject({ classification: "UNSUPPORTED_HIERARCHY", violation: "nested_child" });
  });

  it("keeps the root revision when only a child's checked state changes", () => {
    const root = task({ mirrorRevision: 9 });
    const child = task({ id: "child", todoistTaskId: "child", parentId: root.todoistTaskId, assigneeId: null, dueDatetime: null, durationMinutes: null, mirrorRevision: 2 });

    const before = projector().project([root, child])[0];
    const after = projector().project([root, { ...child, completedAt: "2026-07-15T11:00:00Z", mirrorRevision: 3 }])[0];

    expect(before?.root.mirrorRevision).toBe(9);
    expect(after?.root.mirrorRevision).toBe(9);
    expect(after?.checkedCount).toBe(1);
  });
});
