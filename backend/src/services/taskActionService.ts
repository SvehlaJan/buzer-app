import type Database from "better-sqlite3";
import { createHash, randomUUID } from "node:crypto";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import { addMinutes } from "../domain/time.js";
import { appendExtension, decodeTaskDescription, reconstructTaskDescription } from "../domain/taskDescriptionCodec.js";
import { describeRecurrence, translateRecurrencePreset, type RecurrencePreset } from "../domain/taskRecurrence.js";
import { recurrenceFingerprint } from "./occurrenceReconciler.js";
import { ActionJournalRepository } from "../repositories/actionJournalRepository.js";
import {
  TaskActionCoordinator,
  type TaskActionCommandContext
} from "./taskActionCoordinator.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";
import type { EscalationService } from "./escalationService.js";
import {
  TodoistChecklistService,
  type TodoistChecklistServiceOptions,
  type ChecklistMutationRequest,
  type ChecklistMutationResult,
  type ChecklistActionMetadata,
  type ChildMutation
} from "./todoistChecklistService.js";

export interface TaskActionServiceOptions {
  /** Production-facing checklist actions must supply live sync, snapshot, and coordination. */
  checklist?: TodoistChecklistServiceOptions;
  /** Task 9 supplies these server-scoped dependencies for action-aware root calls. */
  rootActions?: {
    coordinator: TaskActionCoordinator;
    synchronize: () => Promise<void>;
    snapshot: () => unknown;
  };
}

export interface RootActionMetadata {
  actionId: string;
  deviceId: string;
  expectedOccurrenceKey?: string | null;
  expectedTaskRevision?: string;
}

export interface CreateTaskInput {
  title: string;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  description: string | null;
  recurrencePreset?: RecurrencePreset;
  timeZone?: string;
  checklist?: ReadonlyArray<{ clientMutationId?: string; title: string; description?: string | null }>;
  actionId?: string;
  deviceId?: string;
  creatorId: string;
  now: string;
}

export interface RescheduleTaskInput {
  dueDatetime: string;
  durationMinutes: number;
  description?: string | null;
  now: string;
  actionId?: string;
  deviceId?: string;
  expectedOccurrenceKey?: string | null;
  expectedTaskRevision?: string;
}

export interface RootEditPatch {
  expectedTaskRevision: string;
  title?: string;
  description?: string | null;
  projectId?: string;
  assigneeId?: string | null;
  dueDatetime?: string;
  durationMinutes?: number;
  recurrencePreset?: RecurrencePreset;
  timeZone?: string;
}

/** The fixed Task 9 update wire contract. Root and checklist edits are deliberately separate. */
export interface UpdateTaskRequest {
  actionId: string;
  deviceId: string;
  expectedOccurrenceKey: string;
  rootPatch: RootEditPatch | null;
  childMutations: readonly ChildMutation[];
}

interface ResolvedRootEdit {
  title: string;
  description: string | null;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string;
  durationMinutes: number;
  recurrencePreset?: RecurrencePreset;
  timeZone?: string;
}

