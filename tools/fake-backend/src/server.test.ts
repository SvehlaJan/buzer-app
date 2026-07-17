import assert from "node:assert/strict";
import test from "node:test";
import { buildServer } from "./server.js";

const auth = { authorization: "Bearer test-token" };

function action(actionId: string, expectedOccurrenceKey = "occurrence-one-time-partial-1") {
  return { actionId, deviceId: "android-test-1", expectedOccurrenceKey };
}

test("snapshot exposes deterministic nested root scenarios", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth });

  assert.equal(response.statusCode, 200);
  const body = response.json();
  assert.deepEqual(body.tasks.map((task: { id: string }) => task.id), [
    "task-start",
    "task-deadline",
    "task-reschedule",
    "task-start-fail",
    "task-deadline-fail",
    "task-reschedule-fail",
    "task-invalid",
    "task-floating-time",
    "task-one-time-empty",
    "task-one-time-partial",
    "task-recurring-all-checked",
    "task-recurring-extend-disabled",
    "task-custom-recurrence",
    "task-unsupported-hierarchy",
    "task-reconciliation-pending"
  ]);
  assert.deepEqual(body.tasks.find((task: { id: string }) => task.id === "task-one-time-empty").checklistItems, []);
  assert.deepEqual(body.tasks.find((task: { id: string }) => task.id === "task-one-time-partial").checklistItems.map((item: { checked: boolean }) => item.checked), [true, false]);
  assert.match(body.tasks.find((task: { id: string }) => task.id === "task-recurring-all-checked").occurrenceKey, /^occurrence-recurring-all-checked-\d+$/);
  assert.equal(body.tasks.find((task: { id: string }) => task.id === "task-custom-recurrence").recurrence.kind, "custom");
  assert.equal(body.tasks.find((task: { id: string }) => task.id === "task-unsupported-hierarchy").hierarchyStatus, "unsupported");
  assert.equal(body.tasks.find((task: { id: string }) => task.id === "task-reconciliation-pending").reconciliationStatus, "pending");
});

test("reset restores fixtures at a newer snapshot generation", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const initial = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json();

  await app.inject({ method: "POST", url: "/__reset" });

  const reset = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json();
  assert.equal(reset.snapshotGeneration, initial.snapshotGeneration + 1);
  assert.deepEqual(reset.tasks.map((task: { id: string }) => task.id), initial.tasks.map((task: { id: string }) => task.id));
});

test("checking a focused child advances only its child revision and returns authority", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const before = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json()
    .tasks.find((task: { id: string }) => task.id === "task-one-time-partial");
  const child = before.checklistItems.find((item: { checked: boolean }) => !item.checked);

  const response = await app.inject({
    method: "POST", url: `/tasks/task-one-time-partial/checklist/${child.id}/check`, headers: auth,
    payload: { ...action("check-child"), expectedChildRevision: child.childRevision }
  });

  assert.equal(response.statusCode, 200);
  assert.equal(response.json().outcome, "completed");
  assert.equal("childOutcomes" in response.json(), false);
  const updated = response.json().snapshot.tasks.find((task: { id: string }) => task.id === "task-one-time-partial");
  assert.equal(response.json().outcome, "completed");
  assert.equal(updated.taskRevision, before.taskRevision);
  assert.equal(updated.checklistItems.find((item: { id: string }) => item.id === child.id).childRevision, "r:2");
  assert.equal(updated.checkedCount, 2);
});

test("adding a focused checklist child preserves the request client mutation ID", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const root = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json()
    .tasks.find((task: { id: string }) => task.id === "task-one-time-empty");

  const response = await app.inject({
    method: "POST", url: `/tasks/${root.id}/checklist`, headers: auth,
    payload: { ...action("add-child", root.occurrenceKey), clientMutationId: "add-child-intent", title: "New child" }
  });

  assert.equal(response.statusCode, 200);
  assert.equal(response.json().outcome, "completed");
  assert.equal("childOutcomes" in response.json(), false);
});

