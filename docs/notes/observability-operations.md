# Observability Operations & Architecture Specifications

**Date:** 2026-08-21
**Status:** Approved & Implemented
**Schema Version:** `1`

This document defines the operational parameters, data contracts, buffer specifications, budget ceilings, redaction guarantees, and external setup requirements for Buzer observability across Android client and backend VPS services.

---

## 1. Schema Version 1 Definitions

### 1.1 Android Diagnostic Capsule (`android-diagnostics-v1.json`)
The Android diagnostic capsule encapsulates device, runtime, schedule, and state context at report time. Serialized as structured JSON alongside raw attachments:

- **Identity & Metadata**:
  - `schemaVersion`: `1` (integer)
  - `reportSessionId`: UUID v4 string
  - `androidDiagnosticEventId`: 32-character hexadecimal Sentry event ID
  - `capturedAt`: ISO-8601 UTC timestamp
  - `appVersion`: Semantic version string (e.g. `0.1.0-dev`)
  - `versionCode`: Integer build number
  - `gitSha`: 12-character commit SHA or `local`
  - `environment`: Runtime environment tag (e.g. `development-device`, `production-device`)
  - `deviceId`: Stable UUID persisted in Android preferences
  - `processUptimeMs`: Milliseconds since application launch
- **Device & OS Context**:
  - `androidVersion`: Android OS release version (e.g. `14`, `15`)
  - `sdkInt`: Android API level (e.g. `34`, `35`)
  - `manufacturer`: Device manufacturer (e.g. `Google`)
  - `model`: Device model identifier (e.g. `Pixel 8`)
  - `locale`: Active device locale tag (e.g. `en-US`)
  - `timezone`: IANA timezone ID (e.g. `Europe/Prague`)
  - `display`: Screen resolution, density (dpi), and font scale
  - `battery`: Level percentage, charging state, and battery saver status
  - `network`: Transport type (`WIFI`, `CELLULAR`, `OFFLINE`) and meter status
- **Application & Task State**:
  - `currentActivity`: Canonical class name of active activity
  - `foreground`: Boolean indicating application visibility
  - `activeBlocker`: Active blocker state (`none`, `start`, `deadline`, `morning_review`)
  - `cachedSnapshot`: Complete cached Buzer task snapshot (tasks, projects, sections, assignees)
  - `scheduleVersion`: Monotonic integer schedule version
  - `snapshotGeneration`: Authority generation counter
  - `lastSyncTimestamp`: ISO-8601 instant of last successful sync
  - `lastSyncResult`: Outcome code (`success`, `auth_error`, `network_error`, `rate_limited`)
  - `pendingActions`: Array of queued offline action mutations
- **Alarm & Readiness State**:
  - `scheduledAlarms`: Array of registered alarms (trigger time, intent action, task ID, occurrence key)
  - `readinessStatus`: Readiness checklist evaluation (exact alarm permission, overlay permission, notification permission, battery optimization ignore status)
  - `morningReviewState`: Morning review schedule calculator status and local review state
- **Attachments Manifest**:
  - `android-diagnostics-v1.json`: Structured state document
  - `android-app-log.txt`: Text dump of Kermit/AppLog ring buffer
  - `android-network-history.json`: Serialized array of recent HTTP network exchanges
  - `android-threads.txt`: Thread dump of all active threads in process
  - `android-screenshot.webp` (or `.png`): Visual capture of the current window (when capturable)

### 1.2 Backend Diagnostic Capsule (`backend-diagnostics-v1.json`)
The backend diagnostic capsule captures server-side synchronization, database integrity, WhatsApp bridge, and log states:

