import type { TodoistTaskMirror } from "./todoistTypes.js";

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

export interface TodoistDurationMutation {
  amount: number;
  unit: string;
}

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
}

export interface TodoistRecurringCompletionInput {
  commandUuid: string;
  task: TodoistTaskMirror;
}

export interface TodoistMutationGateway {
  itemAdd(input: TodoistItemAddInput): Promise<TodoistItemAddResult>;
  itemUpdate(input: TodoistItemUpdateInput): Promise<void>;
  itemMove(input: TodoistItemMoveInput): Promise<void>;
  itemClose(input: TodoistItemIdentityInput): Promise<void>;
  itemUncomplete(input: TodoistItemIdentityInput): Promise<void>;
  itemDelete(input: TodoistItemIdentityInput): Promise<void>;
  itemUpdateDateComplete(input: TodoistRecurringCompletionInput): Promise<void>;
}
