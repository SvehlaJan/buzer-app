# Handoff: Lean Observability Task 1 — Shared Client

Date: 2026-08-22

Plan: `docs/superpowers/plans/2026-08-22-lean-observability.md`

Task: Task 1 — Reduce the shared client diagnostics contract

Status: DONE

## Checkbox status

- [x] Rewrite the shared API diagnostics test around request IDs and absent trace headers.
- [x] Capture the expected RED result.
- [x] Remove diagnostic report, network-history, and trace types and client plumbing.
- [x] Run focused and full shared host tests.
- [x] Self-review and commit the scoped implementation.

## Files changed

- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- `docs/handoffs/2026-08-22-lean-observability-task-01-shared.md`

`BuzerApiClientContractTest.kt` was added to the claimed scope after confirming that its
diagnostic-report imports and test were the remaining shared compilation users of the
deleted DTOs. The parent agent approved removing only those obsolete hunks; unrelated API
contract coverage was preserved.

`RedactionCorpusParityTest.kt` was inspected and left unchanged because it tests the
general credential redactor rather than the removed capsule payloads.

## Implementation summary

- `DiagnosticModels.kt` now contains only `DiagnosticCorrelation` and
  `DiagnosticLogRecord`; `traceId` and `reportSessionId` were removed from correlation.
- `BuzerApiClient` no longer defines or accepts `NetworkDiagnosticSink`, `TraceHeaders`,
  a trace provider, or a body redactor. It no longer serializes/captures HTTP bodies or
  exposes `createDiagnosticReport`.
- Normal request behavior remains: every request receives `X-Request-ID`, bearer auth and
  configured Ktor timeouts remain active, Ktor logging remains header-only with the
  authorization header sanitized, and response/error decoding is unchanged.
- The request ID provider remains as a deterministic request-correlation test seam; it is
  part of the surviving request-ID behavior rather than removed capsule diagnostics.

## Verification

- Command:
  `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientDiagnosticsTest*' --console=plain`
  (working directory: `client`, before production changes)
- Result: FAIL (expected RED)
- Evidence: `everyRequestCarriesRequestIdWithoutTracePropagation` failed because the old
  trace provider emitted `sentry-trace` instead of the required null header; 1 test run,
  1 failed.

- Command:
  `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*BuzerApiClientDiagnosticsTest*' --console=plain`
  (working directory: `client`, after production changes)
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; focused host test completed with the lean request contract.

- Command:
  `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --console=plain`
  (working directory: `client`)
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; all shared Android host tests passed.

- Command:
  `git diff --check -- client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/DiagnosticModels.kt client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientDiagnosticsTest.kt client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- Result: PASS
- Evidence: no whitespace errors.

- Command:
  `rg -n 'DiagnosticNetworkExchange|DiagnosticReportRequest|DiagnosticReportResponse|NetworkDiagnosticSink|TraceHeaders|createDiagnosticReport|sentry-trace|baggage' client/shared/src/commonMain client/shared/src/commonTest client/shared/src/androidHostTest`
- Result: PASS
- Evidence: only the intentional negative `sentry-trace` and `baggage` assertions remain.

## Manual verification

SKIPPED. This task changes no user-visible UI and is fully covered by the shared host
tests. Android application compilation belongs to the follow-on tasks that remove the
old Android call sites concurrently.

## Assumptions and known limitations

- The Android tasks will remove their call sites for the deleted shared types and
  constructor parameters before the complete client build gate runs.
- No new Ktor or Sentry API facts were needed; the parent session had already completed
  the required Context7 lookup.
- No failures remain in the assigned shared scope.

## Next recommended step

Complete Tasks 2 and 3, then run the combined shared/Android JVM build to verify all old
Android capsule call sites have been removed.
