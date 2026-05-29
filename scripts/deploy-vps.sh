#!/usr/bin/env bash
set -euo pipefail

repo_dir="${BUZER_REPO_DIR:-/srv/buzer/repo}"
branch="${BUZER_DEPLOY_BRANCH:-master}"
compose_file="${BUZER_COMPOSE_FILE:-$repo_dir/deploy/docker-compose.yml}"
health_url="${BUZER_HEALTH_URL:-http://127.0.0.1:3001/health}"

cd "$repo_dir"

git fetch origin "$branch"
git checkout "$branch"
git pull --ff-only origin "$branch"

docker compose -f "$compose_file" up -d --build --remove-orphans

for attempt in {1..20}; do
  if curl --fail --silent --show-error "$health_url" >/dev/null; then
    echo "Buzer backend is healthy at $health_url"
    exit 0
  fi

  echo "Waiting for Buzer backend health check ($attempt/20)"
  sleep 3
done

echo "Buzer backend did not become healthy" >&2
docker compose -f "$compose_file" logs --tail=120 buzer-backend >&2
exit 1
