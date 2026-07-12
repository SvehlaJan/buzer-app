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
  assert.equal(body.tasks.length, 4);
  assert.equal(body.tasks.find((task: { id: string }) => task.id === "task-start").description, "Bring the printed form and check opening hours.");
  assert.equal(body.tasks.find((task: { id: string }) => task.id === "task-deadline").description, "Share the final numbers before closing the block.");
  assert.equal(body.tasks.find((task: { id: string }) => task.id === "task-invalid").description, "Clarify the missing commitment fields.");
  assert.match(
    body.tasks.find((task: { id: string }) => task.id === "task-floating-time").dueDatetime,
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/
  );
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
      projectId: "project-test",
      assigneeId: "user-me",
      dueDatetime: "2026-06-02T16:30:00Z",
      durationMinutes: 45,
      description: "Gate code: 2468"
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
  assert.equal(calls.json().calls[0].body.description, "Gate code: 2468");
});

test("reschedule task records description when supplied", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks/task-start/reschedule",
    headers: { authorization: "Bearer test-token" },
    payload: {
      dueDatetime: "2026-06-02T17:00:00Z",
      durationMinutes: 60,
      description: "Updated gate code: 1357"
    }
  });
  assert.equal(response.statusCode, 204);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls[0].path, "/tasks/task-start/reschedule");
  assert.equal(calls.json().calls[0].body.description, "Updated gate code: 1357");
});

test("update task records full editable payload", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks/task-invalid/update",
    headers: { authorization: "Bearer test-token" },
    payload: {
      title: "Edited task",
      description: "Updated details",
      projectId: "project-test",
      assigneeId: "user-me",
      dueDatetime: "2026-06-02T17:00:00Z",
      durationMinutes: 45
    }
  });
  assert.equal(response.statusCode, 204);

  const calls = await app.inject({ method: "GET", url: "/__calls" });
  assert.equal(calls.json().calls[0].path, "/tasks/task-invalid/update");
  assert.equal(calls.json().calls[0].body.title, "Edited task");
  assert.equal(calls.json().calls[0].body.projectId, "project-test");
  assert.equal(calls.json().calls[0].body.assigneeId, "user-me");
});

test("projects are returned for add-task screen", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "GET",
    url: "/projects",
    headers: { authorization: "Bearer test-token" }
  });

  assert.equal(response.statusCode, 200);
  assert.equal(response.json().projects[0].id, "project-test");
  assert.equal(response.json().projects[0].canAssignTasks, true);
});

test("create task accepts personal project without assignee", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST",
    url: "/tasks",
    headers: { authorization: "Bearer test-token" },
    payload: {
      title: "Personal task",
      projectId: "project-personal",
      dueDatetime: "2026-06-02T16:30:00Z",
      durationMinutes: 45
    }
  });

  assert.equal(response.statusCode, 201);
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
      projectId: "project-test",
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

test("test helper can toggle forced snapshot failures at runtime", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const toggle = await app.inject({
    method: "POST",
    url: "/__fail-snapshot",
    payload: { enabled: true }
  });
  assert.equal(toggle.statusCode, 200);

  const response = await app.inject({
    method: "GET",
    url: "/tasks/snapshot",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 500);
});

test("test helper can toggle forced project failures at runtime", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const toggle = await app.inject({
    method: "POST",
    url: "/__fail-projects",
    payload: { enabled: true }
  });
  assert.equal(toggle.statusCode, 200);
  assert.deepEqual(toggle.json(), { failProjects: true });

  const response = await app.inject({
    method: "GET",
    url: "/projects",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 500);
  assert.deepEqual(response.json(), { error: "forced_project_failure" });
});

test("project failure toggle can be disabled", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  await app.inject({
    method: "POST",
    url: "/__fail-projects",
    payload: { enabled: true }
  });
  await app.inject({
    method: "POST",
    url: "/__fail-projects",
    payload: { enabled: false }
  });

  const response = await app.inject({
    method: "GET",
    url: "/projects",
    headers: { authorization: "Bearer test-token" }
  });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().projects.length, 2);
});
