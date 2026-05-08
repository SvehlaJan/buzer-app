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
      "todoist_sync_state",
      "todoist_tasks",
      "whatsapp_escalations"
    ]);
  });
});
