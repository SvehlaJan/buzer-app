import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TodoistChecklistService, type ChildMutation } from "../../src/services/todoistChecklistService.js";
import { TaskMutationRunner } from "../../src/services/taskMutationRunner.js";
import { TodoistOperationQueue } from "../../src/services/todoistOperationQueue.js";
import { TodoistCommandExecutor } from "../../src/services/todoistCommandExecutor.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("TodoistChecklistService", () => {
  it("adds a direct child with the root project and no child scheduling fields", async () => {
    const { service, adapter, repo } = setup();

    const result = await service.mutate("root", { mutations: [add("draft-1", "  First step ", " Details ")] });

    expect(result).toMatchObject({
      kind: "success",
      snapshot: { authoritative: true }
    });
    expect(adapter.itemAdds).toHaveLength(1);
    expect(adapter.itemAdds[0]).toMatchObject({
      kind: "child",
      parentTodoistTaskId: "todoist-root",
      projectId: "project-1",
      title: "First step",
      description: "Details"
    });
  });

  it("edits a child title and details and deletes it with explicit Todoist commands", async () => {
    const { service, adapter, repo } = setup({ children: [child("one")] });
    const revision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;

    await service.mutate("root", {
      mutations: [{
        type: "edit",
        clientMutationId: "edit-1",
        childId: "child-one",
        expectedChildRevision: revision,
        title: "Renamed",
        description: "New details"
      }]
    });
    const revised = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
    await service.mutate("root", {
      mutations: [{
        type: "delete",
        clientMutationId: "delete-1",
        childId: "child-one",
        expectedChildRevision: revised
      }]
    });

    expect(adapter.itemUpdates[0]).toMatchObject({
      todoistTaskId: "child-one",
      title: "Renamed",
      description: "New details",
      due: { kind: "preserve" }
    });
    expect(adapter.itemDeletes[0]).toMatchObject({ todoistTaskId: "child-one" });
  });

  it("checks and unchecks with dedicated Todoist commands without completing the root", async () => {
    const { service, adapter, repo } = setup({ children: [child("one")] });
    const initial = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
    await service.check("root", {
      type: "check",
      clientMutationId: "check-1",
      childId: "child-one",
      expectedChildRevision: initial
    });
    const afterCheck = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
    await service.uncheck("root", {
      type: "uncheck",
      clientMutationId: "uncheck-1",
      childId: "child-one",
      expectedChildRevision: afterCheck
    });

    expect(adapter.itemCloses).toHaveLength(1);
    expect(adapter.itemUncompletes).toHaveLength(1);
    expect(repo.getById("root")?.completedAt).toBeNull();
  });

  it("accepts an already observed requested checked state without a second Todoist command", async () => {
    const sync = vi.fn(async () => undefined);
    const { service, adapter, repo } = setup({ children: [child("one", { checked: true })], sync });
    const revision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;

    const result = await service.check("root", {
      type: "check",
      clientMutationId: "check-1",
      childId: "child-one",
      expectedChildRevision: revision
    });

    expect(result).toMatchObject({ kind: "success", snapshot: { authoritative: true } });
    expect(adapter.itemCloses).toEqual([]);
    expect(sync).toHaveBeenCalledTimes(1);
  });

  it("rejects a stale child batch before dispatching any Todoist command", async () => {
    const { service, adapter, repo } = setup({ children: [child("one"), child("two")] });
    const current = `r:${repo.getByTodoistTaskId("child-two")?.mirrorRevision}`;

    await expect(service.mutate("root", {
      mutations: [
        { type: "edit", clientMutationId: "stale", childId: "child-one", expectedChildRevision: "r:0", title: "Ignored", description: null },
        { type: "check", clientMutationId: "safe", childId: "child-two", expectedChildRevision: current }
      ]
    })).rejects.toMatchObject({ code: "stale_child", statusCode: 409 });
    expect(adapter.itemCloses).toHaveLength(0);
    expect(adapter.itemUpdates).toHaveLength(0);
  });

  it("rejects a stale occurrence key before dispatching any command", async () => {
    const { service, adapter, repo } = setup({ children: [child("one")] });
    const revision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;

    await expect(service.mutate("root", {
      expectedOccurrenceKey: "obsolete-occurrence",
      mutations: [
        { type: "check", clientMutationId: "check-1", childId: "child-one", expectedChildRevision: revision }
      ]
    })).rejects.toMatchObject({ code: "stale_occurrence", statusCode: 409 });
    expect(adapter.itemCloses).toHaveLength(0);
  });

  it("rejects a child that is not directly under the requested root before dispatch", async () => {
    const { service, repo } = setup({ children: [child("other", { parentTodoistTaskId: "different-root" })] });
    const revision = `r:${repo.getByTodoistTaskId("child-other")?.mirrorRevision}`;

    await expect(service.mutate("root", {
      mutations: [{ type: "check", clientMutationId: "wrong-root", childId: "child-other", expectedChildRevision: revision }]
    })).rejects.toMatchObject({ code: "child_not_under_root", statusCode: 404 });
  });

  it("rejects forbidden client child fields before issuing commands", async () => {
    const { service, adapter } = setup();

    await expect(service.mutate("root", {
      mutations: [{
        type: "add", clientMutationId: "bad", title: "No due", description: null, dueDatetime: "2026-07-15T12:00:00Z"
      } as unknown as ChildMutation]
    })).rejects.toMatchObject({ code: "forbidden_child_field", statusCode: 422 });

    expect(adapter.itemAdds).toEqual([]);
  });

  it.each([
    { type: "add", clientMutationId: "project", title: "No project", description: null, projectId: "project-2" },
    { type: "add", clientMutationId: "parent-camel", title: "No parent", description: null, parentId: "other-root" },
    { type: "add", clientMutationId: "parent-snake", title: "No parent", description: null, parent_id: "other-root" },
    { type: "add", clientMutationId: "parent-todoist", title: "No parent", description: null, parentTodoistTaskId: "other-root" },
    { type: "delete", clientMutationId: "other-variant", childId: "child-one", expectedChildRevision: "r:1", description: null },
    { type: "check", clientMutationId: "unknown", childId: "child-one", expectedChildRevision: "r:1", arbitrary: true }
  ])("rejects every extraneous child mutation field", async (mutation) => {
    const { service, adapter } = setup({ children: [child("one")] });

    await expect(service.mutate("root", { mutations: [mutation as unknown as ChildMutation] })).rejects.toMatchObject({
      code: "forbidden_child_field",
      statusCode: 422
    });

    expect(adapter.itemAdds).toEqual([]);
    expect(adapter.itemUpdates).toEqual([]);
    expect(adapter.itemDeletes).toEqual([]);
    expect(adapter.itemCloses).toEqual([]);
  });

  it("rejects child variants missing their required discriminated-union fields", async () => {
    const { service } = setup();

    await expect(service.mutate("root", {
      mutations: [{
        type: "add", clientMutationId: "missing-description", title: "Required fields only"
      } as unknown as ChildMutation]
    })).rejects.toMatchObject({ code: "forbidden_child_field", statusCode: 422 });
  });

  it("rejects an over-cap batch before adding any children", async () => {
    const { service, adapter } = setup({ children: Array.from({ length: 28 }, (_, index) => child(`seed-${index}`)) });

    await expect(service.mutate("root", {
      mutations: [add("add-1", "Twenty-nine"), add("add-2", "Thirty"), add("add-3", "Too many")]
    })).rejects.toMatchObject({ code: "too_many_children", statusCode: 422 });
    expect(adapter.itemAdds).toHaveLength(0);
  });

  it("rejects a locally invalid batch before any child command is sent", async () => {
    const { service, adapter, repo } = setup({ children: [child("one"), child("two")] });
    const revisionOne = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;
    const revisionTwo = `r:${repo.getByTodoistTaskId("child-two")?.mirrorRevision}`;

    await expect(service.mutate("root", {
      mutations: [
        { type: "edit", clientMutationId: "failed-draft", childId: "child-one", expectedChildRevision: revisionOne, title: " ", description: null },
        { type: "edit", clientMutationId: "saved-draft", childId: "child-two", expectedChildRevision: revisionTwo, title: "Saved", description: null }
      ]
    })).rejects.toMatchObject({ code: "child_title_required", statusCode: 422 });
    expect(adapter.itemUpdates).toEqual([]);
  });

  it.each([
    ["dueDatetime", "2026-07-15T12:00:00Z"],
    ["dueDate", "2026-07-15"],
    ["dueString", "every day"],
    ["dueLang", "en"],
    ["dueTimezone", "Europe/Bratislava"],
    ["isRecurring", true],
    ["durationMinutes", 30],
    ["durationAmount", 30],
    ["durationUnit", "minute"],
    ["assigneeId", "user-self"]
  ] as const)("rejects a direct child with typed %s hierarchy facts", async (field, value) => {
    const { service, repo } = setup();
    repo.upsertTodoistTask({
      id: "invalid-child",
      todoistTaskId: "invalid-child",
      title: "Invalid child",
      description: null,
      projectId: "project-1",
      parentId: "todoist-root",
      childOrder: 0,
      assigneeId: field === "assigneeId" ? (value as string) : null,
      creatorId: null,
      dueDatetime: field === "dueDatetime" ? (value as string) : null,
      dueDate: field === "dueDate" ? (value as string) : null,
      dueString: field === "dueString" ? (value as string) : null,
      dueLang: field === "dueLang" ? (value as string) : null,
      dueTimezone: field === "dueTimezone" ? (value as string) : null,
      isRecurring: field === "isRecurring" ? (value as boolean) : false,
      durationMinutes: field === "durationMinutes" ? (value as number) : null,
      durationAmount: field === "durationAmount" ? (value as number) : null,
      durationUnit: field === "durationUnit" ? (value as string) : null,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T09:00:00Z"
    });

    await expect(service.mutate("root", { mutations: [add("blocked", "Blocked")] })).rejects.toMatchObject({
      code: "unsupported_hierarchy",
      statusCode: 422
    });
  });
});

