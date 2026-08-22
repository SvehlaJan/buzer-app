import { type BackendObservability, backendObservability } from "../diagnostics/backendObservability.js";

export interface SyncOnceService {
  syncOnce(): Promise<void>;
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
  onError?: (error: unknown) => void;
  onSuccess?: () => void;
  observability?: BackendObservability;
}

export class TodoistPoller {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private consecutiveFailures = 0;
  private lastOutcome: "success" | "error" | null = null;
  private lastDurationMs: number | null = null;
  private lastSyncAt: string | null = null;
  private readonly observability: BackendObservability;

  constructor(
    private readonly service: SyncOnceService,
    private readonly options: TodoistPollerOptions = {}
  ) {
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
      void this.tick();
    }
    this.timer = setInterval(() => {
      void this.tick();
    }, this.options.intervalMs ?? 5 * 60_000);
  }

  stop(): void {
    if (this.timer === null) {
      return;
    }

    clearInterval(this.timer);
    this.timer = null;
  }

  async tick(): Promise<void> {
    if (this.running) {
      return;
    }

    this.running = true;
    const start = Date.now();
    try {
      await this.service.syncOnce();
      const durationMs = Date.now() - start;
      this.consecutiveFailures = 0;
      this.lastOutcome = "success";
      this.lastDurationMs = durationMs;
      this.lastSyncAt = new Date().toISOString();

      this.options.onSuccess?.();
    } catch (error) {
      const durationMs = Date.now() - start;
      this.consecutiveFailures += 1;
      this.lastOutcome = "error";
      this.lastDurationMs = durationMs;
      this.lastSyncAt = new Date().toISOString();

      if (this.consecutiveFailures >= 3) {
        this.observability.captureBackgroundFailure("buzer.backend.todoist_poll_failure", error, {
          consecutive_failure_count: String(this.consecutiveFailures)
        });
      }

      try {
        this.options.onError?.(error);
      } catch {
        // Safe callback isolation
      }
    } finally {
      this.running = false;
    }
  }
}
