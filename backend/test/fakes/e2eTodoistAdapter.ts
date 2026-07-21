import type {
  TodoistAdapter,
  TodoistCreateTaskInput,
  TodoistCreateTaskResult,
  TodoistLegacyUpdateCommandIdentities,
  TodoistRescheduleInput,
  TodoistSyncResult,
  TodoistUpdateTaskInput
} from "../../src/todoist/todoistAdapter.js";
import type {
  TodoistChildItemAddInput,
  TodoistDueMutation,
  TodoistItemAddInput,
  TodoistItemAddResult,
  TodoistItemIdentityInput,
  TodoistItemMoveInput,
  TodoistItemUpdateInput,
  TodoistRecurringCompletionInput,
  TodoistRootItemAddInput
} from "../../src/todoist/todoistMutationGateway.js";
import type {
  TodoistProjectMirror,
  TodoistTaskMirror
} from "../../src/todoist/todoistTypes.js";

export type E2EScenario =
  | "none"
  | "after_command_sent_timeout"
  | "sync_lag_effect_unknown";

export const E2E_SCENARIOS: readonly E2EScenario[] = [
  "none",
  "after_command_sent_timeout",
  "sync_lag_effect_unknown"
];

/**
 * In-memory Todoist-side state. It models only Todoist mutations behind the adapter
 * interface; the real backend retains responsibility for snapshots, checklists, and
 * reconciliation.
 */
export class E2ETodoistWorld {
  private tasks = new Map<string, TodoistTaskMirror>();
  private projects = new Map<string, TodoistProjectMirror>();
  private createdCount = 0;
  private loadGeneration = 0;

  constructor(private readonly currentUserId: string) {}

  load(seed: {
    tasks: TodoistTaskMirror[];
    projects: TodoistProjectMirror[];
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
    this.createdCount = 0;
    this.loadGeneration += 1;
  }

  state(): { tasks: TodoistTaskMirror[]; projects: TodoistProjectMirror[] } {
    return {
      tasks: [...this.tasks.values()].map((task) => structuredClone(task)),
      projects: [...this.projects.values()].map((project) => structuredClone(project))
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
      durationAmount: input.duration?.amount ?? null,
      durationUnit: input.duration?.unit ?? null,
      durationMinutes: normalizeDurationMinutes(
        input.duration?.amount ?? null,
        input.duration?.unit ?? null
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
      task.durationAmount = input.duration.amount;
      task.durationUnit = input.duration.unit;
      task.durationMinutes = normalizeDurationMinutes(
        input.duration.amount,
        input.duration.unit
      );
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

export class E2ETodoistAdapter implements TodoistAdapter {
  readonly world: E2ETodoistWorld;
  private scenario: E2EScenario = "none";
  private syncCount = 0;
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

  async sync(_syncToken: string): Promise<TodoistSyncResult> {
    this.syncCount += 1;
    const syncToken = `e2e-sync-${this.syncCount}`;
    if (this.scenario === "sync_lag_effect_unknown") {
      return { syncToken, tasks: [], projects: [], collaborators: [], collaboratorStates: [] };
    }
    const state = this.world.state();
    return {
      syncToken,
      tasks: state.tasks,
      projects: state.projects,
      collaborators: [],
      collaboratorStates: []
    };
  }

  async itemAdd(input: TodoistItemAddInput): Promise<TodoistItemAddResult> {
    return this.runAcceptedCommand(input.commandUuid, () => ({
      todoistTaskId:
        input.kind === "root"
          ? this.world.addRoot(input)
          : this.world.addChild(input)
    }));
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
    this.runAcceptedCommand(input.commandUuid, () => this.world.close(input.todoistTaskId));
  }

  async itemUncomplete(input: TodoistItemIdentityInput): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () => this.world.uncomplete(input.todoistTaskId));
  }

  async itemDelete(input: TodoistItemIdentityInput): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () => this.world.tombstone(input.todoistTaskId));
  }

  async itemUpdateDateComplete(
    input: TodoistRecurringCompletionInput
  ): Promise<void> {
    this.runAcceptedCommand(input.commandUuid, () =>
      this.world.advanceRecurringDue(input.task.todoistTaskId)
    );
  }

  async createTask(
    input: TodoistCreateTaskInput
  ): Promise<TodoistCreateTaskResult> {
    this.guardCommand();
    const todoistTaskId = this.world.addRoot({
      kind: "root",
      commandUuid: "e2e-legacy-create",
      tempId: "e2e-legacy-create",
      title: input.title,
      description: input.description ?? null,
      projectId: input.projectId ?? "project-test",
      assigneeId: input.assigneeId,
      due:
        input.dueDatetime === null
          ? { kind: "clear" }
          : {
              kind: "replace",
              datetime: input.dueDatetime,
              string: null,
              lang: null,
              timezone: null
            },
      duration:
        input.durationMinutes === null
          ? null
          : { amount: input.durationMinutes, unit: "minute" }
    });
    return { todoistTaskId, task: this.world.getTask(todoistTaskId) };
  }

  async completeTask(todoistTaskId: string, _commandUuid: string): Promise<void> {
    this.runAcceptedCommand(_commandUuid, () => this.world.close(todoistTaskId));
  }

  async rescheduleTask(
    input: TodoistRescheduleInput,
    _commandUuid: string
  ): Promise<void> {
    this.runAcceptedCommand(_commandUuid, () =>
      this.world.applyUpdate({
        commandUuid: "e2e-legacy-reschedule",
        todoistTaskId: input.todoistTaskId,
        ...(input.description === undefined ? {} : { description: input.description }),
        duration: { amount: input.durationMinutes, unit: "minute" },
        due: {
          kind: "replace",
          datetime: input.dueDatetime,
          string: null,
          lang: null,
          timezone: null
        },
        assignment: { kind: "preserve" }
      })
    );
  }

  async updateTask(
    input: TodoistUpdateTaskInput,
    commands: TodoistLegacyUpdateCommandIdentities
  ): Promise<void> {
    if (commands.moveCommandUuid !== undefined) {
      this.runAcceptedCommand(commands.moveCommandUuid, () =>
        this.world.move(input.todoistTaskId, input.projectId)
      );
    }
    this.runAcceptedCommand(commands.updateCommandUuid, () =>
      this.world.applyUpdate({
        commandUuid: "e2e-legacy-update",
        todoistTaskId: input.todoistTaskId,
        title: input.title,
        description: input.description,
        duration: { amount: input.durationMinutes, unit: "minute" },
        due: {
          kind: "replace",
          datetime: input.dueDatetime,
          string: null,
          lang: null,
          timezone: null
        },
        assignment:
          input.assigneeId === null
            ? { kind: "unassign" }
            : { kind: "assign", assigneeId: input.assigneeId }
      })
    );
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
      throw new Error("e2e_command_timeout");
    }
  }
}
