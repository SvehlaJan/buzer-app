import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
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
      projects: [],
      tasks: [{
        todoistTaskId: "todoist-1",
        title: "Pick up parcel",
        description: "Bring printed form",
        projectId: null,
        parentId: "todoist-root",
        childOrder: 3,
        assigneeId: "user-self",
        creatorId: "wife",
        dueDate: "2026-05-08T17:00:00Z",
        dueDatetime: "2026-05-08T17:00:00Z",
        dueString: "every day at 17:00",
        dueLang: "en",
        dueTimezone: "Europe/Bratislava",
        isRecurring: true,
        durationAmount: 2,
        durationUnit: "hour",
        durationMinutes: 120,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: "todoist-1" },
        updatedAt: "2026-05-08T10:00:00Z"
      }]
    });

    const service = new TodoistSyncService(db, repo, new ProjectRepository(db), adapter);
    await service.syncOnce();

    expect(repo.getByTodoistTaskId("todoist-1")).toMatchObject({
      title: "Pick up parcel",
      description: "Bring printed form",
      parentId: "todoist-root",
      childOrder: 3,
      dueDate: "2026-05-08T17:00:00Z",
      dueString: "every day at 17:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationAmount: 2,
      durationUnit: "hour",
      durationMinutes: 120
    });
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
      description: null,
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
      description: null,
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
      projects: [],
      tasks: [
        {
          todoistTaskId: "todoist-completed",
          title: "Finish report",
          description: null,
          projectId: null,
          parentId: null,
          childOrder: null,
          assigneeId: "user-self",
          creatorId: "wife",
          dueDate: "2026-05-08T17:00:00Z",
          dueDatetime: "2026-05-08T17:00:00Z",
          dueString: null,
          dueLang: null,
          dueTimezone: null,
          isRecurring: false,
          durationAmount: 20,
          durationUnit: "minute",
          durationMinutes: 20,
          completedAt: "2026-05-08T16:45:00Z",
          deletedAt: null,
          rawJson: { id: "todoist-completed", checked: true },
          updatedAt: "2026-05-08T16:45:00Z"
        },
        {
          todoistTaskId: "todoist-deleted",
          title: "Old errand",
          description: null,
          projectId: null,
          parentId: null,
          childOrder: null,
          assigneeId: "user-self",
          creatorId: "wife",
          dueDate: "2026-05-08T18:00:00Z",
          dueDatetime: "2026-05-08T18:00:00Z",
          dueString: null,
          dueLang: null,
          dueTimezone: null,
          isRecurring: false,
          durationAmount: 30,
          durationUnit: "minute",
          durationMinutes: 30,
          completedAt: null,
          deletedAt: "2026-05-08T16:50:00Z",
          rawJson: { id: "todoist-deleted", is_deleted: true },
          updatedAt: "2026-05-08T16:50:00Z"
        }
      ]
    });

    const service = new TodoistSyncService(db, repo, new ProjectRepository(db), adapter);
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

  it("stores synced projects", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const taskRepo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "project-token",
      tasks: [],
      projects: [{
        todoistProjectId: "project-family",
        name: "Family",
        shared: true,
        canAssignTasks: true,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: { id: "project-family" },
        updatedAt: "2026-06-13T10:00:00Z"
      }]
    });

    const service = new TodoistSyncService(db, taskRepo, projectRepo, adapter);
    await service.syncOnce();

    expect(projectRepo.getActiveByTodoistProjectId("project-family")?.name).toBe("Family");
  });

  it("forces a full sync when the project mirror is empty", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, updated_at)
      values (1, 'existing-incremental-token', '2026-06-15T10:00:00Z')
    `).run();
    const taskRepo = new TaskRepository(db);
    const projectRepo = new ProjectRepository(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "full-sync-token",
      tasks: [],
      projects: [{
        todoistProjectId: "project-family",
        name: "Family",
        shared: true,
        canAssignTasks: true,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: { id: "project-family" },
        updatedAt: "2026-06-15T10:01:00Z"
      }]
    });

    const service = new TodoistSyncService(db, taskRepo, projectRepo, adapter);
    await service.syncOnce();

    expect(adapter.syncTokens).toEqual(["*"]);
    expect(projectRepo.listActive().map((project) => project.todoistProjectId)).toEqual(["project-family"]);
  });
});
