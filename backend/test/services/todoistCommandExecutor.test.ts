import { describe, expect, it, vi } from "vitest";
import {
  MAX_COMMAND_ATTEMPTS,
  MAX_HTTP_ATTEMPT_TIMEOUT_MS,
  TodoistCommandExecutor
} from "../../src/services/todoistCommandExecutor.js";
import { TodoistOperationError } from "../../src/todoist/todoistErrors.js";
import type { TodoistAdapter, TodoistCommand } from "../../src/todoist/todoistAdapter.js";

interface RecordedCall {
  method: string;
  input: any;
  signal?: AbortSignal;
}

function createMockAdapter(handlers?: Partial<Record<keyof TodoistAdapter, (...args: any[]) => Promise<any>>>): {
  adapter: TodoistAdapter;
  calls: RecordedCall[];
} {
  const calls: RecordedCall[] = [];

  const adapter: TodoistAdapter = {
    sync: vi.fn(),
    createTask: vi.fn(),
    completeTask: vi.fn(),
    updateTask: vi.fn(),
    itemAdd: vi.fn(async (input, opts) => {
      calls.push({ method: "itemAdd", input, signal: opts?.signal });
      if (handlers?.itemAdd) return handlers.itemAdd(input, opts);
      return { todoistTaskId: "task-created-id" };
    }),
    itemUpdate: vi.fn(async (input, opts) => {
      calls.push({ method: "itemUpdate", input, signal: opts?.signal });
      if (handlers?.itemUpdate) return handlers.itemUpdate(input, opts);
    }),
    itemMove: vi.fn(async (input, opts) => {
      calls.push({ method: "itemMove", input, signal: opts?.signal });
      if (handlers?.itemMove) return handlers.itemMove(input, opts);
    }),
    itemClose: vi.fn(async (input, opts) => {
      calls.push({ method: "itemClose", input, signal: opts?.signal });
      if (handlers?.itemClose) return handlers.itemClose(input, opts);
    }),
    itemUncomplete: vi.fn(async (input, opts) => {
      calls.push({ method: "itemUncomplete", input, signal: opts?.signal });
      if (handlers?.itemUncomplete) return handlers.itemUncomplete(input, opts);
    }),
    itemDelete: vi.fn(async (input, opts) => {
      calls.push({ method: "itemDelete", input, signal: opts?.signal });
      if (handlers?.itemDelete) return handlers.itemDelete(input, opts);
    }),
    itemUpdateDateComplete: vi.fn(async (input, opts) => {
      calls.push({ method: "itemUpdateDateComplete", input, signal: opts?.signal });
      if (handlers?.itemUpdateDateComplete) return handlers.itemUpdateDateComplete(input, opts);
    })
  };

  return { adapter, calls };
}

