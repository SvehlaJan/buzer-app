import { afterEach, describe, expect, it, vi } from "vitest";
import {
  MUTATION_DEADLINE_MS,
  MUTATION_MINIMUM_EXECUTION_BUDGET_MS,
  TaskMutationRunner,
  type MutationRunResult,
  type TaskSnapshot
} from "../../src/services/taskMutationRunner.js";
import { TodoistOperationQueue } from "../../src/services/todoistOperationQueue.js";
import { TodoistCommandExecutor } from "../../src/services/todoistCommandExecutor.js";
import { TodoistOperationError } from "../../src/todoist/todoistErrors.js";
import type { TodoistAdapter, TodoistCommand } from "../../src/todoist/todoistAdapter.js";
import { DomainError } from "../../src/domain/errors.js";
import { RecordingBackendObservability } from "../fakes/recordingBackendObservability.js";

function createMockSnapshot(overrides?: Partial<TaskSnapshot>): TaskSnapshot {
  return {
    scheduleVersion: 1,
    snapshotGeneration: 1,
    serverTime: "2026-08-31T10:00:00.000Z",
    tasks: [],
    health: [],
    ...overrides
  };
}

function createMockAdapter(handlers?: Partial<Record<keyof TodoistAdapter, (...args: any[]) => Promise<any>>>): {
  adapter: TodoistAdapter;
  calls: Array<{ method: string; input: any }>;
} {
  const calls: Array<{ method: string; input: any }> = [];
  const adapter: TodoistAdapter = {
    sync: vi.fn(),
    itemAdd: vi.fn(async (input) => {
      calls.push({ method: "itemAdd", input });
      if (handlers?.itemAdd) return handlers.itemAdd(input);
      return { todoistTaskId: "created-task-id" };
    }),
    itemUpdate: vi.fn(async (input) => {
      calls.push({ method: "itemUpdate", input });
      if (handlers?.itemUpdate) return handlers.itemUpdate(input);
    }),
    itemMove: vi.fn(async (input) => {
      calls.push({ method: "itemMove", input });
      if (handlers?.itemMove) return handlers.itemMove(input);
    }),
    itemClose: vi.fn(async (input) => {
      calls.push({ method: "itemClose", input });
      if (handlers?.itemClose) return handlers.itemClose(input);
    }),
    itemUncomplete: vi.fn(async (input) => {
      calls.push({ method: "itemUncomplete", input });
      if (handlers?.itemUncomplete) return handlers.itemUncomplete(input);
    }),
    itemDelete: vi.fn(async (input) => {
      calls.push({ method: "itemDelete", input });
      if (handlers?.itemDelete) return handlers.itemDelete(input);
    })
  };
  return { adapter, calls };
}

