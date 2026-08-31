import type Database from "better-sqlite3";
import { createHash, randomUUID } from "node:crypto";
import { DomainError } from "../domain/errors.js";
import {
  ActionJournalRepository,
  type ActionCommandEntry,
  type ActionJournalEntry
} from "../repositories/actionJournalRepository.js";
import { RootTaskMutex } from "./rootTaskMutex.js";

export interface CoordinatorRoot {
  id: string;
  mirrorRevision: number;
  occurrenceKey: string | null;
  reconciliationStatus: "confirmed" | "pending";
}

export interface RootTaskStore {
  getById(id: string): CoordinatorRoot | null;
  markOccurrenceReconciliationPending(id: string, updatedAt: string): void;
  confirmOccurrenceReconciliation(id: string, updatedAt: string): void;
}

export interface TaskSynchronizer {
  syncOnce(): Promise<unknown>;
}

export interface TaskActionCommandContext {
  /** The durable journal reservation time: stable across retries of one action ID. */
  readonly actionCreatedAt: string;
  executeCommand<TResult>(
    stepKey: string,
    send: (commandUuid: string, tempId: string | null) => Promise<TResult>,
    options?: { tempId?: string | null }
  ): Promise<TResult>;
}

export interface TaskActionEffectContext {
  /** Persisted command steps that may require an authoritative effect check. */
  reservedStepKeys: ReadonlySet<string>;
  /** Durable command identities/results, used to prove effects without guessing from user content. */
  commands: ReadonlyMap<string, ActionCommandEntry>;
}

export interface CoordinatedTaskAction<TResponse = unknown, TPayload = unknown> {
  actionId: string;
  deviceId: string;
  rootTaskId: string;
  expectedOccurrenceKey: string | null;
  expectedTaskRevision?: string;
  actionType: string;
  payload: TPayload;
  /** Root creation is journaled before Todoist assigns the root ID, so no local row exists yet. */
  allowRootAbsent?: boolean;
  /** Rebinds a create journal to the mirror row reconstructed by authoritative synchronization. */
  resolveRootTaskId?: () => string | null;
  validate?: (root: CoordinatorRoot) => void;
  isEffectPresent?: (root: CoordinatorRoot, context: TaskActionEffectContext) => boolean;
  /** Builds the replay response after an external command is confirmed by a later sync. */
  responseAfterReconciliation(root: CoordinatorRoot, context: TaskActionEffectContext): TResponse;
  /** The journal stores this reduced value, never the full action response. */
  compactResponse(response: TResponse): unknown;
  /** Runs durable post-effect work when replay observes an already-applied local effect. */
  reconcile?: (context: TaskActionCommandContext, root: CoordinatorRoot) => Promise<void>;
  finalize?: (root: CoordinatorRoot) => void;
  useAuthoritativeResponse?: boolean;
  execute(context: TaskActionCommandContext): Promise<TResponse>;
}

export type TaskActionCoordinatorResult<TResponse> =
  | { kind: "completed"; response: TResponse }
  | { kind: "pending"; actionId: string };

export interface TaskActionCoordinatorOptions {
  db: Database.Database;
  roots: RootTaskStore;
  sync: TaskSynchronizer;
  mutex?: RootTaskMutex;
  now?: () => string;
  commandUuid?: () => string;
}

/**
 * The only in-memory part of action coordination is the root mutex. The journal is deliberately
 * the recovery source of truth, so a recreated coordinator can replay an action safely.
 */
export class TaskActionCoordinator {
  private readonly journal: ActionJournalRepository;
  private readonly mutex: RootTaskMutex;
  private readonly now: () => string;
  private readonly commandUuid: () => string;

  constructor(private readonly options: TaskActionCoordinatorOptions) {
    this.journal = new ActionJournalRepository(options.db);
    this.mutex = options.mutex ?? new RootTaskMutex();
    this.now = options.now ?? (() => new Date().toISOString());
    this.commandUuid = options.commandUuid ?? randomUUID;
  }

