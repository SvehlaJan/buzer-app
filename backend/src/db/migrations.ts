import Database from "better-sqlite3";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

export function migrate(db: Database.Database): void {
  db.pragma("foreign_keys = ON");
  db.pragma("journal_mode = WAL");
  const schema = readFileSync(join(__dirname, "schema.sql"), "utf8");
  db.exec(schema);
  db.prepare("insert or ignore into schema_migrations (version, applied_at) values (?, ?)").run(1, new Date().toISOString());
}
