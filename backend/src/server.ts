import type Database from "better-sqlite3";
import * as crypto from "node:crypto";
import "dotenv/config";
import type { AccountabilitySender } from "./accountability/accountabilitySender.js";
import { DryRunSender } from "./accountability/dryRunSender.js";
import { WhatsAppWebSender } from "./accountability/whatsappWebSender.js";
import Fastify from "fastify";
import type { FastifyError, FastifyRequest } from "fastify";
import rateLimit from "@fastify/rate-limit";
import { openDatabase } from "./db/connection.js";
import { resolveOwnerTimeZone } from "./domain/ownerTimeZone.js";
import { registerRoutes } from "./http/routes.js";
import { CollaboratorRepository } from "./repositories/collaboratorRepository.js";
import { DeviceRepository } from "./repositories/deviceRepository.js";
import { ProjectRepository } from "./repositories/projectRepository.js";
import { TaskRepository } from "./repositories/taskRepository.js";
import { EscalationService } from "./services/escalationService.js";
import { TaskActionService } from "./services/taskActionService.js";
import { TaskSnapshotService } from "./services/taskSnapshotService.js";
import { TodoistPoller } from "./services/todoistPoller.js";
import { createTodoistRuntime, type TodoistRuntime } from "./services/todoistRuntime.js";
import { HttpTodoistAdapter } from "./todoist/httpTodoistAdapter.js";
import type { TodoistAdapter } from "./todoist/todoistAdapter.js";
import { CONFIG } from "./config.js";
import {
  type BackendObservability,
  backendObservability
} from "./diagnostics/backendObservability.js";
import { redactString, redactUrl } from "./diagnostics/redaction.js";

export interface CreateBackendCoreOptions {
  db: Database.Database;
  currentUserId: string;
  todoist: TodoistAdapter;
  todoistRuntime?: TodoistRuntime | undefined;
  whatsapp?: { sender: AccountabilitySender; recipientId: string } | undefined;
  escalationService?: EscalationService | null | undefined;
  observability?: BackendObservability | undefined;
}

export interface BackendCore {
  tasks: TaskRepository;
  projects: ProjectRepository;
  collaborators: CollaboratorRepository;
  devices: DeviceRepository;
  snapshots: TaskSnapshotService;
  escalationService: EscalationService | null;
  todoistRuntime: TodoistRuntime;
  actions: TaskActionService;
}

export function createBackendCore(options: CreateBackendCoreOptions): BackendCore {
  const observability = options.observability ?? backendObservability;
  const tasks = new TaskRepository(options.db);
  const projects = new ProjectRepository(options.db);
  const collaborators = new CollaboratorRepository(options.db);
  const devices = new DeviceRepository(options.db);
  const snapshots = new TaskSnapshotService(tasks, projects, options.currentUserId, true, collaborators);
  const escalationService =
    options.escalationService !== undefined
      ? options.escalationService
      : options.whatsapp !== undefined
        ? new EscalationService(
            options.db,
            tasks,
            options.whatsapp.sender,
            options.whatsapp.recipientId,
            {
              observability,
              ownerTimeZone: () => resolveOwnerTimeZone(collaborators, options.currentUserId)
            }
          )
        : null;
  const todoistRuntime =
    options.todoistRuntime ??
    createTodoistRuntime({
      db: options.db,
      tasks,
      projects,
      collaborators,
      todoist: options.todoist,
      snapshots,
      currentUserId: options.currentUserId,
      observability
    });
  const actions = new TaskActionService({
    tasks,
    projects,
    collaborators,
    snapshots,
    runtime: todoistRuntime,
    escalationService,
    currentUserId: options.currentUserId,
    requireMirrorReady: true
  });

  return {
    tasks,
    projects,
    collaborators,
    devices,
    snapshots,
    escalationService,
    todoistRuntime,
    actions
  };
}

export interface BuildServerOptions extends CreateBackendCoreOptions {
  androidApiToken: string;
  core?: BackendCore | undefined;
  rateLimit?: { max: number; timeWindowMs: number } | undefined;
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
    const errCode = (error as { code?: string }).code;
    const isClientError =
      error.name === "ZodError" ||
      errCode === "FST_ERR_VALIDATION" ||
      (typeof errStatusCode === "number" && errStatusCode >= 400 && errStatusCode < 500) ||
      (reply.statusCode >= 400 && reply.statusCode < 500);

    const isOperationalError =
      errCode === "backend_busy" ||
      errCode === "todoist_command_rejected" ||
      errCode === "todoist_delivery_unknown" ||
      errCode === "todoist_accepted_snapshot_stale" ||
      errCode === "forced_refresh_failed" ||
      errCode === "sync_initializing" ||
      errCode === "collaborators_unavailable";

    if (!isClientError && !isOperationalError) {
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

  const core = options.core ?? createBackendCore(options);
  const routeOptions: BuildServerOptions = {
    ...options,
    escalationService: core.escalationService
  };

  app.register(async (scope) => {
    registerRoutes(scope, routeOptions, {
      snapshots: core.snapshots,
      todoistRuntime: core.todoistRuntime,
      actions: core.actions,
      projects: core.projects,
      collaborators: core.collaborators,
      devices: core.devices
    });
  });
  app.after(() => {
    app.setNotFoundHandler({ preHandler: app.rateLimit() }, (_request, reply) => {
      reply.status(404).send({ code: "not_found", message: "Not Found" });
    });
  });
  return app;
}

if (import.meta.url === `file://${process.argv[1]}`) {
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
  const core = createBackendCore({
    db,
    currentUserId,
    todoist,
    whatsapp: { sender: whatsappSender, recipientId: whatsappRecipientId },
    observability: backendObservability
  });
  const app = buildServer({
    db,
    currentUserId,
    androidApiToken,
    todoist,
    core,
    observability: backendObservability
  });
  const poller = new TodoistPoller(core.todoistRuntime, {
    runImmediately: true,
    observability: backendObservability,
    onError: (error) => {
      app.log.error({ err: error }, "Todoist sync failed");
    }
  });
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
