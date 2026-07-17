import { randomUUID } from "node:crypto";
import { DomainError } from "../domain/errors.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistMutationGateway } from "../todoist/todoistMutationGateway.js";
import type { TaskActionCommandContext, TaskActionCoordinator, TaskActionEffectContext } from "./taskActionCoordinator.js";

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

export interface ChecklistMutationResult {
  root: TaskWithCommitment;
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
  private readonly snapshot: () => unknown;
  private readonly now: () => string;

  constructor(
    private readonly tasks: TaskRepository,
    private readonly todoist: TodoistMutationGateway,
    private readonly options: TodoistChecklistServiceOptions = {}
  ) {
    this.snapshot = options.snapshot ?? (() => ({ tasks: this.tasks.listAll() }));
    this.now = options.now ?? (() => new Date().toISOString());
  }

  async mutate(rootTaskId: string, request: ChecklistMutationRequest): Promise<ChecklistMutationResult> {
    assertRequestShape(request.mutations);
    if (request.action === undefined) {
      throw domainError(
        "checklist_action_metadata_required",
        "Checklist mutations require durable action metadata",
        503
      );
    }
    if (this.options.coordinator === undefined) {
      throw domainError(
        "checklist_action_coordinator_required",
        "Checklist actions with retry metadata require durable coordination",
        503
      );
    }

    const createdChildIds = new Map<string, string>();
    const response = await this.options.coordinator.execute({
      actionId: request.action.actionId,
      deviceId: request.action.deviceId,
      rootTaskId,
      expectedOccurrenceKey: request.action.expectedOccurrenceKey,
      actionType: "checklist_mutation",
      payload: { mutations: request.mutations },
      validate: () => {
        this.assertProjectedTree(rootTaskId);
        this.validateMutations(rootTaskId, request.mutations);
      },
      isEffectPresent: (_root, context) => this.effectsPresent(rootTaskId, request.mutations, createdChildIds, context),
      responseAfterReconciliation: () => undefined,
      compactResponse: () => null,
      execute: (context) => this.executeMutations(rootTaskId, request.mutations, context, createdChildIds)
    });
    if (response.kind === "pending") {
      throw DomainError.reconciliationPending(this.snapshot());
    }
    return {
      root: this.requireRoot(rootTaskId),
      snapshot: this.snapshot()
    };
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
    context: TaskActionCommandContext,
    createdChildIds?: Map<string, string>
  ): Promise<void> {
    const root = this.requireRoot(rootTaskId);
    for (const mutation of mutations) {
      await this.executeOne(root, mutation, context, createdChildIds);
    }
  }

  /** Every deterministic child error is found before the first Todoist command.
   * Once a command was sent, the coordinator must preserve the action as pending. */
  private validateMutations(rootTaskId: string, mutations: readonly ChildMutation[]): void {
    const root = this.requireRoot(rootTaskId);
    let projectedChildCount = this.childrenOf(root).length;
    for (const mutation of mutations) {
      if (mutation.type === "add") {
        if (projectedChildCount >= 30) {
          throw domainError("too_many_children", "A commitment can have at most 30 checklist items");
        }
        requiredTitle(mutation.title);
        requiredProjectId(root);
        projectedChildCount += 1;
        continue;
      }
      const child = this.requireChild(root, mutation.childId);
      if (child.mirrorRevision !== parseRevision(mutation.expectedChildRevision)) {
        throw domainError("stale_child", "Checklist item changed on another device", 409);
      }
      if (mutation.type === "edit") requiredTitle(mutation.title);
    }
  }

