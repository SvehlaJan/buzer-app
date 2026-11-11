import { randomUUID } from "node:crypto";
import { addMinutes, sameDueInstant } from "../domain/time.js";
import { appendExtension, decodeTaskDescription, reconstructTaskDescription } from "../domain/taskDescriptionCodec.js";
import { describeRecurrence, translateRecurrencePreset, type RecurrencePreset } from "../domain/taskRecurrence.js";
import { resolveOwnerTimeZone } from "../domain/ownerTimeZone.js";
import { CommitmentPolicy, MAX_CHECKLIST_CHILDREN } from "../domain/commitmentPolicy.js";
import { DomainError } from "../domain/errors.js";
import type { CollaboratorRepository } from "../repositories/collaboratorRepository.js";
import type { ProjectRepository, TodoistProject } from "../repositories/projectRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistCommand } from "../todoist/todoistAdapter.js";
import type { EscalationService } from "./escalationService.js";
import type { MutationRunResult, SnapshotProvider, TaskSnapshot } from "./taskMutationRunner.js";
import type { TodoistRuntime } from "./todoistRuntime.js";

export type ChildMutation =
  | { type: "add"; clientMutationId: string; title: string; description?: string | null | undefined }
  | {
      type: "edit";
      clientMutationId: string;
      childId: string;
      expectedChildRevision: string;
      title: string;
      description?: string | null | undefined;
    }
  | { type: "delete"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "check"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "uncheck"; clientMutationId: string; childId: string; expectedChildRevision: string };

export interface ChecklistItemToggleOptions {
  expectedOccurrenceKey?: string | null | undefined;
  expectedChildRevision?: string | undefined;
  requestId?: string | undefined;
  signal?: AbortSignal | undefined;
  deadlineMs?: number | undefined;
  minimumExecutionBudgetMs?: number | undefined;
}

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
  expectedOccurrenceKey?: string | null | undefined;
  expectedTaskRevision?: string | undefined;
  now?: string | undefined;
}

export interface PostponeTaskOptions {
  expectedOccurrenceKey?: string | null | undefined;
  expectedTaskRevision?: string | undefined;
  now?: string | undefined;
}

export interface CompleteTaskOptions {
  expectedOccurrenceKey?: string | null | undefined;
  now?: string | undefined;
  requestId?: string | undefined;
  signal?: AbortSignal | undefined;
}

export interface ExtendTaskOptions {
  expectedOccurrenceKey?: string | null | undefined;
  expectedTaskRevision?: string | undefined;
  now?: string | undefined;
  requestId?: string | undefined;
  signal?: AbortSignal | undefined;
}

export interface TaskActionServiceDependencies {
  tasks: TaskRepository;
  projects: ProjectRepository;
  collaborators?: CollaboratorRepository | undefined;
  snapshots: SnapshotProvider;
  runtime: Pick<TodoistRuntime, "executeMutation">;
  escalationService?: EscalationService | null | undefined;
  currentUserId?: string | undefined;
  requireMirrorReady?: boolean | undefined;
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
  private readonly tasks: TaskRepository;
  private readonly projects: ProjectRepository;
  private readonly collaborators: CollaboratorRepository | null;
  private readonly snapshotProvider: () => TaskSnapshot;
  private readonly runtime: Pick<TodoistRuntime, "executeMutation">;
  private readonly currentUserId: string;
  private readonly escalationService: EscalationService | null;
  private readonly requireMirrorReady: boolean;

