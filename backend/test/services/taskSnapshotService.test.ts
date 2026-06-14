import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TaskSnapshotService } from "../../src/services/taskSnapshotService.js";

describe("TaskSnapshotService", () => {
  it("returns validation errors and computed start time", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Prepare lunch",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: "2026-05-08T12:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T08:00:00Z"
    });

    const service = new TaskSnapshotService(repo, "user-self");
    const snapshot = service.getSnapshot("2026-05-08T08:30:00Z");

    expect(snapshot.tasks[0]?.startAt).toBe("2026-05-08T11:30:00.000Z");
    expect(snapshot.tasks[0]?.validationErrors).toEqual([]);
    expect(snapshot.scheduleVersion).toBe(1);
  });

  it("marks assigned tasks without strict fields invalid", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Loose task",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T08:00:00Z"
    });

    const service = new TaskSnapshotService(repo, "user-self");
    const snapshot = service.getSnapshot("2026-05-08T08:30:00Z");

    expect(snapshot.tasks[0]?.state).toBe("invalid");
    expect(snapshot.tasks[0]?.validationErrors).toEqual(["due_datetime_required", "duration_required"]);
  });

  it("returns completed and deleted terminal states ahead of validation errors", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-completed",
      todoistTaskId: "todoist-completed",
      title: "Completed loose task",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: "2026-05-08T11:00:00Z",
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T11:00:00Z"
    });
    repo.upsertTodoistTask({
      id: "task-deleted",
      todoistTaskId: "todoist-deleted",
      title: "Deleted loose task",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: null,
      deletedAt: "2026-05-08T11:05:00Z",
      rawJson: "{}",
      updatedAt: "2026-05-08T11:05:00Z"
    });

    const service = new TaskSnapshotService(repo, "user-self");
    const snapshot = service.getSnapshot("2026-05-08T12:00:00Z");

    expect(snapshot.tasks.map((task) => task.state)).toEqual(["completed", "deleted"]);
    expect(snapshot.tasks.map((task) => task.isCompleted)).toEqual([true, false]);
    expect(snapshot.tasks.map((task) => task.isDeleted)).toEqual([false, true]);
  });

  it("treats companion-owned tasks as assigned to the current user", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-personal",
      todoistTaskId: "todoist-personal",
      title: "Personal strict task",
      projectId: "project-personal",
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z",
      commitmentOwnerId: "user-self"
    });
    const service = new TaskSnapshotService(repo, "user-self");

    const snapshot = service.getSnapshot("2026-06-13T10:00:00Z");

    expect(snapshot.tasks[0]).toMatchObject({
      id: "task-personal",
      isAssignedToUser: true,
      state: "planned",
      validationErrors: []
    });
  });

  it("does not validate unassigned user-created tasks as current-user commitments without an owner", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-unassigned",
      todoistTaskId: "todoist-unassigned",
      title: "Loose personal task",
      projectId: "project-personal",
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: "2026-06-13T12:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z",
      commitmentOwnerId: null
    });
    const service = new TaskSnapshotService(repo, "user-self");

    const snapshot = service.getSnapshot("2026-06-13T10:00:00Z");

    expect(snapshot.tasks[0]).toMatchObject({
      id: "task-unassigned",
      isAssignedToUser: false,
      state: "invalid",
      validationErrors: ["assignee_required_for_created_task"]
    });
  });

  it("does not keep enforcing companion-owned tasks after Todoist assigns someone else", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-reassigned",
      todoistTaskId: "todoist-reassigned",
      title: "Reassigned personal task",
      projectId: "project-personal",
      assigneeId: "someone-else",
      creatorId: "user-self",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-06-13T10:00:00Z",
      commitmentOwnerId: "user-self"
    });
    const service = new TaskSnapshotService(repo, "user-self");

    const snapshot = service.getSnapshot("2026-06-13T10:00:00Z");

    expect(snapshot.tasks[0]).toMatchObject({
      id: "task-reassigned",
      isAssignedToUser: false,
      state: "planned",
      validationErrors: []
    });
  });
});
