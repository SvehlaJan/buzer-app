import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { z } from "zod";
import type { CollaboratorRepository } from "../repositories/collaboratorRepository.js";
import type { DeviceRepository } from "../repositories/deviceRepository.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { BuildServerOptions } from "../server.js";
import type { TaskActionService } from "../services/taskActionService.js";
import type { MutationRunResult, TaskSnapshot } from "../services/taskMutationRunner.js";
import type { TaskSnapshotService } from "../services/taskSnapshotService.js";
import type { TodoistRuntime } from "../services/todoistRuntime.js";
import { isBearerTokenValid, requireBearerToken } from "./auth.js";

const MAX_OPAQUE_VALUE_LENGTH = 200;
const MAX_TEXT_LENGTH = 16_384;
const opaqueValue = z.string().trim().min(1).max(MAX_OPAQUE_VALUE_LENGTH);
const taskRevision = z.string().regex(/^r:\d+$/).max(MAX_OPAQUE_VALUE_LENGTH);
const nullableString = z.string().trim().min(1).max(MAX_TEXT_LENGTH).nullable().optional().transform((value) => value ?? null);
const nullablePositiveInt = z.number().int().positive().nullable().optional().transform((value) => value ?? null);
const nullablePatchString = z.string().trim().min(1).max(MAX_TEXT_LENGTH).nullable().optional();
const nullablePatchPositiveInt = z.number().int().positive().nullable().optional();
const timeZone = z.string().trim().min(1).max(100).refine(isIanaTimeZone, "timeZone must be an IANA time zone");
const recurrencePreset = z.enum(["never", "daily", "weekdays", "weekly", "monthly", "yearly"]);
const taskIdParams = z.strictObject({ id: opaqueValue });
const checklistChildParams = z.strictObject({ id: opaqueValue, childId: opaqueValue });
const getSnapshotQuery = z.strictObject({ forceRefresh: z.enum(["true", "false"]).optional() });

const occurrenceActionMetadata = z.strictObject({ expectedOccurrenceKey: opaqueValue });
const revisionActionMetadata = z.strictObject({
  expectedOccurrenceKey: opaqueValue,
  expectedTaskRevision: taskRevision
});

const checklistDraft = z.strictObject({
  clientMutationId: opaqueValue,
  title: z.string().trim().min(1).max(MAX_TEXT_LENGTH),
  description: nullableString
});

const childMutationBase = { clientMutationId: opaqueValue };
const childMutation = z.discriminatedUnion("type", [
  z.strictObject({ ...childMutationBase, type: z.literal("add"), title: z.string().trim().min(1).max(MAX_TEXT_LENGTH), description: nullableString }),
  z.strictObject({ ...childMutationBase, type: z.literal("edit"), childId: opaqueValue, expectedChildRevision: taskRevision, title: z.string().trim().min(1).max(MAX_TEXT_LENGTH), description: nullableString }),
  z.strictObject({ ...childMutationBase, type: z.literal("delete"), childId: opaqueValue, expectedChildRevision: taskRevision }),
  z.strictObject({ ...childMutationBase, type: z.literal("check"), childId: opaqueValue, expectedChildRevision: taskRevision }),
  z.strictObject({ ...childMutationBase, type: z.literal("uncheck"), childId: opaqueValue, expectedChildRevision: taskRevision })
]);

const createTaskBody = z.strictObject({
  title: z.string().trim().min(1).max(MAX_TEXT_LENGTH),
  projectId: opaqueValue,
  assigneeId: nullableString,
  dueDatetime: nullableString,
  durationMinutes: nullablePositiveInt,
  description: nullableString,
  recurrencePreset: recurrencePreset.optional(),
  timeZone: timeZone.optional(),
  checklistDrafts: z.array(checklistDraft).max(30).optional()
});

const focusedChecklistItemBody = z.strictObject({
  expectedOccurrenceKey: opaqueValue,
  expectedChildRevision: taskRevision
});

const rootEditPatch = z.strictObject({
  expectedTaskRevision: taskRevision,
  title: z.string().trim().min(1).max(MAX_TEXT_LENGTH).optional(),
  description: nullablePatchString,
  projectId: opaqueValue.optional(),
  assigneeId: nullablePatchString,
  dueDatetime: z.string().trim().min(1).max(100).nullable().optional(),
  durationMinutes: nullablePatchPositiveInt,
  recurrencePreset: recurrencePreset.optional(),
  timeZone: timeZone.optional()
});

