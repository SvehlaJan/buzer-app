import type { TodoistTaskMirror } from "./todoistTypes.js";

export interface TodoistSyncResult {
  syncToken: string;
  tasks: TodoistTaskMirror[];
}

export interface TodoistCreateTaskInput {
  title: string;
  projectId: string | null;
  assigneeId: string;
  dueDatetime: string | null;
  durationMinutes: number | null;
}

export interface TodoistRescheduleInput {
  todoistTaskId: string;
  dueDatetime: string;
  durationMinutes: number;
}

export interface TodoistAdapter {
  sync(syncToken: string): Promise<TodoistSyncResult>;
  createTask(input: TodoistCreateTaskInput): Promise<{ todoistTaskId: string }>;
  completeTask(todoistTaskId: string): Promise<void>;
  rescheduleTask(input: TodoistRescheduleInput): Promise<void>;
}
