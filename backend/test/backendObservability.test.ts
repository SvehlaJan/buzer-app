import { describe, it, expect, beforeEach, vi } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
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
  backendObservability,
  isForbiddenLogAttributeKey,
  sanitizeLogAttributes,
  sanitizeLog,
  MAX_LOG_ARRAY_LENGTH
} from "../src/diagnostics/backendObservability.js";

describe("NoOpBackendObservability", () => {
  it("has no capture side effects and awaits flush", async () => {
    const noop = new NoOpBackendObservability();

    expect(noop.info("marker", { key: "val" })).toBeUndefined();
    expect(noop.warn("marker", { key: "val" })).toBeUndefined();
    expect(noop.captureException(new Error("boom"), { taskId: "123" })).toBeUndefined();
    expect(noop.captureRequestFailure(new Error("boom"), { method: "GET", route: "/tasks" })).toBeUndefined();
    expect(noop.captureBackgroundFailure("test.marker", new Error("boom"), { key: "val" })).toBeUndefined();

    const flushResult = await noop.flush();
    expect(flushResult).toBeUndefined();
  });
});

describe("BackendObservabilityRuntime", () => {
  beforeEach(() => {
    backendObservabilityRuntime.reset();
  });

  it("initializes NoOp for blank/empty DSN and is idempotent", () => {
    const obs = backendObservabilityRuntime.initialize("");
    expect(obs).toBeInstanceOf(NoOpBackendObservability);

    const obsWhitespace = backendObservabilityRuntime.initialize("   ");
    expect(obsWhitespace).toBe(obs);
  });

  it("is idempotent on multiple initialize calls", () => {
    const runtime = new BackendObservabilityRuntime();
    const obs1 = runtime.initialize("https://public@sentry.example.com/1", "test-env", "app.buzer@0.1.0+test");
    expect(obs1).toBeInstanceOf(SentryBackendObservability);

    const obs2 = runtime.initialize("https://other@sentry.example.com/2");
    expect(obs2).toBe(obs1);
  });

  it("resets to NoOpBackendObservability on reset()", () => {
    const runtime = new BackendObservabilityRuntime();
    runtime.initialize("https://public@sentry.example.com/1");
    runtime.reset();
    expect(runtime.getInstance()).toBeInstanceOf(NoOpBackendObservability);
  });

  it("exported backendObservability proxy delegates capture and flush to the runtime instance", async () => {
    const fake = new RecordingBackendObservability();
    backendObservabilityRuntime.setInstance(fake);

    expect(backendObservability.info("marker.info", { safe_key: "value" })).toBeUndefined();
    expect(fake.logs).toHaveLength(1);
    expect(fake.logs[0]).toEqual({ level: "info", marker: "marker.info", attributes: { safe_key: "value" } });

    expect(backendObservability.warn("marker.warn", { warn_key: 123 })).toBeUndefined();
    expect(fake.logs).toHaveLength(2);
    expect(fake.logs[1]).toEqual({ level: "warn", marker: "marker.warn", attributes: { warn_key: 123 } });

    expect(backendObservability.captureException(new Error("err"), { taskId: "task-1" })).toBeUndefined();
    expect(fake.exceptions).toHaveLength(1);

    expect(backendObservability.captureRequestFailure(new Error("err"), { method: "POST" })).toBeUndefined();
    expect(fake.requestFailures).toHaveLength(1);

    expect(backendObservability.captureBackgroundFailure("marker", new Error("bg"))).toBeUndefined();
    expect(fake.backgroundFailures).toHaveLength(1);

    expect(await backendObservability.flush()).toBeUndefined();
  });
});

describe("backend startup", () => {
  it("preloads instrumentation in every supported server launcher while server only consumes the runtime", () => {
    const backendRoot = fileURLToPath(new URL("..", import.meta.url));
    const packageJson = JSON.parse(readFileSync(`${backendRoot}/package.json`, "utf8")) as { scripts: Record<string, string> };
    const dockerfile = readFileSync(`${backendRoot}/../Dockerfile`, "utf8");
    const serverSource = readFileSync(`${backendRoot}/src/server.ts`, "utf8");

    expect(packageJson.scripts.start).toContain("--import ./dist/instrument.js");
    expect(packageJson.scripts.dev).toContain("--import ./src/instrument.ts");
    expect(dockerfile).toContain('CMD ["node", "--import", "./dist/instrument.js", "./dist/server.js"]');
    expect(serverSource).not.toContain("backendObservabilityRuntime.initialize(");
  });
});

