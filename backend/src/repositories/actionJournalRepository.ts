import Database from "better-sqlite3";

export type ActionJournalStatus = "processing" | "completed" | "failed" | "reconciliation_pending";
export type ActionCommandStatus = "reserved" | "sent" | "confirmed" | "failed";

export interface ActionJournalEntry {
  actionId: string;
  deviceId: string;
  rootTaskId: string | null;
  expectedOccurrenceKey: string | null;
  actionType: string;
  requestFingerprint: string;
  status: ActionJournalStatus;
  response: unknown | null;
  error: unknown | null;
  createdAt: string;
  updatedAt: string;
}

export interface ActionCommandEntry {
  actionId: string;
  stepKey: string;
  commandUuid: string;
  tempId: string | null;
  status: ActionCommandStatus;
  result: unknown | null;
  updatedAt: string;
}

export interface ReserveActionInput {
  actionId: string;
  deviceId: string;
  rootTaskId: string | null;
  expectedOccurrenceKey: string | null;
  actionType: string;
  requestFingerprint: string;
  now: string;
}

export interface ReserveCommandInput {
  actionId: string;
  stepKey: string;
  commandUuid: string;
  tempId: string | null;
  now: string;
}

export type ActionReservation =
  | { kind: "reserved"; entry: ActionJournalEntry }
  | { kind: "replay"; entry: ActionJournalEntry };

export type ActionCommandReservation =
  | { kind: "reserved"; command: ActionCommandEntry }
  | { kind: "replay"; command: ActionCommandEntry };

export class ActionJournalRepository {
  constructor(private readonly db: Database.Database) {}

  reserve(input: ReserveActionInput): ActionReservation {
    const tx = this.db.transaction((): ActionReservation => {
      const existing = this.get(input.actionId);
      if (existing !== null) {
        if (existing.requestFingerprint !== input.requestFingerprint) {
          throw actionIdReusedError();
        }
        return { kind: "replay", entry: existing };
      }

      this.db.prepare(`
        insert into task_action_journal (
          action_id,
          device_id,
          root_task_id,
          expected_occurrence_key,
          action_type,
          request_fingerprint,
          status,
          created_at,
          updated_at
        ) values (?, ?, ?, ?, ?, ?, 'processing', ?, ?)
      `).run(
        input.actionId,
        input.deviceId,
        input.rootTaskId,
        input.expectedOccurrenceKey,
        input.actionType,
        input.requestFingerprint,
        input.now,
        input.now
      );

      const entry = this.get(input.actionId);
      if (entry === null) {
        throw new Error("action_journal_reservation_missing");
      }
      return { kind: "reserved", entry };
    });

    return tx();
  }

  get(actionId: string): ActionJournalEntry | null {
    const row = this.getJournalRow(actionId);

    return row === undefined ? null : mapJournalRow(row);
  }

  hasDispatchedCommand(actionId: string): boolean {
    const row = this.db.prepare(`
      select 1 as present
      from task_action_commands
      where action_id = ? and status in ('sent', 'confirmed')
      limit 1
    `).get(actionId) as { present: number } | undefined;
    return row !== undefined;
  }

  reserveCommand(input: ReserveCommandInput): ActionCommandReservation {
    const tx = this.db.transaction((): ActionCommandReservation => {
      const existing = this.getCommand(input.actionId, input.stepKey);
      if (existing !== null) {
        return { kind: "replay", command: existing };
      }

      this.db.prepare(`
        insert into task_action_commands (
          action_id, step_key, command_uuid, temp_id, status, updated_at
        ) values (?, ?, ?, ?, 'reserved', ?)
      `).run(input.actionId, input.stepKey, input.commandUuid, input.tempId, input.now);

      const command = this.getCommand(input.actionId, input.stepKey);
      if (command === null) {
        throw new Error("action_command_reservation_missing");
      }
      return { kind: "reserved", command };
    });

    return tx();
  }

  markCommandSent(actionId: string, stepKey: string, updatedAt: string): void {
    const tx = this.db.transaction(() => {
      const current = this.getCommandRow(actionId, stepKey);
      if (current === undefined) {
        throw new Error("action_command_not_found");
      }
      if (current.status === "sent") {
        return;
      }
      if (current.status !== "reserved") {
        throw new Error("action_command_transition_invalid");
      }

      const update = this.db.prepare(`
        update task_action_commands
        set status = 'sent', updated_at = ?
        where action_id = ? and step_key = ? and status = 'reserved'
      `).run(updatedAt, actionId, stepKey);
      if (update.changes !== 1) {
        throw new Error("action_command_transition_conflict");
      }
    });
    tx();
  }

  markCommandConfirmed(actionId: string, stepKey: string, result: unknown, updatedAt: string): void {
    const resultJson = serializeJson(result);
    const tx = this.db.transaction(() => {
      const current = this.getCommandRow(actionId, stepKey);
      if (current === undefined) {
        throw new Error("action_command_not_found");
      }
      if (current.status === "confirmed") {
        if (current.resultJson === resultJson) {
          return;
        }
        throw new Error("action_command_transition_invalid");
      }
      if (current.status !== "sent") {
        throw new Error("action_command_transition_invalid");
      }

      const update = this.db.prepare(`
        update task_action_commands
        set status = 'confirmed', result_json = ?, updated_at = ?
        where action_id = ? and step_key = ? and status = 'sent'
      `).run(resultJson, updatedAt, actionId, stepKey);
      if (update.changes !== 1) {
        throw new Error("action_command_transition_conflict");
      }
    });
    tx();
  }