export class TaskActionService {
  private readonly checklistService: TodoistChecklistService;
  private readonly actionJournal: ActionJournalRepository;

  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly todoist: TodoistAdapter,
    private readonly currentUserId = "",
    private readonly escalationService: EscalationService | null = null,
    private readonly requireMirrorReady = false,
    private readonly options: TaskActionServiceOptions = {}
  ) {
    this.checklistService = new TodoistChecklistService(tasks, todoist, options.checklist);
    this.actionJournal = new ActionJournalRepository(db);
  }

  /** Checklist children are intentionally delegated to their dedicated mutation boundary. */
  async mutateChecklist(taskId: string, request: ChecklistMutationRequest): Promise<ChecklistMutationResult> {
    this.assertMirrorReady();
    this.assertChecklistDependencies(request);
    return this.checklistService.mutate(taskId, request);
  }

  private assertChecklistDependencies(request: ChecklistMutationRequest): void {
    if (request.action === undefined) {
      throw Object.assign(new Error("Checklist mutations require durable action metadata"), {
        code: "checklist_action_metadata_required",
        statusCode: 503
      });
    }
    const checklist = this.options.checklist;
    if (checklist?.coordinator === undefined || checklist.synchronize === undefined || checklist.snapshot === undefined) {
      throw Object.assign(new Error("Checklist mutations require coordinated authoritative synchronization"), {
        code: "checklist_action_coordinator_required",
        statusCode: 503
      });
    }
  }

  private checklistActionForRootEdit(metadata: RootActionMetadata | undefined): ChecklistActionMetadata {
    if (metadata === undefined || metadata.expectedOccurrenceKey === undefined || metadata.expectedOccurrenceKey === null) {
      throw Object.assign(new Error("Checklist mutations require durable action metadata"), {
        code: "checklist_action_metadata_required",
        statusCode: 503
      });
    }
    return {
      actionId: actionStableUuid(metadata.actionId, "checklist_mutations"),
      deviceId: metadata.deviceId,
      expectedOccurrenceKey: metadata.expectedOccurrenceKey
    };
  }

  private async executeRootAction<T>(
    config: {
      rootTaskId: string;
      actionType: string;
      payload: unknown;
      metadata: RootActionMetadata;
      allowRootAbsent?: boolean;
      resolveRootTaskId?: () => string | null;
      isEffectPresent: () => boolean;
      responseAfterReconciliation: () => T;
      reconcile?: (context: TaskActionCommandContext) => Promise<void>;
      finalize?: () => void;
      useAuthoritativeResponse?: boolean;
    },
    execute: (context: TaskActionCommandContext) => Promise<T>
  ): Promise<T> {
    const rootActions = this.options.rootActions;
    if (rootActions === undefined) {
      throw Object.assign(new Error("Root actions with action metadata require coordination"), {
        code: "task_action_coordinator_required",
        statusCode: 503
      });
    }
    const result = await rootActions.coordinator.execute({
      actionId: config.metadata.actionId,
      deviceId: config.metadata.deviceId,
      rootTaskId: config.rootTaskId,
      expectedOccurrenceKey: config.metadata.expectedOccurrenceKey ?? null,
      ...(config.metadata.expectedTaskRevision === undefined ? {} : { expectedTaskRevision: config.metadata.expectedTaskRevision }),
      actionType: config.actionType,
      payload: config.payload,
      allowRootAbsent: config.allowRootAbsent === true,
      ...(config.resolveRootTaskId === undefined ? {} : { resolveRootTaskId: config.resolveRootTaskId }),
      isEffectPresent: () => config.isEffectPresent(),
      responseAfterReconciliation: () => config.responseAfterReconciliation(),
      compactResponse: (response) => response ?? {},
      ...(config.reconcile === undefined ? {} : { reconcile: (context) => config.reconcile?.(context) ?? Promise.resolve() }),
      ...(config.finalize === undefined ? {} : { finalize: () => config.finalize?.() }),
      ...(config.useAuthoritativeResponse === true ? { useAuthoritativeResponse: true } : {}),
      execute
    });
    if (result.kind === "pending") {
      throw Object.assign(new Error("Action is awaiting Todoist confirmation"), {
        code: "reconciliation_pending",
        statusCode: 202,
        details: { actionId: result.actionId, snapshot: rootActions.snapshot() }
      });
    }
    return result.response;
  }

  private rootMetadata(input: {
    actionId?: string;
    deviceId?: string;
    expectedOccurrenceKey?: string | null;
    expectedTaskRevision?: string;
  }, requirements: { occurrence?: boolean; revision?: boolean } = {}): RootActionMetadata {
    if (input.actionId === undefined && input.deviceId === undefined) {
      throw Object.assign(new Error("Root actions require action metadata"), {
        code: "task_action_metadata_required",
        statusCode: 422
      });
    }
    if (input.actionId === undefined || input.deviceId === undefined) {
      throw Object.assign(new Error("Root action metadata must include actionId and deviceId"), {
        code: "task_action_metadata_invalid",
        statusCode: 422
      });
    }
    if (requirements.occurrence === true && input.expectedOccurrenceKey === undefined) {
      throw Object.assign(new Error("Root action metadata must include expectedOccurrenceKey"), {
        code: "task_action_metadata_invalid",
        statusCode: 422
      });
    }
    if (requirements.revision === true && input.expectedTaskRevision === undefined) {
      throw Object.assign(new Error("Root action metadata must include expectedTaskRevision"), {
        code: "task_action_metadata_invalid",
        statusCode: 422
      });
    }
    return {
      actionId: input.actionId,
      deviceId: input.deviceId,
      ...(input.expectedOccurrenceKey === undefined ? {} : { expectedOccurrenceKey: input.expectedOccurrenceKey }),
      ...(input.expectedTaskRevision === undefined ? {} : { expectedTaskRevision: input.expectedTaskRevision })
    };
  }

  async create(input: CreateTaskInput): Promise<{ id: string; todoistTaskId: string }> {
    this.assertMirrorReady();
    const project = this.projects.getActiveByTodoistProjectId(input.projectId);
    if (project === null) {
      throw Object.assign(new Error("Project not found"), {
        code: "project_not_found",
        statusCode: 404
      });
    }

    const errors: string[] = [];
    if (input.title.trim().length === 0) {
      errors.push("title_required");
    }
    if (input.dueDatetime === null) {
      errors.push("due_datetime_required");
    }
    if (input.durationMinutes === null) {
      errors.push("duration_required");
    } else if (!Number.isInteger(input.durationMinutes) || input.durationMinutes <= 0) {
      errors.push("duration_must_be_positive_minutes");
    }
    if (project.canAssignTasks && input.assigneeId === null) {
      errors.push("assignee_required_for_assignable_project");
    }
    if (!project.canAssignTasks && input.assigneeId !== null) {
      errors.push("assignee_not_supported_for_project");
    }
    if (errors.length > 0) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors }
      });
    }

    const dueDatetime = input.dueDatetime;
    const durationMinutes = input.durationMinutes;
    if (dueDatetime === null || durationMinutes === null) {
      throw new Error("unreachable_missing_schedule_after_validation");
    }

    const strictAssigneeId = project.canAssignTasks ? input.assigneeId : this.currentUserId;
    const validation = validateTaskStrictness({
      title: input.title,
      creatorId: input.creatorId,
      assigneeId: strictAssigneeId,
      dueDatetime,
      durationMinutes,
      currentUserId: this.currentUserId
    });
    if (!validation.valid) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors: validation.errors }
      });
    }

    const recurrencePreset = input.recurrencePreset ?? "never";
    const timeZone = input.timeZone ?? "UTC";
    const checklist = input.checklist ?? [];
    if (!isRecurrencePreset(recurrencePreset)) {
      errors.push("recurrence_preset_invalid");
    }
    if (!isIanaTimeZone(timeZone)) {
      errors.push("time_zone_invalid");
    }
    if (checklist.length > 30) {
      errors.push("too_many_children");
    }
    for (const child of checklist) {
      if (child.title.trim().length === 0) {
        errors.push("child_title_required");
      }
    }
    if (errors.length > 0) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors }
      });
    }
    const metadata = this.rootMetadata(input);
    const recurrence = translateRecurrencePreset({ preset: recurrencePreset, selectedStart: dueDatetime, timeZone });
    const id = actionStableUuid(metadata.actionId, "root_id");
    const rootTempId = actionStableUuid(metadata.actionId, "item_add");
    return this.executeRootAction({
      rootTaskId: id,
      actionType: "create_task",
      payload: { title: input.title, projectId: input.projectId, recurrencePreset, timeZone, checklist },
      metadata,
      allowRootAbsent: true,
      useAuthoritativeResponse: true,
      resolveRootTaskId: () => this.syncedCreatedRootId(metadata.actionId),
      isEffectPresent: () => this.syncedCreatedRootId(metadata.actionId) !== null,
      responseAfterReconciliation: () => {
        const rootId = this.syncedCreatedRootId(metadata.actionId);
        const root = rootId === null ? null : this.tasks.getById(rootId);
        if (root === null) throw new Error("created_task_not_available_after_reconciliation");
        return { id: root.id, todoistTaskId: root.todoistTaskId };
      }
    }, async (context) => {
      const created = await context.executeCommand("item_add", (commandUuid) => this.todoist.itemAdd({
        kind: "root",
        commandUuid,
        tempId: rootTempId,
        title: input.title,
        description: normalizeOptionalText(input.description),
        projectId: input.projectId,
        assigneeId: input.assigneeId,
        due: recurrence === null
          ? { kind: "replace", datetime: dueDatetime, string: null, lang: null, timezone: null }
          : { kind: "replace", datetime: dueDatetime, ...recurrence },
        duration: { amount: durationMinutes, unit: "minute" }
      }), { tempId: rootTempId });
      if (created.todoistTaskId === null) {
        throw Object.assign(new Error("Todoist did not return a root task ID"), {
          code: "todoist_create_verification_failed",
          statusCode: 422
        });
      }
      const createdTodoistTaskId = created.todoistTaskId;
      for (const [childOrder, child] of checklist.entries()) {
        const childTitle = child.title.trim();
        const childTempId = actionStableUuid(metadata.actionId, `child_add:${childOrder}`);
        if (childTitle.length === 0) {
          throw Object.assign(new Error("Checklist item title is required"), { code: "child_title_required", statusCode: 422 });
        }
        const childCreated = await context.executeCommand(`child_add:${childOrder}`, (commandUuid, reservedTempId) => this.todoist.itemAdd({
            kind: "child",
            commandUuid,
            tempId: reservedTempId ?? childTempId,
            title: childTitle,
            description: normalizeOptionalText(child.description ?? null),
            projectId: input.projectId,
            parentTodoistTaskId: createdTodoistTaskId
        }), { tempId: childTempId });
        if (childCreated.todoistTaskId === null) {
          throw Object.assign(new Error("Todoist did not return a checklist item ID"), {
            code: "todoist_child_create_unconfirmed",
            statusCode: 502
          });
        }
      }
      return { id, todoistTaskId: createdTodoistTaskId };
    });
  }

  async start(taskId: string, now: string, action?: RootActionMetadata): Promise<void> {
    this.assertMirrorReady();
    const metadata = this.rootMetadata(action ?? {}, { occurrence: true });
    await this.executeRootAction({
      rootTaskId: taskId, actionType: "start", payload: {}, metadata,
      isEffectPresent: () => this.tasks.getById(taskId)?.state === "started",
      responseAfterReconciliation: () => true
    }, async (context) => {
      this.tasks.updateCommitmentState(taskId, { state: "started", startedAt: context.actionCreatedAt, incrementScheduleVersion: true, updatedAt: context.actionCreatedAt });
      return true;
    });
  }

  async done(taskId: string, now: string, action?: RootActionMetadata): Promise<void> {
    this.assertMirrorReady();
    const task = this.requireTask(taskId);
    const metadata = this.rootMetadata(action ?? {}, { occurrence: true });
    await this.executeRootAction({
      rootTaskId: taskId,
      actionType: "done",
      payload: { todoistTaskId: task.todoistTaskId, recurring: task.isRecurring },
      metadata,
      isEffectPresent: () => task.isRecurring
        ? this.hasAdvancedRecurringOccurrence(taskId, task)
        : this.hasAuthoritativelyCompleted(taskId),
      responseAfterReconciliation: () => true
    }, async (context) => {
      const current = this.requireCompletableTask(taskId);
      if (current.isRecurring) {
        await context.executeCommand("item_update_date_complete", (commandUuid) => this.todoist.itemUpdateDateComplete({ commandUuid, task: current }));
        return true;
      }
      await context.executeCommand("item_close", (commandUuid) => this.todoist.itemClose({ commandUuid, todoistTaskId: current.todoistTaskId }));
      return true;
    });
  }

  async postponeStart(taskId: string, now: string, action?: RootActionMetadata): Promise<{ snoozedUntil: string; escalationTriggered: boolean }> {
    this.assertMirrorReady();
    const metadata = this.rootMetadata(action ?? {}, { occurrence: true });
    return this.executeRootAction({
      rootTaskId: taskId, actionType: "postpone_start", payload: {}, metadata,
      isEffectPresent: () => this.tasks.getById(taskId)?.lastSnoozedAt !== null,
      responseAfterReconciliation: () => ({
        snoozedUntil: this.requireTask(taskId).lastSnoozedAt ?? addMinutes(now, 5),
        escalationTriggered: (this.tasks.getById(taskId)?.startDismissCount ?? 0) >= 2
      }),
      reconcile: async (context) => {
        const current = this.requireTask(taskId);
        await this.sendPostponeEscalation(taskId, current.startDismissCount, context);
      }
    }, async (context) => {
      const actionNow = context.actionCreatedAt;
      const snoozedUntil = addMinutes(actionNow, 5);
      const current = this.requireTask(taskId);
      const nextDismissCount = current.startDismissCount + 1;
      const escalationTriggered = nextDismissCount >= 2;
      this.tasks.updateCommitmentState(taskId, { startDismissDelta: 1, lastSnoozedAt: snoozedUntil, incrementScheduleVersion: true, updatedAt: actionNow });
      await this.sendPostponeEscalation(taskId, nextDismissCount, context);
      return { snoozedUntil, escalationTriggered };
    });
  }

  private async sendPostponeEscalation(
    taskId: string,
    dismissCount: number,
    context: TaskActionCommandContext
  ): Promise<void> {
    if (dismissCount < 2 || this.escalationService === null) {
      return;
    }
    if (this.requireTask(taskId).escalationSentAt !== null) {
      return;
    }
    await context.executeCommand("whatsapp_escalation", async () => this.escalationService?.sendForTask(taskId, dismissCount));
  }

  async extend(taskId: string, now: string, action?: RootActionMetadata): Promise<void> {
    this.assertMirrorReady();
    const task = this.requireSchedulableTask(taskId);
    if (task.isRecurring) {
      throw Object.assign(new Error("Recurring commitments cannot be extended"), {
        code: "recurring_extension_unsupported",
        statusCode: 422
      });
    }
    const newDuration = task.durationMinutes + 30;
    const metadata = this.rootMetadata(action ?? {}, { occurrence: true, revision: true });
    await this.executeRootAction({
      rootTaskId: taskId,
      actionType: "extend",
      payload: {},
      metadata,
      isEffectPresent: () => this.tasks.getById(taskId)?.durationMinutes === newDuration,
      responseAfterReconciliation: () => true
      , finalize: () => {
        this.tasks.updateCommitmentState(taskId, {
          state: "started",
          deadlineExtensionDelta: 1,
          incrementScheduleVersion: true,
          updatedAt: now
        });
      }
    }, async (context) => {
      let description: string;
      try {
        description = appendExtension({
          description: task.description,
          timestamp: context.actionCreatedAt,
          timeZone: task.dueTimezone,
          oldDurationMinutes: task.durationMinutes,
          newDurationMinutes: newDuration
        });
      } catch (error) {
        if (error instanceof Error && error.message === "activity_log_full") {
          throw Object.assign(new Error("Activity log has reached its description limit"), {
            code: "activity_log_full", statusCode: 422
          });
        }
        throw error;
      }
      await context.executeCommand("item_update", (commandUuid) => this.todoist.itemUpdate({
        commandUuid,
        todoistTaskId: task.todoistTaskId,
        description,
        duration: { amount: newDuration, unit: "minute" },
        due: { kind: "preserve" },
        assignment: { kind: "preserve" }
      }));
      return true;
    });
  }

  async reschedule(taskId: string, input: RescheduleTaskInput): Promise<void> {
    this.assertMirrorReady();
    const task = this.requireTask(taskId);
    const descriptionWasProvided = "description" in input;
    const normalizedDescription = descriptionWasProvided
      ? reconstructTaskDescription({
          ...decodeTaskDescription(task.description),
          userDescription: normalizeOptionalText(input.description ?? null) ?? ""
        })
      : undefined;
    const recurrence = recurrenceForReschedule(task, input.dueDatetime);
    await this.executeRootAction({
      rootTaskId: taskId,
      actionType: "reschedule",
      payload: { dueDatetime: input.dueDatetime, durationMinutes: input.durationMinutes },
      metadata: this.rootMetadata(input, { occurrence: true }),
      isEffectPresent: () => this.tasks.getById(taskId)?.dueDatetime === input.dueDatetime,
      responseAfterReconciliation: () => true
    }, async (context) => {
      await context.executeCommand("item_update", (commandUuid) => this.todoist.itemUpdate({
        commandUuid,
        todoistTaskId: task.todoistTaskId,
        ...(normalizedDescription === undefined ? {} : { description: normalizedDescription }),
        duration: { amount: input.durationMinutes, unit: "minute" },
        due: recurrence,
        assignment: { kind: "preserve" }
      }));
      return true;
    });
  }

  async update(taskId: string, request: UpdateTaskRequest): Promise<void | ChecklistMutationResult> {
    this.assertMirrorReady();
    const task = this.requireTask(taskId);
    const metadata = this.rootMetadata({
      actionId: request.actionId,
      deviceId: request.deviceId,
      expectedOccurrenceKey: request.expectedOccurrenceKey,
      ...(request.rootPatch === null ? {} : { expectedTaskRevision: request.rootPatch.expectedTaskRevision })
    }, { occurrence: true, revision: request.rootPatch !== null });
    const checklistAction = request.childMutations.length === 0
      ? undefined
      : this.checklistActionForRootEdit(metadata);
    if (checklistAction !== undefined) {
      this.assertChecklistDependencies({ action: checklistAction, mutations: request.childMutations });
    }
    if (request.rootPatch === null) {
      return checklistAction === undefined
        ? undefined
        : this.mutateChecklist(taskId, { action: checklistAction, mutations: request.childMutations });
    }

    const input = this.resolveRootEdit(task, request.rootPatch);
    const project = this.projects.getActiveByTodoistProjectId(input.projectId);
    if (project === null) {
      throw Object.assign(new Error("Project not found"), {
        code: "project_not_found",
        statusCode: 404
      });
    }

    const normalizedTitle = input.title.trim();
    const normalizedDescription = reconstructTaskDescription({
      ...decodeTaskDescription(task.description),
      userDescription: normalizeOptionalText(input.description) ?? ""
    });
    const errors: string[] = [];
    if (normalizedTitle.length === 0) {
      errors.push("title_required");
    }
    if (!Number.isInteger(input.durationMinutes) || input.durationMinutes <= 0) {
      errors.push("duration_must_be_positive_minutes");
    }
    if (project.canAssignTasks && input.assigneeId === null) {
      errors.push("assignee_required_for_assignable_project");
    }
    if (!project.canAssignTasks && input.assigneeId !== null) {
      errors.push("assignee_not_supported_for_project");
    }
    if (errors.length > 0) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors }
      });
    }

    const due = recurrenceForUpdate(task, input);
    const projectMoveRequiresAuthoritativeTree = metadata !== undefined && task.projectId !== input.projectId;
    const expectedDirectChildTodoistIds = projectMoveRequiresAuthoritativeTree
      ? this.directChildTodoistIds(task)
      : [];
    const scheduleChanged = task.dueDatetime !== input.dueDatetime
      || task.durationMinutes !== input.durationMinutes
      || task.isRecurring !== (due.kind === "replace" && due.string !== null)
      || task.dueString !== (due.kind === "replace" ? due.string : task.dueString)
      || task.dueTimezone !== (due.kind === "replace" ? due.timezone : task.dueTimezone);
    const wasOwned = task.assigneeId === this.currentUserId || task.commitmentOwnerId === this.currentUserId;
    const isOwned = (project.canAssignTasks && input.assigneeId === this.currentUserId) || !project.canAssignTasks;
    await this.executeRootAction({
      rootTaskId: taskId,
      actionType: "edit_task",
      payload: {
        title: normalizedTitle,
        projectId: input.projectId,
        assigneeId: input.assigneeId,
        due,
        durationMinutes: input.durationMinutes,
        childMutations: request.childMutations
      },
      metadata,
      isEffectPresent: () => {
        const current = this.tasks.getById(taskId);
        const rootMatches = current?.title === normalizedTitle
          && current.description === normalizedDescription
          && current.projectId === input.projectId
          && current.assigneeId === (project.canAssignTasks ? input.assigneeId : null)
          && current.dueDatetime === input.dueDatetime
          && current.durationMinutes === input.durationMinutes;
        return rootMatches && (!projectMoveRequiresAuthoritativeTree
          || this.hasVerifiedProjectMove(taskId, input.projectId, expectedDirectChildTodoistIds));
      },
      responseAfterReconciliation: () => true
    }, async (context) => {
      if (task.projectId !== input.projectId) {
        await context.executeCommand("item_move", (commandUuid) => this.todoist.itemMove({
          commandUuid,
          todoistTaskId: task.todoistTaskId,
          projectId: input.projectId
        }));
      }
      await context.executeCommand("item_update", (commandUuid) => this.todoist.itemUpdate({
        commandUuid,
        todoistTaskId: task.todoistTaskId,
        title: normalizedTitle,
        description: normalizedDescription,
        duration: { amount: input.durationMinutes, unit: "minute" },
        due,
        assignment: project.canAssignTasks && input.assigneeId !== null
          ? { kind: "assign", assigneeId: input.assigneeId }
          : { kind: "unassign" }
      }));
      return true;
    });
    if (checklistAction !== undefined) {
      return this.mutateChecklist(taskId, {
        action: checklistAction,
        mutations: request.childMutations
      });
    }
  }

  private resolveRootEdit(task: TaskWithCommitment, patch: RootEditPatch): ResolvedRootEdit {
    if (task.projectId === null && patch.projectId === undefined) {
      throw Object.assign(new Error("Root task must have a Todoist project before editing"), {
        code: "root_project_required",
        statusCode: 422
      });
    }
    if (task.dueDatetime === null && patch.dueDatetime === undefined) {
      throw Object.assign(new Error("Root task must have a deadline before editing"), {
        code: "task_missing_deadline_or_duration",
        statusCode: 422
      });
    }
    if (task.durationMinutes === null && patch.durationMinutes === undefined) {
      throw Object.assign(new Error("Root task must have a duration before editing"), {
        code: "task_missing_deadline_or_duration",
        statusCode: 422
      });
    }
    const decodedDescription = decodeTaskDescription(task.description);
    return {
      title: patch.title ?? task.title,
      description: Object.prototype.hasOwnProperty.call(patch, "description")
        ? patch.description ?? null
        : decodedDescription.userDescription,
      projectId: patch.projectId ?? task.projectId!,
      assigneeId: Object.prototype.hasOwnProperty.call(patch, "assigneeId") ? patch.assigneeId ?? null : task.assigneeId,
      dueDatetime: patch.dueDatetime ?? task.dueDatetime!,
      durationMinutes: patch.durationMinutes ?? task.durationMinutes!,
      ...(patch.recurrencePreset === undefined ? {} : { recurrencePreset: patch.recurrencePreset }),
      ...(patch.timeZone === undefined ? {} : { timeZone: patch.timeZone })
    };
  }

  private requireSchedulableTask(taskId: string): TaskWithCommitment & { dueDatetime: string; durationMinutes: number } {
    const task = this.requireTask(taskId);
    if (task.dueDatetime === null || task.durationMinutes === null) {
      throw Object.assign(new Error("Task is missing a deadline or duration"), {
        code: "task_missing_deadline_or_duration",
        statusCode: 422
      });
    }
    return task as TaskWithCommitment & { dueDatetime: string; durationMinutes: number };
  }

  private assertMirrorReady(): void {
    if (this.requireMirrorReady && !this.tasks.isMirrorContractReady()) {
      throw Object.assign(new Error("Todoist synchronization is initializing"), {
        code: "sync_initializing",
        statusCode: 503
      });
    }
  }

  private requireTask(taskId: string): TaskWithCommitment {
    const task = this.tasks.getById(taskId);
    if (task === null) {
      throw Object.assign(new Error("Task not found"), {
        code: "task_not_found",
        statusCode: 404
      });
    }
    return task;
  }

  private requireCompletableTask(taskId: string): TaskWithCommitment {
    const task = this.requireTask(taskId);
    if (task.completedAt !== null || task.deletedAt !== null || task.state === "completed" || task.state === "deleted") {
      throw Object.assign(new Error("Task occurrence is no longer current"), { code: "stale_occurrence", statusCode: 409 });
    }
    const tree = this.directChildTree(task);
    if (!tree.valid) {
      throw Object.assign(new Error("Task hierarchy is not supported"), { code: "unsupported_hierarchy", statusCode: 422 });
    }
    if (tree.children.some((child) => child.completedAt === null)) {
      throw Object.assign(new Error("Complete every checklist item first"), { code: "children_incomplete", statusCode: 422 });
    }
    return task;
  }

  private syncedCreatedRootId(actionId: string): string | null {
    const rootCommand = this.actionJournal.listCommands(actionId).find((command) => command.stepKey === "item_add");
    const todoistTaskId = rootCommand === undefined ? null : todoistTaskIdFromCommandResult(rootCommand.result);
    return todoistTaskId === null ? null : this.tasks.getByTodoistTaskId(todoistTaskId)?.id ?? null;
  }

  private hasAdvancedRecurringOccurrence(taskId: string, previous: TaskWithCommitment): boolean {
    const current = this.tasks.getById(taskId);
    return current !== null
      && current.isRecurring
      && current.occurrenceKey !== null
      && current.occurrenceKey !== previous.occurrenceKey
      && current.occurrenceDueDatetime !== previous.occurrenceDueDatetime;
  }

  private hasAuthoritativelyCompleted(taskId: string): boolean {
    const current = this.tasks.getById(taskId);
    return current?.completedAt !== null && current?.state === "completed";
  }

  private directChildTodoistIds(root: TaskWithCommitment): string[] {
    return this.directChildTree(root).children.map((child) => child.todoistTaskId);
  }

  private hasVerifiedProjectMove(
    taskId: string,
    targetProjectId: string,
    expectedDirectChildTodoistIds: readonly string[]
  ): boolean {
    const root = this.tasks.getById(taskId);
    if (root?.projectId !== targetProjectId) return false;
    const tree = this.directChildTree(root);
    const children = tree.children;
    return tree.valid
      && children.length === expectedDirectChildTodoistIds.length
      && expectedDirectChildTodoistIds.every((todoistTaskId) => children.some((child) => child.todoistTaskId === todoistTaskId))
      && children.every((child) => child.projectId === targetProjectId);
  }

  private directChildTree(root: TaskWithCommitment): { children: TaskWithCommitment[]; valid: boolean } {
    const all = this.tasks.listAll();
    const children = all.filter((candidate) => candidate.parentId === root.todoistTaskId && candidate.deletedAt === null);
    const childIds = new Set(children.map((child) => child.todoistTaskId));
    const hasGrandchild = all.some((candidate) => candidate.deletedAt === null && candidate.parentId !== null && childIds.has(candidate.parentId));
    const invalidChild = children.some((child) =>
      child.projectId !== root.projectId
      || child.dueDate !== null
      || child.dueDatetime !== null
      || child.dueString !== null
      || child.dueLang !== null
      || child.dueTimezone !== null
      || child.isRecurring
      || child.durationAmount !== null
      || child.durationUnit !== null
      || child.durationMinutes !== null
      || child.assigneeId !== null
    );
    return { children, valid: children.length <= 30 && !hasGrandchild && !invalidChild };
  }
}

