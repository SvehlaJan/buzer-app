import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { CollaboratorRepository } from "../../src/repositories/collaboratorRepository.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { OccurrenceReconciler } from "../../src/services/occurrenceReconciler.js";
import { TodoistSyncService } from "../../src/services/todoistSyncService.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("TodoistSyncService", () => {
  it("propagates an optional AbortSignal to the raw Todoist read sync", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const adapter = new FakeTodoistAdapter();
    const syncSpy = vi.spyOn(adapter, "sync");
    const signal = new AbortController().signal;
    const service = new TodoistSyncService(
      db,
      new TaskRepository(db),
      new ProjectRepository(db),
      adapter
    );

    await service.syncOnce({ signal });

    expect(syncSpy).toHaveBeenCalledWith("*", { signal });
  });

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

  it("persists synced collaborator profiles and active project membership with the full mirror contract", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'old-token', 2, '2026-06-13T10:00:00Z')
    `).run();
    const tasks = new TaskRepository(db);
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
      updatedAt: "2026-06-13T10:00:00Z"
    });
    const collaborators = new CollaboratorRepository(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "collaborator-token",
      tasks: [],
      projects: [],
      collaborators: [{
        todoistUserId: "user-wife",
        fullName: "Wife",
        email: "wife@example.test",
        timeZone: "Europe/Warsaw",
        imageId: null,
        isDeleted: false,
        rawJson: { id: "user-wife" },
        updatedAt: "2026-06-13T10:01:00Z"
      }],
      collaboratorStates: [{
        todoistProjectId: "project-family",
        todoistUserId: "user-wife",
        state: "active",
        role: "READ_WRITE",
        isDeleted: false,
        rawJson: { project_id: "project-family", user_id: "user-wife" },
        updatedAt: "2026-06-13T10:01:00Z"
      }]
    });

    await new TodoistSyncService(db, tasks, projects, adapter, "user-self", collaborators).syncOnce();

    expect(adapter.syncTokens).toEqual(["*"]);
    expect(collaborators.listActiveForProject("project-family")).toEqual([
      { todoistUserId: "user-wife", fullName: "Wife", email: "wife@example.test" }
    ]);
    expect(db.prepare(`
      select sync_token as syncToken, mirror_contract_version as mirrorContractVersion
      from todoist_sync_state where id = 1
    `).get()).toEqual({ syncToken: "collaborator-token", mirrorContractVersion: 3 });
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

  it("marks the mirror contract ready only after a successful full sync", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({ syncToken: "full-contract-token", projects: [], tasks: [] });

    await new TodoistSyncService(db, new TaskRepository(db), new ProjectRepository(db), adapter).syncOnce();

    expect(adapter.syncTokens).toEqual(["*"]);
    expect(db.prepare(`
      select sync_token as syncToken, mirror_contract_version as mirrorContractVersion
      from todoist_sync_state where id = 1
    `).get()).toEqual({ syncToken: "full-contract-token", mirrorContractVersion: 3 });
  });

  it("syncOnce performs a raw fetch and apply cycle directly without queue acquisition", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "sync-token-1",
      tasks: [],
      projects: [],
      collaborators: [],
      collaboratorStates: []
    });
    const service = new TodoistSyncService(db, new TaskRepository(db), new ProjectRepository(db), adapter);

    const summary = await service.syncOnce();

    expect(adapter.syncTokens).toEqual(["*"]);
    expect(summary).toEqual({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    expect(service.getLastSyncDiagnostics()).toMatchObject({
      lastOutcome: "success"
    });
    expect(service.getLastSyncDiagnostics().errorMessage).toBeUndefined();
  });

  it("returns a sync summary with aggregate changed counts and bounded identifier lists", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const adapter = new FakeTodoistAdapter();
    const tasks = Array.from({ length: 15 }, (_, i) => ({
      todoistTaskId: `task-${i + 1}`,
      title: `Task ${i + 1}`,
      description: null,
      projectId: "project-1",
      parentId: null,
      childOrder: i,
      assigneeId: null,
      creatorId: "user-self",
      dueDate: "2026-07-15T09:00:00Z",
      dueDatetime: "2026-07-15T09:00:00Z",
      dueString: null,
      dueLang: null,
      dueTimezone: null,
      isRecurring: false,
      durationAmount: 30,
      durationUnit: "minute",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: { id: `task-${i + 1}` },
      updatedAt: "2026-07-15T08:00:00Z"
    }));
    adapter.syncResults.push({
      syncToken: "summary-token",
      tasks,
      projects: [{
        todoistProjectId: "project-1",
        name: "Project 1",
        shared: false,
        canAssignTasks: true,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: { id: "project-1" },
        updatedAt: "2026-07-15T08:00:00Z"
      }],
      collaborators: [],
      collaboratorStates: []
    });
    const service = new TodoistSyncService(db, new TaskRepository(db), new ProjectRepository(db), adapter);

    const summary = await service.syncOnce();

    expect(summary.hasChanges).toBe(true);
    expect(summary.changedTaskCount).toBe(15);
    // Bounded to 10 IDs
    expect(summary.changedTaskIds).toHaveLength(10);
    expect(summary.changedTaskIds).toEqual(tasks.slice(0, 10).map((t) => t.todoistTaskId));
    expect(summary.changedProjectCount).toBe(1);
    expect(summary.changedCollaboratorCount).toBe(0);
  });

  it("rotates state when an authoritative one-time due instance changes", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask({
      id: "local-one-time",
      todoistTaskId: "todoist-one-time",
      title: "Report",
      description: null,
      projectId: "project-1",
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-07-15T09:00:00Z",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:00:00Z"
    });
    const before = tasks.getById("local-one-time");
    db.prepare(`
      update commitment_states
      set state = 'started', started_at = '2026-07-15T08:30:00Z', start_dismiss_count = 2,
          deadline_extension_count = 1, last_snoozed_at = '2026-07-15T08:35:00Z',
          escalation_sent_at = '2026-07-15T08:31:00Z', occurrence_key = 'old-opaque-key',
          occurrence_due_datetime = '2026-07-15T09:00:00Z',
          occurrence_recurrence_fingerprint = '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}'
      where task_id = 'local-one-time'
    `).run();
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "changed-instance-token",
      projects: [],
      tasks: [{
        todoistTaskId: "todoist-one-time",
        title: "Report",
        description: null,
        projectId: "project-1",
        parentId: null,
        childOrder: null,
        assigneeId: "user-self",
        creatorId: "user-self",
        dueDate: "2026-07-15T10:00:00Z",
        dueDatetime: "2026-07-15T10:00:00Z",
        dueString: null,
        dueLang: null,
        dueTimezone: null,
        isRecurring: false,
        durationAmount: 30,
        durationUnit: "minute",
        durationMinutes: 30,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: "todoist-one-time" },
        updatedAt: "2026-07-15T08:40:00Z"
      }]
    });

    await new TodoistSyncService(db, tasks, new ProjectRepository(db), adapter, "user-self").syncOnce();

    expect(tasks.getById("local-one-time")).toMatchObject({
      state: "planned",
      startedAt: null,
      startDismissCount: 0,
      deadlineExtensionCount: 0,
      lastSnoozedAt: null,
      escalationSentAt: null,
      occurrenceDueDatetime: "2026-07-15T10:00:00Z",
      scheduleVersion: (before?.scheduleVersion ?? 0) + 1
    });
    expect(tasks.getById("local-one-time")?.occurrenceKey).not.toBe(before?.occurrenceKey);
  });

  it("rotates occurrence immediately on external recurrence advancement without generating repair commands", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'old-sync-token', 3, '2026-07-15T08:00:00Z')
    `).run();
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-1",
      name: "Project",
      shared: false,
      canAssignTasks: true,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:00:00Z"
    });
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask({
      id: "recurring-root",
      todoistTaskId: "todoist-recurring-root",
      title: "Daily report",
      description: null,
      projectId: "project-1",
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-07-15T09:00:00Z",
      dueString: "every day at 09:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:00:00Z"
    });
    tasks.upsertTodoistTask({
      id: "checked-child",
      todoistTaskId: "todoist-checked-child",
      title: "Checklist step",
      description: null,
      projectId: "project-1",
      parentId: "todoist-recurring-root",
      childOrder: 1,
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: "2026-07-15T08:30:00Z",
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:30:00Z"
    });
    db.prepare(`
      update commitment_states
      set occurrence_key = 'old-recurring-key', occurrence_due_datetime = '2026-07-15T09:00:00Z',
          occurrence_recurrence_fingerprint = '{"isRecurring":true,"dueString":"every day at 09:00","dueLang":"en","dueTimezone":"Europe/Bratislava"}'
      where task_id = 'recurring-root'
    `).run();
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push(
      syncRecurringRoot("2026-07-16T09:00:00Z", "after-advance-token")
    );

    const summary = await new TodoistSyncService(db, tasks, projects, adapter, "user-self").syncOnce();

    // No uncomplete commands are scheduled or sent to Todoist
    expect(adapter.itemUncompletes).toHaveLength(0);
    expect(adapter.syncTokens).toEqual(["old-sync-token"]);

    // Root occurrence is rotated immediately to the new date
    expect(tasks.getById("recurring-root")).toMatchObject({
      occurrenceDueDatetime: "2026-07-16T09:00:00Z",
      state: "planned"
    });
    expect(tasks.getById("recurring-root")?.occurrenceKey).not.toBe("old-recurring-key");

    // The checked child remains checked (observed state accepted without repair)
    expect(tasks.getById("checked-child")?.completedAt).toBe("2026-07-15T08:30:00Z");
    expect(summary.hasChanges).toBe(true);
  });

  it("leaves ownership when a personal project's assignment capability changes without a task item", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'old-token', 3, '2026-07-15T08:00:00Z')
    `).run();
    const projects = new ProjectRepository(db);
    projects.upsertProject({ ...projectMirror("personal-project", false), rawJson: "{}" });
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask({
      ...oneTimeTask({ id: "project-change-root", todoistTaskId: "project-change-root", assigneeId: null }),
      projectId: "personal-project",
      creatorId: "user-self"
    });
    db.prepare(`
      update commitment_states
      set state = 'started', started_at = '2026-07-15T08:30:00Z', occurrence_key = 'personal-key',
          occurrence_due_datetime = '2026-07-15T09:00:00Z',
          occurrence_recurrence_fingerprint = '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}'
      where task_id = 'project-change-root'
    `).run();
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "capability-changed-token",
      projects: [{ ...projectMirror("personal-project", true), updatedAt: "2026-07-15T08:40:00Z" }],
      tasks: []
    });

    await new TodoistSyncService(db, tasks, projects, adapter, "user-self").syncOnce();

    expect(tasks.getById("project-change-root")).toMatchObject({
      state: "planned",
      startedAt: null,
      scheduleVersion: 2
    });
  });

  it("rotates when a task item returns to ownership through a project capability update", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'old-token', 3, '2026-07-15T08:00:00Z')
    `).run();
    const projects = new ProjectRepository(db);
    projects.upsertProject({ ...projectMirror("personal-project", true), rawJson: "{}" });
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask({
      ...oneTimeTask({ id: "project-return-root", todoistTaskId: "project-return-root", assigneeId: null }),
      projectId: "personal-project",
      creatorId: "user-self"
    });
    db.prepare(`
      update commitment_states
      set occurrence_key = 'unowned-project-key', occurrence_due_datetime = '2026-07-15T09:00:00Z',
          occurrence_recurrence_fingerprint = '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}'
      where task_id = 'project-return-root'
    `).run();
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "capability-returned-token",
      projects: [{ ...projectMirror("personal-project", false), updatedAt: "2026-07-15T08:40:00Z" }],
      tasks: [{
        ...syncOneTimeRoot("project-return-root", null),
        projectId: "personal-project",
        creatorId: "user-self"
      }]
    });

    await new TodoistSyncService(db, tasks, projects, adapter, "user-self").syncOnce();

    expect(tasks.getById("project-return-root")).toMatchObject({
      occurrenceDueDatetime: "2026-07-15T09:00:00Z",
      state: "planned"
    });
    expect(tasks.getById("project-return-root")?.occurrenceKey).not.toBe("unowned-project-key");
  });

  it("clears active enforcement state when a claimed companion root is reassigned to another user", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask(oneTimeTask({ id: "owned-root", todoistTaskId: "owned-root", assigneeId: "user-self" }));
    db.prepare(`
      update commitment_states
      set state = 'started', started_at = '2026-07-15T08:30:00Z', start_dismiss_count = 2,
          deadline_extension_count = 1, last_snoozed_at = '2026-07-15T08:35:00Z',
          escalation_sent_at = '2026-07-15T08:31:00Z', occurrence_key = 'owned-key',
          occurrence_due_datetime = '2026-07-15T09:00:00Z',
          occurrence_recurrence_fingerprint = '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}',
          commitment_owner_id = 'user-self'
      where task_id = 'owned-root'
    `).run();
    const before = tasks.getById("owned-root");
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "unassigned-token",
      projects: [],
      tasks: [syncOneTimeRoot("owned-root", "another-user")]
    });

    await new TodoistSyncService(db, tasks, new ProjectRepository(db), adapter, "user-self").syncOnce();

    expect(tasks.getById("owned-root")).toMatchObject({
      state: "planned",
      startedAt: null,
      startDismissCount: 0,
      deadlineExtensionCount: 0,
      lastSnoozedAt: null,
      escalationSentAt: null,
      scheduleVersion: (before?.scheduleVersion ?? 0) + 1
    });
  });

  it("rotates a fresh occurrence when a root returns to the configured user's ownership", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask(oneTimeTask({ id: "returning-root", todoistTaskId: "returning-root", assigneeId: null }));
    db.prepare(`
      update commitment_states
      set occurrence_key = 'unowned-key', occurrence_due_datetime = '2026-07-15T09:00:00Z',
          occurrence_recurrence_fingerprint = '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}'
      where task_id = 'returning-root'
    `).run();
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "returned-token",
      projects: [],
      tasks: [syncOneTimeRoot("returning-root", "user-self")]
    });

    await new TodoistSyncService(db, tasks, new ProjectRepository(db), adapter, "user-self").syncOnce();

    expect(tasks.getById("returning-root")).toMatchObject({
      occurrenceDueDatetime: "2026-07-15T09:00:00Z",
      state: "planned"
    });
    expect(tasks.getById("returning-root")?.occurrenceKey).not.toBe("unowned-key");
  });

  it("initializes a self-created task in a nonassignable personal project without a stored owner", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "personal-project",
      name: "Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:00:00Z"
    });
    const tasks = new TaskRepository(db);
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "personal-token",
      projects: [],
      tasks: [{
        ...syncOneTimeRoot("personal-root", null),
        projectId: "personal-project",
        creatorId: "user-self"
      }]
    });

    await new TodoistSyncService(db, tasks, projects, adapter, "user-self").syncOnce();

    expect(tasks.getByTodoistTaskId("personal-root")).toMatchObject({
      commitmentOwnerId: null,
      occurrenceDueDatetime: "2026-07-15T09:00:00Z"
    });
    expect(tasks.getByTodoistTaskId("personal-root")?.occurrenceKey).not.toBeNull();
  });

  it("excludes mismatched-project, dueString, or durationUnit checked children during occurrence reconciliation", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'old-sync-token', 3, '2026-07-15T08:00:00Z')
    `).run();
    const projects = new ProjectRepository(db);
    projects.upsertProject({ ...projectMirror("project-1", true), rawJson: "{}" });
    projects.upsertProject({ ...projectMirror("project-2", true), rawJson: "{}" });
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask({
      id: "recurring-root",
      todoistTaskId: "todoist-recurring-root",
      title: "Daily report",
      description: null,
      projectId: "project-1",
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDatetime: "2026-07-15T09:00:00Z",
      dueString: "every day at 09:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:00:00Z"
    });
    tasks.upsertTodoistTask({
      id: "child-ok",
      todoistTaskId: "c-ok",
      title: "Step 1",
      description: null,
      projectId: "project-1",
      parentId: "todoist-recurring-root",
      childOrder: 1,
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: "2026-07-15T08:30:00Z",
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:30:00Z"
    });
    tasks.upsertTodoistTask({
      id: "child-proj",
      todoistTaskId: "c-proj",
      title: "Step 2",
      description: null,
      projectId: "project-2",
      parentId: "todoist-recurring-root",
      childOrder: 2,
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: null,
      durationMinutes: null,
      completedAt: "2026-07-15T08:30:00Z",
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:30:00Z"
    });
    tasks.upsertTodoistTask({
      id: "child-due",
      todoistTaskId: "c-due",
      title: "Step 3",
      description: null,
      projectId: "project-1",
      parentId: "todoist-recurring-root",
      childOrder: 3,
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: null,
      dueString: "daily",
      durationMinutes: null,
      completedAt: "2026-07-15T08:30:00Z",
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:30:00Z"
    });
    tasks.upsertTodoistTask({
      id: "child-dur",
      todoistTaskId: "c-dur",
      title: "Step 4",
      description: null,
      projectId: "project-1",
      parentId: "todoist-recurring-root",
      childOrder: 4,
      assigneeId: null,
      creatorId: "user-self",
      dueDatetime: null,
      durationUnit: "minute",
      durationMinutes: null,
      completedAt: "2026-07-15T08:30:00Z",
      deletedAt: null,
      rawJson: "{}",
      updatedAt: "2026-07-15T08:30:00Z"
    });
    db.prepare(`
      update commitment_states
      set occurrence_key = 'old-recurring-key', occurrence_due_datetime = '2026-07-15T09:00:00Z',
          occurrence_recurrence_fingerprint = '{"isRecurring":true,"dueString":"every day at 09:00","dueLang":"en","dueTimezone":"Europe/Bratislava"}'
      where task_id = 'recurring-root'
    `).run();

    const decideSpy = vi.spyOn(OccurrenceReconciler.prototype, "decide");
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push(syncRecurringRoot("2026-07-16T09:00:00Z", "after-advance-token"));

    await new TodoistSyncService(db, tasks, projects, adapter, "user-self").syncOnce();

    expect(decideSpy).toHaveBeenCalledWith(expect.objectContaining({
      checkedChildIds: ["c-ok"]
    }));
  });
});

function oneTimeTask(overrides: Record<string, unknown>) {
  return {
    id: "one-time-local",
    todoistTaskId: "one-time-todoist",
    title: "One time",
    description: null,
    projectId: "project-1",
    assigneeId: "user-self",
    creatorId: "user-self",
    dueDatetime: "2026-07-15T09:00:00Z",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: "{}",
    updatedAt: "2026-07-15T08:00:00Z",
    ...overrides
  };
}

function syncOneTimeRoot(todoistTaskId: string, assigneeId: string | null) {
  return {
    todoistTaskId,
    title: "One time",
    description: null,
    projectId: "project-1",
    parentId: null,
    childOrder: null,
    assigneeId,
    creatorId: "user-self",
    dueDate: "2026-07-15T09:00:00Z",
    dueDatetime: "2026-07-15T09:00:00Z",
    dueString: null,
    dueLang: null,
    dueTimezone: null,
    isRecurring: false,
    durationAmount: 30,
    durationUnit: "minute",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: { id: todoistTaskId },
    updatedAt: "2026-07-15T08:40:00Z"
  };
}

function projectMirror(todoistProjectId: string, canAssignTasks: boolean) {
  return {
    todoistProjectId,
    name: "Personal",
    shared: false,
    canAssignTasks,
    isInboxProject: false,
    isDeleted: false,
    isArchived: false,
    rawJson: {},
    updatedAt: "2026-07-15T08:00:00Z"
  };
}

function syncRecurringRoot(dueDatetime: string, syncToken: string) {
  return {
    syncToken,
    projects: [],
    collaborators: [],
    collaboratorStates: [],
    tasks: [{
      todoistTaskId: "todoist-recurring-root",
      title: "Daily report",
      description: null,
      projectId: "project-1",
      parentId: null,
      childOrder: null,
      assigneeId: "user-self",
      creatorId: "user-self",
      dueDate: dueDatetime,
      dueDatetime,
      dueString: "every day at 09:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationAmount: 30,
      durationUnit: "minute",
      durationMinutes: 30,
      completedAt: null,
      deletedAt: null,
      rawJson: { id: "todoist-recurring-root" },
      updatedAt: "2026-07-15T08:40:00Z"
    }]
  };
}
