import { randomUUID } from "node:crypto";
import { DomainError } from "../domain/errors.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistCommand } from "../todoist/todoistMutationGateway.js";
import type { TaskMutationRunner, MutationRunResult, TaskSnapshot } from "./taskMutationRunner.js";
import type { TaskSnapshotService } from "./taskSnapshotService.js";

export type ChildMutation =
  | { type: "add"; clientMutationId: string; title: string; description?: string | null }
  | { type: "edit"; clientMutationId: string; childId: string; expectedChildRevision: string; title: string; description?: string | null }
  | { type: "delete"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "check"; clientMutationId: string; childId: string; expectedChildRevision: string }
  | { type: "uncheck"; clientMutationId: string; childId: string; expectedChildRevision: string };

export interface ChecklistMutationRequest {
  mutations: readonly ChildMutation[];
  expectedOccurrenceKey?: string | null | undefined;
  requestId?: string | undefined;
  signal?: AbortSignal | undefined;
  deadlineMs?: number | undefined;
  minimumExecutionBudgetMs?: number | undefined;
}

export interface TodoistChecklistServiceOptions {
  snapshots?: TaskSnapshotService | (() => TaskSnapshot);
  now?: () => string;
}

/**
 * The product-service boundary for checklist child mutations.
 * Validates local SQLite state before dispatching validated one-shot Todoist commands
 * through TaskMutationRunner.
 */
export class TodoistChecklistService {
  private readonly snapshotProvider: () => TaskSnapshot;
  private readonly now: () => string;

  constructor(
    private readonly tasks: TaskRepository,
    private readonly runner: TaskMutationRunner,
    snapshotsOrOptions?: TaskSnapshotService | (() => TaskSnapshot) | TodoistChecklistServiceOptions,
    options: TodoistChecklistServiceOptions = {}
  ) {
    if (typeof snapshotsOrOptions === "function") {
      this.snapshotProvider = snapshotsOrOptions;
      this.now = options.now ?? (() => new Date().toISOString());
    } else if (snapshotsOrOptions && "getSnapshot" in snapshotsOrOptions) {
      const snapService = snapshotsOrOptions as TaskSnapshotService;
      this.snapshotProvider = () => snapService.getSnapshot(new Date().toISOString());
      this.now = options.now ?? (() => new Date().toISOString());
    } else if (snapshotsOrOptions && typeof snapshotsOrOptions === "object") {
      const opts = snapshotsOrOptions as TodoistChecklistServiceOptions;
      if (typeof opts.snapshots === "function") {
        this.snapshotProvider = opts.snapshots;
      } else if (opts.snapshots && "getSnapshot" in opts.snapshots) {
        const snapService = opts.snapshots;
        this.snapshotProvider = () => snapService.getSnapshot(new Date().toISOString());
      } else {
        this.snapshotProvider = () => ({ tasks: this.tasks.listAll() }) as unknown as TaskSnapshot;
      }
      this.now = opts.now ?? (() => new Date().toISOString());
    } else {
      this.snapshotProvider = () => ({ tasks: this.tasks.listAll() }) as unknown as TaskSnapshot;
      this.now = options.now ?? (() => new Date().toISOString());
    }
  }

  async mutate(rootTaskId: string, request: ChecklistMutationRequest): Promise<MutationRunResult> {
    assertRequestShape(request.mutations);
    this.assertProjectedTree(rootTaskId);

    const root = this.requireRoot(rootTaskId);
    if (
      request.expectedOccurrenceKey !== undefined &&
      request.expectedOccurrenceKey !== null &&
      request.expectedOccurrenceKey !== root.occurrenceKey
    ) {
      throw DomainError.staleOccurrence(this.snapshotProvider());
    }

    this.validateMutations(root, request.mutations);
    const commands = this.buildChildCommands(root, request.mutations);

    return await this.runner.execute({
      operation: "mutate_checklist",
      ...(request.requestId !== undefined ? { requestId: request.requestId } : {}),
      taskId: rootTaskId,
      ...(root.occurrenceKey !== null ? { occurrenceKey: root.occurrenceKey } : {}),
      commands,
      ...(request.deadlineMs !== undefined ? { deadlineMs: request.deadlineMs } : {}),
      ...(request.minimumExecutionBudgetMs !== undefined ? { minimumExecutionBudgetMs: request.minimumExecutionBudgetMs } : {}),
      ...(request.signal !== undefined ? { signal: request.signal } : {})
    });
  }

  async check(
    rootTaskId: string,
    mutation: Extract<ChildMutation, { type: "check" }>,
    options?: { expectedOccurrenceKey?: string; requestId?: string; signal?: AbortSignal }
  ): Promise<MutationRunResult> {
    return this.mutate(rootTaskId, {
      mutations: [mutation],
      ...(options?.expectedOccurrenceKey !== undefined ? { expectedOccurrenceKey: options.expectedOccurrenceKey } : {}),
      ...(options?.requestId !== undefined ? { requestId: options.requestId } : {}),
      ...(options?.signal !== undefined ? { signal: options.signal } : {})
    });
  }

