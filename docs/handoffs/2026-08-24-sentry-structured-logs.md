# Handoff: Android Sentry Structured Logs

Date: 2026-08-24

Plan: `docs/superpowers/plans/2026-08-24-sentry-structured-logs.md`

## Scope And Status

Implemented Tasks 1 and 2 Steps 1-3 from the plan. Task 2 Step 4, the hosted-Sentry
physical-device log canary, remains unchecked until a build containing this commit is
installed and a fresh report is observed in Sentry Logs.

Starting commit: `0ab2f075d668`

Branch/worktree:

- `feature/sentry-structured-logs`
- `/Users/svehla/playground/buzer-app/.worktrees/sentry-structured-logs`

## Result

- Enabled Sentry's structured Logs product in the Android SDK.
- Kept the existing `AppLog` breadcrumb path and additionally forwards the same records
  to `Sentry.logger()` at the matching severity.
- Added a `logs.beforeSend` sanitizer for log bodies and attributes using the existing
  `DiagnosticRedactor` credential policy.
- Added stable `bug_report_form_opened` and `bug_report_submitted` markers using native
  Sentry feedback callbacks.
- Kept `showFeedback()` as the native `Sentry.feedback().show()` call. No event ID,
  report session, diagnostic event, custom form, attachment, or backend request exists.
- Updated `AGENTS.md`, the lean design, operations notes, and triage runbook so future
  agents know that feedback/log correlation is manual by timestamp, release, and
  environment.

## Files Changed

- `AGENTS.md`
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt`
- `docs/superpowers/specs/2026-08-22-lean-observability-design.md`
- `docs/superpowers/specs/2026-08-24-sentry-structured-logs-design.md`
- `docs/superpowers/plans/2026-08-24-sentry-structured-logs.md`
- `docs/notes/observability-operations.md`
- `docs/runbooks/observability-triage.md`
- `docs/handoffs/2026-08-24-sentry-structured-logs.md`

## Documentation Lookup

- Command: `npx ctx7@latest library "Sentry Java Android SDK" ...`
  Result: PASS
  Evidence: resolved current official SDK source as `/getsentry/sentry-java`.
- Command: `npx ctx7@latest docs "/getsentry/sentry-docs" "Android Java structured logs ..."`
  Result: PASS
  Evidence: official Android guidance requires `options.logs.isEnabled = true` and uses
  `Sentry.logger()` for searchable Logs.
- Local SDK verification: inspected Sentry Java `8.53.0` bytecode signatures for
  `SentryOptions.Logs`, `SentryLogEvent`, `ILoggerApi`, and feedback callbacks.

## Verification

- Command: focused `AndroidObservabilityConfigTest` before production changes.
  Result: FAIL (expected RED)
  Evidence: assertion failed at `options.logs.isEnabled`, proving the test exercised the
  missing configuration.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*AndroidObservabilityConfigTest*' --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; all three focused configuration tests passed.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; 74 actionable tasks, debug APK assembled.

- Command: `git -c core.fsmonitor=false diff --check`
  Result: PASS
  Evidence: no whitespace errors.

- Command: tracked Sentry token/DSN value scan with `git -c core.fsmonitor=false grep`.
  Result: PASS
  Evidence: no committed auth-token or production DSN values. Historical docs contain
  placeholder commands and a `test-token` example only.

- Command: `scripts/e2e-real-backend.sh`
  Result: SKIPPED
  Evidence: this change does not alter client UI or user-visible form behavior; it only
  changes telemetry transport and callbacks. Existing native form entry-point coverage
  remains unchanged.

## Manual Verification

SKIPPED. After integration, install the new build and submit a fresh report. In the
`buzer-android` Logs view, filter by the report release/environment and timestamp; verify
`bug_report_form_opened`, `bug_report_submitted`, and preceding application records.

## Assumptions And Limitations

- Structured logs are independent Sentry items and are not displayed inline in a native
  feedback issue.
- Timestamp/release/environment lookup is acceptable for this one-user, one-device app.
- All app debug/info/warn/error records are sent when Sentry is enabled; hosted quota and
  retention should be watched and sampling added only if real volume requires it.
- Blank-DSN behavior remains unchanged: Sentry delivery is a no-op and local
  Kermit/Logcat output continues.

## Next Step

Integrate the branch, install the resulting Android build, submit a fresh physical-device
report, and complete Task 2 Step 4 only after the two markers and nearby logs are visible
in hosted Sentry.
