import { describe, it, expect, beforeEach, vi } from "vitest";
import * as Sentry from "@sentry/node";
import { openDatabase } from "../src/db/connection.js";
import { migrate } from "../src/db/migrations.js";
import { buildServer } from "../src/server.js";
import { FakeTodoistAdapter } from "./fakes/fakeTodoistAdapter.js";
import { RecordingBackendObservability } from "./fakes/recordingBackendObservability.js";
import {
  NoOpBackendObservability,
  SentryBackendObservability,
  BackendObservabilityRuntime,
  backendObservabilityRuntime,
  backendObservability
} from "../src/diagnostics/backendObservability.js";

describe("NoOpBackendObservability", () => {
  it("is disabled, returns undefined for captures, and flushes successfully", async () => {
    const noop = new NoOpBackendObservability();
    expect(noop.isEnabled()).toBe(false);

    expect(noop.captureRequestFailure(new Error("boom"), { method: "GET", route: "/tasks" })).toBeUndefined();
    expect(noop.captureBackgroundFailure("test.marker", new Error("boom"), { key: "val" })).toBeUndefined();

    // Should not throw
    noop.addBreadcrumb("category", "message", { data: "test" });

    const flushResult = await noop.flush();
    expect(flushResult).toBe(true);
  });
});

describe("BackendObservabilityRuntime", () => {
  beforeEach(() => {
    backendObservabilityRuntime.reset();
  });

  it("initializes NoOp for blank/empty DSN and is idempotent", () => {
    const obs = backendObservabilityRuntime.initialize("");
    expect(obs.isEnabled()).toBe(false);
    expect(obs).toBeInstanceOf(NoOpBackendObservability);

    const obsWhitespace = backendObservabilityRuntime.initialize("   ");
    expect(obsWhitespace).toBe(obs);
    expect(obsWhitespace.isEnabled()).toBe(false);
  });

  it("is idempotent on multiple initialize calls", () => {
    const runtime = new BackendObservabilityRuntime();
    const obs1 = runtime.initialize("https://public@sentry.example.com/1", "test-env", "app.buzer@0.1.0+test");
    expect(obs1.isEnabled()).toBe(true);
    expect(obs1).toBeInstanceOf(SentryBackendObservability);

    const obs2 = runtime.initialize("https://other@sentry.example.com/2");
    expect(obs2).toBe(obs1);
  });

  it("resets to NoOpBackendObservability on reset()", () => {
    const runtime = new BackendObservabilityRuntime();
    runtime.initialize("https://public@sentry.example.com/1");
    expect(runtime.getInstance().isEnabled()).toBe(true);

    runtime.reset();
    expect(runtime.getInstance().isEnabled()).toBe(false);
    expect(runtime.getInstance()).toBeInstanceOf(NoOpBackendObservability);
  });

  it("exported backendObservability proxy delegates to the runtime instance", async () => {
    const fake = new RecordingBackendObservability();
    backendObservabilityRuntime.setInstance(fake);

    expect(backendObservability.isEnabled()).toBe(true);

    backendObservability.addBreadcrumb("test", "msg", { k: "v" });
    expect(fake.breadcrumbs).toHaveLength(1);
    expect(fake.breadcrumbs[0]?.message).toBe("msg");

    const reqId = backendObservability.captureRequestFailure(new Error("err"), { method: "POST" });
    expect(reqId).toBeDefined();
    expect(fake.requestFailures).toHaveLength(1);

    const bgId = backendObservability.captureBackgroundFailure("marker", new Error("bg"));
    expect(bgId).toBeDefined();
    expect(fake.backgroundFailures).toHaveLength(1);

    const flushed = await backendObservability.flush();
    expect(flushed).toBe(true);
  });
});

const sentryMockState = {
  initOptions: null as any,
  lastScope: null as any,
  breadcrumbCalls: [] as any[],
  capturedExceptions: [] as any[],
  capturedMessages: [] as any[],
  flushCalls: [] as number[]
};

vi.mock("@sentry/node", () => ({
  init: vi.fn((options: any) => {
    sentryMockState.initOptions = options;
  }),
  withScope: vi.fn((callback: (scope: any) => void) => {
    const tags: Record<string, string> = {};
    let level: string | undefined;
    const scope = {
      setLevel: vi.fn((lvl: string) => {
        level = lvl;
      }),
      setTag: vi.fn((k: string, v: string) => {
        tags[k] = v;
      }),
      get level() {
        return level;
      },
      get tags() {
        return tags;
      }
    };
    sentryMockState.lastScope = scope;
    return callback(scope);
  }),
  captureException: vi.fn((err: unknown) => {
    sentryMockState.capturedExceptions.push(err);
    return "uuid-1234-5678-90ab-cdef";
  }),
  captureMessage: vi.fn((msg: string) => {
    sentryMockState.capturedMessages.push(msg);
    return "msg-uuid-1234-5678";
  }),
  addBreadcrumb: vi.fn((breadcrumb: any) => {
    sentryMockState.breadcrumbCalls.push(breadcrumb);
  }),
  flush: vi.fn(async (timeoutMs?: number) => {
    sentryMockState.flushCalls.push(timeoutMs ?? 2000);
    return true;
  })
}));