  markCommandFailed(actionId: string, stepKey: string, error: unknown, updatedAt: string): void {
    const errorJson = serializeJson(error);
    const tx = this.db.transaction(() => {
      const current = this.getCommandRow(actionId, stepKey);
      if (current === undefined) {
        throw new Error("action_command_not_found");
      }
      if (current.status === "failed") {
        if (current.resultJson === errorJson) {
          return;
        }
        throw new Error("action_command_transition_invalid");
      }
      if (current.status !== "sent") {
        throw new Error("action_command_transition_invalid");
      }

      const update = this.db.prepare(`
        update task_action_commands
        set status = 'failed', result_json = ?, updated_at = ?
        where action_id = ? and step_key = ? and status = 'sent'
      `).run(errorJson, updatedAt, actionId, stepKey);
      if (update.changes !== 1) {
        throw new Error("action_command_transition_conflict");
      }
    });
    tx();
  }

  markCompleted(actionId: string, response: unknown, updatedAt: string): void {
    this.transitionAction(
      actionId,
      "completed",
      serializeJson(response),
      null,
      ["processing", "reconciliation_pending"],
      updatedAt
    );
  }

  markFailed(actionId: string, error: unknown, updatedAt: string): void {
    this.transitionAction(
      actionId,
      "failed",
      null,
      serializeJson(error),
      ["processing", "reconciliation_pending"],
      updatedAt
    );
  }

  markReconciliationPending(actionId: string, error: unknown, updatedAt: string): void {
    this.transitionAction(
      actionId,
      "reconciliation_pending",
      null,
      serializeJson(error),
      ["processing"],
      updatedAt
    );
  }

  private getJournalRow(actionId: string): ActionJournalRow | undefined {
    return this.db.prepare(`
      select
        action_id as actionId,
        device_id as deviceId,
        root_task_id as rootTaskId,
        expected_occurrence_key as expectedOccurrenceKey,
        action_type as actionType,
        request_fingerprint as requestFingerprint,
        status,
        response_json as responseJson,
        error_json as errorJson,
        created_at as createdAt,
        updated_at as updatedAt
      from task_action_journal
      where action_id = ?
    `).get(actionId) as ActionJournalRow | undefined;
  }

  private getCommand(actionId: string, stepKey: string): ActionCommandEntry | null {
    const row = this.getCommandRow(actionId, stepKey);

    return row === undefined ? null : mapCommandRow(row);
  }

  private getCommandRow(actionId: string, stepKey: string): ActionCommandRow | undefined {
    return this.db.prepare(`
      select
        action_id as actionId,
        step_key as stepKey,
        command_uuid as commandUuid,
        temp_id as tempId,
        status,
        result_json as resultJson,
        updated_at as updatedAt
      from task_action_commands
      where action_id = ? and step_key = ?
    `).get(actionId, stepKey) as ActionCommandRow | undefined;
  }

  private transitionAction(
    actionId: string,
    status: Exclude<ActionJournalStatus, "processing">,
    responseJson: string | null,
    errorJson: string | null,
    allowedFrom: ActionJournalStatus[],
    updatedAt: string
  ): void {
    const tx = this.db.transaction(() => {
      const current = this.getJournalRow(actionId);
      if (current === undefined) {
        throw new Error("action_journal_entry_not_found");
      }
      if (current.status === status) {
        if (current.responseJson === responseJson && current.errorJson === errorJson) {
          return;
        }
        throw new Error("action_journal_transition_invalid");
      }
      if (!allowedFrom.includes(current.status)) {
        throw new Error("action_journal_transition_invalid");
      }

      const update = this.db.prepare(`
        update task_action_journal
        set status = ?, response_json = ?, error_json = ?, updated_at = ?
        where action_id = ? and status = ?
      `).run(status, responseJson, errorJson, updatedAt, actionId, current.status);
      if (update.changes !== 1) {
        throw new Error("action_journal_transition_conflict");
      }
    });
    tx();
  }
}

interface ActionJournalRow {
  actionId: string;
  deviceId: string;
  rootTaskId: string | null;
  expectedOccurrenceKey: string | null;
  actionType: string;
  requestFingerprint: string;
  status: ActionJournalStatus;
  responseJson: string | null;
  errorJson: string | null;
  createdAt: string;
  updatedAt: string;
}

interface ActionCommandRow {
  actionId: string;
  stepKey: string;
  commandUuid: string;
  tempId: string | null;
  status: ActionCommandStatus;
  resultJson: string | null;
  updatedAt: string;
}

function mapJournalRow(row: ActionJournalRow): ActionJournalEntry {
  const { responseJson, errorJson, ...entry } = row;
  return {
    ...entry,
    response: parseJson(responseJson),
    error: parseJson(errorJson)
  };
}

function mapCommandRow(row: ActionCommandRow): ActionCommandEntry {
  const { resultJson, ...command } = row;
  return {
    ...command,
    result: parseJson(resultJson)
  };
}

function serializeJson(value: unknown): string {
  const serialized = JSON.stringify(value);
  if (serialized === undefined) {
    throw new TypeError("Action journal values must be JSON serializable");
  }
  return serialized;
}

function parseJson(value: string | null): unknown | null {
  return value === null ? null : JSON.parse(value) as unknown;
}

function actionIdReusedError(): Error & { code: string; statusCode: number } {
  return Object.assign(
    new Error("Action ID was already used for a different request"),
    { code: "action_id_reused", statusCode: 409 }
  );
}
