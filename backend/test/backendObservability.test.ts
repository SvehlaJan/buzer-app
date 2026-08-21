import { describe, it, expect, beforeEach } from "vitest";
import * as crypto from "node:crypto";
import { openDatabase } from "../src/db/connection.js";
import { migrate } from "../src/db/migrations.js";
import { buildServer } from "../src/server.js";
import { FakeTodoistAdapter } from "./fakes/fakeTodoistAdapter.js";
import {
  RingBuffer,
  truncateUtf8,
  backendLogRingBuffer,
  backendDiagnosticRingBuffer
} from "../src/diagnostics/ringBuffer.js";
import {
  NoOpBackendObservability,
  SentryBackendObservability,
  backendObservabilityRuntime,
  type BackendObservability,
  type DiagnosticAttachment,
  type DiagnosticCorrelation
} from "../src/diagnostics/backendObservability.js";

class RecordingBackendObservability implements BackendObservability {
  public handledErrors: Array<{ marker: string; error: unknown; correlation?: DiagnosticCorrelation | undefined }> = [];
  public fatalErrors: Array<{ marker: string; error: unknown; correlation?: DiagnosticCorrelation | undefined }> = [];
  public diagnosticEvents: Array<{
    eventId: string;
    reportSessionId: string;
    submitted: boolean;
    context: DiagnosticCorrelation;
    attachments: DiagnosticAttachment[];
  }> = [];
  public contexts: DiagnosticCorrelation[] = [];
  public enabled: boolean = true;

  isEnabled(): boolean {
    return this.enabled;
  }

  setContext(correlation: DiagnosticCorrelation): void {
    this.contexts.push(correlation);
  }

  captureHandled(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    this.handledErrors.push({ marker, error, correlation });
    return this.newEventId();
  }

  captureFatal(marker: string, error: unknown, correlation?: DiagnosticCorrelation): string | null {
    this.fatalErrors.push({ marker, error, correlation });
    return this.newEventId();
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
    this.diagnosticEvents.push({ eventId, reportSessionId, submitted, context, attachments });
    return eventId;
  }

  async flush(_timeoutMs?: number): Promise<boolean> {
    return true;
  }
}

describe("RingBuffer & Truncation", () => {
  it("bounds items to capacity and snapshots in newest-first order", () => {
    const buffer = new RingBuffer<string>(3, 60_000);
    buffer.add("first", 1000);
    buffer.add("second", 2000);
    buffer.add("third", 3000);
    buffer.add("fourth", 4000);

    expect(buffer.size()).toBe(3);
    const snapshot = buffer.snapshot(4000);
    expect(snapshot).toEqual(["fourth", "third", "second"]);
  });

  it("filters out items older than maxAgeMillis", () => {
    const buffer = new RingBuffer<string>(10, 10_000); // 10s max age
    buffer.add("expired_1", 1_000);
    buffer.add("expired_2", 2_000);
    buffer.add("fresh_1", 15_000);
    buffer.add("fresh_2", 18_000);

    const snapshot = buffer.snapshot(20_000); // now is 20s; min timestamp is 10s
    expect(snapshot).toEqual(["fresh_2", "fresh_1"]);
  });

  it("clears entries on clear()", () => {
    const buffer = new RingBuffer<number>(5);
    buffer.add(1);
    buffer.add(2);
    expect(buffer.size()).toBe(2);
    buffer.clear();
    expect(buffer.size()).toBe(0);
    expect(buffer.snapshot()).toEqual([]);
  });

  it("safely truncates UTF-8 strings without splitting multi-byte code points or surrogate pairs", () => {
    // ASCII test
    expect(truncateUtf8("hello world", 5)).toBe("hello");
    expect(truncateUtf8("hello", 10)).toBe("hello");
    expect(truncateUtf8("hello", 0)).toBe("");
    expect(truncateUtf8("hello", -1)).toBe("");

    // 4-byte UTF-8 emoji (rocket: 🚀 = \uD83D\uDE80)
    const rocket = "🚀";
    expect(Buffer.byteLength(rocket, "utf8")).toBe(4);

    // If maxBytes is 3, cannot fit 4-byte rocket, should return empty string
    expect(truncateUtf8("🚀", 3)).toBe("");
    // If maxBytes is 4, fits rocket
    expect(truncateUtf8("🚀", 4)).toBe("🚀");
    // Rocket + ascii: "🚀test" -> rocket is 4 bytes, 't' is 1 byte -> 5 bytes
    expect(truncateUtf8("🚀test", 5)).toBe("🚀t");
    expect(truncateUtf8("🚀test", 3)).toBe("");

    // Multi-byte characters (Czech accented characters: 2 bytes each)
    // "Švehla" -> 'Š' is 2 bytes (\xC5\xA0)
    expect(truncateUtf8("Švehla", 2)).toBe("Š");
    expect(truncateUtf8("Švehla", 3)).toBe("Šv");
    expect(truncateUtf8("Švehla", 1)).toBe("");
  });
});

describe("BackendObservability boundary", () => {
  it("NoOpBackendObservability is disabled and returns null for captures", async () => {
    const noop = new NoOpBackendObservability();
    expect(noop.isEnabled()).toBe(false);

    const eventId = noop.newEventId();
    expect(eventId).toMatch(/^[0-9a-f]{32}$/);

    expect(noop.captureHandled("test", new Error("boom"))).toBeNull();
    expect(noop.captureFatal("test", new Error("fatal boom"))).toBeNull();

    const diagId = noop.captureDiagnosticEvent(eventId, "session-1", true, {}, []);
    expect(diagId).toBe(eventId);

    const flushResult = await noop.flush();
    expect(flushResult).toBe(true);
  });

  it("backendObservabilityRuntime initializes NoOp for blank DSN", () => {
    const obs = backendObservabilityRuntime.initialize("");
    expect(obs.isEnabled()).toBe(false);
    expect(obs).toBeInstanceOf(NoOpBackendObservability);

    const obsWhitespace = backendObservabilityRuntime.initialize("   ");
    expect(obsWhitespace.isEnabled()).toBe(false);
    expect(obsWhitespace).toBeInstanceOf(NoOpBackendObservability);
  });

  it("SentryBackendObservability initializes when DSN is present", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1",
      environment: "test-env",
      release: "buzer-backend@0.1.0+test"
    });
    expect(obs.isEnabled()).toBe(true);
    expect(obs.newEventId()).toMatch(/^[0-9a-f]{32}$/);
  });
});

