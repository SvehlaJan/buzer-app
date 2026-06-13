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
      "todoist_projects",
      "todoist_sync_state",
      "todoist_tasks",
      "whatsapp_escalations"
    ]);
  });

  it("adds project mirror and commitment owner columns to an existing database", () => {
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
    const tables = db.prepare("select name from sqlite_master where type = 'table'").all() as Array<{ name: string }>;
    expect(commitmentColumns.map((column) => column.name)).toContain("commitment_owner_id");
    expect(tables.map((table) => table.name)).toContain("todoist_projects");
  });
});
