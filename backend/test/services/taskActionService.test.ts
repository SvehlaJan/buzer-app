import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { TaskActionService } from "../../src/services/taskActionService.js";
import { TaskActionCoordinator } from "../../src/services/taskActionCoordinator.js";
import type { AccountabilitySender, EscalationInput } from "../../src/accountability/accountabilitySender.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

function insertCommittedTask(repo: TaskRepository): void {
  repo.upsertTodoistTask({
    id: "task-1",
    todoistTaskId: "todoist-1",
    title: "Send invoice",
    description: null,
    projectId: null,
    assigneeId: "user-self",
    creatorId: "user-self",
    dueDatetime: "2026-05-08T16:00:00Z",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: "{}",
    updatedAt: "2026-05-08T10:00:00Z"
  });
}

class DeferredSender implements AccountabilitySender {
  readonly sent: EscalationInput[] = [];
  private readonly resolvers: Array<() => void> = [];

  async sendEscalation(input: EscalationInput): Promise<void> {
    this.sent.push(input);
    await new Promise<void>((resolve) => {
      this.resolvers.push(resolve);
    });
  }

  resolveAll(): void {
    for (const resolve of this.resolvers.splice(0)) {
      resolve();
    }
  }

  async getHealth(): Promise<{ connected: boolean; detail: string }> {
    return { connected: true, detail: "deferred" };
  }
}

class FailingSender implements AccountabilitySender {
  sentCount = 0;

  async sendEscalation(): Promise<void> {
    this.sentCount += 1;
    throw new Error("WhatsApp not ready");
  }

  async getHealth(): Promise<{ connected: boolean; detail: string }> {
    return { connected: false, detail: "not ready" };
  }
}

