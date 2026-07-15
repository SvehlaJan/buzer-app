import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import { addMinutes } from "../domain/time.js";
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

    const created = await this.todoist.createTask({
      title: input.title,
      description: normalizeOptionalText(input.description),
      projectId: input.projectId,
      assigneeId: input.assigneeId,
      dueDatetime,
      durationMinutes
    });
    const verificationErrors = verifyCreatedTask({
      task: created.task,
      expectedProjectId: input.projectId,
      expectedAssigneeId: input.assigneeId,
      expectedDueDatetime: dueDatetime,
      expectedDurationMinutes: durationMinutes
    });
    if (verificationErrors.length > 0) {
      throw Object.assign(new Error("Todoist create verification failed"), {
        code: "todoist_create_verification_failed",
        statusCode: 422,
        details: { errors: verificationErrors, todoistTaskId: created.todoistTaskId }
      });
    }

    const id = randomUUID();
    this.tasks.upsertTodoistTask({
      id,
      todoistTaskId: created.todoistTaskId,
      title: created.task.title,
      description: created.task.description,
      projectId: created.task.projectId,
      parentId: created.task.parentId,
      childOrder: created.task.childOrder,
      assigneeId: created.task.assigneeId,
      creatorId: created.task.creatorId ?? input.creatorId,
      dueDate: created.task.dueDate,
      dueDatetime: created.task.dueDatetime,
      dueString: created.task.dueString,
      dueLang: created.task.dueLang,
      dueTimezone: created.task.dueTimezone,
      isRecurring: created.task.isRecurring,
      durationAmount: created.task.durationAmount,
      durationUnit: created.task.durationUnit,
      durationMinutes: created.task.durationMinutes,
      completedAt: created.task.completedAt,
      deletedAt: created.task.deletedAt,
      rawJson: JSON.stringify(created.task.rawJson),
      updatedAt: input.now,
      commitmentOwnerId: project.canAssignTasks ? null : this.currentUserId
    });
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
    await this.todoist.completeTask(task.todoistTaskId, randomUUID());
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
    const newDue = addMinutes(task.dueDatetime, 30);
    await this.todoist.rescheduleTask({
      todoistTaskId: task.todoistTaskId,
      dueDatetime: newDue,
      durationMinutes: task.durationMinutes
    }, randomUUID());
    const tx = this.db.transaction(() => {
      this.tasks.updateTaskSchedule(taskId, newDue, task.durationMinutes, now);
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
      ? normalizeOptionalText(input.description ?? null)
      : undefined;
    await this.todoist.rescheduleTask({
      todoistTaskId: task.todoistTaskId,
      dueDatetime: input.dueDatetime,
      durationMinutes: input.durationMinutes,
      ...(descriptionWasProvided ? { description: input.description ?? null } : {})
    }, randomUUID());
    const tx = this.db.transaction(() => {
      this.tasks.updateTaskSchedule(taskId, input.dueDatetime, input.durationMinutes, input.now, normalizedDescription);
      this.tasks.updateCommitmentState(taskId, {
        state: "planned",
        lastSnoozedAt: null,
        incrementScheduleVersion: true,
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
    const normalizedDescription = normalizeOptionalText(input.description);
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

    await this.todoist.updateTask({
      todoistTaskId: task.todoistTaskId,
      title: normalizedTitle,
      description: normalizedDescription,
      projectId: input.projectId,
      assigneeId: project.canAssignTasks ? input.assigneeId : null,
      dueDatetime: input.dueDatetime,
      durationMinutes: input.durationMinutes
    }, {
      updateCommandUuid: randomUUID(),
      ...(task.projectId !== input.projectId ? { moveCommandUuid: randomUUID() } : {})
    });
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
      this.tasks.updateCommitmentState(taskId, {
        state: "planned",
        startedAt: null,
        lastSnoozedAt: null,
        incrementScheduleVersion: true,
        updatedAt: input.now
      });
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
