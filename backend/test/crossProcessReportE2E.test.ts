import { describe, expect, it } from "vitest";
import { buildE2EServer, type E2EServer } from "./e2eServer.js";
import type { DiagnosticReportRequest, DiagnosticReportResponse } from "../src/diagnostics/diagnosticReportService.js";

const authHeaders = { authorization: "Bearer test-token" };

async function withServer(run: (server: E2EServer) => Promise<void>): Promise<void> {
  const server = await buildE2EServer();
  try {
    await run(server);
  } finally {
    await server.app.close();
  }
}

describe("Cross-Process Report Contract E2E", () => {
  it("rejects unauthorized diagnostic report requests with 401", async () => {
    await withServer(async (server) => {
      const payload: DiagnosticReportRequest = {
        schemaVersion: 1,
        reportSessionId: "session-unauth-1",
        androidDiagnosticEventId: "android-evt-unauth-1",
        deviceId: "device-unauth-1",
        capturedAt: new Date().toISOString(),
        requestIds: []
      };

      const noAuthRes = await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        payload
      });
      expect(noAuthRes.statusCode).toBe(401);

      const badAuthRes = await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: { authorization: "Bearer wrong-token" },
        payload
      });
      expect(badAuthRes.statusCode).toBe(401);
    });
  });

  it("verifies full report capture flow with tags, attachments, and strict redaction", async () => {
    await withServer(async (server) => {
      // 1. Seed some requests with sensitive parameters to test redaction across logs and buffers
      await server.app.inject({
        method: "POST",
        url: "/tasks",
        headers: {
          ...authHeaders,
          "sentry-trace": "trace-secret-1234567890123456",
          baggage: "sentry-environment=test,secret_cookie=SECRET_COOKIE_999",
          "x-device-id": "device-e2e-1"
        },
        payload: {
          actionId: "act-e2e-seed",
          deviceId: "device-e2e-1",
          title: "Task with credentials",
          password: "SECRET_PASSWORD_VAL",
          token: "SECRET_TOKEN_VAL",
          private_key: "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC\n-----END PRIVATE KEY-----"
        }
      });

      // 2. Client sends POST /diagnostics/reports
      const reportRequest: DiagnosticReportRequest = {
        schemaVersion: 1,
        reportSessionId: "session-e2e-flow-123",
        androidDiagnosticEventId: "a1b2c3d4e5f67890123456789abcdef0",
        deviceId: "device-e2e-1",
        capturedAt: "2026-08-21T12:00:00.000Z",
        taskId: "task-start",
        occurrenceKey: "occurrence-task-start-1",
        actionId: "act-e2e-seed",
        requestIds: ["req-1", "req-2"],
        traceId: "trace-abc-123"
      };

      const response = await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: authHeaders,
        payload: reportRequest
      });

      // 3. Verify server responds with 202 and valid DiagnosticReportResponse
      expect(response.statusCode).toBe(202);
      const responseBody = response.json() as DiagnosticReportResponse;
      expect(responseBody.schemaVersion).toBe(1);
      expect(responseBody.reportSessionId).toBe("session-e2e-flow-123");
      expect(responseBody.outcome).toBe("captured");
      expect(responseBody.backendDiagnosticEventId).toMatch(/^[a-f0-9]{32}$/);

      // 4. Inspect recorded Sentry events via debug endpoint GET /__sentry/events
      const debugEventsRes = await server.app.inject({
        method: "GET",
        url: "/__sentry/events"
      });
      expect(debugEventsRes.statusCode).toBe(200);
      const debugEvents = debugEventsRes.json().events;
      expect(debugEvents).toHaveLength(1);

      const sentryEvent = debugEvents[0];
      expect(sentryEvent.message).toBe("buzer.backend.manual_report");
      expect(sentryEvent.reportSessionId).toBe("session-e2e-flow-123");
      expect(sentryEvent.eventId).toBe(responseBody.backendDiagnosticEventId);
      expect(sentryEvent.submitted).toBe(true);

      // Verify required tags
      expect(sentryEvent.tags).toMatchObject({
        report_session_id: "session-e2e-flow-123",
        android_diagnostic_event_id: "a1b2c3d4e5f67890123456789abcdef0",
        backend_diagnostic_event_id: responseBody.backendDiagnosticEventId,
        device_id: "device-e2e-1",
        feedback_submitted: "true",
        capsule_schema_version: "1",
        task_id: "task-start",
        occurrence_key: "occurrence-task-start-1",
        action_id: "act-e2e-seed",
        trace_id: "trace-abc-123"
      });

      // 5. Inspect attachments
      expect(sentryEvent.attachments).toHaveLength(3);
      const attachmentNames = sentryEvent.attachments.map((a: { pathname: string }) => a.pathname);
      expect(attachmentNames).toContain("backend-diagnostics-v1.json");
      expect(attachmentNames).toContain("backend-app-log.txt");
      expect(attachmentNames).toContain("backend-polling-history.json");

      const diagJsonAtt = sentryEvent.attachments.find((a: { pathname: string }) => a.pathname === "backend-diagnostics-v1.json");
      const appLogAtt = sentryEvent.attachments.find((a: { pathname: string }) => a.pathname === "backend-app-log.txt");
      const pollingHistoryAtt = sentryEvent.attachments.find((a: { pathname: string }) => a.pathname === "backend-polling-history.json");

      expect(diagJsonAtt).toBeDefined();
      expect(appLogAtt).toBeDefined();
      expect(pollingHistoryAtt).toBeDefined();

      // Verify capsule JSON structure
      const parsedCapsule = JSON.parse(diagJsonAtt.content);
      expect(parsedCapsule.schemaVersion).toBe(1);
      expect(parsedCapsule.reportSessionId).toBe("session-e2e-flow-123");
      expect(parsedCapsule.androidDiagnosticEventId).toBe("a1b2c3d4e5f67890123456789abcdef0");
      expect(parsedCapsule.backendDiagnosticEventId).toBe(responseBody.backendDiagnosticEventId);
      expect(parsedCapsule.runtime).toBeDefined();
      expect(parsedCapsule.database).toBeDefined();
      expect(parsedCapsule.todoist).toBeDefined();
      expect(parsedCapsule.whatsapp).toBeDefined();

      // 6. Verify strict redaction across all attachment contents
      const forbiddenTokens = [
        "SECRET_PASSWORD_VAL",
        "SECRET_TOKEN_VAL",
        "SECRET_COOKIE_999",
        "test-token",
        "-----BEGIN PRIVATE KEY-----"
      ];

      for (const attachment of [diagJsonAtt, appLogAtt, pollingHistoryAtt]) {
        for (const token of forbiddenTokens) {
          expect(attachment.content).not.toContain(token);
        }
      }
    });
  });

  it("handles duplicate submission idempotently without emitting duplicate Sentry events", async () => {
    await withServer(async (server) => {
      const payload: DiagnosticReportRequest = {
        schemaVersion: 1,
        reportSessionId: "session-idempotency-e2e-1",
        androidDiagnosticEventId: "android-evt-idem-111111111111111",
        deviceId: "device-idem-1",
        capturedAt: "2026-08-21T12:00:00.000Z",
        requestIds: []
      };

      // First submission
      const firstRes = await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: authHeaders,
        payload
      });
      expect(firstRes.statusCode).toBe(202);
      const firstBody = firstRes.json() as DiagnosticReportResponse;

      expect(server.observability.diagnosticEvents).toHaveLength(1);

      // Duplicate submission with the same reportSessionId
      const secondRes = await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: authHeaders,
        payload
      });
      expect(secondRes.statusCode).toBe(202);
      const secondBody = secondRes.json() as DiagnosticReportResponse;

      // Must return identical response
      expect(secondBody).toEqual(firstBody);
      expect(secondBody.backendDiagnosticEventId).toBe(firstBody.backendDiagnosticEventId);

      // Sentry event count MUST remain 1 (no duplicate event emitted)
      expect(server.observability.diagnosticEvents).toHaveLength(1);

      const eventsRes = await server.app.inject({
        method: "GET",
        url: "/__sentry/events"
      });
      expect(eventsRes.json().events).toHaveLength(1);
    });
  });

  it("enforces rate limit: 6 requests per device succeed, 7th returns 429", async () => {
    await withServer(async (server) => {
      const makeRequest = (idx: number, deviceId: string): DiagnosticReportRequest => ({
        schemaVersion: 1,
        reportSessionId: `session-rate-limit-${idx}`,
        androidDiagnosticEventId: `android-rate-limit-${idx}`,
        deviceId,
        capturedAt: "2026-08-21T12:00:00.000Z",
        requestIds: []
      });

      // 6 requests for device-rate-1 succeed
      for (let i = 1; i <= 6; i++) {
        const res = await server.app.inject({
          method: "POST",
          url: "/diagnostics/reports",
          headers: authHeaders,
          payload: makeRequest(i, "device-rate-1")
        });
        expect(res.statusCode).toBe(202);
      }

      // 7th request for device-rate-1 is rate limited
      const rateLimitedRes = await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: authHeaders,
        payload: makeRequest(7, "device-rate-1")
      });
      expect(rateLimitedRes.statusCode).toBe(429);

      // Request from another device succeeds
      const otherDeviceRes = await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: authHeaders,
        payload: makeRequest(8, "device-rate-2")
      });
      expect(otherDeviceRes.statusCode).toBe(202);
    });
  });

  it("resets recorded sentry events on /__reset", async () => {
    await withServer(async (server) => {
      const payload: DiagnosticReportRequest = {
        schemaVersion: 1,
        reportSessionId: "session-to-reset",
        androidDiagnosticEventId: "android-evt-reset",
        deviceId: "device-reset-1",
        capturedAt: "2026-08-21T12:00:00.000Z",
        requestIds: []
      };

      await server.app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: authHeaders,
        payload
      });

      expect(server.observability.diagnosticEvents).toHaveLength(1);

      // Reset
      const resetRes = await server.app.inject({
        method: "POST",
        url: "/__reset",
        payload: {}
      });
      expect(resetRes.statusCode).toBe(200);

      // Events cleared
      expect(server.observability.diagnosticEvents).toHaveLength(0);

      const eventsAfter = await server.app.inject({
        method: "GET",
        url: "/__sentry/events"
      });
      expect(eventsAfter.json().events).toHaveLength(0);
    });
  });
});
