import type {
  TodoistAdapter,
  TodoistChildItemAddInput,
  TodoistDueMutation,
  TodoistItemAddInput,
  TodoistItemAddResult,
  TodoistItemIdentityInput,
  TodoistItemMoveInput,
  TodoistItemUpdateInput,
  TodoistRootItemAddInput,
  TodoistSyncResult
} from "../../src/todoist/todoistAdapter.js";
import { TodoistOperationError } from "../../src/todoist/todoistErrors.js";
import type {
  TodoistCollaboratorMirror,
  TodoistCollaboratorStateMirror,
  TodoistProjectMirror,
  TodoistTaskMirror
} from "../../src/todoist/todoistTypes.js";

export type E2EScenario =
  | "none"
  | "after_command_sent_timeout"
  | "sync_lag_effect_unknown"
  | "command_rejected"
  | "delivery_unknown"
  | "accepted_stale";

export const E2E_SCENARIOS: readonly E2EScenario[] = [
  "none",
  "after_command_sent_timeout",
  "sync_lag_effect_unknown",
  "command_rejected",
  "delivery_unknown",
  "accepted_stale"
];

/**
 * In-memory Todoist-side state. It models only Todoist mutations behind the adapter
 * interface; the real backend retains responsibility for snapshots, checklists, and
 * reconciliation.
 */
export class E2ETodoistWorld {
  private tasks = new Map<string, TodoistTaskMirror>();
  private projects = new Map<string, TodoistProjectMirror>();
  private collaborators = new Map<string, TodoistCollaboratorMirror>();
  private collaboratorStates = new Map<string, TodoistCollaboratorStateMirror>();
  private createdCount = 0;
  private loadGeneration = 0;

  constructor(private readonly currentUserId: string) {}

  load(seed: {
    tasks: TodoistTaskMirror[];
    projects: TodoistProjectMirror[];
    collaborators?: TodoistCollaboratorMirror[];
    collaboratorStates?: TodoistCollaboratorStateMirror[];
  }): void {
    this.tasks = new Map(
      seed.tasks.map((task) => [task.todoistTaskId, structuredClone(task)])
    );
    this.projects = new Map(
      seed.projects.map((project) => [
        project.todoistProjectId,
        structuredClone(project)
      ])
    );
    this.collaborators = new Map(
      (seed.collaborators ?? []).map((collaborator) => [
        collaborator.todoistUserId,
        structuredClone(collaborator)
      ])
    );
    this.collaboratorStates = new Map(
      (seed.collaboratorStates ?? []).map((state) => [
        `${state.todoistProjectId}:${state.todoistUserId}`,
        structuredClone(state)
      ])
    );
    this.createdCount = 0;
    this.loadGeneration += 1;
  }

  state(): {
    tasks: TodoistTaskMirror[];
    projects: TodoistProjectMirror[];
    collaborators: TodoistCollaboratorMirror[];
    collaboratorStates: TodoistCollaboratorStateMirror[];
  } {
    return {
      tasks: [...this.tasks.values()].map((task) => structuredClone(task)),
      projects: [...this.projects.values()].map((project) => structuredClone(project)),
      collaborators: [...this.collaborators.values()].map((collaborator) => structuredClone(collaborator)),
      collaboratorStates: [...this.collaboratorStates.values()].map((state) => structuredClone(state))
    };
  }

