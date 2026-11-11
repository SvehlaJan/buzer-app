import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { CollaboratorRepository } from "../repositories/collaboratorRepository.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";
import { CommitmentPolicy } from "../domain/commitmentPolicy.js";
import {
  OccurrenceReconciler,
  recurrenceFingerprint,
  type OccurrenceDecision
} from "./occurrenceReconciler.js";

export interface TodoistSyncDiagnostics {
  lastSyncAt: string | null;
  lastOutcome: "success" | "error" | null;
  durationMs: number | null;
  errorMessage?: string | null;
}

export interface TodoistSyncSummary {
  hasChanges: boolean;
  changedTaskCount: number;
  changedTaskIds: string[];
  changedProjectCount: number;
  changedCollaboratorCount: number;
}

export interface TodoistSyncOptions {
  signal?: AbortSignal;
}

/**
 * Raw non-queuing sync service for Todoist synchronization.
 *
 * NON-REENTRANCY WARNING:
 * `TodoistSyncService.syncOnce` is a raw, non-queuing primitive.
 * It does NOT acquire the `TodoistOperationQueue` itself. Top-level entry points
 * (background poller, forced refresh endpoints, mutation runners) are responsible for
 * acquiring the process-wide queue slot before calling `syncOnce`.
 * Attempting to acquire the queue inside `syncOnce` would cause deadlock.
 */
export class TodoistSyncService {
  private readonly occurrenceReconciler = new OccurrenceReconciler();
  private lastSyncAt: string | null = null;
  private lastOutcome: "success" | "error" | null = null;
  private lastDurationMs: number | null = null;
  private lastErrorMessage: string | null = null;

  constructor(
    private readonly db: Database.Database,
    private readonly tasks: TaskRepository,
    private readonly projects: ProjectRepository,
    private readonly todoist: TodoistAdapter,
    private readonly currentUserId = "",
    private readonly collaborators = new CollaboratorRepository(db)
  ) {}

  getLastSyncDiagnostics(): TodoistSyncDiagnostics {
    return {
      lastSyncAt: this.lastSyncAt,
      lastOutcome: this.lastOutcome,
      durationMs: this.lastDurationMs,
      ...(this.lastErrorMessage ? { errorMessage: this.lastErrorMessage } : {})
    };
  }

  async syncOnce(options?: TodoistSyncOptions): Promise<TodoistSyncSummary> {
    const start = Date.now();
    try {
      const summary = await this.performSync(options);
      this.lastSyncAt = new Date().toISOString();
      this.lastOutcome = "success";
      this.lastDurationMs = Date.now() - start;
      this.lastErrorMessage = null;
      return summary;
    } catch (err) {
      this.lastSyncAt = new Date().toISOString();
      this.lastOutcome = "error";
      this.lastDurationMs = Date.now() - start;
      this.lastErrorMessage = err instanceof Error ? err.message : String(err);
      throw err;
    }
  }

