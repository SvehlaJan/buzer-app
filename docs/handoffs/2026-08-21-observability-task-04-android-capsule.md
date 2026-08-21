# Handoff: Observability Task 4 - Build Android Context, Capsule, And Attachment Collectors

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 4: Build Android Context, Capsule, And Attachment Collectors  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented structured immutable diagnostic context, in-memory bounded ring buffers, JSON diagnostic capsule builders, and safe multi-collector attachment gathering with deterministic budget truncation:

1. **Structured Immutable Context Store** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticContextStore.kt`):
   - Defined `@Serializable` immutable context section data classes:
     - `UiDiagnosticContext` (screen, currentActivity, blockerType, appSection, inFlightOperation)
     - `SyncDiagnosticContext` (scheduleVersion, snapshotGeneration, lastSyncAt, lastSyncResult, cachedTaskCount, lastSyncError)
     - `SchedulingDiagnosticContext` (alarmsRegistered, nextAlarmAt, morningReviewScheduledAt, lastFiredAlarm)
     - `ReadinessDiagnosticContext` (notificationsGranted, exactAlarmGranted, fullScreenIntentGranted, overlayGranted, batteryOptimizationExempt)
     - `ActionsDiagnosticContext` (pendingActionsCount, lastActionId, lastActionOutcome, lastActionError)
     - `DeviceDiagnosticContext` (batteryLevel, isCharging, isInteractive, isPowerSaveMode, networkType, availableMemoryBytes, lowMemory, freeStorageBytes, previousExitReason)
     - `CorrelationDiagnosticContext` (deviceId, taskId, occurrenceKey, actionId, requestId, traceId, reportSessionId)
   - Created `DiagnosticContextSnapshot` holding all 7 sections.
   - Implemented thread-safe `DiagnosticContextStore` with atomic references per section, non-throwing transforms (`updateUi`, `updateSync`, `updateScheduling`, `updateReadiness`, `updateActions`, `updateDevice`, `updateCorrelation`), `setCorrelation(DiagnosticCorrelation)`, `snapshot()`, and `reset()`.

2. **Diagnostic Ring Buffers & Code-Point UTF-8 Truncator** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticRingBuffer.kt`):
   - Implemented `Utf8Truncator` guaranteeing code point and surrogate pair preservation during byte truncation.
   - Implemented `DiagnosticLogRingBuffer` with 1,000-record capacity eviction, 15-minute age eviction, newest-first snapshot ordering, and thread safety.
   - Implemented `DiagnosticNetworkRingBuffer` with 100-exchange capacity eviction, 15-minute age eviction, newest-first snapshot ordering, and thread safety.
   - Implemented `DiagnosticRingBuffer` unifying log and network ring buffers with test reset capability.

3. **Diagnostic JSON Capsule & Artifact Builders** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidDiagnosticCollector.kt`):
   - Defined schema version `1` capsule models: `AndroidDiagnosticCapsule`, `AndroidAppDiagnostics`, `AndroidDeviceDiagnostics`, `AndroidDisplayMetrics`, `DiagnosticSectionStatus`, and `DiagnosticTruncationRecord`.
   - Implemented deterministic JSON capsule serializer `buildCapsuleJson` with full secret redaction via `DiagnosticRedactor`.
   - Implemented artifact formatters:
     - `android-app-log.txt`: timestamped severity/tag/message/throwable lines.
     - `android-network-history.json`: serialized list of network exchanges.
     - `android-threads.txt`: `Thread.getAllStackTraces()` dump sorted alphabetically by thread name and capped at 512 KiB.
     - `captureScreenshot`: PixelCopy window screenshot helper on API 26+ compressed to WebP/JPEG under 2 MiB, safely returning null in headless or invalid surface environments.

4. **Attachment Collector & Truncation Manager** (`client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidAttachmentCollector.kt`):
   - Asynchronously gathers all diagnostic attachments (`android-diagnostics-v1.json`, `android-app-log.txt`, `android-network-history.json`, `android-threads.txt`, `android-screenshot.webp`).
   - Implemented fail-safe isolation with 1-second timeout per collector (`runInterruptible(Dispatchers.IO)`) and 2-second overall budget.
   - Enforced individual limits (512 KiB thread dump, 2 MiB screenshot, 64 KiB network request/response bodies).
   - Implemented deterministic 8 MiB budget truncation priority:
     1. report/release/correlation identity
     2. current task, snapshot, action, alarm, and readiness state
     3. error/warning logs
     4. failed/slow network exchanges
     5. screenshot
     6. info/debug history
   - Recorded all truncation events in `truncations` and updated section statuses in `sectionStatuses`.

5. **Diagnostic Redactor Performance Optimization** (`client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt`):
   - Added early-exit trigger character checks and non-backtracking quote bounds in regexes to achieve sub-millisecond execution on large payloads.

6. **Unit Test Coverage** (`client/androidApp/src/test/kotlin/app/buzer/android/observability/`):
   - `DiagnosticContextStoreTest`: verified atomic section updates across all sections, immutable frozen snapshots, `setCorrelation` mapping, reset behavior, exception suppression, and multithreaded concurrent updates.
   - `DiagnosticRingBufferTest`: verified 1,000-log / 100-network capacity eviction, 15-minute age eviction, newest-first snapshot order, 1/2/3/4-byte code point UTF-8 truncation, and concurrent thread safety.
   - `AndroidDiagnosticCollectorTest`: verified schema version 1 serialization, app log text formatting, network JSON serialization, sorted thread dump generation under 512 KiB, and secret redaction.
   - `AndroidAttachmentCollectorTest`: verified standard attachment assembly, collector timeout isolation, collector failure isolation, 64 KiB network body truncation, and small-budget deterministic truncation priority.

---

## 2. Verification and Test Evidence

All unit and host tests pass:

- **Android Observability Unit Tests**:
  - `DiagnosticContextStoreTest`: 6 tests PASSED
  - `DiagnosticRingBufferTest`: 6 tests PASSED
  - `AndroidDiagnosticCollectorTest`: 5 tests PASSED
  - `AndroidAttachmentCollectorTest`: 5 tests PASSED
  - `AndroidObservabilityConfigTest`: 3 tests PASSED
  - `SentryEventSanitizerTest`: 4 tests PASSED

- **Full Client Test Suite & Build**:
  - `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`: BUILD SUCCESSFUL (74 tasks executed/up-to-date)

---

## 3. Files Created / Modified

- `client/androidApp/build.gradle.kts` (modified)
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt` (modified)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticContextStore.kt` (created)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/DiagnosticRingBuffer.kt` (created)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidDiagnosticCollector.kt` (created)
- `client/androidApp/src/main/kotlin/app/buzer/android/observability/AndroidAttachmentCollector.kt` (created)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/DiagnosticContextStoreTest.kt` (created)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/DiagnosticRingBufferTest.kt` (created)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidDiagnosticCollectorTest.kt` (created)
- `client/androidApp/src/test/kotlin/app/buzer/android/observability/AndroidAttachmentCollectorTest.kt` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-04-android-capsule.md` (created)

---

## 4. Next Steps

- Proceed to **Task 5: Implement The Manual Report Session And Native Feedback UI**.
