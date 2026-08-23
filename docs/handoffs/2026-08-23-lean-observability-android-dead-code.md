# Handoff: Lean Observability Android Dead-Code Cleanup

Date: 2026-08-23

Plan: `docs/superpowers/plans/2026-08-22-lean-observability.md` (post-review Android/shared dead-surface cleanup)

## Task and checkbox status

- [x] Remove the inventory-proven unused Android observability state, flush, runtime feedback wrapper, ignored event-ID formatting, and raw-throwable breadcrumb plumbing.
- [x] Remove the unused enforcement coordinator observability dependency and API-client parameter.
- [x] Remove diagnostic-record capture timestamps and unused serialization annotations while retaining correlation and sanitized record fields.
- [x] Remove only generated `BUZER_GIT_SHA`; retain Git SHA resolution for `SENTRY_RELEASE`.
- [x] Run expected-red, focused, broad, generated-BuildConfig, and diff verification.

## Files changed

- `client/androidApp/build.gradle.kts`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidObservability.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidObservabilityConfigTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/SentryEventSanitizerTest.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientErrorTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/AppLogTest.kt`
- `docs/handoffs/2026-08-23-lean-observability-android-dead-code.md`

## Implementation summary

- `DiagnosticLogSink` and `AndroidObservability.addLog` now receive only the sanitized `DiagnosticLogRecord`; handled errors retain their separate throwable capture path.
- `captureHandled` now returns `Unit`; Sentry capture remains called, but no event ID is formatted or exposed. The recording seam asserts captured side effects instead.
- Removed dead `isEnabled`, `flush`, `ObservabilityRuntime.showFeedback`, `EnforcementCoordinator.observability`, and `buzerApiClient(observability)` surface.
- `DiagnosticLogRecord` no longer carries a timestamp and the diagnostic models no longer have serialization annotations. `DiagnosticCorrelation` fields are unchanged.
- Debug BuildConfig now emits the SHA only within `SENTRY_RELEASE`.

## Verification

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*AppLogTest*' --console=plain`
  Result: FAIL (expected RED)
  Evidence: test-first one-argument sink expectation failed against the old two-argument `DiagnosticLogSink` contract.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*AppLogTest*' --tests '*BuzerApiClientErrorTest*' --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; sanitized breadcrumb records and request-ID breadcrumb coverage pass.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*AndroidObservabilityConfigTest*' --tests '*SentryEventSanitizerTest*' --tests '*BlockerErrorReporterTest*' --tests '*EnforcementCoordinatorTest*' --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; observability configuration/sanitization and enforcement reporting/coordinator coverage pass.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL`; 74 actionable tasks completed.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:generateDebugBuildConfig --rerun-tasks --console=plain`, then inspect generated `BuildConfig.java`
  Result: PASS
  Evidence: `SENTRY_RELEASE = "app.buzer@0.1.0-dev+6378aea8ba66"`; `BUZER_GIT_SHA` has no generated field.

- Command: `git -c core.fsmonitor=false diff --check`
  Result: PASS
  Evidence: no whitespace errors.

## Manual verification

SKIPPED. This is deletion-only observability plumbing and test adjustment; it does not change user-visible behavior or instrumentation behavior.

## Assumptions and known limitations

- Existing Gradle output continues to report the pre-existing configuration-time `debugRuntimeClasspath` warning; it does not fail any verification command.
- The negative `sentry-trace`/`baggage` tests and all `DiagnosticCorrelation` fields remain unchanged.

## Next recommended step

Root agent can review and integrate the scoped commit with the remaining `feature/lean-observability` work.
