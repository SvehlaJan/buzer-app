#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-18433}"
TOKEN="test-token"
FAKE_BACKEND_URL="http://127.0.0.1:${PORT}"
EMULATOR_BACKEND_URL="http://10.0.2.2:${PORT}"
STARTED_PID=""

cleanup() {
  if [[ -n "${STARTED_PID}" ]]; then
    kill "${STARTED_PID}" >/dev/null 2>&1 || true
    wait "${STARTED_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_fake_backend() {
  local deadline=$((SECONDS + 30))
  while (( SECONDS < deadline )); do
    if curl --silent --fail \
      -H "Authorization: Bearer ${TOKEN}" \
      "${FAKE_BACKEND_URL}/projects" >/dev/null; then
      return 0
    fi
    if [[ -n "${STARTED_PID}" ]] && ! kill -0 "${STARTED_PID}" >/dev/null 2>&1; then
      local exit_code=0
      wait "${STARTED_PID}" || exit_code="$?"
      echo "Fake backend exited before becoming ready with status ${exit_code}" >&2
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for fake backend at ${FAKE_BACKEND_URL}" >&2
  return 1
}

if curl --silent --fail \
  -H "Authorization: Bearer ${TOKEN}" \
  "${FAKE_BACKEND_URL}/projects" >/dev/null; then
  echo "Using existing fake backend at ${FAKE_BACKEND_URL}"
else
  echo "Starting fake backend at ${FAKE_BACKEND_URL}"
  (
    cd "${ROOT_DIR}/tools/fake-backend"
    PORT="${PORT}" npm run dev
  ) &
  STARTED_PID="$!"
  wait_for_fake_backend
fi

cd "${ROOT_DIR}/client"
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL="${EMULATOR_BACKEND_URL}" \
  -PBUZER_BACKEND_TOKEN="${TOKEN}" \
  -PBUZER_ASSIGNEE_ME_ID="${BUZER_ASSIGNEE_ME_ID:-user-me}" \
  -PBUZER_ASSIGNEE_WIFE_ID="${BUZER_ASSIGNEE_WIFE_ID:-user-wife}" \
  -PFAKE_BACKEND_BASE_URL="${EMULATOR_BACKEND_URL}"
