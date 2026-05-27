import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TaskActionService } from "../../src/services/taskActionService.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("TaskActionService", () => {
  it("rejects invalid self-assigned created tasks before calling Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, adapter, "user-self");

    await expect(service.create({
      title: "Loose commitment",
      projectId: null,
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
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, adapter, "user-self");

    await expect(service.create({
      title: "Unassigned task",
      projectId: null,
      assigneeId: null,
      dueDatetime: "2026-05-08T12:00:00Z",
      durationMinutes: 30,
      creatorId: "user-self",
      now: "2026-05-08T10:00:00Z"
    })).rejects.toMatchObject({
      code: "task_validation_failed",
      statusCode: 422,
      details: { errors: ["assignee_required_for_created_task"] }
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
    const service = new TaskActionService(db, repo, adapter);

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
    const adapter = new FakeTodoistAdapter();
    const service = new TaskActionService(db, repo, adapter);

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
      projectId: null,
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
});
