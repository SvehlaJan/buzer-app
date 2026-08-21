import { type BackendObservability, backendObservability } from "../diagnostics/backendObservability.js";
import { backendDiagnosticRingBuffer, backendLogRingBuffer, type RingBuffer } from "../diagnostics/ringBuffer.js";
import { redactString } from "../diagnostics/redaction.js";

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
  diagnosticRingBuffer?: RingBuffer<unknown>;
  logRingBuffer?: RingBuffer<string>;
}

export class TodoistPoller {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private consecutiveFailures = 0;
  private lastOutcome: "success" | "error" | null = null;
  private lastDurationMs: number | null = null;
  private lastSyncAt: string | null = null;
  private readonly observability: BackendObservability;
  private readonly diagnosticRingBuffer: RingBuffer<unknown>;
  private readonly logRingBuffer: RingBuffer<string>;

  constructor(
    private readonly service: SyncOnceService,
    private readonly options: TodoistPollerOptions = {}
  ) {
    this.observability = options.observability ?? backendObservability;
    this.diagnosticRingBuffer = options.diagnosticRingBuffer ?? backendDiagnosticRingBuffer;
    this.logRingBuffer = options.logRingBuffer ?? backendLogRingBuffer;
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
    const startTimeIso = new Date(start).toISOString();
    try {
      await this.service.syncOnce();
      const durationMs = Date.now() - start;
      this.consecutiveFailures = 0;
      this.lastOutcome = "success";
      this.lastDurationMs = durationMs;
      this.lastSyncAt = new Date().toISOString();

      this.diagnosticRingBuffer.add({
        type: "todoist_poll",
        timestamp: startTimeIso,
        outcome: "success",
        durationMs,
        consecutiveFailures: 0
      });

      this.logRingBuffer.add(
        `[${new Date().toISOString()}] INFO Todoist poll succeeded in ${durationMs}ms`
      );

      this.options.onSuccess?.();
    } catch (error) {
      const durationMs = Date.now() - start;
      this.consecutiveFailures += 1;
      this.lastOutcome = "error";
      this.lastDurationMs = durationMs;
      this.lastSyncAt = new Date().toISOString();

      const errorMessage = error instanceof Error ? error.message : String(error);

      this.diagnosticRingBuffer.add({
        type: "todoist_poll",
        timestamp: startTimeIso,
        outcome: "error",
        durationMs,
        consecutiveFailures: this.consecutiveFailures,
        error: redactString(errorMessage)
      });

      this.logRingBuffer.add(
        `[${new Date().toISOString()}] WARN Todoist poll failed (consecutive=${this.consecutiveFailures}, duration=${durationMs}ms): ${redactString(errorMessage)}`
      );

      this.options.onError?.(error);

      if (this.consecutiveFailures >= 3) {
        this.observability.captureHandled("buzer.backend.todoist_poll_failure", error, {
          consecutiveFailureCount: this.consecutiveFailures,
          consecutive_failure_count: this.consecutiveFailures
        });
      }
    } finally {
      this.running = false;
    }
  }
}