  addRoot(input: TodoistRootItemAddInput): string {
    const todoistTaskId = this.nextCreatedId();
    this.tasks.set(todoistTaskId, {
      todoistTaskId,
      title: input.title,
      description: input.description ?? null,
      projectId: input.projectId,
      parentId: null,
      childOrder: null,
      assigneeId: input.assigneeId,
      creatorId: this.currentUserId,
      dueDate: input.due.kind === "replace" ? input.due.datetime : null,
      dueDatetime: input.due.kind === "replace" ? input.due.datetime : null,
      dueString: input.due.kind === "replace" ? input.due.string : null,
      dueLang: input.due.kind === "replace" ? input.due.lang : null,
      dueTimezone: input.due.kind === "replace" ? input.due.timezone : null,
      isRecurring: input.due.kind === "replace" && input.due.string !== null,
      durationAmount: input.duration?.kind === "replace" ? input.duration.amount : null,
      durationUnit: input.duration?.kind === "replace" ? input.duration.unit : null,
      durationMinutes: normalizeDurationMinutes(
        input.duration?.kind === "replace" ? input.duration.amount : null,
        input.duration?.kind === "replace" ? input.duration.unit : null
      ),
      completedAt: null,
      deletedAt: null,
      rawJson: {},
      updatedAt: new Date().toISOString()
    });
    return todoistTaskId;
  }

  addChild(input: TodoistChildItemAddInput): string {
    const todoistTaskId = this.nextCreatedId();
    const siblings = [...this.tasks.values()].filter(
      (task) => task.parentId === input.parentTodoistTaskId
    );
    this.tasks.set(todoistTaskId, {
      todoistTaskId,
      title: input.title,
      description: input.description ?? null,
      projectId: input.projectId,
      parentId: input.parentTodoistTaskId,
      childOrder: siblings.length + 1,
      assigneeId: null,
      creatorId: this.currentUserId,
      dueDate: null,
      dueDatetime: null,
      dueString: null,
      dueLang: null,
      dueTimezone: null,
      isRecurring: false,
      durationAmount: null,
      durationUnit: null,
      durationMinutes: null,
      completedAt: null,
      deletedAt: null,
      rawJson: {},
      updatedAt: new Date().toISOString()
    });
    return todoistTaskId;
  }

  applyUpdate(input: TodoistItemUpdateInput): void {
    const task = this.require(input.todoistTaskId);
    if (input.title !== undefined) task.title = input.title;
    if (input.description !== undefined) task.description = input.description;
    if (input.duration !== undefined) {
      if (input.duration.kind === "clear") {
        task.durationAmount = null;
        task.durationUnit = null;
        task.durationMinutes = null;
      } else if (input.duration.kind === "replace") {
        task.durationAmount = input.duration.amount;
        task.durationUnit = input.duration.unit;
        task.durationMinutes = normalizeDurationMinutes(
          input.duration.amount,
          input.duration.unit
        );
      }
    }
    this.applyDue(task, input.due);
    if (input.assignment.kind === "assign") task.assigneeId = input.assignment.assigneeId;
    if (input.assignment.kind === "unassign") task.assigneeId = null;
    task.updatedAt = new Date().toISOString();
  }

  move(todoistTaskId: string, projectId: string): void {
    const task = this.require(todoistTaskId);
    task.projectId = projectId;
    task.updatedAt = new Date().toISOString();
  }

  close(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    task.completedAt = new Date().toISOString();
    task.updatedAt = task.completedAt;
  }

  uncomplete(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    task.completedAt = null;
    task.updatedAt = new Date().toISOString();
  }

  tombstone(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    task.deletedAt = new Date().toISOString();
    task.updatedAt = task.deletedAt;
  }

  advanceRecurringDue(todoistTaskId: string): void {
    const task = this.require(todoistTaskId);
    if (task.dueDatetime === null) return;

    const rule = task.dueString ?? "";
    const days = rule.startsWith("every year")
      ? 365
      : rule.startsWith("every month")
        ? 30
        : /^every (Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) at/.test(
              rule
            )
          ? 7
          : 1;
    task.dueDatetime = new Date(
      new Date(task.dueDatetime).getTime() + days * 86_400_000
    ).toISOString();
    if (task.dueDate !== null) {
      task.dueDate = task.dueDatetime;
    }
    task.updatedAt = new Date().toISOString();

    for (const child of this.tasks.values()) {
      if (child.parentId === todoistTaskId) {
        child.completedAt = null;
        child.updatedAt = new Date().toISOString();
      }
    }
  }

  getTask(todoistTaskId: string): TodoistTaskMirror {
    return structuredClone(this.require(todoistTaskId));
  }

