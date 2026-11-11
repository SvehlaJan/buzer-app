import type {
  TodoistAdapter,
  TodoistItemAddInput,
  TodoistItemAddResult,
  TodoistItemIdentityInput,
  TodoistItemMoveInput,
  TodoistItemUpdateInput,
  TodoistSyncResult
} from "../../src/todoist/todoistAdapter.js";

export class FakeTodoistAdapter implements TodoistAdapter {
  syncResults: QueuedSyncResult[] = [];
  syncTokens: string[] = [];
  itemAdds: TodoistItemAddInput[] = [];
  itemUpdates: TodoistItemUpdateInput[] = [];
  itemMoves: TodoistItemMoveInput[] = [];
  itemCloses: TodoistItemIdentityInput[] = [];
  itemUncompletes: TodoistItemIdentityInput[] = [];
  itemDeletes: TodoistItemIdentityInput[] = [];

  async sync(syncToken: string, _options?: { signal?: AbortSignal }): Promise<TodoistSyncResult> {
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
}

type QueuedSyncResult = Omit<TodoistSyncResult, "collaborators" | "collaboratorStates"> &
  Partial<Pick<TodoistSyncResult, "collaborators" | "collaboratorStates">>;
