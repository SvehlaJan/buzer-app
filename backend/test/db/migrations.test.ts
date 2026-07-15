import Database from "better-sqlite3";
import { describe, expect, it } from "vitest";
import { migrate } from "../../src/db/migrations.js";

describe("migrate", () => {
  it("creates core tables and enables WAL", () => {
    const db = new Database(":memory:");
    migrate(db);

    const tables = db.prepare("select name from sqlite_master where type = 'table' order by name").all() as Array<{ name: string }>;
    expect(tables.map((row) => row.name)).toEqual([
      "commitment_states",
      "devices",
      "schema_migrations",
      "task_action_commands",
      "task_action_journal",
      "todoist_projects",
      "todoist_sync_state",
      "todoist_tasks",
      "whatsapp_escalations"
    ]);
  });

  it("adds project mirror, commitment owner, and task description columns to an existing database", () => {
    const db = new Database(":memory:");
    db.exec(`
      create table schema_migrations (version integer primary key, applied_at text not null);
      create table todoist_sync_state (id integer primary key check (id = 1), sync_token text not null, updated_at text not null);
      create table todoist_tasks (
        id text primary key,
        todoist_task_id text not null unique,
        title text not null,
        project_id text,
        assignee_id text,
        creator_id text,
        due_datetime text,
        duration_minutes integer,
        completed_at text,
        deleted_at text,
        raw_json text not null,
        updated_at text not null
      );
      create table commitment_states (
        task_id text primary key references todoist_tasks(id) on delete cascade,
        state text not null,
        started_at text,
        start_dismiss_count integer not null default 0,
        deadline_extension_count integer not null default 0,
        last_snoozed_at text,
        escalation_sent_at text,
        schedule_version integer not null default 1,
        updated_at text not null
      );
    `);

    migrate(db);

    const commitmentColumns = db.prepare("pragma table_info(commitment_states)").all() as Array<{ name: string }>;
    const taskColumns = db.prepare("pragma table_info(todoist_tasks)").all() as Array<{ name: string }>;
    const tables = db.prepare("select name from sqlite_master where type = 'table'").all() as Array<{ name: string }>;
    expect(commitmentColumns.map((column) => column.name)).toContain("commitment_owner_id");
    expect(taskColumns.map((column) => column.name)).toContain("description");
    expect(tables.map((table) => table.name)).toContain("todoist_projects");
  });

  it("keeps legacy unkeyed escalation claims unique until occurrence-aware callers roll out", () => {
    const db = new Database(":memory:");
    migrate(db);
    db.prepare(`
      insert into todoist_tasks (
        id, todoist_task_id, title, raw_json, updated_at
      ) values ('task-1', 'todoist-1', 'Task', '{}', '2026-07-15T09:00:00Z')
    `).run();
    db.prepare(`
      insert into whatsapp_escalations (
        id, task_id, recipient_id, message, status, created_at
      ) values ('escalation-1', 'task-1', 'recipient-1', 'First', 'pending', '2026-07-15T09:00:00Z')
    `).run();

    expect(() => db.prepare(`
      insert into whatsapp_escalations (
        id, task_id, recipient_id, message, status, created_at
      ) values ('escalation-2', 'task-1', 'recipient-1', 'Second', 'pending', '2026-07-15T09:01:00Z')
    `).run()).toThrow(/UNIQUE constraint failed/);
  });

  it("declares fresh action journal IDs non-null", () => {
    const db = new Database(":memory:");
    migrate(db);

    const actionIdColumn = (db.prepare("pragma table_info(task_action_journal)").all() as Array<{
      name: string;
      notnull: number;
    }>).find((column) => column.name === "action_id");
    expect(actionIdColumn?.notnull).toBe(1);
    expect(() => insertNullActionId(db)).toThrow(/(?:NOT NULL constraint failed|action_id must not be null)/);
  });

  it("guards action journal IDs in databases that already recorded migration v2", () => {
    const db = new Database(":memory:");
    db.exec(`
      create table schema_migrations (version integer primary key, applied_at text not null);
      insert into schema_migrations (version, applied_at) values
        (1, '2026-07-15T10:00:00Z'),
        (2, '2026-07-15T10:01:00Z');
      create table task_action_journal (
        action_id text primary key,
        device_id text not null,
        root_task_id text,
        expected_occurrence_key text,
        action_type text not null,
        request_fingerprint text not null,
        status text not null check (status in ('processing', 'completed', 'failed', 'reconciliation_pending')),
        response_json text,
        error_json text,
        created_at text not null,
        updated_at text not null
      );
    `);

    migrate(db);

    expect(() => insertNullActionId(db)).toThrow(/task_action_journal.action_id must not be null/);
  });

  it("fills missing occurrence anchors independently in an already-v2 database", () => {
    const db = new Database(":memory:");
    migrate(db);
    db.exec(`
      insert into todoist_tasks (
        id,
        todoist_task_id,
        title,
        due_datetime,
        is_recurring,
        raw_json,
        updated_at
      ) values
        (
          'partial-fingerprint-task',
          'todoist-partial-fingerprint',
          'Partial fingerprint',
          '2026-07-16T12:00:00Z',
          1,
          '{"due":{"string":"Every Day at 12:00","lang":"EN","timezone":"Europe/Bratislava","is_recurring":true}}',
          '2026-07-15T11:00:00Z'
        ),
        (
          'partial-due-task',
          'todoist-partial-due',
          'Partial due',
          '2026-07-17T13:00:00Z',
          0,
          '{}',
          '2026-07-15T11:00:00Z'
        );
      insert into commitment_states (
        task_id,
        state,
        occurrence_key,
        occurrence_due_datetime,
        occurrence_recurrence_fingerprint,
        updated_at
      ) values
        (
          'partial-fingerprint-task',
          'started',
          'preserved-key-fingerprint',
          'preserved-due-anchor',
          null,
          '2026-07-15T11:00:00Z'
        ),
        (
          'partial-due-task',
          'planned',
          'preserved-key-due',
          null,
          'preserved-fingerprint',
          '2026-07-15T11:00:00Z'
        );
    `);

    migrate(db);

    expect(db.prepare(`
      select
        occurrence_key as occurrenceKey,
        occurrence_due_datetime as occurrenceDueDatetime,
        occurrence_recurrence_fingerprint as occurrenceRecurrenceFingerprint
      from commitment_states
      where task_id = 'partial-fingerprint-task'
    `).get()).toEqual({
      occurrenceKey: "preserved-key-fingerprint",
      occurrenceDueDatetime: "preserved-due-anchor",
      occurrenceRecurrenceFingerprint:
        '{"isRecurring":true,"dueString":"every day at 12:00","dueLang":"en","dueTimezone":"Europe/Bratislava"}'
    });
    expect(db.prepare(`
      select
        occurrence_key as occurrenceKey,
        occurrence_due_datetime as occurrenceDueDatetime,
        occurrence_recurrence_fingerprint as occurrenceRecurrenceFingerprint
      from commitment_states
      where task_id = 'partial-due-task'
    `).get()).toEqual({
      occurrenceKey: "preserved-key-due",
      occurrenceDueDatetime: "2026-07-17T13:00:00Z",
      occurrenceRecurrenceFingerprint: "preserved-fingerprint"
    });
  });

  it("migrates legacy commitments to typed mirrors, occurrences, and durable journals", () => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    db.exec(`
      create table schema_migrations (version integer primary key, applied_at text not null);
      insert into schema_migrations (version, applied_at) values (1, '2026-07-14T10:00:00Z');

      create table todoist_sync_state (
        id integer primary key check (id = 1),
        sync_token text not null,
        updated_at text not null
      );
      insert into todoist_sync_state (id, sync_token, updated_at)
      values (1, 'legacy-incremental-token', '2026-07-14T10:00:00Z');

      create table todoist_tasks (
        id text primary key,
        todoist_task_id text not null unique,
        title text not null,
        description text,
        project_id text,
        assignee_id text,
        creator_id text,
        due_datetime text,
        duration_minutes integer,
        completed_at text,
        deleted_at text,
        raw_json text not null,
        updated_at text not null
      );
      create table commitment_states (
        task_id text primary key references todoist_tasks(id) on delete cascade,
        state text not null,
        started_at text,
        commitment_owner_id text,
        start_dismiss_count integer not null default 0,
        deadline_extension_count integer not null default 0,
        last_snoozed_at text,
        escalation_sent_at text,
        schedule_version integer not null default 1,
        updated_at text not null
      );
      create table whatsapp_escalations (
        id text primary key,
        task_id text not null references todoist_tasks(id) on delete cascade,
        recipient_id text not null,
        message text not null,
        status text not null,
        error_message text,
        created_at text not null,
        sent_at text
      );
      create unique index whatsapp_escalations_one_per_task
        on whatsapp_escalations(task_id);

      insert into todoist_tasks (
        id, todoist_task_id, title, description, project_id, assignee_id, creator_id,
        due_datetime, duration_minutes, completed_at, deleted_at, raw_json, updated_at
      ) values
        (
          'active-task', 'todoist-active', 'Active task', null, 'project-1', 'owner-1', 'creator-1',
          '2026-07-15T12:00:00Z', 30, null, null,
          '{"id":"todoist-active","due":{"datetime":"2026-07-15T12:00:00Z","string":" Every   Friday at 16:00 ","lang":"EN","timezone":" Europe/Bratislava ","is_recurring":true}}',
          '2026-07-14T10:00:00Z'
        ),
        (
          'terminal-task', 'todoist-terminal', 'Terminal task', null, 'project-1', 'owner-1', 'creator-1',
          '2026-07-14T12:00:00Z', 30, '2026-07-14T12:30:00Z', null,
          '{"id":"todoist-terminal"}', '2026-07-14T12:30:00Z'
        );
      insert into commitment_states (task_id, state, commitment_owner_id, updated_at) values
        ('active-task', 'started', 'owner-1', '2026-07-14T10:00:00Z'),
        ('terminal-task', 'completed', 'owner-1', '2026-07-14T12:30:00Z');
      insert into whatsapp_escalations (
        id, task_id, recipient_id, message, status, created_at
      ) values
        ('active-escalation', 'active-task', 'recipient-1', 'Active message', 'sent', '2026-07-14T10:30:00Z'),
        ('terminal-escalation', 'terminal-task', 'recipient-1', 'History message', 'sent', '2026-07-14T12:00:00Z');
    `);

    migrate(db);

    const taskColumns = tableColumns(db, "todoist_tasks");
    expect(taskColumns).toEqual(expect.arrayContaining([
      "parent_id",
      "child_order",
      "due_date",
      "due_string",
      "due_lang",
      "due_timezone",
      "is_recurring",
      "duration_amount",
      "duration_unit",
      "mirror_revision"
    ]));
    expect(tableColumns(db, "commitment_states")).toEqual(expect.arrayContaining([
      "occurrence_key",
      "occurrence_due_datetime",
      "occurrence_recurrence_fingerprint",
      "reconciliation_status"
    ]));
    expect(tableColumns(db, "todoist_sync_state")).toContain("mirror_contract_version");
    expect(tableColumns(db, "whatsapp_escalations")).toContain("occurrence_key");

    const occurrences = db.prepare(`
      select
        task_id as taskId,
        occurrence_key as occurrenceKey,
        occurrence_due_datetime as occurrenceDueDatetime,
        occurrence_recurrence_fingerprint as occurrenceRecurrenceFingerprint,
        reconciliation_status as reconciliationStatus
      from commitment_states
      order by task_id
    `).all() as Array<{
      taskId: string;
      occurrenceKey: string;
      occurrenceDueDatetime: string;
      occurrenceRecurrenceFingerprint: string;
      reconciliationStatus: string;
    }>;
    expect(occurrences).toHaveLength(2);
    for (const occurrence of occurrences) {
      expect(occurrence.occurrenceKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      expect(occurrence.reconciliationStatus).toBe("confirmed");
    }
    expect(occurrences[0]?.occurrenceDueDatetime).toBe("2026-07-15T12:00:00Z");
    expect(occurrences[0]?.occurrenceRecurrenceFingerprint).toBe(
      '{"isRecurring":true,"dueString":"every friday at 16:00","dueLang":"en","dueTimezone":"Europe/Bratislava"}'
    );
    expect(occurrences[1]?.occurrenceDueDatetime).toBe("2026-07-14T12:00:00Z");
    expect(occurrences[1]?.occurrenceRecurrenceFingerprint).toBe(
      '{"isRecurring":false,"dueString":null,"dueLang":null,"dueTimezone":null}'
    );

    const escalationRows = db.prepare(`
      select task_id as taskId, occurrence_key as occurrenceKey
      from whatsapp_escalations
      order by task_id
    `).all() as Array<{ taskId: string; occurrenceKey: string }>;
    expect(escalationRows).toEqual([
      { taskId: "active-task", occurrenceKey: occurrences[0]?.occurrenceKey },
      { taskId: "terminal-task", occurrenceKey: "legacy:terminal-task" }
    ]);

    const legacyActiveClaim = db.prepare(`
      insert or ignore into whatsapp_escalations (
        id, task_id, recipient_id, message, status, created_at
      ) values (?, ?, ?, ?, ?, ?)
    `).run(
      "legacy-active-retry",
      "active-task",
      "recipient-1",
      "Legacy caller retry",
      "pending",
      "2026-07-14T10:31:00Z"
    );
    expect(legacyActiveClaim.changes).toBe(0);
    expect(db.prepare(`
      select count(*) as count from whatsapp_escalations where task_id = 'active-task'
    `).get()).toEqual({ count: 1 });

    const escalationIndexes = db.prepare("pragma index_list(whatsapp_escalations)").all() as Array<{ name: string }>;
    expect(escalationIndexes.map((index) => index.name)).toContain("whatsapp_escalations_one_per_occurrence");
    expect(escalationIndexes.map((index) => index.name)).not.toContain("whatsapp_escalations_one_per_task");
    expect(() => db.prepare(`
      insert into whatsapp_escalations (
        id, task_id, occurrence_key, recipient_id, message, status, created_at
      ) values (?, ?, ?, ?, ?, ?, ?)
    `).run(
      "active-escalation-duplicate",
      "active-task",
      occurrences[0]?.occurrenceKey,
      "recipient-1",
      "Duplicate",
      "pending",
      "2026-07-14T10:31:00Z"
    )).toThrow(/UNIQUE constraint failed/);
    expect(() => db.prepare(`
      insert into whatsapp_escalations (
        id, task_id, occurrence_key, recipient_id, message, status, created_at
      ) values (?, ?, ?, ?, ?, ?, ?)
    `).run(
      "active-escalation-next-occurrence",
      "active-task",
      "next-occurrence",
      "recipient-1",
      "Next occurrence",
      "pending",
      "2026-07-15T10:31:00Z"
    )).not.toThrow();

    expect(tableColumns(db, "task_action_journal")).toEqual([
      "action_id",
      "device_id",
      "root_task_id",
      "expected_occurrence_key",
      "action_type",
      "request_fingerprint",
      "status",
      "response_json",
      "error_json",
      "created_at",
      "updated_at"
    ]);
    expect(tableColumns(db, "task_action_commands")).toEqual([
      "action_id",
      "step_key",
      "command_uuid",
      "temp_id",
      "status",
      "result_json",
      "updated_at"
    ]);
    expect(db.prepare("select * from todoist_sync_state").all()).toEqual([]);
    expect(db.prepare("select version from schema_migrations order by version").all()).toEqual([
      { version: 1 },
      { version: 2 }
    ]);

    const activeOccurrenceKey = occurrences[0]?.occurrenceKey;
    db.prepare(`
      insert into todoist_sync_state (id, sync_token, updated_at)
      values (1, 'fresh-full-sync-token', '2026-07-15T13:00:00Z')
    `).run();
    migrate(db);
    expect(db.prepare(`
      select sync_token as syncToken, mirror_contract_version as mirrorContractVersion
      from todoist_sync_state where id = 1
    `).get()).toEqual({ syncToken: "fresh-full-sync-token", mirrorContractVersion: 1 });
    expect(db.prepare(`
      select occurrence_key as occurrenceKey from commitment_states where task_id = 'active-task'
    `).get()).toEqual({ occurrenceKey: activeOccurrenceKey });
  });
});

function tableColumns(db: Database.Database, table: string): string[] {
  return (db.prepare(`pragma table_info(${table})`).all() as Array<{ name: string }>).map((column) => column.name);
}

function insertNullActionId(db: Database.Database): void {
  db.prepare(`
    insert into task_action_journal (
      action_id,
      device_id,
      action_type,
      request_fingerprint,
      status,
      created_at,
      updated_at
    ) values (null, 'device-1', 'test', 'fingerprint', 'processing', '2026-07-15T11:00:00Z', '2026-07-15T11:00:00Z')
  `).run();
}
