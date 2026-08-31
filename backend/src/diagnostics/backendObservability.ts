import * as Sentry from "@sentry/node";
import { redactObject, redactString } from "./redaction.js";

export interface RequestFailureContext {
  requestId?: string;
  method?: string;
  route?: string;
  statusCode?: number;
}

export type SafeLogAttributes = Record<string, unknown>;

export interface BackendObservability {
  captureRequestFailure(error: unknown, context: RequestFailureContext): void;
  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): void;
  flush(timeoutMillis?: number): Promise<void>;
  info?(marker: string, attributes?: SafeLogAttributes): void;
  warn?(marker: string, attributes?: SafeLogAttributes): void;
  captureException?(error: unknown, context?: Record<string, unknown>): void;
}

export class NoOpBackendObservability implements BackendObservability {
  captureRequestFailure(_error: unknown, _context: RequestFailureContext): void {}

  captureBackgroundFailure(_marker: string, _error: unknown, _tags?: Record<string, string | undefined>): void {}

  async flush(_timeoutMillis: number = 2000): Promise<void> {}

  info(_marker: string, _attributes?: SafeLogAttributes): void {}

  warn(_marker: string, _attributes?: SafeLogAttributes): void {}

  captureException(_error: unknown, _context?: Record<string, unknown>): void {}
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
    Sentry.addBreadcrumb({
      category: "log.info",
      message: marker,
      ...(attributes !== undefined ? { data: redactObject(attributes) as Record<string, unknown> } : {}),
      level: "info"
    });
  }

  warn(marker: string, attributes?: SafeLogAttributes): void {
    if (!this.enabled) return;
    Sentry.addBreadcrumb({
      category: "log.warn",
      message: marker,
      ...(attributes !== undefined ? { data: redactObject(attributes) as Record<string, unknown> } : {}),
      level: "warning"
    });
  }

  captureException(error: unknown, context?: Record<string, unknown>): void {
    if (!this.enabled) return;
    Sentry.withScope((scope) => {
      scope.setLevel("error");
      if (context) {
        for (const [key, value] of Object.entries(context)) {
          if (value !== undefined) {
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
    backendObservabilityRuntime.getInstance().info?.(marker, attributes);
  },
  warn(marker: string, attributes?: SafeLogAttributes): void {
    backendObservabilityRuntime.getInstance().warn?.(marker, attributes);
  },
  captureException(error: unknown, context?: Record<string, unknown>): void {
    const inst = backendObservabilityRuntime.getInstance();
    if (inst.captureException) {
      inst.captureException(error, context);
    } else {
      inst.captureBackgroundFailure("unhandled_exception", error, context as Record<string, string | undefined>);
    }
  }
};
