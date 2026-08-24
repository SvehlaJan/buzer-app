# Handoff: Android Sentry Structured Logs

Date: 2026-08-24

Plan: `docs/superpowers/plans/2026-08-24-sentry-structured-logs.md`

## Scope And Status

Implemented all Tasks 1 and 2 steps from the plan, including the hosted-Sentry
physical-device feedback/log canary.

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

- Command: `ANDROID_SERIAL=56061FDCR005J0 ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:installDebug --console=plain`
  Result: PASS
  Evidence: Gradle installed `androidApp-debug.apk` on the connected Pixel 10. The
  installed build uses release `app.buzer@0.1.0-dev+b1b0cd202dcf` and the locally
  configured Android DSN/environment; no configuration value was printed.

- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install`
  Result: PASS
  Evidence: the repository verification helper confirmed the debug build on device
  `56061FDCR005J0`.

## Manual Verification

PASS. The merged build was installed on the physical Pixel 10 and a fresh report was
submitted:

- Feedback: `BUZER-ANDROID-4`, event `be2ae9a1b14b43dda10d3ac513af4710`.
- Release/environment: `app.buzer@0.1.0-dev+b1b0cd202dcf`, `production-device`.
- Replay: `cb60122626864f04aabbe5fe479edfe1`.
- `bug_report_form_opened`: received at `2026-08-24T21:04:20Z`.
- `bug_report_submitted`: received at `2026-08-24T21:04:50Z`.
- Nearby application logs were searchable, including refresh, snapshot, reconciliation,
  and request-ID evidence.
- Feedback and nearby logs shared SDK-provided trace ID
  `487cad564f20484692756fda5282731d`; no app-owned event ID was used.

## Assumptions And Limitations

- Structured logs are independent Sentry items and are not displayed inline in a native
  feedback issue.
- Timestamp/release/environment lookup is acceptable for this one-user, one-device app.
- All app debug/info/warn/error records are sent when Sentry is enabled; hosted quota and
  retention should be watched and sampling added only if real volume requires it.
- Blank-DSN behavior remains unchanged: Sentry delivery is a no-op and local
  Kermit/Logcat output continues.

## Next Step

Use the captured feedback/replay/log timeline to investigate `BUZER-ANDROID-4`. The
remaining observability rollout gap is the backend production canary documented in the
main lean-observability plan.
