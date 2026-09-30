import Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

export function migrate(db: Database.Database): void {
  db.pragma("foreign_keys = ON");
  db.pragma("journal_mode = WAL");
  const schema = readFileSync(join(__dirname, "schema.sql"), "utf8");

  const tx = db.transaction(() => {
    db.exec(schema);
    ensureColumn(db, "commitment_states", "commitment_owner_id", "text");
    ensureColumn(db, "todoist_tasks", "description", "text");
    db.prepare("insert or ignore into schema_migrations (version, applied_at) values (?, ?)").run(1, new Date().toISOString());

    const migrationTwoApplied = db.prepare("select 1 from schema_migrations where version = 2").get() !== undefined;
    if (!migrationTwoApplied) {
      migrateToVersionTwo(db);
      db.prepare("insert into schema_migrations (version, applied_at) values (?, ?)").run(2, new Date().toISOString());
    }
    backfillOccurrences(db);
    const migrationThreeApplied = db.prepare("select 1 from schema_migrations where version = 3").get() !== undefined;
    if (!migrationThreeApplied) {
      migrateToVersionThree(db);
      db.prepare("insert into schema_migrations (version, applied_at) values (?, ?)").run(3, new Date().toISOString());
    }
    const migrationFourApplied = db.prepare("select 1 from schema_migrations where version = 4").get() !== undefined;
    if (!migrationFourApplied) {
      migrateToVersionFour(db);
      db.prepare("insert into schema_migrations (version, applied_at) values (?, ?)").run(4, new Date().toISOString());
    }
    const migrationFiveApplied = db.prepare("select 1 from schema_migrations where version = 5").get() !== undefined;
    if (!migrationFiveApplied) {
      migrateToVersionFive(db);
      db.prepare("insert into schema_migrations (version, applied_at) values (?, ?)").run(5, new Date().toISOString());
    }
    const migrationSixApplied = db.prepare("select 1 from schema_migrations where version = 6").get() !== undefined;
    if (!migrationSixApplied) {
      migrateToVersionSix(db);
      db.prepare("insert into schema_migrations (version, applied_at) values (?, ?)").run(6, new Date().toISOString());
    }
    const migrationSevenApplied = db.prepare("select 1 from schema_migrations where version = 7").get() !== undefined;
    if (!migrationSevenApplied) {
      migrateToVersionSeven(db);
      db.prepare("insert into schema_migrations (version, applied_at) values (?, ?)").run(7, new Date().toISOString());
    }
    const migrationEightApplied = db.prepare("select 1 from schema_migrations where version = 8").get() !== undefined;
    if (!migrationEightApplied) {
      migrateToVersionEight(db);
      db.prepare("insert into schema_migrations (version, applied_at) values (?, ?)").run(8, new Date().toISOString());
    }
  });

  tx();
}

function ensureColumn(db: Database.Database, table: string, column: string, definition: string): void {
  const columns = db.prepare(`pragma table_info(${table})`).all() as Array<{ name: string }>;
  if (!columns.some((entry) => entry.name === column)) {
    db.exec(`alter table ${table} add column ${column} ${definition}`);
  }
}

function dropColumnIfExists(db: Database.Database, table: string, column: string): void {
  const columns = db.prepare(`pragma table_info(${table})`).all() as Array<{ name: string }>;
  if (columns.some((entry) => entry.name === column)) {
    db.exec(`alter table ${table} drop column ${column}`);
  }
}