  async execute<TResponse, TPayload>(
    action: CoordinatedTaskAction<TResponse, TPayload>
  ): Promise<TaskActionCoordinatorResult<TResponse>> {
    const reservation = this.journal.reserve({
      actionId: action.actionId,
      deviceId: action.deviceId,
      rootTaskId: action.rootTaskId,
      expectedOccurrenceKey: action.expectedOccurrenceKey,
      actionType: action.actionType,
      requestFingerprint: actionFingerprint(action.actionType, {
        payload: action.payload,
        expectedOccurrenceKey: action.expectedOccurrenceKey,
        ...(action.expectedTaskRevision === undefined ? {} : { expectedTaskRevision: action.expectedTaskRevision })
      }),
      now: this.now()
    });

    if (reservation.entry.status === "completed") {
      return completedReplay<TResponse>(reservation.entry);
    }
    if (reservation.entry.status === "failed") {
      throw replayFailure(reservation.entry);
    }

    return this.mutex.run(action.rootTaskId, async () => {
      const current = this.journal.get(action.actionId);
      if (current === null) {
        throw new Error("action_journal_reservation_missing");
      }
      if (current.status === "completed") {
        return completedReplay<TResponse>(current);
      }
      if (current.status === "failed") {
        throw replayFailure(current);
      }

      const isReplay = reservation.kind === "replay";
      await this.options.sync.syncOnce();
      let resolvedAction = await this.resolveRoot(action);
      let root = this.requireRoot(resolvedAction.rootTaskId, resolvedAction.allowRootAbsent === true);
      const effectContext = this.effectContext(resolvedAction.actionId);
      const effect = this.effectStatus(resolvedAction, root, effectContext);
      let sentCommand = false;
      const commandContext: TaskActionCommandContext = {
        actionCreatedAt: current.createdAt,
        executeCommand: async <TResult>(
          stepKey: string,
          send: (commandUuid: string, tempId: string | null) => Promise<TResult>,
          commandOptions?: { tempId?: string | null }
        ) => {
          if (resolvedAction.responseAfterReconciliation === undefined) {
            throw new Error("reconciliation_response_provider_required");
          }
          const command = this.reserveCommand(resolvedAction.actionId, stepKey, commandOptions?.tempId ?? null);
          if (command.status === "confirmed") {
            sentCommand = true;
            return restoredCommandResult<TResult>(command.result);
          }
          if (command.status === "reserved") {
            this.journal.markCommandSent(resolvedAction.actionId, stepKey, this.now());
          }
          sentCommand = true;
          const result = await send(command.commandUuid, command.tempId);
          this.journal.markCommandConfirmed(resolvedAction.actionId, stepKey, storedCommandResult(result), this.now());
          return result;
        }
      };

      if (isReplay && effect === "present") {
        await resolvedAction.reconcile?.(commandContext, root);
        const response = resolvedAction.responseAfterReconciliation(root, effectContext);
        this.completeAction(resolvedAction, root, response, true);
        return { kind: "completed", response };
      }

      if (
        isReplay &&
        (current.status === "reconciliation_pending" || this.journal.hasDispatchedCommand(action.actionId)) &&
        effect === "unknown"
      ) {
        return this.markPending(resolvedAction, pendingResponse(current.error) ?? null, new Error("todoist_effect_unverified"));
      }

      if (current.status === "reconciliation_pending" && effect === "absent") {
        this.options.roots.confirmOccurrenceReconciliation(resolvedAction.rootTaskId, this.now());
        root = this.requireRoot(resolvedAction.rootTaskId, resolvedAction.allowRootAbsent === true);
      }

      try {
        this.validate(resolvedAction, root);
      } catch (error) {
        if (error instanceof DomainError && error.statusCode !== 202) {
          this.journal.markFailed(resolvedAction.actionId, serializeDomainError(error), this.now());
        }
        throw error;
      }

      let response: TResponse;
      try {
        response = await resolvedAction.execute(commandContext);
      } catch (error) {
        if (sentCommand || this.journal.hasDispatchedCommand(resolvedAction.actionId)) {
          return this.markPending(resolvedAction, pendingResponse(current.error) ?? null, error);
        }
        if (error instanceof DomainError && error.statusCode !== 202) {
          this.journal.markFailed(resolvedAction.actionId, serializeDomainError(error), this.now());
        }
        throw error;
      }

      try {
        await this.options.sync.syncOnce();
      } catch (error) {
        return this.markPending(resolvedAction, resolvedAction.compactResponse(response), error);
      }

      resolvedAction = await this.resolveRoot(resolvedAction);
      const confirmedRoot = this.requireRoot(resolvedAction.rootTaskId, resolvedAction.allowRootAbsent === true);
      if (sentCommand && this.effectStatus(resolvedAction, confirmedRoot) !== "present") {
        return this.markPending(resolvedAction, resolvedAction.compactResponse(response), new Error("todoist_effect_not_confirmed"));
      }

      const confirmedResponse = resolvedAction.useAuthoritativeResponse === true
        ? resolvedAction.responseAfterReconciliation(confirmedRoot, this.effectContext(resolvedAction.actionId))
        : response;
      this.completeAction(resolvedAction, confirmedRoot, confirmedResponse);
      return { kind: "completed", response: confirmedResponse };
    });
  }