function verifyCreatedTask(input: {
  task: TaskWithTodoistFields;
  expectedProjectId: string;
  expectedAssigneeId: string | null;
  expectedDueDatetime: string;
  expectedDurationMinutes: number;
}): string[] {
  const errors: string[] = [];
  if (input.task.projectId !== input.expectedProjectId) {
    errors.push("project_mismatch");
  }
  if (input.task.assigneeId !== input.expectedAssigneeId) {
    errors.push("assignee_mismatch");
  }
  if (!sameDueDatetime(input.task.dueDatetime, input.expectedDueDatetime)) {
    errors.push("due_datetime_mismatch");
  }
  if (input.task.durationMinutes !== input.expectedDurationMinutes) {
    errors.push("duration_mismatch");
  }
  return errors;
}

function sameDueDatetime(actual: string | null, expected: string): boolean {
  if (actual === expected) {
    return true;
  }
  if (actual === null) {
    return false;
  }
  const actualTimestamp = Date.parse(actual);
  const expectedTimestamp = Date.parse(expected);
  if (!Number.isFinite(actualTimestamp) || !Number.isFinite(expectedTimestamp)) {
    return false;
  }
  return Math.floor(actualTimestamp / 1000) === Math.floor(expectedTimestamp / 1000);
}

function createChildFailureCode(error: unknown): string {
  if (typeof error === "object" && error !== null && "code" in error && typeof error.code === "string") {
    return error.code;
  }
  return "todoist_child_create_failed";
}