- **Identity & Metadata**:
  - `schemaVersion`: `1` (integer)
  - `reportSessionId`: UUID v4 string from client
  - `backendDiagnosticEventId`: 32-character hexadecimal Sentry event ID
  - `androidDiagnosticEventId`: Referent Android Sentry event ID
  - `capturedAt`: ISO-8601 UTC timestamp
  - `backendRelease`: Canonical release string (e.g. `buzer-backend@0.1.0+vps`)
  - `nodeVersion`: Node.js runtime version
  - `environment`: Runtime environment tag (e.g. `development-vps`, `production-vps`)
  - `uptimeSeconds`: Process uptime in seconds
  - `memoryUsage`: V8 heap total, heap used, external memory, and RSS bytes
- **Database & Storage Context**:
  - `sqliteSchemaVersion`: Database user_version PRAGMA
  - `walStatus`: SQLite WAL checkpoint and page count
  - `tableCounts`: Row counts for `tasks`, `commitments`, `action_journal`, `occurrences`, `diagnostic_reports`
- **Todoist Sync Context**:
  - `pollerState`: Active status (`idle`, `polling`, `backoff`, `stopped`)
  - `lastPollStart`: ISO-8601 timestamp of last poll attempt
  - `lastPollSuccess`: ISO-8601 timestamp of last successful Todoist sync
  - `lastPollError`: Error classification of last failed poll (if any)
  - `consecutiveFailureCount`: Integer count of consecutive polling errors
  - `currentSnapshotGeneration`: Server authority generation ID
- **WhatsApp Escalation Context**:
  - `providerMode`: Active mode (`dry_run`, `live`)
  - `clientStatus`: Connection status (`ready`, `authenticated`, `disconnected`, `qr_pending`)
  - `lastDisconnect`: ISO-8601 timestamp of last disconnection
  - `recentEscalations`: Bounded array of recent escalation attempt records
- **Attachments Manifest**:
  - `backend-diagnostics-v1.json`: Structured backend state document
  - `backend-log.txt`: Text dump of backend ring buffer logs around report time

### 1.3 HTTP Contract: `POST /diagnostics/reports`
- **Request Headers**:
  - `Authorization: Bearer <ANDROID_API_TOKEN>`
  - `Content-Type: application/json`
  - `X-Request-ID: <UUID>`
  - `traceparent` / `sentry-trace`: (Optional) W3C/Sentry distributed trace header
  - `baggage`: (Optional) Distributed baggage header
- **Request Body Schema**:
  ```json
  {
    "schemaVersion": 1,
    "reportSessionId": "550e8400-e29b-41d4-a716-446655440000",
    "androidDiagnosticEventId": "9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d",
    "capturedAt": "2026-08-21T18:30:00.000Z",
    "deviceId": "c39a3f2b-8664-4e4b-9f93-b6d1e4c92178",
    "taskId": "optional-task-id",
    "occurrenceKey": "optional-occurrence-key",
    "actionId": "optional-action-id",
    "requestIds": ["req-12345", "req-12346"],
    "traceId": "optional-trace-id"
  }
  ```
- **Response Status & Body**:
  - `202 Accepted` (captured):
    ```json
    {
      "schemaVersion": 1,
      "reportSessionId": "550e8400-e29b-41d4-a716-446655440000",
      "backendDiagnosticEventId": "e4d909c290d042c69d20d40d564b66f3",
      "outcome": "captured"
    }
    ```
  - `401 Unauthorized`: Invalid or missing bearer token.
  - `400 Bad Request`: Validation failure on request body.
  - `429 Too Many Requests`: Rate limit exceeded (> 6 reports/min per device).
  - `503 Service Unavailable`: Observability uninitialized or backend collector failure.

---

## 2. Ring Buffer Specifications

