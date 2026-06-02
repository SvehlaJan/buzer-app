import assert from "node:assert/strict";
import test from "node:test";
import { buildServer } from "./server.js";

test("snapshot requires bearer token", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({ method: "GET", url: "/tasks/snapshot" });

  assert.equal(response.statusCode, 401);
});

test("snapshot returns deterministic tasks", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "GET",
    url: "/tasks/snapshot",
    headers: { authorization: "Bearer test-token" }
  });

  assert.equal(response.statusCode, 200);
  const body = response.json();
  assert.equal(body.scheduleVersion, 1);
  assert.equal(body.tasks.length, 3);
});

test("actions are recorded", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks/task-start/start",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 204);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls[0].path, "/tasks/task-start/start");
});

test("create task is recorded", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks",
    headers: { authorization: "Bearer test-token" },
    payload: {
      title: "Buy milk",
      projectId: null,
      assigneeId: "user-me",
      dueDatetime: "2026-06-02T16:30:00Z",
      durationMinutes: 45
    }
  });

  assert.equal(response.statusCode, 201);
  assert.deepEqual(response.json(), {
    id: "created-task",
    todoistTaskId: "todoist-created-task"
  });

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls[0].path, "/tasks");
  assert.equal(calls.json().calls[0].body.title, "Buy milk");
});

test("review dismiss is recorded", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/review/dismiss",
    headers: { authorization: "Bearer test-token" }
  });

  assert.equal(response.statusCode, 200);
  assert.equal(typeof response.json().snoozedUntil, "string");

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls[0].path, "/review/dismiss");
});

test("forced action failures also fail task creation and still record call", async (t) => {
  const app = await buildServer({ failActions: true });
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks",
    headers: { authorization: "Bearer test-token" },
    payload: {
      title: "Buy milk",
      projectId: null,
      assigneeId: "user-me",
      dueDatetime: "2026-06-02T16:30:00Z",
      durationMinutes: 45
    }
  });

  assert.equal(response.statusCode, 500);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls.length, 1);
  assert.equal(calls.json().calls[0].path, "/tasks");
});

test("forced action failures also fail review dismiss and still record call", async (t) => {
  const app = await buildServer({ failActions: true });
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/review/dismiss",
    headers: { authorization: "Bearer test-token" }
  });

  assert.equal(response.statusCode, 500);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls.length, 1);
  assert.equal(calls.json().calls[0].path, "/review/dismiss");
});

test("reset clears recorded calls", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  await app.inject({
    method: "POST",
    url: "/tasks/task-start/done",
    headers: { authorization: "Bearer test-token" }
  });

  const reset = await app.inject({ method: "POST", url: "/__reset" });
  const calls = await app.inject({ method: "GET", url: "/__calls" });

  assert.equal(reset.statusCode, 200);
  assert.deepEqual(calls.json(), { calls: [] });
});

test("forced action failures return 500 and still record call", async (t) => {
  const app = await buildServer({ failActions: true });
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks/task-start/start",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 500);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls.length, 1);
});

test("test helper can toggle forced action failures at runtime", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const toggle = await app.inject({
    method: "POST",
    url: "/__fail-actions",
    payload: { enabled: true }
  });
  assert.equal(toggle.statusCode, 200);

  const response = await app.inject({
    method: "POST",
    url: "/tasks/task-start/start",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 500);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls.length, 1);
  assert.equal(calls.json().calls[0].path, "/tasks/task-start/start");
});
