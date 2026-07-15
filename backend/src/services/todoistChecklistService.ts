import { randomUUID } from "node:crypto";
import { DomainError } from "../domain/errors.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistMutationGateway } from "../todoist/todoistMutationGateway.js";
import type { TaskActionCommandContext, TaskActionCoordinator } from "./taskActionCoordinator.js";

export type ChildMutation =
  | { type: "add"; clientMutationId: string; title: string; description: string | null }
  | { type: "edit"; clientMutationId: string; childId: string; expectedChildRevision: string; title: string; description: string | null }
  | { type: "delete"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "check"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "uncheck"; clientMutationId: string; childId: string; expectedChildRevision: string };

export interface ChecklistActionMetadata {
  actionId: string;
  deviceId: string;
  expectedOccurrenceKey: string;
}

export interface ChecklistMutationRequest {
  mutations: readonly ChildMutation[];
  action?: ChecklistActionMetadata;
}

export interface ChecklistChildOutcome {
  clientMutationId: string;
  childId: string | null;
  outcome: "completed" | "failed" | "stale_child";
  errorCode: string | null;
}

export interface ChecklistMutationResult {
  outcome: "completed" | "partial";
  root: TaskWithCommitment;
  childOutcomes: ChecklistChildOutcome[];
  snapshot: unknown;
}

export interface TodoistChecklistServiceOptions {
  /** The coordinator provides the durable root lock and per-command journal when supplied. */
  coordinator?: TaskActionCoordinator;
  /** Sync must refresh the local Todoist mirror before validation and after a focused action. */
  synchronize?: () => Promise<void>;
  snapshot?: () => unknown;
  now?: () => string;
}

/**
 * The sole product-service boundary for checklist children. Root mutations deliberately stay out
 * of this service so a child can never inherit root scheduling/assignment update paths.
 */
export class TodoistChecklistService {
  private readonly synchronize: () => Promise<void>;
  private readonly snapshot: () => unknown;
  private readonly now: () => string;

  constructor(
    private readonly tasks: TaskRepository,
    private readonly todoist: TodoistMutationGateway,
    private readonly options: TodoistChecklistServiceOptions = {}
  ) {
    this.synchronize = options.synchronize ?? (async () => undefined);
    this.snapshot = options.snapshot ?? (() => ({ tasks: this.tasks.listAll() }));
    this.now = options.now ?? (() => new Date().toISOString());
  }

  async mutate(rootTaskId: string, request: ChecklistMutationRequest): Promise<ChecklistMutationResult> {
    assertRequestShape(request.mutations);
    if (request.mutations.length === 0) {
      await this.synchronize();
      const root = this.requireRoot(rootTaskId);
      return { outcome: "completed", root, childOutcomes: [], snapshot: this.snapshot() };
    }

    if (request.action !== undefined && this.options.coordinator === undefined) {
      throw domainError(
        "checklist_action_coordinator_required",
        "Checklist actions with retry metadata require durable coordination",
        503
      );
    }

    if (request.action !== undefined && this.options.coordinator !== undefined) {
      const createdChildIds = new Map<string, string>();
      const response = await this.options.coordinator.execute({
        actionId: request.action.actionId,
        deviceId: request.action.deviceId,
        rootTaskId,
        expectedOccurrenceKey: request.action.expectedOccurrenceKey,
        actionType: "checklist_mutation",
        payload: { mutations: request.mutations },
        validate: () => this.assertProjectedTree(rootTaskId),
        isEffectPresent: () => this.effectsPresent(rootTaskId, request.mutations, createdChildIds),
        responseAfterReconciliation: () => this.responseFromCurrentState(rootTaskId, request.mutations),
        compactResponse: (response) => ({
          outcome: response.outcome,
          childOutcomes: response.childOutcomes
        }),
        execute: (context) => this.executeMutations(rootTaskId, request.mutations, context, createdChildIds)
      });
      if (response.kind === "pending") {
        throw DomainError.reconciliationPending(this.snapshot());
      }
      return { ...response.response, snapshot: this.snapshot() };
    }

    await this.synchronize();
    this.assertProjectedTree(rootTaskId);
    const response = await this.executeMutations(rootTaskId, request.mutations);
    await this.synchronize();
    return { ...response, snapshot: this.snapshot() };
  }

  async check(rootTaskId: string, mutation: Extract<ChildMutation, { type: "check" }>, action?: ChecklistActionMetadata) {
    return this.mutate(rootTaskId, { mutations: [mutation], ...(action === undefined ? {} : { action }) });
  }

  async uncheck(rootTaskId: string, mutation: Extract<ChildMutation, { type: "uncheck" }>, action?: ChecklistActionMetadata) {
    return this.mutate(rootTaskId, { mutations: [mutation], ...(action === undefined ? {} : { action }) });
  }

