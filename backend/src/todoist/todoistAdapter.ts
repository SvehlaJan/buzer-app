import type {
  TodoistCollaboratorMirror,
  TodoistCollaboratorStateMirror,
  TodoistProjectMirror,
  TodoistTaskMirror
} from "./todoistTypes.js";
import type { TodoistMutationGateway } from "./todoistMutationGateway.js";

export interface TodoistSyncResult {
  syncToken: string;
  tasks: TodoistTaskMirror[];
  projects: TodoistProjectMirror[];
  collaborators: TodoistCollaboratorMirror[];
  collaboratorStates: TodoistCollaboratorStateMirror[];
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
  dueDatetime: string | null;
  durationMinutes: number | null;
}

export interface TodoistLegacyUpdateCommandIdentities {
  updateCommandUuid: string;
  moveCommandUuid?: string;
}

export interface TodoistAdapter extends TodoistMutationGateway {
  sync(syncToken: string): Promise<TodoistSyncResult>;
  createTask(input: TodoistCreateTaskInput): Promise<TodoistCreateTaskResult>;
  completeTask(todoistTaskId: string, commandUuid: string): Promise<void>;
  rescheduleTask(input: TodoistRescheduleInput, commandUuid: string): Promise<void>;
  updateTask(input: TodoistUpdateTaskInput, commands: TodoistLegacyUpdateCommandIdentities): Promise<void>;
}