function migrateToVersionTwo(db: Database.Database): void {
  ensureColumn(db, "todoist_sync_state", "mirror_contract_version", "integer not null default 1");

  ensureColumn(db, "todoist_tasks", "parent_id", "text");
  ensureColumn(db, "todoist_tasks", "child_order", "integer");
  ensureColumn(db, "todoist_tasks", "due_date", "text");
  ensureColumn(db, "todoist_tasks", "due_string", "text");
  ensureColumn(db, "todoist_tasks", "due_lang", "text");
  ensureColumn(db, "todoist_tasks", "due_timezone", "text");
  ensureColumn(db, "todoist_tasks", "is_recurring", "integer not null default 0");
  ensureColumn(db, "todoist_tasks", "duration_amount", "integer");
  ensureColumn(db, "todoist_tasks", "duration_unit", "text");
  ensureColumn(db, "todoist_tasks", "mirror_revision", "integer not null default 1");

  ensureColumn(db, "commitment_states", "occurrence_key", "text");
  ensureColumn(db, "commitment_states", "occurrence_due_datetime", "text");
  ensureColumn(db, "commitment_states", "occurrence_recurrence_fingerprint", "text");
  ensureColumn(
    db,
    "commitment_states",
    "reconciliation_status",
    "text not null default 'confirmed' check (reconciliation_status in ('confirmed', 'pending'))"
  );

  ensureColumn(db, "whatsapp_escalations", "occurrence_key", "text");
  backfillOccurrences(db);
  backfillEscalationOccurrences(db);

  db.exec("drop index if exists whatsapp_escalations_one_per_task");
  db.exec(`
    create unique index if not exists whatsapp_escalations_one_per_occurrence
      on whatsapp_escalations(task_id, occurrence_key)
  `);
  db.exec(`
    create unique index if not exists whatsapp_escalations_one_unkeyed_per_task
      on whatsapp_escalations(task_id)
      where occurrence_key is null
  `);
  db.exec(`
    create trigger if not exists whatsapp_escalations_reject_unkeyed_after_keyed
    before insert on whatsapp_escalations
    when new.occurrence_key is null
      and exists (
        select 1
        from whatsapp_escalations existing
        where existing.task_id = new.task_id
          and existing.occurrence_key is not null
      )
    begin
      select raise(ignore);
    end
  `);
  db.prepare("delete from todoist_sync_state").run();
}



/**
 * The snapshot endpoint projects these three tables.  SQLite trigger bodies run
 * in the same transaction as their originating statement, so a committed
 * generation always identifies a complete committed projection and a rollback
 * advances nothing.
 */
function migrateToVersionThree(db: Database.Database): void {
  db.exec(`
    create table if not exists snapshot_generations (
      id integer primary key check (id = 1),
      generation integer not null check (generation >= 1)
    );
  `);
  db.prepare("insert or ignore into snapshot_generations (id, generation) values (1, 1)").run();

  for (const table of ["todoist_tasks", "commitment_states", "todoist_projects"]) {
    for (const operation of ["insert", "update", "delete"]) {
      db.exec(`
        create trigger if not exists ${table}_snapshot_generation_after_${operation}
        after ${operation} on ${table}
        begin
          update snapshot_generations set generation = generation + 1 where id = 1;
        end;
      `);
    }
  }
}

function migrateToVersionFour(db: Database.Database): void {
  db.exec(`
    create table if not exists todoist_collaborators (
      todoist_user_id text primary key,
      full_name text not null,
      email text,
      time_zone text,
      image_id text,
      is_deleted integer not null,
      raw_json text not null,
      updated_at text not null
    );

    create table if not exists todoist_project_collaborators (
      todoist_project_id text not null,
      todoist_user_id text not null,
      state text not null check (state in ('active', 'invited')),
      role text,
      is_deleted integer not null,
      raw_json text not null,
      updated_at text not null,
      primary key (todoist_project_id, todoist_user_id)
    );
  `);
}

function migrateToVersionFive(db: Database.Database): void {
  db.exec(`
    create table if not exists diagnostic_reports (
      report_session_id text primary key,
      android_diagnostic_event_id text not null,
      backend_diagnostic_event_id text not null,
      device_id text not null,
      captured_at text not null,
      response_json text not null,
      created_at text not null
    );

    create index if not exists diagnostic_reports_device_created
      on diagnostic_reports(device_id, created_at);
  `);
}

function migrateToVersionSix(db: Database.Database): void {
  db.exec("drop table if exists diagnostic_reports");
}

function migrateToVersionSeven(db: Database.Database): void {
  db.exec("drop table if exists task_action_commands");
  db.exec("drop table if exists task_action_journal");
  dropColumnIfExists(db, "commitment_states", "reconciliation_status");
}

function migrateToVersionEight(db: Database.Database): void {
  // The projection of floating due times changed (decision 2026-09-29-reminder-reliability),
  // so clients must see a newer snapshot generation once after this deploy.
  db.exec("update snapshot_generations set generation = generation + 1 where id = 1");
}

