import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { migrate } from "../../src/db/migrations.js";
import { CollaboratorRepository } from "../../src/repositories/collaboratorRepository.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { buildServer } from "../../src/server.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("HTTP routes", () => {
  it("returns trimmed health without auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({ method: "GET", url: "/health" });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      ok: true,
      service: "buzer-backend",
      timestamp: expect.any(String)
    });
  });

  it("returns WhatsApp health with auth token when escalation service is configured", async () => {
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

    const response = await app.inject({
      method: "GET",
      url: "/health",
      headers: { authorization: "Bearer secret" }
    });
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

  it("trims the 503 health response without auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    db.close();

    const response = await app.inject({ method: "GET", url: "/health" });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toEqual({
      ok: false,
      service: "buzer-backend",
      timestamp: expect.any(String)
    });
  });

  it("includes failure detail in the 503 health response with auth token", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    db.close();

    const response = await app.inject({
      method: "GET",
      url: "/health",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toMatchObject({
      ok: false,
      checks: { database: "failed" },
      message: expect.any(String)
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

  it("authenticates every fixed non-health route before parsing invalid input", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    const fixedRoutes = [
      ["GET", "/tasks/snapshot"], ["GET", "/projects"], ["POST", "/tasks"], ["POST", "/tasks/task-1/start"],
      ["POST", "/tasks/task-1/done"], ["POST", "/tasks/task-1/postpone-start"], ["POST", "/tasks/task-1/extend"],
      ["POST", "/tasks/task-1/update"], ["POST", "/tasks/task-1/checklist"],
      ["POST", "/tasks/task-1/checklist/child-1/update"], ["POST", "/tasks/task-1/checklist/child-1/delete"],
      ["POST", "/tasks/task-1/checklist/child-1/check"], ["POST", "/tasks/task-1/checklist/child-1/uncheck"],
      ["POST", "/review/dismiss"], ["POST", "/device/check-in"]
    ] as const;

    for (const [method, url] of fixedRoutes) {
      const response = await app.inject({ method, url, payload: { unexpected: true } });
      expect(response.statusCode, `${method} ${url}`).toBe(401);
    }
    await app.close();
  });

  it("checks a direct checklist child through the authenticated mutation route and returns the authoritative snapshot", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Root", description: null, projectId: "project-family",
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    repo.upsertTodoistTask({
      id: "child-1", todoistTaskId: "todoist-child-1", title: "Checklist child", description: null, projectId: "project-family",
      parentId: "todoist-1", childOrder: 1, assigneeId: null, creatorId: "user-self", dueDatetime: null, durationMinutes: null,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    db.prepare("update commitment_states set occurrence_key = ?, occurrence_due_datetime = ?, occurrence_recurrence_fingerprint = ? where task_id = ?")
      .run("occurrence-1", "2026-07-15T18:00:00Z", '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}', "task-1");
    const childRevision = `r:${repo.getById("child-1")?.mirrorRevision}`;
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      // Android sends the `ChecklistItem.id` from the authoritative snapshot,
      // which is the mirror-local child ID rather than Todoist's opaque ID.
      method: "POST", url: "/tasks/task-1/checklist/child-1/check", headers: { authorization: "Bearer secret" },
      payload: {
        actionId: "check-child-action", deviceId: "android-1", expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: childRevision
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      actionId: "check-child-action",
      outcome: "completed",
      snapshot: { tasks: [{ id: "task-1", checklistItems: [{ id: "child-1", checked: true }] }] }
    });
    expect(response.json()).not.toHaveProperty("childOutcomes");
    expect(response.json().snapshot.snapshotGeneration).toBeGreaterThan(0);
    expect(adapter.itemCloses).toEqual([
      expect.objectContaining({ todoistTaskId: "todoist-child-1" })
    ]);
  });

  it("blocks typed snapshots until sync initialization finishes while health remains available", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const blocked = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: { authorization: "Bearer secret" }
    });
    expect(blocked.statusCode).toBe(503);
    expect(blocked.json()).toMatchObject({ code: "sync_initializing" });

    const health = await app.inject({ method: "GET", url: "/health" });
    expect(health.statusCode).toBe(200);

    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'ready-token', 2, '2026-07-15T10:00:00Z')
    `).run();
    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ tasks: [] });
    expect(response.json().snapshotGeneration).toBeGreaterThan(0);
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
        isInboxProject: false,
        collaboratorsReady: false,
        collaborators: [{
          id: "user-self",
          displayName: "Me",
          email: null,
          isCurrentUser: true
        }]
      }]
    });
  });

  it("projects only safe project-specific assignee choices after collaborator sync", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'member-ready', 3, '2026-07-22T10:00:00Z')
    `).run();
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
      updatedAt: "2026-07-22T10:00:00Z"
    });
    projects.upsertProject({
      todoistProjectId: "project-personal",
      name: "Personal",
      shared: false,
      canAssignTasks: false,
      isInboxProject: false,
      isDeleted: false,
      isArchived: false,
      rawJson: "{}",
      updatedAt: "2026-07-22T10:00:00Z"
    });
    const collaborators = new CollaboratorRepository(db);
    collaborators.upsertCollaborator({
      todoistUserId: "user-wife",
      fullName: "Wife",
      email: "wife@example.test",
      timeZone: "Europe/Warsaw",
      imageId: "private-avatar-id",
      isDeleted: false,
      rawJson: { private: true },
      updatedAt: "2026-07-22T10:00:00Z"
    });
    collaborators.upsertCollaboratorState({
      todoistProjectId: "project-family",
      todoistUserId: "user-wife",
      state: "active",
      role: "READ_WRITE",
      isDeleted: false,
      rawJson: { private: true },
      updatedAt: "2026-07-22T10:00:00Z"
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
      projects: [
        {
          id: "project-family",
          name: "Family",
          canAssignTasks: true,
          shared: true,
          isInboxProject: false,
          collaboratorsReady: true,
          collaborators: [
            { id: "user-self", displayName: "Me", email: null, isCurrentUser: true },
            { id: "user-wife", displayName: "Wife", email: "wife@example.test", isCurrentUser: false }
          ]
        },
        {
          id: "project-personal",
          name: "Personal",
          canAssignTasks: false,
          shared: false,
          isInboxProject: false,
          collaboratorsReady: true,
          collaborators: [
            { id: "user-self", displayName: "Me", email: null, isCurrentUser: true }
          ]
        }
      ]
    });
  });

  it("handles task action routes and device check-in", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
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
    setOccurrence(db, "task-1", "2026-05-08T18:00:00Z");
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });
    const auth = { authorization: "Bearer secret" };

    const stale = await app.inject({
      method: "POST",
      url: "/tasks/task-1/start",
      headers: auth,
      payload: { ...action("stale"), expectedOccurrenceKey: "obsolete-occurrence" }
    });
    const start = await app.inject({ method: "POST", url: "/tasks/task-1/start", headers: auth, payload: action("start") });
    const startReplay = await app.inject({ method: "POST", url: "/tasks/task-1/start", headers: auth, payload: action("start") });
    const postpone = await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth, payload: action("postpone") });
    const postponeReplay = await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth, payload: action("postpone") });
    const extend = await app.inject({ method: "POST", url: "/tasks/task-1/extend", headers: auth, payload: action("extend", "revision") });
    const extendReplay = await app.inject({ method: "POST", url: "/tasks/task-1/extend", headers: auth, payload: action("extend", "revision") });
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
    expect(startReplay.statusCode).toBe(200);
    expect(postpone.statusCode).toBe(200);
    expect(postponeReplay.statusCode).toBe(200);
    expect(extend.statusCode).toBe(202);
    expect(extendReplay.statusCode).toBe(202);
    expect(checkIn.statusCode).toBe(200);
    expect(start.json()).toMatchObject({ actionId: "action-start", outcome: "completed", snapshot: { tasks: expect.any(Array) } });
    expect(startReplay.json()).toMatchObject({ actionId: "action-start", outcome: "completed" });
    expect(postponeReplay.json()).toMatchObject({ actionId: "action-postpone", outcome: "completed" });
    expect(extend.json()).toMatchObject({ actionId: "action-extend", outcome: "reconciliation_pending", snapshot: { tasks: expect.any(Array) } });
    expect(start.json().snapshot.snapshotGeneration).toBeGreaterThan(0);
    expect(extend.json().snapshot.snapshotGeneration).toBeGreaterThan(0);
    expect(stale.statusCode).toBe(409);
    expect(stale.json().snapshot.snapshotGeneration).toBeGreaterThan(0);
    expect(adapter.itemUpdates).toHaveLength(1);
  });

  it("rejects invalid self-assigned task creation without calling Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
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
        durationMinutes: null,
        ...action("invalid-create", "create")
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
    markMirrorReady(db);
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
        durationMinutes: 30,
        ...action("personal-create", "create")
      }
    });
    await app.close();

    expect(response.statusCode).toBe(202);
    expect(adapter.itemAdds[0]).toMatchObject({ kind: "root", assigneeId: null });
  });

  it("passes trimmed create descriptions to Todoist", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
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
        description: "  Gate code: 2468  ",
        ...action("description-create", "create")
      }
    });
    await app.close();

    expect(response.statusCode).toBe(202);
    expect(adapter.itemAdds[0]?.description).toBe("Gate code: 2468");
  });

  it("accepts fixed-contract checklistDrafts while keeping child scheduling fields out of the root create command", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    new ProjectRepository(db).upsertProject({
      todoistProjectId: "project-family", name: "Family", shared: true, canAssignTasks: true, isInboxProject: false,
      isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST", url: "/tasks", headers: { authorization: "Bearer secret" },
      payload: {
        title: "Root with checklist", description: null, projectId: "project-family", assigneeId: "user-self",
        dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30, recurrencePreset: "never", timeZone: "Europe/Bratislava",
        checklistDrafts: [{ clientMutationId: "draft-child", title: "First child", description: "Details" }],
        ...action("create-with-checklist", "create")
      }
    });
    await app.close();

    expect(response.statusCode).toBe(202);
    expect(adapter.itemAdds).toMatchObject([
      { kind: "root", title: "Root with checklist" },
      { kind: "child", title: "First child", description: "Details" }
    ]);
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
        description: "   ",
        ...action("blank-description", "create")
      }
    });
    await app.close();

    expect(response.statusCode).toBe(400);
    expect(adapter.createdTasks).toEqual([]);
  });

  it("rejects unknown child scheduling fields and invalid IANA time zones", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const projects = new ProjectRepository(db);
    projects.upsertProject({
      todoistProjectId: "project-family", name: "Family", shared: true, canAssignTasks: true, isInboxProject: false,
      isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-06-13T10:00:00Z"
    });
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    const headers = { authorization: "Bearer secret" };
    const base = {
      title: "Strict task", projectId: "project-family", assigneeId: "user-self", dueDatetime: "2026-07-15T18:00:00Z",
      durationMinutes: 30, ...action("strict-child", "create")
    };

    const childSchedule = await app.inject({ method: "POST", url: "/tasks", headers, payload: {
      ...base, checklistDrafts: [{ clientMutationId: "draft-child", title: "Child", dueDatetime: "2026-07-15T19:00:00Z" }]
    } });
    const invalidTimeZone = await app.inject({ method: "POST", url: "/tasks", headers, payload: {
      ...base, actionId: "action-invalid-timezone", timeZone: "Europe/Not-A-Place"
    } });
    await app.close();

    expect(childSchedule.statusCode).toBe(400);
    expect(invalidTimeZone.statusCode).toBe(400);
  });

  it("rejects a null occurrence key before task action execution", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({
      method: "POST", url: "/tasks/task-1/start", headers: { authorization: "Bearer secret" },
      payload: { actionId: "invalid-occurrence", deviceId: "android-1", expectedOccurrenceKey: null }
    });
    await app.close();

    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({ code: "validation_error" });
  });

  it("keeps bearer credentials and full task descriptions out of durable action journal output", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    new ProjectRepository(db).upsertProject({
      todoistProjectId: "project-family", name: "Family", shared: true, canAssignTasks: true, isInboxProject: false,
      isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-06-13T10:00:00Z"
    });
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "android-secret", todoist: new FakeTodoistAdapter() });
    const description = "full-description-do-not-journal\n--- Buzer activity (managed) ---\nprivate-audit";
    const response = await app.inject({
      method: "POST", url: "/tasks", headers: { authorization: "Bearer android-secret" },
      payload: {
        title: "Private task", projectId: "project-family", assigneeId: "user-self", dueDatetime: "2026-07-15T18:00:00Z",
        durationMinutes: 30, description, ...action("journal-safety", "create")
      }
    });
    const journalOutput = JSON.stringify(db.prepare("select * from task_action_journal union all select * from task_action_journal").all());
    await app.close();

    expect(response.statusCode).toBe(202);
    expect(journalOutput).not.toContain("android-secret");
    expect(journalOutput).not.toContain("Bearer");
    expect(journalOutput).not.toContain("full-description-do-not-journal");
    expect(journalOutput).not.toContain("private-audit");
  });

  it("does not register the retired schedule-only mutation route", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
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
    setOccurrence(db, "task-1", "2026-05-08T18:00:00Z");
    db.prepare("update commitment_states set occurrence_key = ?, occurrence_due_datetime = ?, occurrence_recurrence_fingerprint = ? where task_id = ?")
      .run("occurrence-1", "2026-05-08T18:00:00Z", '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}', "task-1");
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });
    const auth = { authorization: "Bearer secret" };

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-1/reschedule",
      headers: auth,
      payload: { dueDatetime: "2026-05-08T22:00:00Z", durationMinutes: 60, description: "  Updated gate code: 1357  ", ...action("retired-route") }
    });
    await app.close();

    expect(response.statusCode).toBe(404);
    expect(adapter.itemUpdates).toEqual([]);
  });

  it("updates a root patch through the fixed update route", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
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
    db.prepare("update commitment_states set occurrence_key = ?, occurrence_due_datetime = ?, occurrence_recurrence_fingerprint = ? where task_id = ?")
      .run("occurrence-1", null, '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}', "task-1");
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
        ...action("update"),
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`,
          title: "Edited task",
          description: "  Updated details  ",
          projectId: "project-family",
          assigneeId: "user-self",
          dueDatetime: "2026-05-08T19:00:00Z",
          durationMinutes: 45
        },
        childMutations: []
      }
    });
    await app.close();

    expect(response.statusCode).toBe(202);
    expect(adapter.itemUpdates[0]).toMatchObject({
      title: "Edited task",
      description: "Updated details",
      assignment: { kind: "assign", assigneeId: "user-self" },
      due: { kind: "replace", datetime: "2026-05-08T19:00:00Z" },
      duration: { amount: 45, unit: "minute" }
    });
  });

  it("preserves explicit null schedule fields when the update route clears a Wife task schedule", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Scheduled task", description: null,
      projectId: "project-family", assigneeId: "user-self", creatorId: "user-self",
      dueDatetime: "2026-07-23T10:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-22T10:00:00Z"
    });
    db.prepare("update commitment_states set occurrence_key = ?, occurrence_due_datetime = ?, occurrence_recurrence_fingerprint = ? where task_id = ?")
      .run("occurrence-1", "2026-07-23T10:00:00Z", '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}', "task-1");
    new ProjectRepository(db).upsertProject({
      todoistProjectId: "project-family", name: "Family", shared: true, canAssignTasks: true,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-22T10:00:00Z"
    });
    addActiveCollaborator(db, "project-family", "user-wife");
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST", url: "/tasks/task-1/update", headers: { authorization: "Bearer secret" },
      payload: {
        ...action("clear-schedule"),
        rootPatch: {
          expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}`,
          assigneeId: "user-wife",
          dueDatetime: null,
          durationMinutes: null,
          recurrencePreset: "never"
        },
        childMutations: []
      }
    });
    await app.close();

    expect(response.statusCode).toBe(202);
    expect(adapter.itemUpdates[0]).toMatchObject({
      due: { kind: "clear" }, duration: { kind: "clear" }, assignment: { kind: "assign", assigneeId: "user-wife" }
    });
  });

  it("rejects legacy flat update fields and only requires a task revision for root patches", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Root", description: null, projectId: "project-family",
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-05-08T18:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-05-08T10:00:00Z"
    });
    setOccurrence(db, "task-1", "2026-05-08T18:00:00Z");
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    const headers = { authorization: "Bearer secret" };

    const legacy = await app.inject({
      method: "POST", url: "/tasks/task-1/update", headers,
      payload: {
        ...action("legacy"), title: "must be rejected", projectId: "project-family", assigneeId: "user-self",
        dueDatetime: "2026-05-08T18:00:00Z", durationMinutes: 30, childMutations: []
      }
    });
    const checklistOnly = await app.inject({
      method: "POST", url: "/tasks/task-1/update", headers,
      payload: { ...action("checklist-only"), rootPatch: null, childMutations: [] }
    });
    await app.close();

    expect(legacy.statusCode).toBe(400);
    expect(checklistOnly.statusCode).toBe(200);
    expect(checklistOnly.json()).toMatchObject({
      actionId: "action-checklist-only", outcome: "completed", snapshot: { tasks: expect.any(Array) }
    });
  });

  it("sends escalation through postpone route on second start postpone", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
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
    setOccurrence(db, "task-1", "2026-05-08T18:00:00Z");
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

    await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth, payload: action("postpone-1") });
    const response = await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth, payload: action("postpone-2") });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ actionId: "action-postpone-2", outcome: "completed", snapshot: { tasks: expect.any(Array) } });
    expect(sender.sent).toHaveLength(1);
  });
});

