import * as Sentry from "@sentry/node";
import { isCredentialKey, redactObject, redactString } from "./redaction.js";

export interface RequestFailureContext {
  requestId?: string;
  method?: string;
  route?: string;
  statusCode?: number;
}

export type SafeLogAttributes = Record<string, unknown>;

export interface InternalErrorContext extends Record<string, unknown> {
  marker?: string;
  operation?: string;
  requestId?: string;
  taskId?: string;
  occurrenceKey?: string;
}

export interface BackendObservability {
  info(marker: string, attributes?: SafeLogAttributes): void;
  warn(marker: string, attributes?: SafeLogAttributes): void;
  captureException(error: unknown, context?: InternalErrorContext | Record<string, unknown>): void;
  captureRequestFailure(error: unknown, context: RequestFailureContext): void;
  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): void;
  flush(timeoutMillis?: number): Promise<void>;
}

export const MAX_LOG_ARRAY_LENGTH = 10;

const FORBIDDEN_NORMALIZED_EXACT_KEYS = new Set([
  "title",
  "tasktitle",
  "projecttitle",
  "description",
  "taskdescription",
  "content",
  "taskcontent",
  "bodycontent",
  "summary",
  "tasksummary",
  "text",
  "prompt",
  "header",
  "headers",
  "reqheaders",
  "resheaders",
  "requestheaders",
  "responseheaders",
  "body",
  "rawbody",
  "reqbody",
  "resbody",
  "requestbody",
  "responsebody",
  "payload",
  "rawpayload",
  "todoistpayload",
  "taskdump",
  "dump",
  "task",
  "tasks",
  "project",
  "rawurl",
  "url",
  "uri",
  "href",
  "email",
  "useremail",
  "phone",
  "phonenumber"
]);

export function isForbiddenLogAttributeKey(rawKey: string): boolean {
  if (isCredentialKey(rawKey)) return true;
  const normalized = rawKey.toLowerCase().replace(/[-_]/g, "");
  if (FORBIDDEN_NORMALIZED_EXACT_KEYS.has(normalized)) return true;
  if (
    normalized.endsWith("title") ||
    normalized.endsWith("description") ||
    normalized.endsWith("content") ||
    normalized.endsWith("payload") ||
    normalized.endsWith("body") ||
    normalized.endsWith("headers") ||
    normalized.endsWith("header") ||
    normalized.endsWith("dump") ||
    normalized.endsWith("rawurl") ||
    normalized.endsWith("url") ||
    normalized.endsWith("uri") ||
    normalized.endsWith("prompt") ||
    normalized.endsWith("email") ||
    normalized.endsWith("phone")
  ) {
    return true;
  }
  return false;
}

export function sanitizeLogAttributes(attributes: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(attributes)) {
    if (isForbiddenLogAttributeKey(key)) {
      continue;
    }
    if (val === undefined) {
      continue;
    }
    if (val === null) {
      result[key] = null;
      continue;
    }
    if (typeof val === "number" || typeof val === "boolean" || typeof val === "bigint") {
      result[key] = val;
      continue;
    }
    if (typeof val === "string") {
      result[key] = redactString(val);
      continue;
    }
    if (Array.isArray(val)) {
      const allScalar = val.every(
        (item) => item === null || item === undefined || typeof item === "string" || typeof item === "number" || typeof item === "boolean"
      );
      if (!allScalar) {
        continue;
      }
      if (val.length > MAX_LOG_ARRAY_LENGTH) {
        result[key] = val.slice(0, MAX_LOG_ARRAY_LENGTH).map((item) => (typeof item === "string" ? redactString(item) : item));
        result[`${key}_count`] = val.length;
        result[`${key}_truncated`] = true;
      } else {
        result[key] = val.map((item) => (typeof item === "string" ? redactString(item) : item));
      }
      continue;
    }
  }
  return result;
}

export function sanitizeLog(log: Sentry.Log): Sentry.Log {
  const message = typeof log.message === "string" ? redactString(log.message) : log.message;
  const attributes = log.attributes ? sanitizeLogAttributes(log.attributes) : undefined;
  return {
    ...log,
    message,
    ...(attributes !== undefined ? { attributes } : {})
  };
}

export class NoOpBackendObservability implements BackendObservability {
  captureRequestFailure(_error: unknown, _context: RequestFailureContext): void {}

  captureBackgroundFailure(_marker: string, _error: unknown, _tags?: Record<string, string | undefined>): void {}

  async flush(_timeoutMillis: number = 2000): Promise<void> {}

  info(_marker: string, _attributes?: SafeLogAttributes): void {}

  warn(_marker: string, _attributes?: SafeLogAttributes): void {}

