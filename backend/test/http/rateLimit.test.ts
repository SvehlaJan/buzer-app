import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { TaskRepository } from "../../src/repositories/taskRepository.js";
import { buildServer as createServer } from "../../src/server.js";
import { FakeTodoistAdapter } from "../fakes/fakeTodoistAdapter.js";

function buildServer(options: Parameters<typeof createServer>[0]) {
  return createServer({ ...options, logger: false });
}

function createApp() {
  const db = new Database(":memory:");
  migrate(db);
  return buildServer({
    db,
    currentUserId: "user-self",
    androidApiToken: "secret",
    todoist: new FakeTodoistAdapter(),
    rateLimit: { max: 3, timeWindowMs: 60_000 }
  });
}

describe("rate limiting", () => {
  it("adds rate-limit headers to an authoritative child-mutation response", async () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare("insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at) values (1, 'ready', 2, '2026-07-15T10:00:00Z')").run();
    const tasks = new TaskRepository(db);
    tasks.upsertTodoistTask({
      id: "root", todoistTaskId: "todoist-root", title: "Root", description: null, projectId: "project-1",
      assigneeId: "user-self", creatorId: "user-self", dueDatetime: "2026-07-15T18:00:00Z", durationMinutes: 30,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    tasks.upsertTodoistTask({
      id: "child", todoistTaskId: "todoist-child", title: "Child", description: null, projectId: "project-1",
      parentId: "todoist-root", childOrder: 1, assigneeId: null, creatorId: "user-self", dueDatetime: null, durationMinutes: null,
      completedAt: null, deletedAt: null, rawJson: "{}", updatedAt: "2026-07-15T10:00:00Z"
    });
    db.prepare("update commitment_states set occurrence_key = ?, occurrence_due_datetime = ?, occurrence_recurrence_fingerprint = ? where task_id = ?")
      .run("occurrence-1", "2026-07-15T18:00:00Z", '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}', "root");
    const childRevision = `r:${tasks.getById("child")?.mirrorRevision}`;
    const app = buildServer({
      db, currentUserId: "user-self", androidApiToken: "secret", todoist: new FakeTodoistAdapter(),
      rateLimit: { max: 3, timeWindowMs: 60_000 }
    });

    const response = await app.inject({
      method: "POST", url: "/tasks/root/checklist/todoist-child/check", remoteAddress: "198.51.100.9",
      headers: { authorization: "Bearer secret" },
      payload: { actionId: "rate-child", deviceId: "android-1", expectedOccurrenceKey: "occurrence-1", expectedChildRevision: childRevision }
    });
    await app.close();

    expect(response.statusCode).toBe(200);
    expect(response.headers["x-ratelimit-limit"]).toBe("3");
    expect(response.json()).toMatchObject({ actionId: "rate-child", outcome: "completed", snapshot: { tasks: expect.any(Array) } });
  });

  it("adds rate-limit headers to responses from the registered route plugin", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      remoteAddress: "198.51.100.9",
      headers: { authorization: "Bearer secret" }
    });
    await app.close();

    expect(response.headers["x-ratelimit-limit"]).toBe("3");
    expect(response.headers["x-ratelimit-remaining"]).toBe("2");
  });

  it("limits the sixty-first request from one source with the production default", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({
      db,
      currentUserId: "user-self",
      androidApiToken: "secret",
      todoist: new FakeTodoistAdapter()
    });

    for (let i = 0; i < 60; i += 1) {
      const response = await app.inject({ method: "GET", url: "/health", remoteAddress: "198.51.100.60" });
      expect(response.statusCode).toBe(200);
    }
    const blocked = await app.inject({ method: "GET", url: "/health", remoteAddress: "198.51.100.60" });
    await app.close();

    expect(blocked.statusCode).toBe(429);
  });

  it("throttles a burst from a single client IP", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      const ok = await app.inject({ method: "GET", url: "/health", remoteAddress: "198.51.100.10" });
      expect(ok.statusCode).toBe(200);
    }
    const blocked = await app.inject({ method: "GET", url: "/health", remoteAddress: "198.51.100.10" });
    await app.close();

    expect(blocked.statusCode).toBe(429);
  });

  it("keys by X-Forwarded-For client when the peer is a trusted proxy", async () => {
    const app = createApp();
    const proxyPeer = "172.28.0.1";

    for (let i = 0; i < 3; i += 1) {
      await app.inject({
        method: "GET",
        url: "/health",
        remoteAddress: proxyPeer,
        headers: { "x-forwarded-for": "203.0.113.1" }
      });
    }
    const exhaustedClient = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: proxyPeer,
      headers: { "x-forwarded-for": "203.0.113.1" }
    });
    const otherClient = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: proxyPeer,
      headers: { "x-forwarded-for": "203.0.113.2" }
    });
    await app.close();

    expect(exhaustedClient.statusCode).toBe(429);
    expect(otherClient.statusCode).toBe(200);
  });

  it("ignores X-Forwarded-For from untrusted peers", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      await app.inject({
        method: "GET",
        url: "/health",
        remoteAddress: "100.100.7.7",
        headers: { "x-forwarded-for": "203.0.113.1" }
      });
    }
    const samePeer = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: "100.100.7.7",
      headers: { "x-forwarded-for": "203.0.113.9" }
    });
    const differentPeerSameHeader = await app.inject({
      method: "GET",
      url: "/health",
      remoteAddress: "100.100.7.8",
      headers: { "x-forwarded-for": "203.0.113.1" }
    });
    await app.close();

    expect(samePeer.statusCode).toBe(429);
    expect(differentPeerSameHeader.statusCode).toBe(200);
  });

  it("rate limits unknown paths", async () => {
    const app = createApp();

    for (let i = 0; i < 3; i += 1) {
      const miss = await app.inject({ method: "GET", url: "/nope", remoteAddress: "198.51.100.20" });
      expect(miss.statusCode).toBe(404);
    }
    const blocked = await app.inject({ method: "GET", url: "/nope", remoteAddress: "198.51.100.20" });
    await app.close();

    expect(blocked.statusCode).toBe(429);
  });
});
