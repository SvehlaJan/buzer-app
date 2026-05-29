#!/usr/bin/env bash
set -euo pipefail

data_dir="${BUZER_DATA_DIR:-/srv/buzer/data}"
db_path="${BUZER_DATABASE_PATH:-$data_dir/buzer.sqlite}"
backup_dir="${BUZER_BACKUP_DIR:-$data_dir/backups}"
retention_days="${BUZER_BACKUP_RETENTION_DAYS:-30}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

mkdir -p "$backup_dir"

if [ ! -f "$db_path" ]; then
  echo "SQLite database not found at $db_path" >&2
  exit 1
fi

sqlite3 "$db_path" ".backup '$backup_dir/buzer-$timestamp.sqlite'"
find "$backup_dir" -type f -name 'buzer-*.sqlite' -mtime +"$retention_days" -delete

echo "Wrote SQLite backup to $backup_dir/buzer-$timestamp.sqlite"
