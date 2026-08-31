import type {
  TodoistCollaboratorMirror,
  TodoistCollaboratorStateMirror,
  TodoistProjectMirror,
  TodoistTaskMirror
} from "./todoistTypes.js";

export type TodoistDueMutation =
  | { kind: "preserve" }
  | { kind: "clear" }
  | {
      kind: "replace";
      datetime: string;
      string: string | null;
      lang: string | null;
      timezone: string | null;
    };

export type TodoistAssignmentMutation =
  | { kind: "preserve" }
  | { kind: "assign"; assigneeId: string }
  | { kind: "unassign" };

export type TodoistDurationMutation =
  | { kind: "preserve" }
  | { kind: "clear" }
  | { kind: "replace"; amount: number; unit: "minute" | "hour" };

export interface TodoistRootItemAddInput {
  kind: "root";
  commandUuid: string;
  tempId: string;
  title: string;
  description?: string | null;
  projectId: string;
  assigneeId: string | null;
  due: TodoistDueMutation;
  duration: TodoistDurationMutation | null;
}

export interface TodoistChildItemAddInput {
  kind: "child";
  commandUuid: string;
  tempId: string;
  title: string;
  description?: string | null;
  projectId: string;
  parentTodoistTaskId: string;
}

export type TodoistItemAddInput = TodoistRootItemAddInput | TodoistChildItemAddInput;

export interface TodoistItemAddResult {
  todoistTaskId: string | null;
}

export interface TodoistItemUpdateInput {
  commandUuid: string;
  todoistTaskId: string;
  title?: string;
  description?: string | null;
  duration?: TodoistDurationMutation;
  due: TodoistDueMutation;
  assignment: TodoistAssignmentMutation;
}

export interface TodoistItemMoveInput {
  commandUuid: string;
  todoistTaskId: string;
  projectId: string;
}

export interface TodoistItemIdentityInput {
  commandUuid: string;
  todoistTaskId: string;
  resetSubtasks?: boolean;
}

export interface TodoistRecurringCompletionInput {
  commandUuid: string;
  task: TodoistTaskMirror;
}

export type TodoistCommand =
  | { type: "item_add"; input: Omit<TodoistRootItemAddInput, "commandUuid"> | Omit<TodoistChildItemAddInput, "commandUuid"> | TodoistItemAddInput }
  | { type: "item_update"; input: Omit<TodoistItemUpdateInput, "commandUuid"> | TodoistItemUpdateInput }
  | { type: "item_move"; input: Omit<TodoistItemMoveInput, "commandUuid"> | TodoistItemMoveInput }
  | { type: "item_close"; input: Omit<TodoistItemIdentityInput, "commandUuid"> | TodoistItemIdentityInput }
  | { type: "item_uncomplete"; input: Omit<TodoistItemIdentityInput, "commandUuid"> | TodoistItemIdentityInput }
  | { type: "item_delete"; input: Omit<TodoistItemIdentityInput, "commandUuid"> | TodoistItemIdentityInput }
  | { type: "item_update_date_complete"; input: Omit<TodoistRecurringCompletionInput, "commandUuid"> | TodoistRecurringCompletionInput };

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

export interface TodoistAdapter {
  sync(syncToken: string, options?: { signal?: AbortSignal }): Promise<TodoistSyncResult>;
  createTask(input: TodoistCreateTaskInput, options?: { signal?: AbortSignal }): Promise<TodoistCreateTaskResult>;
  completeTask(todoistTaskId: string, commandUuid: string, options?: { signal?: AbortSignal }): Promise<void>;
  updateTask(input: TodoistUpdateTaskInput, commands: TodoistLegacyUpdateCommandIdentities, options?: { signal?: AbortSignal }): Promise<void>;
  itemAdd(input: TodoistItemAddInput, options?: { signal?: AbortSignal }): Promise<TodoistItemAddResult>;
  itemUpdate(input: TodoistItemUpdateInput, options?: { signal?: AbortSignal }): Promise<void>;
  itemMove(input: TodoistItemMoveInput, options?: { signal?: AbortSignal }): Promise<void>;
  itemClose(input: TodoistItemIdentityInput, options?: { signal?: AbortSignal }): Promise<void>;
  itemUncomplete(input: TodoistItemIdentityInput, options?: { signal?: AbortSignal }): Promise<void>;
  itemDelete(input: TodoistItemIdentityInput, options?: { signal?: AbortSignal }): Promise<void>;
  itemUpdateDateComplete(input: TodoistRecurringCompletionInput, options?: { signal?: AbortSignal }): Promise<void>;
}
