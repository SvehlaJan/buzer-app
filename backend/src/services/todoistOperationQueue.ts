import { TodoistOperationError } from "../todoist/todoistErrors.js";

export interface QueueRunOptions {
  operation: string;
  requestId?: string;
  deadlineMs: number;
  minimumExecutionBudgetMs?: number;
  signal?: AbortSignal;
}

export interface QueueExecutionContext {
  signal: AbortSignal;
  remainingMs: number;
  queueWaitMs: number;
}

interface QueueItem<T = unknown> {
  options: QueueRunOptions;
  callback: (ctx: QueueExecutionContext) => Promise<T>;
  enqueuedAt: number;
  resolve: (value: T | PromiseLike<T>) => void;
  reject: (reason?: unknown) => void;
  abortHandler?: () => void;
  timer?: NodeJS.Timeout | null;
}

/**
 * Process-wide in-memory FIFO queue for all top-level Todoist operations
 * (polls, mutations, forced refreshes).
 *
 * Enforces:
 * 1. Strict FIFO start order.
 * 2. At most one active callback at a time.
 * 3. Queue wait time counts against the total deadline (deadlineMs).
 * 4. Admission control: operations whose deadline has expired or whose remaining
 *    execution budget is insufficient (< minimumExecutionBudgetMs) are rejected
 *    before execution with a typed TodoistOperationError("timeout", ..., { error_code: "backend_busy" }).
 * 5. Immediate cancellation / dequeue if a client's AbortSignal fires while waiting.
 * 6. A queue-owned execution signal aborts active work at the remaining total deadline.
 *    The caller receives that deadline promptly, while the exclusive slot remains held
 *    until the callback settles; client disconnection no longer controls dequeued work.
 * 7. Pure non-durable in-memory drain loop without SQLite tables, journals, or root mutexes.
 *
 * NON-REENTRANCY WARNING:
 * This queue is strictly non-reentrant. Top-level entry points (HTTP mutation handlers,
 * background poller ticks, manual refresh endpoints) enqueue their units of work.
 * Internal services such as `TodoistSyncService.syncOnce` and individual adapter calls
 * are raw operations and MUST NOT enqueue themselves into this queue; attempting to do so
 * would result in an immediate deadlock.
 */
export class TodoistOperationQueue {
  private readonly queue: QueueItem<unknown>[] = [];
  private isProcessing = false;
  private readonly now: () => number;

  constructor(options?: { now?: () => number }) {
    this.now = options?.now ?? (() => Date.now());
  }

  get activeCount(): number {
    return this.isProcessing ? 1 : 0;
  }

  get queuedCount(): number {
    return this.queue.length;
  }

  async run<T>(
    options: QueueRunOptions,
    callback: (ctx: QueueExecutionContext) => Promise<T>
  ): Promise<T> {
    if (options.signal?.aborted) {
      throw options.signal.reason ?? new Error("Operation aborted");
    }

    const enqueuedAt = this.now();
    const minBudget = options.minimumExecutionBudgetMs ?? 0;

    if (options.deadlineMs <= 0 || options.deadlineMs < minBudget) {
      throw this.createTimeoutError(options);
    }

    return new Promise<T>((resolve, reject) => {
      const item: QueueItem<T> = {
        options,
        callback,
        enqueuedAt,
        resolve: resolve as (value: unknown) => void,
        reject,
      };

      if (options.signal) {
        const abortHandler = () => {
          if (item.timer) {
            clearTimeout(item.timer);
          }
          const index = this.queue.indexOf(item as QueueItem<unknown>);
          if (index !== -1) {
            this.queue.splice(index, 1);
          }
          reject(options.signal!.reason ?? new Error("Operation aborted"));
        };
        item.abortHandler = abortHandler;
        options.signal.addEventListener("abort", abortHandler, { once: true });
      }

      const waitBudget = options.deadlineMs - minBudget;
      if (waitBudget > 0 && Number.isFinite(waitBudget)) {
        item.timer = setTimeout(() => {
          const index = this.queue.indexOf(item as QueueItem<unknown>);
          if (index !== -1) {
            this.queue.splice(index, 1);
            if (item.abortHandler && options.signal) {
              options.signal.removeEventListener("abort", item.abortHandler);
            }
            reject(this.createTimeoutError(options));
          }
        }, waitBudget);
        item.timer.unref?.();
      }

      this.queue.push(item as QueueItem<unknown>);
      void this.processNext();
    });
  }

