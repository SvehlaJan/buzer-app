import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { addMinutes, sameDueInstant } from "../domain/time.js";
import { appendExtension, decodeTaskDescription, reconstructTaskDescription } from "../domain/taskDescriptionCodec.js";
import { describeRecurrence, translateRecurrencePreset, type RecurrencePreset } from "../domain/taskRecurrence.js";
import { resolveOwnerTimeZone } from "../domain/ownerTimeZone.js";
import { DomainError } from "../domain/errors.js";
import type { ProjectRepository, TodoistProject } from "../repositories/projectRepository.js";
import { CollaboratorRepository } from "../repositories/collaboratorRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import { TodoistChecklistService, type ChildMutation, type ChecklistMutationRequest } from "./todoistChecklistService.js";
import { TaskMutationRunner, type MutationRunResult, type TaskSnapshot } from "./taskMutationRunner.js";
import type { TaskSnapshotService } from "./taskSnapshotService.js";
import type { EscalationService } from "./escalationService.js";
import { type BackendObservability, backendObservability } from "../diagnostics/backendObservability.js";
import type { TodoistAdapter, TodoistCommand } from "../todoist/todoistAdapter.js";
import { TodoistOperationQueue } from "./todoistOperationQueue.js";
import { TodoistCommandExecutor } from "./todoistCommandExecutor.js";

export interface CreateTaskInput {
  title: string;
  projectId: string;
  assigneeId?: string | null | undefined;
  dueDatetime?: string | null | undefined;
  durationMinutes?: number | null | undefined;
  description?: string | null | undefined;
  recurrencePreset?: RecurrencePreset | undefined;
  timeZone?: string | undefined;
  checklist?: ReadonlyArray<{ clientMutationId?: string | undefined; title: string; description?: string | null | undefined }> | undefined;
  creatorId?: string | undefined;
  now?: string | undefined;
  requestId?: string | undefined;
  signal?: AbortSignal | undefined;
}

export interface RootEditPatch {
  expectedTaskRevision: string;
  title?: string | undefined;
  description?: string | null | undefined;
  projectId?: string | undefined;
  assigneeId?: string | null | undefined;
  dueDatetime?: string | null | undefined;
  durationMinutes?: number | null | undefined;
  recurrencePreset?: RecurrencePreset | undefined;
  timeZone?: string | undefined;
}

export interface UpdateTaskRequest {
  expectedOccurrenceKey?: string | null | undefined;
  rootPatch: RootEditPatch | null;
  childMutations?: readonly ChildMutation[] | undefined;
  requestId?: string | undefined;
  signal?: AbortSignal | undefined;
}

export interface StartTaskOptions {
  expectedOccurrenceKey?: string | null;
  expectedTaskRevision?: string;
  now?: string;
}

export interface PostponeTaskOptions {
  expectedOccurrenceKey?: string | null;
  expectedTaskRevision?: string;
  now?: string;
}

export interface CompleteTaskOptions {
  expectedOccurrenceKey?: string | null;
  now?: string;
  requestId?: string;
  signal?: AbortSignal;
}

export interface ExtendTaskOptions {
  expectedOccurrenceKey?: string | null;
  expectedTaskRevision?: string;
  now?: string;
  requestId?: string;
  signal?: AbortSignal;
}

interface ResolvedRootEdit {
  title: string;
  description: string | null;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  recurrencePreset?: RecurrencePreset;
  timeZone?: string;
}

export class TaskActionService {
  private readonly runner: TaskMutationRunner;
  private readonly snapshotProvider: () => TaskSnapshot;
  private readonly checklistService: TodoistChecklistService;
  private readonly collaborators: CollaboratorRepository;

