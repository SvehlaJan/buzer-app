# Handoff: Observability Task 1 - Establish Shared Diagnostic Primitives

> [!WARNING]
> **SUPERSEDED (2026-08-22):** The endpoint, trace/capsule, and GitHub-sync material below is historical evidence only. Use the lean plan (`docs/superpowers/plans/2026-08-22-lean-observability.md`) and current runbooks (`docs/runbooks/`) for active operations.

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 1: Establish Shared Diagnostic Primitives  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented core shared diagnostic primitives and redaction contracts for Kotlin Multiplatform:

1. **`DiagnosticModels.kt`** (`client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`):
   - `DiagnosticCorrelation`: Serializable context container for device, task, occurrence, action, request, trace, screen, blocker type, app section, and report session IDs.
   - `DiagnosticLogRecord`: Serializable structured log record with ISO-8601 timestamp (`capturedAt`), `severity`, `tag`, `message`, `throwableType`, and `throwableMessage`.
   - `DiagnosticNetworkExchange`: Serializable HTTP network exchange record capturing duration, method, path, status, outcome, content types, request/response bodies, request/trace/action IDs, and truncation flags.

2. **`DiagnosticRedactor.kt`** (`client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt`):
   - Case-insensitive credential key matching (`authorization`, `cookie`, `set-cookie`, `password`, `secret`, `token`, `api_token`, `access_token`, `refresh_token`, `todoist_token`, `sentry_auth_token`, `private_key`, `whatsapp_session`, `qr_code`, etc.).
   - Regex redaction for Bearer/Basic schemes, URLs with credentials (`scheme://user:pass@host`), PEM private key blocks, cookie/set-cookie lines, and in-text key-value assignments.
   - Preserves correlation keys (`reportSessionId`, `occurrenceKey`, `deviceId`, `taskId`, `actionId`, `requestId`, `traceId`) and already-redacted values (`[REDACTED]`).
   - Supports redaction for `String`, `Map`, `List`, `JsonElement` (`JsonObject`, `JsonArray`, `JsonPrimitive`), and `Throwable`.

3. **`AppLog.kt`** (`client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt`):
   - Added `DiagnosticLogSink` functional interface.
   - Retained Kermit logger as local writer while fanning out records to installed `DiagnosticLogSink`.
   - Sink execution is isolated using `runCatching` to guarantee that logging sink failures never affect application execution.
   - Added `resetSinkForTests()` for clean test teardown.

4. **`test-fixtures/observability/redaction-corpus.json`**:
   - Canonical cross-runtime secret fixture defining sentinel values (`SECRET_TODOIST_123`, `SECRET_ANDROID_123`, `SECRET_WHATSAPP_123`, `SECRET_SENTRY_123`, etc.) with string test cases and nested JSON test cases.

5. **Test Suites**:
   - `DiagnosticRedactorTest.kt` (platform-neutral `commonTest`): Comprehensive redaction tests for strings, auth schemes, URLs, PEM blocks, log key-values, nested maps, lists, and JSON elements.
   - `RedactionCorpusParityTest.kt` (`androidHostTest`): Cross-runtime parity suite loading `test-fixtures/observability/redaction-corpus.json` via file I/O and validating all test cases and complete sentinel absence.
   - `AppLogTest.kt` (`commonTest`): Validates severity mapping, structured record generation, timestamp creation, and sink failure isolation.

---

## 2. Commands Run and Results

- **Run failing test (RED confirmation):**
  ```bash
  ./gradlew :shared:testAndroidHostTest --tests '*DiagnosticRedactorTest*' --console=plain
  ```
  Result: Failed with compilation errors prior to model/redactor implementation (RED).

- **Run shared diagnostics tests:**
  ```bash
  ./gradlew :shared:testAndroidHostTest --tests '*diagnostics*' --console=plain
  ```
  Result: `BUILD SUCCESSFUL` (10 tests passed, 0 failed).

- **Run full shared test suite:**
  ```bash
  ./gradlew :shared:testAndroidHostTest --console=plain
  ```
  Result: `BUILD SUCCESSFUL` (all host tests in `:shared` passed).

- **Run client unit test suite:**
  ```bash
  ./gradlew test --console=plain
  ```
  Result: `BUILD SUCCESSFUL` (all `:shared` and `:androidApp` unit tests passed).

---

## 3. Files Changed

- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt` (created)
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactor.kt` (created)
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt` (modified)
- `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/DiagnosticRedactorTest.kt` (created)
- `client/shared/src/commonTest/kotlin/app/buzer/shared/diagnostics/AppLogTest.kt` (created)
- `client/shared/src/androidHostTest/kotlin/app/buzer/shared/diagnostics/RedactionCorpusParityTest.kt` (created)
- `test-fixtures/observability/redaction-corpus.json` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-01-shared-primitives.md` (created)

---

## 4. Next Steps

- Proceed to **Task 2: Instrument The Shared Ktor Client And Add The Report Contract**.