  constructor(deps: TaskActionServiceDependencies) {
    this.tasks = deps.tasks;
    this.projects = deps.projects;
    this.collaborators = deps.collaborators ?? null;
    this.runtime = deps.runtime;
    this.escalationService = deps.escalationService ?? null;
    this.currentUserId = deps.currentUserId ?? "";
    this.requireMirrorReady = deps.requireMirrorReady ?? false;

    const snapshots = deps.snapshots;
    this.snapshotProvider = typeof snapshots === "function"
      ? snapshots
      : () => snapshots.getSnapshot(new Date().toISOString());
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
    if (checklist.length > MAX_CHECKLIST_CHILDREN) {
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

    return await this.runtime.executeMutation({
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

    return await this.runtime.executeMutation({
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

    return await this.runtime.executeMutation({
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
    const childMutations = request.childMutations ?? [];
    if (childMutations.length > 0) {
      assertChildMutationsShape(childMutations);
    }

    const task = this.requireTask(taskId);

    if (request.expectedOccurrenceKey !== undefined && request.expectedOccurrenceKey !== null && request.expectedOccurrenceKey !== task.occurrenceKey) {
      throw DomainError.staleOccurrence(this.getSnapshot());
    }

    if (request.rootPatch === null) {
      if (childMutations.length === 0) {
        return { kind: "success", snapshot: this.getSnapshot() };
      }
      return await this.runtime.executeMutation({
        operation: "mutate_checklist",
        ...(request.requestId !== undefined ? { requestId: request.requestId } : {}),
        taskId,
        ...(request.expectedOccurrenceKey !== undefined && request.expectedOccurrenceKey !== null
          ? { occurrenceKey: request.expectedOccurrenceKey }
          : task.occurrenceKey !== null
            ? { occurrenceKey: task.occurrenceKey }
            : {}),
        prepareCommands: () => {
          const { root: current, directChildren } = this.assertProjectedTree(taskId);
          if (
            request.expectedOccurrenceKey !== undefined &&
            request.expectedOccurrenceKey !== null &&
            request.expectedOccurrenceKey !== current.occurrenceKey
          ) {
            throw DomainError.staleOccurrence(this.getSnapshot());
          }
          this.validateChildMutations(current, directChildren.length, childMutations);
          return this.buildChildCommands(current, childMutations);
        },
        ...(request.signal !== undefined ? { signal: request.signal } : {})
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
    const rootCommands: TodoistCommand[] = [];

    if (task.projectId !== input.projectId) {
      rootCommands.push({
        type: "item_move",
        input: {
          todoistTaskId: task.todoistTaskId,
          projectId: input.projectId
        }
      });
    }

    rootCommands.push({
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
      const { root: checkedRoot, directChildren } = this.assertProjectedTree(taskId);
      this.validateChildMutations(checkedRoot, directChildren.length, childMutations, input.projectId);
    }

    return await this.runtime.executeMutation({
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
        if (childMutations.length === 0) {
          return rootCommands;
        }
        const { directChildren } = this.assertProjectedTree(taskId);
        this.validateChildMutations(current, directChildren.length, childMutations, input.projectId);
        return [...rootCommands, ...this.buildChildCommands(current, childMutations, input.projectId)];
      },
      ...(request.signal !== undefined ? { signal: request.signal } : {})
    });
  }

  async checkChecklistItem(
    rootId: string,
    childTodoistId: string,
    options?: ChecklistItemToggleOptions
  ): Promise<MutationRunResult> {
    return await this.toggleChecklistItem(rootId, childTodoistId, "check", options);
  }

  async uncheckChecklistItem(
    rootId: string,
    childTodoistId: string,
    options?: ChecklistItemToggleOptions
  ): Promise<MutationRunResult> {
    return await this.toggleChecklistItem(rootId, childTodoistId, "uncheck", options);
  }

  private async toggleChecklistItem(
    rootId: string,
    childTodoistId: string,
    action: "check" | "uncheck",
    options?: ChecklistItemToggleOptions
  ): Promise<MutationRunResult> {
    this.assertMirrorReady();
    if (typeof childTodoistId !== "string" || childTodoistId.trim().length === 0) {
      throw invalidChildMutation();
    }

    return await this.runtime.executeMutation({
      operation: "mutate_checklist",
      ...(options?.requestId !== undefined ? { requestId: options.requestId } : {}),
      taskId: rootId,
      ...(options?.expectedOccurrenceKey !== undefined && options.expectedOccurrenceKey !== null
        ? { occurrenceKey: options.expectedOccurrenceKey }
        : {}),
      prepareCommands: () => {
        const { root } = this.assertProjectedTree(rootId);
        if (
          options?.expectedOccurrenceKey !== undefined &&
          options.expectedOccurrenceKey !== null &&
          options.expectedOccurrenceKey !== root.occurrenceKey
        ) {
          throw DomainError.staleOccurrence(this.getSnapshot());
        }
        const child = this.requireChild(root, childTodoistId);
        if (
          options?.expectedChildRevision !== undefined &&
          child.mirrorRevision !== this.parseChildRevision(options.expectedChildRevision)
        ) {
          throw DomainError.staleChild(this.getSnapshot());
        }
        if (action === "check") {
          return child.completedAt === null
            ? [{ type: "item_close", input: { todoistTaskId: child.todoistTaskId } }]
            : [];
        }
        return child.completedAt !== null
          ? [{ type: "item_uncomplete", input: { todoistTaskId: child.todoistTaskId } }]
          : [];
      },
      ...(options?.deadlineMs !== undefined ? { deadlineMs: options.deadlineMs } : {}),
      ...(options?.minimumExecutionBudgetMs !== undefined
        ? { minimumExecutionBudgetMs: options.minimumExecutionBudgetMs }
        : {}),
      ...(options?.signal !== undefined ? { signal: options.signal } : {})
    });
  }

  private validateChildMutations(
    root: TaskWithCommitment,
    initialChildCount: number,
    mutations: readonly ChildMutation[],
    targetProjectId?: string
  ): void {
    let projectedChildCount = initialChildCount;
    for (const mutation of mutations) {
      if (mutation.type === "add") {
        if (projectedChildCount >= MAX_CHECKLIST_CHILDREN) {
          throw childDomainError("too_many_children", "A commitment can have at most 30 checklist items");
        }
        requiredChildTitle(mutation.title);
        if (targetProjectId === undefined) {
          requiredChecklistProjectId(root);
        }
        projectedChildCount += 1;
        continue;
      }
      const child = this.requireChild(root, mutation.childId);
      if (child.mirrorRevision !== this.parseChildRevision(mutation.expectedChildRevision)) {
        throw DomainError.staleChild(this.getSnapshot());
      }
      if (mutation.type === "edit") {
        requiredChildTitle(mutation.title);
      }
    }
  }

  private buildChildCommands(
    root: TaskWithCommitment,
    mutations: readonly ChildMutation[],
    targetProjectId?: string
  ): TodoistCommand[] {
    const commands: TodoistCommand[] = [];
    for (const mutation of mutations) {
      if (mutation.type === "add") {
        const title = requiredChildTitle(mutation.title);
        const tempId = randomUUID();
        commands.push({
          type: "item_add",
          input: {
            kind: "child",
            tempId,
            title,
            description: normalizeChildDescription(mutation.description),
            projectId: targetProjectId ?? requiredChecklistProjectId(root),
            parentTodoistTaskId: root.todoistTaskId
          }
        });
        continue;
      }

      const child = this.requireChild(root, mutation.childId);

      if (mutation.type === "edit") {
        const title = requiredChildTitle(mutation.title);
        commands.push({
          type: "item_update",
          input: {
            todoistTaskId: child.todoistTaskId,
            title,
            description: normalizeChildDescription(mutation.description),
            due: { kind: "preserve" },
            assignment: { kind: "preserve" }
          }
        });
        continue;
      }

      if (mutation.type === "delete") {
        commands.push({
          type: "item_delete",
          input: { todoistTaskId: child.todoistTaskId }
        });
        continue;
      }

      if (mutation.type === "check") {
        if (child.completedAt === null) {
          commands.push({
            type: "item_close",
            input: { todoistTaskId: child.todoistTaskId }
          });
        }
        continue;
      }

      if (mutation.type === "uncheck") {
        if (child.completedAt !== null) {
          commands.push({
            type: "item_uncomplete",
            input: { todoistTaskId: child.todoistTaskId }
          });
        }
        continue;
      }
    }
    return commands;
  }

  private assertProjectedTree(rootTaskId: string): {
    root: TaskWithCommitment;
    directChildren: TaskWithCommitment[];
  } {
    const root = this.requireChecklistRoot(rootTaskId);
    const all = this.tasks.listAll();
    const hierarchy = this.commitmentPolicy().inspectRootHierarchy(root, all);
    if (hierarchy.status === "unsupported") {
      throw DomainError.unsupportedHierarchy(this.getSnapshot());
    }
    return {
      root,
      directChildren: hierarchy.directChildren
    };
  }

  private requireChecklistRoot(rootTaskId: string): TaskWithCommitment {
    const root = this.tasks.getById(rootTaskId);
    if (root === null) {
      throw childDomainError("task_not_found", "Task not found", 404);
    }
    if (root.parentId !== null) {
      throw childDomainError("checklist_root_required", "Checklist mutations require a root task");
    }
    return root;
  }

  private requireChild(root: TaskWithCommitment, childId: string): TaskWithCommitment {
    const child = this.findChild(root, childId);
    if (child === null || child.parentId !== root.todoistTaskId || child.deletedAt !== null) {
      throw childDomainError("child_not_under_root", "Checklist item is not a direct child of this commitment", 404);
    }
    return child;
  }

  private findChild(root: TaskWithCommitment, childId: string): TaskWithCommitment | null {
    const child = this.tasks.getById(childId) ?? this.tasks.getByTodoistTaskId(childId);
    return child === null || child.parentId !== root.todoistTaskId ? null : child;
  }

  private parseChildRevision(value: string): number {
    const match = /^r:(\d+)$/.exec(value);
    if (match === null) {
      throw DomainError.staleChild(this.getSnapshot());
    }
    return Number(match[1]);
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
    if (this.collaborators === null || !this.collaborators.isMirrorContractReady()) {
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
    const hierarchy = this.commitmentPolicy().inspectRootHierarchy(root, all);
    return {
      children: hierarchy.directChildren,
      valid: hierarchy.status === "valid"
    };
  }

  private commitmentPolicy(): CommitmentPolicy {
    const projectsById = new Map(
      this.projects.listActive().map((project) => [project.todoistProjectId, project])
    );
    return new CommitmentPolicy({
      currentUserId: this.currentUserId,
      projectsById
    });
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

function assertChildMutationsShape(mutations: readonly ChildMutation[]): void {
  const seenClientMutationIds = new Set<string>();
  for (const mutation of mutations as readonly unknown[]) {
    if (typeof mutation !== "object" || mutation === null || Array.isArray(mutation)) {
      throw invalidChildMutation();
    }
    const source = mutation as Record<string, unknown>;
    const type = source.type;
    const allowedKeys = allowedChildMutationKeys(type);
    const requiredKeys = requiredChildMutationKeys(type);
    if (
      allowedKeys === null ||
      requiredKeys === null ||
      !requiredKeys.every((key) => hasOwn(source, key)) ||
      Object.keys(source).some((key) => !allowedKeys.includes(key))
    ) {
      throw invalidChildMutation();
    }
    if (typeof source.clientMutationId !== "string" || source.clientMutationId.trim().length === 0) {
      throw invalidChildMutation();
    }
    if (type === "add" || type === "edit") {
      if (
        typeof source.title !== "string" ||
        (source.description !== null && source.description !== undefined && typeof source.description !== "string")
      ) {
        throw invalidChildMutation();
      }
    }
    if (type !== "add") {
      if (typeof source.childId !== "string" || typeof source.expectedChildRevision !== "string") {
        throw invalidChildMutation();
      }
    }
    if (seenClientMutationIds.has(source.clientMutationId)) {
      throw childDomainError("duplicate_client_mutation_id", "Checklist clientMutationId values must be unique");
    }
    seenClientMutationIds.add(source.clientMutationId);
  }
}

function allowedChildMutationKeys(type: unknown): readonly string[] | null {
  switch (type) {
    case "add":
      return ["type", "clientMutationId", "title", "description"];
    case "edit":
      return ["type", "clientMutationId", "childId", "expectedChildRevision", "title", "description"];
    case "delete":
    case "check":
    case "uncheck":
      return ["type", "clientMutationId", "childId", "expectedChildRevision"];
    default:
      return null;
  }
}

function requiredChildMutationKeys(type: unknown): readonly string[] | null {
  switch (type) {
    case "add":
      return ["type", "clientMutationId", "title"];
    case "edit":
      return ["type", "clientMutationId", "childId", "expectedChildRevision", "title"];
    case "delete":
    case "check":
    case "uncheck":
      return ["type", "clientMutationId", "childId", "expectedChildRevision"];
    default:
      return null;
  }
}

function hasOwn(source: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(source, key);
}

function invalidChildMutation(): DomainError {
  return childDomainError("forbidden_child_field", "Checklist items only accept their declared mutation fields");
}

function requiredChecklistProjectId(root: TaskWithCommitment): string {
  if (root.projectId === null) {
    throw childDomainError("root_project_required", "Checklist root must have a Todoist project");
  }
  return root.projectId;
}

function requiredChildTitle(value: string): string {
  const title = value.trim();
  if (title.length === 0) {
    throw childDomainError("child_title_required", "Checklist title is required");
  }
  return title;
}

function normalizeChildDescription(value?: string | null): string | null {
  const normalized = value?.trim() ?? "";
  return normalized.length === 0 ? null : normalized;
}

function childDomainError(code: string, message: string, statusCode = 422): DomainError {
  return new DomainError(code, message, undefined, statusCode);
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
  const timeChanged = !sameDueInstant(task.dueDatetime, input.dueDatetime, ownerTimeZone);
  const scheduleChanged = timeChanged || task.durationMinutes !== input.durationMinutes;
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
  if (!task.isRecurring) {
    return timeChanged ? { kind: "replace" as const, datetime: input.dueDatetime, string: null, lang: null, timezone: null } : { kind: "preserve" as const };
  }
  if (!scheduleChanged) {
    return { kind: "preserve" as const };
  }
  return recurrenceForScheduleUpdate(task, input.dueDatetime);
}
