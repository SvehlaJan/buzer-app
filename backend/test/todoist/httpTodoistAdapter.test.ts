import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpTodoistAdapter } from "../../src/todoist/httpTodoistAdapter.js";

describe("HttpTodoistAdapter", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("sends item_add temp_id at the command level", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => {
      const body = new URLSearchParams(String(init?.body));
      const commands = JSON.parse(body.get("commands") ?? "[]") as Array<{ uuid: string; temp_id?: string }>;
      return new Response(JSON.stringify({
        sync_status: { [commands[0]?.uuid ?? ""]: "ok" },
        temp_id_mapping: { [commands[0]?.temp_id ?? ""]: "todoist-1" }
      }));
    });
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const created = await adapter.createTask({
      title: "Pay bill",
      projectId: "project-1",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30
    });

    const init = fetchMock.mock.calls[0]?.[1];
    const body = new URLSearchParams(String(init?.body));
    const commands = JSON.parse(body.get("commands") ?? "[]") as Array<{
      type: string;
      uuid: string;
      temp_id?: string;
      args: Record<string, unknown>;
    }>;
    expect(created.todoistTaskId).toBe("todoist-1");
    expect(commands[0]).toMatchObject({
      type: "item_add",
      args: {
        content: "Pay bill",
        project_id: "project-1",
        responsible_uid: "user-self"
      }
    });
    expect(commands[0]?.uuid).toEqual(expect.any(String));
    expect(commands[0]?.temp_id).toEqual(expect.any(String));
    expect(commands[0]?.args).not.toHaveProperty("temp_id");
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
});