  async uncheck(
    rootTaskId: string,
    mutation: Extract<ChildMutation, { type: "uncheck" }>,
    options?: { expectedOccurrenceKey?: string; requestId?: string; signal?: AbortSignal }
  ): Promise<MutationRunResult> {
    return this.mutate(rootTaskId, {
      mutations: [mutation],
      ...(options?.expectedOccurrenceKey !== undefined ? { expectedOccurrenceKey: options.expectedOccurrenceKey } : {}),
      ...(options?.requestId !== undefined ? { requestId: options.requestId } : {}),
      ...(options?.signal !== undefined ? { signal: options.signal } : {})
    });
  }

  validateMutations(root: TaskWithCommitment, mutations: readonly ChildMutation[]): void {
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
      if (child.mirrorRevision !== this.parseRevision(mutation.expectedChildRevision)) {
        throw DomainError.staleChild(this.snapshotProvider());
      }
      if (mutation.type === "edit") {
        requiredTitle(mutation.title);
      }
    }
  }

  buildChildCommands(root: TaskWithCommitment, mutations: readonly ChildMutation[]): TodoistCommand[] {
    const commands: TodoistCommand[] = [];

    for (const mutation of mutations) {
      if (mutation.type === "add") {
        const title = requiredTitle(mutation.title);
        const tempId = randomUUID();
        commands.push({
          type: "item_add",
          input: {
            kind: "child",
            tempId,
            title,
            description: normalizeDescription(mutation.description),
            projectId: requiredProjectId(root),
            parentTodoistTaskId: root.todoistTaskId
          }
        });
        continue;
      }

      const child = this.requireChild(root, mutation.childId);

      if (mutation.type === "edit") {
        const title = requiredTitle(mutation.title);
        commands.push({
          type: "item_update",
          input: {
            todoistTaskId: child.todoistTaskId,
            title,
            description: normalizeDescription(mutation.description),
            due: { kind: "preserve" },
            assignment: { kind: "preserve" }
          }
        });
        continue;
      }

      if (mutation.type === "delete") {
        commands.push({
          type: "item_delete",
          input: { todoistTaskId: child.todoistTaskId }
        });
        continue;
      }

      if (mutation.type === "check") {
        if (child.completedAt === null) {
          commands.push({
            type: "item_close",
            input: { todoistTaskId: child.todoistTaskId }
          });
        }
        continue;
      }

      if (mutation.type === "uncheck") {
        if (child.completedAt !== null) {
          commands.push({
            type: "item_uncomplete",
            input: { todoistTaskId: child.todoistTaskId }
          });
        }
        continue;
      }
    }

    return commands;
  }

  private assertProjectedTree(rootTaskId: string): void {
    const root = this.requireRoot(rootTaskId);
    const all = this.tasks.listAll();
    const children = all.filter((task) => task.parentId === root.todoistTaskId && task.deletedAt === null);
    if (children.length > 30 || all.some((task) => children.some((child) => child.todoistTaskId === task.parentId && task.deletedAt === null))) {
      throw DomainError.unsupportedHierarchy(this.snapshotProvider());
    }
    if (children.some((child) =>
      child.projectId !== root.projectId || child.dueDatetime !== null || child.dueDate !== null ||
      child.dueString !== null || child.dueLang !== null || child.dueTimezone !== null || child.isRecurring ||
      child.durationMinutes !== null || child.durationAmount !== null || child.durationUnit !== null || child.assigneeId !== null
    )) {
      throw DomainError.unsupportedHierarchy(this.snapshotProvider());
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

  private parseRevision(value: string): number {
    const match = /^r:(\d+)$/.exec(value);
    if (match === null) throw DomainError.staleChild(this.snapshotProvider());
    return Number(match[1]);
  }

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
      if (typeof source.title !== "string" || (source.description !== null && source.description !== undefined && typeof source.description !== "string")) {
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

function requiredProjectId(root: TaskWithCommitment): string {
  if (root.projectId === null) throw domainError("root_project_required", "Checklist root must have a Todoist project");
  return root.projectId;
}

function requiredTitle(value: string): string {
  const title = value.trim();
  if (title.length === 0) throw domainError("child_title_required", "Checklist title is required");
  return title;
}

function normalizeDescription(value?: string | null): string | null {
  const normalized = value?.trim() ?? "";
  return normalized.length === 0 ? null : normalized;
}

function domainError(code: string, message: string, statusCode = 422): DomainError {
  return new DomainError(code, message, undefined, statusCode);
}
