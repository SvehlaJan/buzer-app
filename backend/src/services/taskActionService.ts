import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import { addMinutes } from "../domain/time.js";
import { appendExtension, decodeTaskDescription, reconstructTaskDescription } from "../domain/taskDescriptionCodec.js";
import { describeRecurrence, translateRecurrencePreset, type RecurrencePreset } from "../domain/taskRecurrence.js";
import { recurrenceFingerprint } from "./occurrenceReconciler.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";
import type { EscalationService } from "./escalationService.js";
import {
  TodoistChecklistService,
  type TodoistChecklistServiceOptions,
  type ChecklistMutationRequest,
  type ChecklistMutationResult
} from "./todoistChecklistService.js";

export interface TaskActionServiceOptions {
  /** Production-facing checklist actions must supply live sync, snapshot, and coordination. */
  checklist?: TodoistChecklistServiceOptions;
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
  checklist?: ReadonlyArray<{ title: string; description?: string | null }>;
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
}

export interface UpdateTaskInput {
  title: string;
  description: string | null;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string;
  durationMinutes: number;
  recurrencePreset?: RecurrencePreset;
  timeZone?: string;
  expectedTaskRevision?: string;
  expectedOccurrenceKey?: string;
  actionId?: string;
  deviceId?: string;
  checklistMutations?: ChecklistMutationRequest["mutations"];
  now: string;
}

