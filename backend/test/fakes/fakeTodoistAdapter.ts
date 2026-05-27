import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistRescheduleInput,
  TodoistSyncResult
} from "../../src/todoist/todoistAdapter.js";

export class FakeTodoistAdapter implements TodoistAdapter {
  syncResults: TodoistSyncResult[] = [];
  createdTasks: TodoistCreateTaskInput[] = [];
  completedTaskIds: string[] = [];
  reschedules: TodoistRescheduleInput[] = [];

  async sync(_syncToken: string): Promise<TodoistSyncResult> {
    return this.syncResults.shift() ?? { syncToken: "empty-token", tasks: [] };
  }

  async createTask(input: TodoistCreateTaskInput): Promise<{ todoistTaskId: string }> {
    this.createdTasks.push(input);
    return { todoistTaskId: `created-${this.createdTasks.length}` };
  }

  async completeTask(todoistTaskId: string): Promise<void> {
    this.completedTaskIds.push(todoistTaskId);
  }

  async rescheduleTask(input: TodoistRescheduleInput): Promise<void> {
    this.reschedules.push(input);
  }
}
