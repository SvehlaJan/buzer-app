# Handoff: Observability Task 5 - Implement The Manual Report Session And Native Feedback UI

> [!WARNING]
> **SUPERSEDED (2026-08-22):** The endpoint, trace/capsule, and GitHub-sync material below is historical evidence only. Use the lean plan (`docs/superpowers/plans/2026-08-22-lean-observability.md`) and current runbooks (`docs/runbooks/`) for active operations.

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 5: Implement The Manual Report Session And Native Feedback UI  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented the manual report coordinator state machine, wired Sentry feedback callbacks with temporary correlation scope, and added the native visible bug report fallback on the Home screen:

1. **Manual Report Coordinator** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/ManualReportCoordinator.kt`):
   - Implemented state machine: `IDLE -> FORM_OPEN -> SUBMITTED | FAILED | CLOSED -> IDLE`.
   - `onFormOpen()`:
     - Recovers from any stale session and clears prior temporary scope.
     - Generates UUID `reportSessionId` and reserves 32-hex `androidDiagnosticEventId`.
     - Freezes current `DiagnosticContextSnapshot` from `DiagnosticContextStore`.
     - Configures temporary Sentry scope tags (`report_session_id`, `android_diagnostic_event_id`).
     - Asynchronously kicks off `AndroidAttachmentCollector.collectAttachments` with frozen context.
   - `onSubmitSuccess(userFeedback)`:
     - Waits for attachment collection.
     - Captures diagnostic event `buzer.android.manual_report` with `feedback_submitted = "true"`, `capsule_schema_version = "1"`, and reserved event ID.
     - Asynchronously sends backend diagnostic report via `backendReportSender` (`BuzerApiClient.createDiagnosticReport`), including bounded recent network request IDs from `DiagnosticRingBuffer.network`.
     - Isolates backend report failures so telemetry errors never affect UI/feedback.
     - Clears temporary scope and resets state to `IDLE`.
   - `onFormClose()` / `onSubmitError(error)`:
     - Captures non-alerting orphan diagnostic event tagged `feedback_submitted = "false"`.
     - Clears temporary scope and resets state to `IDLE`.
   - Thread-safe synchronization and idempotent callbacks when idle or executed consecutively.

2. **Native Sentry Feedback Wiring** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt`):
   - Configured `options.feedbackOptions`:
     - `isUseShakeGesture = true`
     - `isShowName = false`, `isShowEmail = false`, `isUseSentryUser = false`, `isShowBranding = false`
     - `formTitle = "Report a bug"`, `messageLabel = "What went wrong?"`, `messagePlaceholder = "What did you expect, and what happened?"`
     - `submitButtonLabel = "Send report"`, `successMessageText = "Report sent. Thank you."`
     - Delegated `onFormOpen`, `onFormClose`, `onSubmitSuccess`, and `onSubmitError` to `ManualReportCoordinator`.
   - Updated `captureDiagnosticEvent` to tag `capsule_schema_version = "1"`, `android_diagnostic_event_id`, and `feedback_submitted`.

3. **Home Fallback Bug Reporting Action** (`client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`, `App.kt`, `MainActivity.kt`):
   - Added `onReportBug: () -> Unit = {}` to `HomeScreen` and populated `IconFabStack.secondaryActions` with a "Report a bug" button.
   - Passed `onReportBug` through `BuzerApp` down to `HomeScreen`.
   - Connected `MainActivity` to invoke `ObservabilityRuntime.instance.showFeedback()`.

4. **Unit and Instrumentation Tests**:
   - `ManualReportCoordinatorTest.kt`:
     - Verified paired events on submit success with matched session/event IDs.
     - Verified orphan event capture with `feedback_submitted = false` on form close without submit.
     - Verified orphan event capture on submit error.
     - Verified backend failure isolation.
     - Verified snapshot freezing at form open.
     - Verified recent network request IDs inclusion in backend report.
     - Verified stale session recovery and idempotency.
   - `HomeScreenTest.kt`:
     - Verified FAB stack renders "Add task" and "Report a bug", triggering the bug report callback.
   - `BugReportFlowInstrumentationTest.kt`:
     - Verified launching `MainActivity` and clicking "Report a bug" triggers `AndroidObservability.showFeedback()`.

---

## 2. Verification and Test Evidence

All unit and host tests pass:

- **ManualReportCoordinator Tests**:
  - `ManualReportCoordinatorTest`: 8 tests PASSED
- **Observability Suite**:
  - `DiagnosticContextStoreTest`: 6 tests PASSED
  - `DiagnosticRingBufferTest`: 6 tests PASSED
  - `AndroidDiagnosticCollectorTest`: 5 tests PASSED
  - `AndroidAttachmentCollectorTest`: 5 tests PASSED
  - `AndroidObservabilityConfigTest`: 3 tests PASSED
  - `SentryEventSanitizerTest`: 4 tests PASSED
  - `ManualReportCoordinatorTest`: 8 tests PASSED
- **Client Build & Test Suite**:
  - `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`: BUILD SUCCESSFUL (74 tasks executed/up-to-date)
  - `./gradlew :androidApp:assembleDebugAndroidTest --console=plain`: BUILD SUCCESSFUL (64 tasks executed/up-to-date)

---

## 3. Files Created / Modified

- `client/androidApp/src/main/kotlin/app/buzer/android/observability/ManualReportCoordinator.kt` (created)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/SentryAndroidObservability.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt` (modified)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/ManualReportCoordinatorTest.kt` (created)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/HomeScreenTest.kt` (modified)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportFlowInstrumentationTest.kt` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-05-android-feedback.md` (created)

---

## 4. Next Steps

- Proceed to **Task 6: Wire Android Runtime Context And Critical Non-Fatals**.
