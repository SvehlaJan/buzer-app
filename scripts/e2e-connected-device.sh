#!/usr/bin/env bash
set -euo pipefail

# One narrow entry point for an approved physical-device fake-backend test lane.
#
# The instrumented debug app permits host-loopback cleartext only, so this wrapper
# always establishes adb reverse. Tailscale HTTP is not a supported test path.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${BUZER_DEVICE_SERIAL:-${ANDROID_SERIAL:-}}"
PORT="${PORT:-18438}"

if [[ "${1:-}" == "--class" ]]; then
  if [[ -z "${2:-}" || "${3:-}" != "" ]]; then
    echo "Usage: $0 [--class <instrumentation-class-or-method>]" >&2
    exit 2
  fi
  export ANDROID_TEST_CLASS="${2}"
elif [[ "${1:-}" != "" ]]; then
  echo "Usage: $0 [--class <instrumentation-class-or-method>]" >&2
  exit 2
fi

if [[ -z "${SERIAL}" ]]; then
  devices=()
  while read -r candidate state _; do
    if [[ "${state}" == "device" ]]; then
      devices+=("${candidate}")
    fi
  done < <(adb devices | tail -n +2)
  if [[ "${#devices[@]}" -ne 1 ]]; then
    echo "Attach exactly one Android device or set BUZER_DEVICE_SERIAL." >&2
    exit 2
  fi
  SERIAL="${devices[0]}"
fi

adb -s "${SERIAL}" reverse "tcp:${PORT}" "tcp:${PORT}"
export CONNECTED_DEVICE_BACKEND_URL="http://127.0.0.1:${PORT}"

export ANDROID_SERIAL="${SERIAL}"
export PORT
exec "${ROOT_DIR}/scripts/e2e-fake-backend.sh"