  private async executeMutations(
    rootTaskId: string,
    mutations: readonly ChildMutation[],
    context?: TaskActionCommandContext,
    createdChildIds?: Map<string, string>
  ): Promise<Omit<ChecklistMutationResult, "snapshot">> {
    const root = this.requireRoot(rootTaskId);
    const outcomes: ChecklistChildOutcome[] = [];
    for (const mutation of mutations) {
      try {
        const outcome = await this.executeOne(root, mutation, context, createdChildIds);
        outcomes.push(outcome);
      } catch (error) {
        outcomes.push(failedOutcome(mutation, error));
      }
    }
    const latestRoot = this.requireRoot(rootTaskId);
    return {
      outcome: outcomes.some((outcome) => outcome.outcome !== "completed") ? "partial" : "completed",
      root: latestRoot,
      childOutcomes: outcomes
    };
  }

  private async executeOne(
    root: TaskWithCommitment,
    mutation: ChildMutation,
    context: TaskActionCommandContext | undefined,
    createdChildIds: Map<string, string> | undefined
  ): Promise<ChecklistChildOutcome> {
    if (mutation.type === "add") {
      const children = this.childrenOf(root);
      if (children.length >= 30) {
        throw domainError("too_many_children", "A commitment can have at most 30 checklist items");
      }
      const title = requiredTitle(mutation.title);
      const tempId = randomUUID();
      const created = await this.send(context, `child:${mutation.clientMutationId}:add`, tempId, (commandUuid) =>
        this.todoist.itemAdd({
          kind: "child",
          commandUuid,
          tempId,
          title,
          description: normalizeDescription(mutation.description),
          projectId: requiredProjectId(root),
          parentTodoistTaskId: root.todoistTaskId
        })
      );
      if (created.todoistTaskId === null) {
        throw domainError("todoist_child_create_unconfirmed", "Todoist did not return a checklist item ID", 502);
      }
      this.tasks.createChecklistChild({
        id: `checklist:${created.todoistTaskId}`,
        todoistTaskId: created.todoistTaskId,
        parentTodoistTaskId: root.todoistTaskId,
        projectId: root.projectId,
        title,
        description: normalizeDescription(mutation.description),
        childOrder: this.childrenOf(root).length,
        updatedAt: this.now()
      });
      createdChildIds?.set(mutation.clientMutationId, created.todoistTaskId);
      return completedOutcome(mutation, created.todoistTaskId);
    }

    const child = this.requireChild(root, mutation.childId);
    if (child.mirrorRevision !== parseRevision(mutation.expectedChildRevision)) {
      return staleOutcome(mutation);
    }

    if (mutation.type === "edit") {
      const title = requiredTitle(mutation.title);
      await this.send(context, `child:${child.todoistTaskId}:edit`, null, (commandUuid) => this.todoist.itemUpdate({
        commandUuid,
        todoistTaskId: child.todoistTaskId,
        title,
        description: normalizeDescription(mutation.description),
        due: { kind: "preserve" },
        assignment: { kind: "preserve" }
      }));
      this.tasks.updateChecklistChild(child.id, title, normalizeDescription(mutation.description), this.now());
      return completedOutcome(mutation, child.todoistTaskId);
    }

    if (mutation.type === "delete") {
      await this.send(context, `child:${child.todoistTaskId}:delete`, null, (commandUuid) => this.todoist.itemDelete({
        commandUuid,
        todoistTaskId: child.todoistTaskId
      }));
      this.tasks.deleteChecklistChild(child.id, this.now());
      return completedOutcome(mutation, child.todoistTaskId);
    }

    const requestedChecked = mutation.type === "check";
    const observedChecked = child.completedAt !== null;
    if (requestedChecked === observedChecked) {
      return completedOutcome(mutation, child.todoistTaskId);
    }
    await this.send(context, `child:${child.todoistTaskId}:${mutation.type}`, null, (commandUuid) => requestedChecked
      ? this.todoist.itemClose({ commandUuid, todoistTaskId: child.todoistTaskId })
      : this.todoist.itemUncomplete({ commandUuid, todoistTaskId: child.todoistTaskId })
    );
    this.tasks.setChecklistChildChecked(child.id, requestedChecked, this.now());
    return completedOutcome(mutation, child.todoistTaskId);
  }

  private async send<TResult>(
    context: TaskActionCommandContext | undefined,
    stepKey: string,
    tempId: string | null,
    send: (commandUuid: string) => Promise<TResult>
  ): Promise<TResult> {
    if (context !== undefined) return context.executeCommand(stepKey, send, { tempId });
    return send(randomUUID());
  }

  private responseFromCurrentState(rootTaskId: string, mutations: readonly ChildMutation[]): Omit<ChecklistMutationResult, "snapshot"> {
    const root = this.requireRoot(rootTaskId);
    return {
      outcome: "completed",
      root,
      childOutcomes: mutations.map((mutation) => completedOutcome(
        mutation,
        mutation.type === "add" ? null : mutation.childId
      ))
    };
  }