function setup(input: { children?: ReturnType<typeof child>[]; sync?: () => Promise<void> } = {}) {
  const db = new Database(":memory:");
  migrate(db);
  const repo = new TaskRepository(db);
  repo.upsertTodoistTask({
    id: "root",
    todoistTaskId: "todoist-root",
    title: "Root",
    description: null,
    projectId: "project-1",
    assigneeId: "user-self",
    creatorId: "user-self",
    dueDatetime: "2026-07-15T12:00:00Z",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: "{}",
    updatedAt: "2026-07-15T09:00:00Z"
  });
  repo.initializeOccurrence("root", {
    occurrenceKey: "occurrence-1",
    occurrenceDueDatetime: "2026-07-15T12:00:00Z",
    occurrenceRecurrenceFingerprint: "one-time",
    updatedAt: "2026-07-15T09:00:00Z"
  });

  for (const item of input.children ?? []) {
    repo.createChecklistChild({ ...item, id: `local-${item.todoistTaskId}`, projectId: "project-1", updatedAt: "2026-07-15T09:00:00Z" });
    if (item.checked) repo.setChecklistChildChecked(`local-${item.todoistTaskId}`, true, "2026-07-15T09:30:00Z");
  }

  const sync = async () => {
    for (const close of adapter.itemCloses) {
      repo.setChecklistChildChecked(`local-${close.todoistTaskId}`, true, "2026-07-15T09:30:00Z");
    }
    for (const uncomplete of adapter.itemUncompletes) {
      repo.setChecklistChildChecked(`local-${uncomplete.todoistTaskId}`, false, "2026-07-15T09:30:00Z");
    }
    if (input.sync) await input.sync();
  };

  const adapter = new FakeTodoistAdapter();
  const queue = new TodoistOperationQueue();
  const executor = new TodoistCommandExecutor(adapter);
  const syncService = { syncOnce: sync };
  const snapshotService = () => ({ authoritative: true });
  const runner = new TaskMutationRunner({
    queue,
    executor,
    syncService,
    snapshotService: snapshotService as any
  });

  const service = new TodoistChecklistService(repo, runner, snapshotService as any);

  return { service, adapter, repo, db };
}

function add(clientMutationId: string, title: string, description: string | null = null): Extract<ChildMutation, { type: "add" }> {
  return { type: "add", clientMutationId, title, description };
}

function child(name: string, input: { parentTodoistTaskId?: string; checked?: boolean } = {}) {
  return {
    todoistTaskId: `child-${name}`,
    parentTodoistTaskId: input.parentTodoistTaskId ?? "todoist-root",
    title: `Child ${name}`,
    description: null,
    childOrder: 0,
    checked: input.checked ?? false
  };
}