describe("Fastify Telemetry & Request Tracing Hooks", () => {
  let db: ReturnType<typeof openDatabase>;
  let recordingObs: RecordingBackendObservability;

  beforeEach(() => {
    db = openDatabase(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, mirror_contract_version, updated_at)
      values (1, 'ready-token', 2, '2026-07-15T10:00:00Z')
    `).run();
    recordingObs = new RecordingBackendObservability();
    backendLogRingBuffer.clear();
    backendDiagnosticRingBuffer.clear();
  });

  it("propagates existing X-Request-ID and returns it in response header", async () => {
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    const customRequestId = "custom-req-id-12345";
    const res = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: {
        authorization: "Bearer secret-token",
        "x-request-id": customRequestId
      }
    });

    expect(res.statusCode).toBe(200);
    expect(res.headers["x-request-id"]).toBe(customRequestId);

    // Verify diagnostic ring buffer contains request
    const diagSnapshot = backendDiagnosticRingBuffer.snapshot();
    expect(diagSnapshot.length).toBeGreaterThan(0);
    const entry = diagSnapshot[0];
    expect(entry.requestId).toBe(customRequestId);
    expect(entry.statusCode).toBe(200);
    expect(entry.method).toBe("GET");
  });

  it("generates UUID X-Request-ID when header is missing", async () => {
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    const res = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: {
        authorization: "Bearer secret-token"
      }
    });

    expect(res.statusCode).toBe(200);
    const generatedId = res.headers["x-request-id"] as string;
    expect(generatedId).toBeDefined();
    expect(generatedId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("propagates sentry-trace and extracts correlation params (taskId, actionId, deviceId)", async () => {
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    const traceId = "12345678901234567890123456789012";
    await app.inject({
      method: "POST",
      url: "/tasks",
      headers: {
        authorization: "Bearer secret-token",
        "sentry-trace": traceId,
        "x-device-id": "device-pixel-9"
      },
      payload: {
        actionId: "act-999",
        deviceId: "device-pixel-9",
        title: "Test task",
        projectId: "proj-1"
      }
    });

    // Verify context was recorded with correlation
    const contexts = recordingObs.contexts;
    expect(contexts.length).toBeGreaterThan(0);
    const lastContext = contexts[contexts.length - 1];
    expect(lastContext).toBeDefined();
    expect(lastContext?.traceId).toBe(traceId);
    expect(lastContext?.deviceId).toBe("device-pixel-9");
    expect(lastContext?.actionId).toBe("act-999");
  });

  it("redacts and truncates request body in ring buffer up to 64 KiB", async () => {
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    // Create a payload larger than 64 KiB with embedded secrets
    const largePadding = "x".repeat(70_000);
    const payload = {
      actionId: "act-large",
      deviceId: "device-1",
      password: "SECRET_PASSWORD_999",
      data: largePadding
    };

    await app.inject({
      method: "POST",
      url: "/tasks",
      headers: {
        authorization: "Bearer secret-token"
      },
      payload
    });

    const diagSnapshot = backendDiagnosticRingBuffer.snapshot();
    expect(diagSnapshot.length).toBeGreaterThan(0);
    const entry = diagSnapshot[0];
    expect(entry.requestBody).toBeDefined();
    expect(Buffer.byteLength(entry.requestBody, "utf8")).toBeLessThanOrEqual(65536);
    expect(entry.requestBody).not.toContain("SECRET_PASSWORD_999");
    expect(entry.requestBody).toContain("[REDACTED]");
  });

  it("routes 5xx unhandled internal errors to captureHandled but skips 4xx validation errors", async () => {
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    // 1. Client 400 Bad Request: should NOT capture exception to Sentry
    const badReqRes = await app.inject({
      method: "POST",
      url: "/tasks",
      headers: {
        authorization: "Bearer secret-token"
      },
      payload: {
        // missing required fields for task creation
      }
    });
    expect(badReqRes.statusCode).toBe(400);
    expect(recordingObs.handledErrors.length).toBe(0);

    // 2. Client 404 Not Found: should NOT capture exception to Sentry
    const notFoundRes = await app.inject({
      method: "GET",
      url: "/nonexistent-route",
      headers: {
        authorization: "Bearer secret-token"
      }
    });
    expect(notFoundRes.statusCode).toBe(404);
    expect(recordingObs.handledErrors.length).toBe(0);

    // 3. App with a route that throws an unhandled 500 error
    const errorApp = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });
    errorApp.get("/test/unhandled-error", async () => {
      throw new Error("Unexpected database explosion");
    });

    const errRes = await errorApp.inject({
      method: "GET",
      url: "/test/unhandled-error",
      headers: {
        authorization: "Bearer secret-token"
      }
    });
    expect(errRes.statusCode).toBe(500);
    expect(recordingObs.handledErrors.length).toBe(1);
    expect(recordingObs.handledErrors[0]?.marker).toBe("fastify_unhandled_error");
    expect((recordingObs.handledErrors[0]?.error as Error)?.message).toBe("Unexpected database explosion");
  });
});
