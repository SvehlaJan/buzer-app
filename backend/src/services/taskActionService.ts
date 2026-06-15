import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import { addMinutes } from "../domain/time.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";
import type { EscalationService } from "./escalationService.js";

export interface CreateTaskInput {
  title: string;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  creatorId: string;
  now: string;
}

export interface RescheduleTaskInput {
  dueDatetime: string;
  durationMinutes: number;
  now: string;
}

export class TaskActionService {
  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly todoist: TodoistAdapter,
    private readonly currentUserId = "",
    private readonly escalationService: EscalationService | null = null
  ) {}

  async create(input: CreateTaskInput): Promise<{ id: string; todoistTaskId: string }> {
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
      projectId: created.task.projectId,
      assigneeId: created.task.assigneeId,
      creatorId: created.task.creatorId ?? input.creatorId,
      dueDatetime: created.task.dueDatetime,
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
    this.requireTask(taskId);
    this.tasks.updateCommitmentState(taskId, {
      state: "started",
      startedAt: now,
      incrementScheduleVersion: true,
      updatedAt: now
    });
  }

  async done(taskId: string, now: string): Promise<void> {
    const task = this.requireTask(taskId);
    await this.todoist.completeTask(task.todoistTaskId);
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
    const task = this.requireSchedulableTask(taskId);
    const newDue = addMinutes(task.dueDatetime, 30);
    await this.todoist.rescheduleTask({
      todoistTaskId: task.todoistTaskId,
      dueDatetime: newDue,
      durationMinutes: task.durationMinutes
    });
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
    const task = this.requireTask(taskId);
    await this.todoist.rescheduleTask({
      todoistTaskId: task.todoistTaskId,
      dueDatetime: input.dueDatetime,
      durationMinutes: input.durationMinutes
    });
    const tx = this.db.transaction(() => {
      this.tasks.updateTaskSchedule(taskId, input.dueDatetime, input.durationMinutes, input.now);
      this.tasks.updateCommitmentState(taskId, {
        state: "planned",
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
  projectId: string | null;
  assigneeId: string | null;
  creatorId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
  completedAt: string | null;
  deletedAt: string | null;
  rawJson: unknown;
}
