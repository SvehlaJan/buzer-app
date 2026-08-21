import Database from "better-sqlite3";
import * as crypto from "node:crypto";
import { describe, expect, it } from "vitest";
import { migrate } from "../src/db/migrations.js";
import {
  DiagnosticReportService,
  diagnosticReportRequestSchema
} from "../src/diagnostics/diagnosticReportService.js";
import type {
  BackendObservability,
  DiagnosticAttachment,
  DiagnosticCorrelation
} from "../src/diagnostics/backendObservability.js";

class RecordingBackendObservability implements BackendObservability {
  public capturedEvents: Array<{
    eventId: string;
    reportSessionId: string;
    submitted: boolean;
    context: DiagnosticCorrelation;
    attachments: DiagnosticAttachment[];
  }> = [];

  isEnabled(): boolean {
    return true;
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
    reportSessionId: string,
    submitted: boolean,
    context: DiagnosticCorrelation,
    attachments: DiagnosticAttachment[]
  ): string {
    this.capturedEvents.push({
      eventId,
      reportSessionId,
      submitted,
      context,
      attachments
    });
    return eventId;
  }

  async flush(_timeoutMs?: number): Promise<boolean> {
    return true;
  }
}

describe("DiagnosticReportService", () => {
  it("processes a diagnostic report, captures diagnostic event with attachments, and returns captured outcome", async () => {
    const db = new Database(":memory:");
    migrate(db);

    const recordingObservability = new RecordingBackendObservability();
    const service = new DiagnosticReportService(db, recordingObservability);

    const request = {
      schemaVersion: 1,
      reportSessionId: "session-abc-123",
      androidDiagnosticEventId: "android-evt-456",
      deviceId: "device-xyz-789",
      capturedAt: "2026-08-21T12:30:00.000Z",
      taskId: "task-001",
      occurrenceKey: "occ-001",
      actionId: "act-001",
      requestIds: ["req-1", "req-2"],
      traceId: "trace-999"
    };

    const parsedRequest = diagnosticReportRequestSchema.parse(request);
    const response = await service.handleReport(parsedRequest);

    expect(response).toMatchObject({
      schemaVersion: 1,
      reportSessionId: "session-abc-123",
      backendDiagnosticEventId: expect.stringMatching(/^[a-f0-9]{32}$/),
      outcome: "captured"
    });

    expect(recordingObservability.capturedEvents).toHaveLength(1);
    const captured = recordingObservability.capturedEvents[0];
    expect(captured).toBeDefined();
    expect(captured!.eventId).toBe(response.backendDiagnosticEventId);
    expect(captured!.reportSessionId).toBe("session-abc-123");
    expect(captured!.submitted).toBe(true);
    expect(captured!.context).toMatchObject({
      deviceId: "device-xyz-789",
      reportSessionId: "session-abc-123",
      androidDiagnosticEventId: "android-evt-456",
      taskId: "task-001",
      occurrenceKey: "occ-001",
      actionId: "act-001",
      traceId: "trace-999"
    });

    expect(captured!.attachments).toHaveLength(3);
    const names = captured!.attachments.map((a) => a.pathname);
    expect(names).toEqual(
      expect.arrayContaining([
        "backend-diagnostics-v1.json",
        "backend-app-log.txt",
        "backend-polling-history.json"
      ])
    );
  });

  it("handles duplicate reportSessionId idempotently without re-capturing Sentry event", async () => {
    const db = new Database(":memory:");
    migrate(db);

    const recordingObservability = new RecordingBackendObservability();
    const service = new DiagnosticReportService(db, recordingObservability);

    const request = diagnosticReportRequestSchema.parse({
      schemaVersion: 1,
      reportSessionId: "session-idempotent-1",
      androidDiagnosticEventId: "android-evt-1",
      deviceId: "device-idempotent-1",
      capturedAt: "2026-08-21T12:30:00.000Z"
    });

    // First call
    const firstResponse = await service.handleReport(request);
    expect(firstResponse).toMatchObject({
      schemaVersion: 1,
      reportSessionId: "session-idempotent-1",
      backendDiagnosticEventId: expect.stringMatching(/^[a-f0-9]{32}$/),
      outcome: "captured"
    });
    expect(recordingObservability.capturedEvents).toHaveLength(1);

    // Second call with same reportSessionId
    const secondResponse = await service.handleReport(request);
    expect(secondResponse).toEqual(firstResponse);
    // Sentry event must NOT have been emitted again
    expect(recordingObservability.capturedEvents).toHaveLength(1);

    // Verify row was stored in SQLite diagnostic_reports
    const stored = db.prepare(`
      select
        report_session_id as reportSessionId,
        android_diagnostic_event_id as androidDiagnosticEventId,
        backend_diagnostic_event_id as backendDiagnosticEventId,
        device_id as deviceId,
        captured_at as capturedAt,
        response_json as responseJson
      from diagnostic_reports
      where report_session_id = ?
    `).get("session-idempotent-1") as {
      reportSessionId: string;
      androidDiagnosticEventId: string;
      backendDiagnosticEventId: string;
      deviceId: string;
      capturedAt: string;
      responseJson: string;
    };

    expect(stored).toBeDefined();
    expect(stored.reportSessionId).toBe("session-idempotent-1");
    expect(stored.androidDiagnosticEventId).toBe("android-evt-1");
    expect(stored.backendDiagnosticEventId).toBe(firstResponse.backendDiagnosticEventId);
    expect(stored.deviceId).toBe("device-idempotent-1");
    expect(JSON.parse(stored.responseJson)).toEqual(firstResponse);
  });

  it("validates request schema strictly and rejects invalid requests", () => {
    // Missing required fields
    const invalid1 = {
      schemaVersion: 1,
      reportSessionId: "", // empty
      androidDiagnosticEventId: "event-1",
      deviceId: "device-1",
      capturedAt: "2026-08-21T12:00:00Z"
    };
    expect(() => diagnosticReportRequestSchema.parse(invalid1)).toThrow();

    // Extra unknown fields (strictObject)
    const invalid2 = {
      schemaVersion: 1,
      reportSessionId: "session-1",
      androidDiagnosticEventId: "event-1",
      deviceId: "device-1",
      capturedAt: "2026-08-21T12:00:00Z",
      unknownField: "forbidden"
    };
    expect(() => diagnosticReportRequestSchema.parse(invalid2)).toThrow();
  });
});
