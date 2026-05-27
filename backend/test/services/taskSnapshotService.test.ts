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
});
