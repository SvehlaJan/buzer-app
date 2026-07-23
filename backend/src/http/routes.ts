import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { DeviceRepository } from "../repositories/deviceRepository.js";
import { CollaboratorRepository } from "../repositories/collaboratorRepository.js";
import { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskActionService } from "../services/taskActionService.js";
import type { TaskSnapshotService } from "../services/taskSnapshotService.js";
import type { BuildServerOptions } from "../server.js";
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
const actionMetadata = z.strictObject({
  actionId: opaqueValue,
  deviceId: opaqueValue
});
const occurrenceActionMetadata = actionMetadata.extend({ expectedOccurrenceKey: opaqueValue });
const revisionActionMetadata = actionMetadata.extend({ expectedOccurrenceKey: opaqueValue, expectedTaskRevision: taskRevision });
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
  checklistDrafts: z.array(checklistDraft).max(30).optional(),
  ...actionMetadata.shape
});
const addChecklistItemBody = z.strictObject({
  clientMutationId: opaqueValue,
  title: z.string().trim().min(1).max(MAX_TEXT_LENGTH),
  description: nullableString,
  ...occurrenceActionMetadata.shape
});
const focusedChecklistItemBody = z.strictObject({
  expectedChildRevision: taskRevision,
  ...occurrenceActionMetadata.shape
});
const editChecklistItemBody = focusedChecklistItemBody.extend({
  title: z.string().trim().min(1).max(MAX_TEXT_LENGTH),
  description: nullableString
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
  ...occurrenceActionMetadata.shape,
  rootPatch: rootEditPatch.nullable(),
  childMutations: z.array(childMutation).max(30)
});
const deviceCheckInBody = z.strictObject({
  deviceId: opaqueValue,
  appVersion: z.string().trim().min(1).max(200),
  permissions: z.record(z.string().min(1).max(100), z.union([z.string().max(200), z.number(), z.boolean(), z.null()]))
});
const emptyBody = z.strictObject({});

export interface RouteServices {
  actions: TaskActionService;
  snapshots: TaskSnapshotService;
  collaborators: CollaboratorRepository;
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
  app.setErrorHandler((error: Error & { statusCode?: number; code?: string; details?: Record<string, unknown> }, request, reply) => {
    if (error instanceof z.ZodError) {
      reply.status(400).send({ code: "validation_error", message: "Request validation failed", details: { issues: error.issues } });
      return;
    }
    const statusCode = typeof error.statusCode === "number" ? error.statusCode : 500;
    const actionId = actionIdFromBody(request.body);
    if (statusCode === 202 && error.code === "reconciliation_pending") {
      reply.status(statusCode).send({
        ...(actionId === null ? {} : { actionId }),
        outcome: "reconciliation_pending",
        snapshot: services.snapshots.getSnapshot(new Date().toISOString())
      });
      return;
    }
    if (statusCode === 409) {
      reply.status(statusCode).send({
        code: typeof error.code === "string" ? error.code : "stale_task",
        message: error.message,
        snapshot: services.snapshots.getSnapshot(new Date().toISOString())
      });
      return;
    }
    reply.status(statusCode).send({
      code: typeof error.code === "string" ? error.code : "internal_error",
      message: error.message,
      ...(error.details === undefined ? {} : { details: error.details })
    });
  });

