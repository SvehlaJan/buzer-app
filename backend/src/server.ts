import type Database from "better-sqlite3";
import * as crypto from "node:crypto";
import "dotenv/config";
import { DryRunSender } from "./accountability/dryRunSender.js";
import { WhatsAppWebSender } from "./accountability/whatsappWebSender.js";
import Fastify from "fastify";
import type { FastifyError, FastifyRequest } from "fastify";
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
  backendObservabilityRuntime
} from "./diagnostics/backendObservability.js";
import { redactString, redactUrl } from "./diagnostics/redaction.js";

export interface BuildServerOptions {
  db: Database.Database;
  currentUserId: string;
  androidApiToken: string;
  todoist: TodoistAdapter;
  synchronizer?: TodoistSyncService | undefined;
  escalationService?: EscalationService | null | undefined;
  rateLimit?: { max: number; timeWindowMs: number } | undefined;
  observability?: BackendObservability | undefined;
  logger?: boolean | undefined;
}

export function buildServer(options: BuildServerOptions) {
  const observability = options.observability ?? backendObservability;

  const app = Fastify({
    logger: options.logger === false ? false : {
      hooks: {
        logMethod(args, method) {
          for (let index = 0; index < args.length; index += 1) {
            const arg = args[index];
            if (typeof arg === "string") {
              args[index] = redactString(arg);
            }
          }
          return method.apply(this, args);
        }
      },
      serializers: {
        req: (request: FastifyRequest) => ({
          method: request.method,
          url: redactUrl(request.url)
        }),
        res: (reply) => ({ statusCode: reply.statusCode }),
        err: (error: FastifyError) => ({
          type: error.name,
          message: redactString(error.message),
          stack: ""
        })
      }
    },
    genReqId: (req) => {
      const incoming = (req.headers["x-request-id"] ?? req.headers["request-id"]) as string | undefined;
      return incoming && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(incoming)
        ? incoming
        : crypto.randomUUID();
    },
    // Trust only loopback and the compose network (pinned to 172.28.0.0/16 in
    // docker-compose.yml) so X-Forwarded-For set by tailscaled is honored while
    // direct tailnet peers cannot spoof it.
    trustProxy: ["127.0.0.0/8", "172.28.0.0/16"]
  });

  app.addHook("onRequest", async (request, reply) => {
    reply.header("x-request-id", request.id);
  });

  app.addHook("onError", async (request, reply, error) => {
    const errStatusCode = (error as { statusCode?: number }).statusCode;
    const isClientError =
      error.name === "ZodError" ||
      (error as { code?: string }).code === "FST_ERR_VALIDATION" ||
      (typeof errStatusCode === "number" && errStatusCode >= 400 && errStatusCode < 500) ||
      (reply.statusCode >= 400 && reply.statusCode < 500);

    if (!isClientError) {
      const statusCode =
        reply.statusCode >= 500
          ? reply.statusCode
          : typeof errStatusCode === "number" && errStatusCode >= 500
          ? errStatusCode
          : 500;

      observability.captureRequestFailure(error, {
        requestId: request.id,
        method: request.method,
        route: request.routeOptions?.url ?? request.url,
        statusCode
      });
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
    registerRoutes(scope, options, { actions, snapshots, collaborators, synchronizer });
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


  const db = openDatabase(dbPath);
  const tasks = new TaskRepository(db);
  const projects = new ProjectRepository(db);
  const collaborators = new CollaboratorRepository(db);
  const todoist = new HttpTodoistAdapter(todoistApiToken);
  const whatsappSender = CONFIG.WHATSAPP_DRY_RUN
    ? new DryRunSender()
    : new WhatsAppWebSender({
      sessionPath: CONFIG.WHATSAPP_SESSION_PATH,
      clientId: CONFIG.WHATSAPP_CLIENT_ID,
      observability: backendObservability
    });
  if (whatsappSender instanceof WhatsAppWebSender) {
    await whatsappSender.initialize();
  }
  const escalationService = new EscalationService(
    db,
    tasks,
    whatsappSender,
    whatsappRecipientId,
    { observability: backendObservability }
  );
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
      observability: backendObservability,
      onError: (error) => {
        app.log.error({ err: error }, "Todoist sync failed");
      }
    }
  );
  app.addHook("onClose", async () => {
    poller.stop();
    try {
      if (whatsappSender instanceof WhatsAppWebSender) {
        await whatsappSender.destroy();
      }
    } finally {
      await backendObservability.flush(2000);
    }
  });

  const handleSignal = async () => {
    try {
      await app.close();
    } finally {
      process.exit(0);
    }
  };

  process.on("SIGTERM", () => void handleSignal());
  process.on("SIGINT", () => void handleSignal());

  poller.start();
  await app.listen({ host: "0.0.0.0", port });
}
