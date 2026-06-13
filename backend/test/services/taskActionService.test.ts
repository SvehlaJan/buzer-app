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
      now: "2026-05-08T17:10:00Z"
    });
    await service.done("task-1", "2026-05-08T19:00:00Z");
    const created = await service.create({
      title: "New task",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-05-09T12:00:00Z",
      durationMinutes: 30,
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
      durationMinutes: 45
    });
    expect(created.todoistTaskId).toBe("created-1");
    expect(repo.getByTodoistTaskId("created-1")?.title).toBe("New task");
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
      durationMinutes: 30
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
});
