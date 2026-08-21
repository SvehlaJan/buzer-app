import Database from "better-sqlite3";
import * as crypto from "node:crypto";
import { describe, expect, it } from "vitest";
import { migrate } from "../src/db/migrations.js";
import { buildServer } from "../src/server.js";
import { FakeTodoistAdapter } from "./fakes/fakeTodoistAdapter.js";
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

describe("POST /diagnostics/reports endpoint", () => {
  it("requires bearer authentication", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({
      db,
      currentUserId: "user-1",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter()
    });

    const payload = {
      schemaVersion: 1,
      reportSessionId: "session-1",
      androidDiagnosticEventId: "android-evt-1",
      deviceId: "device-1",
      capturedAt: "2026-08-21T12:00:00Z"
    };

    // Missing token
    const noAuth = await app.inject({
      method: "POST",
      url: "/diagnostics/reports",
      payload
    });
    expect(noAuth.statusCode).toBe(401);

    // Invalid token
    const wrongAuth = await app.inject({
      method: "POST",
      url: "/diagnostics/reports",
      headers: { authorization: "Bearer wrong-token" },
      payload
    });
    expect(wrongAuth.statusCode).toBe(401);

    await app.close();
  });

  it("validates request body and returns 400 for invalid body", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const app = buildServer({
      db,
      currentUserId: "user-1",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter()
    });

    const invalidPayload = {
      // Missing reportSessionId, androidDiagnosticEventId, etc.
      schemaVersion: 1,
      deviceId: "device-1"
    };

    const response = await app.inject({
      method: "POST",
      url: "/diagnostics/reports",
      headers: { authorization: "Bearer secret-token" },
      payload: invalidPayload
    });

    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({
      code: "validation_error"
    });

    await app.close();
  });

  it("processes valid report request and returns 200 with captured response", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const recordingObservability = new RecordingBackendObservability();

    const app = buildServer({
      db,
      currentUserId: "user-1",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObservability
    });

    const payload = {
      schemaVersion: 1,
      reportSessionId: "session-ok-123",
      androidDiagnosticEventId: "android-ok-456",
      deviceId: "device-100",
      capturedAt: "2026-08-21T12:00:00Z",
      taskId: "task-test-1",
      occurrenceKey: "occ-test-1",
      actionId: "act-test-1",
      requestIds: ["req-a", "req-b"],
      traceId: "trace-xyz"
    };

    const response = await app.inject({
      method: "POST",
      url: "/diagnostics/reports",
      headers: { authorization: "Bearer secret-token" },
      payload
    });

    expect(response.statusCode).toBe(200);
    const body = response.json();
    expect(body).toMatchObject({
      schemaVersion: 1,
      reportSessionId: "session-ok-123",
      backendDiagnosticEventId: expect.stringMatching(/^[a-f0-9]{32}$/),
      outcome: "captured"
    });

    expect(recordingObservability.capturedEvents).toHaveLength(1);
    const captured = recordingObservability.capturedEvents[0];
    expect(captured).toBeDefined();
    expect(captured!.eventId).toBe(body.backendDiagnosticEventId);
    expect(captured!.reportSessionId).toBe("session-ok-123");
    expect(captured!.submitted).toBe(true);
    expect(captured!.context).toMatchObject({
      deviceId: "device-100",
      reportSessionId: "session-ok-123",
      taskId: "task-test-1",
      occurrenceKey: "occ-test-1",
      actionId: "act-test-1",
      traceId: "trace-xyz"
    });

    await app.close();
  });

  it("enforces rate limit of 6 requests per minute per device", async () => {
    const db = new Database(":memory:");
    migrate(db);
    const recordingObservability = new RecordingBackendObservability();

    const app = buildServer({
      db,
      currentUserId: "user-1",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObservability
    });

    const makePayload = (idx: number, deviceId: string) => ({
      schemaVersion: 1,
      reportSessionId: `session-rate-${idx}`,
      androidDiagnosticEventId: `android-evt-${idx}`,
      deviceId,
      capturedAt: "2026-08-21T12:00:00Z"
    });

    // 6 requests for device-rate-A should all succeed
    for (let i = 1; i <= 6; i++) {
      const res = await app.inject({
        method: "POST",
        url: "/diagnostics/reports",
        headers: { authorization: "Bearer secret-token" },
        payload: makePayload(i, "device-rate-A")
      });
      expect(res.statusCode).toBe(200);
    }

    // 7th request for device-rate-A should be rate limited (429)
    const blocked = await app.inject({
      method: "POST",
      url: "/diagnostics/reports",
      headers: { authorization: "Bearer secret-token" },
      payload: makePayload(7, "device-rate-A")
    });
    expect(blocked.statusCode).toBe(429);

    // Request from a different device (device-rate-B) should still succeed
    const otherDevice = await app.inject({
      method: "POST",
      url: "/diagnostics/reports",
      headers: { authorization: "Bearer secret-token" },
      payload: makePayload(8, "device-rate-B")
    });
    expect(otherDevice.statusCode).toBe(200);

    await app.close();
  });
});
