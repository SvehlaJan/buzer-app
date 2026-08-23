# Handoff: Observability Task 6 - Wire Android Runtime Context And Critical Non-Fatals

> [!WARNING]
> **SUPERSEDED (2026-08-22):** The endpoint, trace/capsule, and GitHub-sync material below is historical evidence only. Use the lean plan (`docs/superpowers/plans/2026-08-22-lean-observability.md`) and current runbooks (`docs/runbooks/`) for active operations.

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 6: Wire Android Runtime Context And Critical Non-Fatals  
**Status:** DONE  

---

## 1. Summary of Changes

Wired runtime context updates and non-fatal error classification/capture across Android enforcement, blockers, readiness, morning review, and activities:

1. **Error Classification & Critical Non-Fatal Capture** (`client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`):
   - Injected `AndroidObservability` (defaulting to `ObservabilityRuntime.instance`).
   - Implemented error severity classification:
     - **Critical Non-Fatals Captured via `observability.captureHandled(...)`**: Blocker actions (`START_TASK`, `POSTPONE_START`, `COMPLETE_TASK`, `EXTEND_TASK`), coordinator creation, boot reconciliation, alarm scheduling, and snapshot refresh/integrity failures.
     - **Logs / Breadcrumbs Only**: Normal retryable / validation failures (e.g., HTTP 400, 422, 409, 429, or validation messages).
   - Added thread-safe `Throwable` identity deduplication using a `WeakHashMap` set to prevent double-capturing the same exception across nested error boundaries.
   - Isolated observer failures with `runCatching` so broken telemetry never impedes local log files or notifications.

2. **Dependency Roots & Observability Injection** (`client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`):
   - Configured `buzerApiClient(observability)` with `networkSink` recording to `DiagnosticRingBuffer.network` and `traceHeaderProvider` extracting active Sentry trace headers.
   - Injected `observability` and `contextStore = DiagnosticContextStore.instance` into `EnforcementCoordinator` and `BlockerErrorReporter`.

3. **Enforcement Coordinator Context Wiring** (`client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`):
   - Updated `reconcile`: records `alarmsRegistered` count and `nextAlarmAt` ISO timestamp into `SchedulingDiagnosticContext`.
   - Updated `applyAuthoritativeSnapshot` & `applyFreshSnapshot`: records `scheduleVersion`, `snapshotGeneration`, `lastSyncAt`, `lastSyncResult = "success"`, and `cachedTaskCount` into `SyncDiagnosticContext`.
   - Updated `applyConflictSnapshot`: records `lastSyncResult = "conflict"`.
   - Updated `runBackendAction`: updates correlation (`taskId`, `occurrenceKey`, `actionId`), sets in-flight actions, and updates `observability.setContext`.
   - Updated `applyMutationResponse` & `failedAction`: updates action outcome and passes `throwable = error` to `errorReporter.report(...)`.

4. **Producer & Activity Context Updates**:
   - `MainActivity.kt`:
     - Sets UI context (`screen = "Home"`, `currentActivity = "MainActivity"`, `appSection = "Home"`) and Sentry correlation on `onCreate` and `onResume`.
     - Records sync error state in `reportRefreshFailure` and passes `throwable` to `BlockerErrorReporter`.
     - Sets UI context (`screen = "MorningReview"`) on opening Morning Review.
     - Passes `throwable` on coordinator creation failure.
   - `StartBlockerActivity.kt`:
     - Sets UI context (`screen = "StartBlocker"`, `currentActivity = "StartBlockerActivity"`, `blockerType = "StartBlocker"`, `appSection = "Enforcement"`) and correlation (`taskId`, `occurrenceKey`) on `onCreate`.
     - Clears UI context on `onDestroy`.
   - `DeadlineBlockerActivity.kt`:
     - Sets UI context (`screen = "DeadlineBlocker"`, `currentActivity = "DeadlineBlockerActivity"`, `blockerType = "DeadlineBlocker"`, `appSection = "Enforcement"`) and correlation on `onCreate`.
     - Clears UI context on `onDestroy`.
   - `MorningReviewActivity.kt`:
     - Sets UI context (`screen = "MorningReview"`, `currentActivity = "MorningReviewActivity"`, `blockerType = "MorningReview"`, `appSection = "MorningReview"`) on `onCreate`.
     - Clears UI context on `onDestroy`.
     - Passes `throwable` to `errorReporter.report(...)` on cached/forced sync failures.
   - `OverlayBlockerService.kt`:
     - Sets UI context (`screen = "OverlayBlocker"`, `blockerType = request.kind.name`, `appSection = "Enforcement"`) and correlation on `showOverlay`.
     - Cleans up UI and correlation context on `stopOverlay` and `onDestroy`.
   - `AlarmReceiver.kt`:
     - Records `lastFiredAlarm` in `SchedulingDiagnosticContext` upon broadcast receipt.
   - `MorningReviewScheduler.kt`:
     - Records `morningReviewScheduledAt` in `SchedulingDiagnosticContext` on `replaceAlarm`.
   - `AndroidEnforcementReadinessChecker.kt`:
     - Records readiness capabilities (`notificationsGranted`, `exactAlarmGranted`, `fullScreenIntentGranted`, `overlayGranted`, `batteryOptimizationExempt`) in `ReadinessDiagnosticContext` during `check()`.
   - `BootReconcileRunner.kt`:
     - Reports uncaught boot reconciliation failures with `throwable` to `BlockerErrorReporter`.

5. **Telemetry Guard & Test Suite**:
   - `AndroidContextWiringTest.kt`:
     - Enforces that no Kotlin file outside `observability/` imports `io.sentry.*`.
     - Verifies `DiagnosticContextStore` sync updates, scheduling updates, action transitions, correlation updates, readiness updates, and UI updates.
     - Verifies non-fatal error routing and throwable passing to `BlockerErrorReporter`.
   - `BlockerErrorReporterTest.kt`:
     - Verifies non-fatal capture routing, validation log-only routing, coordinator failure routing, throwable deduplication, and observer failure isolation.

---

## 2. Verification and Test Evidence

All unit and host test suites passed successfully:

- **Targeted Test Suites**:
  - `BlockerErrorReporterTest`: 6 tests PASSED
  - `EnforcementCoordinatorTest`: 37 tests PASSED
  - `ForegroundReadinessControllerTest`: 15 tests PASSED
  - `MorningReview*`: 92 tests PASSED
  - `AndroidContextWiringTest`: 5 tests PASSED
  - Total targeted tests: 155 tests PASSED
- **Full Shared and Android Test Suite**:
  - `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`: BUILD SUCCESSFUL (74 actionable tasks executed/up-to-date)
- **Sentry Import Guard**:
  - `AndroidContextWiringTest.noDirectSentryImportsOutsideObservabilityPackage`: PASSED (0 direct Sentry imports outside `app.buzer.android.observability`).

---

## 3. Files Created / Modified

- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticContextStore.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticRingBuffer.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/AndroidEnforcementReadinessChecker.kt` (modified)
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt` (modified)
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt` (modified)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidContextWiringTest.kt` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-06-android-context.md` (created)

---

## 4. Next Steps

- Proceed to **Task 7: Add Backend Observability Foundation**.
