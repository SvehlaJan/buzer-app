# Handoff: Observability Task 9 - Instrument Backend Polling, WhatsApp, And Escalation Signals

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 9: Instrument Backend Polling, WhatsApp, And Escalation Signals  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented operational observability, non-fatal handled failure captures, in-memory ring buffer diagnostics, and secret redaction across background polling, WhatsApp Web integration, and WhatsApp task escalations:

1. **Todoist Poller & Sync Instrumentation** (`backend/src/services/todoistPoller.ts`, `backend/src/services/todoistSyncService.ts`):
   - `TodoistSyncService`:
     - Tracks sync telemetry (`lastSyncAt`, `lastOutcome`, `lastDurationMs`, `lastErrorMessage`).
     - Exposes `getLastSyncDiagnostics()` for reporting and diagnostics.
   - `TodoistPoller`:
     - Accepts optional `observability?: BackendObservability`, `diagnosticRingBuffer?: RingBuffer<unknown>`, and `logRingBuffer?: RingBuffer<string>`.
     - Tracks `consecutiveFailures`, `lastOutcome`, `lastDurationMs`, and `lastSyncAt`.
     - In `tick()`:
       - Measures execution time and records polling events to `diagnosticRingBuffer` (`type: "todoist_poll"`, `outcome`, `durationMs`, `consecutiveFailures`).
       - On success: resets `consecutiveFailures = 0`, logs INFO line to `backendLogRingBuffer`.
       - On error: increments `consecutiveFailures`, logs WARN line to `backendLogRingBuffer`.
       - When `consecutiveFailures >= 3`: captures handled non-fatal `buzer.backend.todoist_poll_failure` via `BackendObservability.captureHandled` with `consecutive_failure_count` tag. Transient errors (<3) log warnings without generating Sentry events.

2. **WhatsApp Web Sender Instrumentation** (`backend/src/accountability/whatsappWebSender.ts`):
   - Accepts optional `observability?: BackendObservability`, `logRingBuffer?: RingBuffer<string>`, and `client?: InstanceType<typeof Client>`.
   - On `qr`: renders terminal QR for operator console but never logs raw QR strings or tokens to ring buffers or observability; records `WhatsApp authentication QR required` to `backendLogRingBuffer`.
   - On `authenticated`, `ready`: records INFO state transitions to `backendLogRingBuffer`.
   - On `auth_failure`: records redacted ERROR to `backendLogRingBuffer` and captures handled non-fatal `buzer.backend.whatsapp_auth_failure`.
   - On `disconnected`: records redacted WARN to `backendLogRingBuffer` and captures handled non-fatal `buzer.backend.whatsapp_disconnected`.

3. **WhatsApp Escalation Service Instrumentation** (`backend/src/services/escalationService.ts`):
   - Accepts optional `observability?: BackendObservability`, `diagnosticRingBuffer?: RingBuffer<unknown>`, and `logRingBuffer?: RingBuffer<string>`.
   - In `sendForTask(taskId, dismissCount)`:
     - Records attempts and outcomes to `diagnosticRingBuffer` (`type: "whatsapp_escalation"`, `taskId`, `occurrenceKey`, `escalationId`, `status: "sent" | "failed" | "already_claimed"`, `dismissCount`).
     - On delivery failure: captures handled non-fatal `buzer.backend.escalation_delivery_failure` with correlated tags (`task_id`, `occurrence_key`, `recipient_configured = "true"`, `escalation_id`).
     - Strictly avoids attaching recipient ID, raw phone numbers, or WhatsApp session data to diagnostic logs or events.

4. **Server Wiring & Generic Correlation** (`backend/src/server.ts`, `backend/src/diagnostics/backendObservability.ts`, `backend/src/diagnostics/backendDiagnosticCollector.ts`):
   - Passed `backendObservability` into `WhatsAppWebSender`, `EscalationService`, and `TodoistPoller`.
   - Enhanced `BackendObservability` scope correlation mapping to dynamically set tags from correlation objects.
   - Enhanced `backendDiagnosticCollector` to include `getLastSyncDiagnostics()` in the `todoist` section of the diagnostic capsule.

5. **Comprehensive Test Suites**:
   - `backend/test/services/todoistPollerDiagnostics.test.ts`:
     - Verified recording of every poll cycle to ring buffer.
     - Verified transient failures (<3) log warning and skip Sentry capture.
     - Verified 3+ consecutive failures capture handled non-fatal `buzer.backend.todoist_poll_failure`.
     - Verified consecutive failure counter resets to 0 on subsequent success.
   - `backend/test/services/escalationServiceDiagnostics.test.ts`:
     - Verified escalation attempt recording to ring buffer.
     - Verified delivery failure captures handled non-fatal `buzer.backend.escalation_delivery_failure` with tags.
     - Verified successful delivery records outcome `sent`.
     - Verified already claimed outcome records `already_claimed`.
     - Verified recipient ID is not leaked in tags or log lines.
   - `backend/test/accountability/whatsappDiagnostics.test.ts`:
     - Verified QR emission logs sanitized message without raw QR code.
     - Verified auth failure and disconnect capture handled non-fatal errors.
     - Verified state transition logging.

---

## 2. Verification and Test Evidence

- **Focused Service & Signal Diagnostics Suites**:
  - `backend/test/services/todoistPoller.test.ts`
  - `backend/test/services/todoistPollerDiagnostics.test.ts`
  - `backend/test/services/escalationService.test.ts`
  - `backend/test/services/escalationServiceDiagnostics.test.ts`
  - `backend/test/accountability/whatsappWebSender.test.ts`
  - `backend/test/accountability/whatsappDiagnostics.test.ts`
  - **Result**: 6 test files passed, 15 tests passed.

- **Full Backend Test Suite**:
  - `npm test` in `backend/`: 41 test files passed, 450 tests passed (0 failures).

- **TypeScript Typecheck**:
  - `npm run typecheck` in `backend/`: 0 errors.

- **Backend Build**:
  - `npm run build` in `backend/`: built successfully to `backend/dist`.

---

## 3. Files Created / Modified

- `backend/src/services/todoistPoller.ts` (modified)
- `backend/src/services/todoistSyncService.ts` (modified)
- `backend/src/services/escalationService.ts` (modified)
- `backend/src/accountability/whatsappWebSender.ts` (modified)
- `backend/src/server.ts` (modified)
- `backend/src/diagnostics/backendObservability.ts` (modified)
- `backend/src/diagnostics/backendDiagnosticCollector.ts` (modified)
- `backend/test/services/todoistPollerDiagnostics.test.ts` (created)
- `backend/test/services/escalationServiceDiagnostics.test.ts` (created)
- `backend/test/accountability/whatsappDiagnostics.test.ts` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-09-backend-background.md` (created)
- `docs/handoffs/2026-08-21-observability-task-09-backend-signals.md` (created)

---

## 4. Next Steps

- Proceed to **Task 10: Verify The Cross-Process Report Contract End To End**.