describe("SentryBackendObservability & Privacy Boundary", () => {
  beforeEach(() => {
    sentryMockState.initOptions = null;
    sentryMockState.lastScope = null;
    sentryMockState.breadcrumbCalls = [];
    sentryMockState.capturedExceptions = [];
    sentryMockState.capturedMessages = [];
    sentryMockState.flushCalls = [];
  });

  it("initializes Sentry with sendDefaultPii=false, canonical release, and without tracing", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1",
      environment: "staging-vps",
      release: "app.buzer@0.1.0+gitsha"
    });

    expect(obs.isEnabled()).toBe(true);
    expect(sentryMockState.initOptions).toBeDefined();
    expect(sentryMockState.initOptions.dsn).toBe("https://public@sentry.example.com/1");
    expect(sentryMockState.initOptions.environment).toBe("staging-vps");
    expect(sentryMockState.initOptions.release).toBe("app.buzer@0.1.0+gitsha");
    expect(sentryMockState.initOptions.sendDefaultPii).toBe(false);
    expect(sentryMockState.initOptions.tracesSampleRate).toBeUndefined();
  });

  it("beforeSend strips request and user and recursively redacts fields", () => {
    new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    expect(sentryMockState.initOptions?.beforeSend).toBeDefined();
    const beforeSend = sentryMockState.initOptions.beforeSend;

    const event: any = {
      message: "Task completed with Bearer secret-token-12345",
      request: { url: "https://api.example.com/tasks?token=secret", headers: { authorization: "secret" } },
      user: { id: "user-123", email: "user@example.com", ip_address: "1.2.3.4" },
      exception: {
        values: [
          { value: "Error with Bearer secret-pass-67890 in stack" }
        ]
      },
      tags: {
        custom_key: "safe_value",
        secret_token: "sk-ant-12345678901234567890"
      },
      extra: {
        apiKey: "sk-ant-12345678901234567890"
      },
      contexts: {
        info: {
          token: "Bearer secret-token-abcde"
        }
      },
      breadcrumbs: [
        {
          message: "User logged in with Bearer secret-token-99999",
          data: { key: "Bearer sk-ant-12345678901234567890" }
        }
      ]
    };

    const sanitized = beforeSend(event);

    expect(sanitized.request).toBeUndefined();
    expect(sanitized.user).toBeUndefined();
    expect(sanitized.message).not.toContain("secret-token-12345");
    expect(sanitized.message).toContain("[REDACTED]");
    expect(sanitized.exception.values[0].value).not.toContain("secret-pass-67890");
    expect(sanitized.tags.secret_token).not.toContain("sk-ant-12345678901234567890");
    expect(sanitized.extra.apiKey).not.toContain("sk-ant-12345678901234567890");
    expect(sanitized.contexts.info.token).not.toContain("secret-token-abcde");
    expect(sanitized.breadcrumbs[0].message).not.toContain("secret-token-99999");
    expect(sanitized.breadcrumbs[0].data.key).not.toContain("sk-ant-12345678901234567890");
  });

  it("beforeBreadcrumb redacts message and data", () => {
    new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const beforeBreadcrumb = sentryMockState.initOptions.beforeBreadcrumb;
    const breadcrumb = {
      message: "API Bearer secret-token-12345 call",
      data: { api_key: "sk-ant-12345678901234567890" }
    };

    const sanitized = beforeBreadcrumb(breadcrumb);
    expect(sanitized.message).not.toContain("secret-token-12345");
    expect(sanitized.data.api_key).not.toContain("sk-ant-12345678901234567890");
  });

  it("captureRequestFailure sets scope tags and captures exception", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const error = new Error("Database query failed");
    const eventId = obs.captureRequestFailure(error, {
      requestId: "req-xyz-1",
      method: "POST",
      route: "/tasks",
      statusCode: 500
    });

    expect(eventId).toBe("uuid1234567890abcdef");
    expect(sentryMockState.lastScope.level).toBe("error");
    expect(sentryMockState.lastScope.tags.request_id).toBe("req-xyz-1");
    expect(sentryMockState.lastScope.tags.method).toBe("POST");
    expect(sentryMockState.lastScope.tags.route).toBe("/tasks");
    expect(sentryMockState.lastScope.tags.status_code).toBe("500");
    expect(sentryMockState.capturedExceptions[0]).toBe(error);
  });

  it("captureRequestFailure ignores expected client failures outside Fastify", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const eventId = obs.captureRequestFailure(new Error("Invalid request"), {
      method: "POST",
      route: "/tasks",
      statusCode: 422
    });

    expect(eventId).toBeUndefined();
    expect(sentryMockState.capturedExceptions).toHaveLength(0);
    expect(sentryMockState.capturedMessages).toHaveLength(0);
  });

  it("captureBackgroundFailure sets marker and custom tags", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const error = new Error("Background task failed");
    const eventId = obs.captureBackgroundFailure("buzer.backend.test_marker", error, {
      consecutive_failure_count: "3",
      undefined_key: undefined
    });

    expect(eventId).toBe("uuid1234567890abcdef");
    expect(sentryMockState.lastScope.level).toBe("error");
    expect(sentryMockState.lastScope.tags.marker).toBe("buzer.backend.test_marker");
    expect(sentryMockState.lastScope.tags.consecutive_failure_count).toBe("3");
    expect(sentryMockState.lastScope.tags.undefined_key).toBeUndefined();
    expect(sentryMockState.capturedExceptions[0]).toBe(error);
  });

  it("addBreadcrumb and flush call Sentry primitives", async () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    obs.addBreadcrumb("network", "HTTP request to Todoist", { url: "/sync" });
    expect(sentryMockState.breadcrumbCalls).toHaveLength(1);
    expect(sentryMockState.breadcrumbCalls[0]).toEqual({
      category: "network",
      message: "HTTP request to Todoist",
      data: { url: "/sync" },
      level: "info"
    });

    const flushed = await obs.flush(1500);
    expect(flushed).toBe(true);
    expect(sentryMockState.flushCalls).toContain(1500);
  });
});

