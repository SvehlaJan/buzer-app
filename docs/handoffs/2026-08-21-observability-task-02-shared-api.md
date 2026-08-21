# Handoff: Observability Task 2 - Instrument The Shared Ktor Client And Add The Report Contract

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 2: Instrument The Shared Ktor Client And Add The Report Contract  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented shared Ktor client instrumentation and manual report contracts for Kotlin Multiplatform:

1. **`DiagnosticModels.kt`** (`client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`):
   - Added `@Serializable data class DiagnosticReportRequest`: serializable request DTO for manual diagnostic reports containing `schemaVersion`, `reportSessionId`, `androidDiagnosticEventId`, `deviceId`, `capturedAt`, `taskId`, `occurrenceKey`, `actionId`, `requestIds`, and `traceId`.
   - Added `@Serializable data class DiagnosticReportResponse`: serializable response DTO containing `schemaVersion`, `reportSessionId`, `backendDiagnosticEventId`, and `outcome`.

2. **`BuzerApiClient.kt`** (`client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`):
   - Added `NetworkDiagnosticSink` functional interface and `TraceHeaders` data class in common code.
   - Extended `BuzerApiClient` constructor with default parameters for `networkSink`, `requestIdProvider`, `traceHeaderProvider`, and `redactor` preserving backward compatibility with all existing callers.
   - Added `createDiagnosticReport(request: DiagnosticReportRequest): DiagnosticReportResponse` targeting `POST /diagnostics/reports` with a 5-second request timeout.
   - Unified request execution through internal `execute` helper:
     - Generates unique UUID `requestId` via `requestIdProvider` and sends `X-Request-ID` header.
     - Attaches `sentry-trace` and `baggage` headers when `traceHeaderProvider` returns non-blank values, without replacing or overriding Bearer authorization.
     - Automatically infers `actionId` from all mutation request DTOs (`CreateTaskRequest`, `TaskActionRequest`, `UpdateTaskRequest`, `ExtendTaskRequest`, `AddChecklistItemRequest`, `UpdateChecklistItemRequest`, `FocusedChecklistMutationRequest`, `DiagnosticReportRequest`).
     - Records only path (e.g. `/tasks/snapshot`), never scheme, host, or URL credentials.
     - Redacts request and response bodies using `DiagnosticRedactor` and bounds stored bodies to 64 KiB (65,536 characters), setting `requestTruncated` and `responseTruncated` flags.
     - Accurately tracks start instant, elapsed duration in milliseconds, status code, response content-type, and outcome (`"success"`, `"http_error"`, `"timeout"`, `"network_error"`, `"cancelled"`).
     - Isolates `networkSink.record(exchange)` execution inside `runCatching` so broken sinks never break HTTP execution or throw to caller.

3. **`BuzerApiClientContractTest.kt`** (`client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`):
   - Added `createDiagnosticReportPostsJsonAndDecodesResponse` test verifying `POST /diagnostics/reports`, Bearer auth, JSON body encoding, 5-second timeout, response decoding into `DiagnosticReportResponse`, and ID round-tripping.

4. **`BuzerApiClientDiagnosticsTest.kt`** (`client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt`):
   - Created comprehensive diagnostic test suite covering:
     - Scheme/host/credentials exclusion (path only in `DiagnosticNetworkExchange.path`).
     - `X-Request-ID`, `sentry-trace`, and `baggage` propagation alongside Bearer auth.
     - Secret redaction and 64 KiB body truncation for request and response bodies.
     - Outcome classification for 2xx (`"success"`), 4xx/5xx (`"http_error"`), timeout (`"timeout"`), socket/network failures (`"network_error"`), and coroutine cancellation (`"cancelled"`).
     - `actionId` inference from mutation requests.
     - Throwing `networkSink` isolation without affecting HTTP result.

---

## 2. Verification and Test Evidence

All shared contract and diagnostic tests pass with full compliance to the specification:

- **Diagnostic Models & DTOs**:
  - `DiagnosticReportRequest` and `DiagnosticReportResponse` serialization and deserialization validated.
- **Contract Tests**:
  - `createDiagnosticReport` contract test validates exact path `POST /diagnostics/reports`, header preservation, JSON body structure, and response mapping.
- **Network Diagnostics Tests**:
  - `recordsOnlyPathNeverSchemeOrHostCredentials`: passed
  - `sendsRequestIdAndTraceHeadersWithoutReplacingBearerAuth`: passed
  - `redactsSecretsAndTruncatesLargeBodies`: passed
  - `truncatesLargeRequestBodyAndSetsTruncatedFlag`: passed
  - `recordsHttpErrorOnNon2xxResponse`: passed
  - `recordsTimeoutOutcomeOnRequestTimeout`: passed
  - `recordsNetworkErrorOnConnectionFailure`: passed
  - `recordsCancelledOutcomeOnCoroutineCancellation`: passed
  - `failingNetworkSinkNeverBreaksHttpExecutionOrThrowsToCaller`: passed
  - `infersActionIdFromVariousMutationRequests`: passed

---

## 3. Files Changed

- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt` (modified)
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt` (modified)
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt` (modified)
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-02-shared-api.md` (created)

---

## 4. Next Steps

- Proceed to **Task 3: Bootstrap Android Sentry Behind A Testable Boundary**.
