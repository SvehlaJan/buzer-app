# Lean Observability Operations & Architecture Specifications

**Date:** 2026-08-24
**Status:** Approved and implemented. Hosted projects plus native Android
feedback/replay/structured logs have been verified; the backend production canary
remains operator-owned and incomplete.
**Supersedes:** 2026-08-21 Paired-Capsule Observability Architecture

This document defines the operational architecture, runtime SDK configurations, correlation tag schemas, canonical release/environment identities, secret redaction policies, and retention rules for Buzer observability across the Android client and the backend VPS service.

---

## 1. System Architecture Overview

Buzer uses a **native-first, lean observability architecture** powered directly by hosted Sentry SaaS (`sentry.io`). There is no intermediate diagnostic capsule endpoint, no paired report state machine, no diagnostic SQLite table, and no automated triage bot.

```text
┌────────────────────────────────────────────────────────┐
│                      Android Client                    │
│                                                        │
│  AppLog ──► Kermit / Logcat (local output)             │
│       └───► Breadcrumbs + Sentry Logs (sanitized)      │
│                                                        │
│  Sentry Android SDK:                                   │
│    • Unhandled Crashes & ANRs                          │
│    • Error Replay (100% on error, 0% normal sessions)  │
│    • Native Screenshots & View Hierarchy               │
│    • Native Feedback Form (Shake gesture + UI buttons) │
│    • Explicit Handled Exceptions (tagged)              │
│    • Offline Envelope Caching                          │
└──────────────────────────┬─────────────────────────────┘
                           │ Direct Sentry Ingest
                           ▼
                 ┌───────────────────┐
                 │   Hosted Sentry   │
                 │     (sentry.io)   │
                 │   Org: jans-gi    │
                 └─────────▲─────────┘
                           │ Direct Sentry Ingest
┌──────────────────────────┴─────────────────────────────┐
│                       Backend VPS                      │
│                                                        │
│  Pino / Fastify ──► stdout structured logging          │
│                                                        │
│  Sentry Node SDK:                                      │
│    • Explicit 5xx Route Failures (Fastify onError hook)│
│    • Background Service Failures (Poller, WhatsApp)    │
│    • Process Failures (uncaught / unhandled)           │
│    • Sanitized Breadcrumbs & Correlation Tags          │
└────────────────────────────────────────────────────────┘
```

### Key Principles
1. **Best-Effort Telemetry**: Observability must never block or degrade alarms, blocker overlays, boot recovery, Todoist sync, WhatsApp messaging, or UI rendering. Blank DSNs or network failures degrade cleanly to no-ops.
2. **Sentry Native Primitives**: Sentry owns crash capture, ANR detection, replay recording, screenshot/view-hierarchy capture, offline delivery, user feedback transport, and structured log transport. Buzer provides only product-specific breadcrumbs/logs, release metadata, and correlation tags.
3. **Zero Custom Storage**: No diagnostic ring buffers, no report files, and no database tables exist. Local execution retains only ephemeral stdout/Logcat logs.
4. **Manual Triage**: Sentry UI is the primary triage inbox. Issues are filtered by release, environment, and tags. Operators file GitHub issues manually for prioritized work.

---

## 2. Runtime SDK Configuration

### 2.1 Android SDK Configuration (`client/androidApp/.../SentryAndroidObservability.kt`)

Initialized in `BuzerApplication.onCreate()` before any Activity, Service, or Receiver runs:

```kotlin
options.isSendDefaultPii = false
options.tracesSampleRate = null            // No performance tracing overhead
options.sessionReplay.onErrorSampleRate = 1.0 // 100% error replay
options.sessionReplay.sessionSampleRate = 0.0 // No continuous session replay
options.isAnrEnabled = true                // Detect Application Not Responding
options.isAttachScreenshot = true          // Native window capture on error/feedback
options.isAttachViewHierarchy = true       // Compose/Android view hierarchy capture
options.logs.isEnabled = true              // Searchable structured Sentry Logs
```

- **User Feedback**: Native Sentry feedback is configured with `showName = false`, `showEmail = false`, `useSentryUser = false`, and `showBranding = false`.
  - Accessible via **physical shake gesture**.
  - Accessible via **Home screen bug report action**.
  - Accessible via **Foreground Readiness gate secondary action**.
  - Emits `bug_report_form_opened` and `bug_report_submitted` through `AppLog`.
