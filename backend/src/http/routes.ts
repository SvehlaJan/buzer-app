import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { DeviceRepository } from "../repositories/deviceRepository.js";
import { TaskRepository } from "../repositories/taskRepository.js";
import { TaskActionService } from "../services/taskActionService.js";
import { TaskSnapshotService } from "../services/taskSnapshotService.js";
import type { BuildServerOptions } from "../server.js";
import { requireBearerToken } from "./auth.js";

const taskIdParams = z.object({ id: z.string().min(1) });
const createTaskBody = z.object({
  title: z.string().min(1),
  projectId: z.string().nullable(),
  assigneeId: z.string().min(1),
  dueDatetime: z.string().nullable(),
  durationMinutes: z.number().int().positive().nullable()
});
const rescheduleBody = z.object({
  dueDatetime: z.string().min(1),
  durationMinutes: z.number().int().positive()
});
const deviceCheckInBody = z.object({
  deviceId: z.string().min(1),
  appVersion: z.string().min(1),
  permissions: z.record(z.string(), z.union([z.string(), z.number(), z.boolean(), z.null()]))
});

export function registerRoutes(app: FastifyInstance, options: BuildServerOptions): void {
  app.addHook("preHandler", async (request) => {
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

  app.post("/tasks", async (request) => {
    const body = createTaskBody.parse(request.body);
    const repo = new TaskRepository(options.db);
    const service = new TaskActionService(options.db, repo, options.todoist, options.currentUserId);
    return service.create({
      ...body,
      creatorId: options.currentUserId,
      now: new Date().toISOString()
    });
  });

  app.post("/tasks/:id/start", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(options.db, new TaskRepository(options.db), options.todoist);
    await service.start(id, new Date().toISOString());
    return { ok: true };
  });

  app.post("/tasks/:id/done", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(options.db, new TaskRepository(options.db), options.todoist);
    await service.done(id, new Date().toISOString());
    return { ok: true };
  });

  app.post("/tasks/:id/postpone-start", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(options.db, new TaskRepository(options.db), options.todoist);
    return service.postponeStart(id, new Date().toISOString());
  });

  app.post("/tasks/:id/extend", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const service = new TaskActionService(options.db, new TaskRepository(options.db), options.todoist);
    await service.extend(id, new Date().toISOString());
    return { ok: true };
  });

  app.post("/tasks/:id/reschedule", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = rescheduleBody.parse(request.body);
    const service = new TaskActionService(options.db, new TaskRepository(options.db), options.todoist);
    await service.reschedule(id, { ...body, now: new Date().toISOString() });
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
