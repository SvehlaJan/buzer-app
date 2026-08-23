# Handoff: Observability Task 8 - Implement The Read-Only Backend Diagnostic Endpoint

> [!WARNING]
> **SUPERSEDED (2026-08-22):** The endpoint, trace/capsule, and GitHub-sync material below is historical evidence only. Use the lean plan (`docs/superpowers/plans/2026-08-22-lean-observability.md`) and current runbooks (`docs/runbooks/`) for active operations.

**Date:** 2026-08-21  
**Plan:** `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`  
**Task:** Task 8: Implement The Read-Only Backend Diagnostic Endpoint  
**Status:** DONE  

---

## 1. Summary of Changes

Implemented the read-only backend diagnostic endpoint and collector pipeline (`POST /diagnostics/reports`), supporting manual bug reporting with full runtime, database, Todoist, WhatsApp, and log telemetry:

1. **Backend Diagnostic Collector** (`backend/src/diagnostics/backendDiagnosticCollector.ts`):
   - Generates schema version 1 diagnostic capsule (`backend-diagnostics-v1.json`).
   - Gathers read-only metadata:
     - `runtime`: Node version, platform, architecture, uptime, pid, memory/heap usage, environment, release version, server timestamp.
     - `database`: Schema migration version, PRAGMA journal mode, PRAGMA quick check, table row counts (`tasks`, `projects`, `collaborators`, `devices`, `actionJournal`, `whatsappEscalations`), up to 20 recent action journal entries (redacted), matched task by `taskId`, matched action by `actionId`, and device details by `deviceId`.
     - `todoist`: Sync token presence/length (never leaking the raw token), mirror contract version, last sync timestamp.
     - `whatsapp`: Configuration status, dry-run mode, readiness status, recent escalation history (never leaking credentials, recipient ID, or QR strings).
     - `sectionStatuses`: Map of section statuses (`complete`, `partial`, `unavailable`, `failed`) and failure reason codes.
     - `truncations`: List of truncation records when budget bounds are hit.
   - Text & JSON artifacts:
     - `backend-app-log.txt`: In-memory log lines from `backendLogRingBuffer`.
     - `backend-polling-history.json`: Diagnostic/network exchanges from `backendDiagnosticRingBuffer`.
   - Priority truncation within 8 MiB (8,388,608 bytes) total budget:
     1. Report identity & capsule metadata (always kept in `backend-diagnostics-v1.json`).
     2. Subsystem state in JSON capsule.
     3. Error/warn logs (kept, info/debug dropped first if logs exceed budget).
     4. Failed/slow polling history.
     5. Info/debug history.

2. **Diagnostic Report Service** (`backend/src/diagnostics/diagnosticReportService.ts`):
   - Defined strict Zod request schema `diagnosticReportRequestSchema`:
     - `schemaVersion`: 1 (default 1)
     - `reportSessionId`: string (1-200 chars)
     - `androidDiagnosticEventId`: string (1-200 chars)
     - `deviceId`: string (1-200 chars)
     - `capturedAt`: string (1-100 chars)
     - Optional correlation IDs: `taskId`, `occurrenceKey`, `actionId`, `requestIds` (max 20), `traceId`.
   - `DiagnosticReportService`:
     - Generates 32-hex `backendDiagnosticEventId`.
     - Collects diagnostic capsule and attachments via `collectDiagnosticsCapsule`.
     - Emits Sentry event `buzer.backend.manual_report` via `BackendObservability.captureDiagnosticEvent` with attachments and correlation tags.
     - Returns `DiagnosticReportResponse` (`{ schemaVersion: 1, reportSessionId, backendDiagnosticEventId, outcome: "captured" }`).

3. **HTTP Route Registration** (`backend/src/http/routes.ts`, `backend/src/server.ts`):
   - Extended `RouteServices` with `diagnostics?: DiagnosticReportService`.
   - Registered `POST /diagnostics/reports`:
     - Authenticated via existing bearer token check in `preHandler`.
     - Rate-limited to 6 requests per minute per device (or IP) with `hook: "preValidation"`.
     - Parses request body via `diagnosticReportRequestSchema`.
     - Handles report and returns HTTP 200 with `DiagnosticReportResponse`.

4. **Comprehensive Test Suites**:
   - `backend/test/backendDiagnosticCollector.test.ts`:
     - Verified schema version 1 capsule generation.
     - Verified read-only database queries, pragma checks, row counts, and status mappings.
     - Verified sensitive field redactions across task titles, actions, and credentials.
     - Verified error resiliency when DB is closed (marks section status as failed without throwing).
     - Verified 8 MiB budget priority truncation and truncation record logging.
   - `backend/test/diagnosticReportService.test.ts`:
     - Verified event capture, tag/correlation mapping, 3 attachments emission, and outcome `"captured"`.
     - Verified strict schema validation.
   - `backend/test/diagnosticEndpoint.test.ts`:
     - Verified bearer token requirement (401 on missing/invalid token).
     - Verified request body validation (400 on invalid body).
     - Verified 200 response with `DiagnosticReportResponse`.
     - Verified per-device rate limiting (6/min allowed, 7th blocked with 429, separate device not blocked).

---

## 2. Verification and Test Evidence

- **Unit & Endpoint Tests**:
  - `npx vitest run test/backendDiagnosticCollector.test.ts test/diagnosticReportService.test.ts test/diagnosticEndpoint.test.ts`: 3 test files passed, 10 tests passed.
- **Full Backend Test Suite**:
  - `npm test`: 37 test files passed, 434 tests passed (0 failures).
- **TypeScript Typecheck**:
  - `npm run typecheck`: 0 errors.
- **Backend Build**:
  - `npm run build`: built successfully to `backend/dist`.

---

## 3. Files Created / Modified

- `backend/src/diagnostics/backendDiagnosticCollector.ts` (created)
- `backend/src/diagnostics/diagnosticReportService.ts` (created)
- `backend/src/http/routes.ts` (modified)
- `backend/src/server.ts` (modified)
- `backend/test/backendDiagnosticCollector.test.ts` (created)
- `backend/test/diagnosticReportService.test.ts` (created)
- `backend/test/diagnosticEndpoint.test.ts` (created)
- `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md` (modified)
- `docs/handoffs/2026-08-21-observability-task-08-backend-endpoint.md` (created)

---

## 4. Next Steps

- Proceed to **Task 9: Instrument Backend Polling, WhatsApp, And Escalation Signals**.
