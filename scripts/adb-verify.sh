#!/usr/bin/env bash
set -euo pipefail

# adb-verify.sh — token-efficient Android device/emulator verification helper.
#
# Wraps the canonical low-token verification commands for the Buzer Android client so
# agents emit small, pre-filtered evidence instead of raw logcat/dumpsys/screenshot blobs.
# See docs/notes/2026-06-12-agent-token-efficient-android-testing.md for the rationale.
#
# Config (override via env):
#   BUZER_DEVICE_SERIAL  device serial; falls back to ANDROID_SERIAL, then sole attached device
#   BUZER_APP_ID         application id            (default: app.buzer.android)
#   BUZER_LOG_TAG        logcat tag filter         (default: Buzer)
#   BUZER_LOG_LINES      default logcat line cap   (default: 200)
#   BUZER_SCREENSHOT_MAX default screenshot max dimension in px (default: 720)
#   ANDROID_HOME         Android SDK location for `install` (default: $HOME/Library/Android/sdk)

app_id="${BUZER_APP_ID:-app.buzer.android}"
log_tag="${BUZER_LOG_TAG:-Buzer}"
log_lines="${BUZER_LOG_LINES:-200}"
screenshot_max="${BUZER_SCREENSHOT_MAX:-720}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

usage() {
  cat >&2 <<EOF
Usage: scripts/adb-verify.sh <command> [args]

Commands:
  devices              List attached devices/emulators.
  logcat-clear         Clear the device log buffer (call before triggering an action).
  logcat [lines]       Dump last N lines for tag "$log_tag" (default $log_lines).
  boot-log [lines]     Dump recent log lines matching boot recovery markers / BAL_BLOCK.
  alarms               Show scheduled alarms for $app_id (dumpsys alarm, filtered).
  perms                Show overlay/notification/exact-alarm/full-screen app-op state.
  screenshot <out> [max]   Capture a downscaled PNG (default max ${screenshot_max}px), verifying exit code and size.
  install              Scoped \`./gradlew :androidApp:installDebug\` on the resolved device.
  health               One-shot snapshot: device + perms + alarms + recent boot log.
  help                 Show this help.

Device selection: BUZER_DEVICE_SERIAL, else ANDROID_SERIAL, else the sole attached device.
EOF
}

# Resolve a single target device serial without interactive prompting.
resolve_serial() {
  if [[ -n "${BUZER_DEVICE_SERIAL:-}" ]]; then
    printf '%s' "$BUZER_DEVICE_SERIAL"
    return 0
  fi
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    printf '%s' "$ANDROID_SERIAL"
    return 0
  fi

  local serials
  serials="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  local count
  count="$(printf '%s\n' "$serials" | grep -c . || true)"

  if [[ "$count" -eq 1 ]]; then
    printf '%s' "$serials"
    return 0
  fi
  if [[ "$count" -eq 0 ]]; then
    echo "adb-verify: no attached device in 'device' state. Run 'adb devices'." >&2
    return 1
  fi
  echo "adb-verify: multiple devices attached; set BUZER_DEVICE_SERIAL or ANDROID_SERIAL:" >&2
  printf '%s\n' "$serials" >&2
  return 1
}

# adb against the resolved device.
adbx() {
  adb -s "$SERIAL" "$@"
}

# Grep that never aborts under `set -e`, with a friendly empty note.
filter() {
  local pattern="$1"
  local out
  out="$(grep -E "$pattern" || true)"
  if [[ -z "$out" ]]; then
    echo "(no matching lines)"
  else
    printf '%s\n' "$out"
  fi
}

cmd_devices() {
  adb devices -l
}

cmd_logcat_clear() {
  adbx logcat -c
  echo "Cleared log buffer on $SERIAL."
}

cmd_logcat() {
  local lines="${1:-$log_lines}"
  adbx logcat -d -s "$log_tag" -t "$lines"
}

cmd_boot_log() {
  local lines="${1:-$log_lines}"
  adbx logcat -d -s "$log_tag" -t "$lines" | filter 'boot_reconcile|BAL_BLOCK|reschedule|reconcile complete'
}

cmd_alarms() {
  adbx shell dumpsys alarm | filter "$app_id"
}

cmd_perms() {
  echo "# appops ($app_id)"
  adbx shell cmd appops get "$app_id" \
    | filter 'POST_NOTIFICATION|SCHEDULE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW'
  echo "# deviceidle whitelist"
  adbx shell dumpsys deviceidle whitelist | filter "$app_id"
}

cmd_screenshot() {
  local out="${1:?usage: screenshot <out.png> [max-dim]}"
  local max="${2:-$screenshot_max}"

  if ! adbx exec-out screencap -p > "$out"; then
    echo "adb-verify: screencap failed on $SERIAL." >&2
    rm -f "$out"
    return 1
  fi

  local bytes
  bytes="$(wc -c < "$out" | tr -d ' ')"
  if [[ "$bytes" -lt 1000 ]]; then
    echo "adb-verify: screenshot is $bytes bytes (likely a secure/blank surface); not trusting it." >&2
    return 1
  fi

  if command -v sips >/dev/null 2>&1; then
    sips -Z "$max" "$out" >/dev/null
    echo "Screenshot: $out (${bytes} bytes raw, downscaled to <= ${max}px)."
  else
    echo "Screenshot: $out (${bytes} bytes; install 'sips'/ImageMagick to downscale before reading)."
  fi
}

cmd_install() {
  export ANDROID_SERIAL="$SERIAL"
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  ( cd "$repo_root/client" && ./gradlew :androidApp:installDebug --console=plain -q )
  echo "Installed debug build on $SERIAL."
}

cmd_health() {
  echo "=== device ==="
  adbx shell getprop ro.product.model
  echo "=== perms ==="
  cmd_perms
  echo "=== alarms ==="
  cmd_alarms
  echo "=== recent boot log ==="
  cmd_boot_log
}

main() {
  local command="${1:-help}"
  case "$command" in
    help|-h|--help) usage; return 0 ;;
    devices) cmd_devices; return 0 ;;
  esac

  SERIAL="$(resolve_serial)"

  shift || true
  case "$command" in
    logcat-clear) cmd_logcat_clear "$@" ;;
    logcat)       cmd_logcat "$@" ;;
    boot-log)     cmd_boot_log "$@" ;;
    alarms)       cmd_alarms "$@" ;;
    perms)        cmd_perms "$@" ;;
    screenshot)   cmd_screenshot "$@" ;;
    install)      cmd_install "$@" ;;
    health)       cmd_health "$@" ;;
    *)
      echo "adb-verify: unknown command '$command'" >&2
      usage
      return 2
      ;;
  esac
}

main "$@"
