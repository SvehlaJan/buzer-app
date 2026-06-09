#!/usr/bin/env bash
set -euo pipefail

container="${BUZER_CONTAINER_NAME:-buzer-backend}"
backup_dir="${BUZER_BACKUP_DIR:-./data/backups}"
retention_days="${BUZER_BACKUP_RETENTION_DAYS:-30}"

docker exec \
  -e BUZER_BACKUP_DIR="$backup_dir" \
  "$container" \
  node -e '
    const fs = require("node:fs");
    const Database = require("better-sqlite3");

    const dbPath = process.env.DATABASE_PATH || "./data/buzer.sqlite";
    const backupDir = process.env.BUZER_BACKUP_DIR || "./data/backups";
    const timestamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
    const target = `${backupDir}/buzer-${timestamp}.sqlite`;

    fs.mkdirSync(backupDir, { recursive: true });
    new Database(dbPath).backup(target).then(() => {
      console.log(`Wrote SQLite backup to ${target}`);
    }).catch((error) => {
      console.error(error);
      process.exit(1);
    });
  '

docker exec "$container" find "$backup_dir" -type f -name 'buzer-*.sqlite' -mtime +"$retention_days" -delete
