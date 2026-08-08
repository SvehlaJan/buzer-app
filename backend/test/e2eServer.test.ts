import { describe, expect, it, vi } from "vitest";
import { buildE2EServer, type E2EServer } from "./e2eServer.js";

const auth = { authorization: "Bearer test-token" };

async function withServer(run: (server: E2EServer) => Promise<void>): Promise<void> {
  const server = await buildE2EServer();
  try {
    await run(server);
  } finally {
    await server.app.close();
  }
}

async function snapshot(server: E2EServer): Promise<Record<string, any>> {
  const response = await server.app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth });
  expect(response.statusCode).toBe(200);
  return response.json();
}

async function snapshotTask(server: E2EServer, id: string): Promise<Record<string, any>> {
  const task = (await snapshot(server)).tasks.find((candidate: { id: string }) => candidate.id === id);
  expect(task, `missing fixture task ${id}`).toBeDefined();
  return task;
}

const device = { deviceId: "e2e-device" };
let actionCounter = 0;

function action(extra: Record<string, unknown> = {}): Record<string, unknown> {
  actionCounter += 1;
  return { actionId: `e2e-action-${actionCounter}`, ...device, ...extra };
}

async function mutate(
  server: E2EServer,
  url: string,
  payload: Record<string, unknown>,
  expectedStatus = 200
): Promise<Record<string, any>> {
  const response = await server.app.inject({ method: "POST", url, headers: auth, payload });
  expect(response.statusCode, `${url} -> ${response.body}`).toBe(expectedStatus);
  return response.json();
}

describe("seeding", () => {
  it("serves the fixture snapshot with real-backend derivations", async () => {
    await withServer(async (server) => {
      const body = await snapshot(server);
      expect(body.tasks).toHaveLength(27);
      const byId = new Map<string, any>(body.tasks.map((task: { id: string }) => [task.id, task]));
      expect(byId.get("task-start").title).toBe("Start blocker fixture");
      expect(byId.get("task-start").occurrenceKey).toBe("occurrence-task-start-1");
      expect(byId.get("task-start").isAssignedToUser).toBe(true);
      expect(byId.get("task-deadline").state).toBe("started");
      expect(byId.get("task-invalid").state).toBe("invalid");
      expect(byId.get("task-unsupported-hierarchy").hierarchyStatus).toBe("unsupported");
      expect(byId.get("task-unsupported-hierarchy").hierarchyViolation).toBe("child_has_schedule");
      expect(byId.get("task-custom-recurrence").recurrence.kind).toBe("custom");
      expect(byId.get("task-recurring-all-checked").recurrence.preset).toBe("daily");
      expect(byId.get("task-recurring-all-checked").checkedCount).toBe(2);
      expect(byId.get("task-recurring-all-checked").totalCount).toBe(2);
      expect(byId.get("task-reconciliation-pending").reconciliationStatus).toBe("pending");
      expect(byId.has("task-assigned-other")).toBe(false);
      expect(byId.get("task-unassigned-root")).toMatchObject({
        assigneeId: null,
        isAssignedToUser: false,
        validationErrors: [],
        hierarchyStatus: "valid"
      });
      expect(byId.get("task-unassigned-root").checklistItems).toEqual([
        expect.objectContaining({ id: "child-unassigned-1", checked: false })
      ]);
      expect(byId.get("task-incomplete-checklist")).toMatchObject({
        checkedCount: 0,
        totalCount: 1
      });
      const partialChildren = byId.get("task-one-time-partial").checklistItems;
      expect(partialChildren.map((child: { id: string }) => child.id).sort())
        .toEqual(["child-one-time-partial-done", "child-one-time-partial-open"]);
      expect(partialChildren.find((child: { id: string }) => child.id === "child-one-time-partial-done").checked).toBe(true);
    });
  });

  it("serves the shared collaborator project contract and rejects missing auth", async () => {
    await withServer(async (server) => {
      const projects = await server.app.inject({ method: "GET", url: "/projects", headers: auth });
      expect(projects.statusCode).toBe(200);
      expect(projects.json().projects).toEqual([
        {
          id: "project-personal",
          name: "Test Personal",
          canAssignTasks: false,
          shared: false,
          isInboxProject: false,
          collaboratorsReady: true,
          collaborators: [{ id: "user-me", displayName: "Me", email: null, isCurrentUser: true }]
        },
        {
          id: "project-test",
          name: "Test Shared",
          canAssignTasks: true,
          shared: true,
          isInboxProject: false,
          collaboratorsReady: true,
          collaborators: [
            { id: "user-me", displayName: "Me", email: null, isCurrentUser: true },
            { id: "user-wife", displayName: "Wife", email: "wife@example.test", isCurrentUser: false }
          ]
        }
      ]);
      const unauthorized = await server.app.inject({ method: "GET", url: "/tasks/snapshot" });
      expect(unauthorized.statusCode).toBe(401);
    });
  });
});