  private effectsPresent(
    rootTaskId: string,
    mutations: readonly ChildMutation[],
    createdChildIds: ReadonlyMap<string, string>
  ): boolean {
    const root = this.requireRoot(rootTaskId);
    return mutations.every((mutation) => {
      if (mutation.type === "add") {
        const createdId = createdChildIds.get(mutation.clientMutationId);
        if (createdId !== undefined) {
          const created = this.tasks.getByTodoistTaskId(createdId);
          return created?.parentId === root.todoistTaskId && created.deletedAt === null;
        }
        return this.childrenOf(root).some((child) =>
          child.title === mutation.title.trim() && child.description === normalizeDescription(mutation.description)
        );
      }
      const child = this.tasks.getByTodoistTaskId(mutation.childId);
      if (child === null || child.parentId !== root.todoistTaskId) return mutation.type === "delete" && child?.deletedAt !== null;
      if (mutation.type === "delete") return child.deletedAt !== null;
      if (mutation.type === "check") return child.completedAt !== null;
      if (mutation.type === "uncheck") return child.completedAt === null;
      return child.title === mutation.title && child.description === normalizeDescription(mutation.description);
    });
  }

  private assertProjectedTree(rootTaskId: string): void {
    const root = this.requireRoot(rootTaskId);
    const all = this.tasks.listAll();
    const children = all.filter((task) => task.parentId === root.todoistTaskId && task.deletedAt === null);
    if (children.length > 30 || all.some((task) => children.some((child) => child.todoistTaskId === task.parentId && task.deletedAt === null))) {
      throw DomainError.unsupportedHierarchy(this.snapshot());
    }
    if (children.some((child) =>
      child.projectId !== root.projectId || child.dueDatetime !== null || child.dueDate !== null ||
      child.durationMinutes !== null || child.durationAmount !== null || child.isRecurring || child.assigneeId !== null
    )) {
      throw DomainError.unsupportedHierarchy(this.snapshot());
    }
  }

  private requireRoot(rootTaskId: string): TaskWithCommitment {
    const root = this.tasks.getById(rootTaskId);
    if (root === null) throw domainError("task_not_found", "Task not found", 404);
    if (root.parentId !== null) throw domainError("checklist_root_required", "Checklist mutations require a root task");
    return root;
  }

  private childrenOf(root: TaskWithCommitment): TaskWithCommitment[] {
    return this.tasks.listAll().filter((task) => task.parentId === root.todoistTaskId && task.deletedAt === null);
  }

  private requireChild(root: TaskWithCommitment, childId: string): TaskWithCommitment {
    const child = this.tasks.getByTodoistTaskId(childId);
    if (child === null || child.parentId !== root.todoistTaskId || child.deletedAt !== null) {
      throw domainError("child_not_under_root", "Checklist item is not a direct child of this commitment", 404);
    }
    return child;
  }
}

function assertRequestShape(mutations: readonly ChildMutation[]): void {
  for (const mutation of mutations as readonly Record<string, unknown>[]) {
    const forbidden = [
      "parent", "parentId", "parent_id", "due", "dueDate", "dueDatetime", "duration", "durationMinutes",
      "recurrence", "isRecurring", "assigneeId", "assignment", "responsibleUid", "children", "descendants"
    ];
    if (forbidden.some((field) => field in mutation)) {
      throw domainError("forbidden_child_field", "Checklist items cannot carry scheduling, assignment, or descendants");
    }
  }
}

function parseRevision(value: string): number {
  const match = /^r:(\d+)$/.exec(value);
  if (match === null) throw domainError("stale_child", "Checklist item revision is invalid", 409);
  return Number(match[1]);
}

function requiredProjectId(root: TaskWithCommitment): string {
  if (root.projectId === null) throw domainError("root_project_required", "Checklist root must have a Todoist project");
  return root.projectId;
}

function requiredTitle(value: string): string {
  const title = value.trim();
  if (title.length === 0) throw domainError("child_title_required", "Checklist title is required");
  return title;
}

function normalizeDescription(value: string | null): string | null {
  const normalized = value?.trim() ?? "";
  return normalized.length === 0 ? null : normalized;
}

function completedOutcome(mutation: ChildMutation, childId: string | null): ChecklistChildOutcome {
  return { clientMutationId: mutation.clientMutationId, childId, outcome: "completed", errorCode: null };
}

function staleOutcome(mutation: Exclude<ChildMutation, { type: "add" }>): ChecklistChildOutcome {
  return { clientMutationId: mutation.clientMutationId, childId: mutation.childId, outcome: "stale_child", errorCode: "stale_child" };
}

function failedOutcome(mutation: ChildMutation, error: unknown): ChecklistChildOutcome {
  const code = error instanceof DomainError ? error.code : "todoist_child_mutation_failed";
  return {
    clientMutationId: mutation.clientMutationId,
    childId: mutation.type === "add" ? null : mutation.childId,
    outcome: code === "stale_child" ? "stale_child" : "failed",
    errorCode: code
  };
}

function domainError(code: string, message: string, statusCode = 422): DomainError {
  return new DomainError(code, message, undefined, statusCode);
}