describe("Fastify Telemetry & Request Hooks", () => {
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
  });

  it("uses structured logging by default and lets test servers disable it explicitly", () => {
    const runtimeApp = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });
    const quietTestApp = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs,
      logger: false
    });

    expect(runtimeApp.log.level).toBe("info");
    expect(quietTestApp.log.level).toBeUndefined();
  });

  it("redacts credential-bearing request URLs from runtime log records", async () => {
    const write = vi.spyOn(process.stdout, "write").mockImplementation(() => true);
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    try {
      const response = await app.inject({
        method: "GET",
        url: "/tasks/snapshot?auth=SECRET_AUTH_123",
        headers: { authorization: "Bearer secret-token" }
      });

      expect(response.statusCode).toBe(200);
      const logs = write.mock.calls.map(([chunk]) => String(chunk)).join("");
      expect(logs).not.toContain("SECRET_AUTH_123");
      expect(logs).not.toContain("secret-token");
    } finally {
      write.mockRestore();
    }
  });

  it("replaces unsafe request IDs and redacts arbitrary credential-suffix query keys in log records", async () => {
    const write = vi.spyOn(process.stdout, "write").mockImplementation(() => true);
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });

    try {
      const response = await app.inject({
        method: "GET",
        url: "/tasks/snapshot?custom_secret=SECRET_SENTRY_123&safe=value",
        headers: { authorization: "Bearer secret-token", "x-request-id": "SECRET_ANDROID_123" }
      });

      expect(response.statusCode).toBe(200);
      expect(response.headers["x-request-id"]).not.toContain("SECRET_ANDROID_123");
      const logs = write.mock.calls.map(([chunk]) => String(chunk)).join("");
      expect(logs).not.toContain("SECRET_ANDROID_123");
      expect(logs).not.toContain("SECRET_SENTRY_123");
      expect(logs).toContain("custom_secret=[REDACTED]");
    } finally {
      write.mockRestore();
    }
  });

  it("preserves safe semantic Pino messages", async () => {
    const write = vi.spyOn(process.stdout, "write").mockImplementation(() => true);
    const app = buildServer({ db, currentUserId: "user-test", androidApiToken: "secret-token", todoist: new FakeTodoistAdapter() });

    try {
      await app.inject({ method: "GET", url: "/health" });
      expect(write.mock.calls.map(([chunk]) => String(chunk)).join("")).toContain("incoming request");
    } finally {
      write.mockRestore();
    }
  });

  it("does not emit credential-bearing error messages in runtime log records", async () => {
    const write = vi.spyOn(process.stdout, "write").mockImplementation(() => true);
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });
    app.get("/test/credential-error", async () => {
      throw new Error("Bearer SECRET_ANDROID_123");
    });

    try {
      const response = await app.inject({ method: "GET", url: "/test/credential-error" });

      expect(response.statusCode).toBe(500);
      const logs = write.mock.calls.map(([chunk]) => String(chunk)).join("");
      expect(logs).not.toContain("SECRET_ANDROID_123");
    } finally {
      write.mockRestore();
    }
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

  it("routes 5xx unhandled internal errors to captureRequestFailure but skips 4xx validation and not-found errors", async () => {
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
    expect(recordingObs.requestFailures).toHaveLength(0);

    // 2. Client 404 Not Found: should NOT capture exception to Sentry
    const notFoundRes = await app.inject({
      method: "GET",
      url: "/nonexistent-route",
      headers: {
        authorization: "Bearer secret-token"
      }
    });
    expect(notFoundRes.statusCode).toBe(404);
    expect(recordingObs.requestFailures).toHaveLength(0);

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
        authorization: "Bearer secret-token",
        "x-request-id": "req-fail-500"
      }
    });
    expect(errRes.statusCode).toBe(500);
    expect(recordingObs.requestFailures).toHaveLength(1);
    const failure = recordingObs.requestFailures[0];
    expect((failure?.error as Error)?.message).toBe("Unexpected database explosion");
    expect(failure?.context).toMatchObject({
      requestId: "req-fail-500",
      method: "GET",
      route: "/test/unhandled-error",
      statusCode: 500
    });
  });
});
