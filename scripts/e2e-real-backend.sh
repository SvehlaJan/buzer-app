#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-18433}"
TOKEN="test-token"
BACKEND_URL="http://127.0.0.1:${PORT}"
EMULATOR_BACKEND_URL="http://10.0.2.2:${PORT}"
# A physical device reaches the host backend through adb reverse.
CONNECTED_DEVICE_BACKEND_URL="${CONNECTED_DEVICE_BACKEND_URL:-http://127.0.0.1:${PORT}}"
STARTED_PID=""

cleanup() {
  if [[ -n "${STARTED_PID}" ]]; then
    kill "${STARTED_PID}" >/dev/null 2>&1 || true
    wait "${STARTED_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_backend() {
  local deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    if curl --silent --fail "${BACKEND_URL}/health" >/dev/null; then
      return 0
    fi
    if [[ -n "${STARTED_PID}" ]] && ! kill -0 "${STARTED_PID}" >/dev/null 2>&1; then
      local exit_code=0
      wait "${STARTED_PID}" || exit_code="$?"
      echo "E2E backend exited before becoming ready with status ${exit_code}" >&2
      return 1
    fi
    sleep 1
  done
  echo "Timed out waiting for E2E backend at ${BACKEND_URL}" >&2
  return 1
}

if curl --silent --fail "${BACKEND_URL}/health" >/dev/null; then
  echo "Using existing E2E backend at ${BACKEND_URL}"
else
  echo "Starting E2E backend at ${BACKEND_URL}"
  (
    cd "${ROOT_DIR}/backend"
    PORT="${PORT}" npm run e2e-server
  ) &
  STARTED_PID="$!"
  wait_for_backend
fi

# CI sets BUZER_GMD_GPU (e.g. swiftshader_indirect) to force the managed-device
# emulator GPU mode on GPU-less runners; unset locally, the flag is omitted.
GMD_GPU_ARG="${BUZER_GMD_GPU:+-Pandroid.testoptions.manageddevices.emulator.gpu=${BUZER_GMD_GPU}}"

cd "${ROOT_DIR}/client"
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  TEST_TASK=":androidApp:connectedDebugAndroidTest"
  DEVICE_BACKEND_URL="${CONNECTED_DEVICE_BACKEND_URL}"
else
  TEST_TASK="pixel8Api34DebugAndroidTest"
  DEVICE_BACKEND_URL="${EMULATOR_BACKEND_URL}"
fi

GRADLE_ARGS=("${TEST_TASK}" "--rerun-tasks")
if [[ -n "${GMD_GPU_ARG}" ]]; then
  GRADLE_ARGS+=("${GMD_GPU_ARG}")
fi
if [[ -n "${ANDROID_TEST_CLASS:-}" ]]; then
  GRADLE_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=${ANDROID_TEST_CLASS}")
fi
GRADLE_ARGS+=(
  "-PBUZER_BACKEND_BASE_URL=${DEVICE_BACKEND_URL}"
  "-PBUZER_BACKEND_TOKEN=${TOKEN}"
  "-PBUZER_ASSIGNEE_ME_ID=${BUZER_ASSIGNEE_ME_ID:-user-me}"
  "-PBUZER_ASSIGNEE_WIFE_ID=${BUZER_ASSIGNEE_WIFE_ID:-user-wife}"
  "-PFAKE_BACKEND_BASE_URL=${DEVICE_BACKEND_URL}"
)

./gradlew "${GRADLE_ARGS[@]}"