function backfillOccurrences(db: Database.Database): void {
  const rows = db.prepare(`
    select
      c.task_id as taskId,
      c.occurrence_key as occurrenceKey,
      c.occurrence_due_datetime as occurrenceDueDatetime,
      c.occurrence_recurrence_fingerprint as occurrenceRecurrenceFingerprint,
      t.due_datetime as dueDatetime,
      t.is_recurring as isRecurring,
      t.due_string as dueString,
      t.due_lang as dueLang,
      t.due_timezone as dueTimezone,
      t.raw_json as rawJson
    from commitment_states c
    join todoist_tasks t on t.id = c.task_id
    where c.occurrence_key is null
      or trim(c.occurrence_key) = ''
      or (
        (c.occurrence_due_datetime is null or trim(c.occurrence_due_datetime) = '')
        and t.due_datetime is not null
      )
      or c.occurrence_recurrence_fingerprint is null
      or trim(c.occurrence_recurrence_fingerprint) = ''
  `).all() as Array<{
    taskId: string;
    occurrenceKey: string | null;
    occurrenceDueDatetime: string | null;
    occurrenceRecurrenceFingerprint: string | null;
    dueDatetime: string | null;
    isRecurring: number;
    dueString: string | null;
    dueLang: string | null;
    dueTimezone: string | null;
    rawJson: string;
  }>;

  const update = db.prepare(`
    update commitment_states
    set
      occurrence_key = ?,
      occurrence_due_datetime = ?,
      occurrence_recurrence_fingerprint = ?
    where task_id = ?
  `);
  for (const row of rows) {
    const recurrenceFacts = legacyRecurrenceFacts(row);
    update.run(
      nonBlank(row.occurrenceKey) ?? randomUUID(),
      nonBlank(row.occurrenceDueDatetime) ?? row.dueDatetime,
      nonBlank(row.occurrenceRecurrenceFingerprint) ?? recurrenceFingerprint(recurrenceFacts),
      row.taskId
    );
  }
}

function backfillEscalationOccurrences(db: Database.Database): void {
  db.prepare(`
    update whatsapp_escalations
    set occurrence_key = coalesce(
      (
        select case
          when c.state in ('completed', 'deleted') then 'legacy:' || whatsapp_escalations.task_id
          else c.occurrence_key
        end
        from commitment_states c
        where c.task_id = whatsapp_escalations.task_id
      ),
      'legacy:' || task_id
    )
    where occurrence_key is null or trim(occurrence_key) = ''
  `).run();
}

function recurrenceFingerprint(input: {
  isRecurring: boolean;
  dueString: string | null;
  dueLang: string | null;
  dueTimezone: string | null;
}): string {
  return JSON.stringify({
    isRecurring: input.isRecurring,
    dueString: normalizeFingerprintText(input.dueString, true),
    dueLang: normalizeFingerprintText(input.dueLang, true),
    dueTimezone: normalizeFingerprintText(input.dueTimezone, false)
  });
}

function normalizeFingerprintText(value: string | null, lowerCase: boolean): string | null {
  const normalized = value?.trim().replace(/\s+/g, " ") ?? "";
  if (normalized.length === 0) {
    return null;
  }
  return lowerCase ? normalized.toLowerCase() : normalized;
}

function legacyRecurrenceFacts(input: {
  isRecurring: number;
  dueString: string | null;
  dueLang: string | null;
  dueTimezone: string | null;
  rawJson: string;
}): {
  isRecurring: boolean;
  dueString: string | null;
  dueLang: string | null;
  dueTimezone: string | null;
} {
  const raw = parseLegacyRawJson(input.rawJson);
  const due = asRecord(raw?.due);
  return {
    isRecurring: readBoolean(due?.is_recurring)
      ?? readBoolean(raw?.is_recurring)
      ?? input.isRecurring === 1,
    dueString: readString(due?.string) ?? readString(raw?.due_string) ?? input.dueString,
    dueLang: readString(due?.lang) ?? readString(raw?.due_lang) ?? input.dueLang,
    dueTimezone: readString(due?.timezone) ?? readString(raw?.due_timezone) ?? input.dueTimezone
  };
}

function parseLegacyRawJson(value: string): Record<string, unknown> | null {
  try {
    return asRecord(JSON.parse(value) as unknown);
  } catch (error) {
    if (!(error instanceof SyntaxError)) {
      throw error;
    }
    return null;
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function readBoolean(value: unknown): boolean | null {
  return typeof value === "boolean" ? value : null;
}

function readString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function nonBlank(value: string | null): string | null {
  return value?.trim() ? value : null;
}