const updateTaskBody = z.strictObject({
  expectedOccurrenceKey: opaqueValue.optional(),
  rootPatch: rootEditPatch.nullable(),
  childMutations: z.array(childMutation).max(30)
});

const deviceCheckInBody = z.strictObject({
  deviceId: opaqueValue,
  appVersion: z.string().trim().min(1).max(200),
  permissions: z.record(z.string().min(1).max(100), z.union([z.string().max(200), z.number(), z.boolean(), z.null()]))
});

export interface RouteServices {
  snapshots: TaskSnapshotService;
  todoistRuntime: Pick<TodoistRuntime, "forceRefresh">;
  actions: TaskActionService;
  projects: ProjectRepository;
  collaborators: CollaboratorRepository;
  devices: DeviceRepository;
}

export function registerRoutes(app: FastifyInstance, options: BuildServerOptions, services: RouteServices): void {
  app.get("/health", async (request, reply) => {
    const authenticated = isBearerTokenValid(request, options.androidApiToken);
    const timestamp = new Date().toISOString();
    const checks: { database: "ok" | "failed"; whatsapp?: { connected: boolean; detail: string } } = { database: "ok" };
    try {
      options.db.prepare("select 1").get();
    } catch (error) {
      checks.database = "failed";
      reply.status(503).send({ ok: false, service: "buzer-backend", timestamp, ...(authenticated ? { checks, message: error instanceof Error ? error.message : "Database health check failed" } : {}) });
      return;
    }
    if (authenticated && options.escalationService !== null && options.escalationService !== undefined) {
      checks.whatsapp = await options.escalationService.getHealth();
    }
    return { ok: true, service: "buzer-backend", timestamp, ...(authenticated ? { checks } : {}) };
  });

  app.addHook("preHandler", async (request) => {
    if (request.routeOptions.url !== "/health") requireBearerToken(request, options.androidApiToken);
  });

  app.setErrorHandler((error: Error & { statusCode?: number; code?: string; details?: Record<string, unknown> }, _request, reply) => {
    if (error instanceof z.ZodError) {
      reply.status(400).send({ code: "validation_error", message: "Request validation failed", details: { issues: error.issues } });
      return;
    }
    const statusCode = typeof error.statusCode === "number" ? error.statusCode : 500;
    if (statusCode === 409 || error.code === "stale_task" || error.code === "stale_occurrence" || error.code === "stale_child") {
      reply.status(statusCode).send({
        code: typeof error.code === "string" ? error.code : "stale_task",
        message: error.message,
        snapshot: (error.details?.snapshot as TaskSnapshot | undefined) ?? services.snapshots.getSnapshot(new Date().toISOString())
      });
      return;
    }
    reply.status(statusCode).send({
      code: typeof error.code === "string" ? error.code : "internal_error",
      message: error.message,
      ...(error.details === undefined ? {} : { details: error.details })
    });
  });

  app.get("/tasks/snapshot", async (request, reply) => {
    const query = getSnapshotQuery.parse(request.query ?? {});
    if (query.forceRefresh === "true") {
      const signal = getRequestSignal(request);
      const outcome = await services.todoistRuntime.forceRefresh({
        requestId: request.id,
        ...(signal !== undefined ? { signal } : {})
      });
      switch (outcome.kind) {
        case "success":
          return outcome.snapshot;
        case "busy":
          return reply.status(503).send({
            code: "backend_busy",
            message: outcome.message,
            ...(outcome.snapshot !== undefined ? { snapshot: outcome.snapshot } : {})
          });
        case "failed":
          return reply.status(503).send({
            code: "forced_refresh_failed",
            message: outcome.message,
            ...(outcome.snapshot !== undefined ? { snapshot: outcome.snapshot } : {})
          });
      }
    }

    return services.snapshots.getSnapshot(new Date().toISOString());
  });

  app.get("/projects", async () => {
    const collaboratorsReady = services.collaborators.isMirrorContractReady();
    return {
      projects: services.projects.listActive().map((project) => ({
        id: project.todoistProjectId,
        name: project.name,
        canAssignTasks: project.canAssignTasks,
        shared: project.shared,
        isInboxProject: project.isInboxProject,
        collaboratorsReady,
        collaborators: project.shared
          ? projectCollaborators(services.collaborators, options.currentUserId, project.todoistProjectId)
          : [currentUserCollaborator(options.currentUserId)]
      }))
    };
  });

  app.post("/tasks", async (request, reply) => {
    const body = createTaskBody.parse(request.body);
    const signal = getRequestSignal(request);
    const result = await services.actions.createTask({
      title: body.title,
      projectId: body.projectId,
      assigneeId: body.assigneeId,
      dueDatetime: body.dueDatetime,
      durationMinutes: body.durationMinutes,
      description: body.description,
      ...(body.recurrencePreset === undefined ? {} : { recurrencePreset: body.recurrencePreset }),
      ...(body.timeZone === undefined ? {} : { timeZone: body.timeZone }),
      ...(body.checklistDrafts === undefined ? {} : {
        checklist: body.checklistDrafts.map((draft) => ({
          clientMutationId: draft.clientMutationId,
          title: draft.title,
          description: draft.description
        }))
      }),
      creatorId: options.currentUserId,
      now: new Date().toISOString(),
      requestId: request.id,
      ...(signal !== undefined ? { signal } : {})
    });
    return sendMutationResult(reply, result);
  });

  app.post("/tasks/:id/start", async (request, reply) => {
    const { id } = taskIdParams.parse(request.params);
    const body = occurrenceActionMetadata.parse(request.body);
    const result = await services.actions.startTask(id, new Date().toISOString(), body);
    return sendMutationResult(reply, result);
  });

  app.post("/tasks/:id/done", async (request, reply) => {
    const { id } = taskIdParams.parse(request.params);
    const body = occurrenceActionMetadata.parse(request.body);
    const signal = getRequestSignal(request);
    const result = await services.actions.completeTask(id, new Date().toISOString(), {
      ...body,
      requestId: request.id,
      ...(signal !== undefined ? { signal } : {})
    });
    return sendMutationResult(reply, result);
  });

  app.post("/tasks/:id/postpone-start", async (request, reply) => {
    const { id } = taskIdParams.parse(request.params);
    const body = occurrenceActionMetadata.parse(request.body);
    const result = await services.actions.postponeTask(id, new Date().toISOString(), body);
    return sendMutationResult(reply, result);
  });

  app.post("/tasks/:id/extend", async (request, reply) => {
    const { id } = taskIdParams.parse(request.params);
    const body = revisionActionMetadata.parse(request.body);
    const signal = getRequestSignal(request);
    const result = await services.actions.extendTask(id, new Date().toISOString(), {
      ...body,
      requestId: request.id,
      ...(signal !== undefined ? { signal } : {})
    });
    return sendMutationResult(reply, result);
  });

  app.post("/tasks/:id/update", async (request, reply) => {
    const { id } = taskIdParams.parse(request.params);
    const body = updateTaskBody.parse(request.body);
    const signal = getRequestSignal(request);
    const result = await services.actions.editTask(id, {
      expectedOccurrenceKey: body.expectedOccurrenceKey,
      rootPatch: body.rootPatch === null ? null : mapRootEditPatch(body.rootPatch),
      childMutations: body.childMutations.map(mapChildMutation),
      requestId: request.id,
      ...(signal !== undefined ? { signal } : {})
    });
    return sendMutationResult(reply, result);
  });

  app.post("/tasks/:id/checklist/:childId/check", async (request, reply) => {
    const { id, childId } = checklistChildParams.parse(request.params);
    const body = focusedChecklistItemBody.parse(request.body);
    const signal = getRequestSignal(request);
    const result = await services.actions.checkChecklistItem(id, childId, {
      expectedOccurrenceKey: body.expectedOccurrenceKey,
      expectedChildRevision: body.expectedChildRevision,
      requestId: request.id,
      ...(signal !== undefined ? { signal } : {})
    });
    return sendMutationResult(reply, result);
  });

  app.post("/tasks/:id/checklist/:childId/uncheck", async (request, reply) => {
    const { id, childId } = checklistChildParams.parse(request.params);
    const body = focusedChecklistItemBody.parse(request.body);
    const signal = getRequestSignal(request);
    const result = await services.actions.uncheckChecklistItem(id, childId, {
      expectedOccurrenceKey: body.expectedOccurrenceKey,
      expectedChildRevision: body.expectedChildRevision,
      requestId: request.id,
      ...(signal !== undefined ? { signal } : {})
    });
    return sendMutationResult(reply, result);
  });

  app.post("/device/check-in", async (request) => {
    const body = deviceCheckInBody.parse(request.body);
    services.devices.upsertCheckIn({
      id: body.deviceId,
      appVersion: body.appVersion,
      permissions: body.permissions,
      checkedInAt: new Date().toISOString()
    });
    return { ok: true };
  });
}

