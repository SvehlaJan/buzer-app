import type { TodoistProjectMirror, TodoistTaskMirror } from "./todoistTypes.js";

export interface TodoistSyncResult {
  syncToken: string;
  tasks: TodoistTaskMirror[];
  projects: TodoistProjectMirror[];
}

export interface TodoistCreateTaskInput {
  title: string;
  description?: string | null;
  projectId: string | null;
  assigneeId: string | null;
  dueDatetime: string | null;
  durationMinutes: number | null;
}

export interface TodoistCreateTaskResult {
  todoistTaskId: string;
  task: TodoistTaskMirror;
}

export interface TodoistRescheduleInput {
  todoistTaskId: string;
  dueDatetime: string;
  durationMinutes: number;
  description?: string | null;
}

export interface TodoistUpdateTaskInput {
  todoistTaskId: string;
  title: string;
  description: string | null;
  projectId: string;
  assigneeId: string | null;
  dueDatetime: string;
  durationMinutes: number;
}

export interface TodoistAdapter {
  sync(syncToken: string): Promise<TodoistSyncResult>;
  createTask(input: TodoistCreateTaskInput): Promise<TodoistCreateTaskResult>;
  completeTask(todoistTaskId: string): Promise<void>;
  rescheduleTask(input: TodoistRescheduleInput): Promise<void>;
  updateTask(input: TodoistUpdateTaskInput): Promise<void>;
}
