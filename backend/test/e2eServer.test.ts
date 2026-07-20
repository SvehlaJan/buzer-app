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