  private async executeOne(
    root: TaskWithCommitment,
    mutation: ChildMutation,
    context: TaskActionCommandContext,
    createdChildIds: Map<string, string> | undefined
  ): Promise<void> {
    if (mutation.type === "add") {
      const children = this.childrenOf(root);
      if (children.length >= 30) {
        throw domainError("too_many_children", "A commitment can have at most 30 checklist items");
      }
      const title = requiredTitle(mutation.title);
      const tempId = randomUUID();
      const created = await this.send(context, `child:${mutation.clientMutationId}:add`, tempId, (commandUuid, reservedTempId) =>
        this.todoist.itemAdd({
          kind: "child",
          commandUuid,
          tempId: reservedTempId ?? tempId,
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
      return;
    }

    const child = this.requireChild(root, mutation.childId);
    if (child.mirrorRevision !== parseRevision(mutation.expectedChildRevision)) {
      throw domainError("stale_child", "Checklist item changed on another device", 409);
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
      return;
    }

    if (mutation.type === "delete") {
      await this.send(context, `child:${child.todoistTaskId}:delete`, null, (commandUuid) => this.todoist.itemDelete({
        commandUuid,
        todoistTaskId: child.todoistTaskId
      }));
      this.tasks.deleteChecklistChild(child.id, this.now());
      return;
    }

    const requestedChecked = mutation.type === "check";
    const observedChecked = child.completedAt !== null;
    if (requestedChecked === observedChecked) {
      return;
    }
    await this.send(context, `child:${child.todoistTaskId}:${mutation.type}`, null, (commandUuid) => requestedChecked
      ? this.todoist.itemClose({ commandUuid, todoistTaskId: child.todoistTaskId })
      : this.todoist.itemUncomplete({ commandUuid, todoistTaskId: child.todoistTaskId })
    );
    this.tasks.setChecklistChildChecked(child.id, requestedChecked, this.now());
    return;
  }

  private async send<TResult>(
    context: TaskActionCommandContext,
    stepKey: string,
    tempId: string | null,
    send: (commandUuid: string, tempId: string | null) => Promise<TResult>
  ): Promise<TResult> {
    return context.executeCommand(stepKey, send, { tempId });
  }

  private effectsPresent(
    rootTaskId: string,
    mutations: readonly ChildMutation[],
    createdChildIds: ReadonlyMap<string, string>,
    context: TaskActionEffectContext
  ): boolean {
    const root = this.requireRoot(rootTaskId);
    return mutations.filter((mutation) => context.reservedStepKeys.has(commandStepKey(mutation))).every((mutation) => {
      if (mutation.type === "add") {
        const createdId = createdChildIds.get(mutation.clientMutationId);
        if (createdId !== undefined) {
          const created = this.tasks.getByTodoistTaskId(createdId);
          return created?.parentId === root.todoistTaskId && created.deletedAt === null;
        }
        const command = context.commands.get(commandStepKey(mutation));
        const todoistTaskId = command === undefined ? null : todoistTaskIdFromCommandResult(command.result);
        if (todoistTaskId === null) return false;
        const created = this.tasks.getByTodoistTaskId(todoistTaskId);
        return created?.parentId === root.todoistTaskId && created.deletedAt === null;
      }
      const child = this.findChild(root, mutation.childId);
      if (child === null || child.parentId !== root.todoistTaskId) return mutation.type === "delete" && child?.deletedAt !== null;
      if (mutation.type === "delete") return child.deletedAt !== null;
      if (mutation.type === "check") return child.completedAt !== null;
      if (mutation.type === "uncheck") return child.completedAt === null;
      return child.title === requiredTitle(mutation.title) && child.description === normalizeDescription(mutation.description);
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
      child.dueString !== null || child.dueLang !== null || child.dueTimezone !== null || child.isRecurring ||
      child.durationMinutes !== null || child.durationAmount !== null || child.durationUnit !== null || child.assigneeId !== null
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
    const child = this.findChild(root, childId);
    if (child === null || child.parentId !== root.todoistTaskId || child.deletedAt !== null) {
      throw domainError("child_not_under_root", "Checklist item is not a direct child of this commitment", 404);
    }
    return child;
  }

  /** Snapshot/API child IDs are mirror IDs. Accept legacy Todoist IDs only for
   * pre-v3 in-flight actions; all current clients receive and send the local ID. */
  private findChild(root: TaskWithCommitment, childId: string): TaskWithCommitment | null {
    const child = this.tasks.getById(childId) ?? this.tasks.getByTodoistTaskId(childId);
    return child === null || child.parentId !== root.todoistTaskId ? null : child;
  }
}

function assertRequestShape(mutations: readonly ChildMutation[]): void {
  for (const mutation of mutations as readonly unknown[]) {
    if (typeof mutation !== "object" || mutation === null || Array.isArray(mutation)) {
      throw invalidChildMutation();
    }
    const source = mutation as Record<string, unknown>;
    const type = source.type;
    const allowedKeys = childMutationKeys(type);
    if (allowedKeys === null || !allowedKeys.every((key) => hasOwn(source, key)) || Object.keys(source).some((key) => !allowedKeys.includes(key))) {
      throw invalidChildMutation();
    }
    if (typeof source.clientMutationId !== "string") throw invalidChildMutation();
    if (type === "add" || type === "edit") {
      if (typeof source.title !== "string" || (source.description !== null && typeof source.description !== "string")) {
        throw invalidChildMutation();
      }
    }
    if (type !== "add") {
      if (typeof source.childId !== "string" || typeof source.expectedChildRevision !== "string") {
        throw invalidChildMutation();
      }
    }
  }
}

function childMutationKeys(type: unknown): readonly string[] | null {
  switch (type) {
    case "add": return ["type", "clientMutationId", "title", "description"];
    case "edit": return ["type", "clientMutationId", "childId", "expectedChildRevision", "title", "description"];
    case "delete":
    case "check":
    case "uncheck": return ["type", "clientMutationId", "childId", "expectedChildRevision"];
    default: return null;
  }
}

function hasOwn(source: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(source, key);
}

function invalidChildMutation(): DomainError {
  return domainError("forbidden_child_field", "Checklist items only accept their declared mutation fields");
}

function commandStepKey(mutation: ChildMutation): string {
  return mutation.type === "add"
    ? `child:${mutation.clientMutationId}:add`
    : `child:${mutation.childId}:${mutation.type}`;
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

function todoistTaskIdFromCommandResult(result: unknown): string | null {
  if (typeof result !== "object" || result === null) return null;
  const value = "kind" in result && (result as { kind?: unknown }).kind === "value"
    ? (result as { value?: unknown }).value
    : result;
  if (typeof value !== "object" || value === null) return null;
  const todoistTaskId = (value as { todoistTaskId?: unknown }).todoistTaskId;
  return typeof todoistTaskId === "string" ? todoistTaskId : null;
}

function domainError(code: string, message: string, statusCode = 422): DomainError {
  return new DomainError(code, message, undefined, statusCode);
}
