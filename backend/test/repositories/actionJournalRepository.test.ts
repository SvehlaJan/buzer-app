import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";
import { ActionJournalRepository } from "../../src/repositories/actionJournalRepository.js";

describe("ActionJournalRepository", () => {
  it("reserves once, replays the same fingerprint after reconstruction, and rejects action ID reuse", () => {
    const db = new Database(":memory:");
    migrate(db);
    const input = {
      actionId: "action-1",
      deviceId: "device-1",
      rootTaskId: "root-1",
      expectedOccurrenceKey: "occurrence-1",
      actionType: "extend",
      requestFingerprint: "sha256:same-request",
      now: "2026-07-15T09:00:00Z"
    };

    const first = new ActionJournalRepository(db).reserve(input);
    expect(first).toEqual({
      kind: "reserved",
      entry: {
        actionId: "action-1",
        deviceId: "device-1",
        rootTaskId: "root-1",
        expectedOccurrenceKey: "occurrence-1",
        actionType: "extend",
        requestFingerprint: "sha256:same-request",
        status: "processing",
        response: null,
        error: null,
        createdAt: "2026-07-15T09:00:00Z",
        updatedAt: "2026-07-15T09:00:00Z"
      }
    });

    const reconstructed = new ActionJournalRepository(db);
    expect(reconstructed.reserve({ ...input, now: "2026-07-15T09:01:00Z" })).toEqual({
      kind: "replay",
      entry: first.entry
    });

    let reusedError: unknown;
    try {
      reconstructed.reserve({
        ...input,
        requestFingerprint: "sha256:different-request",
        now: "2026-07-15T09:02:00Z"
      });
    } catch (error) {
      reusedError = error;
    }
    expect(reusedError).toMatchObject({
      code: "action_id_reused",
      statusCode: 409,
      message: "Action ID was already used for a different request"
    });
  });

  it("persists and replays stable command identities across command state transitions", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new ActionJournalRepository(db);
    repo.reserve({
      actionId: "action-command",
      deviceId: "device-1",
      rootTaskId: "root-1",
      expectedOccurrenceKey: "occurrence-1",
      actionType: "update",
      requestFingerprint: "sha256:update",
      now: "2026-07-15T09:00:00Z"
    });

    const first = repo.reserveCommand({
      actionId: "action-command",
      stepKey: "move-root",
      commandUuid: "command-stable",
      tempId: "temp-root",
      now: "2026-07-15T09:01:00Z"
    });
    expect(first).toEqual({
      kind: "reserved",
      command: {
        actionId: "action-command",
        stepKey: "move-root",
        commandUuid: "command-stable",
        tempId: "temp-root",
        status: "reserved",
        result: null,
        updatedAt: "2026-07-15T09:01:00Z"
      }
    });

    repo.markCommandSent("action-command", "move-root", "2026-07-15T09:02:00Z");
    expect(repo.reserveCommand({
      actionId: "action-command",
      stepKey: "move-root",
      commandUuid: "new-uuid-that-must-not-replace-the-reserved-one",
      tempId: null,
      now: "2026-07-15T09:03:00Z"
    })).toMatchObject({
      kind: "replay",
      command: {
        commandUuid: "command-stable",
        tempId: "temp-root",
        status: "sent",
        result: null,
        updatedAt: "2026-07-15T09:02:00Z"
      }
    });

    repo.markCommandConfirmed(
      "action-command",
      "move-root",
      { todoistTaskId: "todoist-root" },
      "2026-07-15T09:04:00Z"
    );
    expect(repo.reserveCommand({
      actionId: "action-command",
      stepKey: "move-root",
      commandUuid: "another-unused-uuid",
      tempId: null,
      now: "2026-07-15T09:05:00Z"
    })).toMatchObject({
      kind: "replay",
      command: {
        commandUuid: "command-stable",
        status: "confirmed",
        result: { todoistTaskId: "todoist-root" },
        updatedAt: "2026-07-15T09:04:00Z"
      }
    });
  });

  it("stores compact completed, failed, and reconciliation-pending results", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new ActionJournalRepository(db);

    reserveAction(repo, "completed-action");
    repo.markCompleted("completed-action", { outcome: "completed", count: 2 }, "2026-07-15T09:10:00Z");
    expect(repo.get("completed-action")).toMatchObject({
      status: "completed",
      response: { outcome: "completed", count: 2 },
      error: null,
      updatedAt: "2026-07-15T09:10:00Z"
    });

    reserveAction(repo, "failed-action");
    repo.markFailed("failed-action", { code: "todoist_unavailable" }, "2026-07-15T09:11:00Z");
    expect(repo.get("failed-action")).toMatchObject({
      status: "failed",
      response: null,
      error: { code: "todoist_unavailable" },
      updatedAt: "2026-07-15T09:11:00Z"
    });

    reserveAction(repo, "pending-action");
    repo.markReconciliationPending(
      "pending-action",
      { code: "sync_confirmation_pending" },
      "2026-07-15T09:12:00Z"
    );
    expect(repo.get("pending-action")).toMatchObject({
      status: "reconciliation_pending",
      response: null,
      error: { code: "sync_confirmation_pending" },
      updatedAt: "2026-07-15T09:12:00Z"
    });
    expect(repo.get("missing-action")).toBeNull();

    expect(db.prepare(`
      select response_json as responseJson from task_action_journal where action_id = 'completed-action'
    `).get()).toEqual({ responseJson: '{"outcome":"completed","count":2}' });
    expect(db.prepare(`
      select error_json as errorJson from task_action_journal where action_id = 'failed-action'
    `).get()).toEqual({ errorJson: '{"code":"todoist_unavailable"}' });
    expect(tableColumns(db, "task_action_journal")).not.toEqual(expect.arrayContaining([
      "headers",
      "authorization",
      "bearer_token",
      "todoist_token",
      "raw_http_request"
    ]));
  });

  it("enforces legal and idempotent action status transitions", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new ActionJournalRepository(db);

    expect(() => repo.markCompleted(
      "missing-action",
      { outcome: "completed" },
      "2026-07-15T09:20:00Z"
    )).toThrow("action_journal_entry_not_found");

    reserveAction(repo, "terminal-action");
    repo.markCompleted("terminal-action", { outcome: "completed" }, "2026-07-15T09:21:00Z");
    repo.markCompleted("terminal-action", { outcome: "completed" }, "2026-07-15T09:22:00Z");
    expect(repo.get("terminal-action")).toMatchObject({
      status: "completed",
      response: { outcome: "completed" },
      updatedAt: "2026-07-15T09:21:00Z"
    });
    expect(() => repo.markCompleted(
      "terminal-action",
      { outcome: "different" },
      "2026-07-15T09:23:00Z"
    )).toThrow("action_journal_transition_invalid");
    expect(() => repo.markFailed(
      "terminal-action",
      { code: "late_failure" },
      "2026-07-15T09:24:00Z"
    )).toThrow("action_journal_transition_invalid");

    reserveAction(repo, "recovering-action");
    repo.markReconciliationPending(
      "recovering-action",
      { code: "sync_pending" },
      "2026-07-15T09:25:00Z"
    );
    repo.markCompleted("recovering-action", { outcome: "completed" }, "2026-07-15T09:26:00Z");
    expect(repo.get("recovering-action")).toMatchObject({
      status: "completed",
      response: { outcome: "completed" },
      error: null,
      updatedAt: "2026-07-15T09:26:00Z"
    });
  });

  it("enforces legal and idempotent command status transitions", () => {
    const db = new Database(":memory:");
    migrate(db);
    const repo = new ActionJournalRepository(db);

    expect(() => repo.markCommandSent(
      "missing-action",
      "missing-step",
      "2026-07-15T09:30:00Z"
    )).toThrow("action_command_not_found");

    reserveAction(repo, "command-transition-action");
    repo.reserveCommand({
      actionId: "command-transition-action",
      stepKey: "update-root",
      commandUuid: "stable-command-uuid",
      tempId: null,
      now: "2026-07-15T09:31:00Z"
    });
    expect(() => repo.markCommandConfirmed(
      "command-transition-action",
      "update-root",
      { ok: true },
      "2026-07-15T09:32:00Z"
    )).toThrow("action_command_transition_invalid");

    repo.markCommandSent("command-transition-action", "update-root", "2026-07-15T09:33:00Z");
    repo.markCommandSent("command-transition-action", "update-root", "2026-07-15T09:34:00Z");
    expect(repo.reserveCommand({
      actionId: "command-transition-action",
      stepKey: "update-root",
      commandUuid: "unused-command-uuid",
      tempId: null,
      now: "2026-07-15T09:35:00Z"
    })).toMatchObject({
      kind: "replay",
      command: {
        status: "sent",
        updatedAt: "2026-07-15T09:33:00Z"
      }
    });

    repo.markCommandConfirmed(
      "command-transition-action",
      "update-root",
      { ok: true },
      "2026-07-15T09:36:00Z"
    );
    repo.markCommandConfirmed(
      "command-transition-action",
      "update-root",
      { ok: true },
      "2026-07-15T09:37:00Z"
    );
    expect(repo.reserveCommand({
      actionId: "command-transition-action",
      stepKey: "update-root",
      commandUuid: "another-unused-command-uuid",
      tempId: null,
      now: "2026-07-15T09:38:00Z"
    })).toMatchObject({
      kind: "replay",
      command: {
        status: "confirmed",
        result: { ok: true },
        updatedAt: "2026-07-15T09:36:00Z"
      }
    });
    expect(() => repo.markCommandConfirmed(
      "command-transition-action",
      "update-root",
      { ok: false },
      "2026-07-15T09:39:00Z"
    )).toThrow("action_command_transition_invalid");
    expect(() => repo.markCommandSent(
      "command-transition-action",
      "update-root",
      "2026-07-15T09:40:00Z"
    )).toThrow("action_command_transition_invalid");
  });
});

function reserveAction(repo: ActionJournalRepository, actionId: string): void {
  repo.reserve({
    actionId,
    deviceId: "device-1",
    rootTaskId: "root-1",
    expectedOccurrenceKey: "occurrence-1",
    actionType: "test-action",
    requestFingerprint: `sha256:${actionId}`,
    now: "2026-07-15T09:00:00Z"
  });
}

function tableColumns(db: Database.Database, table: string): string[] {
  return (db.prepare(`pragma table_info(${table})`).all() as Array<{ name: string }>).map((column) => column.name);
}