  private completeAction<TResponse>(
    action: CoordinatedTaskAction<TResponse>,
    root: CoordinatorRoot,
    response: TResponse,
    confirmReconciliation = false
  ): void {
    const compactResponse = action.compactResponse(response);
    const complete = this.options.db.transaction(() => {
      action.finalize?.(root);
      this.journal.markCompleted(action.actionId, compactResponse, this.now());
      if (confirmReconciliation) {
        this.options.roots.confirmOccurrenceReconciliation(action.rootTaskId, this.now());
      }
    });
    complete();
  }

  private async resolveRoot<TResponse, TPayload>(
    action: CoordinatedTaskAction<TResponse, TPayload>
  ): Promise<CoordinatedTaskAction<TResponse, TPayload>> {
    const rootTaskId = action.resolveRootTaskId?.() ?? action.rootTaskId;
    if (rootTaskId !== action.rootTaskId) {
      this.journal.reassignRootTask(action.actionId, rootTaskId, this.now());
      await this.mutex.handoff(action.rootTaskId, rootTaskId);
      return { ...action, rootTaskId };
    }
    return action;
  }

  private reserveCommand(actionId: string, stepKey: string, tempId: string | null): ActionCommandEntry {
    return this.journal.reserveCommand({
      actionId,
      stepKey,
      commandUuid: this.commandUuid(),
      tempId,
      now: this.now()
    }).command;
  }

  private validate(action: CoordinatedTaskAction, root: CoordinatorRoot): void {
    if (root.reconciliationStatus === "pending") {
      throw DomainError.reconciliationPending(snapshotContext(root));
    }
    if (action.expectedOccurrenceKey !== null && root.occurrenceKey !== action.expectedOccurrenceKey) {
      throw DomainError.staleOccurrence(snapshotContext(root));
    }
    if (action.expectedTaskRevision !== undefined && action.expectedTaskRevision !== `r:${root.mirrorRevision}`) {
      throw DomainError.staleTask(snapshotContext(root));
    }
    action.validate?.(root);
  }

  private requireRoot(rootTaskId: string, allowAbsent = false): CoordinatorRoot {
    const root = this.options.roots.getById(rootTaskId);
    if (root === null) {
      if (allowAbsent) {
        return {
          id: rootTaskId,
          mirrorRevision: 0,
          occurrenceKey: null,
          reconciliationStatus: "confirmed"
        };
      }
      throw DomainError.staleTask({ taskId: rootTaskId, snapshot: null });
    }
    return root;
  }

  private effectStatus(
    action: CoordinatedTaskAction,
    root: CoordinatorRoot,
    context = this.effectContext(action.actionId)
  ): "present" | "absent" | "unknown" {
    if (action.isEffectPresent === undefined) {
      return "unknown";
    }
    return action.isEffectPresent(root, context) ? "present" : "absent";
  }