- **Privacy Boundary**: `beforeSend` nullifies `event.request = null` and `event.user = null`; `beforeSend`, `beforeBreadcrumb`, and `logs.beforeSend` apply recursive credential redaction before transport.
- **Logcat / Kermit**: `AppLog.installSink` forwards each record to both Sentry breadcrumbs and structured Sentry Logs. The same sink remains a no-op when the DSN is blank.
- **Feedback/log lookup**: Native feedback is not linked to logs through a custom event ID. Use the feedback timestamp, release, and environment to inspect nearby records in Sentry's **Logs** view. The 2026-08-24 canary also showed the feedback and surrounding logs sharing an SDK-provided trace ID, which can be used as an additional pivot when present.

### 2.2 Backend Node SDK Configuration (`backend/src/diagnostics/backendObservability.ts`)

Initialized before Fastify server boot:

```typescript
Sentry.init({
  dsn: SENTRY_BACKEND_DSN,
  environment: SENTRY_ENVIRONMENT,
  release: SENTRY_RELEASE,
  sendDefaultPii: false,
  tracesSampleRate: undefined,              // Tracing disabled (Fastify 5 unsupported)
  beforeSend(event) {
    event.request = undefined;              // Remove request headers/body/IP
    event.user = undefined;                 // Remove user identifiers
    return sanitizeSentryEvent(event);      // Recursive secret redaction
  },
  beforeBreadcrumb(breadcrumb) {
    return sanitizeBreadcrumb(breadcrumb);  // Recursive secret redaction
  }
});
```

- **Request Failures**: Fastify `onError` hook captures route errors producing `5xx` responses, attaching `request_id`, HTTP `method`, `route`, and `status_code`. `4xx` client errors are ignored.
- **Background Failures**: Poller sync failures (after threshold), WhatsApp disconnection/escalation failures, and startup/shutdown errors are captured explicitly with component tags.
- **Process Integration**: Sentry SDK handles unhandled rejections and uncaught exceptions natively. Fastify and Node process signal handlers (`SIGTERM`, `SIGINT`) handle graceful shutdown and Sentry flush.

---

## 3. Standard Correlation Tag Schema

Events on both Android and backend carry standard, low-cardinality correlation tags:

| Tag | Runtime | Description | Example |
| :--- | :--- | :--- | :--- |
| `marker` | Backend background failures | Category or operation marker | `buzer.backend.todoist_poll_failure`, `buzer.backend.whatsapp_disconnected` |
| `request_id` | Both | Unique request identifier (`X-Request-ID`) | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` |
| `method` | Backend | HTTP method for failed request | `GET`, `POST`, `PATCH` |
| `route` | Backend | Fastify route pattern (not raw URL) | `/api/tasks/:id` |
| `status_code` | Backend | HTTP status code for 5xx errors | `500`, `503` |
| `device_id` | Android | Stable anonymized device UUID | `c39a3f2b-8664-4e4b-9f93-b6d1e4c92178` |
| `task_id` | Both | Todoist / Buzer task identifier | `task-123456` |
| `occurrence_key` | Both | Recurring task occurrence key | `2026-08-22#task-123456` |
| `action_id` | Both | Client offline action mutation ID | `act-789` |
| `screen` | Android | Active screen identifier | `Home`, `TaskEditor`, `ReadinessGate` |
| `blocker_type` | Android | Active enforcement blocker type | `start`, `deadline`, `morning_review`, `none` |
| `app_section` | Android | UI application section | `task_list`, `editor`, `settings` |

> **Note on Trace Propagation:** Distributed trace headers (`sentry-trace`, `baggage`) are **not** propagated across HTTP boundaries in this iteration. Client and server events are correlated via `request_id` (`X-Request-ID`), `task_id`, `action_id`, `occurrence_key`, and timestamps.

---

## 4. Release & Environment Identification

### 4.1 Canonical Release Format and Source
Both runtimes use the canonical release shape:

```text
app.buzer@<VERSION_NAME>+<GIT_SHA_12>
```

- Android derives `app.buzer@<versionName>+<git-sha-12>` at Gradle configuration time. It takes the first 12 characters of `GITHUB_SHA`, otherwise runs `git rev-parse --short=12 HEAD`, otherwise uses `local`. It does not read `SENTRY_RELEASE` from Gradle properties, `local.properties`, or the environment.
- The VPS deploy script derives `app.buzer@0.1.0+<git-sha-12>` from the checked-out commit immediately before Docker Compose starts. Leave `SENTRY_RELEASE` out of the VPS `.env`; an explicitly exported deploy-shell `SENTRY_RELEASE` overrides the script default and is for exceptional operator use only.
- Local backend development falls back to `app.buzer@0.1.0+local` when no release is supplied.

Current backend background markers are `buzer.backend.todoist_poll_failure`, `buzer.backend.whatsapp_auth_failure`, `buzer.backend.whatsapp_disconnected`, and `buzer.backend.escalation_delivery_failure`. The poller emits its marker only after three consecutive failures.

