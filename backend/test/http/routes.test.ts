import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { migrate } from "../../src/db/migrations.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { buildServer } from "../../src/server.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("HTTP routes", () => {
  it("returns database health without auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({ method: "GET", url: "/health" });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      ok: true,
      service: "buzer-backend",
      checks: { database: "ok" }
    });
  });

  it("returns WhatsApp health when escalation service is configured", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    const sender = new DryRunSender();
    const escalationService = new EscalationService(db, repo, sender, "wife-chat");
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: new FakeTodoistAdapter(),
      escalationService
    });

    const response = await app.inject({ method: "GET", url: "/health" });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      ok: true,
      checks: {
        database: "ok",
        whatsapp: {
          connected: true,
          detail: "dry-run"
        }
      }
    });
  });

  it("rejects missing auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({ method: "GET", url: "/tasks/snapshot" });
    await app.close();

    expect(response.statusCode).toBe(401);
  });

  it("returns task snapshot with auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ tasks: [] });
  });

  it("returns active projects with auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
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
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({
      method: "GET",
      url: "/projects",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      projects: [{
        id: "project-family",
        name: "Family",
        canAssignTasks: true,
        shared: true,
        isInboxProject: false
      }]
    });
  });

  it("handles task action routes and device check-in", async () => {
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
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });
    const auth = { authorization: "Bearer secret" };

    const start = await app.inject({ method: "POST", url: "/tasks/task-1/start", headers: auth });
    const extend = await app.inject({ method: "POST", url: "/tasks/task-1/extend", headers: auth });
    const checkIn = await app.inject({
      method: "POST",
      url: "/device/check-in",
      headers: auth,
      payload: {
        deviceId: "android-1",
        appVersion: "0.1.0-dev",
        permissions: { exactAlarm: "granted" }
      }
    });
    await app.close();

    expect(start.statusCode).toBe(200);
    expect(extend.statusCode).toBe(200);
    expect(checkIn.statusCode).toBe(200);
    expect(adapter.reschedules).toHaveLength(1);
  });

  it("rejects invalid self-assigned task creation without calling Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
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
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks",
      headers: { authorization: "Bearer secret" },
      payload: {
        title: "Loose commitment",
        projectId: "project-family",
        assigneeId: "user-self",
        dueDatetime: null,
        durationMinutes: null
      }
    });
    await app.close();

    expect(response.statusCode).toBe(422);
    expect(response.json()).toMatchObject({
      code: "task_validation_failed",
      details: { errors: ["due_datetime_required", "duration_required"] }
    });
    expect(adapter.createdTasks).toEqual([]);
  });

  it("accepts omitted assignee for non-assignable project creation", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
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
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks",
      headers: { authorization: "Bearer secret" },
      payload: {
        title: "Personal strict task",
        projectId: "project-personal",
        dueDatetime: "2026-06-13T12:00:00Z",
        durationMinutes: 30
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(adapter.createdTasks[0]?.assigneeId).toBeNull();
  });

  it("passes trimmed create descriptions to Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
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
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks",
      headers: { authorization: "Bearer secret" },
      payload: {
        title: "Family errand",
        projectId: "project-family",
        assigneeId: "user-self",
        dueDatetime: "2026-06-13T12:00:00Z",
        durationMinutes: 30,
        description: "  Gate code: 2468  "
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(adapter.createdTasks[0]?.description).toBe("Gate code: 2468");
  });

  it("rejects blank create descriptions", async () => {
    const db = new Database(":memory:");
    migrate(db);
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
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks",
      headers: { authorization: "Bearer secret" },
      payload: {
        title: "Family errand",
        projectId: "project-family",
        assigneeId: "user-self",
        dueDatetime: "2026-06-13T12:00:00Z",
        durationMinutes: 30,
        description: "   "
      }
    });
    await app.close();

    expect(response.statusCode).toBe(400);
    expect(adapter.createdTasks).toEqual([]);
  });

  it("passes reschedule descriptions through with omitted, null, blank, and set semantics", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Repair task",
      description: "Original details",
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
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });
    const auth = { authorization: "Bearer secret" };

    await app.inject({
      method: "POST",
      url: "/tasks/task-1/reschedule",
      headers: auth,
      payload: { dueDatetime: "2026-05-08T19:00:00Z", durationMinutes: 45 }
    });
    await app.inject({
      method: "POST",
      url: "/tasks/task-1/reschedule",
      headers: auth,
      payload: { dueDatetime: "2026-05-08T20:00:00Z", durationMinutes: 50, description: null }
    });
    await app.inject({
      method: "POST",
      url: "/tasks/task-1/reschedule",
      headers: auth,
      payload: { dueDatetime: "2026-05-08T21:00:00Z", durationMinutes: 55, description: "   " }
    });
    await app.inject({
      method: "POST",
      url: "/tasks/task-1/reschedule",
      headers: auth,
      payload: { dueDatetime: "2026-05-08T22:00:00Z", durationMinutes: 60, description: "  Updated gate code: 1357  " }
    });
    await app.close();

    expect(adapter.reschedules.map((reschedule) => reschedule.description)).toEqual([undefined, null, "", "Updated gate code: 1357"]);
    expect(repo.getByTodoistTaskId("todoist-1")?.description).toBe("Updated gate code: 1357");
  });

  it("updates full task edit payload through update route", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Old title",
      description: null,
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
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-1/update",
      headers: { authorization: "Bearer secret" },
      payload: {
        title: "Edited task",
        description: "  Updated details  ",
        projectId: "project-family",
        assigneeId: "user-self",
        dueDatetime: "2026-05-08T19:00:00Z",
        durationMinutes: 45
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(adapter.updates[0]).toMatchObject({
      title: "Edited task",
      description: "Updated details",
      projectId: "project-family",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T19:00:00Z",
      durationMinutes: 45
    });
  });

  it("sends escalation through postpone route on second start postpone", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Send invoice",
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
    const sender = new DryRunSender();
    const escalationService = new EscalationService(db, repo, sender, "wife-chat");
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: new FakeTodoistAdapter(),
      escalationService
    });
    const auth = { authorization: "Bearer secret" };

    await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth });
    const response = await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ escalationTriggered: true });
    expect(sender.sent).toHaveLength(1);
  });
});