test("every snapshot-affecting fake mutation advances its global snapshot generation", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const before = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json();
  const root = before.tasks.find((task: { id: string }) => task.id === "task-one-time-empty");

  const response = await app.inject({
    method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth,
    payload: action("start-generation", root.occurrenceKey)
  });

  assert.equal(response.statusCode, 200);
  assert.equal(response.json().snapshot.snapshotGeneration, before.snapshotGeneration + 1);
  const conflict = await app.inject({
    method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth,
    payload: action("stale-generation", "obsolete-occurrence")
  });
  assert.equal(conflict.statusCode, 409);
  assert.ok(conflict.json().snapshot.snapshotGeneration > 0);
  assert.equal(conflict.json().snapshot.snapshotGeneration, response.json().snapshot.snapshotGeneration);
});

test("every fake task mutation category advances the returned authoritative generation exactly once", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const initial = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json();
  const root = initial.tasks.find((task: { id: string }) => task.id === "task-one-time-empty");
  let generation = initial.snapshotGeneration;
  const expectAdvance = (response: { statusCode: number; json: () => { snapshot: { snapshotGeneration: number } } }, statusCode: number) => {
    assert.equal(response.statusCode, statusCode);
    generation += 1;
    assert.equal(response.json().snapshot.snapshotGeneration, generation);
  };

  const create = await app.inject({
    method: "POST", url: "/tasks", headers: auth,
    payload: { actionId: "generation-create", deviceId: "android-test-1", title: "Created", projectId: "project-test", dueDatetime: "2026-06-03T10:00:00.000Z", durationMinutes: 30 }
  });
  expectAdvance(create, 200);

  const start = await app.inject({ method: "POST", url: `/tasks/${root.id}/start`, headers: auth, payload: action("generation-start", root.occurrenceKey) });
  expectAdvance(start, 200);
  const postpone = await app.inject({ method: "POST", url: `/tasks/${root.id}/postpone-start`, headers: auth, payload: action("generation-postpone", root.occurrenceKey) });
  expectAdvance(postpone, 200);
  const extend = await app.inject({ method: "POST", url: `/tasks/${root.id}/extend`, headers: auth, payload: action("generation-extend", root.occurrenceKey) });
  expectAdvance(extend, 200);
  const reschedule = await app.inject({ method: "POST", url: `/tasks/${root.id}/reschedule`, headers: auth, payload: { ...action("generation-reschedule", root.occurrenceKey), dueDatetime: "2026-06-02T12:00:00.000Z", durationMinutes: 45 } });
  expectAdvance(reschedule, 200);
  const update = await app.inject({ method: "POST", url: `/tasks/${root.id}/update`, headers: auth, payload: { ...action("generation-update", root.occurrenceKey), rootPatch: { expectedTaskRevision: "r:3", title: "Updated" }, childMutations: [] } });
  expectAdvance(update, 200);
  const add = await app.inject({ method: "POST", url: `/tasks/${root.id}/checklist`, headers: auth, payload: { ...action("generation-add", root.occurrenceKey), title: "New child" } });
  expectAdvance(add, 200);
  const childId = add.json().snapshot.tasks
    .find((task: { id: string }) => task.id === root.id)
    .checklistItems.find((item: { title: string }) => item.title === "New child").id;
  const edit = await app.inject({ method: "POST", url: `/tasks/${root.id}/checklist/${childId}/update`, headers: auth, payload: { ...action("generation-edit", root.occurrenceKey), expectedChildRevision: "r:1", title: "Renamed" } });
  expectAdvance(edit, 200);
  const check = await app.inject({ method: "POST", url: `/tasks/${root.id}/checklist/${childId}/check`, headers: auth, payload: { ...action("generation-check", root.occurrenceKey), expectedChildRevision: "r:2" } });
  expectAdvance(check, 200);
  const uncheck = await app.inject({ method: "POST", url: `/tasks/${root.id}/checklist/${childId}/uncheck`, headers: auth, payload: { ...action("generation-uncheck", root.occurrenceKey), expectedChildRevision: "r:3" } });
  expectAdvance(uncheck, 200);
  const remove = await app.inject({ method: "POST", url: `/tasks/${root.id}/checklist/${childId}/delete`, headers: auth, payload: { ...action("generation-delete", root.occurrenceKey), expectedChildRevision: "r:4" } });
  expectAdvance(remove, 200);
  const done = await app.inject({ method: "POST", url: `/tasks/${root.id}/done`, headers: auth, payload: action("generation-done", root.occurrenceKey) });
  expectAdvance(done, 200);
});