function markMirrorReady(db: Database.Database): void {
  db.prepare(`
    insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
    values (1, 'ready-token', 3, '2026-07-15T10:00:00Z')
  `).run();
}

function addActiveCollaborator(db: Database.Database, projectId: string, userId: string): void {
  const collaborators = new CollaboratorRepository(db);
  collaborators.upsertCollaborator({
    todoistUserId: userId,
    fullName: "Wife",
    email: "wife@example.test",
    timeZone: "Europe/Warsaw",
    imageId: null,
    isDeleted: false,
    rawJson: {},
    updatedAt: "2026-07-22T10:00:00Z"
  });
  collaborators.upsertCollaboratorState({
    todoistProjectId: projectId,
    todoistUserId: userId,
    state: "active",
    role: "READ_WRITE",
    isDeleted: false,
    rawJson: {},
    updatedAt: "2026-07-22T10:00:00Z"
  });
}

function action(id: string, kind: "create" | "occurrence" | "revision" = "occurrence") {
  return {
    actionId: `action-${id}`,
    deviceId: "android-1",
    ...(kind === "create" ? {} : { expectedOccurrenceKey: "occurrence-1" }),
    ...(kind === "revision" ? { expectedTaskRevision: "r:1" } : {})
  };
}

function setOccurrence(db: Database.Database, taskId: string, dueDatetime: string): void {
  db.prepare("update commitment_states set occurrence_key = ?, occurrence_due_datetime = ?, occurrence_recurrence_fingerprint = ? where task_id = ?")
    .run("occurrence-1", dueDatetime, '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}', taskId);
}
