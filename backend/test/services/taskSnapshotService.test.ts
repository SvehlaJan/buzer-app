import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { TaskSnapshotService } from "../../src/services/taskSnapshotService.js";

describe("TaskSnapshotService", () => {
  it("blocks typed snapshots until the occurrence mirror contract is ready", () => {
    const db = new Database(":memory:");
    migrate(db);
    const service = new TaskSnapshotService(new TaskRepository(db), new ProjectRepository(db), "user-self", true);

    expect(() => service.getSnapshot("2026-07-15T10:00:00Z")).toThrow(expect.objectContaining({
      code: "sync_initializing",
      statusCode: 503
    }));

    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'ready-token', 2, '2026-07-15T10:00:00Z')
    `).run();
    expect(service.getSnapshot("2026-07-15T10:00:00Z")).toMatchObject({ tasks: [] });
  });

  it("returns validation errors and computed start time", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Prepare lunch",
      description: "Bring printed form",
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

    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self");
    const snapshot = service.getSnapshot("2026-05-08T08:30:00Z");

    expect(snapshot.tasks[0]?.startAt).toBe("2026-05-08T12:00:00.000Z");
    expect(snapshot.tasks[0]?.description).toBe("Bring printed form");
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
      description: null,
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

    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self");
    const snapshot = service.getSnapshot("2026-05-08T08:30:00Z");

    expect(snapshot.tasks[0]?.state).toBe("invalid");
    expect(snapshot.tasks[0]?.validationErrors).toEqual(["due_datetime_required", "duration_required"]);
  });

  it("exposes Todoist date-only due dates without satisfying exact-time validation", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-date-only",
      todoistTaskId: "todoist-date-only",
      title: "Date-only task",
      description: null,
      projectId: null,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDate: "2026-07-13",
      dueDatetime: null,
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{not-used}",
      updatedAt: "2026-07-13T06:00:00Z"
    });

    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self");
    const snapshot = service.getSnapshot("2026-07-13T06:30:00Z");

    expect(snapshot.tasks[0]).toMatchObject({
      id: "task-date-only",
      dueDatetime: null,
      dueDate: "2026-07-13",
      state: "invalid",
      validationErrors: ["due_datetime_required"]
    });
  });

  it("returns completed and deleted terminal states ahead of validation errors", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-completed",
      todoistTaskId: "todoist-completed",
      title: "Completed loose task",
      description: null,
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
      description: null,
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

    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self");
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
      description: null,
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
    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self");

    const snapshot = service.getSnapshot("2026-06-13T10:00:00Z");

    expect(snapshot.tasks[0]).toMatchObject({
      id: "task-personal",
      isAssignedToUser: true,
      state: "planned",
      validationErrors: []
    });
  });

  it("treats unassigned tasks in non-assignable projects as current-user commitments", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
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
      updatedAt: "2026-07-13T06:00:00Z"
    });
    repo.upsertTodoistTask({
      id: "task-personal-unassigned",
      todoistTaskId: "todoist-personal-unassigned",
      title: "Personal task from Todoist",
      description: null,
      projectId: "project-personal",
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: "2026-07-13T12:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-13T06:00:00Z",
      commitmentOwnerId: null
    });
    const service = new TaskSnapshotService(repo, projectRepo, "user-self");

    const snapshot = service.getSnapshot("2026-07-13T06:30:00Z");

    expect(snapshot.tasks[0]).toMatchObject({
      id: "task-personal-unassigned",
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
      description: null,
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
    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self");

    const snapshot = service.getSnapshot("2026-06-13T10:00:00Z");

    expect(snapshot.tasks).toEqual([]);
  });

  it("does not keep enforcing companion-owned tasks after Todoist assigns someone else", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-reassigned",
      todoistTaskId: "todoist-reassigned",
      title: "Reassigned personal task",
      description: null,
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
    const service = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self");

    const snapshot = service.getSnapshot("2026-06-13T10:00:00Z");

    expect(snapshot.tasks).toEqual([]);
  });

  it("projects only owned roots with ordered nested checklist revisions and progress", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask(rootTask({
      description: "User notes\n--- Buzer activity (managed) ---\n2026-07-15 14:05 Europe/Bratislava — Extended +30 min (60 → 90 min)",
      isRecurring: true,
      dueString: "every day at 18:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava"
    }));
    repo.upsertTodoistTask(childTask({ id: "child-b", todoistTaskId: "todoist-child-b", title: "Second", childOrder: 2, completedAt: null }));
    repo.upsertTodoistTask(childTask({ id: "child-a", todoistTaskId: "todoist-child-a", title: "First", childOrder: 1, completedAt: "2026-07-15T10:00:00Z" }));
    db.prepare("update commitment_states set occurrence_key = ?, reconciliation_status = 'pending' where task_id = ?")
      .run("occurrence-opaque", "root");

    const snapshot = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self").getSnapshot("2026-07-15T10:00:00Z");

    expect(snapshot.tasks).toHaveLength(1);
    expect(snapshot.tasks[0]).toMatchObject({
      id: "root",
      description: "User notes",
      taskRevision: "r:1",
      occurrenceKey: "occurrence-opaque",
      reconciliationStatus: "pending",
      recurrence: {
        kind: "supported",
        preset: "daily",
        rule: "every day at 18:00",
        language: "en",
        timeZone: "Europe/Bratislava"
      },
      checkedCount: 1,
      totalCount: 2,
      hierarchyStatus: "valid",
      hierarchyViolation: null,
      activityLogEntries: [{
        timestamp: "2026-07-15 14:05",
        timeZone: "Europe/Bratislava",
        message: "Extended +30 min (60 → 90 min)"
      }],
      checklistItems: [
        { id: "child-a", childRevision: "r:1", checked: true, order: 1 },
        { id: "child-b", childRevision: "r:1", checked: false, order: 2 }
      ]
    });
  });

  it("keeps invalid and unsupported owned roots reviewable while never projecting children as roots", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask(rootTask({ id: "invalid", todoistTaskId: "todoist-invalid", dueDatetime: null, durationMinutes: null }));
    repo.upsertTodoistTask(rootTask({ id: "unsupported", todoistTaskId: "todoist-unsupported" }));
    repo.upsertTodoistTask(childTask({ id: "bad-child", todoistTaskId: "todoist-bad-child", parentId: "todoist-unsupported", dueDatetime: "2026-07-15T19:00:00Z" }));
    repo.upsertTodoistTask(childTask({ id: "orphan-child", todoistTaskId: "todoist-orphan-child", parentId: "missing-root" }));

    const snapshot = new TaskSnapshotService(repo, new ProjectRepository(db), "user-self").getSnapshot("2026-07-15T10:00:00Z");

    expect(snapshot.tasks.map((task) => task.id)).toEqual(["unsupported", "invalid"]);
    expect(snapshot.tasks).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: "invalid", state: "invalid", hierarchyStatus: "valid", hierarchyViolation: null }),
      expect.objectContaining({ id: "unsupported", state: "invalid", hierarchyStatus: "unsupported", hierarchyViolation: "child_has_schedule" })
    ]));
  });
});

function rootTask(overrides: Partial<Parameters<TaskRepository["upsertTodoistTask"]>[0]> = {}) {
  return {
    id: "root",
    todoistTaskId: "todoist-root",
    title: "Root commitment",
    description: null,
    projectId: "project-1",
    assigneeId: "user-self",
    creatorId: "user-self",
    dueDatetime: "2026-07-15T18:00:00Z",
    durationMinutes: 60,
    completedAt: null,
    deletedAt: null,
    rawJson: "{not-used}",
    updatedAt: "2026-07-15T10:00:00Z",
    ...overrides
  };
}

function childTask(overrides: Partial<Parameters<TaskRepository["upsertTodoistTask"]>[0]> = {}) {
  return {
    id: "child",
    todoistTaskId: "todoist-child",
    title: "Checklist item",
    description: "details",
    projectId: "project-1",
    parentId: "todoist-root",
    childOrder: 1,
    assigneeId: null,
    creatorId: "user-self",
    dueDatetime: null,
    durationMinutes: null,
    completedAt: null,
    deletedAt: null,
    rawJson: "{not-used}",
    updatedAt: "2026-07-15T10:00:00Z",
    ...overrides
  };
}
