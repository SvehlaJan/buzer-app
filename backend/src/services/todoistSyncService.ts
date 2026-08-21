import type Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { CollaboratorRepository } from "../repositories/collaboratorRepository.js";
import type { ProjectRepository } from "../repositories/projectRepository.js";
import { ActionJournalRepository } from "../repositories/actionJournalRepository.js";
import type { TaskRepository, TaskWithCommitment } from "../repositories/taskRepository.js";
import type { TodoistAdapter } from "../todoist/todoistAdapter.js";
import { TaskEnforcementPolicy } from "../domain/taskEnforcementPolicy.js";
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

export class TodoistSyncService {
  private syncTail: Promise<void> = Promise.resolve();
  private manualSyncInFlight: Promise<void> | null = null;
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

  syncNow(): Promise<void> {
    if (this.manualSyncInFlight !== null) return this.manualSyncInFlight;
    const run = this.syncOnce().finally(() => {
      if (this.manualSyncInFlight === run) this.manualSyncInFlight = null;
    });
    this.manualSyncInFlight = run;
    return run;
  }

  async syncOnce(): Promise<void> {
    const start = Date.now();
    const run = this.syncTail.then(async () => {
      try {
        await this.syncOnceSerialized();
        this.lastSyncAt = new Date().toISOString();
        this.lastOutcome = "success";
        this.lastDurationMs = Date.now() - start;
        this.lastErrorMessage = null;
      } catch (err) {
        this.lastSyncAt = new Date().toISOString();
        this.lastOutcome = "error";
        this.lastDurationMs = Date.now() - start;
        this.lastErrorMessage = err instanceof Error ? err.message : String(err);
        throw err;
      }
    });
    this.syncTail = run.catch(() => undefined);
    return run;
  }

  private async syncOnceSerialized(allowChildResetFollowUp = true): Promise<void> {
    const preSyncTasks = this.tasks.listAll();
    const preSyncPolicy = new TaskEnforcementPolicy({
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
    const result = await this.todoist.sync(syncToken);
    const now = new Date().toISOString();
    const previousByTodoistId = new Map(
      preSyncTasks.map((task) => [task.todoistTaskId, task])
    );
    const resetPlans: ResetPlan[] = [];

    const tx = this.db.transaction(() => {
      for (const collaborator of result.collaborators) {
        this.collaborators.upsertCollaborator(collaborator);
      }

      for (const collaboratorState of result.collaboratorStates) {
        this.collaborators.upsertCollaboratorState(collaboratorState);
      }

      for (const project of result.projects) {
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

      for (const task of result.tasks) {
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
      const policy = new TaskEnforcementPolicy({
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
        const decision = this.occurrenceReconciler.decide({
          current: currentTask,
          incoming: currentTask,
          isOwned: isOwnedForReconciliation(currentTask, this.currentUserId, policy),
          wasOwned: previous === null
            ? isOwnedForReconciliation(currentTask, this.currentUserId, policy)
            : isOwnedForReconciliation(previous, this.currentUserId, preSyncPolicy),
          source: "sync",
          checkedChildIds: eligibleCheckedChildIds(currentTask, allTasks)
        });
        this.applyOccurrenceDecision(currentTask, decision, now, resetPlans);
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

    if (!allowChildResetFollowUp) {
      return;
    }
    for (const plan of resetPlans) {
      await this.resetCheckedChildren(plan);
    }
    if (resetPlans.length > 0) {
      await this.syncOnceSerialized(false);
    }
  }

  private applyOccurrenceDecision(
    task: TaskWithCommitment,
    decision: OccurrenceDecision,
    now: string,
    resetPlans: ResetPlan[]
  ): void {
    if (decision.kind === "rotate") {
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
    if (decision.kind === "reset_children_then_rotate") {
      this.tasks.markOccurrenceReconciliationPending(task.id, now);
      resetPlans.push({ task, checkedChildIds: decision.checkedChildIds });
    }
  }

  private async resetCheckedChildren(plan: ResetPlan): Promise<void> {
    const journal = new ActionJournalRepository(this.db);
    const fingerprint = JSON.stringify({
      taskId: plan.task.id,
      occurrenceKey: plan.task.occurrenceKey,
      checkedChildIds: plan.checkedChildIds
    });
    const actionId = `system-recurring-reset:${plan.task.id}:${plan.task.occurrenceKey}`;
    const now = new Date().toISOString();
    journal.reserve({
      actionId,
      deviceId: "system",
      rootTaskId: plan.task.id,
      expectedOccurrenceKey: plan.task.occurrenceKey,
      actionType: "recurring_child_reset",
      requestFingerprint: fingerprint,
      now
    });

    for (const childId of plan.checkedChildIds) {
      const reservation = journal.reserveCommand({
        actionId,
        stepKey: `uncomplete:${childId}`,
        commandUuid: randomUUID(),
        tempId: null,
        now
      });
      if (reservation.command.status === "confirmed") {
        continue;
      }
      if (reservation.command.status === "reserved") {
        journal.markCommandSent(actionId, reservation.command.stepKey, now);
      }
      await this.todoist.itemUncomplete({
        commandUuid: reservation.command.commandUuid,
        todoistTaskId: childId
      });
      journal.markCommandConfirmed(actionId, reservation.command.stepKey, { todoistTaskId: childId }, new Date().toISOString());
    }
    journal.markCompleted(actionId, { outcome: "commands_sent" }, new Date().toISOString());
  }
}

interface ResetPlan {
  task: TaskWithCommitment;
  checkedChildIds: string[];
}

function isTerminal(task: TaskWithCommitment): boolean {
  return task.completedAt !== null || task.deletedAt !== null;
}

function isOwnedForReconciliation(
  task: TaskWithCommitment,
  currentUserId: string,
  policy: TaskEnforcementPolicy
): boolean {
  return task.assigneeId === currentUserId || policy.isCompanionOwned(task);
}

function eligibleCheckedChildIds(root: TaskWithCommitment, allTasks: TaskWithCommitment[]): string[] {
  return allTasks
    .filter((task) =>
      task.parentId === root.todoistTaskId &&
      task.completedAt !== null &&
      task.deletedAt === null &&
      task.dueDatetime === null &&
      task.dueDate === null &&
      task.durationMinutes === null &&
      task.durationAmount === null &&
      !task.isRecurring &&
      task.assigneeId === null
    )
    .map((task) => task.todoistTaskId);
}