describe("TaskMutationRunner", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("exports default constants MUTATION_DEADLINE_MS = 13_000 and MUTATION_MINIMUM_EXECUTION_BUDGET_MS = 5_000", () => {
    expect(MUTATION_DEADLINE_MS).toBe(13_000);
    expect(MUTATION_MINIMUM_EXECUTION_BUDGET_MS).toBe(5_000);
  });

  // Scenario 1: Queue budget exhausted -> busy, 0 commands sent, 0 syncs
  it("returns { kind: 'busy' } when queue budget is exhausted without sending commands or syncing", async () => {
    let now = 1000;
    const queue = new TodoistOperationQueue({ now: () => now });
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();

    // Block the queue with an operation
    let releaseBlocker: () => void;
    const blockerPromise = new Promise<void>((resolve) => {
      releaseBlocker = resolve;
    });
    const blocker = queue.run(
      { operation: "long_running", deadlineMs: 30_000 },
      () => blockerPromise
    );

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs,
      now: () => now
    });

    const command: TodoistCommand = {
      type: "item_close",
      input: { todoistTaskId: "task-1" }
    };

    // Mutation is enqueued while blocker runs
    const mutationPromise = runner.execute({
      operation: "close_task",
      requestId: "req-busy-1",
      taskId: "task-1",
      occurrenceKey: "occ-1",
      commands: [command],
      deadlineMs: 13_000,
      minimumExecutionBudgetMs: 5_000
    });

    // Advance time past budget (wait 9s -> remaining 4s < 5s min budget)
    now += 9_000;

    releaseBlocker!();
    await blocker;

    const result = await mutationPromise;

    expect(result).toEqual({ kind: "busy" });
    expect(calls).toHaveLength(0);
    expect(syncOnce).toHaveBeenCalledTimes(0);

    // Verify structured logs: started and backend_busy warn, no Sentry exceptions
    expect(obs.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: {
          operation: "close_task",
          command_count: 1,
          request_id: "req-busy-1",
          task_id: "task-1",
          occurrence_key: "occ-1"
        }
      },
      {
        level: "warn",
        marker: "backend_busy",
        attributes: {
          operation: "close_task",
          request_id: "req-busy-1",
          task_id: "task-1",
          occurrence_key: "occ-1"
        }
      }
    ]);
    expect(obs.exceptions).toHaveLength(0);
    expect(obs.backgroundFailures).toHaveLength(0);
  });

  // Scenario 2: Acknowledged batch -> N commands, 1 sync success -> { kind: "success", snapshot }
  it("executes multi-command batch sequentially with fresh UUIDs and returns success with snapshot", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const expectedSnapshot = createMockSnapshot({ scheduleVersion: 42 });

    let uuidCounter = 1;
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => expectedSnapshot,
      observability: obs,
      uuidFactory: () => `uuid-${uuidCounter++}`
    });

    const commands: TodoistCommand[] = [
      {
        type: "item_update",
        input: {
          todoistTaskId: "task-1",
          due: { kind: "preserve" },
          assignment: { kind: "preserve" }
        }
      },
      {
        type: "item_close",
        input: {
          todoistTaskId: "task-1"
        }
      }
    ];

    const result = await runner.execute({
      operation: "complete_and_update",
      requestId: "req-ack-batch",
      taskId: "task-1",
      occurrenceKey: "occ-1",
      commands
    });

    expect(result).toEqual({
      kind: "success",
      snapshot: expectedSnapshot
    });

    expect(calls).toHaveLength(2);
    expect(calls[0]).toMatchObject({
      method: "itemUpdate",
      input: { commandUuid: "uuid-1", todoistTaskId: "task-1" }
    });
    expect(calls[1]).toMatchObject({
      method: "itemClose",
      input: { commandUuid: "uuid-2", todoistTaskId: "task-1" }
    });

    expect(syncOnce).toHaveBeenCalledTimes(1);

    expect(obs.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: {
          operation: "complete_and_update",
          command_count: 2,
          request_id: "req-ack-batch",
          task_id: "task-1",
          occurrence_key: "occ-1"
        }
      },
      {
        level: "info",
        marker: "todoist_operation_succeeded",
        attributes: {
          operation: "complete_and_update",
          command_count: 2,
          request_id: "req-ack-batch",
          task_id: "task-1",
          occurrence_key: "occ-1"
        }
      }
    ]);
    expect(obs.exceptions).toHaveLength(0);
  });

  it("prepares commands only after entering the FIFO and pairs a start record with the validation-failed terminal for a pre-send conflict", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();
    const prepareCommands = vi.fn((): TodoistCommand[] => {
      throw DomainError.staleTask(snapshot);
    });

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs
    });

    await expect(runner.execute({
      operation: "edit_task",
      requestId: "req-prepare-conflict",
      taskId: "task-1",
      prepareCommands
    })).rejects.toMatchObject({ code: "stale_task", statusCode: 409 });

    expect(prepareCommands).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(0);
    expect(syncOnce).not.toHaveBeenCalled();
    expect(obs.exceptions).toHaveLength(0);
    expect(obs.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: {
          operation: "edit_task",
          command_count: 0,
          request_id: "req-prepare-conflict",
          task_id: "task-1"
        }
      },
      {
        level: "warn",
        marker: "todoist_operation_validation_failed",
        attributes: {
          operation: "edit_task",
          error_code: "stale_task",
          request_id: "req-prepare-conflict",
          task_id: "task-1"
        }
      }
    ]);
  });

  it("logs a start record before preparation, then the actual prepared command count on the succeeded terminal", async () => {
    const queue = new TodoistOperationQueue();
    const eventOrder: string[] = [];
    const { adapter } = createMockAdapter({
      itemUpdate: async () => { eventOrder.push("send"); }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const obs = new RecordingBackendObservability();
    const originalInfo = obs.info.bind(obs);
    obs.info = (marker, attributes) => {
      eventOrder.push(marker);
      originalInfo(marker, attributes);
    };
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce: vi.fn().mockResolvedValue(undefined) },
      snapshotService: () => createMockSnapshot(),
      observability: obs
    });

    const result = await runner.execute({
      operation: "edit_task",
      prepareCommands: () => {
        eventOrder.push("prepare");
        return [{
          type: "item_update",
          input: {
            todoistTaskId: "task-1",
            due: { kind: "preserve" },
            assignment: { kind: "preserve" }
          }
        }];
      }
    });

    expect(result.kind).toBe("success");
    expect(eventOrder).toEqual([
      "todoist_operation_started",
      "prepare",
      "send",
      "todoist_operation_succeeded"
    ]);
    expect(obs.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: { operation: "edit_task", command_count: 0 }
      },
      {
        level: "info",
        marker: "todoist_operation_succeeded",
        attributes: { operation: "edit_task", command_count: 1 }
      }
    ]);
  });

  it("pairs a start record with backend_busy admission rejection even when commands are only prepared inside the FIFO", async () => {
    let now = 1000;
    const queue = new TodoistOperationQueue({ now: () => now });
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();
    const prepareCommands = vi.fn((): TodoistCommand[] => [
      { type: "item_close", input: { todoistTaskId: "task-1" } }
    ]);

    let releaseBlocker: () => void;
    const blockerPromise = new Promise<void>((resolve) => {
      releaseBlocker = resolve;
    });
    const blocker = queue.run(
      { operation: "long_running", deadlineMs: 30_000 },
      () => blockerPromise
    );

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs,
      now: () => now
    });

    const mutationPromise = runner.execute({
      operation: "close_task",
      requestId: "req-busy-prepared",
      prepareCommands,
      deadlineMs: 13_000,
      minimumExecutionBudgetMs: 5_000
    });

    // Advance time past budget (wait 9s -> remaining 4s < 5s min budget)
    now += 9_000;

    releaseBlocker!();
    await blocker;

    const result = await mutationPromise;

    expect(result).toEqual({ kind: "busy" });
    expect(prepareCommands).not.toHaveBeenCalled();
    expect(calls).toHaveLength(0);
    expect(syncOnce).toHaveBeenCalledTimes(0);
    expect(obs.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: {
          operation: "close_task",
          command_count: 0,
          request_id: "req-busy-prepared"
        }
      },
      {
        level: "warn",
        marker: "backend_busy",
        attributes: {
          operation: "close_task",
          request_id: "req-busy-prepared"
        }
      }
    ]);
    expect(obs.exceptions).toHaveLength(0);
  });

  it("pairs a start record with the cancelled terminal when the caller aborts before the FIFO admits the operation", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const prepareCommands = vi.fn((): TodoistCommand[] => []);

    // Occupy the FIFO so the mutation sits queued instead of running immediately.
    let releaseBlocker: () => void;
    const blockerPromise = new Promise<void>((resolve) => {
      releaseBlocker = resolve;
    });
    const blocker = queue.run(
      { operation: "long_running", deadlineMs: 30_000 },
      () => blockerPromise
    );

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => createMockSnapshot(),
      observability: obs
    });

    const controller = new AbortController();
    const cancelReason = new Error("client disconnected");

    const mutationPromise = runner.execute({
      operation: "edit_task",
      requestId: "req-cancel-1",
      prepareCommands,
      signal: controller.signal
    });

    controller.abort(cancelReason);

    await expect(mutationPromise).rejects.toBe(cancelReason);

    releaseBlocker!();
    await blocker;

    expect(prepareCommands).not.toHaveBeenCalled();
    expect(calls).toHaveLength(0);
    expect(syncOnce).not.toHaveBeenCalled();
    expect(obs.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: {
          operation: "edit_task",
          command_count: 0,
          request_id: "req-cancel-1"
        }
      },
      {
        level: "warn",
        marker: "todoist_operation_cancelled",
        attributes: {
          operation: "edit_task",
          request_id: "req-cancel-1"
        }
      }
    ]);
    expect(obs.exceptions).toHaveLength(0);
  });

  it("resolves a created root temp ID to its permanent Todoist ID before a later child request", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter({
      itemAdd: async (input) => ({
        todoistTaskId: input.kind === "root" ? "todoist-root-real" : "todoist-child-real"
      })
    });
    const executor = new TodoistCommandExecutor(adapter);
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce: vi.fn().mockResolvedValue(undefined) },
      snapshotService: () => createMockSnapshot(),
      uuidFactory: (() => {
        let id = 0;
        return () => `uuid-${++id}`;
      })()
    });

    const result = await runner.execute({
      operation: "create_task",
      commands: [
        {
          type: "item_add",
          input: {
            kind: "root",
            tempId: "root-temp",
            title: "Root",
            description: null,
            projectId: "project-1",
            assigneeId: null,
            due: { kind: "preserve" },
            duration: null
          }
        },
        {
          type: "item_add",
          input: {
            kind: "child",
            tempId: "child-temp",
            title: "Child",
            description: null,
            projectId: "project-1",
            parentTodoistTaskId: "root-temp"
          }
        }
      ]
    });

    expect(result.kind).toBe("success");
    expect(calls).toHaveLength(2);
    expect(calls[1]).toMatchObject({
      method: "itemAdd",
      input: {
        kind: "child",
        parentTodoistTaskId: "todoist-root-real"
      }
    });
  });

  // Scenario 3: Acknowledged no-op -> N commands, 1 sync success -> { kind: "success", snapshot } (no effect comparison)
  it("returns success with snapshot on acknowledged no-op without effect comparison", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const expectedSnapshot = createMockSnapshot();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => expectedSnapshot,
      observability: obs
    });

    // An empty commands batch or command that results in no-op in Todoist
    const result = await runner.execute({
      operation: "noop_op",
      commands: []
    });

    expect(result).toEqual({
      kind: "success",
      snapshot: expectedSnapshot
    });
    expect(syncOnce).toHaveBeenCalledTimes(1);
    expect(result).not.toHaveProperty("isEffectPresent");
    expect(result).not.toHaveProperty("effectCheck");
  });

  // Scenario 4: Explicit rejection -> stop at first, 1 best effort sync -> { kind: "rejected", snapshot? }
  it("stops at first command rejection, performs best-effort sync, and returns rejected with snapshot", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter } = createMockAdapter({
      itemClose: async () => {
        throw new TodoistOperationError("command_rejected", undefined, {
          command_type: "item_close",
          error_tag: "ITEM_NOT_FOUND"
        });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const refreshedSnapshot = createMockSnapshot({ scheduleVersion: 5 });

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => refreshedSnapshot,
      observability: obs
    });

    const commands: TodoistCommand[] = [
      { type: "item_close", input: { todoistTaskId: "task-not-found" } },
      { type: "item_delete", input: { todoistTaskId: "task-not-found" } }
    ];

    const result = await runner.execute({
      operation: "close_and_delete",
      requestId: "req-rej-1",
      taskId: "task-not-found",
      commands
    });

    expect(result).toEqual({
      kind: "rejected",
      snapshot: refreshedSnapshot
    });

    // Best-effort syncOnce was executed
    expect(syncOnce).toHaveBeenCalledTimes(1);

    expect(obs.logs).toContainEqual({
      level: "warn",
      marker: "todoist_command_rejected",
      attributes: {
        operation: "close_and_delete",
        command_type: "item_close",
        command_index: 0,
        error_kind: "command_rejected",
        request_id: "req-rej-1",
        task_id: "task-not-found"
      }
    });
    expect(obs.exceptions).toHaveLength(0);
  });

  // Scenario 5: First command rejection -> 1 command sent, 1 best effort sync -> { kind: "rejected", snapshot? }
  it("stops immediately when first command fails in multi-command batch", async () => {
    const queue = new TodoistOperationQueue();
    let secondCommandSent = false;
    const { adapter, calls } = createMockAdapter({
      itemUpdate: async () => {
        throw new TodoistOperationError("client", undefined, { http_code: 400 });
      },
      itemClose: async () => {
        secondCommandSent = true;
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs
    });

    const commands: TodoistCommand[] = [
      {
        type: "item_update",
        input: {
          todoistTaskId: "task-1",
          due: { kind: "preserve" },
          assignment: { kind: "preserve" }
        }
      },
      {
        type: "item_close",
        input: { todoistTaskId: "task-1" }
      }
    ];

    const result = await runner.execute({
      operation: "update_then_close",
      commands
    });

    expect(result).toEqual({
      kind: "rejected",
      snapshot
    });

    expect(calls).toHaveLength(1);
    expect(secondCommandSent).toBe(false);
    expect(syncOnce).toHaveBeenCalledTimes(1);
  });

  // Scenario 6: Later command rejection -> stop at failing command, no ack count in result
  it("stops at failing second command without executing third command and omits ack counts from result", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter({
      itemDelete: async () => {
        throw new TodoistOperationError("server", undefined, { http_code: 500 });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs
    });

    const commands: TodoistCommand[] = [
      {
        type: "item_close",
        input: { todoistTaskId: "child-1" }
      },
      {
        type: "item_delete",
        input: { todoistTaskId: "child-2" }
      },
      {
        type: "item_close",
        input: { todoistTaskId: "child-3" }
      }
    ];

    const result = await runner.execute({
      operation: "multi_step",
      commands
    });

    expect(result).toEqual({
      kind: "rejected",
      snapshot
    });

    // Command 1 ran once, Command 2 ran twice (initial + 1 retry for 500), Command 3 never ran
    expect(calls).toHaveLength(3);
    expect(calls[0]?.method).toBe("itemClose");
    expect(calls[1]?.method).toBe("itemDelete");
    expect(calls[2]?.method).toBe("itemDelete");

    // Result does NOT have acknowledgedCount, batchSize, pending, actionId
    expect(result).not.toHaveProperty("acknowledgedCount");
    expect(result).not.toHaveProperty("batchSize");
    expect(result).not.toHaveProperty("pending");
    expect(result).not.toHaveProperty("actionId");
  });

  // Scenario 7: Transport after send -> delivery_unknown, stop at failing command, 1 best effort sync
  it("returns delivery_unknown and logs warning when transport timeout occurs", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter } = createMockAdapter({
      itemUpdate: async () => {
        throw new TodoistOperationError("timeout");
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs
    });

    const commands: TodoistCommand[] = [
      {
        type: "item_update",
        input: {
          todoistTaskId: "task-1",
          due: { kind: "preserve" },
          assignment: { kind: "preserve" }
        }
      }
    ];

    const result = await runner.execute({
      operation: "update_task",
      commands
    });

    expect(result).toEqual({
      kind: "delivery_unknown",
      snapshot
    });

    expect(syncOnce).toHaveBeenCalledTimes(1);

    expect(obs.logs).toContainEqual({
      level: "warn",
      marker: "todoist_delivery_unknown",
      attributes: {
        operation: "update_task",
        command_type: "item_update",
        command_index: 0,
        error_kind: "timeout"
      }
    });
    expect(obs.exceptions).toHaveLength(0);
  });

  it("maps an active queue deadline after command dispatch to delivery_unknown, never backend_busy", async () => {
    vi.useFakeTimers();
    const queue = new TodoistOperationQueue();
    let rejectDispatchedCommand: ((error: TodoistOperationError) => void) | undefined;
    const dispatchedCommand = new Promise<void>((_resolve, reject) => {
      rejectDispatchedCommand = reject;
    });
    const { adapter, calls } = createMockAdapter({
      itemClose: async () => await dispatchedCommand
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const observability = new RecordingBackendObservability();
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => createMockSnapshot(),
      observability
    });

    const result = runner.execute({
      operation: "complete_task",
      requestId: "req-active-deadline",
      taskId: "task-1",
      occurrenceKey: "occurrence-1",
      commands: [{ type: "item_close", input: { todoistTaskId: "todoist-1" } }],
      deadlineMs: 1000,
      minimumExecutionBudgetMs: 0
    });
    await vi.advanceTimersByTimeAsync(0);
    expect(calls).toHaveLength(1);

    await vi.advanceTimersByTimeAsync(1000);

    await expect(result).resolves.toEqual({ kind: "delivery_unknown" });
    expect(observability.logs.map(({ marker }) => marker)).toEqual([
      "todoist_operation_started",
      "todoist_delivery_unknown"
    ]);
    expect(observability.logs).not.toContainEqual(expect.objectContaining({
      marker: "backend_busy"
    }));
    expect(queue.activeCount).toBe(1);

    rejectDispatchedCommand?.(new TodoistOperationError("timeout"));
    await vi.advanceTimersByTimeAsync(0);
    expect(syncOnce).toHaveBeenCalledTimes(1);
    expect(queue.activeCount).toBe(0);
    expect(observability.logs.map(({ marker }) => marker)).toEqual([
      "todoist_operation_started",
      "todoist_delivery_unknown"
    ]);
    expect(observability.logs).not.toContainEqual(expect.objectContaining({
      marker: "todoist_operation_succeeded"
    }));
  });

  it("preserves acknowledged commands as accepted_stale when post-command sync hits the active deadline", async () => {
    vi.useFakeTimers();
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    let syncSignal: AbortSignal | undefined;
    const syncOnce = vi.fn(async (options?: { signal?: AbortSignal }) => {
      syncSignal = options?.signal;
      await new Promise<void>((_resolve, reject) => {
        if (syncSignal?.aborted) {
          reject(syncSignal.reason);
          return;
        }
        syncSignal?.addEventListener("abort", () => reject(syncSignal?.reason), { once: true });
      });
    });
    const observability = new RecordingBackendObservability();
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => createMockSnapshot(),
      observability
    });

    const result = runner.execute({
      operation: "complete_task",
      requestId: "req-accepted-deadline",
      commands: [{ type: "item_close", input: { todoistTaskId: "todoist-1" } }],
      deadlineMs: 1000,
      minimumExecutionBudgetMs: 0
    });
    await vi.advanceTimersByTimeAsync(0);
    const nextCallback = vi.fn().mockResolvedValue("next-ran");
    const next = queue.run({ operation: "next", deadlineMs: 5000 }, nextCallback);

    expect(calls).toHaveLength(1);
    expect(syncOnce).toHaveBeenCalledTimes(1);
    expect(syncOnce).toHaveBeenCalledWith({ signal: expect.any(AbortSignal) });
    expect(syncSignal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1000);

    await expect(result).resolves.toEqual({ kind: "accepted_stale" });
    await expect(next).resolves.toBe("next-ran");
    expect(syncSignal?.aborted).toBe(true);
    expect(nextCallback).toHaveBeenCalledOnce();
    expect(queue.activeCount).toBe(0);
    expect(observability.logs.map(({ marker }) => marker)).toEqual([
      "todoist_operation_started",
      "todoist_accepted_snapshot_stale"
    ]);
    expect(observability.logs).not.toContainEqual(expect.objectContaining({
      marker: "todoist_operation_succeeded"
    }));
  });

  it("preserves explicit command rejection when best-effort sync hits the active deadline", async () => {
    vi.useFakeTimers();
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter({
      itemClose: async () => {
        throw new TodoistOperationError("command_rejected");
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    let syncSignal: AbortSignal | undefined;
    const syncOnce = vi.fn(async (options?: { signal?: AbortSignal }) => {
      syncSignal = options?.signal;
      await new Promise<void>((_resolve, reject) => {
        if (syncSignal?.aborted) {
          reject(syncSignal.reason);
          return;
        }
        syncSignal?.addEventListener("abort", () => reject(syncSignal?.reason), { once: true });
      });
    });
    const observability = new RecordingBackendObservability();
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => createMockSnapshot(),
      observability
    });

    const result = runner.execute({
      operation: "complete_task",
      requestId: "req-rejected-deadline",
      commands: [{ type: "item_close", input: { todoistTaskId: "todoist-1" } }],
      deadlineMs: 1000,
      minimumExecutionBudgetMs: 0
    });
    await vi.advanceTimersByTimeAsync(0);

    expect(calls).toHaveLength(1);
    expect(syncOnce).toHaveBeenCalledTimes(1);
    expect(syncOnce).toHaveBeenCalledWith({ signal: expect.any(AbortSignal) });
    await vi.advanceTimersByTimeAsync(1000);

    await expect(result).resolves.toEqual({ kind: "rejected" });
    expect(syncSignal?.aborted).toBe(true);
    expect(queue.activeCount).toBe(0);
    expect(observability.logs.map(({ marker }) => marker)).toEqual([
      "todoist_operation_started",
      "todoist_command_rejected"
    ]);
    expect(observability.logs).not.toContainEqual(expect.objectContaining({
      marker: "todoist_operation_succeeded"
    }));
  });

  // Scenario 8: Retry succeeds -> max 2 attempts reusing same UUID -> { kind: "success", snapshot }
  it("retries transient failure reusing the same UUID and returns success when attempt 2 succeeds", async () => {
    const queue = new TodoistOperationQueue();
    let attempt = 0;
    const { adapter, calls } = createMockAdapter({
      itemMove: async () => {
        attempt++;
        if (attempt === 1) {
          throw new TodoistOperationError("transport");
        }
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs,
      uuidFactory: () => "fixed-uuid"
    });

    const commands: TodoistCommand[] = [
      {
        type: "item_move",
        input: {
          todoistTaskId: "task-1",
          projectId: "proj-target"
        }
      }
    ];

    const result = await runner.execute({
      operation: "move_task",
      commands
    });

    expect(result).toEqual({
      kind: "success",
      snapshot
    });

    expect(calls).toHaveLength(2);
    expect(calls[0]?.input.commandUuid).toBe("fixed-uuid");
    expect(calls[1]?.input.commandUuid).toBe("fixed-uuid");
    expect(syncOnce).toHaveBeenCalledTimes(1);
  });

  // Scenario 9: Ack then sync fails -> N commands succeed, 1 failed sync -> { kind: "accepted_stale" }
  it("returns { kind: 'accepted_stale' } when command succeeds but post-ack sync fails", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockRejectedValue(new TodoistOperationError("transport", undefined, { message: "Network down" }));
    const obs = new RecordingBackendObservability();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => createMockSnapshot(),
      observability: obs
    });

    const commands: TodoistCommand[] = [
      {
        type: "item_close",
        input: { todoistTaskId: "task-1" }
      }
    ];

    const result = await runner.execute({
      operation: "close_task",
      requestId: "req-stale-1",
      taskId: "task-1",
      commands
    });

    expect(result).toEqual({
      kind: "accepted_stale"
    });

    expect(calls).toHaveLength(1);
    expect(syncOnce).toHaveBeenCalledTimes(1);

    expect(obs.logs).toContainEqual({
      level: "warn",
      marker: "todoist_accepted_snapshot_stale",
      attributes: {
        operation: "close_task",
        command_count: 1,
        request_id: "req-stale-1",
        task_id: "task-1"
      }
    });
    // Operational failure does not capture Sentry exception
    expect(obs.exceptions).toHaveLength(0);
  });

  // Scenario 10: Failure sync succeeds -> returns { kind: "rejected" | "delivery_unknown", snapshot }
  it("attaches snapshot when post-failure sync succeeds", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter } = createMockAdapter({
      itemDelete: async () => {
        throw new TodoistOperationError("command_rejected", undefined, {
          command_type: "item_delete",
          error_tag: "ITEM_NOT_FOUND"
        });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockResolvedValue(undefined);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot({ scheduleVersion: 10 });

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot,
      observability: obs
    });

    const result = await runner.execute({
      operation: "delete_task",
      commands: [{ type: "item_delete", input: { todoistTaskId: "task-99" } }]
    });

    expect(result).toEqual({
      kind: "rejected",
      snapshot
    });
    expect(syncOnce).toHaveBeenCalledTimes(1);
  });

  // Scenario 11: Failure sync fails -> returns { kind: "rejected" | "delivery_unknown" } without snapshot
  it("returns failure outcome without snapshot when post-failure sync fails", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter } = createMockAdapter({
      itemDelete: async () => {
        throw new TodoistOperationError("command_rejected", undefined, {
          command_type: "item_delete",
          error_tag: "ITEM_NOT_FOUND"
        });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockRejectedValue(new TodoistOperationError("transport"));
    const obs = new RecordingBackendObservability();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => createMockSnapshot(),
      observability: obs
    });

    const result = await runner.execute({
      operation: "delete_task",
      commands: [{ type: "item_delete", input: { todoistTaskId: "task-99" } }]
    });

    expect(result).toEqual({
      kind: "rejected"
    });
    expect(syncOnce).toHaveBeenCalledTimes(1);
    expect(obs.exceptions).toHaveLength(0);
  });

  // Serialization & FIFO queue slot held through syncOnce
  it("holds the FIFO queue slot across all commands AND post-command syncOnce without re-enqueuing", async () => {
    const queue = new TodoistOperationQueue();
    let releaseSync: () => void;
    const syncGate = new Promise<void>((resolve) => {
      releaseSync = resolve;
    });

    let syncRunning = false;
    let syncObservedActiveCount = 0;

    const syncOnce = vi.fn(async () => {
      syncRunning = true;
      syncObservedActiveCount = queue.activeCount;
      await syncGate;
      syncRunning = false;
    });

    const { adapter } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const snapshot = createMockSnapshot();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => snapshot
    });

    const mutationPromise = runner.execute({
      operation: "mutate_and_sync",
      commands: [{ type: "item_close", input: { todoistTaskId: "task-1" } }]
    });

    // Wait until sync is in-flight
    await vi.waitFor(() => expect(syncOnce).toHaveBeenCalledTimes(1));
    expect(syncRunning).toBe(true);
    expect(syncObservedActiveCount).toBe(1);

    // Queue another operation while sync is in flight
    let concurrentOpStarted = false;
    const queuedOpPromise = queue.run(
      { operation: "concurrent_poll", deadlineMs: 10_000 },
      async () => {
        concurrentOpStarted = true;
        return "poll_done";
      }
    );

    // Verify concurrent op is waiting in queue and has NOT started while mutation holds the slot
    expect(queue.queuedCount).toBe(1);
    expect(concurrentOpStarted).toBe(false);

    // Release sync to complete the mutation
    releaseSync!();

    const [mutationResult, queuedResult] = await Promise.all([
      mutationPromise,
      queuedOpPromise
    ]);

    expect(mutationResult).toEqual({
      kind: "success",
      snapshot
    });
    expect(queuedResult).toBe("poll_done");
    expect(concurrentOpStarted).toBe(true);
    expect(queue.activeCount).toBe(0);
  });

  it("captures Sentry exception on unexpected internal invariant defect", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const syncOnce = vi.fn().mockRejectedValue(new TypeError("Invariant corrupted SQLite schema"));
    const obs = new RecordingBackendObservability();

    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce },
      snapshotService: () => createMockSnapshot(),
      observability: obs
    });

    const result = await runner.execute({
      operation: "internal_defect_op",
      commands: [{ type: "item_close", input: { todoistTaskId: "task-1" } }]
    });

    expect(result).toEqual({ kind: "accepted_stale" });
    expect(obs.exceptions).toHaveLength(1);
    expect(obs.exceptions[0]?.error).toBeInstanceOf(TypeError);
    expect(obs.exceptions[0]?.context).toMatchObject({
      marker: "post_ack_sync_internal_defect",
      operation: "internal_defect_op"
    });
  });

  it("captures an unexpected pre-send preparation defect once and completes the start/failed lifecycle pair", async () => {
    const queue = new TodoistOperationQueue();
    const { adapter } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const obs = new RecordingBackendObservability();
    const defect = new TypeError("SQLite preparation invariant failed");
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce: vi.fn().mockResolvedValue(undefined) },
      snapshotService: () => createMockSnapshot(),
      observability: obs
    });

    await expect(runner.execute({
      operation: "edit_task",
      prepareCommands: () => { throw defect; }
    })).rejects.toBe(defect);

    expect(obs.exceptions).toHaveLength(1);
    expect(obs.exceptions[0]).toMatchObject({
      error: defect,
      context: { marker: "mutation_runner_internal_defect", operation: "edit_task" }
    });
    expect(obs.logs).toEqual([
      {
        level: "info",
        marker: "todoist_operation_started",
        attributes: { operation: "edit_task", command_count: 0 }
      },
      {
        level: "warn",
        marker: "todoist_operation_failed",
        attributes: { operation: "edit_task", error_kind: "internal" }
      }
    ]);
  });

  it.each(["", "   ", 123])("stops a dependent batch and captures once when root item_add returns malformed ID %j", async (todoistTaskId) => {
    const queue = new TodoistOperationQueue();
    const { adapter, calls } = createMockAdapter({
      itemAdd: async () => ({ todoistTaskId } as any)
    });
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();
    const runner = new TaskMutationRunner({
      queue,
      executor: new TodoistCommandExecutor(adapter),
      syncService: { syncOnce: vi.fn().mockResolvedValue(undefined) },
      snapshotService: () => snapshot,
      observability: obs
    });

    const result = await runner.execute({
      operation: "create_task",
      commands: [
        {
          type: "item_add",
          input: {
            kind: "root",
            tempId: "root-temp",
            title: "Root",
            description: null,
            projectId: "project-1",
            assigneeId: null,
            due: { kind: "preserve" },
            duration: null
          }
        },
        {
          type: "item_add",
          input: {
            kind: "child",
            tempId: "child-temp",
            title: "Child",
            description: null,
            projectId: "project-1",
            parentTodoistTaskId: "root-temp"
          }
        }
      ]
    });

    expect(result).toEqual({ kind: "delivery_unknown", snapshot });
    expect(calls).toHaveLength(1);
    expect(obs.exceptions).toHaveLength(1);
    expect(obs.exceptions[0]?.context).toMatchObject({ marker: "todoist_command_parse_defect" });
  });

  it("captures a Todoist parse defect exactly once while returning visible delivery uncertainty", async () => {
    const queue = new TodoistOperationQueue();
    const parseError = new TodoistOperationError("parse", undefined, { http_code: 200 });
    const { adapter } = createMockAdapter({
      itemDelete: async () => {
        throw parseError;
      }
    });
    const executor = new TodoistCommandExecutor(adapter);
    const obs = new RecordingBackendObservability();
    const snapshot = createMockSnapshot();
    const runner = new TaskMutationRunner({
      queue,
      executor,
      syncService: { syncOnce: vi.fn().mockResolvedValue(undefined) },
      snapshotService: () => snapshot,
      observability: obs
    });

    const result = await runner.execute({
      operation: "delete_child",
      requestId: "req-parse",
      commands: [{ type: "item_delete", input: { todoistTaskId: "child-1" } }]
    });

    expect(result).toEqual({ kind: "delivery_unknown", snapshot });
    expect(obs.exceptions).toHaveLength(1);
    expect(obs.exceptions[0]).toMatchObject({
      error: parseError,
      context: {
        marker: "todoist_command_parse_defect",
        operation: "delete_child",
        request_id: "req-parse"
      }
    });
  });
});
