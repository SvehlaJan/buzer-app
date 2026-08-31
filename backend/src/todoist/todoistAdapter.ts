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
  sync(syncToken: string, options?: { signal?: AbortSignal }): Promise<TodoistSyncResult>;
  createTask(input: TodoistCreateTaskInput, options?: { signal?: AbortSignal }): Promise<TodoistCreateTaskResult>;
  completeTask(todoistTaskId: string, commandUuid: string, options?: { signal?: AbortSignal }): Promise<void>;
  updateTask(input: TodoistUpdateTaskInput, commands: TodoistLegacyUpdateCommandIdentities, options?: { signal?: AbortSignal }): Promise<void>;
}

