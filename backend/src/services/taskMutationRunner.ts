import { randomUUID } from "node:crypto";
import { type BackendObservability, backendObservability } from "../diagnostics/backendObservability.js";
import { DomainError } from "../domain/errors.js";
import { TodoistOperationError } from "../todoist/todoistErrors.js";
import type { TodoistCommand } from "../todoist/todoistAdapter.js";
import type { TodoistCommandExecutor } from "./todoistCommandExecutor.js";
import type { QueueExecutionContext, TodoistOperationQueue } from "./todoistOperationQueue.js";
import type { TaskSnapshotService } from "./taskSnapshotService.js";
import { isOperationalFailure } from "./todoistPoller.js";

export const MUTATION_DEADLINE_MS = 13_000;
export const MUTATION_MINIMUM_EXECUTION_BUDGET_MS = 5_000;

export type TaskSnapshot = ReturnType<TaskSnapshotService["getSnapshot"]>;

export type MutationRunResult =
  | { kind: "success"; snapshot: TaskSnapshot }
  | { kind: "rejected"; snapshot?: TaskSnapshot }
  | { kind: "delivery_unknown"; snapshot?: TaskSnapshot }
  | { kind: "accepted_stale" }
  | { kind: "busy" };

export interface MutationRunOptions {
  operation: string;
  requestId?: string;
  taskId?: string;
  occurrenceKey?: string;
  commands?: TodoistCommand[];
  prepareCommands?: () => TodoistCommand[];
  deadlineMs?: number;
  minimumExecutionBudgetMs?: number;
  signal?: AbortSignal;
}

export interface SyncOnceService {
  syncOnce(options?: { signal?: AbortSignal }): Promise<unknown>;
}

export type SnapshotProvider =
  | { getSnapshot(serverTime: string): TaskSnapshot }
  | (() => TaskSnapshot);

export interface TaskMutationRunnerDependencies {
  queue: TodoistOperationQueue;
  executor: TodoistCommandExecutor;
  syncService: SyncOnceService;
  snapshotService: SnapshotProvider;
  observability?: BackendObservability;
  uuidFactory?: () => string;
  now?: () => number;
}

export class TaskMutationRunner {
  private readonly queue: TodoistOperationQueue;
  private readonly executor: TodoistCommandExecutor;
  private readonly syncService: SyncOnceService;
  private readonly snapshotService: SnapshotProvider;
  private readonly observability: BackendObservability;
  private readonly uuidFactory: () => string;
  private readonly now: () => number;

  constructor(
    queueOrDeps: TodoistOperationQueue | TaskMutationRunnerDependencies,
    executor?: TodoistCommandExecutor,
    syncService?: SyncOnceService,
    snapshotService?: SnapshotProvider,
    observability?: BackendObservability,
    uuidFactory?: () => string,
    now?: () => number
  ) {
    if ("queue" in queueOrDeps && !("run" in queueOrDeps)) {
      const deps = queueOrDeps as TaskMutationRunnerDependencies;
      this.queue = deps.queue;
      this.executor = deps.executor;
      this.syncService = deps.syncService;
      this.snapshotService = deps.snapshotService;
      this.observability = deps.observability ?? backendObservability;
      this.uuidFactory = deps.uuidFactory ?? randomUUID;
      this.now = deps.now ?? Date.now;
    } else {
      this.queue = queueOrDeps as TodoistOperationQueue;
      this.executor = executor!;
      this.syncService = syncService!;
      this.snapshotService = snapshotService!;
      this.observability = observability ?? backendObservability;
      this.uuidFactory = uuidFactory ?? randomUUID;
      this.now = now ?? Date.now;
    }
  }