describe("TodoistCommandExecutor", () => {
  it("exports MAX_COMMAND_ATTEMPTS = 2 and MAX_HTTP_ATTEMPT_TIMEOUT_MS = 5000", () => {
    expect(MAX_COMMAND_ATTEMPTS).toBe(2);
    expect(MAX_HTTP_ATTEMPT_TIMEOUT_MS).toBe(5000);
  });

  it("sends success once and returns outcome success", async () => {
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_close",
      input: { todoistTaskId: "todoist-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "cmd-uuid-1",
      deadlineMs: Date.now() + 10000
    });

    expect(result).toEqual({ outcome: "success" });
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({
      method: "itemClose",
      input: { commandUuid: "cmd-uuid-1", todoistTaskId: "todoist-1" }
    });
  });

  it("returns todoistTaskId on successful item_add", async () => {
    const { adapter, calls } = createMockAdapter({
      itemAdd: async () => ({ todoistTaskId: "created-task-42" })
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_add",
      input: {
        kind: "root",
        tempId: "temp-1",
        title: "Buy milk",
        projectId: "proj-1",
        assigneeId: null,
        due: { kind: "preserve" },
        duration: null
      }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "add-uuid-1",
      deadlineMs: Date.now() + 10000
    });

    expect(result).toEqual({
      outcome: "success",
      todoistTaskId: "created-task-42"
    });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.input.commandUuid).toBe("add-uuid-1");
  });

  it("retries transport error once reusing the exact same command UUID and succeeds on attempt 2", async () => {
    let attempt = 0;
    const { adapter, calls } = createMockAdapter({
      itemDelete: async () => {
        attempt++;
        if (attempt === 1) {
          throw new TodoistOperationError("transport");
        }
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_delete",
      input: { todoistTaskId: "task-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "fixed-command-uuid",
      deadlineMs: Date.now() + 10000
    });

    expect(result).toEqual({ outcome: "success" });
    expect(calls).toHaveLength(2);
    expect(calls[0]?.input.commandUuid).toBe("fixed-command-uuid");
    expect(calls[1]?.input.commandUuid).toBe("fixed-command-uuid");
  });

  it("retries timeout error once reusing the exact same command UUID and returns delivery_unknown if attempt 2 also fails", async () => {
    const { adapter, calls } = createMockAdapter({
      itemUpdate: async () => {
        throw new TodoistOperationError("timeout");
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_update",
      input: {
        todoistTaskId: "task-1",
        due: { kind: "preserve" },
        assignment: { kind: "preserve" }
      }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "timeout-uuid",
      deadlineMs: Date.now() + 10000
    });

    expect(result.outcome).toBe("delivery_unknown");
    if (result.outcome === "delivery_unknown") {
      expect(result.error.kind).toBe("timeout");
    }
    expect(calls).toHaveLength(2);
    expect(calls[0]?.input.commandUuid).toBe("timeout-uuid");
    expect(calls[1]?.input.commandUuid).toBe("timeout-uuid");
  });

  it("retries 5xx server error once and returns rejected when attempt 2 also fails", async () => {
    const { adapter, calls } = createMockAdapter({
      itemMove: async () => {
        throw new TodoistOperationError("server", undefined, { http_code: 502 });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_move",
      input: { todoistTaskId: "task-1", projectId: "proj-2" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "server-uuid",
      deadlineMs: Date.now() + 10000
    });

    expect(result.outcome).toBe("rejected");
    if (result.outcome === "rejected") {
      expect(result.error.kind).toBe("server");
      expect(result.error.safeAttributes).toEqual({ http_code: 502 });
    }
    expect(calls).toHaveLength(2);
    expect(calls[0]?.input.commandUuid).toBe("server-uuid");
    expect(calls[1]?.input.commandUuid).toBe("server-uuid");
  });

  it("never retries explicit command_rejected failure and returns rejected outcome", async () => {
    const { adapter, calls } = createMockAdapter({
      itemClose: async () => {
        throw new TodoistOperationError("command_rejected", undefined, {
          command_type: "item_close",
          error_tag: "ITEM_NOT_FOUND"
        });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_close",
      input: { todoistTaskId: "task-not-found" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "rejected-uuid",
      deadlineMs: Date.now() + 10000
    });

    expect(result.outcome).toBe("rejected");
    if (result.outcome === "rejected") {
      expect(result.error.kind).toBe("command_rejected");
      expect(result.error.safeAttributes.error_tag).toBe("ITEM_NOT_FOUND");
    }
    expect(calls).toHaveLength(1);
  });

  it("never retries 4xx client failure and returns rejected outcome", async () => {
    const { adapter, calls } = createMockAdapter({
      itemDelete: async () => {
        throw new TodoistOperationError("client", undefined, { http_code: 400 });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_delete",
      input: { todoistTaskId: "task-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "client-err-uuid",
      deadlineMs: Date.now() + 10000
    });

    expect(result.outcome).toBe("rejected");
    if (result.outcome === "rejected") {
      expect(result.error.kind).toBe("client");
      expect(result.error.safeAttributes).toEqual({ http_code: 400 });
    }
    expect(calls).toHaveLength(1);
  });

  it("never retries parse failure and returns delivery_unknown outcome", async () => {
    const { adapter, calls } = createMockAdapter({
      itemDelete: async () => {
        throw new TodoistOperationError("parse", undefined, { http_code: 200 });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_delete",
      input: { todoistTaskId: "task-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "parse-err-uuid",
      deadlineMs: Date.now() + 10000
    });

    expect(result.outcome).toBe("delivery_unknown");
    if (result.outcome === "delivery_unknown") {
      expect(result.error.kind).toBe("parse");
      expect(result.error.safeAttributes).toEqual({ http_code: 200 });
    }
    expect(calls).toHaveLength(1);
  });

  it("treats a successful item_add response without a permanent ID as a parse defect", async () => {
    const { adapter, calls } = createMockAdapter({
      itemAdd: async () => ({ todoistTaskId: null })
    });
    const executor = new TodoistCommandExecutor(adapter);

    const result = await executor.executeCommand({
      command: {
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
      commandUuid: "missing-temp-mapping",
      deadlineMs: Date.now() + 10_000
    });

    expect(result.outcome).toBe("delivery_unknown");
    if (result.outcome === "delivery_unknown") {
      expect(result.error.kind).toBe("parse");
    }
    expect(calls).toHaveLength(1);
  });

  it("honors Retry-After ms from 429 responses when wait and retry fit deadline", async () => {
    let currentTime = 1000;
    const sleptMs: number[] = [];
    const sleep = async (ms: number) => {
      sleptMs.push(ms);
      currentTime += ms;
    };
    let attempt = 0;

    const { adapter, calls } = createMockAdapter({
      itemUncomplete: async () => {
        attempt++;
        if (attempt === 1) {
          throw new TodoistOperationError("rate_limited", 2000, { http_code: 429 });
        }
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_uncomplete",
      input: { todoistTaskId: "task-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "rate-limit-fit",
      deadlineMs: 10000,
      now: () => currentTime,
      sleep
    });

    expect(result).toEqual({ outcome: "success" });
    expect(sleptMs).toEqual([2000]);
    expect(calls).toHaveLength(2);
    expect(calls[0]?.input.commandUuid).toBe("rate-limit-fit");
    expect(calls[1]?.input.commandUuid).toBe("rate-limit-fit");
  });

  it("does not sleep or retry 429 when Retry-After exceeds remaining deadline", async () => {
    let currentTime = 8000;
    const sleptMs: number[] = [];
    const sleep = async (ms: number) => {
      sleptMs.push(ms);
      currentTime += ms;
    };

    const { adapter, calls } = createMockAdapter({
      itemUncomplete: async () => {
        throw new TodoistOperationError("rate_limited", 3000, { http_code: 429 });
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_uncomplete",
      input: { todoistTaskId: "task-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "rate-limit-exceeds",
      deadlineMs: 10000, // 2000ms remaining, but Retry-After is 3000ms
      now: () => currentTime,
      sleep
    });

    expect(result.outcome).toBe("rejected");
    if (result.outcome === "rejected") {
      expect(result.error.kind).toBe("rate_limited");
    }
    expect(sleptMs).toHaveLength(0);
    expect(calls).toHaveLength(1);
  });

  it("does not retry when deadline is exhausted after attempt 1", async () => {
    let currentTime = 1000;
    const { adapter, calls } = createMockAdapter({
      itemDelete: async () => {
        currentTime = 10500; // Simulates time passing beyond deadline (10000)
        throw new TodoistOperationError("transport");
      }
    });
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_delete",
      input: { todoistTaskId: "task-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "deadline-exhausted",
      deadlineMs: 10000,
      now: () => currentTime
    });

    expect(result.outcome).toBe("delivery_unknown");
    if (result.outcome === "delivery_unknown") {
      expect(result.error.kind).toBe("transport");
    }
    expect(calls).toHaveLength(1);
  });

  it("stops immediately without sending if parent AbortSignal is already aborted", async () => {
    const { adapter, calls } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);
    const controller = new AbortController();
    controller.abort();

    const command: TodoistCommand = {
      type: "item_close",
      input: { todoistTaskId: "task-1" }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "already-aborted",
      deadlineMs: Date.now() + 10000,
      signal: controller.signal
    });

    expect(result.outcome).toBe("delivery_unknown");
    expect(calls).toHaveLength(0);
  });

  it("never claims whether earlier batch commands applied", async () => {
    const { adapter } = createMockAdapter();
    const executor = new TodoistCommandExecutor(adapter);

    const command: TodoistCommand = {
      type: "item_update_date_complete",
      input: {
        task: {
          todoistTaskId: "task-1",
          title: "Recurring task",
          description: null,
          projectId: "proj-1",
          parentId: null,
          childOrder: 1,
          assigneeId: null,
          creatorId: null,
          dueDate: "2026-08-31",
          dueDatetime: null,
          dueString: "every day",
          dueLang: "en",
          dueTimezone: null,
          isRecurring: true,
          durationAmount: null,
          durationUnit: null,
          durationMinutes: null,
          completedAt: null,
          deletedAt: null,
          rawJson: {},
          updatedAt: "2026-08-31T10:00:00Z"
        }
      }
    };

    const result = await executor.executeCommand({
      command,
      commandUuid: "recurring-cmd",
      deadlineMs: Date.now() + 10000
    });

    expect(result).toEqual({ outcome: "success" });
    expect(result).not.toHaveProperty("batchSize");
    expect(result).not.toHaveProperty("acknowledgedCount");
    expect(result).not.toHaveProperty("earlierApplied");
  });
});