describe("happy-path mutation conformance (every action completes, never goes pending)", () => {
  it("holds and releases exactly the next forced sync response", async () => {
    await withServer(async (server) => {
      const armed = await server.app.inject({ method: "POST", url: "/__hold-next-sync", payload: {} });
      expect(armed.statusCode).toBe(200);
      expect(armed.json()).toEqual({ state: "armed" });

      const syncPromise = server.app.inject({
        method: "POST",
        url: "/tasks/sync",
        headers: auth,
        payload: {}
      });

      await vi.waitFor(async () => {
        const status = await server.app.inject({ method: "GET", url: "/__sync-hold" });
        expect(status.json()).toEqual({ state: "waiting" });
      });

      const release = await server.app.inject({ method: "POST", url: "/__release-sync", payload: {} });
      expect(release.statusCode).toBe(200);
      expect(release.json()).toEqual({ state: "released" });

      const syncResponse = await syncPromise;
      expect(syncResponse.statusCode).toBe(200);
      const idle = await server.app.inject({ method: "GET", url: "/__sync-hold" });
      expect(idle.json()).toEqual({ state: "idle" });
    });
  });

  it("records the exact serialized authority returned by a completed forced sync", async () => {
    await withServer(async (server) => {
      await server.app.inject({ method: "POST", url: "/__reset", payload: {} });

      const response = await server.app.inject({
        method: "POST",
        url: "/tasks/sync",
        headers: auth,
        payload: {}
      });
      expect(response.statusCode).toBe(200);
      const responseSnapshot = response.json();

      const callsResponse = await server.app.inject({ method: "GET", url: "/__calls" });
      expect(callsResponse.json().calls).toEqual([
        expect.objectContaining({
          method: "POST",
          path: "/tasks/sync",
          responseCompletedAt: expect.any(String),
          responseStatusCode: 200,
          responseAuthority: {
            serverTime: responseSnapshot.serverTime,
            snapshotGeneration: responseSnapshot.snapshotGeneration
          }
        })
      ]);
    });
  });

  it("records the response-completed boundary for correlated Android mutations", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-reschedule");
      const actionId = "e2e-response-correlation";

      await mutate(
        server,
        "/tasks/task-reschedule/update",
        {
          actionId,
          ...device,
          expectedOccurrenceKey: task.occurrenceKey,
          rootPatch: {
            expectedTaskRevision: task.taskRevision,
            durationMinutes: 30
          },
          childMutations: []
        }
      );

      const callsResponse = await server.app.inject({ method: "GET", url: "/__calls" });
      const call = callsResponse.json().calls.find(
        (candidate: { path: string; body: { actionId?: string } }) =>
          candidate.path === "/tasks/task-reschedule/update" && candidate.body.actionId === actionId
      );
      expect(call).toMatchObject({
        responseStatusCode: 200,
        responseCompletedAt: expect.any(String)
      });
    });
  });

  it("starts a task", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      const body = await mutate(
        server,
        "/tasks/task-start/start",
        action({ expectedOccurrenceKey: task.occurrenceKey })
      );

      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-start")).state).toBe("started");
    });
  });

  it("completes a one-time task", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-one-time-empty");
      const body = await mutate(
        server,
        "/tasks/task-one-time-empty/done",
        action({ expectedOccurrenceKey: task.occurrenceKey })
      );

      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-one-time-empty")).isCompleted).toBe(true);
    });
  });

  it("advances a recurring occurrence and resets its checklist", async () => {
    await withServer(async (server) => {
      const before = await snapshotTask(server, "task-recurring-all-checked");
      const body = await mutate(
        server,
        "/tasks/task-recurring-all-checked/done",
        action({ expectedOccurrenceKey: before.occurrenceKey })
      );
      const after = await snapshotTask(server, "task-recurring-all-checked");

      expect(body.outcome).toBe("completed");
      expect(after.isCompleted).toBe(false);
      expect(after.dueDatetime).not.toBe(before.dueDatetime);
      expect(Date.parse(after.dueDatetime) - Date.parse(before.dueDatetime)).toBe(24 * 60 * 60_000);
      expect(after.occurrenceKey).not.toBe(before.occurrenceKey);
      expect(after.checkedCount).toBe(0);
      expect(after.checklistItems).toHaveLength(before.checklistItems.length);
      expect(after.checklistItems.every((child: { checked: boolean }) => !child.checked)).toBe(true);
    });
  });

  it("postpones a start blocker", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      const body = await mutate(
        server,
        "/tasks/task-start/postpone-start",
        action({ expectedOccurrenceKey: task.occurrenceKey })
      );

      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-start")).startDismissCount).toBe(1);
    });
  });

  it("extends a deadline by thirty minutes", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-deadline");
      const body = await mutate(
        server,
        "/tasks/task-deadline/extend",
        action({
          expectedOccurrenceKey: task.occurrenceKey,
          expectedTaskRevision: task.taskRevision
        })
      );

      expect(body.outcome).toBe("completed");
      expect((await snapshotTask(server, "task-deadline")).durationMinutes).toBe(task.durationMinutes + 30);
    });
  });

  it("updates a complete schedule through normal reconciliation and keeps an action retry idempotent", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-reschedule");
      const dueDatetime = "2026-08-03T16:47:00.000Z";
      const actionId = "e2e-complete-schedule-update";
      const request = {
        actionId,
        ...device,
        expectedOccurrenceKey: task.occurrenceKey,
        rootPatch: {
          expectedTaskRevision: task.taskRevision,
          title: task.title,
          description: null,
          projectId: task.projectId,
          assigneeId: task.assigneeId,
          dueDatetime,
          durationMinutes: 30
        },
        childMutations: []
      };
      const body = await mutate(
        server,
        "/tasks/task-reschedule/update",
        request
      );
      const retry = await mutate(server, "/tasks/task-reschedule/update", request);
      const after = await snapshotTask(server, "task-reschedule");

      expect(body.outcome).toBe("completed");
      expect(retry.outcome).toBe("completed");
      expect(after.dueDatetime).toBe(dueDatetime);
      expect(after.durationMinutes).toBe(30);
      expect(server.db.prepare(
        "select step_key as stepKey from task_action_commands where action_id = ? order by step_key"
      ).all(actionId)).toEqual([{ stepKey: "item_update" }]);
    });
  });

  it("completes the invalid deadline-blocker fixture update after restoring its duration", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-invalid-blocker");
      const body = await mutate(
        server,
        "/tasks/task-invalid-blocker/update",
        action({
          expectedOccurrenceKey: task.occurrenceKey,
          rootPatch: {
            expectedTaskRevision: task.taskRevision,
            durationMinutes: 30
          },
          childMutations: []
        })
      );
      const after = await snapshotTask(server, "task-invalid-blocker");

      expect(body.outcome).toBe("completed");
      expect(after.durationMinutes).toBe(30);
      expect(after.dueDatetime).toBe(task.dueDatetime);
    });
  });

  it("edits a root and a checklist child through the task update route", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-one-time-partial");
      const child = task.checklistItems.find(
        (candidate: { id: string }) => candidate.id === "child-one-time-partial-open"
      );
      const body = await mutate(
        server,
        "/tasks/task-one-time-partial/update",
        action({
          expectedOccurrenceKey: task.occurrenceKey,
          rootPatch: {
            expectedTaskRevision: task.taskRevision,
            title: "Edited root fixture"
          },
          childMutations: [{
            type: "edit",
            clientMutationId: "root-update-child-edit",
            childId: child.id,
            expectedChildRevision: child.childRevision,
            title: "Edited child fixture",
            description: "Edited from the root update route"
          }]
        })
      );
      const after = await snapshotTask(server, "task-one-time-partial");

      expect(body.outcome).toBe("completed");
      expect(after.title).toBe("Edited root fixture");
      expect(after.checklistItems.find(
        (candidate: { id: string }) => candidate.id === "child-one-time-partial-open"
      )).toMatchObject({ title: "Edited child fixture", description: "Edited from the root update route" });
    });
  });

  it("creates a root with a checklist child", async () => {
    await withServer(async (server) => {
      const dueDatetime = new Date(Date.now() + 240 * 60_000).toISOString();
      const body = await mutate(
        server,
        "/tasks",
        action({
          title: "E2E created task",
          projectId: "project-test",
          assigneeId: "user-me",
          dueDatetime,
          durationMinutes: 30,
          description: null,
          checklistDrafts: [{
            clientMutationId: "created-child",
            title: "Created child",
            description: "Created with the root"
          }]
        })
      );
      const created = (await snapshot(server)).tasks.find(
        (candidate: { title: string }) => candidate.title === "E2E created task"
      );

      expect(body.outcome).toBe("completed");
      expect(created).toMatchObject({
        projectId: "project-test",
        assigneeId: "user-me",
        dueDatetime,
        durationMinutes: 30,
        checklistItems: [{ title: "Created child", description: "Created with the root" }]
      });
    });
  });

  it("allows an unscheduled Wife task and clears an existing task schedule", async () => {
    await withServer(async (server) => {
      const created = await mutate(
        server,
        "/tasks",
        action({
          title: "Wife can schedule later",
          projectId: "project-test",
          assigneeId: "user-wife",
          dueDatetime: null,
          durationMinutes: null
        })
      );
      expect(created.outcome).toBe("completed");
      const createdTask = server.adapter.world.state().tasks.find(
        (candidate) => candidate.title === "Wife can schedule later"
      );
      expect(createdTask).toMatchObject({
        projectId: "project-test",
        assigneeId: "user-wife",
        dueDatetime: null,
        durationMinutes: null
      });

      const existing = await snapshotTask(server, "task-one-time-empty");
      const updated = await mutate(
        server,
        "/tasks/task-one-time-empty/update",
        action({
          expectedOccurrenceKey: existing.occurrenceKey,
          rootPatch: {
            expectedTaskRevision: existing.taskRevision,
            assigneeId: "user-wife",
            dueDatetime: null,
            durationMinutes: null
          },
          childMutations: []
        })
      );
      expect(updated.outcome).toBe("completed");
      expect(server.adapter.world.getTask("todoist-task-one-time-empty")).toMatchObject({
        assigneeId: "user-wife",
        dueDatetime: null,
        durationMinutes: null
      });
    });
  });

  it("adds, updates, checks, unchecks, and deletes a checklist child", async () => {
    await withServer(async (server) => {
      const before = await snapshotTask(server, "task-one-time-partial");
      const added = await mutate(
        server,
        "/tasks/task-one-time-partial/checklist",
        action({
          expectedOccurrenceKey: before.occurrenceKey,
          clientMutationId: "checklist-add",
          title: "New checklist child",
          description: null
        })
      );
      const afterAdd = await snapshotTask(server, "task-one-time-partial");
      const child = afterAdd.checklistItems.find(
        (candidate: { title: string }) => candidate.title === "New checklist child"
      );
      expect(added.outcome).toBe("completed");
      expect(child).toMatchObject({ checked: false, description: null });
      const updated = await mutate(
        server,
        `/tasks/task-one-time-partial/checklist/${child.id}/update`,
        action({
          expectedOccurrenceKey: afterAdd.occurrenceKey,
          expectedChildRevision: child.childRevision,
          title: "Updated checklist child",
          description: "Updated through the focused route"
        })
      );
      const afterUpdate = await snapshotTask(server, "task-one-time-partial");
      const updatedChild = afterUpdate.checklistItems.find(
        (candidate: { id: string }) => candidate.id === child.id
      );
      expect(updated.outcome).toBe("completed");
      expect(updatedChild).toMatchObject({
        checked: false,
        title: "Updated checklist child",
        description: "Updated through the focused route"
      });
      const checked = await mutate(
        server,
        `/tasks/task-one-time-partial/checklist/${child.id}/check`,
        action({
          expectedOccurrenceKey: afterUpdate.occurrenceKey,
          expectedChildRevision: updatedChild.childRevision
        })
      );
      const afterCheck = await snapshotTask(server, "task-one-time-partial");
      const checkedChild = afterCheck.checklistItems.find(
        (candidate: { id: string }) => candidate.id === child.id
      );
      expect(checked.outcome).toBe("completed");
      expect(checkedChild).toMatchObject({ checked: true });
      const unchecked = await mutate(
        server,
        `/tasks/task-one-time-partial/checklist/${child.id}/uncheck`,
        action({
          expectedOccurrenceKey: afterCheck.occurrenceKey,
          expectedChildRevision: checkedChild.childRevision
        })
      );
      const afterUncheck = await snapshotTask(server, "task-one-time-partial");
      const uncheckedChild = afterUncheck.checklistItems.find(
        (candidate: { id: string }) => candidate.id === child.id
      );
      expect(unchecked.outcome).toBe("completed");
      expect(uncheckedChild).toMatchObject({ checked: false });
      const deleted = await mutate(
        server,
        `/tasks/task-one-time-partial/checklist/${child.id}/delete`,
        action({
          expectedOccurrenceKey: afterUncheck.occurrenceKey,
          expectedChildRevision: uncheckedChild.childRevision
        })
      );
      const afterDelete = await snapshotTask(server, "task-one-time-partial");

      expect(deleted.outcome).toBe("completed");
      expect(afterDelete.checklistItems.find(
        (candidate: { id: string }) => candidate.id === child.id
      )).toBeUndefined();
    });
  });

  it("dismisses morning review", async () => {
    await withServer(async (server) => {
      const before = Date.now();
      const body = await mutate(server, "/review/dismiss", {});
      const after = Date.now();
      const snoozedUntil = Date.parse(body.snoozedUntil);

      expect(typeof body.snoozedUntil).toBe("string");
      expect(snoozedUntil).toBeGreaterThanOrEqual(before + 5 * 60_000 - 1_000);
      expect(snoozedUntil).toBeLessThanOrEqual(after + 5 * 60_000 + 1_000);
    });
  });
});