  async execute(options: MutationRunOptions): Promise<MutationRunResult> {
    const deadlineMs = options.deadlineMs ?? MUTATION_DEADLINE_MS;
    const minimumExecutionBudgetMs = options.minimumExecutionBudgetMs ?? MUTATION_MINIMUM_EXECUTION_BUDGET_MS;
    const activeDeadlineState: {
      outcome: "delivery_unknown" | "rejected" | "accepted_stale";
    } = {
      outcome: "delivery_unknown"
    };
    type TerminalLog = {
      level: "info" | "warn";
      marker: string;
      attributes?: Record<string, unknown>;
    };
    let pendingTerminalLog: TerminalLog | undefined;
    let terminalLogged = false;
    const emitTerminal = (terminal: TerminalLog): void => {
      if (terminalLogged) {
        return;
      }
      terminalLogged = true;
      const attributes = {
        operation: options.operation,
        ...(terminal.attributes ?? {}),
        ...(options.requestId !== undefined ? { request_id: options.requestId } : {}),
        ...(options.taskId !== undefined ? { task_id: options.taskId } : {}),
        ...(options.occurrenceKey !== undefined ? { occurrence_key: options.occurrenceKey } : {})
      };
      if (terminal.level === "info") {
        this.observability.info?.(terminal.marker, attributes);
      } else {
        this.observability.warn?.(terminal.marker, attributes);
      }
    };

    this.observability.info?.("todoist_operation_started", {
      operation: options.operation,
      ...(options.commands !== undefined ? { command_count: options.commands.length } : {}),
      ...(options.requestId !== undefined ? { request_id: options.requestId } : {}),
      ...(options.taskId !== undefined ? { task_id: options.taskId } : {}),
      ...(options.occurrenceKey !== undefined ? { occurrence_key: options.occurrenceKey } : {})
    });

    try {
      const result: MutationRunResult = await this.queue.run(
        {
          operation: options.operation,
          ...(options.requestId !== undefined ? { requestId: options.requestId } : {}),
          deadlineMs,
          minimumExecutionBudgetMs,
          ...(options.signal !== undefined ? { signal: options.signal } : {})
        },
        async (ctx: QueueExecutionContext) => {
          const executionDeadlineMs = this.now() + ctx.remainingMs;
          const commands = options.prepareCommands?.() ?? options.commands ?? [];
          const resolvedTempIds = new Map<string, string>();

          for (const [i, originalCommand] of commands.entries()) {
            const command = resolveTemporaryIds(originalCommand, resolvedTempIds);
            const commandUuid = this.uuidFactory();

            const cmdResult = await this.executor.executeCommand({
              command,
              commandUuid,
              deadlineMs: executionDeadlineMs,
              signal: ctx.signal,
              now: this.now
            });

            if (cmdResult.outcome === "rejected" || cmdResult.outcome === "delivery_unknown") {
              activeDeadlineState.outcome = cmdResult.outcome;
              const marker = cmdResult.outcome === "rejected"
                ? "todoist_command_rejected"
                : "todoist_delivery_unknown";

              pendingTerminalLog = {
                level: "warn",
                marker,
                attributes: {
                  command_type: command.type,
                  command_index: i,
                  error_kind: cmdResult.error.kind
                }
              };

              if (cmdResult.error.kind === "parse") {
                this.captureDefect(cmdResult.error, "todoist_command_parse_defect", options);
              }

              // Best-effort post-failure syncOnce to refresh SQLite
              let snapshot: TaskSnapshot | undefined;
              try {
                await this.syncService.syncOnce({ signal: ctx.signal });
                snapshot = this.getSnapshot();
              } catch (syncError) {
                if (!isOperationalFailure(syncError)) {
                  this.captureDefect(syncError, "post_failure_sync_internal_defect", options);
                }
              }

              return {
                kind: cmdResult.outcome,
                ...(snapshot !== undefined ? { snapshot } : {})
              };
            }

            if (
              cmdResult.outcome === "success" &&
              command.type === "item_add" &&
              typeof cmdResult.todoistTaskId === "string"
            ) {
              resolvedTempIds.set(command.input.tempId, cmdResult.todoistTaskId);
            }
          }

          // All commands in the batch succeeded (or 0 commands).
          // Perform post-command syncOnce exactly once.
          activeDeadlineState.outcome = "accepted_stale";
          try {
            await this.syncService.syncOnce({ signal: ctx.signal });
            const snapshot = this.getSnapshot();

            pendingTerminalLog = {
              level: "info",
              marker: "todoist_operation_succeeded",
              attributes: { command_count: commands.length }
            };

            return {
              kind: "success",
              snapshot
            };
          } catch (syncError) {
            pendingTerminalLog = {
              level: "warn",
              marker: "todoist_accepted_snapshot_stale",
              attributes: { command_count: commands.length }
            };

            if (!isOperationalFailure(syncError)) {
              this.captureDefect(syncError, "post_ack_sync_internal_defect", options);
            }

            return {
              kind: "accepted_stale"
            };
          }
        }
      );
      if (pendingTerminalLog !== undefined) {
        emitTerminal(pendingTerminalLog);
      }
      return result;
    } catch (error) {
      if (
        error instanceof TodoistOperationError &&
        error.safeAttributes?.failure_stage === "active"
      ) {
        const marker = activeDeadlineState.outcome === "rejected"
          ? "todoist_command_rejected"
          : activeDeadlineState.outcome === "accepted_stale"
            ? "todoist_accepted_snapshot_stale"
            : "todoist_delivery_unknown";
        emitTerminal({
          level: "warn",
          marker,
          attributes: { failure_stage: "active" }
        });
        return { kind: activeDeadlineState.outcome };
      }

      if (
        error instanceof TodoistOperationError &&
        error.safeAttributes?.error_code === "backend_busy"
      ) {
        emitTerminal({ level: "warn", marker: "backend_busy" });
        return { kind: "busy" };
      }

      if (error instanceof DomainError || isExpectedValidationFailure(error)) {
        emitTerminal({
          level: "warn",
          marker: "todoist_operation_validation_failed",
          attributes: {
            error_code: error.code
          }
        });
        throw error;
      }

      if (options.signal?.aborted && error === options.signal.reason) {
        emitTerminal({
          level: "warn",
          marker: "todoist_operation_cancelled"
        });
        throw error;
      }

      emitTerminal({
        level: "warn",
        marker: "todoist_operation_failed",
        attributes: {
          error_kind: error instanceof TodoistOperationError ? error.kind : "internal"
        }
      });

      if (!isOperationalFailure(error)) {
        this.captureDefect(error, "mutation_runner_internal_defect", options);
      }

      throw error;
    }
  }