  app.get("/tasks/snapshot", async () => services.snapshots.getSnapshot(new Date().toISOString()));
  app.get("/projects", async () => {
    const collaboratorsReady = services.collaborators.isMirrorContractReady();
    return {
      projects: new ProjectRepository(options.db).listActive().map((project) => ({
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

  app.post("/tasks", async (request) => {
    const body = createTaskBody.parse(request.body);
    const result = await services.actions.create({
      title: body.title, projectId: body.projectId, assigneeId: body.assigneeId, dueDatetime: body.dueDatetime,
      durationMinutes: body.durationMinutes, description: body.description, actionId: body.actionId, deviceId: body.deviceId,
      ...(body.recurrencePreset === undefined ? {} : { recurrencePreset: body.recurrencePreset }),
      ...(body.timeZone === undefined ? {} : { timeZone: body.timeZone }),
      ...(body.checklistDrafts === undefined ? {} : {
        checklist: body.checklistDrafts.map((draft) => ({
          clientMutationId: draft.clientMutationId,
          title: draft.title,
          description: draft.description
        }))
      }),
      creatorId: options.currentUserId, now: new Date().toISOString()
    });
    return mutationResponse(body.actionId, result, services.snapshots);
  });
  app.post("/tasks/:id/start", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = occurrenceActionMetadata.parse(request.body);
    await services.actions.start(id, new Date().toISOString(), body);
    return mutationResponse(body.actionId, undefined, services.snapshots);
  });
  app.post("/tasks/:id/done", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = occurrenceActionMetadata.parse(request.body);
    await services.actions.done(id, new Date().toISOString(), body);
    return mutationResponse(body.actionId, undefined, services.snapshots);
  });
  app.post("/tasks/:id/postpone-start", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = occurrenceActionMetadata.parse(request.body);
    return mutationResponse(body.actionId, await services.actions.postponeStart(id, new Date().toISOString(), body), services.snapshots);
  });
  app.post("/tasks/:id/extend", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = revisionActionMetadata.parse(request.body);
    await services.actions.extend(id, new Date().toISOString(), body);
    return mutationResponse(body.actionId, undefined, services.snapshots);
  });
  app.post("/tasks/:id/update", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = updateTaskBody.parse(request.body);
    const result = await services.actions.update(id, {
      actionId: body.actionId,
      deviceId: body.deviceId,
      expectedOccurrenceKey: body.expectedOccurrenceKey,
      rootPatch: body.rootPatch === null ? null : mapRootEditPatch(body.rootPatch),
      childMutations: body.childMutations.map(mapChildMutation)
    });
    return mutationResponse(body.actionId, result, services.snapshots);
  });
  app.post("/tasks/:id/checklist", async (request) => {
    const { id } = taskIdParams.parse(request.params);
    const body = addChecklistItemBody.parse(request.body);
    const result = await services.actions.mutateChecklist(id, {
      action: { actionId: body.actionId, deviceId: body.deviceId, expectedOccurrenceKey: requiredOccurrenceKey(body.expectedOccurrenceKey) },
      mutations: [{ type: "add", clientMutationId: body.clientMutationId, title: body.title, description: body.description }]
    });
    return mutationResponse(body.actionId, result, services.snapshots);
  });
  app.post("/tasks/:id/checklist/:childId/update", async (request) => {
    const { id, childId } = checklistChildParams.parse(request.params);
    const body = editChecklistItemBody.parse(request.body);
    const result = await services.actions.mutateChecklist(id, {
      action: { actionId: body.actionId, deviceId: body.deviceId, expectedOccurrenceKey: requiredOccurrenceKey(body.expectedOccurrenceKey) },
      mutations: [{ type: "edit", clientMutationId: body.actionId, childId, expectedChildRevision: body.expectedChildRevision, title: body.title, description: body.description }]
    });
    return mutationResponse(body.actionId, result, services.snapshots);
  });
  for (const action of ["delete", "check", "uncheck"] as const) {
    app.post(`/tasks/:id/checklist/:childId/${action}`, async (request) => {
      const { id, childId } = checklistChildParams.parse(request.params);
      const body = focusedChecklistItemBody.parse(request.body);
      const result = await services.actions.mutateChecklist(id, {
        action: { actionId: body.actionId, deviceId: body.deviceId, expectedOccurrenceKey: requiredOccurrenceKey(body.expectedOccurrenceKey) },
        mutations: [{ type: action, clientMutationId: body.actionId, childId, expectedChildRevision: body.expectedChildRevision }]
      });
      return mutationResponse(body.actionId, result, services.snapshots);
    });
  }
  app.post("/review/dismiss", async (request) => {
    emptyBody.parse(request.body ?? {});
    return { snoozedUntil: new Date(Date.now() + 5 * 60_000).toISOString() };
  });
  app.post("/device/check-in", async (request) => {
    const body = deviceCheckInBody.parse(request.body);
    new DeviceRepository(options.db).upsertCheckIn({ id: body.deviceId, appVersion: body.appVersion, permissions: body.permissions, checkedInAt: new Date().toISOString() });
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

function mutationResponse(actionId: string, result: unknown, snapshots: TaskSnapshotService) {
  const snapshot = snapshots.getSnapshot(new Date().toISOString());
  return { actionId, outcome: "completed", snapshot };
}

const checklistChildParams = z.strictObject({ id: opaqueValue, childId: opaqueValue });

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

function requiredOccurrenceKey(value: string | null): string {
  if (value === null) {
    throw Object.assign(new Error("Checklist mutations require expectedOccurrenceKey"), {
      code: "task_action_metadata_invalid",
      statusCode: 422
    });
  }
  return value;
}

function actionIdFromBody(body: unknown): string | null {
  if (typeof body !== "object" || body === null || !("actionId" in body)) return null;
  const actionId = (body as { actionId?: unknown }).actionId;
  return typeof actionId === "string" ? actionId : null;
}

function isIanaTimeZone(value: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}
