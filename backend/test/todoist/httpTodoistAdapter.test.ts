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
      description: "Bring invoice copy",
      responsible_uid: "43186358",
      due: { datetime: "2026-05-08T16:00:00Z" },
      duration: { amount: 30, unit: "minute" },
      added_by_uid: "43186358",
      updated_at: "2026-05-08T10:00:00Z"
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const created = await adapter.createTask({
      title: "Pay bill",
      projectId: "project-1",
      assigneeId: "43186358",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      description: "  Bring invoice copy  "
    });

    expect(fetchMock.mock.calls[0]?.[0]).toBe("https://todoist.test/api/v1/tasks");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe("POST");
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      content: "Pay bill",
      project_id: "project-1",
      assignee_id: 43186358,
      due_datetime: "2026-05-08T16:00:00Z",
      duration: 30,
      duration_unit: "minute",
      description: "Bring invoice copy"
    });
    expect(created.task).toMatchObject({
      todoistTaskId: "todoist-created",
      projectId: "project-1",
      assigneeId: "43186358",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      description: "Bring invoice copy"
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

  it("maps REST create due.date datetimes to normalized dueDatetime", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      id: "todoist-created",
      content: "Pay bill",
      project_id: "project-1",
      responsible_uid: "43186358",
      due: { date: "2026-05-08T16:00:00.000000Z" },
      duration: { amount: 30, unit: "minute" },
      added_by_uid: "43186358",
      updated_at: "2026-05-08T10:00:00Z"
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const created = await adapter.createTask({
      title: "Pay bill",
      projectId: "project-1",
      assigneeId: "43186358",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30
    });

    expect(created.task.dueDatetime).toBe("2026-05-08T16:00:00Z");
  });

  it("rejects non-numeric REST assignee IDs before fetch", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await expect(adapter.createTask({
      title: "Pay bill",
      projectId: "project-1",
      assigneeId: "user-self",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30
    })).rejects.toThrow("todoist_assignee_id_must_be_numeric");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends only content when optional create fields are null", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      id: "todoist-inbox",
      content: "Inbox task",
      added_by_uid: "43186358",
      updated_at: "2026-05-08T10:00:00Z"
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.createTask({
      title: "Inbox task",
      projectId: null,
      assigneeId: null,
      dueDatetime: null,
      durationMinutes: null,
      description: "   "
    });

    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      content: "Inbox task"
    });
  });

  it("reschedules without description when omitted", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({
      sync_status: {}
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.rescheduleTask({
      todoistTaskId: "todoist-1",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30
    }, "reschedule-command");

    const body = new URLSearchParams(String(fetchMock.mock.calls[0]?.[1]?.body));
    const commands = JSON.parse(body.get("commands") ?? "[]") as Array<{ args: Record<string, unknown> }>;
    expect(commands[0]?.args).not.toHaveProperty("description");
  });

  it("reschedule trims, sets, and clears descriptions", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({
      sync_status: {}
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.rescheduleTask({
      todoistTaskId: "todoist-1",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 30,
      description: "  Updated gate code: 1357  "
    }, "reschedule-set");
    await adapter.rescheduleTask({
      todoistTaskId: "todoist-1",
      dueDatetime: "2026-05-08T17:00:00Z",
      durationMinutes: 45,
      description: "   "
    }, "reschedule-blank");
    await adapter.rescheduleTask({
      todoistTaskId: "todoist-1",
      dueDatetime: "2026-05-08T18:00:00Z",
      durationMinutes: 60,
      description: null
    }, "reschedule-clear");

    const descriptions = fetchMock.mock.calls.map((call) => {
      const body = new URLSearchParams(String(call[1]?.body));
      const commands = JSON.parse(body.get("commands") ?? "[]") as Array<{ args: { description?: string } }>;
      return commands[0]?.args.description;
    });
    expect(descriptions).toEqual(["Updated gate code: 1357", "", ""]);
  });

  it("moves projects separately before updating editable task fields", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({
      sync_status: {}
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.updateTask({
      todoistTaskId: "todoist-1",
      title: "Edited task",
      description: "  Updated details  ",
      projectId: "project-family",
      assigneeId: "43186358",
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 45
    }, {
      moveCommandUuid: "legacy-move",
      updateCommandUuid: "legacy-update"
    });

    expect(readCommands(fetchMock)).toEqual([
      {
        type: "item_move",
        uuid: "legacy-move",
        args: { id: "todoist-1", project_id: "project-family" }
      },
      {
        type: "item_update",
        uuid: "legacy-update",
        args: {
          id: "todoist-1",
          content: "Edited task",
          description: "Updated details",
          responsible_uid: "43186358",
          due: { date: "2026-05-08T16:00:00Z" },
          duration: { amount: 45, unit: "minute" }
        }
      }
    ]);
  });

  it("explicitly clears responsible_uid when updating a task without an assignee", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({
      sync_status: {}
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.updateTask({
      todoistTaskId: "todoist-1",
      title: "Personal task",
      description: null,
      projectId: "project-personal",
      assigneeId: null,
      dueDatetime: "2026-05-08T16:00:00Z",
      durationMinutes: 45
    }, { updateCommandUuid: "legacy-unassign" });

    const body = new URLSearchParams(String(fetchMock.mock.calls[0]?.[1]?.body));
    const commands = JSON.parse(body.get("commands") ?? "[]") as Array<{ args: Record<string, unknown> }>;
    expect(commands[0]?.args.responsible_uid).toBeNull();
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

  it("maps hierarchy, recurrence, checked state, and duration facts without product-policy inference", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      sync_token: "typed-token",
      items: [
        {
          id: "child-hour",
          content: "Keep exact details",
          description: "  whitespace is user data  ",
          project_id: "project-family",
          parent_id: "root-1",
          child_order: 7,
          responsible_uid: "user-self",
          added_by_uid: "wife",
          checked: true,
          due: {
            date: "2026-07-16T08:30:00Z",
            datetime: "2026-07-16T08:30:00Z",
            string: "every weekday at 10:30",
            lang: "en",
            timezone: "Europe/Bratislava",
            is_recurring: true
          },
          duration: { amount: 2, unit: "hour" },
          updated_at: "2026-07-15T12:00:00Z"
        },
        {
          id: "minute-task",
          content: "Minutes",
          description: null,
          checked: false,
          duration: { amount: 45, unit: "minute" },
          updated_at: "2026-07-15T12:01:00Z"
        },
        {
          id: "unsupported-duration",
          content: "Unsupported duration",
          duration: { amount: 3, unit: "day" },
          updated_at: "2026-07-15T12:02:00Z"
        }
      ]
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const result = await adapter.sync("*");

    expect(result.tasks[0]).toEqual({
      todoistTaskId: "child-hour",
      title: "Keep exact details",
      description: "  whitespace is user data  ",
      projectId: "project-family",
      parentId: "root-1",
      childOrder: 7,
      assigneeId: "user-self",
      creatorId: "wife",
      dueDate: "2026-07-16T08:30:00Z",
      dueDatetime: "2026-07-16T08:30:00Z",
      dueString: "every weekday at 10:30",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava",
      isRecurring: true,
      durationAmount: 2,
      durationUnit: "hour",
      durationMinutes: 120,
      completedAt: "2026-07-15T12:00:00Z",
      deletedAt: null,
      rawJson: expect.any(Object),
      updatedAt: "2026-07-15T12:00:00Z"
    });
    expect(result.tasks[1]).toMatchObject({
      durationAmount: 45,
      durationUnit: "minute",
      durationMinutes: 45,
      completedAt: null
    });
    expect(result.tasks[2]).toMatchObject({
      durationAmount: 3,
      durationUnit: "day",
      durationMinutes: null
    });
  });

  it("emits root item_add with the caller command UUID and temp ID", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      sync_status: { "root-command": "ok" },
      temp_id_mapping: { "root-temp": "todoist-root" }
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const result = await adapter.itemAdd({
      kind: "root",
      commandUuid: "root-command",
      tempId: "root-temp",
      title: "Recurring root",
      description: "Root details",
      projectId: "project-family",
      assigneeId: "user-self",
      due: {
        kind: "replace",
        datetime: "2026-07-16T08:30:00+02:00",
        string: "every weekday at 08:30",
        lang: "en",
        timezone: "Europe/Bratislava"
      },
      duration: { amount: 45, unit: "minute" }
    });

    expect(readCommands(fetchMock)).toEqual([{
      type: "item_add",
      uuid: "root-command",
      temp_id: "root-temp",
      args: {
        content: "Recurring root",
        description: "Root details",
        project_id: "project-family",
        responsible_uid: "user-self",
        due: {
          date: "2026-07-16T08:30:00+02:00",
          string: "every weekday at 08:30",
          lang: "en",
          timezone: "Europe/Bratislava"
        },
        duration: { amount: 45, unit: "minute" }
      }
    }]);
    expect(result).toEqual({ todoistTaskId: "todoist-root" });
  });

  it("emits child item_add with only checklist-safe fields", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      sync_status: { "child-command": "ok" },
      temp_id_mapping: { "child-temp": "todoist-child" }
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    const result = await adapter.itemAdd({
      kind: "child",
      commandUuid: "child-command",
      tempId: "child-temp",
      title: "Pack charger",
      description: "USB-C",
      projectId: "project-family",
      parentTodoistTaskId: "todoist-root"
    });

    const commands = readCommands(fetchMock);
    expect(commands).toEqual([{
      type: "item_add",
      uuid: "child-command",
      temp_id: "child-temp",
      args: {
        content: "Pack charger",
        description: "USB-C",
        project_id: "project-family",
        parent_id: "todoist-root"
      }
    }]);
    expect(commands[0]?.args).not.toHaveProperty("due");
    expect(commands[0]?.args).not.toHaveProperty("duration");
    expect(commands[0]?.args).not.toHaveProperty("responsible_uid");
    expect(result).toEqual({ todoistTaskId: "todoist-child" });
  });

  it("makes item_update due and assignment mutations explicit without accepting project moves", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({
      sync_status: {}
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.itemUpdate({
      commandUuid: "update-preserve",
      todoistTaskId: "todoist-root",
      title: "Metadata only",
      duration: { amount: 90, unit: "minute" },
      due: { kind: "preserve" },
      assignment: { kind: "preserve" }
    });
    await adapter.itemUpdate({
      commandUuid: "update-clear",
      todoistTaskId: "todoist-root",
      description: null,
      due: { kind: "clear" },
      assignment: { kind: "unassign" }
    });
    await adapter.itemUpdate({
      commandUuid: "update-replace",
      todoistTaskId: "todoist-root",
      due: {
        kind: "replace",
        datetime: "2026-07-17T09:15:00+02:00",
        string: "every day at 09:15",
        lang: "en",
        timezone: "Europe/Bratislava"
      },
      assignment: { kind: "assign", assigneeId: "user-self" }
    });

    const [preserve, clear, replace] = readCommands(fetchMock);
    expect(preserve).toEqual({
      type: "item_update",
      uuid: "update-preserve",
      args: {
        id: "todoist-root",
        content: "Metadata only",
        duration: { amount: 90, unit: "minute" }
      }
    });
    expect(preserve?.args).not.toHaveProperty("project_id");
    expect(clear).toEqual({
      type: "item_update",
      uuid: "update-clear",
      args: {
        id: "todoist-root",
        description: "",
        due: null,
        responsible_uid: null
      }
    });
    expect(replace).toEqual({
      type: "item_update",
      uuid: "update-replace",
      args: {
        id: "todoist-root",
        due: {
          date: "2026-07-17T09:15:00+02:00",
          string: "every day at 09:15",
          lang: "en",
          timezone: "Europe/Bratislava"
        },
        responsible_uid: "user-self"
      }
    });
  });

  it("emits dedicated move, close, uncomplete, and delete commands with caller UUIDs", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({
      sync_status: {}
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.itemMove({ commandUuid: "move-command", todoistTaskId: "todoist-1", projectId: "project-new" });
    await adapter.itemClose({ commandUuid: "close-command", todoistTaskId: "todoist-1" });
    await adapter.itemUncomplete({ commandUuid: "uncomplete-command", todoistTaskId: "todoist-1" });
    await adapter.itemDelete({ commandUuid: "delete-command", todoistTaskId: "todoist-1" });

    expect(readCommands(fetchMock)).toEqual([
      {
        type: "item_move",
        uuid: "move-command",
        args: { id: "todoist-1", project_id: "project-new" }
      },
      {
        type: "item_close",
        uuid: "close-command",
        args: { id: "todoist-1" }
      },
      {
        type: "item_uncomplete",
        uuid: "uncomplete-command",
        args: { id: "todoist-1" }
      },
      {
        type: "item_delete",
        uuid: "delete-command",
        args: { id: "todoist-1" }
      }
    ]);
  });

  it("emits recurring completion with mirrored due facts and a hard-coded child reset", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      sync_status: { "recurring-complete": "ok" }
    })));
    const adapter = new HttpTodoistAdapter("todoist-token", "https://todoist.test/api/v1");

    await adapter.itemUpdateDateComplete({
      commandUuid: "recurring-complete",
      task: {
        todoistTaskId: "todoist-root",
        title: "Recurring root",
        description: null,
        projectId: "project-family",
        parentId: null,
        childOrder: null,
        assigneeId: "user-self",
        creatorId: "wife",
        dueDate: "2026-07-16T08:30:00+02:00",
        dueDatetime: "2026-07-16T08:30:00+02:00",
        dueString: "every weekday at 08:30",
        dueLang: "en",
        dueTimezone: "Europe/Bratislava",
        isRecurring: true,
        durationAmount: 45,
        durationUnit: "minute",
        durationMinutes: 45,
        completedAt: null,
        deletedAt: null,
        rawJson: {},
        updatedAt: "2026-07-15T12:00:00Z"
      }
    });

    expect(readCommands(fetchMock)).toEqual([{
      type: "item_update_date_complete",
      uuid: "recurring-complete",
      args: {
        id: "todoist-root",
        due: {
          date: "2026-07-16T08:30:00+02:00",
          string: "every weekday at 08:30",
          lang: "en",
          timezone: "Europe/Bratislava",
          is_recurring: true
        },
        is_forward: 1,
        reset_subtasks: true
      }
    }]);
  });

  it("reports sanitized command failures with UUID, type, and Todoist status", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      sync_status: {
        "delete-failure": { error_code: 22, error: "invalid item" }
      }
    })));
    const adapter = new HttpTodoistAdapter("super-secret-token", "https://todoist.test/api/v1");

    const error = await adapter.itemDelete({
      commandUuid: "delete-failure",
      todoistTaskId: "todoist-1"
    }).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(Error);
    const message = (error as Error).message;
    expect(message).toContain("delete-failure");
    expect(message).toContain("item_delete");
    expect(message).toContain("invalid item");
    expect(message).not.toContain("super-secret-token");
    expect(message.toLowerCase()).not.toContain("authorization");
  });
});

function readCommands(fetchMock: ReturnType<typeof vi.spyOn>): Array<{
  type: string;
  uuid: string;
  temp_id?: string;
  args: Record<string, unknown>;
}> {
  return fetchMock.mock.calls.flatMap((call: [unknown, RequestInit?]) => {
    const body = new URLSearchParams(String(call[1]?.body));
    return JSON.parse(body.get("commands") ?? "[]") as Array<{
      type: string;
      uuid: string;
      temp_id?: string;
      args: Record<string, unknown>;
    }>;
  });
}