  private readonly currentUserId: string;
  private readonly escalationService: EscalationService | null;
  private readonly requireMirrorReady: boolean;
  private readonly observability: BackendObservability;

  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    runnerOrAdapter: TaskMutationRunner | TodoistAdapter,
    snapshotsOrUserId?: TaskSnapshotService | string,
    currentUserIdOrEscalation?: string | EscalationService | null,
    escalationServiceOrMirrorReady?: EscalationService | null | boolean,
    requireMirrorReadyOrOptions?: boolean | unknown,
    observability: BackendObservability = backendObservability
  ) {
    if (typeof snapshotsOrUserId === "string") {
      this.currentUserId = snapshotsOrUserId;
      this.escalationService = (currentUserIdOrEscalation as EscalationService) ?? null;
      this.requireMirrorReady = typeof escalationServiceOrMirrorReady === "boolean" ? escalationServiceOrMirrorReady : false;
      this.observability = (requireMirrorReadyOrOptions as BackendObservability) ?? observability ?? backendObservability;
      this.snapshotProvider = () => ({ tasks: this.tasks.listAll() }) as unknown as TaskSnapshot;
    } else {
      if (typeof snapshotsOrUserId === "function") {
        this.snapshotProvider = snapshotsOrUserId as () => TaskSnapshot;
      } else if (snapshotsOrUserId && typeof snapshotsOrUserId === "object" && "getSnapshot" in snapshotsOrUserId) {
        const snapService = snapshotsOrUserId as TaskSnapshotService;
        this.snapshotProvider = () => snapService.getSnapshot(new Date().toISOString());
      } else {
        this.snapshotProvider = () => ({ tasks: this.tasks.listAll() }) as unknown as TaskSnapshot;
      }
      this.currentUserId = typeof currentUserIdOrEscalation === "string" ? currentUserIdOrEscalation : "";
      this.escalationService = (escalationServiceOrMirrorReady as EscalationService) ?? null;
      this.requireMirrorReady = typeof requireMirrorReadyOrOptions === "boolean" ? requireMirrorReadyOrOptions : false;
      this.observability = observability;
    }

    if (runnerOrAdapter instanceof TaskMutationRunner || (runnerOrAdapter && "execute" in runnerOrAdapter)) {
      this.runner = runnerOrAdapter as TaskMutationRunner;
    } else {
      const queue = new TodoistOperationQueue();
      const executor = new TodoistCommandExecutor(runnerOrAdapter);
      this.runner = new TaskMutationRunner({
        queue,
        executor,
        syncService: { syncOnce: async () => undefined },
        snapshotService: this.snapshotProvider,
        observability: this.observability
      });
    }

    this.checklistService = new TodoistChecklistService(this.tasks, this.runner, this.snapshotProvider);
    this.collaborators = new CollaboratorRepository(db);
  }

  async createTask(input: CreateTaskInput): Promise<MutationRunResult> {
    this.assertMirrorReady();
    const project = this.projects.getActiveByTodoistProjectId(input.projectId);
    if (project === null) {
      throw Object.assign(new Error("Project not found"), {
        code: "project_not_found",
        statusCode: 404
      });
    }

    const recurrencePreset = input.recurrencePreset ?? "never";
    const timeZone = input.timeZone ?? "UTC";
    const checklist = input.checklist ?? [];
    const errors = [
      ...this.projectAssignmentErrors(project, input.assigneeId ?? null),
      ...scheduleErrors({
        dueDatetime: input.dueDatetime ?? null,
        durationMinutes: input.durationMinutes ?? null,
        required: scheduleIsRequired(project.canAssignTasks, input.assigneeId ?? null, this.currentUserId)
      })
    ];
    if (input.title.trim().length === 0) {
      errors.push("title_required");
    }
    if ((input.dueDatetime === null || input.dueDatetime === undefined) && (input.durationMinutes === null || input.durationMinutes === undefined) && recurrencePreset !== "never") {
      errors.push("recurrence_requires_schedule");
    }
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

    const dueDatetime = input.dueDatetime ?? null;
    const durationMinutes = input.durationMinutes ?? null;
    const scheduled = dueDatetime !== null && durationMinutes !== null;
    const recurrence = scheduled
      ? translateRecurrencePreset({ preset: recurrencePreset, selectedStart: dueDatetime!, timeZone })
      : null;

    const rootTempId = randomUUID();
    const commands: TodoistCommand[] = [];

    commands.push({
      type: "item_add",
      input: {
        kind: "root",
        tempId: rootTempId,
        title: input.title.trim(),
        description: normalizeOptionalText(input.description ?? null),
        projectId: input.projectId,
        assigneeId: project.canAssignTasks ? input.assigneeId ?? null : null,
        due: !scheduled
          ? { kind: "preserve" }
          : recurrence === null
            ? { kind: "replace", datetime: dueDatetime!, string: null, lang: null, timezone: null }
            : { kind: "replace", datetime: dueDatetime!, ...recurrence },
        duration: scheduled
          ? { kind: "replace", amount: durationMinutes!, unit: "minute" }
          : null
      }
    });

    for (const child of checklist) {
      const childTitle = child.title.trim();
      const childTempId = randomUUID();
      commands.push({
        type: "item_add",
        input: {
          kind: "child",
          tempId: childTempId,
          title: childTitle,
          description: normalizeOptionalText(child.description ?? null),
          projectId: input.projectId,
          parentTodoistTaskId: rootTempId
        }
      });
    }

    return await this.runner.execute({
      operation: "create_task",
      ...(input.requestId !== undefined ? { requestId: input.requestId } : {}),
      commands,
      ...(input.signal !== undefined ? { signal: input.signal } : {})
    });
  }

  async startTask(taskId: string, nowOrOptions?: string | StartTaskOptions, options?: StartTaskOptions): Promise<{ kind: "success"; snapshot: TaskSnapshot }> {
    this.assertMirrorReady();
    const opts: StartTaskOptions = typeof nowOrOptions === "object" ? nowOrOptions : options ?? {};
    const now = typeof nowOrOptions === "string" ? nowOrOptions : opts.now ?? new Date().toISOString();

    const task = this.requireTask(taskId);
    if (task.completedAt !== null || task.deletedAt !== null) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }
    if (opts.expectedOccurrenceKey !== undefined && opts.expectedOccurrenceKey !== null && opts.expectedOccurrenceKey !== task.occurrenceKey) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }
    if (opts.expectedTaskRevision !== undefined && this.parseRevision(opts.expectedTaskRevision) !== task.mirrorRevision) {
      throw DomainError.staleTask(this.getSnapshot());
    }

    this.tasks.updateCommitmentState(taskId, {
      state: "started",
      startedAt: now,
      incrementScheduleVersion: true,
      updatedAt: now
    });

    return {
      kind: "success",
      snapshot: this.getSnapshot()
    };
  }

  async postponeTask(
    taskId: string,
    nowOrOptions?: string | PostponeTaskOptions,
    options?: PostponeTaskOptions
  ): Promise<{ kind: "success"; snapshot: TaskSnapshot; snoozedUntil: string; escalationTriggered: boolean }> {
    this.assertMirrorReady();
    const opts: PostponeTaskOptions = typeof nowOrOptions === "object" ? nowOrOptions : options ?? {};
    const now = typeof nowOrOptions === "string" ? nowOrOptions : opts.now ?? new Date().toISOString();

    const task = this.requireTask(taskId);
    if (task.completedAt !== null || task.deletedAt !== null) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }
    if (opts.expectedOccurrenceKey !== undefined && opts.expectedOccurrenceKey !== null && opts.expectedOccurrenceKey !== task.occurrenceKey) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }
    if (opts.expectedTaskRevision !== undefined && this.parseRevision(opts.expectedTaskRevision) !== task.mirrorRevision) {
      throw DomainError.staleTask(this.getSnapshot());
    }

    const snoozedUntil = addMinutes(now, 5);
    const nextDismissCount = task.startDismissCount + 1;
    const escalationTriggered = nextDismissCount >= 2;

    this.tasks.updateCommitmentState(taskId, {
      startDismissDelta: 1,
      lastSnoozedAt: snoozedUntil,
      incrementScheduleVersion: true,
      updatedAt: now
    });

    if (escalationTriggered && this.escalationService !== null && task.escalationSentAt === null) {
      await this.escalationService.handleSecondDismissal(taskId);
    }

    return {
      kind: "success",
      snapshot: this.getSnapshot(),
      snoozedUntil,
      escalationTriggered
    };
  }

  async completeTask(taskId: string, nowOrOptions?: string | CompleteTaskOptions, options?: CompleteTaskOptions): Promise<MutationRunResult> {
    this.assertMirrorReady();
    const opts: CompleteTaskOptions = typeof nowOrOptions === "object" ? nowOrOptions : options ?? {};

    const task = this.requireCompletableTask(taskId);
    if (opts.expectedOccurrenceKey !== undefined && opts.expectedOccurrenceKey !== null && opts.expectedOccurrenceKey !== task.occurrenceKey) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }

    const commands: TodoistCommand[] = [];
    if (task.isRecurring) {
      commands.push({
        type: "item_close",
        input: {
          todoistTaskId: task.todoistTaskId,
          resetSubtasks: true
        }
      });
    } else {
      commands.push({
        type: "item_close",
        input: {
          todoistTaskId: task.todoistTaskId
        }
      });
    }

    return await this.runner.execute({
      operation: "complete_task",
      ...(opts.requestId !== undefined ? { requestId: opts.requestId } : {}),
      taskId,
      ...(task.occurrenceKey !== null ? { occurrenceKey: task.occurrenceKey } : {}),
      prepareCommands: () => {
        const current = this.requireCompletableTask(taskId);
        if (
          opts.expectedOccurrenceKey !== undefined &&
          opts.expectedOccurrenceKey !== null &&
          opts.expectedOccurrenceKey !== current.occurrenceKey
        ) {
          throw DomainError.staleOccurrence(this.getSnapshot());
        }
        return commands;
      },
      ...(opts.signal !== undefined ? { signal: opts.signal } : {})
    });
  }

  async extendTask(taskId: string, nowOrOptions?: string | ExtendTaskOptions, options?: ExtendTaskOptions): Promise<MutationRunResult> {
    this.assertMirrorReady();
    const opts: ExtendTaskOptions = typeof nowOrOptions === "object" ? nowOrOptions : options ?? {};
    const now = typeof nowOrOptions === "string" ? nowOrOptions : opts.now ?? new Date().toISOString();

    const task = this.requireSchedulableTask(taskId);
    if (task.isRecurring) {
      throw Object.assign(new Error("Recurring commitments cannot be extended"), {
        code: "recurring_extension_unsupported",
        statusCode: 422
      });
    }
    if (opts.expectedOccurrenceKey !== undefined && opts.expectedOccurrenceKey !== null && opts.expectedOccurrenceKey !== task.occurrenceKey) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }
    if (opts.expectedTaskRevision !== undefined && this.parseRevision(opts.expectedTaskRevision) !== task.mirrorRevision) {
      throw DomainError.staleTask(this.getSnapshot());
    }

    const newDuration = task.durationMinutes + 30;
    let description: string;
    try {
      description = appendExtension({
        description: task.description,
        timestamp: now,
        timeZone: task.dueTimezone,
        oldDurationMinutes: task.durationMinutes,
        newDurationMinutes: newDuration
      });
    } catch (error) {
      if (error instanceof Error && error.message === "activity_log_full") {
        throw Object.assign(new Error("Activity log has reached its description limit"), {
          code: "activity_log_full",
          statusCode: 422
        });
      }
      throw error;
    }

    const commands: TodoistCommand[] = [{
      type: "item_update",
      input: {
        todoistTaskId: task.todoistTaskId,
        description,
        duration: { kind: "replace", amount: newDuration, unit: "minute" },
        due: { kind: "preserve" },
        assignment: { kind: "preserve" }
      }
    }];

    return await this.runner.execute({
      operation: "extend_task",
      ...(opts.requestId !== undefined ? { requestId: opts.requestId } : {}),
      taskId,
      ...(task.occurrenceKey !== null ? { occurrenceKey: task.occurrenceKey } : {}),
      prepareCommands: () => {
        const current = this.requireSchedulableTask(taskId);
        if (current.isRecurring) {
          throw Object.assign(new Error("Recurring commitments cannot be extended"), {
            code: "recurring_extension_unsupported",
            statusCode: 422
          });
        }
        if (
          opts.expectedOccurrenceKey !== undefined &&
          opts.expectedOccurrenceKey !== null &&
          opts.expectedOccurrenceKey !== current.occurrenceKey
        ) {
          throw DomainError.staleOccurrence(this.getSnapshot());
        }
        if (
          opts.expectedTaskRevision !== undefined &&
          this.parseRevision(opts.expectedTaskRevision) !== current.mirrorRevision
        ) {
          throw DomainError.staleTask(this.getSnapshot());
        }
        return commands;
      },
      ...(opts.signal !== undefined ? { signal: opts.signal } : {})
    });
  }

  async editTask(taskId: string, request: UpdateTaskRequest): Promise<MutationRunResult> {
    this.assertMirrorReady();
    const task = this.requireTask(taskId);

    if (request.expectedOccurrenceKey !== undefined && request.expectedOccurrenceKey !== null && request.expectedOccurrenceKey !== task.occurrenceKey) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }

    const childMutations = request.childMutations ?? [];

    if (request.rootPatch === null) {
      if (childMutations.length === 0) {
        return { kind: "success", snapshot: this.getSnapshot() };
      }
      return this.checklistService.mutate(taskId, {
        mutations: childMutations,
        expectedOccurrenceKey: request.expectedOccurrenceKey,
        requestId: request.requestId,
        signal: request.signal
      });
    }

    if (this.parseRevision(request.rootPatch.expectedTaskRevision) !== task.mirrorRevision) {
      throw DomainError.staleTask(this.getSnapshot());
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
    const errors = [
      ...this.projectAssignmentErrors(project, input.assigneeId),
      ...scheduleErrors({
        dueDatetime: input.dueDatetime,
        durationMinutes: input.durationMinutes,
        required: scheduleIsRequired(project.canAssignTasks, input.assigneeId, this.currentUserId)
      })
    ];
    if (normalizedTitle.length === 0) {
      errors.push("title_required");
    }
    if (input.dueDatetime === null && input.durationMinutes === null && input.recurrencePreset !== undefined && input.recurrencePreset !== "never") {
      errors.push("recurrence_requires_schedule");
    }
    if (errors.length > 0) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors }
      });
    }

    const due = recurrenceForUpdate(task, input, resolveOwnerTimeZone(this.collaborators, this.currentUserId));
    const commands: TodoistCommand[] = [];

    if (task.projectId !== input.projectId) {
      commands.push({
        type: "item_move",
        input: {
          todoistTaskId: task.todoistTaskId,
          projectId: input.projectId
        }
      });
    }

    commands.push({
      type: "item_update",
      input: {
        todoistTaskId: task.todoistTaskId,
        title: normalizedTitle,
        description: normalizedDescription,
        duration: input.durationMinutes === null
          ? { kind: "clear" }
          : { kind: "replace", amount: input.durationMinutes, unit: "minute" },
        due,
        assignment: project.canAssignTasks && input.assigneeId !== null
          ? { kind: "assign", assigneeId: input.assigneeId }
          : { kind: "unassign" }
      }
    });

    if (childMutations.length > 0) {
      this.checklistService.validateMutations(task, childMutations);
      const childCommands = this.checklistService.buildChildCommands(task, childMutations);
      commands.push(...childCommands);
    }

    return await this.runner.execute({
      operation: "edit_task",
      ...(request.requestId !== undefined ? { requestId: request.requestId } : {}),
      taskId,
      ...(task.occurrenceKey !== null ? { occurrenceKey: task.occurrenceKey } : {}),
      prepareCommands: () => {
        const current = this.requireTask(taskId);
        if (
          request.expectedOccurrenceKey !== undefined &&
          request.expectedOccurrenceKey !== null &&
          request.expectedOccurrenceKey !== current.occurrenceKey
        ) {
          throw DomainError.staleOccurrence(this.getSnapshot());
        }
        if (this.parseRevision(request.rootPatch!.expectedTaskRevision) !== current.mirrorRevision) {
          throw DomainError.staleTask(this.getSnapshot());
        }
        if (childMutations.length > 0) {
          this.checklistService.validateMutations(current, childMutations);
        }
        return commands;
      },
      ...(request.signal !== undefined ? { signal: request.signal } : {})
    });
  }

  async mutateChecklist(taskId: string, request: ChecklistMutationRequest): Promise<MutationRunResult> {
    this.assertMirrorReady();
    return this.checklistService.mutate(taskId, request);
  }

  // Aliases for compatibility
  async create(input: CreateTaskInput): Promise<MutationRunResult> {
    return this.createTask(input);
  }

  async start(taskId: string, now?: string, options?: StartTaskOptions): Promise<{ kind: "success"; snapshot: TaskSnapshot }> {
    return this.startTask(taskId, now, options);
  }

  async postponeStart(
    taskId: string,
    now?: string,
    options?: PostponeTaskOptions
  ): Promise<{ kind: "success"; snapshot: TaskSnapshot; snoozedUntil: string; escalationTriggered: boolean }> {
    return this.postponeTask(taskId, now, options);
  }

  async done(taskId: string, now?: string, options?: CompleteTaskOptions): Promise<MutationRunResult> {
    return this.completeTask(taskId, now, options);
  }

  async extend(taskId: string, now?: string, options?: ExtendTaskOptions): Promise<MutationRunResult> {
    return this.extendTask(taskId, now, options);
  }

  async update(taskId: string, request: UpdateTaskRequest): Promise<MutationRunResult> {
    return this.editTask(taskId, request);
  }

  private resolveRootEdit(task: TaskWithCommitment, patch: RootEditPatch): ResolvedRootEdit {
    if (task.projectId === null && patch.projectId === undefined) {
      throw Object.assign(new Error("Root task must have a Todoist project before editing"), {
        code: "root_project_required",
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
      dueDatetime: Object.prototype.hasOwnProperty.call(patch, "dueDatetime")
        ? patch.dueDatetime ?? null
        : task.dueDatetime,
      durationMinutes: Object.prototype.hasOwnProperty.call(patch, "durationMinutes")
        ? patch.durationMinutes ?? null
        : task.durationMinutes,
      ...(patch.recurrencePreset === undefined ? {} : { recurrencePreset: patch.recurrencePreset }),
      ...(patch.timeZone === undefined ? {} : { timeZone: patch.timeZone })
    };
  }

  private projectAssignmentErrors(project: TodoistProject, assigneeId: string | null): string[] {
    if (!project.canAssignTasks) {
      return assigneeId === null ? [] : ["assignee_not_supported_for_project"];
    }
    if (assigneeId === null) {
      return ["assignee_required_for_assignable_project"];
    }
    if (!this.collaborators.isMirrorContractReady()) {
      throw Object.assign(new Error("Project collaborator data is unavailable"), {
        code: "collaborators_unavailable",
        statusCode: 503
      });
    }
    if (assigneeId !== this.currentUserId && !this.collaborators.isActiveMember(project.todoistProjectId, assigneeId)) {
      return ["assignee_not_in_project"];
    }
    return [];
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
      throw DomainError.staleOccurrence(this.getSnapshot());
    }
    const tree = this.directChildTree(task);
    if (!tree.valid) {
      throw DomainError.unsupportedHierarchy(this.getSnapshot());
    }
    if (tree.children.some((child) => child.completedAt === null)) {
      throw DomainError.childrenIncomplete(this.getSnapshot());
    }
    return task;
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

  private parseRevision(value: string): number {
    const match = /^r:(\d+)$/.exec(value);
    if (match === null) throw DomainError.staleTask(this.getSnapshot());
    return Number(match[1]);
  }

  private getSnapshot(): TaskSnapshot {
    return this.snapshotProvider();
  }
}

function normalizeOptionalText(value: string | null): string | null {
  return value?.trim().length ? value.trim() : null;
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

function scheduleIsRequired(
  canAssignTasks: boolean,
  assigneeId: string | null,
  currentUserId: string
): boolean {
  return !canAssignTasks || assigneeId === currentUserId;
}

function scheduleErrors(input: {
  dueDatetime: string | null;
  durationMinutes: number | null;
  required: boolean;
}): string[] {
  const errors: string[] = [];
  const hasDue = input.dueDatetime !== null;
  const hasDuration = input.durationMinutes !== null;
  if (hasDue !== hasDuration) {
    errors.push("schedule_fields_must_be_set_together");
  }
  if (input.required && !hasDue) {
    errors.push("due_datetime_required");
  }
  if (input.required && !hasDuration) {
    errors.push("duration_required");
  }
  if (input.durationMinutes !== null && (!Number.isInteger(input.durationMinutes) || input.durationMinutes <= 0)) {
    errors.push("duration_must_be_positive_minutes");
  }
  return errors;
}

function recurrenceForScheduleUpdate(task: TaskWithCommitment, dueDatetime: string) {
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

function recurrenceForUpdate(task: TaskWithCommitment, input: ResolvedRootEdit, ownerTimeZone: string) {
  const scheduleChanged = !sameDueInstant(task.dueDatetime, input.dueDatetime, ownerTimeZone)
    || task.durationMinutes !== input.durationMinutes;
  if (input.dueDatetime === null || input.durationMinutes === null) {
    return { kind: "clear" as const };
  }
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
  if (!scheduleChanged) {
    return { kind: "preserve" as const };
  }
  if (!task.isRecurring) {
    return { kind: "replace" as const, datetime: input.dueDatetime, string: null, lang: null, timezone: null };
  }
  return recurrenceForScheduleUpdate(task, input.dueDatetime);
}
