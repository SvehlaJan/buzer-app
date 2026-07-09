import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistCreateTaskResult,
  TodoistRescheduleInput,
  TodoistSyncResult
} from "../../src/todoist/todoistAdapter.js";

export class FakeTodoistAdapter implements TodoistAdapter {
  syncResults: TodoistSyncResult[] = [];
  syncTokens: string[] = [];
  createdTasks: TodoistCreateTaskInput[] = [];
  createResults: TodoistCreateTaskResult[] = [];
  completedTaskIds: string[] = [];
  reschedules: TodoistRescheduleInput[] = [];

  async sync(syncToken: string): Promise<TodoistSyncResult> {
    this.syncTokens.push(syncToken);
    return this.syncResults.shift() ?? { syncToken: "empty-token", tasks: [], projects: [] };
  }

  async createTask(input: TodoistCreateTaskInput): Promise<TodoistCreateTaskResult> {
    this.createdTasks.push(input);
    return this.createResults.shift() ?? {
      todoistTaskId: `created-${this.createdTasks.length}`,
      task: {
        todoistTaskId: `created-${this.createdTasks.length}`,
        title: input.title,
        description: input.description ?? null,
        projectId: input.projectId,
        assigneeId: input.assigneeId,
        creatorId: "user-self",
        dueDatetime: input.dueDatetime,
        durationMinutes: input.durationMinutes,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: `created-${this.createdTasks.length}` },
        updatedAt: "2026-06-13T10:00:00Z"
      }
    };
  }

  async completeTask(todoistTaskId: string): Promise<void> {
    this.completedTaskIds.push(todoistTaskId);
  }

  async rescheduleTask(input: TodoistRescheduleInput): Promise<void> {
    this.reschedules.push(input);
  }
}
