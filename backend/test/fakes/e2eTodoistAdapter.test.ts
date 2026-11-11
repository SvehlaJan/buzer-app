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
      duration: { kind: "replace", amount: 90, unit: "minute" },
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

  it("keeps dueDate aligned with datetime root creation, replacement, and clearing", async () => {
    const adapter = adapterWith();
    const root = await adapter.itemAdd({
      kind: "root",
      commandUuid: "c-add",
      tempId: "tmp-1",
      title: "Dated root",
      projectId: "project-test",
      assigneeId: "user-me",
      due: {
        kind: "replace",
        datetime: "2026-07-19T08:00:00.000Z",
        string: null,
        lang: null,
        timezone: null
      },
      duration: { kind: "replace", amount: 30, unit: "minute" }
    });
    const todoistTaskId = root.todoistTaskId as string;
    expect((await syncedTask(adapter, todoistTaskId)).dueDate).toBe(
      "2026-07-19T08:00:00.000Z"
    );

    await adapter.itemUpdate({
      commandUuid: "c-replace",
      todoistTaskId,
      due: {
        kind: "replace",
        datetime: "2026-07-20T09:00:00.000Z",
        string: null,
        lang: null,
        timezone: null
      },
      assignment: { kind: "preserve" }
    });
    expect((await syncedTask(adapter, todoistTaskId)).dueDate).toBe(
      "2026-07-20T09:00:00.000Z"
    );

    await adapter.itemUpdate({
      commandUuid: "c-clear",
      todoistTaskId,
      due: { kind: "clear" },
      duration: { kind: "clear" },
      assignment: { kind: "preserve" }
    });
    const cleared = await syncedTask(adapter, todoistTaskId);
    expect(cleared.dueDate).toBeNull();
    expect(cleared.dueDatetime).toBeNull();
    expect(cleared.durationAmount).toBeNull();
    expect(cleared.durationUnit).toBeNull();
    expect(cleared.durationMinutes).toBeNull();
  });

  it("normalizes minute and hour durations for root and item updates", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    const root = await adapter.itemAdd({
      kind: "root",
      commandUuid: "c-add",
      tempId: "tmp-1",
      title: "Two hours",
      projectId: "project-test",
      assigneeId: "user-me",
      due: { kind: "clear" },
      duration: { kind: "replace", amount: 2, unit: "hour" }
    });
    expect((await syncedTask(adapter, root.todoistTaskId as string)).durationMinutes).toBe(120);

    await adapter.itemUpdate({
      commandUuid: "c-minute",
      todoistTaskId: "t-1",
      duration: { kind: "replace", amount: 45, unit: "minute" },
      due: { kind: "preserve" },
      assignment: { kind: "preserve" }
    });
    expect((await syncedTask(adapter, "t-1")).durationMinutes).toBe(45);

    await adapter.itemUpdate({
      commandUuid: "c-hour",
      todoistTaskId: "t-1",
      duration: { kind: "replace", amount: 2, unit: "hour" },
      due: { kind: "preserve" },
      assignment: { kind: "preserve" }
    });
    expect((await syncedTask(adapter, "t-1")).durationMinutes).toBe(120);
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
      duration: { kind: "replace", amount: 30, unit: "minute" }
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

  it("replays accepted commands without duplicating additions or reapplying closes", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    const add = {
      kind: "root" as const,
      commandUuid: "c-add",
      tempId: "tmp-1",
      title: "Created root",
      projectId: "project-test",
      assigneeId: "user-me",
      due: { kind: "clear" as const },
      duration: null
    };
    const first = await adapter.itemAdd(add);
    const replay = await adapter.itemAdd(add);
    expect(replay.todoistTaskId).toBe(first.todoistTaskId);
    expect((await adapter.sync("*")).tasks).toHaveLength(2);

    await adapter.itemClose({ commandUuid: "c-close", todoistTaskId: "t-1" });
    await adapter.itemUncomplete({ commandUuid: "c-uncomplete", todoistTaskId: "t-1" });
    await adapter.itemClose({ commandUuid: "c-close", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).completedAt).toBeNull();
  });

  it("forgets accepted commands when a fresh world is loaded", async () => {
    const adapter = adapterWith();
    const add = {
      kind: "root" as const,
      commandUuid: "c-add",
      tempId: "tmp-1",
      title: "Created root",
      projectId: "project-test",
      assigneeId: "user-me",
      due: { kind: "clear" as const },
      duration: null
    };
    await adapter.itemAdd(add);
    adapter.world.load({ tasks: [], projects: [project] });

    const replayAfterLoad = await adapter.itemAdd(add);
    const state = await adapter.sync("*");
    expect(state.tasks).toHaveLength(1);
    expect(state.tasks[0]?.todoistTaskId).toBe(replayAfterLoad.todoistTaskId);
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
    await adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" });
    expect((await syncedTask(adapter, "t-1")).completedAt).not.toBeNull();
  });

  it("sync_lag_effect_unknown applies mutations but hides them from sync", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    adapter.setScenario("sync_lag_effect_unknown");
    await adapter.itemClose({ commandUuid: "c-1", todoistTaskId: "t-1" });

    expect((await adapter.sync("*")).tasks).toHaveLength(0);
    adapter.setScenario("none");
    expect((await syncedTask(adapter, "t-1")).completedAt).not.toBeNull();
  });

  it("holdNextSync resolves entered when sync starts and suspends sync until release is called", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    const hold = adapter.holdNextSync();

    let syncSettled = false;
    const syncPromise = adapter.sync("*").then((result) => {
      syncSettled = true;
      return result;
    });

    await hold.entered;
    expect(syncSettled).toBe(false);

    hold.release();
    const result = await syncPromise;
    expect(syncSettled).toBe(true);
    expect(result.tasks).toHaveLength(1);

    // Subsequent sync calls are not held
    const nextResult = await adapter.sync(result.syncToken);
    expect(nextResult.tasks).toHaveLength(1);
  });

  it("holdNextSync release before sync starts disarms cleanly without blocking later syncs", async () => {
    const adapter = adapterWith(mirror({ todoistTaskId: "t-1", title: "One" }));
    const hold = adapter.holdNextSync();
    hold.release();
    await expect(hold.entered).resolves.toBeUndefined();

    const result = await adapter.sync("*");
    expect(result.tasks).toHaveLength(1);
  });
});
