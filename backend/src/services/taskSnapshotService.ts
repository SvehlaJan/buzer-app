import { computeStartAt } from "../domain/time.js";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import type { TaskRepository } from "../repositories/taskRepository.js";

export class TaskSnapshotService {
  constructor(
    private readonly tasks: TaskRepository,
    private readonly currentUserId: string
  ) {}

  getSnapshot(serverTime: string) {
    const rows = this.tasks.listAll();
    const items = rows.map((task) => {
      const validation = validateTaskStrictness({
        title: task.title,
        creatorId: task.creatorId,
        assigneeId: task.assigneeId,
        dueDatetime: task.dueDatetime,
        durationMinutes: task.durationMinutes,
        currentUserId: this.currentUserId
      });
      const startAt = task.dueDatetime !== null && task.durationMinutes !== null
        ? computeStartAt(task.dueDatetime, task.durationMinutes)
        : null;

      return {
        id: task.id,
        todoistTaskId: task.todoistTaskId,
        title: task.title,
        projectId: task.projectId,
        assigneeId: task.assigneeId,
        creatorId: task.creatorId,
        dueDatetime: task.dueDatetime,
        durationMinutes: task.durationMinutes,
        startAt,
        state: validation.valid ? task.state : "invalid",
        isAssignedToUser: task.assigneeId === this.currentUserId,
        isCreatedByUser: task.creatorId === this.currentUserId,
        isCompleted: task.completedAt !== null,
        isDeleted: task.deletedAt !== null,
        validationErrors: validation.errors,
        startedAt: task.startedAt,
        startDismissCount: task.startDismissCount,
        deadlineExtensionCount: task.deadlineExtensionCount,
        escalationSentAt: task.escalationSentAt,
        updatedAt: task.updatedAt
      };
    });

    return {
      scheduleVersion: Math.max(0, ...rows.map((row) => row.scheduleVersion)),
      serverTime,
      tasks: items,
      health: []
    };
  }
}