const sentryMockState = {
  initOptions: null as any,
  lastScope: null as any,
  capturedExceptions: [] as any[],
  capturedMessages: [] as any[],
  flushCalls: [] as number[],
  loggerInfoCalls: [] as { marker: string; attributes?: Record<string, unknown> | undefined }[],
  loggerWarnCalls: [] as { marker: string; attributes?: Record<string, unknown> | undefined }[],
  breadcrumbs: [] as any[]
};

vi.mock("@sentry/node", () => ({
  init: vi.fn((options: any) => {
    sentryMockState.initOptions = options;
  }),
  logger: {
    info: vi.fn((marker: string, attributes?: Record<string, unknown>) => {
      sentryMockState.loggerInfoCalls.push({ marker, attributes });
    }),
    warn: vi.fn((marker: string, attributes?: Record<string, unknown>) => {
      sentryMockState.loggerWarnCalls.push({ marker, attributes });
    })
  },
  addBreadcrumb: vi.fn((breadcrumb: any) => {
    sentryMockState.breadcrumbs.push(breadcrumb);
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
  }),
  captureMessage: vi.fn((msg: string) => {
    sentryMockState.capturedMessages.push(msg);
  }),
  flush: vi.fn(async (timeoutMs?: number) => {
    sentryMockState.flushCalls.push(timeoutMs ?? 2000);
    return true;
  })
}));