interface ProjectCollaboratorResponse {
  id: string;
  displayName: string;
  email: string | null;
  isCurrentUser: boolean;
}

function currentUserCollaborator(currentUserId: string): ProjectCollaboratorResponse {
  return { id: currentUserId, displayName: "Me", email: null, isCurrentUser: true };
}

function projectCollaborators(
  collaborators: CollaboratorRepository,
  currentUserId: string,
  projectId: string
): ProjectCollaboratorResponse[] {
  return [
    currentUserCollaborator(currentUserId),
    ...collaborators.listActiveForProject(projectId)
      .filter((collaborator) => collaborator.todoistUserId !== currentUserId)
      .map((collaborator) => ({
        id: collaborator.todoistUserId,
        displayName: collaborator.fullName,
        email: collaborator.email,
        isCurrentUser: false
      }))
  ];
}

function getRequestSignal(request: FastifyRequest): AbortSignal | undefined {
  return request.signal;
}

function sendMutationResult(
  reply: FastifyReply,
  result: MutationRunResult | { kind: "success"; snapshot: TaskSnapshot; snoozedUntil?: string }
) {
  switch (result.kind) {
    case "success": {
      const snoozedUntil = "snoozedUntil" in result && typeof result.snoozedUntil === "string"
        ? result.snoozedUntil
        : undefined;
      return reply.status(200).send({
        snapshot: result.snapshot,
        ...(snoozedUntil !== undefined ? { snoozedUntil } : {})
      });
    }
    case "busy":
      return reply.status(503).send({
        code: "backend_busy",
        message: "Buzer is currently processing another Todoist operation. Nothing was sent. Please try again shortly."
      });
    case "rejected":
      return reply.status(502).send({
        code: "todoist_command_rejected",
        message: "Todoist rejected the requested change. Check Todoist before trying again.",
        ...(result.snapshot !== undefined ? { snapshot: result.snapshot } : {})
      });
    case "delivery_unknown":
      return reply.status(504).send({
        code: "todoist_delivery_unknown",
        message: "Buzer could not confirm the change. Check Todoist before trying again.",
        ...(result.snapshot !== undefined ? { snapshot: result.snapshot } : {})
      });
    case "accepted_stale":
      return reply.status(502).send({
        code: "todoist_accepted_snapshot_stale",
        message: "The change was accepted by Todoist, but the local snapshot could not be refreshed."
      });
  }
}

