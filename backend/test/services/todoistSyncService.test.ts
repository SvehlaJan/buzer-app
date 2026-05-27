import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TodoistSyncService } from "../../src/services/todoistSyncService.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("TodoistSyncService", () => {
  it("stores synced tasks and updates sync token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "next-token",
      tasks: [{
        todoistTaskId: "todoist-1",
        title: "Pick up parcel",
        projectId: null,
        assigneeId: "user-self",
        creatorId: "wife",
        dueDatetime: "2026-05-08T17:00:00Z",
        durationMinutes: 20,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: "todoist-1" },
        updatedAt: "2026-05-08T10:00:00Z"
      }]
    });

    const service = new TodoistSyncService(db, repo, adapter);
    await service.syncOnce();

    expect(repo.getByTodoistTaskId("todoist-1")?.title).toBe("Pick up parcel");
    const token = db.prepare("select sync_token as syncToken from todoist_sync_state where id = 1").get() as { syncToken: string };
    expect(token.syncToken).toBe("next-token");
  });

  it("moves local commitment state to completed and deleted from Todoist sync", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "completed-local",
      todoistTaskId: "todoist-completed",
      title: "Finish report",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: "2026-05-08T17:00:00Z",
      durationMinutes: 20,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    repo.upsertTodoistTask({
      id: "deleted-local",
      todoistTaskId: "todoist-deleted",
      title: "Old errand",
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDatetime: "2026-05-08T18:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-05-08T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "terminal-token",
      tasks: [
        {
          todoistTaskId: "todoist-completed",
          title: "Finish report",
          projectId: null,
          assigneeId: "user-self",
          creatorId: "wife",
          dueDatetime: "2026-05-08T17:00:00Z",
          durationMinutes: 20,
          completedAt: "2026-05-08T16:45:00Z",
          deletedAt: null,
          rawJson: { id: "todoist-completed", checked: true },
          updatedAt: "2026-05-08T16:45:00Z"
        },
        {
          todoistTaskId: "todoist-deleted",
          title: "Old errand",
          projectId: null,
          assigneeId: "user-self",
          creatorId: "wife",
          dueDatetime: "2026-05-08T18:00:00Z",
          durationMinutes: 30,
          completedAt: null,
          deletedAt: "2026-05-08T16:50:00Z",
          rawJson: { id: "todoist-deleted", is_deleted: true },
          updatedAt: "2026-05-08T16:50:00Z"
        }
      ]
    });

    const service = new TodoistSyncService(db, repo, adapter);
    await service.syncOnce();

    expect(repo.getByTodoistTaskId("todoist-completed")).toMatchObject({
      completedAt: "2026-05-08T16:45:00Z",
      state: "completed"
    });
    expect(repo.getByTodoistTaskId("todoist-deleted")).toMatchObject({
      deletedAt: "2026-05-08T16:50:00Z",
      state: "deleted"
    });
  });
});
