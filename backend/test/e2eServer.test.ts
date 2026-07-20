import { describe, expect, it } from "vitest";
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
      expect(body.tasks).toHaveLength(15);
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
      const partialChildren = byId.get("task-one-time-partial").checklistItems;
      expect(partialChildren.map((child: { id: string }) => child.id).sort())
        .toEqual(["child-one-time-partial-done", "child-one-time-partial-open"]);
      expect(partialChildren.find((child: { id: string }) => child.id === "child-one-time-partial-done").checked).toBe(true);
    });
  });

  it("serves the two fixture projects and rejects missing auth", async () => {
    await withServer(async (server) => {
      const projects = await server.app.inject({ method: "GET", url: "/projects", headers: auth });
      expect(projects.statusCode).toBe(200);
      expect(projects.json().projects.map((project: { id: string; name: string }) => [project.id, project.name]))
        .toEqual([["project-personal", "Test Personal"], ["project-test", "Test Shared"]]);
      const unauthorized = await server.app.inject({ method: "GET", url: "/tasks/snapshot" });
      expect(unauthorized.statusCode).toBe(401);
    });
  });
});

describe("happy-path mutation conformance (every action completes, never goes pending)", () => {
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
      expect(after.occurrenceKey).not.toBe(before.occurrenceKey);
      expect(after.checkedCount).toBe(0);
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
      expect((await snapshotTask(server, "task-deadline")).durationMinutes).toBe(90);
    });
  });

  it("reschedules a task", async () => {
    await withServer(async (server) => {
      const task = await snapshotTask(server, "task-reschedule");
      const dueDatetime = new Date(Date.now() + 120 * 60_000).toISOString();
      const body = await mutate(
        server,
        "/tasks/task-reschedule/reschedule",
        action({ expectedOccurrenceKey: task.occurrenceKey, dueDatetime, durationMinutes: 45 })
      );
      const after = await snapshotTask(server, "task-reschedule");

      expect(body.outcome).toBe("completed");
      expect(after.dueDatetime).toBe(dueDatetime);
      expect(after.durationMinutes).toBe(45);
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
      const body = await mutate(
        server,
        "/tasks",
        action({
          title: "E2E created task",
          projectId: "project-test",
          assigneeId: "user-me",
          dueDatetime: new Date(Date.now() + 240 * 60_000).toISOString(),
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
        checklistItems: [{ title: "Created child", description: "Created with the root" }]
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
      const body = await mutate(server, "/review/dismiss", {});

      expect(typeof body.snoozedUntil).toBe("string");
    });
  });
});
