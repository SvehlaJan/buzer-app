import { describe, expect, it } from "vitest";
import {
  CommitmentPolicy,
  MAX_CHECKLIST_CHILDREN,
  type PolicyTaskRecord
} from "../../src/domain/commitmentPolicy.js";
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
    projectsById: new Map([
      ["project-personal", { canAssignTasks: false }],
      ["project-shared", { canAssignTasks: true }],
      ["proj-1", { canAssignTasks: false }],
      ["proj-2", { canAssignTasks: true }]
    ])
  });
}

function createPolicy() {
  return new CommitmentPolicy({
    currentUserId,
    projectsById: new Map([
      ["proj-1", { canAssignTasks: false }],
      ["proj-2", { canAssignTasks: true }],
      ["project-personal", { canAssignTasks: false }],
      ["project-shared", { canAssignTasks: true }]
    ])
  });
}

function makeRoot(overrides: Partial<PolicyTaskRecord> = {}): PolicyTaskRecord {
  return {
    id: "root",
    todoistTaskId: "todoist-root",
    title: "Prepare lunch",
    projectId: "proj-1",
    parentId: null,
    assigneeId: null,
    creatorId: currentUserId,
    dueDatetime: "2026-08-02T12:00:00Z",
    durationMinutes: 30,
    isRecurring: false,
    commitmentOwnerId: null,
    ...overrides
  };
}