| Buffer Target | Capacity (Count) | Time Window | Content Specification | Eviction Policy |
| :--- | :--- | :--- | :--- | :--- |
| **Android AppLog** | 1,000 log entries | 15 minutes | Timestamp, log level, tag (`Buzer.*`), thread ID, message, throwable stack trace | FIFO sliding window on count and timestamp |
| **Android Network History** | 100 API exchanges | 15 minutes | Timestamp, method, URL path, HTTP status, durationMs, request ID, trace ID, action ID, content types, redacted bodies | FIFO sliding window on count and timestamp |
| **Backend Structured Log** | 1,000 log entries | 15 minutes | Timestamp, level, component (`poller`, `routes`, `whatsapp`, `db`), message, metadata object | FIFO sliding window on count and timestamp |

- **Sliding Window Enforcement**: Entries older than 15 minutes are evicted even if the count ceiling is not reached.
- **Thread Safety**: All ring buffers are backed by thread-safe, non-blocking synchronized data structures.
- **Memory Footprint**: Buffer operations allocate bounded memory; entries are pre-sanitized before insertion.

---

## 3. Budget Limits & Resource Ceilings

To avoid network congestion, payload rejection, and memory bloat, strict size ceilings are enforced:

| Resource | Maximum Ceiling | Truncation / Fallback Behavior |
| :--- | :--- | :--- |
| **Total Diagnostic Capsule** | 8.0 MiB (compressed) | Deterministic section truncation per priority rules |
| **Screenshot Attachment** | 2.0 MiB | Scaled/compressed to WebP (quality 80) or omitted |
| **Network Request/Response Body** | 64 KiB (per exchange) | Redacted and truncated with `[TRUNCATED: original N bytes]` marker |
| **Process Thread Dump** | 512 KiB | Bounded to active application thread states |
| **Log Buffer Attachment** | 2.0 MiB | Oldest entries dropped first to meet ceiling |

### Deterministic Truncation Priority:
When a diagnostic capsule exceeds the 8.0 MiB ceiling, sections are preserved in strict order of diagnostic value:
1. **Report & Correlation Identity** (Session ID, Sentry Event IDs, Git SHA, Release, Environment)
2. **Current State Snapshots** (Task snapshot, pending actions, alarm table, readiness state)
3. **Error & Warning Logs** (`WARN` and `ERROR` log lines)
4. **Failed & Slow Network Exchanges** (Non-2xx HTTP responses, timed-out requests)
5. **Screenshot Attachment**
6. **Info & Debug Log History**

Every truncation event is explicitly recorded in `capsule.metadata.truncationWarnings`.

---

## 4. Rate Limits & Concurrency

- **Backend Ingestion Rate Limit**:
  - 6 report submissions per 1 minute per device/IP address.
  - Enforced via Fastify rate-limiter with memory storage.
  - Exceeding the rate returns `429 Too Many Requests` with `Retry-After` header.
- **Android Client Session Concurrency**:
  - Exactly **1 in-flight manual report session** allowed at any given time.
  - `ManualReportCoordinator` enforces a mutex state machine (`idle` -> `collecting` -> `submitting` -> `completed` / `failed`).
  - Stale sessions (> 5 minutes inactive) are automatically invalidated and cleaned up.
- **Execution Timeouts & Deadlines**:
  - Android diagnostic collection deadline: **2.0 seconds** (proceeds with partial capsule on timeout).
  - Android client HTTP backend request timeout: **5.0 seconds** (never blocks feedback completion).
  - Backend diagnostic assembly deadline: **2.0 seconds**.
  - Backend total endpoint response deadline: **3.0 seconds**.

---

## 5. PII and Secret Redaction Guarantees

### 5.1 Permitted Diagnostic Content (One-User Companion)
- Task titles, task descriptions, notes, and checklist items.
- Todoist project names, section names, and assignee display names / IDs.
- Cached schedule state, morning review parameters, and notification timings.
- Screen names, UI component interaction breadcrumbs, and window screenshots.