describe("TaskActionService", () => {
  it.each([
    ["add", { type: "add", clientMutationId: "add", title: "Add", description: null }],
    ["edit", { type: "edit", clientMutationId: "edit", childId: "child-1", expectedChildRevision: "r:1", title: "Edit", description: null }],
    ["delete", { type: "delete", clientMutationId: "delete", childId: "child-1", expectedChildRevision: "r:1" }],
    ["check", { type: "check", clientMutationId: "check", childId: "child-1", expectedChildRevision: "r:1" }],
    ["uncheck", { type: "uncheck", clientMutationId: "uncheck", childId: "child-1", expectedChildRevision: "r:1" }]
  ] as const)("rejects metadata-free checklist %s actions before Todoist", async (_type, mutation) => {
    const db = new Database(":memory:");
    migrate(db);
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, new TaskRepository(db), new ProjectRepository(db), adapter);

    await expect(service.mutateChecklist("task-1", { mutations: [mutation] })).rejects.toMatchObject({
      code: "checklist_action_metadata_required",
      statusCode: 503
    });

    expect(adapter.itemAdds).toEqual([]);
    expect(adapter.itemUpdates).toEqual([]);
    expect(adapter.itemDeletes).toEqual([]);
    expect(adapter.itemCloses).toEqual([]);
    expect(adapter.itemUncompletes).toEqual([]);
  });

  it("uses injected authoritative synchronization and snapshots for focused checklist actions", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.createChecklistChild({
      id: "child-local",
      todoistTaskId: "child-one",
      parentTodoistTaskId: "todoist-1",
      projectId: null,
      title: "First step",
      description: null,
      childOrder: 0,
      updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1",
      occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time",
      updatedAt: "2026-07-15T09:00:00Z"
    });
    const synchronize = vi.fn(async () => undefined);
    const snapshot = vi.fn(() => ({ source: "authoritative-snapshot" }));
    const coordinator = new TaskActionCoordinator({ db, roots: repo, sync: { syncOnce: synchronize } });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(
      db,
      repo,
      new ProjectRepository(db),
      adapter,
      "user-self",
      null,
      false,
      { checklist: { coordinator, synchronize, snapshot } }
    );
    const revision = `r:${repo.getByTodoistTaskId("child-one")?.mirrorRevision}`;

    const result = await service.mutateChecklist("task-1", {
      action: { actionId: "focused-check", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1" },
      mutations: [{ type: "check", clientMutationId: "check-one", childId: "child-one", expectedChildRevision: revision }]
    });

    expect(synchronize).toHaveBeenCalledTimes(2);
    expect(adapter.itemCloses).toHaveLength(1);
    expect(result.snapshot).toEqual({ source: "authoritative-snapshot" });
  });

  it("blocks mutations until the occurrence mirror contract is ready", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const service = new TaskActionService(
      db,
      new TaskRepository(db),
      new ProjectRepository(db),
      new FakeTodoistAdapter(),
      "user-self",
      null,
      true
    );

    await expect(service.start("task-1", "2026-07-15T10:00:00Z")).rejects.toMatchObject({
      code: "sync_initializing",
      statusCode: 503
    });
  });

  it("rejects invalid self-assigned created tasks before calling Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, projectRepo, adapter, "user-self");

    await expect(service.create({
      title: "Loose commitment",
      description: null,
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: null,
      durationMinutes: null,
      creatorId: "user-self",
      now: "2026-05-08T10:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      statusCode: 422
    });

    expect(adapter.createdTasks).toEqual([]);
  });

  it("rejects created tasks without assignment before calling Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, projectRepo, adapter, "user-self");

    await expect(service.create({
      title: "Unassigned task",
      description: null,
      projectId: "project-family",
      assigneeId: null,
      dueDatetime: "2026-05-08T12:00:00Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-05-08T10:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      statusCode: 422,
      details: { errors: ["assignee_required_for_assignable_project"] }
    });

    expect(adapter.createdTasks).toEqual([]);
  });

  it("builds a backend-owned daily recurrence for a new root commitment", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const synchronize = vi.fn(async () => undefined);
    const coordinator = new TaskActionCoordinator({ db, roots: repo, sync: { syncOnce: synchronize } });
    const service = new TaskActionService(db, repo, projects, adapter, "user-self", null, false, {
      rootActions: { coordinator, synchronize, snapshot: () => ({ source: "snapshot" }) }
    });

    await service.create({
      title: "Daily review",
      description: "Keep it brief",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-07-16T08:30:00Z",
      durationMinutes: 30,
      recurrencePreset: "daily",
      timeZone: "Europe/Bratislava",
      checklist: [],
      actionId: "create-daily",
      deviceId: "phone-a",
      creatorId: "user-self",
      now: "2026-07-15T09:00:00Z"
    });

    expect(adapter.itemAdds).toEqual([expect.objectContaining({
      kind: "root",
      title: "Daily review",
      due: {
        kind: "replace",
        datetime: "2026-07-16T08:30:00Z",
        string: "every day at 10:30",
        lang: "en",
        timezone: "Europe/Bratislava"
      },
      duration: { amount: 30, unit: "minute" }
    })]);
    expect(adapter.createdTasks).toEqual([]);
  });

  it("reuses an action-aware root create command UUID and temp ID after an ambiguous send", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-family", name: "Family", shared: true, canAssignTasks: true,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    class TimeoutOnceAdapter extends FakeTodoistAdapter {
      timeout = true;

      override async itemAdd(input: Parameters<FakeTodoistAdapter["itemAdd"]>[0]) {
        if (this.timeout) {
          this.itemAdds.push(input);
          throw new Error("ambiguous create timeout");
        }
        return super.itemAdd(input);
      }
    }
    const adapter = new TimeoutOnceAdapter();
    const synchronize = vi.fn(async () => undefined);
    const coordinator = new TaskActionCoordinator({ db, roots: repo, sync: { syncOnce: synchronize } });
    const service = new TaskActionService(db, repo, projects, adapter, "user-self", null, false, {
      rootActions: { coordinator, synchronize, snapshot: () => ({ source: "snapshot" }) }
    });
    const input = {
      title: "Daily review", description: null, projectId: "project-family", assigneeId: "user-self",
      dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30, recurrencePreset: "daily" as const,
      timeZone: "Europe/Bratislava", checklist: [], actionId: "create-retry", deviceId: "phone-a",
      creatorId: "user-self", now: "2026-07-15T09:00:00Z"
    };

    await expect(service.create(input)).rejects.toThrow("ambiguous create timeout");
    adapter.timeout = false;
    await service.create(input);

    expect(adapter.itemAdds).toHaveLength(2);
    expect(adapter.itemAdds[1]).toMatchObject({
      commandUuid: adapter.itemAdds[0]?.commandUuid,
      tempId: adapter.itemAdds[0]?.tempId
    });
    expect(db.prepare(`select temp_id as tempId from task_action_commands where action_id = ? and step_key = 'item_add'`)
      .get("create-retry")).toMatchObject({ tempId: adapter.itemAdds[0]?.tempId });
  });

  it("replays an interrupted action-aware checklist create without skipping its unfinished child", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-family", name: "Family", shared: true, canAssignTasks: true,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    class ChildTimeoutOnceAdapter extends FakeTodoistAdapter {
      timeoutChild = true;

      override async itemAdd(input: Parameters<FakeTodoistAdapter["itemAdd"]>[0]) {
        if (input.kind === "child" && this.timeoutChild) {
          this.itemAdds.push(input);
          throw new Error("ambiguous child create timeout");
        }
        return super.itemAdd(input);
      }
    }
    const adapter = new ChildTimeoutOnceAdapter();
    const synchronize = vi.fn(async () => undefined);
    const coordinator = new TaskActionCoordinator({ db, roots: repo, sync: { syncOnce: synchronize } });
    const service = new TaskActionService(db, repo, projects, adapter, "user-self", null, false, {
      rootActions: { coordinator, synchronize, snapshot: () => ({ source: "snapshot" }) }
    });
    const input = {
      title: "Daily review", description: null, projectId: "project-family", assigneeId: "user-self",
      dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30, recurrencePreset: "daily" as const,
      timeZone: "Europe/Bratislava", checklist: [{ title: "Review agenda" }], actionId: "create-child-retry",
      deviceId: "phone-a", creatorId: "user-self", now: "2026-07-15T09:00:00Z"
    };

    await expect(service.create(input)).rejects.toThrow("ambiguous child create timeout");
    adapter.timeoutChild = false;
    await service.create(input);

    expect(adapter.itemAdds.filter((item) => item.kind === "root")).toHaveLength(1);
    const childAdds = adapter.itemAdds.filter((item) => item.kind === "child");
    expect(childAdds).toHaveLength(2);
    expect(childAdds[1]).toMatchObject({
      commandUuid: childAdds[0]?.commandUuid,
      tempId: childAdds[0]?.tempId
    });
    expect(repo.listAll().filter((task) => task.parentId !== null)).toHaveLength(1);
  });

  it("extends a one-time commitment without moving its Todoist block start", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Exercise",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-05-08T18:00:00Z",
      durationMinutes: 60,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, new ProjectRepository(db), adapter);

    await service.extend("task-1", "2026-05-08T18:00:00Z");

    expect(adapter.itemUpdates).toEqual([{
      commandUuid: expect.any(String),
      todoistTaskId: "todoist-1",
      due: { kind: "preserve" },
      assignment: { kind: "preserve" },
      duration: { amount: 90, unit: "minute" },
      description: [
        "--- Buzer activity (managed) ---",
        "2026-05-08 18:00 UTC — Extended +30 min (60 → 90 min)"
      ].join("\n")
    }]);
    expect(repo.getById("task-1")?.dueDatetime).toBe("2026-05-08T18:00:00Z");
    expect(repo.getById("task-1")?.durationMinutes).toBe(90);
    expect(repo.getById("task-1")?.deadlineExtensionCount).toBe(1);
  });

  it("reschedules a supported recurring commitment with its recurrence and rotates the occurrence", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Daily review", description: null,
      projectId: null, assigneeId: "user-self", creatorId: "user-self",
      dueDate: "2026-07-16T08:30:00Z", dueDatetime: "2026-07-16T08:30:00Z",
      dueString: "every day at 10:30", dueLang: "en", dueTimezone: "Europe/Bratislava", isRecurring: true,
      durationAmount: 30, durationUnit: "minute", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "old-occurrence", occurrenceDueDatetime: "2026-07-16T08:30:00Z",
      occurrenceRecurrenceFingerprint: JSON.stringify({ isRecurring: true, dueString: "every day at 10:30", dueLang: "en", dueTimezone: "Europe/Bratislava" }),
      updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.updateCommitmentState("task-1", { state: "started", startedAt: "2026-07-15T09:00:00Z", updatedAt: "2026-07-15T09:01:00Z" });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, new ProjectRepository(db), adapter, "user-self");

    await service.reschedule("task-1", {
      dueDatetime: "2026-07-17T08:30:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z"
    });

    expect(adapter.itemUpdates).toEqual([expect.objectContaining({
      todoistTaskId: "todoist-1",
      due: {
        kind: "replace", datetime: "2026-07-17T08:30:00Z", string: "every day at 10:30", lang: "en", timezone: "Europe/Bratislava"
      },
      duration: { amount: 30, unit: "minute" }
    })]);
    expect(repo.getById("task-1")).toMatchObject({ state: "planned", occurrenceDueDatetime: "2026-07-17T08:30:00Z" });
    expect(repo.getById("task-1")?.occurrenceKey).not.toBe("old-occurrence");
  });

  it("does not close a root while one direct checklist child remains incomplete", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.createChecklistChild({
      id: "child-local", todoistTaskId: "child-1", parentTodoistTaskId: "todoist-1", projectId: null,
      title: "Prepare invoice", description: null, childOrder: 0, updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, new ProjectRepository(db), adapter, "user-self");

    await expect(service.done("task-1", "2026-07-15T10:00:00Z")).rejects.toMatchObject({ code: "children_incomplete" });
    expect(adapter.itemCloses).toEqual([]);
    expect(adapter.recurringCompletions).toEqual([]);
  });

  it("starts, completes, postpones, reschedules, and creates tasks", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Exercise",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-05-08T18:00:00Z",
      durationMinutes: 60,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, projectRepo, adapter);

    await service.start("task-1", "2026-05-08T17:00:00Z");
    await service.postponeStart("task-1", "2026-05-08T17:05:00Z");
    await service.reschedule("task-1", {
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45,
      description: "Updated gate code: 1357",
      now: "2026-05-08T17:10:00Z"
    });
    await service.done("task-1", "2026-05-08T19:00:00Z");
    const created = await service.create({
      title: "New task",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-05-09T12:00:00Z",
      durationMinutes: 30,
      description: "Gate code: 2468",
      creatorId: "user-self",
      now: "2026-05-08T19:05:00Z"
    });

    expect(repo.getById("task-1")).toMatchObject({
      state: "completed",
      startedAt: null,
      startDismissCount: 0,
      lastSnoozedAt: null,
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45
    });
    expect(adapter.itemCloses).toEqual([expect.objectContaining({ todoistTaskId: "todoist-1" })]);
    expect(adapter.itemUpdates[0]).toMatchObject({
      todoistTaskId: "todoist-1",
      due: { kind: "replace", datetime: "2026-05-08T19:00:00Z", string: null, lang: null, timezone: null },
      duration: { amount: 45, unit: "minute" },
      description: "Updated gate code: 1357"
    });
    expect(repo.getByTodoistTaskId("todoist-1")?.description).toBe("Updated gate code: 1357");
    expect(adapter.itemAdds[0]).toMatchObject({ kind: "root", description: "Gate code: 2468" });
    expect(created.todoistTaskId).toMatch(/^created-/);
    expect(repo.getByTodoistTaskId(created.todoistTaskId)?.title).toBe("New task");
    expect(repo.getByTodoistTaskId(created.todoistTaskId)?.description).toBe("Gate code: 2468");
  });

  it("updates all editable Todoist fields and resets commitment planning", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Old title",
      description: "Old details",
      projectId: "project-family",
      assigneeId: "user-self",
      creatorId: "user-wife",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, projectRepo, adapter, "user-self");

    await service.update("task-1", {
      title: "Edited task",
      description: " Updated details ",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45,
      now: "2026-05-08T17:10:00Z"
    });

    expect(adapter.itemUpdates[0]).toMatchObject({
      todoistTaskId: "todoist-1",
      title: "Edited task",
      description: "Updated details",
      assignment: { kind: "assign", assigneeId: "user-self" },
      due: { kind: "replace", datetime: "2026-05-08T19:00:00Z", string: null, lang: null, timezone: null },
      duration: { amount: 45, unit: "minute" }
    });
    expect(repo.getById("task-1")).toMatchObject({
      title: "Edited task",
      description: "Updated details",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45,
      state: "planned"
    });
  });

  it("keeps the current occurrence while applying metadata-only title and user-description edits", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Send invoice",
      description: "Old details\n--- Buzer activity (managed) ---\n2026-07-15 09:00 UTC — Extended +30 min (30 → 60 min)",
      projectId: "project-personal", assigneeId: null, creatorId: "user-self", dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30, completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z",
      commitmentOwnerId: "user-self"
    });
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.updateCommitmentState("task-1", { state: "started", startedAt: "2026-07-15T09:00:00Z", updatedAt: "2026-07-15T09:00:00Z" });
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, projects, adapter, "user-self");

    await service.update("task-1", {
      title: "Send the invoice", description: "New details", projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z"
    });

    expect(adapter.itemUpdates[0]?.description).toBe(
      "New details\n--- Buzer activity (managed) ---\n2026-07-15 09:00 UTC — Extended +30 min (30 → 60 min)"
    );
    expect(repo.getById("task-1")).toMatchObject({
      state: "started", startedAt: "2026-07-15T09:00:00Z", occurrenceKey: "occurrence-1"
    });
  });

  it("replays an action-aware project edit without repeating its confirmed move step", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    const projects = new ProjectRepository(db);
    for (const todoistProjectId of ["project-old", "project-new"]) {
      projects.upsertProject({
        todoistProjectId, name: todoistProjectId, shared: true, canAssignTasks: true,
        isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
      });
    }
    repo.updateTaskFields("task-1", {
      title: "Send invoice", description: null, projectId: "project-old", assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, updatedAt: "2026-07-15T09:00:00Z", commitmentOwnerId: null
    });
    class InterruptingAdapter extends FakeTodoistAdapter {
      failUpdate = true;
      override async itemUpdate(input: Parameters<FakeTodoistAdapter["itemUpdate"]>[0]): Promise<void> {
        if (this.failUpdate) throw new Error("interrupted after move");
        return super.itemUpdate(input);
      }
    }
    const adapter = new InterruptingAdapter();
    const synchronize = vi.fn(async () => undefined);
    const coordinator = new TaskActionCoordinator({ db, roots: repo, sync: { syncOnce: synchronize } });
    const service = new TaskActionService(db, repo, projects, adapter, "user-self", null, false, {
      rootActions: { coordinator, synchronize, snapshot: () => ({ source: "snapshot" }) }
    });
    const input = {
      title: "Renamed invoice", description: "", projectId: "project-new", assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "move-edit", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1"
    };

    await expect(service.update("task-1", input)).rejects.toThrow("interrupted after move");
    adapter.failUpdate = false;
    await service.update("task-1", input);

    expect(adapter.itemMoves).toHaveLength(1);
    expect(adapter.itemUpdates).toHaveLength(1);
  });

  it("sends escalation on second start postpone", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    const adapter = new FakeTodoistAdapter();
    const sender = new DryRunSender();
    const escalation = new EscalationService(db, repo, sender, "wife-chat");
    const service = new TaskActionService(db, repo, new ProjectRepository(db), adapter, "", escalation);

    await service.postponeStart("task-1", "2026-05-08T15:00:00Z");
    await service.postponeStart("task-1", "2026-05-08T15:05:00Z");

    const count = db.prepare("select count(*) as count from whatsapp_escalations").get() as { count: number };
    expect(count.count).toBe(1);
    expect(sender.sent).toHaveLength(1);
  });

  it("claims only one escalation when second postpone requests overlap", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    const adapter = new FakeTodoistAdapter();
    const sender = new DeferredSender();
    const escalation = new EscalationService(db, repo, sender, "wife-chat");
    const service = new TaskActionService(db, repo, new ProjectRepository(db), adapter, "", escalation);

    await service.postponeStart("task-1", "2026-05-08T15:00:00Z");
    const first = service.postponeStart("task-1", "2026-05-08T15:05:00Z");
    const second = service.postponeStart("task-1", "2026-05-08T15:06:00Z");
    sender.resolveAll();
    await Promise.all([first, second]);

    const count = db.prepare("select count(*) as count from whatsapp_escalations").get() as { count: number };
    expect(count.count).toBe(1);
    expect(sender.sent).toHaveLength(1);
  });

  it("continues snoozing when escalation send fails and does not retry-spam the task", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    const adapter = new FakeTodoistAdapter();
    const sender = new FailingSender();
    const escalation = new EscalationService(db, repo, sender, "wife-chat");
    const service = new TaskActionService(db, repo, new ProjectRepository(db), adapter, "", escalation);

    await service.postponeStart("task-1", "2026-05-08T15:00:00Z");
    await expect(service.postponeStart("task-1", "2026-05-08T15:05:00Z")).resolves.toEqual({
      snoozedUntil: "2026-05-08T15:10:00.000Z",
      escalationTriggered: true
    });
    await service.postponeStart("task-1", "2026-05-08T15:10:00Z");

    const rows = db.prepare("select status, error_message as errorMessage from whatsapp_escalations").all() as Array<{
      status: string;
      errorMessage: string | null;
    }>;
    expect(rows).toEqual([{ status: "failed", errorMessage: "WhatsApp not ready" }]);
    expect(sender.sentCount).toBe(1);
    expect(repo.getById("task-1")).toMatchObject({
      startDismissCount: 3,
      lastSnoozedAt: "2026-05-08T15:15:00.000Z"
    });
  });

  it("creates a self-owned commitment in a non-assignable project without Todoist assignee", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const taskRepo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-personal",
      name: "Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

    const created = await service.create({
      title: "Personal strict task",
      description: null,
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z"
    });

    expect(adapter.itemAdds[0]).toMatchObject({
      kind: "root",
      title: "Personal strict task",
      projectId: "project-personal",
      assigneeId: null,
      due: { kind: "replace", datetime: "2026-06-13T12:00:00Z", string: null, lang: null, timezone: null },
      duration: { amount: 30, unit: "minute" }
    });
    expect(taskRepo.getById(created.id)).toMatchObject({
      assigneeId: null,
      commitmentOwnerId: "user-self"
    });
  });

  it("requires assignee for assignable projects", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const taskRepo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

    await expect(service.create({
      title: "Shared task",
      description: null,
      projectId: "project-family",
      assigneeId: null,
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      statusCode: 422,
      details: { errors: ["assignee_required_for_assignable_project"] }
    });
  });

  it("rejects assignee for non-assignable projects", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const taskRepo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-personal",
      name: "Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

    await expect(service.create({
      title: "Personal task",
      description: null,
      projectId: "project-personal",
      assigneeId: "user-self",
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      statusCode: 422,
      details: { errors: ["assignee_not_supported_for_project"] }
    });
  });

  it("rejects an invalid time zone and more than 30 checklist drafts before Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const taskRepo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-personal",
      name: "Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

    await expect(service.create({
      title: "Personal strict task",
      description: null,
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      timeZone: "not/a/time-zone",
      checklist: Array.from({ length: 31 }, (_, index) => ({ title: `Step ${index + 1}` })),
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      details: { errors: expect.arrayContaining(["time_zone_invalid", "too_many_children"]) }
    });
    expect(adapter.itemAdds).toEqual([]);
  });

  it("rejects non-positive duration before calling Todoist even when task is assigned away", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const taskRepo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    projectRepo.upsertProject({
      todoistProjectId: "project-family",
      name: "Family",
      shared: true,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

    await expect(service.create({
      title: "Assigned-away task",
      description: null,
      projectId: "project-family",
      assigneeId: "someone-else",
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 0,
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      statusCode: 422,
      details: { errors: ["duration_must_be_positive_minutes"] }
    });

    expect(adapter.createdTasks).toEqual([]);
  });
});
