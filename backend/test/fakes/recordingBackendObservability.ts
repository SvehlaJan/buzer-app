import type {
  BackendObservability,
  RequestFailureContext
} from "../../src/diagnostics/backendObservability.js";

export interface RecordedRequestFailure {
  error: unknown;
  context: RequestFailureContext;
}

export interface RecordedBackgroundFailure {
  marker: string;
  error: unknown;
  tags?: Record<string, string | undefined>;
}

export interface RecordedLog {
  level: "info" | "warn" | "error";
  marker: string;
  attributes?: Record<string, unknown>;
}

export interface RecordedException {
  error: unknown;
  context?: Record<string, unknown>;
}

export class RecordingBackendObservability implements BackendObservability {
  public requestFailures: RecordedRequestFailure[] = [];
  public backgroundFailures: RecordedBackgroundFailure[] = [];
  public logs: RecordedLog[] = [];
  public exceptions: RecordedException[] = [];

  info(marker: string, attributes?: Record<string, unknown>): void {
    this.logs.push({ level: "info", marker, ...(attributes !== undefined ? { attributes } : {}) });
  }

  warn(marker: string, attributes?: Record<string, unknown>): void {
    this.logs.push({ level: "warn", marker, ...(attributes !== undefined ? { attributes } : {}) });
  }

  captureException(error: unknown, context?: Record<string, unknown>): void {
    this.exceptions.push({ error, ...(context !== undefined ? { context } : {}) });
  }

  captureRequestFailure(error: unknown, context: RequestFailureContext): void {
    this.requestFailures.push({ error, context });
  }

  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): void {
    this.backgroundFailures.push({
      marker,
      error,
      ...(tags !== undefined ? { tags } : {})
    });
  }

  async flush(_timeoutMillis?: number): Promise<void> {
  }

  clear(): void {
    this.requestFailures.length = 0;
    this.backgroundFailures.length = 0;
    this.logs.length = 0;
    this.exceptions.length = 0;
  }
}
