#!/usr/bin/env bash
set -euo pipefail

repo_dir="${BUZER_REPO_DIR:-/home/skclaw/buzer-app}"
branch="${BUZER_DEPLOY_BRANCH:-master}"
env_file="${BUZER_ENV_FILE:-$repo_dir/.env}"
compose_file="${BUZER_COMPOSE_FILE:-$repo_dir/docker-compose.yml}"
compose_service="${BUZER_COMPOSE_SERVICE:-backend}"
host_port="${BUZER_HOST_PORT:-18432}"
health_url="${BUZER_HEALTH_URL:-http://127.0.0.1:$host_port/health}"
health_max_attempts="${BUZER_HEALTH_MAX_ATTEMPTS:-40}"
health_sleep_seconds="${BUZER_HEALTH_SLEEP_SECONDS:-3}"

cd "$repo_dir"

git fetch origin "$branch"
git checkout -B "$branch" "origin/$branch"

docker compose --env-file "$env_file" -f "$compose_file" up -d --build --remove-orphans

for attempt in $(seq 1 "$health_max_attempts"); do
  if curl --fail --silent --show-error "$health_url" >/dev/null; then
    echo "Buzer backend is healthy at $health_url"
    exit 0
  fi

  # WhatsApp re-authentication on startup (especially after cleaning up stale
  # Chromium profile lock files) can push readiness past a short budget; a
  # slow-but-successful deploy must not be misreported as failed.
  echo "Waiting for Buzer backend health check ($attempt/$health_max_attempts)"
  sleep "$health_sleep_seconds"
done

echo "Buzer backend did not become healthy" >&2
docker compose --env-file "$env_file" -f "$compose_file" logs --tail=120 "$compose_service" >&2
exit 1
