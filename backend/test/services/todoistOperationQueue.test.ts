import { describe, expect, it, vi } from "vitest";
import { TodoistOperationQueue } from "../../src/services/todoistOperationQueue.js";
import { TodoistOperationError } from "../../src/todoist/todoistErrors.js";

describe("TodoistOperationQueue", () => {
  it("executes operations sequentially in strict FIFO start order", async () => {
    const queue = new TodoistOperationQueue();
    const order: string[] = [];
    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    const p1 = queue.run(
      { operation: "op1", deadlineMs: 5000 },
      async () => {
        order.push("p1:start");
        await firstGate;
        order.push("p1:end");
        return "r1";
      }
    );

    const p2 = queue.run(
      { operation: "op2", deadlineMs: 5000 },
      async () => {
        order.push("p2:start");
        order.push("p2:end");
        return "r2";
      }
    );

    const p3 = queue.run(
      { operation: "op3", deadlineMs: 5000 },
      async () => {
        order.push("p3:start");
        order.push("p3:end");
        return "r3";
      }
    );

    await vi.waitFor(() => expect(order).toEqual(["p1:start"]));
    expect(queue.queuedCount).toBe(2);

    releaseFirst?.();

    const results = await Promise.all([p1, p2, p3]);
    expect(results).toEqual(["r1", "r2", "r3"]);
    expect(order).toEqual([
      "p1:start",
      "p1:end",
      "p2:start",
      "p2:end",
      "p3:start",
      "p3:end",
    ]);
    expect(queue.activeCount).toBe(0);
    expect(queue.queuedCount).toBe(0);
  });

  it("enforces single concurrency (activeCount <= 1 at all times)", async () => {
    const queue = new TodoistOperationQueue();
    let maxObservedActive = 0;
    let currentlyRunning = 0;

    const runTask = (id: string) =>
      queue.run({ operation: `task-${id}`, deadlineMs: 5000 }, async () => {
        currentlyRunning++;
        maxObservedActive = Math.max(maxObservedActive, queue.activeCount);
        expect(currentlyRunning).toBe(1);
        await new Promise((resolve) => setTimeout(resolve, 10));
        currentlyRunning--;
      });

    expect(queue.activeCount).toBe(0);

    await Promise.all([
      runTask("1"),
      runTask("2"),
      runTask("3"),
      runTask("4"),
    ]);

    expect(maxObservedActive).toBe(1);
    expect(queue.activeCount).toBe(0);
    expect(currentlyRunning).toBe(0);
  });

  it("calculates queue wait time and remaining execution budget correctly", async () => {
    let currentTime = 1000;
    const clock = () => currentTime;
    const queue = new TodoistOperationQueue({ now: clock });

    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    const p1 = queue.run(
      { operation: "poll", deadlineMs: 10000 },
      async (ctx) => {
        expect(ctx.queueWaitMs).toBe(0);
        expect(ctx.remainingMs).toBe(10000);
        expect(ctx.signal.aborted).toBe(false);
        await firstGate;
      }
    );

    // Advance simulated time before enqueuing second operation
    currentTime = 1200;

    const p2 = queue.run(
      { operation: "mutate", deadlineMs: 5000 },
      async (ctx) => {
        // Enqueued at 1200, started at 2000 => queueWaitMs = 800
        expect(ctx.queueWaitMs).toBe(800);
        expect(ctx.remainingMs).toBe(4200);
        expect(ctx.signal.aborted).toBe(false);
      }
    );

    // Advance time while first is still running
    currentTime = 2000;
    releaseFirst?.();

    await Promise.all([p1, p2]);
  });

  it("rejects an entry whose deadline has expired while waiting in queue before execution", async () => {
    let currentTime = 1000;
    const queue = new TodoistOperationQueue({ now: () => currentTime });

    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    const p1 = queue.run(
      { operation: "long_op", deadlineMs: 10000 },
      async () => {
        await firstGate;
      }
    );

    const secondCallback = vi.fn();
    const p2 = queue.run(
      { operation: "poll", requestId: "req-123", deadlineMs: 2000 },
      async (ctx) => {
        secondCallback();
      }
    );

    const thirdCallback = vi.fn().mockResolvedValue("third-ok");
    const p3 = queue.run(
      { operation: "mutate", deadlineMs: 10000 },
      thirdCallback
    );

    // Advance clock past p2's deadline (enqueued at 1000, deadlineMs is 2000 => expires at 3000)
    currentTime = 3500;
    releaseFirst?.();

    await expect(p1).resolves.toBeUndefined();

    // p2 should be rejected with TodoistOperationError without executing callback
    await expect(p2).rejects.toThrow(TodoistOperationError);
    await expect(p2).rejects.toMatchObject({
      kind: "timeout",
      safeAttributes: {
        error_code: "backend_busy",
        operation: "poll",
        request_id: "req-123",
      },
    });
    expect(secondCallback).not.toHaveBeenCalled();

    // p3 (deadline not expired: enqueued at 1000, wait = 2500, remaining = 7500) should succeed
    await expect(p3).resolves.toBe("third-ok");
    expect(thirdCallback).toHaveBeenCalledOnce();
  });

  it("handles multiple expired items in the queue and drains remaining valid items", async () => {
    let currentTime = 1000;
    const queue = new TodoistOperationQueue({ now: () => currentTime });

    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    const p1 = queue.run({ operation: "op1", deadlineMs: 10000 }, async () => {
      await firstGate;
    });

    const p2 = queue.run({ operation: "op2_short", deadlineMs: 1000 }, vi.fn());
    const p3 = queue.run({ operation: "op3_short", deadlineMs: 1500 }, vi.fn());
    const validCallback = vi.fn().mockResolvedValue("survived");
    const p4 = queue.run({ operation: "op4_long", deadlineMs: 8000 }, validCallback);

    currentTime = 4000;
    releaseFirst?.();

    await expect(p1).resolves.toBeUndefined();
    await expect(p2).rejects.toThrow(TodoistOperationError);
    await expect(p3).rejects.toThrow(TodoistOperationError);
    await expect(p4).resolves.toBe("survived");
    expect(validCallback).toHaveBeenCalledOnce();
  });

  it("rejects an entry if remaining budget is less than minimumExecutionBudgetMs", async () => {
    let currentTime = 1000;
    const queue = new TodoistOperationQueue({ now: () => currentTime });

    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    const p1 = queue.run(
      { operation: "op1", deadlineMs: 10000 },
      async () => {
        await firstGate;
      }
    );

    const secondCallback = vi.fn();
    const p2 = queue.run(
      {
        operation: "mutation",
        requestId: "req-min-budget",
        deadlineMs: 3000,
        minimumExecutionBudgetMs: 500,
      },
      secondCallback
    );

    // Advance time so remainingMs is 400 (< 500 min budget)
    currentTime = 3600; // wait is 2600, remaining is 3000 - 2600 = 400
    releaseFirst?.();

    await expect(p1).resolves.toBeUndefined();
    await expect(p2).rejects.toThrow(TodoistOperationError);
    await expect(p2).rejects.toMatchObject({
      kind: "timeout",
      safeAttributes: {
        error_code: "backend_busy",
        operation: "mutation",
        request_id: "req-min-budget",
      },
    });
    expect(secondCallback).not.toHaveBeenCalled();
  });

  it("rejects immediately at enqueue time if deadlineMs <= 0 or less than minimumExecutionBudgetMs", async () => {
    const queue = new TodoistOperationQueue();

    await expect(
      queue.run({ operation: "op_expired", deadlineMs: 0 }, async () => {})
    ).rejects.toThrow(TodoistOperationError);

    await expect(
      queue.run(
        {
          operation: "op_insufficient",
          deadlineMs: 200,
          minimumExecutionBudgetMs: 500,
        },
        async () => {}
      )
    ).rejects.toThrow(TodoistOperationError);
  });

  it("immediately dequeues and rejects if AbortSignal aborts while waiting in queue", async () => {
    const queue = new TodoistOperationQueue();
    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });

    const p1 = queue.run({ operation: "op1", deadlineMs: 5000 }, async () => {
      await firstGate;
    });

    const ac = new AbortController();
    const abortedCallback = vi.fn();

    const p2 = queue.run(
      { operation: "op2", deadlineMs: 5000, signal: ac.signal },
      abortedCallback
    );

    const thirdCallback = vi.fn().mockResolvedValue("op3_done");
    const p3 = queue.run(
      { operation: "op3", deadlineMs: 5000 },
      thirdCallback
    );

    expect(queue.queuedCount).toBe(2);

    // Abort p2 while waiting
    const abortReason = new Error("Caller cancelled");
    ac.abort(abortReason);

    // p2 should be removed from queue immediately
    expect(queue.queuedCount).toBe(1);
    await expect(p2).rejects.toThrow("Caller cancelled");
    expect(abortedCallback).not.toHaveBeenCalled();

    releaseFirst?.();
    await p1;
    await expect(p3).resolves.toBe("op3_done");
    expect(thirdCallback).toHaveBeenCalledOnce();
  });

  it("immediately rejects if signal is already aborted before calling run", async () => {
    const queue = new TodoistOperationQueue();
    const ac = new AbortController();
    ac.abort(new Error("Already aborted"));

    const callback = vi.fn();
    await expect(
      queue.run(
        { operation: "op", deadlineMs: 5000, signal: ac.signal },
        callback
      )
    ).rejects.toThrow("Already aborted");

    expect(callback).not.toHaveBeenCalled();
    expect(queue.activeCount).toBe(0);
    expect(queue.queuedCount).toBe(0);
  });

  it("releases queue and continues to next entry if active callback throws an error", async () => {
    const queue = new TodoistOperationQueue();
    const error = new Error("Todoist network explosion");

    const p1 = queue.run({ operation: "faulty", deadlineMs: 5000 }, async () => {
      throw error;
    });

    const p2 = queue.run({ operation: "subsequent", deadlineMs: 5000 }, async () => {
      return "recovered";
    });

    await expect(p1).rejects.toThrow(error);
    await expect(p2).resolves.toBe("recovered");
    expect(queue.activeCount).toBe(0);
    expect(queue.queuedCount).toBe(0);
  });

  it("removes abort event listener when item begins execution", async () => {
    const queue = new TodoistOperationQueue();
    const ac = new AbortController();

    let executed = false;
    await queue.run(
      { operation: "test_listener", deadlineMs: 5000, signal: ac.signal },
      async (ctx) => {
        executed = true;
        expect(ctx.signal).toBe(ac.signal);
      }
    );

    expect(executed).toBe(true);
  });
});
