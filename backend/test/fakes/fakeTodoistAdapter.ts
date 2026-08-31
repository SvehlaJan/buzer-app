import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistCreateTaskResult,
  TodoistItemAddInput,
  TodoistItemAddResult,
  TodoistItemIdentityInput,
  TodoistItemMoveInput,
  TodoistItemUpdateInput,
  TodoistLegacyUpdateCommandIdentities,
  TodoistRecurringCompletionInput,
  TodoistSyncResult,
  TodoistUpdateTaskInput
} from "../../src/todoist/todoistAdapter.js";

export class FakeTodoistAdapter implements TodoistAdapter {
  syncResults: QueuedSyncResult[] = [];
  syncTokens: string[] = [];
  createdTasks: TodoistCreateTaskInput[] = [];
  createResults: TodoistCreateTaskResult[] = [];
  completedTaskIds: string[] = [];
  updates: TodoistUpdateTaskInput[] = [];
  itemAdds: TodoistItemAddInput[] = [];
  itemUpdates: TodoistItemUpdateInput[] = [];
  itemMoves: TodoistItemMoveInput[] = [];
  itemCloses: TodoistItemIdentityInput[] = [];
  itemUncompletes: TodoistItemIdentityInput[] = [];
  itemDeletes: TodoistItemIdentityInput[] = [];
  recurringCompletions: TodoistRecurringCompletionInput[] = [];

  async sync(syncToken: string): Promise<TodoistSyncResult> {
    this.syncTokens.push(syncToken);
    const result = this.syncResults.shift();
    return {
      syncToken: result?.syncToken ?? "empty-token",
      tasks: result?.tasks ?? [],
      projects: result?.projects ?? [],
      collaborators: result?.collaborators ?? [],
      collaboratorStates: result?.collaboratorStates ?? []
    };
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
        parentId: null,
        childOrder: null,
        assigneeId: input.assigneeId,
        creatorId: "user-self",
        dueDate: input.dueDatetime,
        dueDatetime: input.dueDatetime,
        dueString: null,
        dueLang: null,
        dueTimezone: null,
        isRecurring: false,
        durationAmount: input.durationMinutes,
        durationUnit: input.durationMinutes === null ? null : "minute",
        durationMinutes: input.durationMinutes,
        completedAt: null,
        deletedAt: null,
        rawJson: { id: `created-${this.createdTasks.length}` },
        updatedAt: "2026-06-13T10:00:00Z"
      }
    };
  }

  async completeTask(todoistTaskId: string, _commandUuid: string): Promise<void> {
    this.completedTaskIds.push(todoistTaskId);
  }

  async updateTask(input: TodoistUpdateTaskInput, _commands: TodoistLegacyUpdateCommandIdentities): Promise<void> {
    this.updates.push(input);
  }

  async itemAdd(input: TodoistItemAddInput): Promise<TodoistItemAddResult> {
    this.itemAdds.push(input);
    return { todoistTaskId: `created-${input.tempId}` };
  }

  async itemUpdate(input: TodoistItemUpdateInput): Promise<void> {
    this.itemUpdates.push(input);
  }

  async itemMove(input: TodoistItemMoveInput): Promise<void> {
    this.itemMoves.push(input);
  }

  async itemClose(input: TodoistItemIdentityInput): Promise<void> {
    this.itemCloses.push(input);
  }

  async itemUncomplete(input: TodoistItemIdentityInput): Promise<void> {
    this.itemUncompletes.push(input);
  }

  async itemDelete(input: TodoistItemIdentityInput): Promise<void> {
    this.itemDeletes.push(input);
  }

  async itemUpdateDateComplete(input: TodoistRecurringCompletionInput): Promise<void> {
    this.recurringCompletions.push(input);
  }
}

type QueuedSyncResult = Omit<TodoistSyncResult, "collaborators" | "collaboratorStates"> &
  Partial<Pick<TodoistSyncResult, "collaborators" | "collaboratorStates">>;
