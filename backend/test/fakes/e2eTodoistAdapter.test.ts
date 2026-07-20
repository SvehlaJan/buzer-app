import { describe, expect, it } from "vitest";
import { E2ETodoistAdapter } from "./e2eTodoistAdapter.js";
import type {
  TodoistProjectMirror,
  TodoistTaskMirror
} from "../../src/todoist/todoistTypes.js";

function mirror(
  overrides: Partial<TodoistTaskMirror> &
    Pick<TodoistTaskMirror, "todoistTaskId" | "title">
): TodoistTaskMirror {
  return {
    description: null,
    projectId: "project-test",
    parentId: null,
    childOrder: null,
    assigneeId: "user-me",
    creatorId: "user-me",
    dueDate: null,
    dueDatetime: "2026-07-18T10:00:00.000Z",
    dueString: null,
    dueLang: null,
    dueTimezone: null,
    isRecurring: false,
    durationAmount: 30,
    durationUnit: "minute",
    durationMinutes: 30,
    completedAt: null,
    deletedAt: null,
    rawJson: {},
    updatedAt: "2026-07-18T09:00:00.000Z",
    ...overrides
  };
}

const project: TodoistProjectMirror = {
  todoistProjectId: "project-test",
  name: "Test Shared",
  shared: true,
  canAssignTasks: true,
  isInboxProject: false,
  isDeleted: false,
  isArchived: false,
  rawJson: {},
  updatedAt: "2026-07-18T09:00:00.000Z"
};

function adapterWith(...tasks: TodoistTaskMirror[]): E2ETodoistAdapter {
  const adapter = new E2ETodoistAdapter("user-me");
  adapter.world.load({ tasks, projects: [project] });
  return adapter;
}

async function syncedTask(
  adapter: E2ETodoistAdapter,
  todoistTaskId: string
): Promise<TodoistTaskMirror> {
  const result = await adapter.sync("*");
  const task = result.tasks.find(
    (candidate) => candidate.todoistTaskId === todoistTaskId
  );
  if (task === undefined) throw new Error(`missing ${todoistTaskId}`);
  return task;
}

describe("E2ETodoistWorld semantics", () => {
  it("returns the full world from sync with a fresh token each time", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    const first = await adapter.sync("*");
    const second = await adapter.sync(first.syncToken);

    expect(first.tasks).toHaveLength(1);
    expect(second.tasks).toHaveLength(1);
    expect(second.syncToken).not.toBe(first.syncToken);
    expect(first.projects[0]?.todoistProjectId).toBe("project-test");
  });

  it("itemClose sets completedAt and itemUncomplete clears it", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    await adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).completedAt).not.toBeNull();
    await adapter.itemUncomplete({ commandUuid: "c-2", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).completedAt).toBeNull();
  });

  it("itemUpdate applies title, duration, and a due replacement", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    await adapter.itemUpdate({
      commandUuid: "c-1",
      todoistTaskId: "t-1",
      title: "Renamed",
      duration: { amount: 90, unit: "minute" },
      due: {
        kind: "replace",
        datetime: "2026-07-19T08:00:00.000Z",
        string: null,
        lang: null,
        timezone: null
      },
      assignment: { kind: "preserve" }
    });
    const task = await syncedTask(adapter, "t-1");

    expect(task.title).toBe("Renamed");
    expect(task.durationMinutes).toBe(90);
    expect(task.dueDatetime).toBe("2026-07-19T08:00:00.000Z");
    expect(task.isRecurring).toBe(false);
  });

  it("itemAdd creates a root and a child under it", async () => {
    const adapter = adapterWith();
    const root = await adapter.itemAdd({
      kind: "root",
      commandUuid: "c-1",
      tempId: "tmp-1",
      title: "Created root",
      projectId: "project-test",
      assigneeId: "user-me",
      due: {
        kind: "replace",
        datetime: "2026-07-19T08:00:00.000Z",
        string: null,
        lang: null,
        timezone: null
      },
      duration: { amount: 30, unit: "minute" }
    });
    expect(root.todoistTaskId).not.toBeNull();
    const child = await adapter.itemAdd({
      kind: "child",
      commandUuid: "c-2",
      tempId: "tmp-2",
      title: "Created child",
      projectId: "project-test",
      parentTodoistTaskId: root.todoistTaskId as string
    });
    const childTask = await syncedTask(adapter, child.todoistTaskId as string);

    expect(childTask.parentId).toBe(root.todoistTaskId);
    expect(childTask.childOrder).toBe(1);
    expect(childTask.dueDatetime).toBeNull();
  });

  it("itemMove changes the project and itemDelete tombstones", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    await adapter.itemMove({
      commandUuid: "c-1",
      todoistTaskId: "t-1",
      projectId: "project-personal"
    });
    expect((await syncedTask(adapter, "t-1")).projectId).toBe("project-personal");
    await adapter.itemDelete({ commandUuid: "c-2", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).deletedAt).not.toBeNull();
  });

  it("itemUpdateDateComplete advances a daily recurring due by one day", async () => {
    const task = mirror({
      todoistTaskId: "t-1",
      title: "Daily",
      isRecurring: true,
      dueString: "every day at 10:00",
      dueLang: "en",
      dueTimezone: "Europe/Bratislava"
    });
    const adapter = adapterWith(task);
    await adapter.itemUpdateDateComplete({ commandUuid: "c-1", task });

    expect((await syncedTask(adapter, "t-1")).dueDatetime).toBe(
      "2026-07-19T10:00:00.000Z"
    );
  });
});

describe("scenario presets", () => {
  it("after_command_sent_timeout throws from mutations and leaves the world untouched", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    adapter.setScenario("after_command_sent_timeout");

    await expect(
      adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" })
    ).rejects.toThrow();
    adapter.setScenario("none");

    expect((await syncedTask(adapter, "t-1")).completedAt).toBeNull();
  });

  it("sync_lag_effect_unknown applies mutations but hides them from sync", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    adapter.setScenario("sync_lag_effect_unknown");
    await adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" });

    expect((await adapter.sync("*")).tasks).toHaveLength(0);
    adapter.setScenario("none");
    expect((await syncedTask(adapter, "t-1")).completedAt).not.toBeNull();
  });
});