test("Done rejects a root with unchecked direct children and returns the current snapshot", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());

  const response = await app.inject({
    method: "POST", url: "/tasks/task-one-time-partial/done", headers: auth, payload: action("done-incomplete")
  });

  assert.equal(response.statusCode, 409);
  assert.equal(response.json().code, "incomplete_checklist");
  assert.equal(response.json().snapshot.tasks.find((task: { id: string }) => task.id === "task-one-time-partial").checkedCount, 1);
});

test("recurring Done advances occurrence and resets all children", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const before = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json()
    .tasks.find((task: { id: string }) => task.id === "task-recurring-all-checked");

  const response = await app.inject({
    method: "POST", url: "/tasks/task-recurring-all-checked/done", headers: auth,
    payload: action("done-recurring", before.occurrenceKey)
  });

  assert.equal(response.statusCode, 200);
  const updated = response.json().snapshot.tasks.find((task: { id: string }) => task.id === "task-recurring-all-checked");
  assert.equal(updated.occurrenceKey, "occurrence-recurring-all-checked-2");
  assert.equal(updated.dueDatetime, "2026-06-02T10:00:00.000Z");
  assert.equal(updated.checkedCount, 0);
  assert.ok(updated.checklistItems.every((item: { checked: boolean }) => !item.checked));
});

test("recurring Extend remains visible in the snapshot but is rejected", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const root = (await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).json()
    .tasks.find((task: { id: string }) => task.id === "task-recurring-extend-disabled");

  const response = await app.inject({
    method: "POST", url: "/tasks/task-recurring-extend-disabled/extend", headers: auth,
    payload: action("extend-recurring", root.occurrenceKey)
  });

  assert.equal(response.statusCode, 409);
  assert.equal(response.json().code, "extend_not_available");
  assert.equal(response.json().snapshot.tasks.find((task: { id: string }) => task.id === root.id).occurrenceKey, root.occurrenceKey);
});

test("ambiguous child creates become pending and invalid child batches are terminal conflicts", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const create = await app.inject({
    method: "POST", url: "/tasks", headers: auth,
    payload: {
      ...action("create-partial"), title: "Created root", projectId: "project-test", assigneeId: "user-me",
      dueDatetime: "2026-06-03T10:00:00.000Z", durationMinutes: 30, recurrencePreset: "never", timeZone: "Europe/Bratislava",
      checklistDrafts: [
        { clientMutationId: "child-ok", title: "Works", description: null },
        { clientMutationId: "child-fail", title: "[partial-failure]", description: null }
      ]
    }
  });
  assert.equal(create.statusCode, 202);
  assert.equal(create.json().outcome, "reconciliation_pending");
  assert.equal("createdRoot" in create.json(), false);
  assert.equal("childOutcomes" in create.json(), false);

  const edit = await app.inject({
    method: "POST", url: "/tasks/task-one-time-partial/update", headers: auth,
    payload: {
      ...action("edit-partial"), rootPatch: null,
      childMutations: [
        { type: "check", clientMutationId: "valid-check", childId: "child-one-time-partial-open", expectedChildRevision: "r:1" },
        { type: "check", clientMutationId: "stale-check", childId: "child-one-time-partial-done", expectedChildRevision: "r:99" }
      ]
    }
  });
  assert.equal(edit.statusCode, 409);
  assert.equal(edit.json().code, "stale_child");
});

test("identical action replay returns its stored response while changed bodies conflict", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const payload = { ...action("replayed-start", "occurrence-one-time-empty-1") };
  const first = await app.inject({ method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth, payload });
  const replay = await app.inject({ method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth, payload });
  const conflict = await app.inject({
    method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth,
    payload: { ...payload, expectedOccurrenceKey: "changed-occurrence" }
  });

  assert.equal(first.statusCode, 200);
  assert.deepEqual(replay.json(), first.json());
  assert.equal(conflict.statusCode, 409);
  assert.equal(conflict.json().code, "action_id_reused");
});