describe("debug surface parity", () => {
  it("selects each supported scenario and rejects an unknown name", async () => {
    await withServer(async (server) => {
      for (const name of ["after_command_sent_timeout", "sync_lag_effect_unknown"] as const) {
        const response = await server.app.inject({ method: "POST", url: "/__scenario", payload: { name } });
        expect(response.statusCode).toBe(200);
        expect(response.json()).toEqual({ scenario: name });
      }

      const unknown = await server.app.inject({ method: "POST", url: "/__scenario", payload: { name: "nope" } });
      expect(unknown.statusCode).toBe(400);
    });
  });

  it("keeps metadata-only and full mutation call views separate", async () => {
    await withServer(async (server) => {
      await mutate(server, "/tasks", action({
        title: "Recorded task",
        description: "must-not-be-recorded",
        projectId: "project-test",
        assigneeId: "user-me",
        dueDatetime: new Date(Date.now() + 120 * 60_000).toISOString(),
        durationMinutes: 30
      }));

      const calls = (await server.app.inject({ method: "GET", url: "/__calls" })).json().calls;
      const recorded = calls.find((call: { path: string }) => call.path === "/tasks");
      expect(recorded).toMatchObject({ method: "POST", path: "/tasks" });
      expect(Object.keys(recorded.body).sort()).toEqual(["actionId", "deviceId"]);
      expect(recorded.body).not.toHaveProperty("title");

      const fullCalls = (await server.app.inject({ method: "GET", url: "/__calls/full" })).json().calls;
      const fullRecorded = fullCalls.find((call: { path: string }) => call.path === "/tasks");
      expect(fullRecorded).toMatchObject({
        method: "POST",
        path: "/tasks",
        payload: {
          title: "Recorded task",
          description: "must-not-be-recorded",
          projectId: "project-test",
          assigneeId: "user-me"
        }
      });
    });
  });

  it("records a forced action failure and includes the current snapshot", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      await server.app.inject({ method: "POST", url: "/__fail-actions", payload: { enabled: true } });

      const failed = await mutate(server, "/tasks/task-start/start", action({ expectedOccurrenceKey: task.occurrenceKey }), 500);
      expect(failed).toMatchObject({ code: "forced_failure", snapshot: expect.any(Object) });
      expect(failed).not.toHaveProperty("message");
      const calls = (await server.app.inject({ method: "GET", url: "/__calls" })).json().calls;
      expect(calls.some((call: { path: string }) => call.path === "/tasks/task-start/start")).toBe(true);
    });
  });

  it("forces old-fake-compatible snapshot and project failures", async () => {
    await withServer(async (server) => {
      await server.app.inject({ method: "POST", url: "/__fail-snapshot", payload: { enabled: true } });
      const failedSnapshot = await server.app.inject({ method: "GET", url: "/tasks/snapshot", headers: auth });
      expect(failedSnapshot.statusCode).toBe(500);
      expect(failedSnapshot.json()).toEqual({ error: "forced_snapshot_failure" });

      await server.app.inject({ method: "POST", url: "/__fail-projects", payload: { enabled: true } });
      const failedProjects = await server.app.inject({ method: "GET", url: "/projects", headers: auth });
      expect(failedProjects.statusCode).toBe(500);
      expect(failedProjects.json()).toEqual({ error: "forced_project_failure" });
    });
  });

  it("fails exactly one child add through the explicit adapter control", async () => {
    await withServer(async (server) => {
      const root = await server.adapter.itemAdd({
        kind: "root",
        commandUuid: "control-root-add",
        tempId: "control-root-temp",
        title: "Control root",
        description: null,
        projectId: "project-test",
        assigneeId: "user-me",
        due: { kind: "preserve" },
        duration: null
      });
      await server.app.inject({
        method: "POST",
        url: "/__fail-next-child-add",
        payload: { enabled: true }
      });

      await expect(server.adapter.itemAdd({
        kind: "child",
        commandUuid: "control-child-fail",
        tempId: "control-child-fail-temp",
        title: "First child",
        description: null,
        projectId: "project-test",
        parentTodoistTaskId: root.todoistTaskId!
      })).rejects.toMatchObject({ code: "e2e_forced_child_create_failure" });

      await expect(server.adapter.itemAdd({
        kind: "child",
        commandUuid: "control-child-success",
        tempId: "control-child-success-temp",
        title: "Second child",
        description: null,
        projectId: "project-test",
        parentTodoistTaskId: root.todoistTaskId!
      })).resolves.toEqual({ todoistTaskId: expect.any(String) });
    });
  });

  it("drives after_command_sent_timeout through real reconciliation", async () => {
    await withServer(async (server) => {
      await server.app.inject({ method: "POST", url: "/__scenario", payload: { name: "after_command_sent_timeout" } });
      const task = await snapshotTask(server, "task-one-time-empty");

      const body = await mutate(
        server,
        "/tasks/task-one-time-empty/done",
        action({ expectedOccurrenceKey: task.occurrenceKey }),
        202
      );
      expect(body.outcome).toBe("reconciliation_pending");
      expect((await snapshotTask(server, "task-one-time-empty")).reconciliationStatus).toBe("pending");
    });
  });

  it("drives sync_lag_effect_unknown through real reconciliation", async () => {
    await withServer(async (server) => {
      await server.app.inject({ method: "POST", url: "/__scenario", payload: { name: "sync_lag_effect_unknown" } });
      const task = await snapshotTask(server, "task-reschedule");
      const dueDatetime = new Date(Date.now() + 90 * 60_000).toISOString();

      const body = await mutate(
        server,
        "/tasks/task-reschedule/update",
        action({
          expectedOccurrenceKey: task.occurrenceKey,
          rootPatch: {
            expectedTaskRevision: task.taskRevision,
            dueDatetime,
            durationMinutes: 45
          },
          childMutations: []
        }),
        202
      );
      expect(body.outcome).toBe("reconciliation_pending");
      expect((await snapshotTask(server, "task-reschedule")).reconciliationStatus).toBe("pending");
    });
  });

  it("resets calls and controls, reseeds state, and keeps snapshot generation monotonic", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-start");
      await mutate(server, "/tasks/task-start/start", action({ expectedOccurrenceKey: task.occurrenceKey }));
      const before = (await snapshot(server)).snapshotGeneration;
      await server.app.inject({ method: "POST", url: "/__fail-snapshot", payload: { enabled: true } });
      await server.app.inject({ method: "POST", url: "/__scenario", payload: { name: "after_command_sent_timeout" } });

      const reset = await server.app.inject({ method: "POST", url: "/__reset", payload: {} });
      expect(reset.statusCode).toBe(200);
      const after = await snapshot(server);
      expect(after.snapshotGeneration).toBeGreaterThan(before);
      const reseeded = await snapshotTask(server, "task-start");
      expect(reseeded.state).toBe("planned");
      expect((await server.app.inject({ method: "GET", url: "/__calls" })).json().calls).toHaveLength(0);
      expect((await server.app.inject({ method: "GET", url: "/__calls/full" })).json().calls).toHaveLength(0);
      expect((await mutate(
        server,
        "/tasks/task-start/start",
        action({ expectedOccurrenceKey: reseeded.occurrenceKey })
      )).outcome).toBe("completed");
    });
  });

  it("validates and applies an optional named Morning Review reset scenario", async () => {
    await withServer(async (server) => {
      const invalid = await server.app.inject({
        method: "POST",
        url: "/__reset",
        payload: { morningReviewScenario: "invalid" }
      });
      expect(invalid.statusCode).toBe(200);
      expect((await snapshot(server)).tasks.map((task: { id: string }) => task.id)).toEqual(["task-invalid"]);

      const rejected = await server.app.inject({
        method: "POST",
        url: "/__reset",
        payload: { morningReviewScenario: "unknown" }
      });
      expect(rejected.statusCode).toBe(400);
      expect(rejected.json()).toMatchObject({ error: "unknown_morning_review_scenario" });
    });
  });
});