  captureException(_error: unknown, _context?: InternalErrorContext | Record<string, unknown>): void {}
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
      release: options.release ?? "app.buzer@0.1.0+local",
      sendDefaultPii: false,
      enableLogs: true,
      beforeSendLog: (log) => sanitizeLog(log),
      beforeSend: (event) => {
        delete (event as { request?: unknown }).request;
        delete (event as { user?: unknown }).user;
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
        if (event.breadcrumbs) {
          for (const breadcrumb of event.breadcrumbs) {
            if (breadcrumb.message) {
              breadcrumb.message = redactString(breadcrumb.message);
            }
            if (breadcrumb.data) {
              breadcrumb.data = redactObject(breadcrumb.data);
            }
          }
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

  captureRequestFailure(error: unknown, context: RequestFailureContext): void {
    if (!this.enabled) return;
    if (context.statusCode !== undefined && context.statusCode >= 400 && context.statusCode < 500) {
      return;
    }
    Sentry.withScope((scope) => {
      scope.setLevel("error");
      if (context.requestId) {
        scope.setTag("request_id", context.requestId);
      }
      if (context.method) {
        scope.setTag("method", context.method);
      }
      if (context.route) {
        scope.setTag("route", context.route);
      }
      if (context.statusCode !== undefined) {
        scope.setTag("status_code", String(context.statusCode));
      }
      if (error instanceof Error) {
        Sentry.captureException(error);
      } else {
        Sentry.captureMessage(redactString(String(error)));
      }
    });
  }

  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): void {
    if (!this.enabled) return;
    Sentry.withScope((scope) => {
      scope.setLevel("error");
      scope.setTag("marker", marker);
      if (tags) {
        for (const [key, value] of Object.entries(tags)) {
          if (value !== undefined) {
            scope.setTag(key, value);
          }
        }
      }
      if (error instanceof Error) {
        Sentry.captureException(error);
      } else {
        Sentry.captureMessage(redactString(String(error)));
      }
    });
  }

  info(marker: string, attributes?: SafeLogAttributes): void {
    if (!this.enabled) return;
    const safeAttrs = attributes !== undefined ? sanitizeLogAttributes(attributes) : undefined;
    if (typeof Sentry.logger?.info === "function") {
      Sentry.logger.info(marker, safeAttrs);
    }
    Sentry.addBreadcrumb({
      category: "log.info",
      message: marker,
      ...(safeAttrs !== undefined ? { data: safeAttrs } : {}),
      level: "info"
    });
  }

  warn(marker: string, attributes?: SafeLogAttributes): void {
    if (!this.enabled) return;
    const safeAttrs = attributes !== undefined ? sanitizeLogAttributes(attributes) : undefined;
    if (typeof Sentry.logger?.warn === "function") {
      Sentry.logger.warn(marker, safeAttrs);
    }
    Sentry.addBreadcrumb({
      category: "log.warn",
      message: marker,
      ...(safeAttrs !== undefined ? { data: safeAttrs } : {}),
      level: "warning"
    });
  }

  captureException(error: unknown, context?: InternalErrorContext | Record<string, unknown>): void {
    if (!this.enabled) return;
    Sentry.withScope((scope) => {
      scope.setLevel("error");
      if (context) {
        for (const [key, value] of Object.entries(context)) {
          if (value !== undefined && !isForbiddenLogAttributeKey(key)) {
            scope.setTag(key, String(value));
          }
        }
      }
      if (error instanceof Error) {
        Sentry.captureException(error);
      } else {
        Sentry.captureMessage(redactString(String(error)));
      }
    });
  }

  async flush(timeoutMillis: number = 2000): Promise<void> {
    if (this.enabled) {
      await Sentry.flush(timeoutMillis);
    }
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
        release: release ?? "app.buzer@0.1.0+local"
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
  captureRequestFailure(error: unknown, context: RequestFailureContext): void {
    backendObservabilityRuntime.getInstance().captureRequestFailure(error, context);
  },
  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): void {
    backendObservabilityRuntime.getInstance().captureBackgroundFailure(marker, error, tags);
  },
  flush(timeoutMillis?: number): Promise<void> {
    return backendObservabilityRuntime.getInstance().flush(timeoutMillis);
  },
  info(marker: string, attributes?: SafeLogAttributes): void {
    backendObservabilityRuntime.getInstance().info(marker, attributes);
  },
  warn(marker: string, attributes?: SafeLogAttributes): void {
    backendObservabilityRuntime.getInstance().warn(marker, attributes);
  },
  captureException(error: unknown, context?: InternalErrorContext | Record<string, unknown>): void {
    backendObservabilityRuntime.getInstance().captureException(error, context);
  }
};