describe("isForbiddenLogAttributeKey & sanitizeLogAttributes", () => {
  it("correctly identifies forbidden keys", () => {
    // Forbidden exact / suffix keys
    expect(isForbiddenLogAttributeKey("title")).toBe(true);
    expect(isForbiddenLogAttributeKey("task_title")).toBe(true);
    expect(isForbiddenLogAttributeKey("taskTitle")).toBe(true);
    expect(isForbiddenLogAttributeKey("project_title")).toBe(true);
    expect(isForbiddenLogAttributeKey("description")).toBe(true);
    expect(isForbiddenLogAttributeKey("task_description")).toBe(true);
    expect(isForbiddenLogAttributeKey("content")).toBe(true);
    expect(isForbiddenLogAttributeKey("task_content")).toBe(true);
    expect(isForbiddenLogAttributeKey("body_content")).toBe(true);
    expect(isForbiddenLogAttributeKey("summary")).toBe(true);
    expect(isForbiddenLogAttributeKey("task_summary")).toBe(true);
    expect(isForbiddenLogAttributeKey("text")).toBe(true);
    expect(isForbiddenLogAttributeKey("prompt")).toBe(true);
    expect(isForbiddenLogAttributeKey("header")).toBe(true);
    expect(isForbiddenLogAttributeKey("headers")).toBe(true);
    expect(isForbiddenLogAttributeKey("auth_header")).toBe(true);
    expect(isForbiddenLogAttributeKey("req_headers")).toBe(true);
    expect(isForbiddenLogAttributeKey("res_headers")).toBe(true);
    expect(isForbiddenLogAttributeKey("body")).toBe(true);
    expect(isForbiddenLogAttributeKey("raw_body")).toBe(true);
    expect(isForbiddenLogAttributeKey("req_body")).toBe(true);
    expect(isForbiddenLogAttributeKey("payload")).toBe(true);
    expect(isForbiddenLogAttributeKey("raw_payload")).toBe(true);
    expect(isForbiddenLogAttributeKey("todoist_payload")).toBe(true);
    expect(isForbiddenLogAttributeKey("dump")).toBe(true);
    expect(isForbiddenLogAttributeKey("task_dump")).toBe(true);
    expect(isForbiddenLogAttributeKey("task")).toBe(true);
    expect(isForbiddenLogAttributeKey("tasks")).toBe(true);
    expect(isForbiddenLogAttributeKey("project")).toBe(true);
    expect(isForbiddenLogAttributeKey("url")).toBe(true);
    expect(isForbiddenLogAttributeKey("raw_url")).toBe(true);
    expect(isForbiddenLogAttributeKey("uri")).toBe(true);
    expect(isForbiddenLogAttributeKey("href")).toBe(true);
    expect(isForbiddenLogAttributeKey("email")).toBe(true);
    expect(isForbiddenLogAttributeKey("user_email")).toBe(true);
    expect(isForbiddenLogAttributeKey("phone")).toBe(true);
    expect(isForbiddenLogAttributeKey("phone_number")).toBe(true);

    // Credential keys
    expect(isForbiddenLogAttributeKey("token")).toBe(true);
    expect(isForbiddenLogAttributeKey("api_key")).toBe(true);
    expect(isForbiddenLogAttributeKey("api_token")).toBe(true);
    expect(isForbiddenLogAttributeKey("access_token")).toBe(true);
    expect(isForbiddenLogAttributeKey("refresh_token")).toBe(true);
    expect(isForbiddenLogAttributeKey("password")).toBe(true);
    expect(isForbiddenLogAttributeKey("secret")).toBe(true);
    expect(isForbiddenLogAttributeKey("cookie")).toBe(true);
    expect(isForbiddenLogAttributeKey("authorization")).toBe(true);
    expect(isForbiddenLogAttributeKey("qr_code")).toBe(true);

    // Allowed scalar identifiers and metrics
    expect(isForbiddenLogAttributeKey("request_id")).toBe(false);
    expect(isForbiddenLogAttributeKey("task_id")).toBe(false);
    expect(isForbiddenLogAttributeKey("occurrence_key")).toBe(false);
    expect(isForbiddenLogAttributeKey("operation")).toBe(false);
    expect(isForbiddenLogAttributeKey("error_code")).toBe(false);
    expect(isForbiddenLogAttributeKey("status_code")).toBe(false);
    expect(isForbiddenLogAttributeKey("duration_ms")).toBe(false);
    expect(isForbiddenLogAttributeKey("queue_wait_ms")).toBe(false);
    expect(isForbiddenLogAttributeKey("consecutive_failure_count")).toBe(false);
    expect(isForbiddenLogAttributeKey("action_id")).toBe(false);
    expect(isForbiddenLogAttributeKey("device_id")).toBe(false);
    expect(isForbiddenLogAttributeKey("changed_task_ids")).toBe(false);
    expect(isForbiddenLogAttributeKey("changed_task_count")).toBe(false);
    expect(isForbiddenLogAttributeKey("is_success")).toBe(false);
    expect(isForbiddenLogAttributeKey("operational")).toBe(false);
    expect(isForbiddenLogAttributeKey("error_kind")).toBe(false);
    expect(isForbiddenLogAttributeKey("previous_failure_count")).toBe(false);
  });

  it("sanitizeLogAttributes strips forbidden keys and redacts secrets", () => {
    const sanitized = sanitizeLogAttributes({
      task_title: "Secret Task",
      task_id: "task-100",
      description: "Secret desc",
      operation: "sync",
      auth_header: "Bearer sk-12345",
      duration_ms: 120,
      safe_text: "Bearer sk-ant-secret12345678901234567890",
      null_val: null,
      bool_val: true,
      num_val: 42,
      bigint_val: 100n,
      undef_val: undefined
    });

    expect(sanitized).toEqual({
      task_id: "task-100",
      operation: "sync",
      duration_ms: 120,
      safe_text: "Bearer [REDACTED]",
      null_val: null,
      bool_val: true,
      num_val: 42,
      bigint_val: 100n
    });
  });

  it("sanitizeLogAttributes truncates scalar arrays exceeding MAX_LOG_ARRAY_LENGTH and adds count/truncated flags", () => {
    const shortArray = ["id-1", "id-2", "Bearer secret-val"];
    const longArray = Array.from({ length: 15 }, (_, i) => `item-${i + 1}`);

    const sanitized = sanitizeLogAttributes({
      short_list: shortArray,
      long_list: longArray,
      object_list: [{ id: "1" }, { id: "2" }]
    });

    expect(sanitized.short_list).toEqual(["id-1", "id-2", "Bearer [REDACTED]"]);
    expect(sanitized.short_list_count).toBeUndefined();
    expect(sanitized.short_list_truncated).toBeUndefined();

    expect(sanitized.long_list).toEqual(longArray.slice(0, MAX_LOG_ARRAY_LENGTH));
    expect(sanitized.long_list_count).toBe(15);
    expect(sanitized.long_list_truncated).toBe(true);

    // Non-scalar array omitted
    expect(sanitized.object_list).toBeUndefined();
  });
});