function mapRootEditPatch(patch: z.infer<typeof rootEditPatch>) {
  return {
    expectedTaskRevision: patch.expectedTaskRevision,
    ...(patch.title === undefined ? {} : { title: patch.title }),
    ...(patch.description === undefined ? {} : { description: patch.description }),
    ...(patch.projectId === undefined ? {} : { projectId: patch.projectId }),
    ...(patch.assigneeId === undefined ? {} : { assigneeId: patch.assigneeId }),
    ...(Object.prototype.hasOwnProperty.call(patch, "dueDatetime") ? { dueDatetime: patch.dueDatetime ?? null } : {}),
    ...(Object.prototype.hasOwnProperty.call(patch, "durationMinutes") ? { durationMinutes: patch.durationMinutes ?? null } : {}),
    ...(patch.recurrencePreset === undefined ? {} : { recurrencePreset: patch.recurrencePreset }),
    ...(patch.timeZone === undefined ? {} : { timeZone: patch.timeZone })
  };
}

function mapChildMutation(mutation: z.infer<typeof childMutation>) {
  switch (mutation.type) {
    case "add":
      return {
        type: "add" as const,
        clientMutationId: mutation.clientMutationId,
        title: mutation.title,
        description: mutation.description
      };
    case "edit":
      return {
        type: "edit" as const,
        clientMutationId: mutation.clientMutationId,
        childId: mutation.childId,
        expectedChildRevision: mutation.expectedChildRevision,
        title: mutation.title,
        description: mutation.description
      };
    case "delete":
    case "check":
    case "uncheck":
      return {
        type: mutation.type,
        clientMutationId: mutation.clientMutationId,
        childId: mutation.childId,
        expectedChildRevision: mutation.expectedChildRevision
      };
  }
}

function isIanaTimeZone(value: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}