  private effectContext(actionId: string): TaskActionEffectContext {
    const commands = this.journal.listCommands(actionId);
    const reservedStepKeys = new Set(
      commands
        .filter((command) => command.status !== "failed")
        .map((command) => command.stepKey)
    );
    return { reservedStepKeys, commands: new Map(commands.map((command) => [command.stepKey, command])) };
  }

  private markPending<TResponse>(
    action: CoordinatedTaskAction<TResponse>,
    response: unknown,
    error: unknown
  ): TaskActionCoordinatorResult<TResponse> {
    const now = this.now();
    this.options.roots.markOccurrenceReconciliationPending(action.rootTaskId, now);
    const current = this.journal.get(action.actionId);
    if (current?.status === "reconciliation_pending") {
      return { kind: "pending", actionId: action.actionId };
    }
    this.journal.markReconciliationPending(action.actionId, {
      code: "reconciliation_pending",
      response,
      cause: compactError(error)
    }, now);
    return { kind: "pending", actionId: action.actionId };
  }
}

export function actionFingerprint(actionType: string, payload: unknown): string {
  const canonical = JSON.stringify({ actionType, payload: canonicalJsonValue(payload) });
  return createHash("sha256").update(canonical).digest("hex");
}

function canonicalJsonValue(value: unknown): unknown {
  if (value === null || typeof value === "string" || typeof value === "boolean") {
    return value;
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new TypeError("Action payload must be JSON serializable");
    return value;
  }
  if (Array.isArray(value)) return value.map(canonicalJsonValue);
  if (typeof value === "object") {
    const source = value as Record<string, unknown>;
    const sorted: Record<string, unknown> = {};
    for (const key of Object.keys(source).sort()) {
      if (source[key] !== undefined) sorted[key] = canonicalJsonValue(source[key]);
    }
    return sorted;
  }
  throw new TypeError("Action payload must be JSON serializable");
}

function completedReplay<TResponse>(entry: ActionJournalEntry): TaskActionCoordinatorResult<TResponse> {
  return { kind: "completed", response: entry.response as TResponse };
}

function pendingResponse<TResponse>(error: unknown): TResponse | undefined {
  if (typeof error !== "object" || error === null || !("response" in error)) return undefined;
  return (error as { response: TResponse }).response;
}

type StoredCommandResult =
  | { kind: "void" }
  | { kind: "value"; value: unknown };

function storedCommandResult<TResult>(result: TResult): StoredCommandResult {
  return result === undefined ? { kind: "void" } : { kind: "value", value: result };
}

function restoredCommandResult<TResult>(result: unknown): TResult {
  if (isStoredCommandResult(result)) {
    return (result.kind === "void" ? undefined : result.value) as TResult;
  }
  return result as TResult;
}

function isStoredCommandResult(value: unknown): value is StoredCommandResult {
  if (typeof value !== "object" || value === null || !("kind" in value)) return false;
  const kind = (value as { kind: unknown }).kind;
  return kind === "void" || kind === "value";
}

function replayFailure(entry: ActionJournalEntry): Error {
  const error = entry.error as { code?: unknown; message?: unknown; statusCode?: unknown } | null;
  return Object.assign(new Error(typeof error?.message === "string" ? error.message : "Action previously failed"), {
    code: typeof error?.code === "string" ? error.code : "action_failed",
    statusCode: typeof error?.statusCode === "number" ? error.statusCode : 422,
    details: error
  });
}

function serializeDomainError(error: DomainError): Record<string, unknown> {
  return { code: error.code, message: error.message, statusCode: error.statusCode, details: error.details ?? null };
}

function compactError(error: unknown): Record<string, unknown> {
  if (error instanceof Error) return { name: error.name, message: error.message };
  return { message: "Unknown synchronization failure" };
}

function snapshotContext(root: CoordinatorRoot): Record<string, unknown> {
  return {
    taskId: root.id,
    taskRevision: `r:${root.mirrorRevision}`,
    occurrenceKey: root.occurrenceKey,
    reconciliationStatus: root.reconciliationStatus
  };
}
