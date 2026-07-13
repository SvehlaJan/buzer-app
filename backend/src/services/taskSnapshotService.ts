import { computeStartAt } from "../domain/time.js";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository } from "../repositories/taskRepository.js";

export class TaskSnapshotService {
  constructor(
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly currentUserId: string
  ) {}

  getSnapshot(serverTime: string) {
    const rows = this.tasks.listAll();
    const projectsById = new Map(
      this.projects.listActive().map((project) => [project.todoistProjectId, project])
    );
    const items = rows.map((task) => {
      const project = task.projectId === null ? null : projectsById.get(task.projectId) ?? null;
      const isCurrentUserPersonalProjectTask =
        task.assigneeId === null &&
        task.creatorId === this.currentUserId &&
        project?.canAssignTasks === false;
      const isCompanionOwnedByUser =
        task.assigneeId === null &&
        (task.commitmentOwnerId === this.currentUserId || isCurrentUserPersonalProjectTask);
      const isAssignedToUser = task.assigneeId === this.currentUserId || isCompanionOwnedByUser;
      const validation = validateTaskStrictness({
        title: task.title,
        creatorId: task.creatorId,
        assigneeId: isCompanionOwnedByUser ? this.currentUserId : task.assigneeId,
        dueDatetime: task.dueDatetime,
        durationMinutes: task.durationMinutes,
        currentUserId: this.currentUserId
      });
      const startAt = task.dueDatetime !== null && task.durationMinutes !== null
        ? computeStartAt(task.dueDatetime, task.durationMinutes)
        : null;
      const terminalState = task.deletedAt !== null
        ? "deleted"
        : task.completedAt !== null
          ? "completed"
          : null;

      return {
        id: task.id,
        todoistTaskId: task.todoistTaskId,
        title: task.title,
        description: task.description,
        projectId: task.projectId,
        assigneeId: task.assigneeId,
        creatorId: task.creatorId,
        dueDatetime: task.dueDatetime,
        durationMinutes: task.durationMinutes,
        startAt,
        state: terminalState ?? (validation.valid ? task.state : "invalid"),
        isAssignedToUser,
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
