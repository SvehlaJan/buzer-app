import * as Sentry from "@sentry/node";
import { redactObject, redactString } from "./redaction.js";

export interface RequestFailureContext {
  requestId?: string;
  method?: string;
  route?: string;
  statusCode?: number;
}

export interface BackendObservability {
  isEnabled(): boolean;
  addBreadcrumb(category: string, message: string, data?: Record<string, unknown>): void;
  captureRequestFailure(error: unknown, context: RequestFailureContext): string | undefined;
  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): string | undefined;
  flush(timeoutMillis?: number): Promise<boolean>;
}

export class NoOpBackendObservability implements BackendObservability {
  isEnabled(): boolean {
    return false;
  }

  addBreadcrumb(_category: string, _message: string, _data?: Record<string, unknown>): void {}

  captureRequestFailure(_error: unknown, _context: RequestFailureContext): string | undefined {
    return undefined;
  }

  captureBackgroundFailure(_marker: string, _error: unknown, _tags?: Record<string, string | undefined>): string | undefined {
    return undefined;
  }

  async flush(_timeoutMillis: number = 2000): Promise<boolean> {
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

  isEnabled(): boolean {
    return this.enabled;
  }

  addBreadcrumb(category: string, message: string, data?: Record<string, unknown>): void {
    if (!this.enabled) return;
    const sanitizedMessage = redactString(message);
    const sanitizedData = data !== undefined ? redactObject(data) : undefined;
    Sentry.addBreadcrumb({
      category,
      message: sanitizedMessage,
      ...(sanitizedData !== undefined ? { data: sanitizedData as Record<string, any> } : {}),
      level: "info"
    });
  }

  captureRequestFailure(error: unknown, context: RequestFailureContext): string | undefined {
    if (!this.enabled) return undefined;
    let eventId: string | undefined;
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
        eventId = Sentry.captureException(error);
      } else {
        eventId = Sentry.captureMessage(redactString(String(error)));
      }
    });
    return eventId ? eventId.replace(/-/g, "").toLowerCase() : undefined;
  }

  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): string | undefined {
    if (!this.enabled) return undefined;
    let eventId: string | undefined;
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
        eventId = Sentry.captureException(error);
      } else {
        eventId = Sentry.captureMessage(redactString(String(error)));
      }
    });
    return eventId ? eventId.replace(/-/g, "").toLowerCase() : undefined;
  }

  async flush(timeoutMillis: number = 2000): Promise<boolean> {
    if (!this.enabled) return true;
    return Sentry.flush(timeoutMillis);
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
  isEnabled(): boolean {
    return backendObservabilityRuntime.getInstance().isEnabled();
  },
  addBreadcrumb(category: string, message: string, data?: Record<string, unknown>): void {
    backendObservabilityRuntime.getInstance().addBreadcrumb(category, message, data);
  },
  captureRequestFailure(error: unknown, context: RequestFailureContext): string | undefined {
    return backendObservabilityRuntime.getInstance().captureRequestFailure(error, context);
  },
  captureBackgroundFailure(marker: string, error: unknown, tags?: Record<string, string | undefined>): string | undefined {
    return backendObservabilityRuntime.getInstance().captureBackgroundFailure(marker, error, tags);
  },
  flush(timeoutMillis?: number): Promise<boolean> {
    return backendObservabilityRuntime.getInstance().flush(timeoutMillis);
  }
};