interface TaskWithTodoistFields {
  title: string;
  description: string | null;
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: unknown;
}

function normalizeOptionalText(value: string | null): string | null {
  return value?.trim().length ? value.trim() : null;
}

function todoistTaskIdFromCommandResult(result: unknown): string | null {
  const value = typeof result === "object" && result !== null
    && "kind" in result && "value" in result && (result as { kind: unknown }).kind === "value"
    ? (result as { value: unknown }).value
    : result;
  if (typeof value !== "object" || value === null || !("todoistTaskId" in value)) return null;
  const todoistTaskId = (value as { todoistTaskId: unknown }).todoistTaskId;
  return typeof todoistTaskId === "string" ? todoistTaskId : null;
}

/** Stable UUIDs let a replay reconstruct the exact Todoist temp identity from an action ID. */
function actionStableUuid(actionId: string, stepKey: string): string {
  const hash = createHash("sha256").update(actionId).update("\0").update(stepKey).digest("hex");
  const variant = ((Number.parseInt(hash[16]!, 16) & 0b0011) | 0b1000).toString(16);
  return `${hash.slice(0, 8)}-${hash.slice(8, 12)}-5${hash.slice(13, 16)}-${variant}${hash.slice(17, 20)}-${hash.slice(20, 32)}`;
}

