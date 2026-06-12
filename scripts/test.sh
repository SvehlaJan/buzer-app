#!/usr/bin/env bash
set -euo pipefail

# test.sh — run Buzer JVM unit tests and print a concise, low-token summary.
#
# Replaces the "cd client && ./gradlew … --console=plain -q && grep the JUnit XML"
# dance with one allowlistable command.
#
# Usage:
#   scripts/test.sh                      # androidApp debug unit tests
#   scripts/test.sh EnforcementCoordinator   # filter to matching test classes
#   scripts/test.sh --shared             # also run shared host tests
#   scripts/test.sh --shared Coordinator # combine

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
client_dir="$script_dir/../client"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

run_shared=false
filter=""
for arg in "$@"; do
  case "$arg" in
    --shared) run_shared=true ;;
    *) filter="$arg" ;;
  esac
done

tasks=()
$run_shared && tasks+=(":shared:testAndroidHostTest")
tasks+=(":androidApp:testDebugUnitTest")

gradle_args=(--console=plain -q)
[[ -n "$filter" ]] && gradle_args+=(--tests "*${filter}*")

cd "$client_dir"
echo "Running: ./gradlew ${tasks[*]} ${gradle_args[*]}"
set +e
./gradlew "${tasks[@]}" "${gradle_args[@]}"
status=$?
set -e

echo "=== test summary (gradle exit=$status) ==="
result_dirs=(
  androidApp/build/test-results/testDebugUnitTest
  shared/build/test-results/testAndroidHostTest
)
shopt -s nullglob
total=0 fails=0 errs=0 skips=0 bad=""
for dir in "${result_dirs[@]}"; do
  for xml in "$dir"/TEST-*.xml; do
    line="$(grep -oE '<testsuite [^>]*>' "$xml" | head -1)"
    [[ -z "$line" ]] && continue
    name="$(sed -E 's/.*name="([^"]+)".*/\1/' <<<"$line")"
    t="$(sed -E 's/.*[^a-z]tests="([0-9]+)".*/\1/' <<<"$line")"
    s="$(sed -E 's/.*skipped="([0-9]+)".*/\1/' <<<"$line")"
    f="$(sed -E 's/.*failures="([0-9]+)".*/\1/' <<<"$line")"
    e="$(sed -E 's/.*errors="([0-9]+)".*/\1/' <<<"$line")"
    total=$((total + t)); skips=$((skips + s)); fails=$((fails + f)); errs=$((errs + e))
    if [[ "$f" -gt 0 || "$e" -gt 0 ]]; then
      bad+=$'\n'"  FAIL ${name##*.} (failures=$f errors=$e)"
    fi
  done
done

echo "tests=$total skipped=$skips failures=$fails errors=$errs"
[[ -n "$bad" ]] && echo "failing classes:$bad"

if [[ "$status" -eq 0 && "$fails" -eq 0 && "$errs" -eq 0 ]]; then
  echo "RESULT: PASS"
else
  echo "RESULT: FAIL"
fi
exit "$status"
