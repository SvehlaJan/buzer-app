import type Database from "better-sqlite3";
import * as crypto from "node:crypto";
import "dotenv/config";
import { DryRunSender } from "./accountability/dryRunSender.js";
import { WhatsAppWebSender } from "./accountability/whatsappWebSender.js";
import Fastify from "fastify";
import rateLimit from "@fastify/rate-limit";
import { openDatabase } from "./db/connection.js";
import { registerRoutes } from "./http/routes.js";
import { CollaboratorRepository } from "./repositories/collaboratorRepository.js";
import { ProjectRepository } from "./repositories/projectRepository.js";
import { TaskRepository } from "./repositories/taskRepository.js";
import { EscalationService } from "./services/escalationService.js";
import { TaskActionCoordinator } from "./services/taskActionCoordinator.js";
import { TaskActionService } from "./services/taskActionService.js";
import { TaskSnapshotService } from "./services/taskSnapshotService.js";
import { TodoistPoller } from "./services/todoistPoller.js";
import { TodoistSyncService } from "./services/todoistSyncService.js";
import { HttpTodoistAdapter } from "./todoist/httpTodoistAdapter.js";
import type { TodoistAdapter } from "./todoist/todoistAdapter.js";
import { CONFIG } from "./config.js";
import {
  type BackendObservability,
  backendObservability,
  backendObservabilityRuntime,
  type DiagnosticCorrelation
} from "./diagnostics/backendObservability.js";
import { DiagnosticReportService } from "./diagnostics/diagnosticReportService.js";
import { backendDiagnosticRingBuffer, backendLogRingBuffer, truncateUtf8 } from "./diagnostics/ringBuffer.js";
import { redactString } from "./diagnostics/redaction.js";

export interface BuildServerOptions {
  db: Database.Database;
  currentUserId: string;
  androidApiToken: string;
  todoist: TodoistAdapter;
  synchronizer?: TodoistSyncService | undefined;
  escalationService?: EscalationService | null | undefined;
  rateLimit?: { max: number; timeWindowMs: number } | undefined;
  observability?: BackendObservability | undefined;
}

