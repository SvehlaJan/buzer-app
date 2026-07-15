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

function actionAwareService(
  db: Database.Database,
  repo: TaskRepository,
  projects: ProjectRepository,
  adapter: FakeTodoistAdapter,
  synchronize: () => Promise<void> = async () => undefined,
  escalation: EscalationService | null = null
): TaskActionService {
  const coordinator = new TaskActionCoordinator({ db, roots: repo, sync: { syncOnce: synchronize } });
  return new TaskActionService(db, repo, projects, adapter, "user-self", escalation, false, {
    rootActions: { coordinator, synchronize, snapshot: () => ({ source: "snapshot" }) },
    checklist: { coordinator, synchronize, snapshot: () => ({ source: "snapshot" }) }
  });
}

describe("TaskActionService", () => {
  it("rejects metadata-free root creation before sending Todoist commands", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, new TaskRepository(db), projects, adapter, "user-self");

    await expect(service.create({
      title: "Root", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30, creatorId: "user-self", now: "2026-07-15T09:00:00Z"
    })).rejects.toMatchObject({ code: "task_action_metadata_required", statusCode: 422 });
    expect(adapter.itemAdds).toEqual([]);
  });

  it.each([
    ["start", (service: TaskActionService) => service.start("task-1", "2026-07-15T10:00:00Z")],
    ["postpone", (service: TaskActionService) => service.postponeStart("task-1", "2026-07-15T10:00:00Z")]
  ] as const)("rejects metadata-free %s actions before mutating commitment state", async (_name, invoke) => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    const service = actionAwareService(db, repo, new ProjectRepository(db), new FakeTodoistAdapter());
    const initialScheduleVersion = repo.getById("task-1")?.scheduleVersion;

    await expect(invoke(service)).rejects.toMatchObject({ code: "task_action_metadata_required", statusCode: 422 });
    expect(repo.getById("task-1")).toMatchObject({
      state: "planned", startDismissCount: 0, scheduleVersion: initialScheduleVersion
    });
  });

  it("keeps action-aware create pending without an authoritative mirrored root", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = actionAwareService(db, repo, projects, adapter);

    await expect(service.create({
      title: "Root", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30, creatorId: "user-self", now: "2026-07-15T09:00:00Z",
      actionId: "create-needs-sync", deviceId: "phone-a"
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
    expect(repo.listAll()).toEqual([]);
  });

  it("adopts a sync-reconstructed root after a confirmed create command", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    let syncCalls = 0;
    const service = actionAwareService(db, repo, projects, adapter, async () => {
      syncCalls += 1;
      if (syncCalls !== 3) return;
      const todoistTaskId = `created-${adapter.itemAdds[0]?.tempId}`;
      repo.upsertTodoistTask({
        id: "sync-root", todoistTaskId, title: "Root", description: null, projectId: "project-personal",
        assigneeId: null, creatorId: "user-self", dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30,
        completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z", commitmentOwnerId: "user-self"
      });
    });
    const input = {
      title: "Root", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30, creatorId: "user-self", now: "2026-07-15T09:00:00Z",
      actionId: "create-reconstructed", deviceId: "phone-a"
    };

    await expect(service.create(input)).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
    await expect(service.create(input)).resolves.toEqual({ id: "sync-root", todoistTaskId: `created-${adapter.itemAdds[0]?.tempId}` });
    expect(db.prepare("select root_task_id as rootTaskId from task_action_journal where action_id = ?").get("create-reconstructed"))
      .toEqual({ rootTaskId: "sync-root" });
  });

  it("keeps action-aware reschedule pending without authoritative mirror confirmation", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    const service = actionAwareService(db, repo, new ProjectRepository(db), new FakeTodoistAdapter());

    await expect(service.reschedule("task-1", {
      dueDatetime: "2026-05-08T17:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "reschedule-needs-sync", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1"
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
    expect(repo.getById("task-1")).toMatchObject({ dueDatetime: "2026-05-08T16:00:00Z", occurrenceKey: "occurrence-1" });
  });

  it("keeps action-aware non-move edit pending without authoritative mirror confirmation", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.updateTaskFields("task-1", {
      title: "Send invoice", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, updatedAt: "2026-07-15T09:00:00Z", commitmentOwnerId: "user-self"
    });
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const service = actionAwareService(db, repo, projects, new FakeTodoistAdapter());

    await expect(service.update("task-1", {
      title: "Updated", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "edit-needs-sync", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1",
      expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
    expect(repo.getById("task-1")?.title).toBe("Send invoice");
  });

  it("keeps one-time Done local state unchanged when coordinated confirmation is pending", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    let syncCalls = 0;
    const adapter = new FakeTodoistAdapter();
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter, async () => {
      syncCalls += 1;
      if (syncCalls === 2) throw new Error("sync unavailable");
    });

    await expect(service.done("task-1", "2026-07-15T10:00:00Z", {
      actionId: "done-pending", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1"
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

    expect(adapter.itemCloses).toHaveLength(1);
    expect(repo.getById("task-1")).toMatchObject({
      completedAt: null, state: "planned", occurrenceKey: "occurrence-1", reconciliationStatus: "pending"
    });
  });

  it("rejects Done when a direct child has a non-datetime schedule field", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.createChecklistChild({
      id: "child-local", todoistTaskId: "child-1", parentTodoistTaskId: "todoist-1", projectId: null,
      title: "Prepare invoice", description: null, childOrder: 0, updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.upsertTodoistTask({
      id: "child-local", todoistTaskId: "child-1", title: "Prepare invoice", description: null, projectId: null,
      parentId: "todoist-1", childOrder: 0, assigneeId: null, creatorId: null,
      dueDate: null, dueDatetime: null, dueString: "every day", dueLang: "en", dueTimezone: "Europe/Bratislava", isRecurring: false,
      durationAmount: null, durationUnit: null, durationMinutes: null, completedAt: "2026-07-15T09:30:00Z", deletedAt: null,
      rawJson: "{}", updatedAt: "2026-07-15T09:30:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter);

    await expect(service.done("task-1", "2026-07-15T10:00:00Z", {
      actionId: "done-invalid-child", deviceId: "phone-a", expectedOccurrenceKey: null
    })).rejects.toMatchObject({
      code: "unsupported_hierarchy", statusCode: 422
    });
    expect(adapter.itemCloses).toEqual([]);
  });

  it("preflights recurrence and every checklist draft before creating a root command", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = actionAwareService(db, repo, projects, adapter);

    await expect(service.create({
      title: "Root", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30,
      recurrencePreset: "not-a-preset" as never,
      timeZone: "Europe/Bratislava", checklist: [{ title: "   " }],
      creatorId: "user-self", now: "2026-07-15T09:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      details: { errors: expect.arrayContaining(["recurrence_preset_invalid", "child_title_required"]) }
    });
    expect(adapter.itemAdds).toEqual([]);
  });

  it("rejects an unconfirmed Todoist checklist child instead of silently dropping it", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    class MissingChildIdAdapter extends FakeTodoistAdapter {
      override async itemAdd(input: Parameters<FakeTodoistAdapter["itemAdd"]>[0]) {
        const created = await super.itemAdd(input);
        return input.kind === "child" ? { todoistTaskId: null } : created;
      }
    }
    const adapter = new MissingChildIdAdapter();
    const service = actionAwareService(db, repo, projects, adapter);

    await expect(service.create({
      title: "Root", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-07-16T08:30:00Z", durationMinutes: 30, recurrencePreset: "never",
      timeZone: "Europe/Bratislava", checklist: [{ title: "Child" }], creatorId: "user-self", now: "2026-07-15T09:00:00Z",
      actionId: "create-unconfirmed-child", deviceId: "phone-a"
    })).rejects.toMatchObject({ code: "todoist_child_create_unconfirmed", statusCode: 502 });

    expect(adapter.itemAdds.map((input) => input.kind)).toEqual(["root", "child"]);
  });

  it("does not mutate local extension state when coordinated confirmation is pending", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    let syncCalls = 0;
    const service = actionAwareService(db, repo, new ProjectRepository(db), new FakeTodoistAdapter(), async () => {
      syncCalls += 1;
      if (syncCalls === 2) throw new Error("sync unavailable");
    });

    await expect(service.extend("task-1", "2026-07-15T10:00:00Z", {
      actionId: "extend-pending", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1"
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

    expect(repo.getById("task-1")).toMatchObject({ durationMinutes: 30, deadlineExtensionCount: 0, state: "planned" });
  });

  it("finalizes an authoritatively confirmed extension after mirror confirmation", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    let syncCalls = 0;
    const service = actionAwareService(db, repo, new ProjectRepository(db), new FakeTodoistAdapter(), async () => {
      syncCalls += 1;
      if (syncCalls !== 2) return;
      repo.upsertTodoistTask({
        id: "task-1", todoistTaskId: "todoist-1", title: "Send invoice", description: null, projectId: null,
        assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 60,
        completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
      });
    });
    const action = { actionId: "extend-confirmed", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1" };

    await expect(service.extend("task-1", "2026-07-15T10:00:00Z", action)).resolves.toBeUndefined();

    expect(repo.getById("task-1")).toMatchObject({
      durationMinutes: 60, deadlineExtensionCount: 1, state: "started", scheduleVersion: 2
    });
    expect(syncCalls).toBe(2);
  });

  it("keeps recurring Done pending until synchronization exposes the next occurrence", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Send invoice", description: null, projectId: null,
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-05-08T16:00:00Z",
      dueString: "every day at 16:00", dueLang: "en", dueTimezone: "Europe/Bratislava", isRecurring: true,
      durationMinutes: 30, completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "daily", updatedAt: "2026-07-15T09:00:00Z"
    });
    const service = actionAwareService(db, repo, new ProjectRepository(db), new FakeTodoistAdapter());

    await expect(service.done("task-1", "2026-07-15T10:00:00Z", {
      actionId: "recurring-done", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1"
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
    expect(repo.getById("task-1")).toMatchObject({ occurrenceKey: "occurrence-1", state: "planned" });
  });

  it("completes a recurring Done only after authoritative synchronization rotates the occurrence", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Send invoice", description: null, projectId: null,
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-05-08T16:00:00Z",
      dueString: "every day at 16:00", dueLang: "en", dueTimezone: "Europe/Bratislava", isRecurring: true,
      durationMinutes: 30, completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "daily", updatedAt: "2026-07-15T09:00:00Z"
    });
    let syncCalls = 0;
    const service = actionAwareService(db, repo, new ProjectRepository(db), new FakeTodoistAdapter(), async () => {
      syncCalls += 1;
      if (syncCalls !== 2) return;
      repo.upsertTodoistTask({
        id: "task-1", todoistTaskId: "todoist-1", title: "Send invoice", description: null, projectId: null,
        assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-05-09T16:00:00Z",
        dueString: "every day at 16:00", dueLang: "en", dueTimezone: "Europe/Bratislava", isRecurring: true,
        durationMinutes: 30, completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
      });
      repo.rotateOccurrence("task-1", {
        occurrenceKey: "occurrence-2", occurrenceDueDatetime: "2026-05-09T16:00:00Z",
        occurrenceRecurrenceFingerprint: "daily", updatedAt: "2026-07-15T10:00:00Z"
      });
    });

    await expect(service.done("task-1", "2026-07-15T10:00:00Z", {
      actionId: "recurring-done-confirmed", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1"
    })).resolves.toBeUndefined();
    expect(repo.getById("task-1")).toMatchObject({ occurrenceKey: "occurrence-2", occurrenceDueDatetime: "2026-05-09T16:00:00Z" });
  });

  it("requires revision and occurrence metadata for action-aware edits and occurrence metadata for Done and reschedule", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const service = actionAwareService(db, repo, projects, new FakeTodoistAdapter());

    await expect(service.update("task-1", {
      title: "Updated", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "edit-missing", deviceId: "phone-a"
    })).rejects.toMatchObject({ code: "task_action_metadata_invalid", statusCode: 422 });
    await expect(service.done("task-1", "2026-07-15T10:00:00Z", { actionId: "done-missing", deviceId: "phone-a" }))
      .rejects.toMatchObject({ code: "task_action_metadata_invalid", statusCode: 422 });
    await expect(service.reschedule("task-1", {
      dueDatetime: "2026-05-08T17:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "reschedule-missing", deviceId: "phone-a"
    })).rejects.toMatchObject({ code: "task_action_metadata_invalid", statusCode: 422 });
  });

  it("composes supplied checklist mutations into the durable root edit workflow", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.updateTaskFields("task-1", {
      title: "Send invoice", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, updatedAt: "2026-07-15T09:00:00Z", commitmentOwnerId: "user-self"
    });
    repo.createChecklistChild({
      id: "child-local", todoistTaskId: "child-one", parentTodoistTaskId: "todoist-1", projectId: "project-personal",
      title: "First step", description: null, childOrder: 0, updatedAt: "2026-07-15T09:00:00Z"
    });
    repo.initializeOccurrence("task-1", {
      occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T16:00:00Z",
      occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z"
    });
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-personal", name: "Personal", shared: false, canAssignTasks: false,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T09:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const service = actionAwareService(db, repo, projects, adapter);

    await expect(service.update("task-1", {
      title: "Updated", description: null, projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "edit-with-checklist", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1",
      expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`,
      checklistMutations: [{ type: "check", clientMutationId: "check-child", childId: "child-one", expectedChildRevision: "r:1" }]
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
    expect(adapter.itemCloses).toHaveLength(0);
  });

  it("keeps a coordinated project move pending until the direct child tree is authoritative", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    insertCommittedTask(repo);
    repo.updateTaskFields("task-1", {
      title: "Send invoice", description: null, projectId: "project-old", assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, updatedAt: "2026-07-15T09:00:00Z", commitmentOwnerId: null
    });
    repo.createChecklistChild({
      id: "child-local", todoistTaskId: "child-one", parentTodoistTaskId: "todoist-1", projectId: "project-old",
      title: "First step", description: null, childOrder: 0, updatedAt: "2026-07-15T09:00:00Z"
    });
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
    const service = actionAwareService(db, repo, projects, new FakeTodoistAdapter());

    await expect(service.update("task-1", {
      title: "Send invoice", description: null, projectId: "project-new", assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "move-needs-tree-sync", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1",
      expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });
  });

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

    await expect(service.create({
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
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

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
    await expect(service.create(input)).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

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
    await expect(service.create(input)).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

    expect(adapter.itemAdds.filter((item) => item.kind === "root")).toHaveLength(1);
    const childAdds = adapter.itemAdds.filter((item) => item.kind === "child");
    expect(childAdds).toHaveLength(2);
    expect(childAdds[1]).toMatchObject({
      commandUuid: childAdds[0]?.commandUuid,
      tempId: childAdds[0]?.tempId
    });
    expect(repo.listAll()).toEqual([]);
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
    repo.initializeOccurrence("task-1", { occurrenceKey: "occurrence-1", occurrenceDueDatetime: "2026-05-08T18:00:00Z", occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-07-15T09:00:00Z" });
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter);

    await expect(service.extend("task-1", "2026-05-08T18:00:00Z", { actionId: "extend-command", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1" }))
      .rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

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
    expect(repo.getById("task-1")?.durationMinutes).toBe(60);
    expect(repo.getById("task-1")?.deadlineExtensionCount).toBe(0);
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
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter);

    await expect(service.reschedule("task-1", {
      dueDatetime: "2026-07-17T08:30:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "reschedule-command", deviceId: "phone-a", expectedOccurrenceKey: "old-occurrence"
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

    expect(adapter.itemUpdates).toEqual([expect.objectContaining({
      todoistTaskId: "todoist-1",
      due: {
        kind: "replace", datetime: "2026-07-17T08:30:00Z", string: "every day at 10:30", lang: "en", timezone: "Europe/Bratislava"
      },
      duration: { amount: 30, unit: "minute" }
    })]);
    expect(repo.getById("task-1")).toMatchObject({ state: "started", occurrenceDueDatetime: "2026-07-16T08:30:00Z", occurrenceKey: "old-occurrence" });
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
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter);

    await expect(service.done("task-1", "2026-07-15T10:00:00Z", { actionId: "done-incomplete", deviceId: "phone-a", expectedOccurrenceKey: null })).rejects.toMatchObject({ code: "children_incomplete" });
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
    const service = actionAwareService(db, repo, projectRepo, adapter);

    await service.start("task-1", "2026-05-08T17:00:00Z", { actionId: "start-aggregate", deviceId: "phone-a", expectedOccurrenceKey: null });
    await service.postponeStart("task-1", "2026-05-08T17:05:00Z", { actionId: "postpone-aggregate", deviceId: "phone-a", expectedOccurrenceKey: null });
    expect(repo.getById("task-1")).toMatchObject({
      state: "started",
      startDismissCount: 1
    });
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
    repo.initializeOccurrence("task-1", { occurrenceKey: "occurrence-1", occurrenceDueDatetime: null, occurrenceRecurrenceFingerprint: "one-time", updatedAt: "2026-05-08T10:00:00Z" });
    const service = actionAwareService(db, repo, projectRepo, adapter);

    await expect(service.update("task-1", {
      title: "Edited task",
      description: " Updated details ",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45,
      now: "2026-05-08T17:10:00Z", actionId: "edit-fields", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1", expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

    expect(adapter.itemUpdates[0]).toMatchObject({
      todoistTaskId: "todoist-1",
      title: "Edited task",
      description: "Updated details",
      assignment: { kind: "assign", assigneeId: "user-self" },
      due: { kind: "replace", datetime: "2026-05-08T19:00:00Z", string: null, lang: null, timezone: null },
      duration: { amount: 45, unit: "minute" }
    });
    expect(repo.getById("task-1")?.title).toBe("Old title");
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
    const service = actionAwareService(db, repo, projects, adapter);

    await expect(service.update("task-1", {
      title: "Send the invoice", description: "New details", projectId: "project-personal", assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "edit-metadata", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1", expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

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
    let syncCalls = 0;
    const synchronize = vi.fn(async () => {
      syncCalls += 1;
      if (syncCalls !== 3) return;
      repo.updateTaskFields("task-1", {
        title: "Renamed invoice", description: "", projectId: "project-new", assigneeId: "user-self",
        dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, updatedAt: "2026-07-15T10:00:00Z", commitmentOwnerId: null
      });
    });
    const coordinator = new TaskActionCoordinator({ db, roots: repo, sync: { syncOnce: synchronize } });
    const service = new TaskActionService(db, repo, projects, adapter, "user-self", null, false, {
      rootActions: { coordinator, synchronize, snapshot: () => ({ source: "snapshot" }) }
    });
    const input = {
      title: "Renamed invoice", description: "", projectId: "project-new", assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z", durationMinutes: 30, now: "2026-07-15T10:00:00Z",
      actionId: "move-edit", deviceId: "phone-a", expectedOccurrenceKey: "occurrence-1",
      expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`
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
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter, async () => undefined, escalation);

    await service.postponeStart("task-1", "2026-05-08T15:00:00Z", { actionId: "postpone-one", deviceId: "phone-a", expectedOccurrenceKey: null });
    await service.postponeStart("task-1", "2026-05-08T15:05:00Z", { actionId: "postpone-two", deviceId: "phone-a", expectedOccurrenceKey: null });

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
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter, async () => undefined, escalation);

    await service.postponeStart("task-1", "2026-05-08T15:00:00Z", { actionId: "overlap-first", deviceId: "phone-a", expectedOccurrenceKey: null });
    const first = service.postponeStart("task-1", "2026-05-08T15:05:00Z", { actionId: "overlap-second", deviceId: "phone-a", expectedOccurrenceKey: null });
    const second = service.postponeStart("task-1", "2026-05-08T15:06:00Z", { actionId: "overlap-third", deviceId: "phone-a", expectedOccurrenceKey: null });
    await vi.waitFor(() => expect(sender.sent).toHaveLength(1));
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
    const service = actionAwareService(db, repo, new ProjectRepository(db), adapter, async () => undefined, escalation);

    await service.postponeStart("task-1", "2026-05-08T15:00:00Z", { actionId: "fail-first", deviceId: "phone-a", expectedOccurrenceKey: null });
    await expect(service.postponeStart("task-1", "2026-05-08T15:05:00Z", { actionId: "fail-second", deviceId: "phone-a", expectedOccurrenceKey: null })).resolves.toEqual({
      snoozedUntil: "2026-05-08T15:10:00.000Z",
      escalationTriggered: true
    });
    await service.postponeStart("task-1", "2026-05-08T15:10:00Z", { actionId: "fail-third", deviceId: "phone-a", expectedOccurrenceKey: null });

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
    const service = actionAwareService(db, taskRepo, projectRepo, adapter);

    await expect(service.create({
      title: "Personal strict task",
      description: null,
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z", actionId: "create-personal", deviceId: "phone-a"
    })).rejects.toMatchObject({ code: "reconciliation_pending", statusCode: 202 });

    expect(adapter.itemAdds[0]).toMatchObject({
      kind: "root",
      title: "Personal strict task",
      projectId: "project-personal",
      assigneeId: null,
      due: { kind: "replace", datetime: "2026-06-13T12:00:00Z", string: null, lang: null, timezone: null },
      duration: { amount: 30, unit: "minute" }
    });
    expect(taskRepo.listAll()).toEqual([]);
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
