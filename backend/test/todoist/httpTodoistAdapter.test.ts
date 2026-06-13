import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpTodoistAdapter } from "../../src/todoist/httpTodoistAdapter.js";

describe("HttpTodoistAdapter", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("creates tasks through REST with optional assignee", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      id: "todoist-created",
      content: "Pay bill",
      project_id: "project-1",
      responsible_uid: "user-self",
      due: { datetime: "2026-05-08T16:00:00Z" },
      duration: { amount: 30, unit: "minute" },
      added_by_uid: "user-self",
      updated_at: "2026-05-08T10:00:00Z"
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const created = await adapter.createTask({
      title: "Pay bill",
      projectId: "project-1",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30
    });

    expect(fetchMock.mock.calls[0]?.[0]).toBe("https://todoist.test/api/v1/tasks");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe("POST");
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      content: "Pay bill",
      project_id: "project-1",
      assignee_id: "user-self",
      due_datetime: "2026-05-08T16:00:00Z",
      duration: 30,
      duration_unit: "minute"
    });
    expect(created.task).toMatchObject({
      todoistTaskId: "todoist-created",
      projectId: "project-1",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30
    });
  });

  it("omits assignee_id when creating a task without assignment", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      id: "todoist-personal",
      content: "Personal task",
      project_id: "project-personal",
      due: { datetime: "2026-05-08T18:00:00Z" },
      duration: { amount: 45, unit: "minute" },
      added_by_uid: "user-self",
      updated_at: "2026-05-08T10:00:00Z"
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.createTask({
      title: "Personal task",
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-05-08T18:00:00Z",
      durationMinutes: 45
    });

    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).not.toHaveProperty("assignee_id");
  });

  it("maps Todoist checked, completed_at, and is_deleted fields", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      sync_token: "next-token",
      items: [
        {
          id: "completed-1",
          content: "Completed task",
          checked: true,
          completed_at: "2026-05-08T12:00:00Z",
          updated_at: "2026-05-08T12:01:00Z"
        },
        {
          id: "deleted-1",
          content: "Deleted task",
          is_deleted: true,
          updated_at: "2026-05-08T12:02:00Z"
        }
      ]
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const result = await adapter.sync("*");

    expect(result.tasks[0]).toMatchObject({
      todoistTaskId: "completed-1",
      completedAt: "2026-05-08T12:00:00Z",
      deletedAt: null
    });
    expect(result.tasks[1]).toMatchObject({
      todoistTaskId: "deleted-1",
      completedAt: null,
      deletedAt: "2026-05-08T12:02:00Z"
    });
  });

  it("sync maps Todoist projects", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      sync_token: "project-token",
      items: [],
      projects: [
        {
          id: "project-family",
          name: "Family",
          shared: true,
          can_assign_tasks: true,
          inbox_project: false,
          is_deleted: false,
          is_archived: false,
          updated_at: "2026-06-13T10:00:00Z"
        }
      ]
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const result = await adapter.sync("*");

    const body = new URLSearchParams(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(JSON.parse(body.get("resource_types") ?? "[]")).toEqual(["items", "projects"]);
    expect(result.projects).toEqual([
      {
        todoistProjectId: "project-family",
        name: "Family",
        shared: true,
        canAssignTasks: true,
        isInboxProject: false,
        isDeleted: false,
        isArchived: false,
        rawJson: {
          id: "project-family",
          name: "Family",
          shared: true,
          can_assign_tasks: true,
          inbox_project: false,
          is_deleted: false,
          is_archived: false,
          updated_at: "2026-06-13T10:00:00Z"
        },
        updatedAt: "2026-06-13T10:00:00Z"
      }
    ]);
  });
});
