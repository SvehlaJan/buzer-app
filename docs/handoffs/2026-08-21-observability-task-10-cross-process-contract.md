# Handoff: Observability Task 10 - Verify The Cross-Process Report Contract End To End

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 10: Verify The Cross-Process Report Contract End To End  
**Status:** DONE  

---

## 1. Summary of Changes

Verified and completed the cross-process diagnostic report contract between the Android client and the backend server end-to-end, ensuring hermetic Sentry capture, idempotent correlation, rate limiting, and strict secret redaction across all diagnostic attachments:

1. **Hermetic Sentry Capture in E2E Server** (`backend/test/e2eServer.ts`, `backend/test/fakes/recordingBackendObservability.ts`):
   - Created reusable `RecordingBackendObservability` test fake that captures Sentry diagnostic events, handled errors, and fatal errors with full tag and attachment metadata in-memory without contacting external Sentry servers.
   - Injected `RecordingBackendObservability` into `buildServer` options in `e2eServer.ts`.
   - Exposed debug endpoint `GET /__sentry/events` returning diagnostic events, handled errors, and fatal errors with full attachment content.
   - Wired `observability.clear()` into the `reset()` transaction to cleanly purge recorded events between tests.
   - Exposed `observability: RecordingBackendObservability` on the `E2EServer` interface.

2. **Cross-Process Report E2E Test Suite** (`backend/test/crossProcessReportE2E.test.ts`):
   - Full flow integration testing:
     - Verified bearer token authorization requirement (rejects 401 when unauthenticated or bad token).
     - Verified client sends valid `DiagnosticReportRequest` (schemaVersion 1, reportSessionId, androidDiagnosticEventId, deviceId, capturedAt, taskId, occurrenceKey, actionId, requestIds, traceId) to `POST /diagnostics/reports`.
     - Verified server responds with HTTP 202 and matching `DiagnosticReportResponse` (schemaVersion 1, matching reportSessionId, 32-hex lowercase backendDiagnosticEventId, outcome "captured").
     - Inspected recorded Sentry event on backend:
       - Message: `buzer.backend.manual_report`
       - Verified tags: `report_session_id`, `android_diagnostic_event_id`, `backend_diagnostic_event_id`, `device_id`, `feedback_submitted = "true"`, `capsule_schema_version = "1"`, `task_id`, `occurrence_key`, `action_id`, `trace_id`.
       - Verified required attachments: `backend-diagnostics-v1.json`, `backend-app-log.txt`, `backend-polling-history.json`.
       - Verified strict redaction across all attachment contents (ensuring passwords, bearer tokens, private keys, session tokens, and cookie values never leak).
   - Duplicate submission idempotency:
     - Verified duplicate POST requests with the same `reportSessionId` return identical `backendDiagnosticEventId` and do not emit a duplicate Sentry event.
   - Rate limiting:
     - Verified 6 requests succeed within rate limit window, 7th returns HTTP 429 Too Many Requests.
     - Verified rate limiting is scoped per device ID.
   - Reset lifecycle:
     - Verified `POST /__reset` purges recorded Sentry events and resets state.

3. **Android Bug Report Flow Instrumentation Tests** (`client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportFlowInstrumentationTest.kt`):
   - Verified opening report form freezes context snapshot and begins attachment gathering.
   - Verified that mutating the context store after form opening does not affect the frozen snapshot captured during form open.
   - Verified submission creates paired `buzer.android.manual_report` event with `feedback_submitted = "true"`, `capsule_schema_version = "1"`, reserved event ID, and attachments (`android-diagnostics-v1.json`, `android-app-log.txt`, `android-network-history.json`).
   - Verified backend diagnostic report request is dispatched with correlated IDs and recent request history.
   - Verified fallback action "Report a bug" triggers `ObservabilityRuntime.showFeedback()`.

4. **Redaction & Tag Correlation Improvements** (`backend/src/diagnostics/backendObservability.ts`, `backend/src/diagnostics/diagnosticReportService.ts`, `backend/src/diagnostics/redaction.ts`):
   - Added `backendDiagnosticEventId` explicitly to `DiagnosticCorrelation` and `diagnosticReportService` correlation mapping.
   - Added `backend_diagnostic_event_id` tag to Sentry scope in `SentryBackendObservability.captureDiagnosticEvent`.
   - Enhanced `isCredentialKey` and `KEY_VALUE_PATTERN` in `redaction.ts` to support cookie key patterns.

---

## 2. Verification and Test Evidence

- **Backend Cross-Process Report E2E Tests**:
  - `npm test -- --run test/crossProcessReportE2E.test.ts` in `backend/`:
    - 5 tests passed (0 failures).

- **Full Backend Test Suite**:
  - `npm test` in `backend/`:
    - 42 test files passed, 456 tests passed (0 failures).

- **Backend Typecheck & Build**:
  - `npm run typecheck` in `backend/`: 0 errors.
  - `npm run build` in `backend/`: built successfully to `backend/dist`.

- **Client Tests & Instrumentation Build**:
  - `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest --console=plain` in `client/`:
    - 55 unit test tasks passed, AndroidTest APK assembled cleanly (0 errors).

---

## 3. Files Created / Modified

- `backend/src/diagnostics/backendObservability.ts` (modified)
- `backend/src/diagnostics/diagnosticReportService.ts` (modified)
- `backend/src/diagnostics/redaction.ts` (modified)
- `backend/test/fakes/recordingBackendObservability.ts` (created)
- `backend/test/e2eServer.ts` (modified)
- `backend/test/crossProcessReportE2E.test.ts` (created)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/observability/BugReportFlowInstrumentationTest.kt` (modified)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-10-cross-process-contract.md` (created)

---

## 4. Next Steps

- Proceed to **Task 11: Add Deduplicated Sentry-To-GitHub Triage**.
