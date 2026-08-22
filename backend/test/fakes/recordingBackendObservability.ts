import * as crypto from "node:crypto";
import type {
  BackendObservability,
  RequestFailureContext
} from "../../src/diagnostics/backendObservability.js";

export interface RecordedBreadcrumb {
  category: string;
  message: string;
  data?: Record<string, unknown>;
}

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
  public breadcrumbs: RecordedBreadcrumb[] = [];
  public requestFailures: RecordedRequestFailure[] = [];
  public backgroundFailures: RecordedBackgroundFailure[] = [];
  public enabled: boolean = true;

  isEnabled(): boolean {
    return this.enabled;
  }

  addBreadcrumb(category: string, message: string, data?: Record<string, unknown>): void {
    this.breadcrumbs.push({
      category,
      message,
      ...(data !== undefined ? { data } : {})
    });
  }

  captureRequestFailure(error: unknown, context: RequestFailureContext): string | undefined {
    if (!this.enabled) return undefined;
    this.requestFailures.push({ error, context });
    return crypto.randomUUID().replace(/-/g, "").toLowerCase();
  }

  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): string | undefined {
    if (!this.enabled) return undefined;
    this.backgroundFailures.push({
      marker,
      error,
      ...(tags !== undefined ? { tags } : {})
    });
    return crypto.randomUUID().replace(/-/g, "").toLowerCase();
  }

  async flush(_timeoutMillis?: number): Promise<boolean> {
    return true;
  }

  clear(): void {
    this.breadcrumbs.length = 0;
    this.requestFailures.length = 0;
    this.backgroundFailures.length = 0;
  }
}
