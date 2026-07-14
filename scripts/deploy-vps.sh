#!/usr/bin/env bash
set -euo pipefail

repo_dir="${BUZER_REPO_DIR:-/home/skclaw/buzer-app}"
branch="${BUZER_DEPLOY_BRANCH:-master}"
env_file="${BUZER_ENV_FILE:-$repo_dir/.env}"
compose_file="${BUZER_COMPOSE_FILE:-$repo_dir/docker-compose.yml}"
compose_service="${BUZER_COMPOSE_SERVICE:-backend}"
host_port="${BUZER_HOST_PORT:-18432}"
health_url="${BUZER_HEALTH_URL:-http://127.0.0.1:$host_port/buzer/health}"

cd "$repo_dir"

git fetch origin "$branch"
git checkout -B "$branch" "origin/$branch"

docker compose --env-file "$env_file" -f "$compose_file" up -d --build --remove-orphans

for attempt in {1..20}; do
  if curl --fail --silent --show-error "$health_url" >/dev/null; then
    echo "Buzer backend is healthy at $health_url"
    exit 0
  fi

  echo "Waiting for Buzer backend health check ($attempt/20)"
  sleep 3
done

echo "Buzer backend did not become healthy" >&2
docker compose --env-file "$env_file" -f "$compose_file" logs --tail=120 "$compose_service" >&2
exit 1
