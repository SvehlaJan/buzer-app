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

export class RecordingBackendObservability implements BackendObservability {
  public requestFailures: RecordedRequestFailure[] = [];
  public backgroundFailures: RecordedBackgroundFailure[] = [];

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
  }
}