  private async performSync(options?: TodoistSyncOptions): Promise<TodoistSyncSummary> {
    const preSyncTasks = this.tasks.listAll();
    const preSyncPolicy = new CommitmentPolicy({
      currentUserId: this.currentUserId,
      projectsById: new Map(
        this.projects.listActive().map((project) => [project.todoistProjectId, project])
      )
    });
    const current = this.db
      .prepare("select sync_token as syncToken, mirror_contract_version as mirrorContractVersion from todoist_sync_state where id = 1")
      .get() as { syncToken: string; mirrorContractVersion: number } | undefined;
    const requiresFullSync = (current?.mirrorContractVersion ?? 0) < 3;
    const syncToken = requiresFullSync || this.projects.listActive().length === 0
      ? "*"
      : current?.syncToken ?? "*";
    const result = options?.signal === undefined
      ? await this.todoist.sync(syncToken)
      : await this.todoist.sync(syncToken, { signal: options.signal });
    const now = new Date().toISOString();
    const previousByTodoistId = new Map(
      preSyncTasks.map((task) => [task.todoistTaskId, task])
    );

    const tx = this.db.transaction(() => {
      for (const collaborator of result.collaborators ?? []) {
        this.collaborators.upsertCollaborator(collaborator);
      }

      for (const collaboratorState of result.collaboratorStates ?? []) {
        this.collaborators.upsertCollaboratorState(collaboratorState);
      }

      for (const project of result.projects ?? []) {
        this.projects.upsertProject({
          todoistProjectId: project.todoistProjectId,
          name: project.name,
          shared: project.shared,
          canAssignTasks: project.canAssignTasks,
          isInboxProject: project.isInboxProject,
          isDeleted: project.isDeleted,
          isArchived: project.isArchived,
          rawJson: JSON.stringify(project.rawJson),
          updatedAt: project.updatedAt
        });
      }

      for (const task of result.tasks ?? []) {
        const existing = previousByTodoistId.get(task.todoistTaskId) ?? null;
        const id = existing?.id ?? randomUUID();
        this.tasks.upsertTodoistTask({
          id,
          todoistTaskId: task.todoistTaskId,
          title: task.title,
          description: task.description,
          projectId: task.projectId,
          parentId: task.parentId,
          childOrder: task.childOrder,
          assigneeId: task.assigneeId,
          creatorId: task.creatorId,
          dueDate: task.dueDate,
          dueDatetime: task.dueDatetime,
          dueString: task.dueString,
          dueLang: task.dueLang,
          dueTimezone: task.dueTimezone,
          isRecurring: task.isRecurring,
          durationAmount: task.durationAmount,
          durationUnit: task.durationUnit,
          durationMinutes: task.durationMinutes,
          completedAt: task.completedAt,
          deletedAt: task.deletedAt,
          rawJson: JSON.stringify(task.rawJson),
          updatedAt: task.updatedAt
        });

        const terminalState = task.deletedAt !== null
          ? "deleted"
          : task.completedAt !== null
            ? "completed"
            : null;
        if (terminalState !== null && existing?.state !== terminalState) {
          this.tasks.updateCommitmentState(id, {
            state: terminalState,
            incrementScheduleVersion: true,
            updatedAt: task.updatedAt
          });
        }
      }

      const allTasks = this.tasks.listAll();
      const policy = new CommitmentPolicy({
        currentUserId: this.currentUserId,
        projectsById: new Map(
          this.projects.listActive().map((project) => [project.todoistProjectId, project])
        )
      });
      for (const currentTask of allTasks) {
        if (currentTask.parentId !== null || isTerminal(currentTask)) {
          continue;
        }
        const previous = previousByTodoistId.get(currentTask.todoistTaskId) ?? null;
        const isOwnedNow = isOwnedForReconciliation(currentTask, allTasks, this.currentUserId, policy);
        const decision = this.occurrenceReconciler.decide({
          current: currentTask,
          incoming: currentTask,
          isOwned: isOwnedNow,
          wasOwned: previous === null
            ? isOwnedNow
            : isOwnedForReconciliation(previous, preSyncTasks, this.currentUserId, preSyncPolicy),
          source: "sync",
          checkedChildIds: policy.eligibleCheckedChildTodoistIds(currentTask, allTasks)
        });
        this.applyOccurrenceDecision(currentTask, decision, now);
      }

      this.db.prepare(`
        insert into todoist_sync_state (id, sync_token, updated_at)
        values (1, ?, ?)
        on conflict(id) do update set sync_token = excluded.sync_token, updated_at = excluded.updated_at
      `).run(result.syncToken, now);
      if (syncToken === "*") {
        this.db.prepare(`
          update todoist_sync_state
          set mirror_contract_version = 3, updated_at = ?
          where id = 1
        `).run(now);
      }
    });

    tx();

    const changedTaskCount = (result.tasks ?? []).length;
    const changedTaskIds = (result.tasks ?? []).slice(0, 10).map((t) => t.todoistTaskId);
    const changedProjectCount = (result.projects ?? []).length;
    const changedCollaboratorCount = (result.collaborators?.length ?? 0) + (result.collaboratorStates?.length ?? 0);
    const hasChanges = changedTaskCount > 0 || changedProjectCount > 0 || changedCollaboratorCount > 0;

    return {
      hasChanges,
      changedTaskCount,
      changedTaskIds,
      changedProjectCount,
      changedCollaboratorCount
    };
  }

  private applyOccurrenceDecision(
    task: TaskWithCommitment,
    decision: OccurrenceDecision,
    now: string
  ): void {
    if (decision.kind === "rotate" || decision.kind === "reset_children_then_rotate") {
      this.tasks.rotateOccurrence(task.id, {
        occurrenceKey: randomUUID(),
        occurrenceDueDatetime: task.dueDatetime,
        occurrenceRecurrenceFingerprint: recurrenceFingerprint(task),
        updatedAt: now
      });
      return;
    }
    if (decision.kind === "leave_ownership") {
      this.tasks.leaveOccurrenceOwnership(task.id, now);
      return;
    }
  }
}

function isTerminal(task: TaskWithCommitment): boolean {
  return task.completedAt !== null || task.deletedAt !== null;
}

function isOwnedForReconciliation(
  task: TaskWithCommitment,
  allTasks: readonly TaskWithCommitment[],
  currentUserId: string,
  policy: CommitmentPolicy
): boolean {
  const result = policy.classify(task, allTasks);
  return (
    result.classification !== "NOT_OWNED" &&
    result.classification !== "ROOT_UNASSIGNED" &&
    (task.assigneeId === currentUserId || result.isCompanionOwned)
  );
}