function makeChild(overrides: Partial<PolicyTaskRecord> = {}): PolicyTaskRecord {
  return {
    id: "child",
    todoistTaskId: "todoist-child",
    title: "Checklist item",
    projectId: "proj-1",
    parentId: "todoist-root",
    assigneeId: null,
    creatorId: currentUserId,
    dueDatetime: null,
    durationMinutes: null,
    isRecurring: false,
    commitmentOwnerId: null,
    ...overrides
  };
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

  it("classifies an unassigned root in an assignable project as ROOT_UNASSIGNED without validation errors", () => {
    const root = task({
      id: "unassigned-root",
      todoistTaskId: "todoist-unassigned-root",
      projectId: "project-shared",
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: null,
      durationMinutes: null
    });

    expect(policy().classify(root, [root])).toEqual({
      classification: "ROOT_UNASSIGNED",
      violation: null,
      isCompanionOwned: false,
      validationErrors: []
    });
  });

  it("classifies an unassigned self-created task in a non-assignable project as ROOT_COMMITMENT when strictly valid", () => {
    const root = task({
      id: "personal-root",
      todoistTaskId: "todoist-personal-root",
      projectId: "project-personal",
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: "2026-07-15T12:00:00Z",
      durationMinutes: 30
    });

    expect(policy().classify(root, [root])).toEqual({
      classification: "ROOT_COMMITMENT",
      violation: null,
      isCompanionOwned: true
    });
  });

  it("requires strict validation for unassigned self-created task in a non-assignable project", () => {
    const root = task({
      id: "personal-root-invalid",
      todoistTaskId: "todoist-personal-root-invalid",
      projectId: "project-personal",
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: null,
      durationMinutes: null
    });

    expect(policy().classify(root, [root])).toMatchObject({
      classification: "ROOT_CANDIDATE_INVALID",
      violation: null,
      isCompanionOwned: true,
      validationErrors: ["due_datetime_required", "duration_required"]
    });
  });

  it("remains NOT_OWNED when a root is assigned to another user even in an assignable project", () => {
    const root = task({
      id: "other-user-root",
      todoistTaskId: "todoist-other-user-root",
      projectId: "project-shared",
      assigneeId: "wife",
      creatorId: currentUserId
    });

    expect(policy().classify(root, [root])).toEqual({
      classification: "NOT_OWNED",
      violation: null,
      isCompanionOwned: false
    });
  });

  it("classifies an eligible child under a ROOT_UNASSIGNED parent as CHECKLIST_CHILD", () => {
    const root = task({
      id: "unassigned-root",
      todoistTaskId: "todoist-unassigned-root",
      projectId: "project-shared",
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: null,
      durationMinutes: null
    });
    const child = task({
      id: "child",
      todoistTaskId: "todoist-child",
      projectId: "project-shared",
      parentId: root.todoistTaskId,
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: null,
      durationMinutes: null
    });

    expect(policy().classify(child, [root, child])).toEqual({
      classification: "CHECKLIST_CHILD",
      violation: null,
      isCompanionOwned: false
    });
  });

  it("rejects an unassigned root with unsupported children as UNSUPPORTED_HIERARCHY", () => {
    const root = task({
      id: "unassigned-root",
      todoistTaskId: "todoist-unassigned-root",
      projectId: "project-shared",
      assigneeId: null,
      creatorId: currentUserId,
      dueDatetime: null,
      durationMinutes: null
    });
    const childWithSchedule = task({
      id: "child-scheduled",
      todoistTaskId: "todoist-child-scheduled",
      projectId: "project-shared",
      parentId: root.todoistTaskId,
      assigneeId: null,
      dueDatetime: "2026-07-15T13:00:00Z",
      durationMinutes: null
    });

    expect(policy().classify(root, [root, childWithSchedule])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "child_has_schedule"
    });

    expect(policy().classify(childWithSchedule, [root, childWithSchedule])).toEqual({
      classification: "NOT_OWNED",
      violation: null,
      isCompanionOwned: false
    });
  });

  it("flags child dueString, dueLang, dueTimezone, and durationUnit as unsupported hierarchy", () => {
    const p = createPolicy();
    const root = makeRoot();
    expect(p.classify(root, [root, makeChild({ dueString: "tomorrow" })])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "child_has_schedule"
    });
    expect(p.classify(root, [root, makeChild({ dueLang: "en" })])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "child_has_schedule"
    });
    expect(p.classify(root, [root, makeChild({ dueTimezone: "Europe/Prague" })])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "child_has_schedule"
    });
    expect(p.classify(root, [root, makeChild({ durationUnit: "minute" })])).toMatchObject({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "child_has_duration"
    });
  });

  it("includes root validationErrors on companion-owned UNSUPPORTED_HIERARCHY roots missing schedule", () => {
    const p = createPolicy();
    const root = makeRoot({ dueDatetime: null, durationMinutes: null });
    const child = makeChild({ dueDate: "2026-08-03" });
    expect(p.classify(root, [root, child])).toEqual({
      classification: "UNSUPPORTED_HIERARCHY",
      violation: "child_has_schedule",
      isCompanionOwned: true,
      validationErrors: ["due_datetime_required", "duration_required"]
    });
  });

  it("excludes mismatched-project or dueString children from eligibleCheckedChildTodoistIds", () => {
    const p = createPolicy();
    const root = makeRoot({ projectId: "proj-1" });
    const okChecked = makeChild({ todoistTaskId: "c-ok", projectId: "proj-1", completedAt: "2026-08-02T09:00:00Z" });
    const wrongProj = makeChild({ todoistTaskId: "c-proj", projectId: "proj-2", completedAt: "2026-08-02T09:00:00Z" });
    const dueStr = makeChild({ todoistTaskId: "c-due", projectId: "proj-1", dueString: "daily", completedAt: "2026-08-02T09:00:00Z" });
    expect(p.eligibleCheckedChildTodoistIds(root, [root, okChecked, wrongProj, dueStr])).toEqual(["c-ok"]);
  });

  it("inspects root hierarchy in deterministic childOrder then todoistTaskId order", () => {
    const p = createPolicy();
    const root = makeRoot();
    const laterDuration = makeChild({ id: "c-2", todoistTaskId: "c-2", childOrder: 2, durationUnit: "minute" });
    const earlierSchedule = makeChild({ id: "c-1", todoistTaskId: "c-1", childOrder: 1, dueString: "tomorrow" });
    const inspection = p.inspectRootHierarchy(root, [root, laterDuration, earlierSchedule]);

    expect(MAX_CHECKLIST_CHILDREN).toBe(30);
    expect(inspection.status).toBe("unsupported");
    expect(inspection.violation).toBe("child_has_schedule");
    expect(inspection.directChildren.map((child) => child.todoistTaskId)).toEqual(["c-1", "c-2"]);
  });
});