export class TaskActionService {
  private readonly checklistService: TodoistChecklistService;

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
    if (!isIanaTimeZone(timeZone)) {
      errors.push("time_zone_invalid");
    }
    if (checklist.length > 30) {
      errors.push("too_many_children");
    }
    if (errors.length > 0) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors }
      });
    }
    const recurrence = translateRecurrencePreset({ preset: recurrencePreset, selectedStart: dueDatetime, timeZone });
    const rootTempId = randomUUID();
    const created = await this.todoist.itemAdd({
      kind: "root",
      commandUuid: input.actionId ?? randomUUID(),
      tempId: rootTempId,
      title: input.title,
      description: normalizeOptionalText(input.description),
      projectId: input.projectId,
      assigneeId: input.assigneeId,
      due: recurrence === null
        ? { kind: "replace", datetime: dueDatetime, string: null, lang: null, timezone: null }
        : { kind: "replace", datetime: dueDatetime, ...recurrence },
      duration: { amount: durationMinutes, unit: "minute" }
    });
    if (created.todoistTaskId === null) {
      throw Object.assign(new Error("Todoist did not return a root task ID"), {
        code: "todoist_create_verification_failed",
        statusCode: 422
      });
    }

    const id = randomUUID();
    this.tasks.upsertTodoistTask({
      id,
      todoistTaskId: created.todoistTaskId,
      title: input.title,
      description: normalizeOptionalText(input.description),
      projectId: input.projectId,
      parentId: null,
      childOrder: null,
      assigneeId: input.assigneeId,
      creatorId: input.creatorId,
      dueDate: dueDatetime,
      dueDatetime,
      dueString: recurrence?.string ?? null,
      dueLang: recurrence?.lang ?? null,
      dueTimezone: recurrence?.timezone ?? null,
      isRecurring: recurrence !== null,
      durationAmount: durationMinutes,
      durationUnit: "minute",
      durationMinutes,
      completedAt: null,
      deletedAt: null,
      rawJson: JSON.stringify({ id: created.todoistTaskId }),
      updatedAt: input.now,
      commitmentOwnerId: project.canAssignTasks ? null : this.currentUserId
    });
    for (const [childOrder, child] of checklist.entries()) {
      const childTitle = child.title.trim();
      if (childTitle.length === 0) {
        throw Object.assign(new Error("Checklist item title is required"), { code: "child_title_required", statusCode: 422 });
      }
      const childCreated = await this.todoist.itemAdd({
        kind: "child",
        commandUuid: randomUUID(),
        tempId: randomUUID(),
        title: childTitle,
        description: normalizeOptionalText(child.description ?? null),
        projectId: input.projectId,
        parentTodoistTaskId: created.todoistTaskId
      });
      if (childCreated.todoistTaskId !== null) {
        this.tasks.createChecklistChild({
          id: randomUUID(),
          todoistTaskId: childCreated.todoistTaskId,
          parentTodoistTaskId: created.todoistTaskId,
          projectId: input.projectId,
          title: childTitle,
          description: normalizeOptionalText(child.description ?? null),
          childOrder,
          updatedAt: input.now
        });
      }
    }
    return { id, todoistTaskId: created.todoistTaskId };
  }

  async start(taskId: string, now: string): Promise<void> {
    this.assertMirrorReady();
    this.requireTask(taskId);
    this.tasks.updateCommitmentState(taskId, {
      state: "started",
      startedAt: now,
      incrementScheduleVersion: true,
      updatedAt: now
    });
  }

  async done(taskId: string, now: string): Promise<void> {
    this.assertMirrorReady();
    const task = this.requireTask(taskId);
    if (task.completedAt !== null || task.deletedAt !== null || task.state === "completed" || task.state === "deleted") {
      throw Object.assign(new Error("Task occurrence is no longer current"), {
        code: "stale_occurrence",
        statusCode: 409
      });
    }
    const all = this.tasks.listAll();
    const children = all.filter((candidate) => candidate.parentId === task.todoistTaskId && candidate.deletedAt === null);
    const hasGrandchild = all.some((candidate) => children.some((child) => child.todoistTaskId === candidate.parentId));
    const invalidChild = children.some((child) =>
      child.dueDatetime !== null || child.dueDate !== null || child.durationMinutes !== null || child.isRecurring || child.assigneeId !== null
    );
    if (hasGrandchild || invalidChild || children.length > 30) {
      throw Object.assign(new Error("Task hierarchy is not supported"), {
        code: "unsupported_hierarchy",
        statusCode: 422
      });
    }
    if (children.some((child) => child.completedAt === null)) {
      throw Object.assign(new Error("Complete every checklist item first"), {
        code: "children_incomplete",
        statusCode: 422
      });
    }
    if (task.isRecurring) {
      await this.todoist.itemUpdateDateComplete({ commandUuid: randomUUID(), task });
      return;
    }
    await this.todoist.itemClose({ commandUuid: randomUUID(), todoistTaskId: task.todoistTaskId });
    const tx = this.db.transaction(() => {
      this.tasks.markCompleted(taskId, now);
      this.tasks.updateCommitmentState(taskId, {
        state: "completed",
        incrementScheduleVersion: true,
        updatedAt: now
      });
    });
    tx();
  }

  async postponeStart(taskId: string, now: string): Promise<{ snoozedUntil: string; escalationTriggered: boolean }> {
    this.assertMirrorReady();
    const task = this.requireTask(taskId);
    const snoozedUntil = addMinutes(now, 5);
    const nextDismissCount = task.startDismissCount + 1;
    const escalationTriggered = nextDismissCount >= 2;
    this.tasks.updateCommitmentState(taskId, {
      startDismissDelta: 1,
      lastSnoozedAt: snoozedUntil,
      incrementScheduleVersion: true,
      updatedAt: now
    });
    if (escalationTriggered && task.escalationSentAt === null && this.escalationService !== null) {
      await this.escalationService.sendForTask(taskId, nextDismissCount);
    }
    return { snoozedUntil, escalationTriggered };
  }

  async extend(taskId: string, now: string): Promise<void> {
    this.assertMirrorReady();
    const task = this.requireSchedulableTask(taskId);
    if (task.isRecurring) {
      throw Object.assign(new Error("Recurring commitments cannot be extended"), {
        code: "recurring_extension_unsupported",
        statusCode: 422
      });
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
    await this.todoist.itemUpdate({
      commandUuid: randomUUID(),
      todoistTaskId: task.todoistTaskId,
      description,
      duration: { amount: newDuration, unit: "minute" },
      due: { kind: "preserve" },
      assignment: { kind: "preserve" }
    });
    const tx = this.db.transaction(() => {
      this.tasks.updateTaskSchedule(taskId, task.dueDatetime, newDuration, now, description);
      this.tasks.updateCommitmentState(taskId, {
        state: "started",
        deadlineExtensionDelta: 1,
        incrementScheduleVersion: true,
        updatedAt: now
      });
    });
    tx();
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
    await this.todoist.itemUpdate({
      commandUuid: randomUUID(),
      todoistTaskId: task.todoistTaskId,
      ...(normalizedDescription === undefined ? {} : { description: normalizedDescription }),
      duration: { amount: input.durationMinutes, unit: "minute" },
      due: recurrence,
      assignment: { kind: "preserve" }
    });
    const tx = this.db.transaction(() => {
      this.tasks.updateTaskSchedule(taskId, input.dueDatetime, input.durationMinutes, input.now, normalizedDescription);
      this.tasks.rotateOccurrence(taskId, {
        occurrenceKey: randomUUID(),
        occurrenceDueDatetime: input.dueDatetime,
        occurrenceRecurrenceFingerprint: recurrenceFingerprint({
          isRecurring: task.isRecurring,
          dueString: recurrence.kind === "replace" ? recurrence.string : task.dueString,
          dueLang: recurrence.kind === "replace" ? recurrence.lang : task.dueLang,
          dueTimezone: recurrence.kind === "replace" ? recurrence.timezone : task.dueTimezone
        }),
        updatedAt: input.now
      });
    });
    tx();
  }

  async update(taskId: string, input: UpdateTaskInput): Promise<void> {
    this.assertMirrorReady();
    const task = this.requireTask(taskId);
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
    if (task.projectId !== input.projectId) {
      await this.todoist.itemMove({
        commandUuid: randomUUID(),
        todoistTaskId: task.todoistTaskId,
        projectId: input.projectId
      });
    }
    await this.todoist.itemUpdate({
      commandUuid: randomUUID(),
      todoistTaskId: task.todoistTaskId,
      title: normalizedTitle,
      description: normalizedDescription,
      duration: { amount: input.durationMinutes, unit: "minute" },
      due,
      assignment: project.canAssignTasks && input.assigneeId !== null
        ? { kind: "assign", assigneeId: input.assigneeId }
        : { kind: "unassign" }
    });
    const scheduleChanged = task.dueDatetime !== input.dueDatetime
      || task.durationMinutes !== input.durationMinutes
      || task.isRecurring !== (due.kind === "replace" && due.string !== null)
      || task.dueString !== (due.kind === "replace" ? due.string : task.dueString)
      || task.dueTimezone !== (due.kind === "replace" ? due.timezone : task.dueTimezone);
    const wasOwned = task.assigneeId === this.currentUserId || task.commitmentOwnerId === this.currentUserId;
    const isOwned = (project.canAssignTasks && input.assigneeId === this.currentUserId) || !project.canAssignTasks;
    const tx = this.db.transaction(() => {
      this.tasks.updateTaskFields(taskId, {
        title: normalizedTitle,
        description: normalizedDescription,
        projectId: input.projectId,
        assigneeId: project.canAssignTasks ? input.assigneeId : null,
        dueDatetime: input.dueDatetime,
        durationMinutes: input.durationMinutes,
        updatedAt: input.now,
        commitmentOwnerId: project.canAssignTasks ? null : this.currentUserId
      });
      if (!isOwned && wasOwned) {
        this.tasks.leaveOccurrenceOwnership(taskId, input.now);
      } else if (isOwned && (!wasOwned || scheduleChanged)) {
        this.tasks.rotateOccurrence(taskId, {
          occurrenceKey: randomUUID(),
          occurrenceDueDatetime: input.dueDatetime,
          occurrenceRecurrenceFingerprint: recurrenceFingerprint({
            isRecurring: due.kind === "replace" && due.string !== null,
            dueString: due.kind === "replace" ? due.string : task.dueString,
            dueLang: due.kind === "replace" ? due.lang : task.dueLang,
            dueTimezone: due.kind === "replace" ? due.timezone : task.dueTimezone
          }),
          updatedAt: input.now
        });
      }
    });
    tx();
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

function isIanaTimeZone(value: string): boolean {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
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

function recurrenceForUpdate(task: TaskWithCommitment, input: UpdateTaskInput) {
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
