import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { TaskActionService } from "../../src/services/taskActionService.js";
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

  it("extends due datetime by exactly 30 minutes", async () => {
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

    expect(adapter.reschedules[0]).toEqual({
      todoistTaskId: "todoist-1",
      dueDatetime: "2026-05-08T18:30:00.000Z",
      durationMinutes: 60
    });
    expect(repo.getById("task-1")?.deadlineExtensionCount).toBe(1);
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
      startedAt: "2026-05-08T17:00:00Z",
      startDismissCount: 1,
      lastSnoozedAt: null,
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45
    });
    expect(adapter.completedTaskIds).toEqual(["todoist-1"]);
    expect(adapter.reschedules[0]).toEqual({
      todoistTaskId: "todoist-1",
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45,
      description: "Updated gate code: 1357"
    });
    expect(repo.getByTodoistTaskId("todoist-1")?.description).toBe("Updated gate code: 1357");
    expect(adapter.createdTasks[0]?.description).toBe("Gate code: 2468");
    expect(created.todoistTaskId).toBe("created-1");
    expect(repo.getByTodoistTaskId("created-1")?.title).toBe("New task");
    expect(repo.getByTodoistTaskId("created-1")?.description).toBe("Gate code: 2468");
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

    expect(adapter.updates[0]).toEqual({
      todoistTaskId: "todoist-1",
      title: "Edited task",
      description: "Updated details",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45
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

    expect(adapter.createdTasks[0]).toEqual({
      title: "Personal strict task",
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      description: null
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

  it("rejects Todoist-created assignee mismatch when expected assignee is null", async () => {
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
    adapter.createResults.push({
      todoistTaskId: "created-1",
      task: {
        todoistTaskId: "created-1",
        title: "Personal strict task",
        description: null,
        projectId: "project-personal",
        parentId: null,
        childOrder: null,
        assigneeId: "someone",
        creatorId: "user-self",
        dueDate: "2026-06-13T12:00:00Z",
        dueDatetime: "2026-06-13T12:00:00Z",
        dueString: null,
        dueLang: null,
        dueTimezone: null,
        isRecurring: false,
        durationAmount: 30,
        durationUnit: "minute",
        durationMinutes: 30,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: "created-1" },
        updatedAt: "2026-06-13T10:00:00Z"
      }
    });
    const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

    await expect(service.create({
      title: "Personal strict task",
      description: null,
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z"
    })).rejects.toMatchObject({
      code: "todoist_create_verification_failed",
      statusCode: 422,
      details: { errors: ["assignee_mismatch"], todoistTaskId: "created-1" }
    });

    expect(taskRepo.getByTodoistTaskId("created-1")).toBeNull();
  });

  it("persists every authoritative created mirror field after equivalent-due verification", async () => {
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
    adapter.createResults.push({
      todoistTaskId: "created-1",
      task: {
        todoistTaskId: "created-1",
        title: "Personal strict task",
        description: null,
        projectId: "project-personal",
        parentId: "unexpected-parent",
        childOrder: 4,
        assigneeId: null,
        creatorId: "user-self",
        dueDate: "2026-06-13T12:00:00Z",
        dueDatetime: "2026-06-13T12:00:00Z",
        dueString: "every weekday at 12:00",
        dueLang: "en",
        dueTimezone: "Europe/Bratislava",
        isRecurring: true,
        durationAmount: 0.5,
        durationUnit: "hour",
        durationMinutes: 30,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: "created-1" },
        updatedAt: "2026-06-13T10:00:00Z"
      }
    });
    const service = new TaskActionService(db, taskRepo, projectRepo, adapter, "user-self");

    const created = await service.create({
      title: "Personal strict task",
      description: null,
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-06-13T12:00:00.927Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-06-13T10:00:00Z"
    });

    expect(taskRepo.getById(created.id)).toMatchObject({
      parentId: "unexpected-parent",
      childOrder: 4,
      dueDate: "2026-06-13T12:00:00Z",
      dueDatetime: "2026-06-13T12:00:00Z",
      dueString: "every weekday at 12:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationAmount: 0.5,
      durationUnit: "hour",
      durationMinutes: 30
    });
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