export function buildServer(options: BuildServerOptions) {
  const observability = options.observability ?? backendObservability;

  const app = Fastify({
    logger: false,
    requestIdHeader: "x-request-id",
    genReqId: (req) => {
      const incoming = (req.headers["x-request-id"] ?? req.headers["request-id"]) as string | undefined;
      return incoming && incoming.trim() !== "" ? incoming : crypto.randomUUID();
    },
    // Trust only loopback and the compose network (pinned to 172.28.0.0/16 in
    // docker-compose.yml) so X-Forwarded-For set by tailscaled is honored while
    // direct tailnet peers cannot spoof it.
    trustProxy: ["127.0.0.0/8", "172.28.0.0/16"]
  });

  app.addHook("onRequest", async (request, reply) => {
    const requestId = request.id;
    reply.header("x-request-id", requestId);
    (request as unknown as { startTime: number }).startTime = Date.now();

    const sentryTrace = (request.headers["sentry-trace"] as string | undefined) ?? undefined;
    const baggage = (request.headers["baggage"] as string | undefined) ?? undefined;

    const correlation: DiagnosticCorrelation = {
      requestId,
      ...(sentryTrace !== undefined ? { traceId: sentryTrace } : {}),
      ...(baggage !== undefined ? { baggage } : {})
    };
    (request as unknown as { diagnosticCorrelation: DiagnosticCorrelation }).diagnosticCorrelation = correlation;
    observability.setContext(correlation);

    const sanitizedUrl = redactString(request.raw.url ?? request.url);
    backendLogRingBuffer.add(`[${new Date().toISOString()}] REQ ${request.method} ${sanitizedUrl} req_id=${requestId}`);
  });

  app.addHook("preHandler", async (request) => {
    let sanitizedBody: string | undefined;
    if (request.body !== undefined && request.body !== null) {
      const rawBody = typeof request.body === "string" ? request.body : JSON.stringify(request.body);
      sanitizedBody = truncateUtf8(redactString(rawBody), 65536);
      (request as unknown as { sanitizedBody?: string }).sanitizedBody = sanitizedBody;
    }

    const params = request.params as Record<string, unknown> | undefined;
    const body = typeof request.body === "object" && request.body !== null ? (request.body as Record<string, unknown>) : undefined;
    const taskId = params?.taskId ?? params?.id ?? body?.taskId ?? body?.id;
    const actionId = params?.actionId ?? body?.actionId;
    const deviceId = (request.headers["x-device-id"] as string | undefined) ?? (body?.deviceId as string | undefined);
    const occurrenceKey = (params?.occurrenceKey as string | undefined) ?? (body?.occurrenceKey as string | undefined);

    const prev = (request as unknown as { diagnosticCorrelation?: DiagnosticCorrelation }).diagnosticCorrelation;
    const correlation: DiagnosticCorrelation = {
      ...prev,
      ...(taskId !== undefined ? { taskId: String(taskId) } : {}),
      ...(actionId !== undefined ? { actionId: String(actionId) } : {}),
      ...(deviceId !== undefined ? { deviceId: String(deviceId) } : {}),
      ...(occurrenceKey !== undefined ? { occurrenceKey: String(occurrenceKey) } : {})
    };
    (request as unknown as { diagnosticCorrelation: DiagnosticCorrelation }).diagnosticCorrelation = correlation;
    observability.setContext(correlation);
  });

  app.addHook("onResponse", async (request, reply) => {
    const startTime = (request as unknown as { startTime?: number }).startTime;
    const durationMs = startTime !== undefined ? Date.now() - startTime : 0;
    const sanitizedUrl = redactString(request.raw.url ?? request.url);
    const requestId = request.id;
    const correlation = (request as unknown as { diagnosticCorrelation?: DiagnosticCorrelation }).diagnosticCorrelation;
    const sanitizedBody = (request as unknown as { sanitizedBody?: string }).sanitizedBody;

    backendDiagnosticRingBuffer.add({
      timestamp: new Date().toISOString(),
      requestId,
      method: request.method,
      url: sanitizedUrl,
      statusCode: reply.statusCode,
      durationMs,
      requestBody: sanitizedBody,
      correlation
    });

    backendLogRingBuffer.add(
      `[${new Date().toISOString()}] RES ${request.method} ${sanitizedUrl} ${reply.statusCode} ${durationMs}ms req_id=${requestId}`
    );
  });

  app.addHook("onError", async (request, reply, error) => {
    const errStatusCode = (error as { statusCode?: number }).statusCode;
    const isClientError =
      error.name === "ZodError" ||
      (error as { code?: string }).code === "FST_ERR_VALIDATION" ||
      (typeof errStatusCode === "number" && errStatusCode >= 400 && errStatusCode < 500) ||
      (reply.statusCode >= 400 && reply.statusCode < 500);

    const correlation = (request as unknown as { diagnosticCorrelation?: DiagnosticCorrelation }).diagnosticCorrelation;

    if (!isClientError) {
      observability.captureHandled("fastify_unhandled_error", error, correlation);
    } else {
      const code = typeof errStatusCode === "number" ? errStatusCode : (reply.statusCode >= 400 ? reply.statusCode : 400);
      backendLogRingBuffer.add(
        `[${new Date().toISOString()}] WARN ${request.method} ${redactString(request.raw.url ?? request.url)} client_error=${code} ${redactString(error.message)} req_id=${request.id}`
      );
    }
  });

  app.register(rateLimit, {
    max: options.rateLimit?.max ?? 60,
    timeWindow: options.rateLimit?.timeWindowMs ?? 60_000
  });
  const tasks = new TaskRepository(options.db);
  const projects = new ProjectRepository(options.db);
  const collaborators = new CollaboratorRepository(options.db);
  const snapshots = new TaskSnapshotService(tasks, projects, options.currentUserId, true);
  const synchronizer = options.synchronizer ?? new TodoistSyncService(
    options.db, tasks, projects, options.todoist, options.currentUserId, collaborators
  );
  const diagnostics = new DiagnosticReportService(
    options.db,
    observability,
    synchronizer,
    options.escalationService
  );
  const coordinator = new TaskActionCoordinator({ db: options.db, roots: tasks, sync: synchronizer });
  const actions = new TaskActionService(
    options.db,
    tasks,
    projects,
    options.todoist,
    options.currentUserId,
    options.escalationService ?? null,
    true,
    {
      rootActions: {
        coordinator,
        synchronize: () => synchronizer.syncOnce(),
        snapshot: () => snapshots.getSnapshot(new Date().toISOString())
      },
      checklist: {
        coordinator,
        synchronize: () => synchronizer.syncOnce(),
        snapshot: () => snapshots.getSnapshot(new Date().toISOString())
      }
    }
  );
  app.register(async (scope) => {
    registerRoutes(scope, options, { actions, snapshots, collaborators, synchronizer, diagnostics });
  });
  app.after(() => {
    app.setNotFoundHandler({ preHandler: app.rateLimit() }, (_request, reply) => {
      reply.status(404).send({ code: "not_found", message: "Not Found" });
    });
  });
  return app;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  backendObservabilityRuntime.initialize(
    CONFIG.SENTRY_BACKEND_DSN,
    CONFIG.SENTRY_ENVIRONMENT,
    CONFIG.SENTRY_RELEASE
  );

  const dbPath = CONFIG.DATABASE_PATH;
  const currentUserId = CONFIG.TODOIST_USER_ID;
  const androidApiToken = CONFIG.ANDROID_API_TOKEN;
  const todoistApiToken = CONFIG.TODOIST_API_TOKEN;
  const whatsappRecipientId = CONFIG.WHATSAPP_RECIPIENT_ID;
  const port = CONFIG.PORT;

  if (
    !currentUserId ||
    !androidApiToken ||
    !todoistApiToken ||
    !whatsappRecipientId
  ) {
    throw new Error("TODOIST_USER_ID, ANDROID_API_TOKEN, TODOIST_API_TOKEN, and WHATSAPP_RECIPIENT_ID are required");
  }

  process.on("unhandledRejection", async (reason) => {
    backendObservability.captureFatal("unhandled_rejection", reason);
    try {
      await backendObservability.flush(2000);
    } finally {
      process.exit(1);
    }
  });

  process.on("uncaughtException", async (error) => {
    backendObservability.captureFatal("uncaught_exception", error);
    try {
      await backendObservability.flush(2000);
    } finally {
      process.exit(1);
    }
  });

  const db = openDatabase(dbPath);
  const tasks = new TaskRepository(db);
  const projects = new ProjectRepository(db);
  const collaborators = new CollaboratorRepository(db);
  const todoist = new HttpTodoistAdapter(todoistApiToken);
  const whatsappSender = CONFIG.WHATSAPP_DRY_RUN
    ? new DryRunSender()
    : new WhatsAppWebSender({
      sessionPath: CONFIG.WHATSAPP_SESSION_PATH,
      clientId: CONFIG.WHATSAPP_CLIENT_ID
    });
  if (whatsappSender instanceof WhatsAppWebSender) {
    await whatsappSender.initialize();
  }
  const escalationService = new EscalationService(db, tasks, whatsappSender, whatsappRecipientId);
  const synchronizer = new TodoistSyncService(db, tasks, projects, todoist, currentUserId, collaborators);
  const app = buildServer({
    db,
    currentUserId,
    androidApiToken,
    todoist,
    escalationService,
    synchronizer,
    observability: backendObservability
  });
  const poller = new TodoistPoller(
    synchronizer,
    {
      runImmediately: true,
      onError: (error) => {
        backendObservability.captureHandled("todoist_poller_error", error);
        app.log.error({ err: error }, "Todoist sync failed");
      }
    }
  );
  app.addHook("onClose", async () => {
    poller.stop();
    if (whatsappSender instanceof WhatsAppWebSender) {
      await whatsappSender.destroy();
    }
    await backendObservability.flush(2000);
  });

  const handleSignal = async () => {
    try {
      await app.close();
      await backendObservability.flush(2000);
    } finally {
      process.exit(0);
    }
  };

  process.on("SIGTERM", () => void handleSignal());
  process.on("SIGINT", () => void handleSignal());

  poller.start();
  await app.listen({ host: "0.0.0.0", port });
}