  private async processNext(): Promise<void> {
    if (this.isProcessing) {
      return;
    }

    while (this.queue.length > 0) {
      const item = this.queue.shift()!;

      if (item.timer) {
        clearTimeout(item.timer);
      }

      // Clean up the queue abort listener before execution
      if (item.abortHandler && item.options.signal) {
        item.options.signal.removeEventListener("abort", item.abortHandler);
      }

      // If aborted right before dequeue
      if (item.options.signal?.aborted) {
        item.reject(item.options.signal.reason ?? new Error("Operation aborted"));
        continue;
      }

      const now = this.now();
      const queueWaitMs = Math.max(0, now - item.enqueuedAt);
      const remainingMs = item.options.deadlineMs - queueWaitMs;
      const minBudget = item.options.minimumExecutionBudgetMs ?? 0;

      if (remainingMs <= 0 || remainingMs < minBudget) {
        item.reject(this.createTimeoutError(item.options));
        continue;
      }

      this.isProcessing = true;
      let executionTimer: NodeJS.Timeout | null = null;
      try {
        const executionController = new AbortController();
        const ctx: QueueExecutionContext = {
          signal: executionController.signal,
          remainingMs,
          queueWaitMs,
        };
        type CallbackOutcome =
          | { kind: "success"; value: unknown }
          | { kind: "failure"; error: unknown };
        type DeadlineOutcome = { kind: "deadline"; error: TodoistOperationError };
        let resolveDeadline: ((outcome: DeadlineOutcome) => void) | undefined;
        const deadlinePromise = new Promise<DeadlineOutcome>((resolve) => {
          resolveDeadline = resolve;
          if (!Number.isFinite(remainingMs)) {
            return;
          }
          executionTimer = setTimeout(() => {
            const error = this.createExecutionTimeoutError(item.options);
            resolve({ kind: "deadline", error });
            executionController.abort(error);
          }, remainingMs);
          executionTimer.unref?.();
        });
        let callbackPromise: Promise<unknown>;
        try {
          callbackPromise = Promise.resolve(item.callback(ctx));
        } catch (error) {
          callbackPromise = Promise.reject(error);
        }
        const callbackOutcome: Promise<CallbackOutcome> = callbackPromise.then(
          (value) => ({ kind: "success", value }),
          (error: unknown) => ({ kind: "failure", error })
        );

        const elapsedAfterInvocation = Math.max(0, this.now() - item.enqueuedAt);
        let firstOutcome: CallbackOutcome | DeadlineOutcome;
        if (elapsedAfterInvocation >= item.options.deadlineMs) {
          const error = this.createExecutionTimeoutError(item.options);
          resolveDeadline?.({ kind: "deadline", error });
          executionController.abort(error);
          firstOutcome = { kind: "deadline", error };
        } else {
          firstOutcome = await Promise.race([callbackOutcome, deadlinePromise]);
        }

        if (firstOutcome.kind === "deadline") {
          item.reject(firstOutcome.error);
          // The caller receives the deadline promptly, but this drain loop retains the
          // exclusive slot until cooperative cancellation actually settles the callback.
          // Mapping both branches consumes a late rejection without surfacing it as an
          // unhandled promise rejection.
          await callbackOutcome;
        } else if (firstOutcome.kind === "failure") {
          item.reject(firstOutcome.error);
        } else {
          item.resolve(firstOutcome.value);
        }
      } catch (err) {
        item.reject(err);
      } finally {
        if (executionTimer !== null) {
          clearTimeout(executionTimer);
        }
        this.isProcessing = false;
      }
    }
  }

  private createTimeoutError(options: QueueRunOptions): TodoistOperationError {
    const safeAttributes: Record<string, string | number> = {
      error_code: "backend_busy",
      failure_stage: "waiting",
      operation: options.operation,
    };
    if (options.requestId) {
      safeAttributes.request_id = options.requestId;
    }
    return new TodoistOperationError("timeout", undefined, safeAttributes);
  }

  private createExecutionTimeoutError(options: QueueRunOptions): TodoistOperationError {
    const safeAttributes: Record<string, string | number> = {
      error_code: "todoist_operation_deadline",
      failure_stage: "active",
      operation: options.operation,
    };
    if (options.requestId) {
      safeAttributes.request_id = options.requestId;
    }
    return new TodoistOperationError("timeout", undefined, safeAttributes);
  }
}
