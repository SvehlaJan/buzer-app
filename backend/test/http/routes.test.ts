import Database from "better-sqlite3";
import { describe, expect, it, vi } from "vitest";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { migrate } from "../../src/db/migrations.js";
import { CollaboratorRepository } from "../../src/repositories/collaboratorRepository.js";
import { ProjectRepository } from "../../src/repositories/projectRepository.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { TodoistOperationQueue } from "../../src/services/todoistOperationQueue.js";
import { TodoistSyncService } from "../../src/services/todoistSyncService.js";
import { TodoistOperationError } from "../../src/todoist/todoistErrors.js";
import { buildServer as createServer } from "../../src/server.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

function buildServer(options: Parameters<typeof createServer>[0]) {
  return createServer({ ...options, logger: false });
}

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

  it("echoes X-Request-ID header in every response", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const customId = "123e4567-e89b-42d3-a456-426614174000";
    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: {
        authorization: "Bearer secret",
        "x-request-id": customId
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.headers["x-request-id"]).toBe(customId);
  });

  it("serves cached snapshot on GET /tasks/snapshot with 0 Todoist sync calls", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ tasks: [] });
    expect(adapter.syncTokens).toHaveLength(0);
  });

  it("serves cached snapshot on GET /tasks/snapshot?forceRefresh=false with 0 Todoist sync calls", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=false",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(adapter.syncTokens).toHaveLength(0);
  });

  it("performs raw sync and returns updated snapshot on GET /tasks/snapshot?forceRefresh=true", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    new ProjectRepository(db).upsertProject({
      todoistProjectId: "project-family", name: "Family", shared: true, canAssignTasks: true,
      isInboxProject: false, isDeleted: false, isArchived: false, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=true",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ tasks: [] });
    expect(adapter.syncTokens).toEqual(["ready-token"]);
  });

  it("passes the queue execution signal to forced refresh sync", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const adapter = new FakeTodoistAdapter();
    const synchronizer = new TodoistSyncService(
      db,
      new TaskRepository(db),
      new ProjectRepository(db),
      adapter,
      "user-self"
    );
    const syncOnce = vi.spyOn(synchronizer, "syncOnce").mockResolvedValue({
      hasChanges: false,
      changedTaskCount: 0,
      changedTaskIds: [],
      changedProjectCount: 0,
      changedCollaboratorCount: 0
    });
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: adapter,
      synchronizer
    });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=true",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(syncOnce).toHaveBeenCalledWith({ signal: expect.any(AbortSignal) });
  });

  it("rejects invalid forceRefresh query parameters with 400", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const invalid = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=invalid",
      headers: { authorization: "Bearer secret" }
    });
    const extraKey = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?unknown=1",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(invalid.statusCode).toBe(400);
    expect(invalid.json()).toMatchObject({ code: "validation_error" });
    expect(extraKey.statusCode).toBe(400);
    expect(extraKey.json()).toMatchObject({ code: "validation_error" });
  });

  it("returns 503 forced_refresh_failed when force refresh fails", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const adapter = new FakeTodoistAdapter();
    adapter.sync = vi.fn().mockRejectedValue(new Error("Todoist connection timeout"));
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=true",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toMatchObject({
      code: "forced_refresh_failed",
      message: "Todoist connection timeout",
      snapshot: { tasks: [] }
    });
  });

  it("captures a forced-refresh parse/internal defect while still returning the typed warning", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const adapter = new FakeTodoistAdapter();
    const internalError = new TodoistOperationError("parse");
    adapter.sync = vi.fn().mockRejectedValue(internalError);
    const observability = new RecordingBackendObservability();
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: adapter,
      observability
    });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=true",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toMatchObject({ code: "forced_refresh_failed" });
    expect(observability.exceptions).toEqual([{
      error: internalError,
      context: {
        marker: "forced_refresh_internal_defect",
        operation: "force_refresh",
        request_id: expect.any(String)
      }
    }]);
    expect(observability.requestFailures).toHaveLength(0);
  });

  it("keeps operational forced-refresh failures warning-only", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const adapter = new FakeTodoistAdapter();
    adapter.sync = vi.fn().mockRejectedValue(new TodoistOperationError("transport"));
    const observability = new RecordingBackendObservability();
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: adapter,
      observability
    });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=true",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(observability.logs).toContainEqual(expect.objectContaining({
      level: "warn",
      marker: "forced_refresh_failed"
    }));
    expect(observability.exceptions).toHaveLength(0);
    expect(observability.requestFailures).toHaveLength(0);
  });

  it("returns 503 backend_busy when forced refresh cannot acquire queue budget", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const queue = new TodoistOperationQueue();
    queue.run = vi.fn().mockRejectedValue(
      new TodoistOperationError("timeout", undefined, { error_code: "backend_busy" })
    );
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: new FakeTodoistAdapter(),
      todoistQueue: queue
    });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=true",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toEqual({
      code: "backend_busy",
      message: "Buzer is currently processing another Todoist operation. Nothing was sent. Please try again shortly.",
      snapshot: expect.any(Object)
    });
  });

  it("maps an active forced-refresh deadline timeout to forced_refresh_failed, not backend_busy", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const queue = new TodoistOperationQueue();
    queue.run = vi.fn().mockRejectedValue(
      new TodoistOperationError("timeout", undefined, { operation: "force_refresh" })
    );
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: new FakeTodoistAdapter(),
      todoistQueue: queue
    });

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot?forceRefresh=true",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toMatchObject({ code: "forced_refresh_failed" });
  });

  it("returns 404 for removed POST /tasks/sync", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });

    const response = await app.inject({
      method: "POST",
      url: "/tasks/sync",
      headers: { authorization: "Bearer secret" },
      payload: {}
    });
    await app.close();

    expect(response.statusCode).toBe(404);
  });

  it("rejects mutation requests containing actionId or deviceId under strict Zod mode", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Test", description: null, projectId: "project-family",
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    setOccurrence(db, "task-1", "2026-07-15T18:00:00Z");
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter() });
    const auth = { authorization: "Bearer secret" };

    const startWithActionId = await app.inject({
      method: "POST", url: "/tasks/task-1/start", headers: auth,
      payload: { expectedOccurrenceKey: "occurrence-1", actionId: "legacy-action-id" }
    });
    const doneWithDeviceId = await app.inject({
      method: "POST", url: "/tasks/task-1/done", headers: auth,
      payload: { expectedOccurrenceKey: "occurrence-1", deviceId: "android-device" }
    });
    const createWithActionId = await app.inject({
      method: "POST", url: "/tasks", headers: auth,
      payload: {
        title: "New task", projectId: "project-family", assigneeId: "user-self",
        dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30,
        actionId: "legacy-action-id", deviceId: "android-device"
      }
    });
    await app.close();

    expect(startWithActionId.statusCode).toBe(400);
    expect(startWithActionId.json()).toMatchObject({ code: "validation_error" });
    expect(doneWithDeviceId.statusCode).toBe(400);
    expect(doneWithDeviceId.json()).toMatchObject({ code: "validation_error" });
    expect(createWithActionId.statusCode).toBe(400);
    expect(createWithActionId.json()).toMatchObject({ code: "validation_error" });
  });

  it("checks a direct checklist child and returns snapshot only (HTTP 200)", async () => {
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
    setOccurrence(db, "task-1", "2026-07-15T18:00:00Z");
    const childRevision = `r:${repo.getById("child-1")?.mirrorRevision}`;
    const adapter = new FakeTodoistAdapter();
    adapter.syncResults.push({
      syncToken: "new-token",
      projects: [],
      tasks: [{
        todoistTaskId: "todoist-child-1", title: "Checklist child", description: null, projectId: "project-family",
        parentId: "todoist-1", childOrder: 1, assigneeId: null, creatorId: "user-self", dueDate: null, dueDatetime: null,
        dueString: null, dueLang: null, dueTimezone: null, isRecurring: false, durationAmount: null, durationUnit: null,
        durationMinutes: null, completedAt: "2026-07-15T18:00:00Z", deletedAt: null, rawJson: {}, updatedAt: "2026-07-15T18:00:00Z"
      }]
    });
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-1/checklist/child-1/check",
      headers: { authorization: "Bearer secret" },
      payload: {
        expectedOccurrenceKey: "occurrence-1",
        expectedChildRevision: childRevision
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      snapshot: expect.objectContaining({
        tasks: [
          expect.objectContaining({
            id: "task-1",
            checklistItems: [expect.objectContaining({ id: "child-1", checked: true })]
          })
        ]
      })
    });
    expect(response.json()).not.toHaveProperty("actionId");
    expect(response.json()).not.toHaveProperty("outcome");
    expect(adapter.itemCloses).toEqual([
      expect.objectContaining({ todoistTaskId: "todoist-child-1" })
    ]);
  });

  it("does not treat a parsed real-socket mutation request as pre-aborted", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-socket", todoistTaskId: "todoist-socket", title: "Socket mutation", description: null,
      projectId: "project-family", assigneeId: "user-self", creatorId: "user-self",
      dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30, completedAt: null, deletedAt: null,
      rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    setOccurrence(db, "task-socket", "2026-07-15T18:00:00Z");
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: adapter
    });
    const address = await app.listen({ host: "127.0.0.1", port: 0 });

    const response = await fetch(`${address}/tasks/task-socket/done`, {
      method: "POST",
      headers: {
        authorization: "Bearer secret",
        "content-type": "application/json"
      },
      body: JSON.stringify({ expectedOccurrenceKey: "occurrence-1" })
    });
    const body = await response.json();
    await app.close();

    expect(response.status).toBe(200);
    expect(body).toMatchObject({ snapshot: expect.any(Object) });
    expect(adapter.itemCloses).toEqual([
      expect.objectContaining({ todoistTaskId: "todoist-socket" })
    ]);
  });

  it("states that nothing was sent when a mutation expires before execution", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-busy", todoistTaskId: "todoist-busy", title: "Busy mutation", description: null,
      projectId: "project-family", assigneeId: "user-self", creatorId: "user-self",
      dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30, completedAt: null, deletedAt: null,
      rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    setOccurrence(db, "task-busy", "2026-07-15T18:00:00Z");
    const queue = new TodoistOperationQueue();
    queue.run = vi.fn().mockRejectedValue(
      new TodoistOperationError("timeout", undefined, { error_code: "backend_busy" })
    );
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: adapter,
      todoistQueue: queue
    });

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-busy/done",
      headers: { authorization: "Bearer secret" },
      payload: { expectedOccurrenceKey: "occurrence-1" }
    });
    await app.close();

    expect(response.statusCode).toBe(503);
    expect(response.json()).toEqual({
      code: "backend_busy",
      message: "Buzer is currently processing another Todoist operation. Nothing was sent. Please try again shortly."
    });
    expect(adapter.itemCloses).toHaveLength(0);
  });

  it("handles local task action routes and device check-in", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Exercise",
      description: null,
      projectId: "project-family",
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
      payload: { expectedOccurrenceKey: "obsolete-occurrence" }
    });
    const start = await app.inject({
      method: "POST",
      url: "/tasks/task-1/start",
      headers: auth,
      payload: { expectedOccurrenceKey: "occurrence-1" }
    });
    const postpone = await app.inject({
      method: "POST",
      url: "/tasks/task-1/postpone-start",
      headers: auth,
      payload: { expectedOccurrenceKey: "occurrence-1" }
    });
    const extend = await app.inject({
      method: "POST",
      url: "/tasks/task-1/extend",
      headers: auth,
      payload: { expectedOccurrenceKey: "occurrence-1", expectedTaskRevision: `r:${repo.getById("task-1")?.mirrorRevision}` }
    });
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
    expect(postpone.statusCode).toBe(200);
    expect(extend.statusCode).toBe(200);
    expect(checkIn.statusCode).toBe(200);

    expect(start.json()).toEqual({ snapshot: expect.any(Object) });
    expect(postpone.json()).toMatchObject({ snapshot: expect.any(Object), snoozedUntil: expect.any(String) });
    expect(extend.json()).toEqual({ snapshot: expect.any(Object) });
    expect(stale.statusCode).toBe(409);
    expect(stale.json()).toMatchObject({ code: "stale_occurrence", snapshot: expect.any(Object) });
    expect(adapter.itemUpdates).toHaveLength(1);
  });

  it("returns 502 todoist_command_rejected when Todoist explicitly rejects a command", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Test", description: null, projectId: "project-family",
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    setOccurrence(db, "task-1", "2026-07-15T18:00:00Z");
    const adapter = new FakeTodoistAdapter();
    adapter.itemClose = vi.fn().mockRejectedValue(
      new TodoistOperationError("command_rejected", undefined, { error_code: 400, error_tag: "INVALID_ARGUMENT" })
    );
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-1/done",
      headers: { authorization: "Bearer secret" },
      payload: { expectedOccurrenceKey: "occurrence-1" }
    });
    await app.close();

    expect(response.statusCode).toBe(502);
    expect(response.json()).toMatchObject({
      code: "todoist_command_rejected",
      message: expect.stringContaining("Todoist rejected"),
      snapshot: expect.any(Object)
    });
  });

  it("returns 504 todoist_delivery_unknown when command transport/timeout is unknown", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Test", description: null, projectId: "project-family",
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    setOccurrence(db, "task-1", "2026-07-15T18:00:00Z");
    const adapter = new FakeTodoistAdapter();
    adapter.itemClose = vi.fn().mockRejectedValue(
      new TodoistOperationError("transport", undefined, { error_code: "network_reset" })
    );
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-1/done",
      headers: { authorization: "Bearer secret" },
      payload: { expectedOccurrenceKey: "occurrence-1" }
    });
    await app.close();

    expect(response.statusCode).toBe(504);
    expect(response.json()).toMatchObject({
      code: "todoist_delivery_unknown",
      message: expect.stringContaining("could not confirm"),
      snapshot: expect.any(Object)
    });
  });

  it("returns 502 todoist_accepted_snapshot_stale when commands succeed but post-command sync fails", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1", todoistTaskId: "todoist-1", title: "Test", description: null, projectId: "project-family",
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    setOccurrence(db, "task-1", "2026-07-15T18:00:00Z");
    const adapter = new FakeTodoistAdapter();
    adapter.sync = vi.fn().mockRejectedValue(
      new TodoistOperationError("transport", undefined, { error_code: "sync_failed" })
    );
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-1/done",
      headers: { authorization: "Bearer secret" },
      payload: { expectedOccurrenceKey: "occurrence-1" }
    });
    await app.close();

    expect(response.statusCode).toBe(502);
    expect(response.json()).toEqual({
      code: "todoist_accepted_snapshot_stale",
      message: "The change was accepted by Todoist, but the local snapshot could not be refreshed."
    });
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
        durationMinutes: 30
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
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
        description: "  Gate code: 2468  "
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
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
        checklistDrafts: [{ clientMutationId: "draft-child", title: "First child", description: "Details" }]
      }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
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
        description: "   "
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
      durationMinutes: 30
    };

    const childSchedule = await app.inject({ method: "POST", url: "/tasks", headers, payload: {
      ...base, checklistDrafts: [{ clientMutationId: "draft-child", title: "Child", dueDatetime: "2026-07-15T19:00:00Z" }]
    } });
    const invalidTimeZone = await app.inject({ method: "POST", url: "/tasks", headers, payload: {
      ...base, timeZone: "Europe/Not-A-Place"
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
      payload: { expectedOccurrenceKey: null }
    });
    await app.close();

    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({ code: "validation_error" });
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
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });
    const auth = { authorization: "Bearer secret" };

    const response = await app.inject({
      method: "POST",
      url: "/tasks/task-1/reschedule",
      headers: auth,
      payload: { dueDatetime: "2026-05-08T22:00:00Z", durationMinutes: 60, description: "  Updated gate code: 1357  " }
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
    setOccurrence(db, "task-1", null);
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
        expectedOccurrenceKey: "occurrence-1",
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

    expect(response.statusCode).toBe(200);
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
    setOccurrence(db, "task-1", "2026-07-23T10:00:00Z");
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
        expectedOccurrenceKey: "occurrence-1",
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

    expect(response.statusCode).toBe(200);
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
        title: "must be rejected", projectId: "project-family", assigneeId: "user-self",
        dueDatetime: "2026-05-08T18:00:00Z", durationMinutes: 30, childMutations: []
      }
    });
    const checklistOnly = await app.inject({
      method: "POST", url: "/tasks/task-1/update", headers,
      payload: { expectedOccurrenceKey: "occurrence-1", rootPatch: null, childMutations: [] }
    });
    await app.close();

    expect(legacy.statusCode).toBe(400);
    expect(checklistOnly.statusCode).toBe(200);
    expect(checklistOnly.json()).toMatchObject({
      snapshot: { tasks: expect.any(Array) }
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

    await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth, payload: { expectedOccurrenceKey: "occurrence-1" } });
    const response = await app.inject({ method: "POST", url: "/tasks/task-1/postpone-start", headers: auth, payload: { expectedOccurrenceKey: "occurrence-1" } });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ snapshot: { tasks: expect.any(Array) }, snoozedUntil: expect.any(String) });
    expect(sender.sent).toHaveLength(1);
  });

  it("Fastify onError hook excludes operational errors from Sentry issue capture", async () => {
    const db = new Database(":memory:");
    migrate(db);
    markMirrorReady(db);
    const recordingObs = new RecordingBackendObservability();
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    // 1. Validation 400
    await app.inject({
      method: "POST",
      url: "/tasks",
      headers: { authorization: "Bearer secret" },
      payload: { invalid: true }
    });
    expect(recordingObs.requestFailures).toHaveLength(0);

    // 2. 404 Not Found
    await app.inject({
      method: "GET",
      url: "/tasks/sync",
      headers: { authorization: "Bearer secret" }
    });
    expect(recordingObs.requestFailures).toHaveLength(0);

    await app.close();
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

function setOccurrence(db: Database.Database, taskId: string, dueDatetime: string | null): void {
  db.prepare("update commitment_states set occurrence_key = ?, occurrence_due_datetime = ?, occurrence_recurrence_fingerprint = ? where task_id = ?")
    .run("occurrence-1", dueDatetime, '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}', taskId);
}