### 4.2 Canonical Runtime Environments
- `development-device`: Android local debug / emulator builds
- `production-device`: Android signed release APK installed on physical device
- `development-vps`: Backend running locally during development
- `production-vps`: Backend running on the production VPS

---

## 5. PII and Secret Redaction Guarantees

### 5.1 Permitted Diagnostic Content (Personal Accountability Companion)
For this single-user personal companion, capturing task-visible content is explicitly permitted to maximize debugging fidelity:
- Task titles, task descriptions, notes, and checklist items.
- Todoist project names, section names, and label names.
- Cached schedule state, morning review parameters, and notification timings.
- Screen names, UI component interaction breadcrumbs, and window screenshots.

### 5.2 Strictly Prohibited Secrets & Credentials
The following values are **MANDATORY EXCLUSIONS** and must NEVER appear in logs, breadcrumbs, Sentry events, or any external system:
- `ANDROID_API_TOKEN` / Bearer authorization tokens
- `TODOIST_API_TOKEN` / Todoist personal access tokens
- `SENTRY_AUTH_TOKEN` / Sentry user/org API tokens
- WhatsApp session keys, QR code strings, auth tokens, and Chromium profile files
- HTTP sensitive headers: `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-Api-Key`
- Raw environment dumps (`process.env`, `System.getenv()`)
- Private keys, PEM blocks, certificates, or GitHub tokens

### 5.3 Multi-Stage Sanitization Engine
- **Kotlin Shared Redactor (`DiagnosticRedactor.kt`)**: Redacts nested maps, lists, strings, and throwable messages. Used by `SentryAndroidObservability` for event, breadcrumb, and structured-log sanitization.
- **TypeScript Backend Redactor (`redactor.ts`)**: Recursive sanitization of strings, objects, error stacks, breadcrumbs, tags, and extra metadata.
- **Key-Value Sentinel Redaction**: Keys matching `token`, `secret`, `password`, `auth`, `cookie`, `key`, `credential` (case-insensitive) have values replaced with `[REDACTED]`.
- **Pattern Matching**: Regex matches for `Bearer [A-Za-z0-9_\-\.]{8,}` are replaced with `Bearer [REDACTED]`.
- **Nullified Request & User**: SDK-level `request` and `user` objects are always cleared to prevent inadvertent IP or header leaks.

---

## 6. Retention Policies

| Store | Data Type | Retention Duration | Cleanup Mechanism |
| :--- | :--- | :--- | :--- |
| **Sentry Cloud** | Crash/ANR events, handled exceptions, feedback | 30 days | Sentry Cloud automatic retention policy |
| **Sentry Cloud** | Android structured logs | Hosted-plan policy | Sentry Cloud automatic retention policy |
| **Sentry Cloud** | Error replays & native screenshots | 30 days | Sentry Cloud automatic retention policy |
| **Android In-Memory** | AppLog entries in Logcat | Process lifetime / OS ring | Cleared on process kill / OS logcat rotation |
| **Backend In-Memory** | stdout logs via Pino | Process lifetime / Docker log | Docker log rotation (`max-size: 10m`) |

*There is no persistent SQLite table or file storage for diagnostic reports.*

---

## 7. Hosted Sentry Inventory & Resources

| Resource | Slug / Identifier | Project ID | Platform |
| :--- | :--- | ---: | :--- |
| **Organization** | `jans-gi` | — | Hosted Sentry SaaS |
| **Android Project** | `buzer-android` | `4511950606172240` | Android (Kotlin) |
| **Backend Project** | `buzer-backend` | `4511950617641040` | Node.js (TypeScript) |

---

## 8. Removed Legacy Components (Do Not Re-Implement)

The following components were removed during the lean observability simplification and must not be restored without a formal Architecture Decision Record:
- **`DiagnosticContextStore` & Android Context Providers**: Replaced by direct tag passing at capture boundaries.
- **Custom Android / Backend Ring Buffers**: Replaced by Sentry's native breadcrumb and envelope buffering.
- **Capsule Collectors & Attachments**: `android-diagnostics-v1.json`, `backend-diagnostics-v1.json`, `android-app-log.txt`, `android-network-history.json`, and `android-threads.txt` are completely removed.
- **`POST /diagnostics/reports`**: Removed HTTP diagnostic endpoint.
- **`diagnostic_reports` SQLite Table**: Removed via database migration v6.
- **Distributed Trace Propagation**: `sentry-trace` and `baggage` headers are not propagated.
- **Automated Triage Bot (`tools/sentry-github-sync`)**: Removed in favor of direct Sentry UI triage.