### 5.2 Strictly Prohibited Secrets & Sensitive Credentials
The following values are **MANDATORY EXCLUSIONS** and must NEVER appear in logs, breadcrumbs, capsules, attachments, Sentry events, or GitHub issues:
- `ANDROID_API_TOKEN` / Bearer authorization headers
- `TODOIST_API_TOKEN` / Todoist personal access tokens
- `SENTRY_AUTH_TOKEN` / Sentry user/org API tokens
- WhatsApp credentials: QR code strings, LocalAuth session keys, auth tokens, Chromium profile lock files
- HTTP sensitive headers: `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-Api-Key`
- Environment dumps: Unfiltered `process.env` or Android `System.getenv()`
- Private keys, PEM blocks, or certificate secrets

### 5.3 Redaction Engine Guarantees
- **Multi-Stage Sanitization**: Redaction occurs (1) before insertion into ring buffers and (2) during JSON serialization prior to transmission.
- **Header Masking**: Sensitive headers are replaced with `[REDACTED]`.
- **Bearer Token Patterns**: Regex matches for `Bearer [A-Za-z0-9_\-\.]{8,}` are replaced with `Bearer [REDACTED]`.
- **Key-Value Sentinel Matching**: Keys matching `token`, `secret`, `password`, `auth`, `cookie`, `key`, `credential` (case-insensitive) have values replaced with `[REDACTED]`.
- **Test Corpus Verification**: Regression test suites (`redaction.test.ts`, `SentryEventSanitizerTest.kt`) verify zero leaks against synthetic secret tokens.

---

## 6. Retention Policies

| Store | Data Type | Retention Duration | Cleanup Mechanism |
| :--- | :--- | :--- | :--- |
| **Sentry Cloud** | Crash/ANR events, handled exceptions, logs | 30 days | Sentry Cloud automatic retention policy |
| **Sentry Cloud** | Session replays & attachments | 30 days | Sentry Cloud retention policy |
| **Android In-Memory** | App logs & network history | 15 minutes / Process lifetime | Ring buffer FIFO eviction; cleared on app kill |
| **Backend In-Memory** | Server logs & request history | 15 minutes / Process lifetime | Ring buffer FIFO eviction; cleared on server restart |
| **Backend SQLite** | `diagnostic_reports` idempotency table | 7 days | Periodic vacuum / cleanup query |

---

## 7. Hosted Sentry Inventory & Configuration

| Resource | Slug | Project ID | Platform |
| :--- | :--- | ---: | :--- |
| **Organization** | `svehla` (or `jans-gi`) | — | Hosted Sentry Cloud |
| **Android Project** | `buzer-android` | `4511950606172240` | Android (Kotlin/Java) |
| **Backend Project** | `buzer-backend` | `4511950617641040` | Node.js / Fastify |

### Canonical Runtime Environments
- `development-device`: Android local debug / emulator / dev builds
- `production-device`: Android signed release APK installed on physical device
- `development-backend`: Backend running locally during development
- `production-vps`: Backend running on production VPS (`docker-compose`)

### Canonical Release Identity
```text
app.buzer@${VERSION_NAME}+${GIT_SHA_12}
```
Example: `app.buzer@0.1.0-dev+a1b2c3d4e5f6`

---

## 8. External Setup Checklist

- [x] Sentry organization and projects created (`buzer-android`, `buzer-backend`).
- [x] Android release mapping & ProGuard upload configured in `build.gradle.kts`.
- [x] Backend Sentry SDK integration and Fastify error hooks implemented.
- [x] Cross-process correlation contract implemented (`report_session_id`).
- [x] Automated Sentry-to-GitHub triage tooling implemented (`tools/sentry-github-sync`).
- [ ] Store `SENTRY_ANDROID_DSN` in local operator properties / CI secrets.
- [ ] Store `SENTRY_BACKEND_DSN` in VPS `.env` file.
- [ ] Configure `SENTRY_AUTH_TOKEN` in CI/build environments for symbol upload.
- [ ] Link GitHub repository in Sentry project settings.
- [ ] Configure Sentry alert rules for Fatal, ANR, Polling Failures, and User Feedback.
