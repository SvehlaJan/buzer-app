import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { DryRunSender } from "../../src/accountability/dryRunSender.js";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { EscalationService } from "../../src/services/escalationService.js";
import { buildServer } from "../../src/server.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

describe("HTTP routes", () => {
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

  it("handles task action routes and device check-in", async () => {
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
    const adapter = new FakeTodoistAdapter();
    const app = buildServer({ db, currentUserId: "user-self", androidApiToken: "secret", todoist: adapter });

    const response = await app.inject({
      method: "POST",
      url: "/tasks",
      headers: { authorization: "Bearer secret" },
      payload: {
        title: "Loose commitment",
        projectId: null,
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

  it("sends escalation through postpone route on second start postpone", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new TaskRepository(db);
    repo.upsertTodoistTask({
      id: "task-1",
      todoistTaskId: "todoist-1",
      title: "Send invoice",
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
