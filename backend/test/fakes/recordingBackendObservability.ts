import * as crypto from "node:crypto";
import type {
  BackendObservability,
  DiagnosticAttachment,
  DiagnosticCorrelation
} from "../../src/diagnostics/backendObservability.js";

export interface RecordedDiagnosticEvent {
  eventId: string;
  reportSessionId: string;
  submitted: boolean;
  context: DiagnosticCorrelation;
  attachments: DiagnosticAttachment[];
  message: string;
  tags: Record<string, string>;
}

export interface RecordedHandledError {
  marker: string;
  error: unknown;
  correlation?: DiagnosticCorrelation | undefined;
  tags?: Record<string, string>;
}

export class RecordingBackendObservability implements BackendObservability {
  public handledErrors: RecordedHandledError[] = [];
  public fatalErrors: RecordedHandledError[] = [];
  public diagnosticEvents: RecordedDiagnosticEvent[] = [];
  public contexts: DiagnosticCorrelation[] = [];
  public enabled: boolean = true;

  isEnabled(): boolean {
    return this.enabled;
  }

  setContext(correlation: DiagnosticCorrelation): void {
    this.contexts.push(correlation);
  }

  captureHandled(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    const eventId = this.newEventId();
    const tags: Record<string, string> = { marker };
    if (correlation) {
      for (const [k, v] of Object.entries(correlation)) {
        if (v !== undefined && v !== null && (typeof v === "string" || typeof v === "number" || typeof v === "boolean")) {
          const snakeKey = k.replace(/[A-Z]/g, (m) => `_${m.toLowerCase()}`);
          tags[snakeKey] = String(v);
        }
      }
    }
    this.handledErrors.push({ marker, error, correlation, tags });
    return eventId;
  }

  captureFatal(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    const eventId = this.newEventId();
    const tags: Record<string, string> = { marker };
    if (correlation) {
      for (const [k, v] of Object.entries(correlation)) {
        if (v !== undefined && v !== null && (typeof v === "string" || typeof v === "number" || typeof v === "boolean")) {
          const snakeKey = k.replace(/[A-Z]/g, (m) => `_${m.toLowerCase()}`);
          tags[snakeKey] = String(v);
        }
      }
    }
    this.fatalErrors.push({ marker, error, correlation, tags });
    return eventId;
  }

  newEventId(): string {
    return crypto.randomUUID().replace(/-/g, "").toLowerCase();
  }

  captureDiagnosticEvent(
    eventId: string,
    reportSessionId: string,
    submitted: boolean,
    context: DiagnosticCorrelation,
    attachments: DiagnosticAttachment[]
  ): string {
    const tags: Record<string, string> = {
      report_session_id: reportSessionId,
      backend_diagnostic_event_id: eventId,
      feedback_submitted: String(submitted),
      capsule_schema_version: "1"
    };
    if (context) {
      for (const [k, v] of Object.entries(context)) {
        if (v !== undefined && v !== null && (typeof v === "string" || typeof v === "number" || typeof v === "boolean")) {
          const snakeKey = k.replace(/[A-Z]/g, (m) => `_${m.toLowerCase()}`);
          tags[snakeKey] = String(v);
        }
      }
    }
    this.diagnosticEvents.push({
      eventId,
      reportSessionId,
      submitted,
      context,
      attachments,
      message: "buzer.backend.manual_report",
      tags
    });
    return eventId;
  }

  async flush(_timeoutMs?: number): Promise<boolean> {
    return true;
  }

  clear(): void {
    this.handledErrors.length = 0;
    this.fatalErrors.length = 0;
    this.diagnosticEvents.length = 0;
    this.contexts.length = 0;
  }
}