describe("sanitizeLog", () => {
  it("redacts message and sanitizes attributes", () => {
    const log: any = {
      level: "info",
      message: "Operation completed with Bearer secret-tok-12345",
      attributes: {
        task_id: "t-1",
        task_title: "Forbidden Title",
        auth: "Bearer secret-tok-12345"
      }
    };

    const sanitized = sanitizeLog(log);
    expect(sanitized.message).not.toContain("secret-tok-12345");
    expect(sanitized.message).toContain("[REDACTED]");
    expect(sanitized.attributes).toEqual({
      task_id: "t-1"
    });
  });

  it("handles log without attributes", () => {
    const log: any = {
      level: "warn",
      message: "Warning message without attrs"
    };

    const sanitized = sanitizeLog(log);
    expect(sanitized.message).toBe("Warning message without attrs");
    expect(sanitized.attributes).toBeUndefined();
  });
});

describe("SentryBackendObservability & Privacy Boundary", () => {
  beforeEach(() => {
    sentryMockState.initOptions = null;
    sentryMockState.lastScope = null;
    sentryMockState.capturedExceptions = [];
    sentryMockState.capturedMessages = [];
    sentryMockState.flushCalls = [];
    sentryMockState.loggerInfoCalls = [];
    sentryMockState.loggerWarnCalls = [];
    sentryMockState.breadcrumbs = [];
  });

  it("initializes Sentry with sendDefaultPii=false, enableLogs=true, beforeSendLog, canonical release, and without tracing", () => {
    new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1",
      environment: "staging-vps",
      release: "app.buzer@0.1.0+gitsha"
    });

    expect(sentryMockState.initOptions).toBeDefined();
    expect(sentryMockState.initOptions.dsn).toBe("https://public@sentry.example.com/1");
    expect(sentryMockState.initOptions.environment).toBe("staging-vps");
    expect(sentryMockState.initOptions.release).toBe("app.buzer@0.1.0+gitsha");
    expect(sentryMockState.initOptions.sendDefaultPii).toBe(false);
    expect(sentryMockState.initOptions.enableLogs).toBe(true);
    expect(typeof sentryMockState.initOptions.beforeSendLog).toBe("function");
    expect(sentryMockState.initOptions.tracesSampleRate).toBeUndefined();
  });

  it("does not initialize Sentry when DSN is empty or whitespace", () => {
    const emptyObs = new SentryBackendObservability({ dsn: "" });
    expect(sentryMockState.initOptions).toBeNull();

    emptyObs.info("some_marker", { key: "val" });
    emptyObs.warn("some_warn", { key: "val" });
    emptyObs.captureException(new Error("boom"));
    expect(sentryMockState.loggerInfoCalls).toHaveLength(0);
    expect(sentryMockState.loggerWarnCalls).toHaveLength(0);
    expect(sentryMockState.capturedExceptions).toHaveLength(0);
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

  it("beforeSendLog strips forbidden keys, redacts secrets, and truncates long scalar arrays with count/truncated flag", () => {
    new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const beforeSendLog = sentryMockState.initOptions.beforeSendLog;
    expect(beforeSendLog).toBeDefined();

    const logEntry: any = {
      message: "Operation completed with token Bearer secret-token-12345",
      attributes: {
        // Allowed scalars
        request_id: "req-123",
        task_id: "task-456",
        occurrence_key: "task-456:2026-08-31",
        operation: "complete_occurrence",
        error_code: "TASK_NOT_FOUND",
        status_code: 404,
        duration_ms: 42,
        queue_wait_ms: 10,
        consecutive_failure_count: 3,
        is_success: false,
        // Forbidden keys to strip
        task_title: "Secret Task Title",
        title: "Top Level Title",
        description: "Confidential task description",
        task_description: "Another description",
        content: "Some sensitive content",
        task_content: "Sensitive task body",
        body: "raw body content",
        req_body: "request body",
        payload: { sensitive: "data" },
        raw_payload: '{"some":"payload"}',
        raw_url: "https://api.todoist.com/rest/v2/tasks",
        url: "https://example.com/sensitive",
        auth_header: "Bearer secret-token",
        headers: { authorization: "secret" },
        dump: "task dump text",
        prompt: "system prompt content",
        email: "user@example.com",
        user_email: "admin@example.com",
        phone: "+1234567890",
        phone_number: "+0987654321",
        token: "secret-token-val",
        api_key: "sk-ant-1234567890",
        secret: "my-secret-key",
        // Value redaction for allowed key containing secrets
        custom_message: "Connected with Bearer sk-ant-12345678901234567890",
        // Scalar arrays <= 10 items
        allowed_task_ids: ["t-1", "t-2", "Bearer secret-token-in-array"],
        // Scalar arrays > 10 items (should truncate and add _count and _truncated)
        long_scalar_list: ["id-1", "id-2", "id-3", "id-4", "id-5", "id-6", "id-7", "id-8", "id-9", "id-10", "id-11", "id-12"],
        // Non-scalar array (should be stripped)
        object_array: [{ a: 1 }, { b: 2 }],
        // Complex object (should be stripped)
        nested_object: { foo: "bar" }
      }
    };

    const sanitized = beforeSendLog(logEntry);

    expect(sanitized.message).not.toContain("secret-token-12345");
    expect(sanitized.message).toContain("[REDACTED]");

    // Check allowed scalars are retained
    expect(sanitized.attributes.request_id).toBe("req-123");
    expect(sanitized.attributes.task_id).toBe("task-456");
    expect(sanitized.attributes.occurrence_key).toBe("task-456:2026-08-31");
    expect(sanitized.attributes.operation).toBe("complete_occurrence");
    expect(sanitized.attributes.error_code).toBe("TASK_NOT_FOUND");
    expect(sanitized.attributes.status_code).toBe(404);
    expect(sanitized.attributes.duration_ms).toBe(42);
    expect(sanitized.attributes.queue_wait_ms).toBe(10);
    expect(sanitized.attributes.consecutive_failure_count).toBe(3);
    expect(sanitized.attributes.is_success).toBe(false);

    // Check forbidden keys are stripped
    expect(sanitized.attributes.task_title).toBeUndefined();
    expect(sanitized.attributes.title).toBeUndefined();
    expect(sanitized.attributes.description).toBeUndefined();
    expect(sanitized.attributes.task_description).toBeUndefined();
    expect(sanitized.attributes.content).toBeUndefined();
    expect(sanitized.attributes.task_content).toBeUndefined();
    expect(sanitized.attributes.body).toBeUndefined();
    expect(sanitized.attributes.req_body).toBeUndefined();
    expect(sanitized.attributes.payload).toBeUndefined();
    expect(sanitized.attributes.raw_payload).toBeUndefined();
    expect(sanitized.attributes.raw_url).toBeUndefined();
    expect(sanitized.attributes.url).toBeUndefined();
    expect(sanitized.attributes.auth_header).toBeUndefined();
    expect(sanitized.attributes.headers).toBeUndefined();
    expect(sanitized.attributes.dump).toBeUndefined();
    expect(sanitized.attributes.prompt).toBeUndefined();
    expect(sanitized.attributes.email).toBeUndefined();
    expect(sanitized.attributes.user_email).toBeUndefined();
    expect(sanitized.attributes.phone).toBeUndefined();
    expect(sanitized.attributes.phone_number).toBeUndefined();
    expect(sanitized.attributes.token).toBeUndefined();
    expect(sanitized.attributes.api_key).toBeUndefined();
    expect(sanitized.attributes.secret).toBeUndefined();
    expect(sanitized.attributes.object_array).toBeUndefined();
    expect(sanitized.attributes.nested_object).toBeUndefined();

    // Check secret redaction in allowed string
    expect(sanitized.attributes.custom_message).not.toContain("sk-ant-12345678901234567890");
    expect(sanitized.attributes.custom_message).toContain("[REDACTED]");

    // Check scalar array <= 10 items
    expect(sanitized.attributes.allowed_task_ids).toHaveLength(3);
    expect(sanitized.attributes.allowed_task_ids[2]).toContain("[REDACTED]");
    expect(sanitized.attributes.allowed_task_ids_count).toBeUndefined();
    expect(sanitized.attributes.allowed_task_ids_truncated).toBeUndefined();

    // Check scalar array > 10 items
    expect(sanitized.attributes.long_scalar_list).toHaveLength(10);
    expect(sanitized.attributes.long_scalar_list_count).toBe(12);
    expect(sanitized.attributes.long_scalar_list_truncated).toBe(true);
  });

  it("info(marker, attributes) forwards sanitized attributes to Sentry.logger.info and adds breadcrumb", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    obs.info("todoist_poll_changed", {
      changed_task_count: 5,
      changed_task_ids: ["t1", "t2"],
      task_title: "Secret leaked title",
      auth_token: "Bearer sk-1234567890"
    });

    expect(sentryMockState.loggerInfoCalls).toHaveLength(1);
    expect(sentryMockState.loggerInfoCalls[0]?.marker).toBe("todoist_poll_changed");
    expect(sentryMockState.loggerInfoCalls[0]?.attributes).toEqual({
      changed_task_count: 5,
      changed_task_ids: ["t1", "t2"]
    });

    expect(sentryMockState.breadcrumbs).toHaveLength(1);
    expect(sentryMockState.breadcrumbs[0]).toEqual({
      category: "log.info",
      message: "todoist_poll_changed",
      data: {
        changed_task_count: 5,
        changed_task_ids: ["t1", "t2"]
      },
      level: "info"
    });
  });

  it("warn(marker, attributes) forwards sanitized attributes to Sentry.logger.warn and adds breadcrumb", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    obs.warn("todoist_poll_failed", {
      duration_ms: 150,
      consecutive_failure_count: 2,
      error_kind: "network",
      operational: true,
      description: "sensitive description"
    });

    expect(sentryMockState.loggerWarnCalls).toHaveLength(1);
    expect(sentryMockState.loggerWarnCalls[0]?.marker).toBe("todoist_poll_failed");
    expect(sentryMockState.loggerWarnCalls[0]?.attributes).toEqual({
      duration_ms: 150,
      consecutive_failure_count: 2,
      error_kind: "network",
      operational: true
    });

    expect(sentryMockState.breadcrumbs).toHaveLength(1);
    expect(sentryMockState.breadcrumbs[0]).toEqual({
      category: "log.warn",
      message: "todoist_poll_failed",
      data: {
        duration_ms: 150,
        consecutive_failure_count: 2,
        error_kind: "network",
        operational: true
      },
      level: "warning"
    });
  });

  it("info and warn handle calls without attributes", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    obs.info("simple_info_marker");
    obs.warn("simple_warn_marker");

    expect(sentryMockState.loggerInfoCalls).toHaveLength(1);
    expect(sentryMockState.loggerInfoCalls[0]).toEqual({ marker: "simple_info_marker", attributes: undefined });
    expect(sentryMockState.loggerWarnCalls).toHaveLength(1);
    expect(sentryMockState.loggerWarnCalls[0]).toEqual({ marker: "simple_warn_marker", attributes: undefined });

    expect(sentryMockState.breadcrumbs).toHaveLength(2);
    expect(sentryMockState.breadcrumbs[0]).toEqual({
      category: "log.info",
      message: "simple_info_marker",
      level: "info"
    });
    expect(sentryMockState.breadcrumbs[1]).toEqual({
      category: "log.warn",
      message: "simple_warn_marker",
      level: "warning"
    });
  });

  it("captureException(error, context) sets context tags on scope and captures exception", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const error = new Error("Corrupted database invariant");
    obs.captureException(error, {
      marker: "db_invariant_corrupted",
      taskId: "task-999",
      occurrenceKey: "task-999:2026-08-31",
      custom_detail: "details_here"
    });

    expect(sentryMockState.lastScope.level).toBe("error");
    expect(sentryMockState.lastScope.tags.marker).toBe("db_invariant_corrupted");
    expect(sentryMockState.lastScope.tags.taskId).toBe("task-999");
    expect(sentryMockState.lastScope.tags.occurrenceKey).toBe("task-999:2026-08-31");
    expect(sentryMockState.lastScope.tags.custom_detail).toBe("details_here");
    expect(sentryMockState.capturedExceptions[0]).toBe(error);
  });

  it("captureException(nonErrorString, context) captures redacted message", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    obs.captureException("Unexpected failure with Bearer secret-token-xyz", {
      marker: "unknown_error"
    });

    expect(sentryMockState.lastScope.level).toBe("error");
    expect(sentryMockState.lastScope.tags.marker).toBe("unknown_error");
    expect(sentryMockState.capturedMessages).toHaveLength(1);
    expect(sentryMockState.capturedMessages[0]).not.toContain("secret-token-xyz");
    expect(sentryMockState.capturedMessages[0]).toContain("[REDACTED]");
  });

  it("captureException handles call without context", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const error = new Error("Bare error");
    obs.captureException(error);

    expect(sentryMockState.lastScope.level).toBe("error");
    expect(sentryMockState.lastScope.tags).toEqual({});
    expect(sentryMockState.capturedExceptions[0]).toBe(error);
  });

  it("captureRequestFailure sets scope tags and captures exception", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const error = new Error("Database query failed");
    expect(obs.captureRequestFailure(error, {
      requestId: "req-xyz-1",
      method: "POST",
      route: "/tasks",
      statusCode: 500
    })).toBeUndefined();

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

    expect(obs.captureRequestFailure(new Error("Invalid request"), {
      method: "POST",
      route: "/tasks",
      statusCode: 422
    })).toBeUndefined();

    expect(sentryMockState.capturedExceptions).toHaveLength(0);
    expect(sentryMockState.capturedMessages).toHaveLength(0);
  });

  it("captureBackgroundFailure sets marker and custom tags", () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    const error = new Error("Background task failed");
    expect(obs.captureBackgroundFailure("buzer.backend.test_marker", error, {
      consecutive_failure_count: "3",
      undefined_key: undefined
    })).toBeUndefined();

    expect(sentryMockState.lastScope.level).toBe("error");
    expect(sentryMockState.lastScope.tags.marker).toBe("buzer.backend.test_marker");
    expect(sentryMockState.lastScope.tags.consecutive_failure_count).toBe("3");
    expect(sentryMockState.lastScope.tags.undefined_key).toBeUndefined();
    expect(sentryMockState.capturedExceptions[0]).toBe(error);
  });

  it("flushes Sentry with the supplied timeout", async () => {
    const obs = new SentryBackendObservability({
      dsn: "https://public@sentry.example.com/1"
    });

    expect(await obs.flush(1500)).toBeUndefined();
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
        url: "/health?auth=SECRET_AUTH_123",
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
        url: "/health?custom_secret=SECRET_SENTRY_123&safe=value",
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

  it("replaces opaque credential-like request IDs that lack blacklist keywords", async () => {
    const app = buildServer({
      db,
      currentUserId: "user-test",
      androidApiToken: "secret-token",
      todoist: new FakeTodoistAdapter(),
      observability: recordingObs
    });
    const opaqueToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvcGFxdWUtc2VjcmV0In0.signature";

    const response = await app.inject({
      method: "GET",
      url: "/tasks/snapshot",
      headers: { authorization: "Bearer secret-token", "x-request-id": opaqueToken }
    });

    expect(response.statusCode).toBe(200);
    expect(response.headers["x-request-id"]).not.toBe(opaqueToken);
    expect(response.headers["x-request-id"]).toMatch(/^[0-9a-f-]{36}$/);
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

    const customRequestId = "123e4567-e89b-42d3-a456-426614174000";
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
        "x-request-id": "123e4567-e89b-42d3-a456-426614174001"
      }
    });
    expect(errRes.statusCode).toBe(500);
    expect(recordingObs.requestFailures).toHaveLength(1);
    const failure = recordingObs.requestFailures[0];
    expect((failure?.error as Error)?.message).toBe("Unexpected database explosion");
    expect(failure?.context).toMatchObject({
      requestId: "123e4567-e89b-42d3-a456-426614174001",
      method: "GET",
      route: "/test/unhandled-error",
      statusCode: 500
    });
  });
});