test("stale occurrence and stale child responses contain authoritative snapshots", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const staleOccurrence = await app.inject({
    method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth,
    payload: action("stale-occurrence", "old-occurrence")
  });
  const staleChild = await app.inject({
    method: "POST", url: "/tasks/task-one-time-partial/checklist/child-one-time-partial-open/check", headers: auth,
    payload: { ...action("stale-child"), expectedChildRevision: "r:999" }
  });

  assert.equal(staleOccurrence.statusCode, 409);
  assert.equal(staleOccurrence.json().code, "stale_occurrence");
  assert.ok(Array.isArray(staleOccurrence.json().snapshot.tasks));
  assert.equal(staleChild.statusCode, 409);
  assert.equal(staleChild.json().code, "stale_child");
  assert.ok(Array.isArray(staleChild.json().snapshot.tasks));
});

test("test controls produce pending, stale, activity full, rate limit, and forced failures", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const control = async (path: string) => app.inject({ method: "POST", url: path, payload: { enabled: true } });
  await control("/__pending-actions");
  const pending = await app.inject({ method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth, payload: action("pending", "occurrence-one-time-empty-1") });
  assert.equal(pending.statusCode, 202);
  assert.equal(pending.json().outcome, "reconciliation_pending");
  assert.ok(pending.json().snapshot.snapshotGeneration > 0);
  await control("/__stale-actions");
  const stale = await app.inject({ method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth, payload: action("forced-stale", "occurrence-one-time-empty-1") });
  assert.equal(stale.statusCode, 409);
  await control("/__activity-log-full");
  const full = await app.inject({ method: "POST", url: "/tasks/task-one-time-empty/extend", headers: auth, payload: action("log-full", "occurrence-one-time-empty-1") });
  assert.equal(full.statusCode, 422);
  await control("/__rate-limit");
  const limited = await app.inject({ method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth, payload: action("limited", "occurrence-one-time-empty-1") });
  assert.equal(limited.statusCode, 429);
  assert.equal(limited.headers["retry-after"], "7");
  await app.inject({ method: "POST", url: "/__fail-snapshot", payload: { enabled: true } });
  assert.equal((await app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth })).statusCode, 500);
  await app.inject({ method: "POST", url: "/__fail-actions", payload: { enabled: true } });
  assert.equal((await app.inject({ method: "POST", url: "/tasks/task-one-time-empty/start", headers: auth, payload: action("failed", "occurrence-one-time-empty-1") })).statusCode, 500);
});

test("all fixed routes require auth and recorded calls retain only action metadata", async (t) => {
  const app = await buildServer();
  t.after(() => app.close());
  const protectedRoutes = [
    "/tasks/snapshot", "/projects", "/tasks", "/tasks/task-one-time-empty/start", "/tasks/task-one-time-empty/done",
    "/tasks/task-one-time-empty/postpone-start", "/tasks/task-one-time-empty/extend", "/tasks/task-one-time-empty/reschedule",
    "/tasks/task-one-time-empty/update", "/tasks/task-one-time-partial/checklist",
    "/tasks/task-one-time-partial/checklist/child-one-time-partial-open/update",
    "/tasks/task-one-time-partial/checklist/child-one-time-partial-open/delete",
    "/tasks/task-one-time-partial/checklist/child-one-time-partial-open/check",
    "/tasks/task-one-time-partial/checklist/child-one-time-partial-open/uncheck", "/review/dismiss", "/device/check-in"
  ];
  for (const url of protectedRoutes) {
    const response = await app.inject({ method: url === "/tasks/snapshot" || url === "/projects" ? "GET" : "POST", url });
    assert.equal(response.statusCode, 401, url);
  }
  await app.inject({
    method: "POST", url: "/tasks/task-one-time-empty/start", headers: { ...auth, authorization: "Bearer test-token" },
    payload: { ...action("recorded", "occurrence-one-time-empty-1"), title: "must not be recorded" }
  });
  const calls = (await app.inject({ method: "GET", url: "/__calls" })).json().calls;
  assert.deepEqual(calls[0].body, { actionId: "recorded", deviceId: "android-test-1", expectedOccurrenceKey: "occurrence-one-time-empty-1" });
  assert.doesNotMatch(JSON.stringify(calls), /Bearer|test-token|must not be recorded/);
});
