import * as crypto from "node:crypto";
import * as Sentry from "@sentry/node";
import { redactAny, redactObject, redactString } from "./redaction.js";

export interface DiagnosticAttachment {
  pathname: string;
  bytes: Buffer;
  contentType?: string;
}

export interface DiagnosticCorrelation {
  deviceId?: string;
  taskId?: string;
  occurrenceKey?: string;
  actionId?: string;
  requestId?: string;
  traceId?: string;
  baggage?: string;
  reportSessionId?: string;
  androidDiagnosticEventId?: string;
}

export interface BackendObservability {
  isEnabled(): boolean;
  setContext(correlation: DiagnosticCorrelation): void;
  captureHandled(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null;
  captureFatal(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null;
  newEventId(): string;
  captureDiagnosticEvent(
    eventId: string,
    reportSessionId: string,
    submitted: boolean,
    context: DiagnosticCorrelation,
    attachments: DiagnosticAttachment[]
  ): string;
  flush(timeoutMs?: number): Promise<boolean>;
}

export class NoOpBackendObservability implements BackendObservability {
  isEnabled(): boolean {
    return false;
  }

  setContext(_correlation: DiagnosticCorrelation): void {}

  captureHandled(_marker: string, _error: unknown, _correlation?: DiagnosticCorrelation): string | null {
    return null;
  }

  captureFatal(_marker: string, _error: unknown, _correlation?: DiagnosticCorrelation): string | null {
    return null;
  }

  newEventId(): string {
    return crypto.randomUUID().replace(/-/g, "").toLowerCase();
  }

  captureDiagnosticEvent(
    eventId: string,
    _reportSessionId: string,
    _submitted: boolean,
    _context: DiagnosticCorrelation,
    _attachments: DiagnosticAttachment[]
  ): string {
    return eventId;
  }

  async flush(_timeoutMs: number = 2000): Promise<boolean> {
    return true;
  }
}

export interface SentryObservabilityOptions {
  dsn: string;
  environment?: string | undefined;
  release?: string | undefined;
}

export class SentryBackendObservability implements BackendObservability {
  private readonly enabled: boolean;

  constructor(options: SentryObservabilityOptions) {
    if (!options.dsn || options.dsn.trim() === "") {
      this.enabled = false;
      return;
    }

    this.enabled = true;
    Sentry.init({
      dsn: options.dsn,
      environment: options.environment ?? "development-vps",
      release: options.release ?? "buzer-backend@0.1.0+local",
      sendDefaultPii: true,
      tracesSampleRate: 1.0,
      beforeSend: (event) => {
        if (event.message) {
          event.message = redactString(event.message);
        }
        if (event.exception?.values) {
          for (const val of event.exception.values) {
            if (val.value) {
              val.value = redactString(val.value);
            }
          }
        }
        if (event.tags) {
          event.tags = redactObject(event.tags) as Record<string, string | number | boolean>;
        }
        if (event.extra) {
          event.extra = redactObject(event.extra);
        }
        if (event.contexts) {
          event.contexts = redactObject(event.contexts) as Record<string, Record<string, unknown>>;
        }
        return event;
      },
      beforeBreadcrumb: (breadcrumb) => {
        if (breadcrumb.message) {
          breadcrumb.message = redactString(breadcrumb.message);
        }
        if (breadcrumb.data) {
          breadcrumb.data = redactObject(breadcrumb.data);
        }
        return breadcrumb;
      }
    });
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  newEventId(): string {
    return crypto.randomUUID().replace(/-/g, "").toLowerCase();
  }

  setContext(correlation: DiagnosticCorrelation): void {
    if (!this.enabled) return;
    const sanitized = redactObject(correlation as unknown as Record<string, unknown>);
    Sentry.setContext("correlation", sanitized);
    if (correlation.deviceId) Sentry.setTag("device_id", correlation.deviceId);
    if (correlation.taskId) Sentry.setTag("task_id", correlation.taskId);
    if (correlation.occurrenceKey) Sentry.setTag("occurrence_key", correlation.occurrenceKey);
    if (correlation.actionId) Sentry.setTag("action_id", correlation.actionId);
    if (correlation.requestId) Sentry.setTag("request_id", correlation.requestId);
    if (correlation.traceId) Sentry.setTag("trace_id", correlation.traceId);
    if (correlation.baggage) Sentry.setTag("baggage", correlation.baggage);
    if (correlation.reportSessionId) Sentry.setTag("report_session_id", correlation.reportSessionId);
    if (correlation.androidDiagnosticEventId) Sentry.setTag("android_diagnostic_event_id", correlation.androidDiagnosticEventId);
  }

  captureHandled(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    if (!this.enabled) return null;
    let eventId: string | undefined;
    Sentry.withScope((scope) => {
      scope.setLevel("error");
      scope.setTag("marker", marker);
      if (correlation) {
        const sanitized = redactObject(correlation as unknown as Record<string, unknown>);
        scope.setContext("correlation", sanitized);
        if (correlation.deviceId) scope.setTag("device_id", correlation.deviceId);
        if (correlation.taskId) scope.setTag("task_id", correlation.taskId);
        if (correlation.occurrenceKey) scope.setTag("occurrence_key", correlation.occurrenceKey);
        if (correlation.actionId) scope.setTag("action_id", correlation.actionId);
        if (correlation.requestId) scope.setTag("request_id", correlation.requestId);
        if (correlation.traceId) scope.setTag("trace_id", correlation.traceId);
        if (correlation.baggage) scope.setTag("baggage", correlation.baggage);
        if (correlation.reportSessionId) scope.setTag("report_session_id", correlation.reportSessionId);
        if (correlation.androidDiagnosticEventId) scope.setTag("android_diagnostic_event_id", correlation.androidDiagnosticEventId);
      }
      if (error instanceof Error) {
        eventId = Sentry.captureException(error);
      } else {
        eventId = Sentry.captureMessage(redactString(String(error)));
      }
    });
    return eventId ? eventId.replace(/-/g, "").toLowerCase() : null;
  }

  captureFatal(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    if (!this.enabled) return null;
    let eventId: string | undefined;
    Sentry.withScope((scope) => {
      scope.setLevel("fatal");
      scope.setTag("marker", marker);
      if (correlation) {
        const sanitized = redactObject(correlation as unknown as Record<string, unknown>);
        scope.setContext("correlation", sanitized);
        if (correlation.deviceId) scope.setTag("device_id", correlation.deviceId);
        if (correlation.taskId) scope.setTag("task_id", correlation.taskId);
        if (correlation.occurrenceKey) scope.setTag("occurrence_key", correlation.occurrenceKey);
        if (correlation.actionId) scope.setTag("action_id", correlation.actionId);
        if (correlation.requestId) scope.setTag("request_id", correlation.requestId);
        if (correlation.traceId) scope.setTag("trace_id", correlation.traceId);
        if (correlation.baggage) scope.setTag("baggage", correlation.baggage);
        if (correlation.reportSessionId) scope.setTag("report_session_id", correlation.reportSessionId);
        if (correlation.androidDiagnosticEventId) scope.setTag("android_diagnostic_event_id", correlation.androidDiagnosticEventId);
      }
      if (error instanceof Error) {
        eventId = Sentry.captureException(error);
      } else {
        eventId = Sentry.captureMessage(redactString(String(error)));
      }
    });
    return eventId ? eventId.replace(/-/g, "").toLowerCase() : null;
  }

  captureDiagnosticEvent(
    eventId: string,
    reportSessionId: string,
    submitted: boolean,
    context: DiagnosticCorrelation,
    attachments: DiagnosticAttachment[]
  ): string {
    if (!this.enabled) return eventId;
    Sentry.withScope((scope) => {
      scope.setLevel("info");
      scope.setTag("report_session_id", reportSessionId);
      scope.setTag("feedback_submitted", String(submitted));
      scope.setTag("capsule_schema_version", "1");
      if (context) {
        const sanitized = redactObject(context as unknown as Record<string, unknown>);
        scope.setContext("correlation", sanitized);
        if (context.deviceId) scope.setTag("device_id", context.deviceId);
        if (context.taskId) scope.setTag("task_id", context.taskId);
        if (context.occurrenceKey) scope.setTag("occurrence_key", context.occurrenceKey);
        if (context.actionId) scope.setTag("action_id", context.actionId);
        if (context.requestId) scope.setTag("request_id", context.requestId);
        if (context.traceId) scope.setTag("trace_id", context.traceId);
        if (context.baggage) scope.setTag("baggage", context.baggage);
        if (context.reportSessionId) scope.setTag("report_session_id", context.reportSessionId);
        if (context.androidDiagnosticEventId) scope.setTag("android_diagnostic_event_id", context.androidDiagnosticEventId);
      }
      for (const att of attachments) {
        scope.addAttachment({
          filename: att.pathname,
          data: new Uint8Array(att.bytes),
          ...(att.contentType !== undefined ? { contentType: att.contentType } : {})
        });
      }
      Sentry.captureEvent({
        event_id: eventId,
        message: "buzer.backend.manual_report",
        level: "info"
      });
    });
    return eventId;
  }

  async flush(timeoutMs: number = 2000): Promise<boolean> {
    if (!this.enabled) return true;
    return Sentry.flush(timeoutMs);
  }
}

export class BackendObservabilityRuntime {
  private instance: BackendObservability = new NoOpBackendObservability();
  private initialized: boolean = false;

  initialize(dsn?: string, environment?: string, release?: string): BackendObservability {
    if (this.initialized) {
      return this.instance;
    }
    if (!dsn || dsn.trim() === "") {
      this.instance = new NoOpBackendObservability();
    } else {
      this.instance = new SentryBackendObservability({
        dsn,
        environment: environment ?? "development-vps",
        release: release ?? "buzer-backend@0.1.0+local"
      });
    }
    this.initialized = true;
    return this.instance;
  }

  getInstance(): BackendObservability {
    return this.instance;
  }

  setInstance(instance: BackendObservability): void {
    this.instance = instance;
    this.initialized = true;
  }

  reset(): void {
    this.instance = new NoOpBackendObservability();
    this.initialized = false;
  }
}

export const backendObservabilityRuntime = new BackendObservabilityRuntime();

export const backendObservability: BackendObservability = {
  isEnabled(): boolean {
    return backendObservabilityRuntime.getInstance().isEnabled();
  },
  setContext(correlation: DiagnosticCorrelation): void {
    backendObservabilityRuntime.getInstance().setContext(correlation);
  },
  captureHandled(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    return backendObservabilityRuntime.getInstance().captureHandled(marker, error, correlation);
  },
  captureFatal(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    return backendObservabilityRuntime.getInstance().captureFatal(marker, error, correlation);
  },
  newEventId(): string {
    return backendObservabilityRuntime.getInstance().newEventId();
  },
  captureDiagnosticEvent(
    eventId: string,
    reportSessionId: string,
    submitted: boolean,
    context: DiagnosticCorrelation,
    attachments: DiagnosticAttachment[]
  ): string {
    return backendObservabilityRuntime.getInstance().captureDiagnosticEvent(
      eventId,
      reportSessionId,
      submitted,
      context,
      attachments
    );
  },
  flush(timeoutMs?: number): Promise<boolean> {
    return backendObservabilityRuntime.getInstance().flush(timeoutMs);
  }
};