  generation(): number {
    return this.loadGeneration;
  }

  private nextCreatedId(): string {
    this.createdCount += 1;
    return `e2e-created-${this.createdCount}`;
  }

  private applyDue(task: TodoistTaskMirror, due: TodoistDueMutation): void {
    if (due.kind === "preserve") return;
    if (due.kind === "clear") {
      task.dueDate = null;
      task.dueDatetime = null;
      task.dueString = null;
      task.dueLang = null;
      task.dueTimezone = null;
      task.isRecurring = false;
      return;
    }
    task.dueDate = due.datetime;
    task.dueDatetime = due.datetime;
    task.dueString = due.string;
    task.dueLang = due.lang;
    task.dueTimezone = due.timezone;
    task.isRecurring = due.string !== null;
  }

  private require(todoistTaskId: string): TodoistTaskMirror {
    const task = this.tasks.get(todoistTaskId);
    if (task === undefined) {
      throw new Error(`e2e world has no task ${todoistTaskId}`);
    }
    return task;
  }
}

function normalizeDurationMinutes(
  amount: number | null,
  unit: string | null
): number | null {
  if (amount === null) return null;
  if (unit === "minute") return amount;
  if (unit === "hour") return amount * 60;
  return null;
}

export interface E2ESyncHold {
  entered: Promise<void>;
  release: () => void;
}

interface InternalSyncHold {
  entered: Promise<void>;
  markEntered: () => void;
  waitUntilReleased: (signal?: AbortSignal) => Promise<void>;
  release: () => void;
}

function createInternalSyncHold(onDisposed: () => void): InternalSyncHold {
  let released = false;
  let markEntered!: () => void;
  const entered = new Promise<void>((resolve) => {
    markEntered = resolve;
  });
  let resolveRelease!: () => void;
  const releasePromise = new Promise<void>((resolve) => {
    resolveRelease = resolve;
  });

  const release = (): void => {
    if (released) {
      return;
    }
    released = true;
    onDisposed();
    markEntered();
    resolveRelease();
  };

  const waitUntilReleased = async (signal?: AbortSignal): Promise<void> => {
    if (released) {
      return;
    }
    if (signal?.aborted) {
      throw new TodoistOperationError("timeout");
    }
    if (!signal) {
      await releasePromise;
      return;
    }
    await new Promise<void>((resolve, reject) => {
      const onAbort = () => {
        reject(new TodoistOperationError("timeout"));
      };
      signal.addEventListener("abort", onAbort, { once: true });
      void releasePromise.then(() => {
        signal.removeEventListener("abort", onAbort);
        resolve();
      });
    });
  };

  return {
    entered,
    markEntered,
    waitUntilReleased,
    release
  };
}

export class E2ETodoistAdapter implements TodoistAdapter {
  readonly world: E2ETodoistWorld;
  private scenario: E2EScenario = "none";
  private failNextChildAdd = false;
  private syncCount = 0;
  private armedSyncHold: InternalSyncHold | null = null;
  private activeSyncHold: InternalSyncHold | null = null;
  private readonly acceptedCommandOutcomes = new Map<string, unknown>();
  private acceptedCommandGeneration = 0;

  constructor(currentUserId: string) {
    this.world = new E2ETodoistWorld(currentUserId);
  }

  setScenario(name: E2EScenario): void {
    this.scenario = name;
  }

  currentScenario(): E2EScenario {
    return this.scenario;
  }

  /** Test-only control for exercising root-created / child-failed reconciliation. */
  setFailNextChildAdd(enabled: boolean): void {
    this.failNextChildAdd = enabled;
  }

  holdNextSync(): E2ESyncHold {
    this.releaseHeldSync();
    const hold = createInternalSyncHold(() => {
      if (this.armedSyncHold === hold) {
        this.armedSyncHold = null;
      }
      if (this.activeSyncHold === hold) {
        this.activeSyncHold = null;
      }
    });
    this.armedSyncHold = hold;
    return {
      entered: hold.entered,
      release: hold.release
    };
  }

