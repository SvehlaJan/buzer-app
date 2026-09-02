import { type BackendObservability, backendObservability } from "../diagnostics/backendObservability.js";
import { TodoistOperationError } from "../todoist/todoistErrors.js";
import { TodoistOperationQueue } from "./todoistOperationQueue.js";
import type { TodoistSyncOptions, TodoistSyncSummary } from "./todoistSyncService.js";

export const DEFAULT_POLL_INTERVAL_MS = 60_000;
export const POLL_DEADLINE_MS = 13_000;

export interface SyncOnceService {
  syncOnce(options?: TodoistSyncOptions): Promise<TodoistSyncSummary | void>;
}

export interface TodoistPollerDiagnostics {
  consecutiveFailures: number;
  lastOutcome: "success" | "error" | null;
  lastDurationMs: number | null;
  lastSyncAt: string | null;
}

export interface TodoistPollerOptions {
  intervalMs?: number;
  runImmediately?: boolean;
  queue?: TodoistOperationQueue;
  onError?: (error: unknown) => void;
  onSuccess?: (summary?: TodoistSyncSummary | void) => void;
  observability?: BackendObservability;
}

export class TodoistPoller {
  private timer: NodeJS.Timeout | null = null;
  private consecutiveFailures = 0;
  private lastOutcome: "success" | "error" | null = null;
  private lastDurationMs: number | null = null;
  private lastSyncAt: string | null = null;
  private readonly queue: TodoistOperationQueue;
  private readonly intervalMs: number;
  private readonly observability: BackendObservability;

  constructor(
    private readonly service: SyncOnceService,
    private readonly options: TodoistPollerOptions = {}
  ) {
    this.queue = options.queue ?? new TodoistOperationQueue();
    this.intervalMs = options.intervalMs ?? DEFAULT_POLL_INTERVAL_MS;
    this.observability = options.observability ?? backendObservability;
  }

  getDiagnostics(): TodoistPollerDiagnostics {
    return {
      consecutiveFailures: this.consecutiveFailures,
      lastOutcome: this.lastOutcome,
      lastDurationMs: this.lastDurationMs,
      lastSyncAt: this.lastSyncAt
    };
  }

  start(): void {
    if (this.timer !== null) {
      return;
    }

    if (this.options.runImmediately === true) {
      void this.pollOnce();
    }
    this.timer = setInterval(() => {
      void this.pollOnce();
    }, this.intervalMs);
  }

  stop(): void {
    if (this.timer === null) {
      return;
    }

    clearInterval(this.timer);
    this.timer = null;
  }

  async pollOnce(): Promise<void> {
    const start = Date.now();
    try {
      const summary = await this.queue.run(
        {
          operation: "poll",
          deadlineMs: POLL_DEADLINE_MS
        },
        async ({ signal }) => {
          return await this.service.syncOnce({ signal });
        }
      );

      const durationMs = Date.now() - start;
      const hadFailures = this.consecutiveFailures > 0;
      const previousFailureCount = this.consecutiveFailures;

      this.consecutiveFailures = 0;
      this.lastOutcome = "success";
      this.lastDurationMs = durationMs;
      this.lastSyncAt = new Date().toISOString();

      if (hadFailures) {
        this.observability.info?.("todoist_poll_recovered", {
          duration_ms: durationMs,
          previous_failure_count: previousFailureCount
        });
      }

      if (summary && summary.hasChanges) {
        this.observability.info?.("todoist_poll_changed", {
          changed_task_count: summary.changedTaskCount,
          changed_task_ids: summary.changedTaskIds,
          changed_project_count: summary.changedProjectCount,
          changed_collaborator_count: summary.changedCollaboratorCount,
          duration_ms: durationMs
        });
      }

      this.options.onSuccess?.(summary);
    } catch (error) {
      const durationMs = Date.now() - start;
      this.consecutiveFailures += 1;
      this.lastOutcome = "error";
      this.lastDurationMs = durationMs;
      this.lastSyncAt = new Date().toISOString();

      const operational = isOperationalFailure(error);

      this.observability.warn?.("todoist_poll_failed", {
        duration_ms: durationMs,
        consecutive_failure_count: this.consecutiveFailures,
        error_kind: error instanceof TodoistOperationError ? error.kind : "unknown",
        operational
      });

      if (!operational) {
        if (this.observability.captureException) {
          this.observability.captureException(error, {
            marker: "todoist_poll_internal_defect",
            consecutive_failure_count: String(this.consecutiveFailures)
          });
        } else {
          this.observability.captureBackgroundFailure(
            "buzer.backend.todoist_poll_internal_defect",
            error,
            { consecutive_failure_count: String(this.consecutiveFailures) }
          );
        }
      }

      try {
        this.options.onError?.(error);
      } catch {
        // Safe callback isolation
      }
    }
  }

  async tick(): Promise<void> {
    return this.pollOnce();
  }
}

export function isOperationalFailure(error: unknown): boolean {
  if (error instanceof TodoistOperationError) {
    return error.kind !== "parse";
  }
  if (error instanceof Error) {
    if (
      error.name === "AbortError" ||
      error.name === "FetchError" ||
      error.name === "TimeoutError"
    ) {
      return true;
    }
    const msg = error.message.toLowerCase();
    if (
      msg.includes("network") ||
      msg.includes("timeout") ||
      msg.includes("econnreset") ||
      msg.includes("econnrefused") ||
      msg.includes("enotfound") ||
      msg.includes("etimedout") ||
      msg.includes("rate limit") ||
      msg.includes("429") ||
      msg.includes("500") ||
      msg.includes("502") ||
      msg.includes("503") ||
      msg.includes("504") ||
      msg.includes("backend_busy")
    ) {
      return true;
    }
  }
  return false;
}