function isIanaTimeZone(value: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}

function isRecurrencePreset(value: unknown): value is RecurrencePreset {
  return value === "never"
    || value === "daily"
    || value === "weekdays"
    || value === "weekly"
    || value === "monthly"
    || value === "yearly";
}

function recurrenceForReschedule(task: TaskWithCommitment, dueDatetime: string) {
  if (!task.isRecurring) {
    return { kind: "replace" as const, datetime: dueDatetime, string: null, lang: null, timezone: null };
  }
  const description = describeRecurrence({
    isRecurring: task.isRecurring,
    rule: task.dueString,
    language: task.dueLang,
    timeZone: task.dueTimezone
  });
  if (description.kind !== "supported" || description.preset === null || task.dueTimezone === null) {
    throw Object.assign(new Error("Custom Todoist recurrence must be replaced before changing its schedule"), {
      code: "custom_recurrence_schedule_change_requires_replacement",
      statusCode: 422
    });
  }
  const recurrence = translateRecurrencePreset({
    preset: description.preset,
    selectedStart: dueDatetime,
    timeZone: task.dueTimezone
  });
  if (recurrence === null) {
    throw new Error("supported_recurrence_translation_missing");
  }
  return { kind: "replace" as const, datetime: dueDatetime, ...recurrence };
}

function recurrenceForUpdate(task: TaskWithCommitment, input: ResolvedRootEdit) {
  const scheduleChanged = task.dueDatetime !== input.dueDatetime || task.durationMinutes !== input.durationMinutes;
  if (input.recurrencePreset !== undefined) {
    const timeZone = input.timeZone ?? task.dueTimezone ?? "UTC";
    if (!isIanaTimeZone(timeZone)) {
      throw Object.assign(new Error("Time zone must be an IANA time zone"), { code: "time_zone_invalid", statusCode: 422 });
    }
    const recurrence = translateRecurrencePreset({
      preset: input.recurrencePreset,
      selectedStart: input.dueDatetime,
      timeZone
    });
    return recurrence === null
      ? { kind: "replace" as const, datetime: input.dueDatetime, string: null, lang: null, timezone: null }
      : { kind: "replace" as const, datetime: input.dueDatetime, ...recurrence };
  }
  if (!task.isRecurring) {
    return { kind: "replace" as const, datetime: input.dueDatetime, string: null, lang: null, timezone: null };
  }
  if (scheduleChanged) {
    return recurrenceForReschedule(task, input.dueDatetime);
  }
  return { kind: "preserve" as const };
}