  releaseHeldSync(): void {
    this.armedSyncHold?.release();
    this.activeSyncHold?.release();
  }

  async sync(_syncToken: string, options?: { signal?: AbortSignal }): Promise<TodoistSyncResult> {
    const hold = this.armedSyncHold;
    if (hold !== null) {
      this.armedSyncHold = null;
      this.activeSyncHold = hold;
      hold.markEntered();
      try {
        await hold.waitUntilReleased(options?.signal);
      } finally {
        if (this.activeSyncHold === hold) {
          this.activeSyncHold = null;
        }
      }
    }

    this.syncCount += 1;
    const syncToken = `e2e-sync-${this.syncCount}`;
    if (this.scenario === "accepted_stale") {
      throw new TodoistOperationError("transport", undefined, { error_code: "e2e_sync_transport_error" });
    }
    if (this.scenario === "sync_lag_effect_unknown") {
      return { syncToken, tasks: [], projects: [], collaborators: [], collaboratorStates: [] };
    }
    const state = this.world.state();
    return {
      syncToken,
      tasks: state.tasks,
      projects: state.projects,
      collaborators: state.collaborators,
      collaboratorStates: state.collaboratorStates
    };
  }

  async itemAdd(input: TodoistItemAddInput): Promise<TodoistItemAddResult> {
    if (input.kind === "child" && this.failNextChildAdd) {
      this.failNextChildAdd = false;
      throw Object.assign(new Error("e2e_forced_child_create_failure"), {
        code: "e2e_forced_child_create_failure"
      });
    }
    return this.runAcceptedCommand(input.commandUuid, () => {
      if (input.kind === "root") {
        const todoistTaskId = this.world.addRoot(input);
        return { todoistTaskId };
      }
      const todoistTaskId = this.world.addChild(input);
      return { todoistTaskId };
    });
  }

  async itemUpdate(input: TodoistItemUpdateInput): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () => this.world.applyUpdate(input));
  }

  async itemMove(input: TodoistItemMoveInput): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () =>
      this.world.move(input.todoistTaskId, input.projectId)
    );
  }

  async itemClose(input: TodoistItemIdentityInput): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () => {
      const task = this.world.getTask(input.todoistTaskId);
      if (task.isRecurring) {
        this.world.advanceRecurringDue(input.todoistTaskId);
      } else {
        this.world.close(input.todoistTaskId);
      }
    });
  }

  async itemUncomplete(input: TodoistItemIdentityInput): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () => this.world.uncomplete(input.todoistTaskId));
  }

  async itemDelete(input: TodoistItemIdentityInput): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () => this.world.tombstone(input.todoistTaskId));
  }

  private runAcceptedCommand<T>(commandUuid: string, mutation: () => T): T {
    this.clearAcceptedCommandsForWorldLoad();
    this.guardCommand();
    if (this.acceptedCommandOutcomes.has(commandUuid)) {
      return structuredClone(this.acceptedCommandOutcomes.get(commandUuid)) as T;
    }
    const outcome = mutation();
    this.acceptedCommandOutcomes.set(commandUuid, structuredClone(outcome));
    return outcome;
  }

  private clearAcceptedCommandsForWorldLoad(): void {
    const generation = this.world.generation();
    if (generation !== this.acceptedCommandGeneration) {
      this.acceptedCommandOutcomes.clear();
      this.acceptedCommandGeneration = generation;
    }
  }

  private guardCommand(): void {
    if (this.scenario === "after_command_sent_timeout") {
      throw new TodoistOperationError("timeout", undefined, { error_code: "e2e_command_timeout" });
    }
    if (this.scenario === "delivery_unknown") {
      throw new TodoistOperationError("transport", undefined, { error_code: "e2e_delivery_unknown" });
    }
    if (this.scenario === "command_rejected") {
      throw new TodoistOperationError("command_rejected", undefined, { error_code: 400, error_tag: "INVALID_ARGUMENT" });
    }
  }
}
