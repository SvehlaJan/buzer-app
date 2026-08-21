import { CONFIG } from "./config.js";
import { backendObservabilityRuntime } from "./diagnostics/backendObservability.js";

backendObservabilityRuntime.initialize(
  CONFIG.SENTRY_BACKEND_DSN,
  CONFIG.SENTRY_ENVIRONMENT,
  CONFIG.SENTRY_RELEASE
);
