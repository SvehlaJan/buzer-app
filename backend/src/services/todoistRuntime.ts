import type Database from "better-sqlite3";
import {
  type BackendObservability,
  backendObservability
} from "../diagnostics/backendObservability.js";
import { CollaboratorRepository } from "../repositories/collaboratorRepository.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";
import { TodoistOperationError } from "../todoist/todoistErrors.js";
import {
  MUTATION_DEADLINE_MS,
  MUTATION_MINIMUM_EXECUTION_BUDGET_MS,
  TaskMutationRunner,
  type MutationRunOptions,
  type MutationRunResult,
  type SnapshotProvider,
  type TaskSnapshot
} from "./taskMutationRunner.js";
import { TodoistCommandExecutor } from "./todoistCommandExecutor.js";
import { TodoistOperationQueue } from "./todoistOperationQueue.js";
import {
  isOperationalFailure,
  POLL_DEADLINE_MS,
  type PollOnceOptions
} from "./todoistPoller.js";
import {
  TodoistSyncService,
  type TodoistSyncSummary
} from "./todoistSyncService.js";

export type { PollOnceOptions } from "./todoistPoller.js";

const BACKEND_BUSY_MESSAGE =
  "Buzer is currently processing another Todoist operation. Nothing was sent. Please try again shortly.";
const FORCED_REFRESH_FAILED_MESSAGE = "Failed to refresh snapshot from Todoist.";

export type ForceRefreshResult =
  | { kind: "success"; snapshot: TaskSnapshot }
  | { kind: "busy"; message: string; snapshot?: TaskSnapshot }
  | { kind: "failed"; message: string; snapshot?: TaskSnapshot };

export interface ForceRefreshOptions {
  requestId?: string | undefined;
  deadlineMs?: number | undefined;
  minimumExecutionBudgetMs?: number | undefined;
  signal?: AbortSignal | undefined;
}

export interface TodoistRuntime {
  executeMutation(options: MutationRunOptions): Promise<MutationRunResult>;
  forceRefresh(options?: ForceRefreshOptions): Promise<ForceRefreshResult>;
  pollOnce(options?: PollOnceOptions): Promise<TodoistSyncSummary | void>;
}

export interface CreateTodoistRuntimeOptions {
  db: Database.Database;
  tasks: TaskRepository;
  projects: ProjectRepository;
  collaborators?: CollaboratorRepository | undefined;
  todoist: TodoistAdapter;
  snapshots: SnapshotProvider;
  currentUserId?: string | null | undefined;
  observability?: BackendObservability | undefined;
  uuidFactory?: (() => string) | undefined;
  now?: (() => number) | undefined;
}

export function createTodoistRuntime(options: CreateTodoistRuntimeOptions): TodoistRuntime {
  const now = options.now ?? Date.now;
  const observability = options.observability ?? backendObservability;
  const collaborators = options.collaborators ?? new CollaboratorRepository(options.db);
  const currentUserId = options.currentUserId ?? "";

  const queue = new TodoistOperationQueue({ now });
  const executor = new TodoistCommandExecutor(options.todoist);
  const syncService = new TodoistSyncService(
    options.db,
    options.tasks,
    options.projects,
    options.todoist,
    currentUserId,
    collaborators
  );
  const runner = new TaskMutationRunner({
    queue,
    executor,
    syncService,
    snapshotService: options.snapshots,
    observability,
    ...(options.uuidFactory !== undefined ? { uuidFactory: options.uuidFactory } : {}),
    now
  });

  const readSnapshot = (): TaskSnapshot => {
    if (typeof options.snapshots === "function") {
      return options.snapshots();
    }
    return options.snapshots.getSnapshot(new Date(now()).toISOString());
  };

  const safeReadSnapshot = (requestId?: string): TaskSnapshot | undefined => {
    try {
      return readSnapshot();
    } catch (snapshotError) {
      observability.captureException(snapshotError, {
        marker: "forced_refresh_snapshot_internal_defect",
        operation: "force_refresh",
        ...(requestId !== undefined ? { request_id: requestId } : {})
      });
      return undefined;
    }
  };

  return {
    async executeMutation(mutationOptions: MutationRunOptions): Promise<MutationRunResult> {
      return await runner.execute(mutationOptions);
    },

    async forceRefresh(forceOptions?: ForceRefreshOptions): Promise<ForceRefreshResult> {
      const requestId = forceOptions?.requestId;
      const logAttributes = {
        operation: "force_refresh",
        ...(requestId !== undefined ? { request_id: requestId } : {})
      };

      observability.info?.("todoist_operation_started", logAttributes);

      try {
        await queue.run(
          {
            operation: "force_refresh",
            ...(requestId !== undefined ? { requestId } : {}),
            deadlineMs: forceOptions?.deadlineMs ?? MUTATION_DEADLINE_MS,
            minimumExecutionBudgetMs:
              forceOptions?.minimumExecutionBudgetMs ?? MUTATION_MINIMUM_EXECUTION_BUDGET_MS,
            ...(forceOptions?.signal !== undefined ? { signal: forceOptions.signal } : {})
          },
          async ({ signal }) => {
            await syncService.syncOnce({ signal });
          }
        );

        const snapshot = readSnapshot();
        observability.info?.("todoist_operation_succeeded", logAttributes);
        return { kind: "success", snapshot };
      } catch (error) {
        if (
          error instanceof TodoistOperationError &&
          error.safeAttributes?.error_code === "backend_busy"
        ) {
          observability.warn?.("backend_busy", logAttributes);
          const snapshot = safeReadSnapshot(requestId);
          return {
            kind: "busy",
            message: BACKEND_BUSY_MESSAGE,
            ...(snapshot !== undefined ? { snapshot } : {})
          };
        }

        observability.warn?.("forced_refresh_failed", logAttributes);

        const operational =
          isOperationalFailure(error) ||
          (forceOptions?.signal?.aborted === true && error === forceOptions.signal.reason);

        if (!operational) {
          observability.captureException(error, {
            marker: "forced_refresh_internal_defect",
            ...logAttributes
          });
        }

        const snapshot = safeReadSnapshot(requestId);
        return {
          kind: "failed",
          message: operational && error instanceof Error
            ? error.message
            : FORCED_REFRESH_FAILED_MESSAGE,
          ...(snapshot !== undefined ? { snapshot } : {})
        };
      }
    },

    async pollOnce(pollOptions?: PollOnceOptions): Promise<TodoistSyncSummary | void> {
      return await queue.run(
        {
          operation: "poll",
          deadlineMs: pollOptions?.deadlineMs ?? POLL_DEADLINE_MS,
          ...(pollOptions?.signal !== undefined ? { signal: pollOptions.signal } : {})
        },
        async ({ signal }) => {
          return await syncService.syncOnce({ signal });
        }
      );
    }
  };
}