  private getSnapshot(): TaskSnapshot {
    if (typeof this.snapshotService === "function") {
      return this.snapshotService();
    }
    return this.snapshotService.getSnapshot(new Date(this.now()).toISOString());
  }

  private captureDefect(error: unknown, marker: string, options: MutationRunOptions): void {
    if (this.observability.captureException) {
      this.observability.captureException(error, {
        marker,
        operation: options.operation,
        ...(options.requestId !== undefined ? { request_id: options.requestId } : {}),
        ...(options.taskId !== undefined ? { task_id: options.taskId } : {}),
        ...(options.occurrenceKey !== undefined ? { occurrence_key: options.occurrenceKey } : {})
      });
    } else {
      this.observability.captureBackgroundFailure(
        `buzer.backend.${marker}`,
        error,
        {
          operation: options.operation,
          ...(options.requestId !== undefined ? { request_id: options.requestId } : {}),
          ...(options.taskId !== undefined ? { task_id: options.taskId } : {}),
          ...(options.occurrenceKey !== undefined ? { occurrence_key: options.occurrenceKey } : {})
        }
      );
    }
  }
}

function resolveTemporaryIds(
  command: TodoistCommand,
  resolvedTempIds: ReadonlyMap<string, string>
): TodoistCommand {
  if (command.type !== "item_add" || command.input.kind !== "child") {
    return command;
  }
  const permanentParentId = resolvedTempIds.get(command.input.parentTodoistTaskId);
  if (permanentParentId === undefined) {
    return command;
  }
  return {
    ...command,
    input: {
      ...command.input,
      parentTodoistTaskId: permanentParentId
    }
  };
}

function isExpectedValidationFailure(error: unknown): error is { code: string; statusCode: number } {
  if (typeof error !== "object" || error === null) {
    return false;
  }
  const candidate = error as { code?: unknown; statusCode?: unknown };
  return typeof candidate.code === "string"
    && typeof candidate.statusCode === "number"
    && candidate.statusCode >= 400
    && candidate.statusCode < 500;
}
