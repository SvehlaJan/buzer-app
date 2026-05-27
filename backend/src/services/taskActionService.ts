import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import { addMinutes } from "../domain/time.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";

export interface CreateTaskInput {
  title: string;
  projectId: string | null;
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
    private readonly todoist: TodoistAdapter,
    private readonly currentUserId = ""
  ) {}

  async create(input: CreateTaskInput): Promise<{ id: string; todoistTaskId: string }> {
    const validation = validateTaskStrictness({
      title: input.title,
      creatorId: input.creatorId,
      assigneeId: input.assigneeId,
      dueDatetime: input.dueDatetime,
      durationMinutes: input.durationMinutes,
      currentUserId: this.currentUserId
    });
    if (!validation.valid) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors: validation.errors }
      });
    }
    if (input.assigneeId === null) {
      throw Object.assign(new Error("Task validation failed"), {
        code: "task_validation_failed",
        statusCode: 422,
        details: { errors: ["assignee_required_for_created_task"] }
      });
    }

    const created = await this.todoist.createTask({
      title: input.title,
      projectId: input.projectId,
      assigneeId: input.assigneeId,
      dueDatetime: input.dueDatetime,
      durationMinutes: input.durationMinutes
    });
    const id = randomUUID();
    this.tasks.upsertTodoistTask({
      id,
      todoistTaskId: created.todoistTaskId,
      title: input.title,
      projectId: input.projectId,
      assigneeId: input.assigneeId,
      creatorId: input.creatorId,
      dueDatetime: input.dueDatetime,
      durationMinutes: input.durationMinutes,
      completedAt: null,
      deletedAt: null,
      rawJson: JSON.stringify({ id: created.todoistTaskId }),
      updatedAt: input.now
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
    const escalationTriggered = task.startDismissCount >= 1;
    this.tasks.updateCommitmentState(taskId, {
      startDismissDelta: 1,
      lastSnoozedAt: snoozedUntil,
      ...(escalationTriggered && task.escalationSentAt === null ? { escalationSentAt: now } : {}),
      incrementScheduleVersion: true,
      updatedAt: now
    });
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
