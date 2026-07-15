import { computeStartAt } from "../domain/time.js";
import { TaskEnforcementPolicy } from "../domain/taskEnforcementPolicy.js";
import { validateTaskStrictness } from "../domain/taskValidation.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository } from "../repositories/taskRepository.js";

export class TaskSnapshotService {
  constructor(
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly currentUserId: string,
    private readonly requireMirrorReady = false
  ) {}

  getSnapshot(serverTime: string) {
    this.assertMirrorReady();
    const rows = this.tasks.listAll();
    const projectsById = new Map(
      this.projects.listActive().map((project) => [project.todoistProjectId, project])
    );
    const policy = new TaskEnforcementPolicy({
      currentUserId: this.currentUserId,
      projectsById
    });
    const items = rows.map((task) => {
      const isCompanionOwnedByUser = policy.isCompanionOwned(task);
      const isAssignedToUser = task.assigneeId === this.currentUserId || isCompanionOwnedByUser;
      const validation = validateTaskStrictness({
        title: task.title,
        creatorId: task.creatorId,
        assigneeId: task.assigneeId,
        dueDatetime: task.dueDatetime,
        durationMinutes: task.durationMinutes,
        currentUserId: this.currentUserId,
        isCompanionOwned: isCompanionOwnedByUser
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
        dueDate: extractDateOnlyDueDate(task.rawJson),
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

  private assertMirrorReady(): void {
    if (this.requireMirrorReady && !this.tasks.isMirrorContractReady()) {
      throw syncInitializingError();
    }
  }
}

function syncInitializingError(): Error & { code: string; statusCode: number } {
  return Object.assign(new Error("Todoist synchronization is initializing"), {
    code: "sync_initializing",
    statusCode: 503
  });
}

function extractDateOnlyDueDate(rawJson: string): string | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawJson);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null || !("due" in parsed)) {
    return null;
  }
  const due = (parsed as { due?: unknown }).due;
  if (typeof due !== "object" || due === null || !("date" in due)) {
    return null;
  }
  const date = (due as { date?: unknown }).date;
  return typeof date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : null;
}
