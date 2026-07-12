import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { DeviceRepository } from "../repositories/deviceRepository.js";
import { ProjectRepository } from "../repositories/projectRepository.js";
import { TaskRepository } from "../repositories/taskRepository.js";
import { TaskActionService } from "../services/taskActionService.js";
import { TaskSnapshotService } from "../services/taskSnapshotService.js";
import type { BuildServerOptions } from "../server.js";
import { requireBearerToken } from "./auth.js";

const taskIdParams = z.object({ id: z.string().min(1) });
const nullableString = z.string().min(1).nullish().transform((value) => value ?? null);
const nullablePositiveInt = z.number().int().positive().nullish().transform((value) => value ?? null);

const createTaskBody = z.object({
  title: z.string().min(1),
  projectId: z.string().min(1),
  assigneeId: nullableString,
  dueDatetime: nullableString,
  durationMinutes: nullablePositiveInt,
  description: z.string().trim().min(1).nullable().optional()
});
const rescheduleBody = z.object({
  dueDatetime: z.string().min(1),
  durationMinutes: z.number().int().positive(),
  description: z.string().trim().nullable().optional()
});
const updateTaskBody = z.object({
  title: z.string().trim().min(1),
  description: z.string().trim().nullable().optional(),
  projectId: z.string().min(1),
  assigneeId: nullableString,
  dueDatetime: z.string().min(1),
  durationMinutes: z.number().int().positive()
});
const deviceCheckInBody = z.object({
  deviceId: z.string().min(1),
  appVersion: z.string().min(1),
  permissions: z.record(z.string(), z.union([z.string(), z.number(), z.boolean(), z.null()]))
});

export function registerRoutes(app: FastifyInstance, options: BuildServerOptions): void {
  app.get("/health", async (_request, reply) => {
    const checks: {
      database: "ok" | "failed";
      whatsapp?: { connected: boolean; detail: string };
    } = { database: "ok" };

    try {
      options.db.prepare("select 1").get();
    } catch (error) {
      checks.database = "failed";
      reply.status(503).send({
        ok: false,
        service: "buzer-backend",
        timestamp: new Date().toISOString(),
        checks,
        message: error instanceof Error ? error.message : "Database health check failed"
      });
      return;
    }

    if (options.escalationService !== null && options.escalationService !== undefined) {
      checks.whatsapp = await options.escalationService.getHealth();
    }

    return {
      ok: true,
      service: "buzer-backend",
      timestamp: new Date().toISOString(),
      checks
    };
  });

  app.addHook("preHandler", async (request) => {
    if (request.url === "/health") {
      return;
    }

    requireBearerToken(request, options.androidApiToken);
  });

  app.setErrorHandler((error: Error & { statusCode?: number; code?: string; details?: Record<string, unknown> }, _request, reply) => {
    if (error instanceof z.ZodError) {
      reply.status(400).send({
        code: "validation_error",
        message: "Request validation failed",
        details: { issues: error.issues }
      });
      return;
    }

    const statusCode = typeof error.statusCode === "number" ? error.statusCode : 500;
    reply.status(statusCode).send({
      code: typeof error.code === "string" ? error.code : "internal_error",
      message: error.message,
      ...(error.details !== undefined ? { details: error.details } : {})
    });
  });

  app.get("/tasks/snapshot", async () => {
    const repo = new TaskRepository(options.db);
    const service = new TaskSnapshotService(repo, options.currentUserId);
    return service.getSnapshot(new Date().toISOString());
  });

  app.get("/projects", async () => {
    const repo = new ProjectRepository(options.db);
    return {
      projects: repo.listActive().map((project) => ({
        id: project.todoistProjectId,
        name: project.name,
        canAssignTasks: project.canAssignTasks,
        shared: project.shared,
        isInboxProject: project.isInboxProject
      }))
    };
  });

  app.post("/tasks", async (request) => {
    const body = createTaskBody.parse(request.body);
    const repo = new TaskRepository(options.db);
    const service = new TaskActionService(
      options.db,
      repo,
      new ProjectRepository(options.db),
      options.todoist,
      options.currentUserId,
      options.escalationService ?? null
    );
    return service.create({
      ...body,
      description: body.description ?? null,
      creatorId: options.currentUserId,
      now: new Date().toISOString()
    });
  });

  app.post("/tasks/:id/start", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(
      options.db,
      new TaskRepository(options.db),
      new ProjectRepository(options.db),
      options.todoist,
      options.currentUserId,
      options.escalationService ?? null
    );
    await service.start(id, new Date().toISOString());
    return { ok: true };
  });

  app.post("/tasks/:id/done", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(
      options.db,
      new TaskRepository(options.db),
      new ProjectRepository(options.db),
      options.todoist,
      options.currentUserId,
      options.escalationService ?? null
    );
    await service.done(id, new Date().toISOString());
    return { ok: true };
  });

  app.post("/tasks/:id/postpone-start", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(
      options.db,
      new TaskRepository(options.db),
      new ProjectRepository(options.db),
      options.todoist,
      options.currentUserId,
      options.escalationService ?? null
    );
    return service.postponeStart(id, new Date().toISOString());
  });

  app.post("/tasks/:id/extend", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(
      options.db,
      new TaskRepository(options.db),
      new ProjectRepository(options.db),
      options.todoist,
      options.currentUserId,
      options.escalationService ?? null
    );
    await service.extend(id, new Date().toISOString());
    return { ok: true };
  });

  app.post("/tasks/:id/reschedule", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = rescheduleBody.parse(request.body);
    const service = new TaskActionService(
      options.db,
      new TaskRepository(options.db),
      new ProjectRepository(options.db),
      options.todoist,
      options.currentUserId,
      options.escalationService ?? null
    );
    const descriptionPatch = Object.prototype.hasOwnProperty.call(body, "description")
      ? { description: body.description ?? null }
      : {};
    await service.reschedule(id, {
      dueDatetime: body.dueDatetime,
      durationMinutes: body.durationMinutes,
      ...descriptionPatch,
      now: new Date().toISOString()
    });
    return { ok: true };
  });

  app.post("/tasks/:id/update", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = updateTaskBody.parse(request.body);
    const service = new TaskActionService(
      options.db,
      new TaskRepository(options.db),
      new ProjectRepository(options.db),
      options.todoist,
      options.currentUserId,
      options.escalationService ?? null
    );
    await service.update(id, {
      title: body.title,
      description: body.description ?? null,
      projectId: body.projectId,
      assigneeId: body.assigneeId,
      dueDatetime: body.dueDatetime,
      durationMinutes: body.durationMinutes,
      now: new Date().toISOString()
    });
    return { ok: true };
  });

  app.post("/review/dismiss", async () => {
    return { snoozedUntil: new Date(Date.now() + 5 * 60_000).toISOString() };
  });

  app.post("/device/check-in", async (request) => {
    const body = deviceCheckInBody.parse(request.body);
    const repo = new DeviceRepository(options.db);
    repo.upsertCheckIn({
      id: body.deviceId,
      appVersion: body.appVersion,
      permissions: body.permissions,
      checkedInAt: new Date().toISOString()
    });
    return { ok: true };
  });
}
