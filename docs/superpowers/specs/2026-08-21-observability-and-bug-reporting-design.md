# Buzer Observability and Bug Reporting Design

Date: 2026-08-21

Status: Superseded on 2026-08-22 by
`docs/superpowers/specs/2026-08-22-lean-observability-design.md`. Retained for historical
context only; do not implement new work from this document.

## Relationship To Existing Product Truth

This document extends the approved product specification at
`docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`. It does not
change task lifecycle, enforcement, Todoist authority, WhatsApp behavior, or current MVP
scope. If this document conflicts with a newer decision document, the newer decision
document wins.

This document is intentionally self-contained. Implementation may be split across
different LLMs, harnesses, sessions, branches, and worktrees. No implementation agent
should need the design conversation that produced it.

## Goal

Make crashes, ANRs, handled failures, silent UI glitches, Android enforcement failures,
backend failures, and user-observed bugs durable and diagnosable. A report should contain
enough correlated Android and backend evidence that a future agent can usually identify
the failing component and create a reproducing test without first asking the user to
recreate the incident.

The operational loop is:

```text
observe -> correlate -> triage -> reproduce -> fix -> verify -> release -> watch for recurrence
```

## Current State

The repository already has useful diagnostic foundations:

- Shared Kotlin uses Kermit `2.1.0` through
  `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt`.
- Android emits structured `Buzer` Logcat messages across refresh, alarm, blocker,
  overlay, readiness, editor, and action paths.
- `SnapshotDiagnostics` emits bounded count/state summaries rather than whole payloads.
- `scripts/adb-verify.sh` provides compact device evidence for logs, alarms, permissions,
  and screenshots.
- `scripts/e2e-real-backend.sh` is the required Android UI/instrumentation authority.
- The accepted E2E foundation design and plan add hermetic test-process ownership,
  correlation, and compact failure diagnostics.
- The backend currently constructs Fastify with `logger: false`. Some explicit
  `console.*` statements exist for WhatsApp lifecycle, but there is no durable backend
  issue store.

The missing capabilities are:

- durable crash, ANR, non-fatal, and backend error collection;
- release and culprit-commit correlation;
- searchable Android/backend traces and identifiers;
- a low-friction report path for glitches that do not throw;
- a bounded diagnostic bundle attached to manual reports;
- a single triage inbox and an explicit repeatable repair workflow.

## Approved Decisions

1. Use hosted Sentry Cloud. Do not self-host Sentry on the Buzer VPS.
2. Use one Sentry organization with separate `buzer-android` and `buzer-backend`
   projects.
3. Use Sentry's native Android feedback form and built-in shake gesture. Do not add
   Shake, Bugsee, Luciq/Instabug, Crashlytics, or another overlapping reporting SDK.
4. Add a visible `Report a bug` fallback entry because a gesture must not be the only
   invocation path.
5. Capture automatic crashes, ANRs, selected handled failures, backend failures,
   performance traces, logs/breadcrumbs, and manual feedback.
6. For manual feedback, create a correlated Android diagnostic capsule and backend
   diagnostic event in addition to Sentry's feedback-triggered replay.
7. Task titles, descriptions, assignee/project data, and bounded API bodies may be
   captured. Credentials and authentication material must never be captured.
8. Sentry is the operational event inbox. GitHub issues are the durable engineering work
   queue.
9. Scheduled triage may summarize and file/update issues, but it must not modify code,
   merge, deploy, or resolve incidents autonomously.
10. Observability is best-effort. It must never delay or weaken alarms, blockers, boot
    recovery, foreground readiness, or user actions.

## Success Criteria

The design is successful when all of the following are true:

- A controlled Android crash produces a symbolicated Sentry issue with release, device,
  recent app behavior, and relevant Buzer context.
- A controlled Android ANR produces a usable ANR issue and thread evidence.
- A controlled backend exception produces a Sentry issue with route/operation, release,
  and correlated request context.
- Shaking a physical Android device opens Sentry's feedback form without an additional
  Android permission.
- The visible fallback opens the same form.
- A submitted manual report contains a replay and references a correlated Android
  diagnostic event and, when reachable, a backend diagnostic event.
- A report made while offline is retained and delivered later without blocking the app.
- A backend outage does not prevent the Android feedback from being retained or sent.
- No tested report contains a bearer token, Todoist token, Sentry auth token, WhatsApp
  credential/session material, cookie, or authorization header.
- A new or regressed actionable Sentry issue reaches the configured alert destination
  and can be represented by exactly one deduplicated GitHub issue.
- A fix can be tied to a Git SHA release and a Sentry issue can be resolved in that
  release and reopened on recurrence.

## Non-Goals

- Replacing the existing Kermit logging facade.
- Adding product analytics, advertising analytics, or user-growth tracking.
- Building a custom bug-report form in the first implementation.
- Capturing other applications' logs or Android-wide unrestricted Logcat.
- Uploading heap dumps or whole SQLite database files on every report.
- Automatically writing or deploying fixes from telemetry.
- Changing enforcement, Todoist, recurrence, action reconciliation, or WhatsApp product
  behavior.
- Making live Sentry availability a dependency of automated tests.

## High-Level Architecture

```text
Android app                                         Backend VPS
-----------                                         -----------
Kermit/AppLog ----> bounded ring buffer             structured backend log buffer
      |                     |                                 |
      +--> Sentry breadcrumbs|                                 +--> Sentry breadcrumbs/logs
                            v                                 v
Sentry Android SDK --> buzer-android project        Sentry Node SDK --> buzer-backend project
      |                     |                                 |
      | crash/ANR/error     | feedback                       | route/poller/WhatsApp/process error
      |                     v                                 |
      |              diagnostic event <--- IDs ---> diagnostic event
      |                     |                                 |
      +-------- trace/request/action/report correlation ------+

Sentry alerts --> triage job --> deduplicated GitHub issue --> test/fix/release workflow
```

### Sentry Project Split

Use two projects so platform configuration, alert rules, issue grouping, ownership, and
release artifacts remain understandable:

- `buzer-android`: Android crashes, ANRs, non-fatals, feedback, replay, mobile traces,
  screenshots, and Android diagnostic attachments.
- `buzer-backend`: Fastify/Node exceptions, Todoist polling, WhatsApp lifecycle failures,
  backend traces, structured logs, and backend diagnostic attachments.

Both projects use the same canonical release identity where possible:

```text
app.buzer@<version>+<short-git-sha>
```

Events also carry the full Git SHA as a tag/context field. Android build type and backend
deployment environment are environments or tags, never encoded into exception messages.

### Required Runtime Components

The eventual implementation should preserve these boundaries even if concrete class or
file names differ after plan-time repository re-audit.

#### Android `ObservabilityRuntime`

Owns early Sentry initialization, release/environment configuration, error/replay sample
policy, feedback-form configuration, global secret filtering, offline transport behavior,
and lifecycle cleanup. It is initialized from an Android `Application` before activities,
receivers, and services use it.

#### Android `DiagnosticContextStore`

Owns the latest structured state exposed to Sentry scopes and manual reports. Producers
update small immutable sections; they do not call Sentry directly. Sections include UI,
sync, scheduling, readiness, actions, process/device health, and correlation identifiers.

#### Android `DiagnosticRingBuffer`

Stores sanitized, timestamped, bounded app logs and network exchanges. It is independent
of Logcat so reports do not require privileged log access and previous Logcat rotation
does not erase the evidence.

#### Android `ManualReportCoordinator`

Owns exactly one report session from feedback-form open through success/error/close. It
freezes the report-time state, supplies correlation tags/context, prepares attachments,
and requests the paired backend diagnostic event after a successful submission. It never
owns the visual form or shake detector; Sentry owns those.

#### Backend `BackendObservability`

Owns Sentry initialization, structured backend logs, exception capture, request/trace
context, secret filtering, process-error hooks, and graceful flush. It does not enable
unrestricted Fastify request logging.

#### Backend `DiagnosticReportService`

Builds a read-only, bounded backend capsule for a supplied report session. It selects
relevant database/action/sync/WhatsApp/log evidence, emits a correlated Sentry event, and
returns the event identity. It cannot invoke Todoist mutations, lifecycle actions, or
WhatsApp sends.

#### Triage Job

Queries Sentry for unresolved actionable issues and updates GitHub issues using stable
Sentry issue IDs. It is a separate operational tool; it is not part of either runtime's
request path.

## Initialization And Configuration

### Android

The Android SDK must be initialized before `MainActivity`, blocker activities, receivers,
or services can report failures. Add an application class and register it in the main
manifest.

Configuration inputs must not be committed:

- `SENTRY_ANDROID_DSN`: ingestion DSN supplied through Gradle/local configuration.
- `SENTRY_ENVIRONMENT`: for example `development-device` or `production-device`.
- `SENTRY_RELEASE`: generated from app version plus Git SHA.

The DSN is an ingestion identifier rather than a Sentry account credential, but it still
remains external configuration. A Sentry auth token must never be embedded in an APK.

When the DSN is blank, the runtime becomes a no-op while local Kermit/Logcat diagnostics
continue working. This is the normal automated-test behavior.

### Backend

Sentry must initialize before Fastify and instrumented dependencies are imported. The
backend uses an ESM-compatible preloaded instrumentation module or the current official
equivalent verified during implementation.

Configuration inputs:

- `SENTRY_DSN`
- `SENTRY_ENVIRONMENT`
- `SENTRY_RELEASE`

Source-map upload credentials are build/deploy credentials and must exist only in the
relevant local/VPS/CI secret store:

- `SENTRY_AUTH_TOKEN`
- `SENTRY_ORG`
- `SENTRY_ANDROID_PROJECT`
- `SENTRY_BACKEND_PROJECT`

When `SENTRY_DSN` is absent, backend observability becomes a no-op except for bounded
local/container logs. Production startup must not fail only because Sentry is
misconfigured or unreachable.

## Capture Policy

### Android Automatic Capture

Capture 100 percent of:

- uncaught JVM/Kotlin crashes;
- Android ANRs supported by the SDK;
- explicitly classified enforcement-critical handled exceptions;
- feedback events;
- error-associated session replays.

Initially capture 100 percent of performance transactions because the app has one user.
Revisit only if Sentry quota, battery, or runtime measurements justify sampling. Do not
enable always-on normal-session replay initially; error replay and the feedback form's
30-second replay buffer provide higher-value evidence with lower runtime risk.

`AppLog` behavior:

- debug/info/warn/error messages remain visible in Logcat and enter the sanitized local
  ring buffer;
- selected messages become Sentry breadcrumbs;
- a warn/error with a throwable at an approved error boundary may become a non-fatal
  event;
- a plain error string does not automatically become an issue;
- the same throwable must not be captured twice by nested layers.

Automatic breadcrumbs should cover:

- app/activity/service/receiver lifecycle;
- navigation and visible screen transitions;
- meaningful taps and task actions;
- snapshot fetch/reduce/apply transitions;
- alarm scheduling, firing, cancellation, and stale suppression;
- blocker/overlay show, action, timeout, and dismissal;
- readiness changes and settings round-trips;
- API request start/end/failure;
- backend/action/request identifiers returned to the client.

### Backend Automatic Capture

Capture 100 percent of:

- uncaught exceptions and unhandled promise rejections;
- Fastify route errors that become 5xx responses;
- Todoist poll/synchronization failures;
- action coordinator/finalization failures;
- WhatsApp initialization, authentication, disconnection, and send failures;
- database migration/startup/shutdown failures;
- diagnostic endpoint assembly failures.

Expected domain errors and validated 4xx responses are breadcrumbs/logs rather than
issues unless repetition crosses an alert threshold. Keep Fastify `logger: false` unless
a later approved design replaces it with proven redacted request logging.

## Correlation Contract

These identifiers must use the same spelling and meaning on both platforms:

| Field | Purpose | Searchable tag? |
| --- | --- | --- |
| `release` | App/backend Git-SHA release | Yes |
| `environment` | Runtime environment | Yes |
| `device_id` | Existing Buzer installation/device identity | Yes |
| `task_id` | Backend task identity when relevant | Yes |
| `todoist_task_id` | Todoist identity when relevant | Context by default |
| `occurrence_key` | Recurring occurrence identity | Yes |
| `action_id` | Durable action-journal identity | Yes |
| `request_id` | HTTP request identity | Yes |
| `trace_id` | Cross-process Sentry trace | Native trace field |
| `schedule_version` | Applied schedule version | Context |
| `snapshot_generation` | Snapshot authority generation | Context |
| `report_session_id` | One manual-report attempt | Yes |
| `android_diagnostic_event_id` | Paired Android capsule event | Context |
| `backend_diagnostic_event_id` | Paired backend capsule event | Context |

Android API requests propagate the current Sentry trace headers using the current
supported Ktor/Sentry integration or a tested explicit interceptor. The backend continues
the trace using the current supported Sentry Fastify/Node integration. Header propagation
must not replace the backend's own request ID.

Dynamic values must not be interpolated into exception messages used for issue grouping.
Use stable messages plus tags/context. Default fingerprints combine stable component,
operation, and exception type. Task, occurrence, device, action, request, and timestamp
values are excluded from fingerprints.

## Manual Feedback Flow

### Invocation

Sentry Android SDK 8.36.0 introduced global shake-to-feedback; 8.41.0 added the current
`Sentry.feedback()` API and per-form support. Implementation must resolve the current
stable SDK version and current API with Context7 immediately before editing dependencies.

Enable the SDK's built-in accelerometer gesture globally. It requires no additional
Android permission. Configure the native form as follows:

- title: `Report a bug`;
- description is required by the SDK;
- description prompt asks what happened and what was expected;
- hide name and email fields;
- disable Sentry branding if supported by the selected stable SDK;
- success and error callbacks route through `ManualReportCoordinator`;
- open and close callbacks create and dispose the report session.

Add `Report a bug` as a secondary action in the existing Home `IconFabStack`, alongside
the primary Add task action. It invokes the same `Sentry.feedback().show(...)` path and
is exercised by instrumentation tests. Do not create a second form or an unrelated
settings screen solely for reporting.

### Report Session State Machine

```text
idle
  -> form_open: create report_session_id, freeze Android state, set temporary scope
  -> submitted: feedback accepted/cached by Sentry
      -> emit Android diagnostic event and request backend diagnostic event
      -> completed: clear temporary scope and local frozen state
  -> submit_error: retain bounded frozen state for retry evidence, then clear scope
  -> cancelled/closed: discard frozen state and clear scope
```

Only one session may be active. A second open reuses or cleanly replaces stale state; it
must not stack scopes or leak an old `report_session_id` into later events.

Sentry's Android feedback capture applies current scope tags and contexts to the feedback
event, but its native form does not expose arbitrary attachment selection. Therefore:

1. On form open, generate `report_session_id` and a reserved
   `android_diagnostic_event_id` if the current public SDK permits it; otherwise capture
   the diagnostic event immediately and store the returned ID.
2. Freeze the capsule at form-open time so later UI changes do not rewrite the incident.
3. Put both identifiers into the temporary Sentry scope before submission.
4. Let Sentry attach the feedback-triggered replay.
5. Emit or finish the paired Android diagnostic event with the frozen attachments.
6. Request the paired backend event using the same `report_session_id`.

If the SDK cannot reserve an event ID, capturing the paired Android diagnostic event on
form open is the approved fallback. A cancelled form may then leave a low-priority orphan
diagnostic event; tag it `feedback_submitted=false` and exclude it from alerts.

## Android Diagnostic Capsule

The capsule schema begins at version `1` and is serialized as deterministic JSON plus
separate binary/text attachments. Every section reports its own capture status:
`complete`, `partial`, `unavailable`, or `failed` with a stable reason code.

### Structured JSON Sections

- Report identity and timestamps.
- App version, version code, full Git SHA, build type, environment, process uptime, and
  install/device identity.
- Android version/API, manufacturer/model, locale, timezone, display configuration, and
  device wall/elapsed clocks.
- Current activity/screen, foreground/background state, blocker/overlay state, open
  editor draft, selected task/occurrence, and in-flight operation.
- Full cached Buzer task snapshot and current reduced UI state.
- Schedule version, snapshot generation, last refresh attempt/result, last successful
  sync, pending refresh/action state, and last error.
- Local alarm registry, occurrence identities, alarm kind, scheduled trigger instants,
  last fired/cancelled state, and Morning Review schedule/local state.
- Notification, exact-alarm, full-screen-intent, overlay, battery-optimization, and other
  readiness capability state.
- Pending/retried actions and latest action responses known to Android.
- Battery/charging/doze, connectivity, available memory, memory-pressure state, free
  storage, and previous process-exit reason where Android exposes it.
- Current thread names/states and a bounded application thread dump.
- Current trace/span, request, action, task, occurrence, and report identifiers.
- Relevant SharedPreferences/local-store values after secret filtering.

### Attachments

- `android-diagnostics-v1.json`
- `android-app-log.txt`: sanitized Kermit/AppLog ring buffer.
- `android-network-history.json`: sanitized bounded API history.
- `android-threads.txt`: bounded app-process thread dump.
- `android-screenshot.webp` or `.png` when a capturable window exists.

The Sentry replay is a separate native Sentry item and is not duplicated into the
capsule archive.

### Buffer And Size Policy

The implementation plan may tighten limits after verifying current Sentry attachment
limits, but it must not exceed these app-owned ceilings:

- logs: newest 1,000 entries or 15 minutes, whichever is smaller;
- network history: newest 100 exchanges or 15 minutes, whichever is smaller;
- individual request/response body: 64 KiB after UTF-8 encoding and redaction;
- thread dump: 512 KiB;
- screenshot: 2 MiB;
- all Android diagnostic attachments excluding replay: 8 MiB compressed/encoded total.

When over budget, preserve in this order:

1. report/release/correlation identity;
2. current task, snapshot, action, alarm, and readiness state;
3. error and warning logs;
4. failed and slow network exchanges;
5. screenshot;
6. info/debug history.

Truncation is explicit in capsule metadata. Never silently omit a section.

## Backend Diagnostic Contract

Add this authenticated endpoint with the following contract:

```http
POST /diagnostics/reports
Authorization: Bearer <existing Android API token>
Content-Type: application/json

{
  "schemaVersion": 1,
  "reportSessionId": "uuid",
  "androidDiagnosticEventId": "sentry-id",
  "capturedAt": "ISO-8601 instant",
  "deviceId": "existing device identity",
  "taskId": "optional backend task id",
  "occurrenceKey": "optional occurrence key",
  "actionId": "optional action id",
  "requestIds": ["bounded", "recent", "ids"],
  "traceId": "optional sentry trace id"
}
```

Successful response:

```json
{
  "schemaVersion": 1,
  "reportSessionId": "uuid",
  "backendDiagnosticEventId": "sentry-id",
  "outcome": "captured"
}
```

Required endpoint properties:

- existing Android bearer authentication;
- strict schema validation;
- idempotency by `reportSessionId`;
- read-only database access;
- no Todoist, WhatsApp, action, or reconciliation mutation;
- a narrow independent rate limit, initially five sessions per ten minutes per device/IP;
- a two-second backend collection deadline and three-second total endpoint deadline;
- a five-second Android client timeout that never blocks feedback submission;
- `202`/captured semantics are acceptable because Sentry transport may cache offline;
- missing optional selectors still produce a general backend capsule;
- backend failure never invalidates the already-submitted Android feedback.

### Backend Capsule Contents

- Backend version/full Git SHA/environment, process uptime, Node version, process memory,
  event-loop health, and current time.
- SQLite schema/migration version, WAL/checkpoint state, bounded table/queue counts, and a
  lightweight read-only health check.
- Last Todoist poll start/success/failure, current poller state, bounded recent sync
  outcomes, and snapshot generation.
- WhatsApp provider mode, initialized/ready/disconnected state, last transition, and
  bounded recent send outcomes.
- Relevant task, commitment, occurrence, action-journal, escalation, and retry/job rows
  selected by supplied identifiers.
- Bounded sanitized structured server logs and request history around the report time.
- Correlation IDs and the Android diagnostic event reference.

Emit `backend-diagnostics-v1.json` and `backend-log.txt` as attachments to a backend
Sentry diagnostic event tagged with `report_session_id` and
`android_diagnostic_event_id`.

Backend capsule bounds are:

- logs: newest 1,000 entries or 15 minutes, whichever is smaller;
- request history: newest 100 exchanges or 15 minutes, whichever is smaller;
- individual stored request/response body: 64 KiB after redaction;
- all backend diagnostic attachments: 8 MiB compressed/encoded total.

Apply the same identity/state/error-first truncation priority used by the Android
capsule and record every truncation in capsule metadata.

## Logs And Network History

### Android

Keep Kermit as the application logging API. Add an Android log writer/sink that preserves
Logcat behavior while forwarding sanitized entries to the ring buffer and Sentry
breadcrumbs/logs. Shared code remains unaware of the Sentry SDK.

Record network exchanges at the Buzer API client boundary rather than attempting to read
system-wide traffic. Capture method, normalized URL/path, start/end time, duration,
status, timeout/cancellation classification, request/response content type, bounded
bodies, request ID, trace ID, and action ID.

### Backend

Introduce a small structured logging facade or sink used by route boundaries, poller,
action coordinator, diagnostics, and WhatsApp provider. It must write useful local
container output and a bounded memory ring while forwarding appropriate records to
Sentry. Do not route all Fastify headers/bodies through an unrestricted logger.

## Data Inclusion And Mandatory Secret Exclusion

The user explicitly approved capturing personally identifying and task-visible data for
this one-user application. The following may be included when useful:

- task titles and descriptions;
- project and assignee names/identities;
- cached task snapshots and UI drafts;
- sanitized API request/response bodies;
- device metadata and session behavior;
- screenshots and replays.

The following must never leave their secret store or appear in local diagnostic buffers,
Sentry, attachments, GitHub issues, console output, or tests:

- Android API bearer token;
- Todoist API token and authorization headers;
- Sentry account/auth token;
- WhatsApp cookies, QR contents, LocalAuth/session files, Chromium profile contents, or
  session credentials;
- HTTP `Authorization`, `Cookie`, `Set-Cookie`, proxy authorization, or equivalent
  credential headers;
- `.env`, `local.properties`, secret-store, or full process-environment dumps;
- private keys or GitHub tokens.

Use one shared redaction contract per runtime. Redaction occurs before data enters any
ring buffer and again immediately before Sentry serialization. Exact-name filtering is
not enough: redact common credential keys case-insensitively and bearer/token/cookie
patterns inside strings. Tests must seed recognizable canary secrets in every supported
location and assert that serialized events and attachments contain none of them.

## Failure Behavior

- Sentry initialization failure: continue app/backend startup with local logging and a
  stable observability-disabled marker.
- Sentry ingest unavailable: rely on SDK disk caching/retry; never block product work.
- Android manual collection has a two-second overall deadline. Timeout emits a partial
  capsule with failed section metadata and never delays feedback submission.
- Screenshot unavailable: omit it with a reason; keep state/log/replay evidence.
- One collector throws: isolate it and continue other collectors.
- Capsule exceeds limit: deterministically truncate by the documented priority.
- Backend endpoint unavailable: keep Android feedback/capsule and record the failed
  backend-correlation attempt.
- Backend Sentry unavailable: return a bounded failure outcome and retain local backend
  logs; do not retry inside the HTTP request indefinitely.
- Form submission fails: call the SDK error callback, preserve local evidence long
  enough for retry diagnostics, and clear temporary global scope state.
- Reporting code recursively logs its own failure: mark it internal and prohibit
  re-entry into Sentry capture.
- App is killed while a form is open: expire persisted report-session state at next
  startup and never leak its tags into the new session.

All collection work except the minimum window screenshot handoff runs off the main
thread. Use a short overall collection deadline and per-collector isolation. Receivers,
services, blocker actions, and UI rendering never wait for an upload or backend capsule.

## Alerts And Issue Grouping

Immediate alerts:

- new or regressed Android fatal crash;
- new or regressed ANR;
- manual feedback submission;
- backend process/startup failure;
- first enforcement-critical or data-consistency non-fatal;
- three consecutive Todoist poll failures or no successful poll for 15 minutes;
- any failed WhatsApp escalation send, or a WhatsApp disconnection lasting five minutes.

Daily digest:

- isolated handled exceptions;
- performance degradation;
- discarded-event/client-report counts;
- diagnostic-capsule failures;
- unresolved issue summary.

Grouping rules use stable component/operation/exception fingerprints. Never include
timestamps, IDs, task content, HTTP bodies, or raw exception messages containing dynamic
values in custom fingerprints.

## GitHub Triage Automation

Sentry remains the event source. GitHub receives only actionable durable work.

The scheduled/manual triage job:

1. queries unresolved/new/regressed Sentry issues with a least-privilege API token;
2. applies policy by issue type, severity, repetition, release, and environment;
3. finds an existing GitHub issue using a hidden stable marker such as
   `<!-- sentry-issue:<id> -->`;
4. creates or updates exactly one issue with Sentry links, first/last seen release,
   frequency, affected component, correlation IDs, and a concise reproduction lead;
5. labels the issue by platform and severity;
6. records its own failures without opening recursive duplicate issues.

It must not:

- copy attachment contents or secrets into GitHub;
- close GitHub issues solely because telemetry stopped;
- modify repository files;
- create branches, commits, pull requests, merges, releases, or deployments;
- mark a Sentry issue resolved without a known release decision.

## Repair Workflow

For each selected issue:

1. Read the Sentry event, capsule, replay, release, correlated backend event, and relevant
   recent repository handoffs.
2. Reproduce the failure with the smallest relevant automated test. Use the systematic
   debugging and test-driven development skills required by the repository workflow.
3. Implement a scoped fix in a dedicated worktree.
4. Run focused verification, then every existing suite that asserts the changed behavior.
5. For client UI/user-visible behavior, run the full unfiltered
   `scripts/e2e-real-backend.sh` suite.
6. Push and confirm `ci.yml` before claiming completion.
7. Release with the fix Git SHA.
8. Resolve the Sentry issue in that release. A recurrence is treated as a failed fix and
   reopens triage.

## Testing Strategy

### Pure/Unit Tests

- redaction across headers, JSON objects, strings, exceptions, attachments, and nested
  structures;
- ring-buffer count/age/byte eviction and deterministic ordering;
- network body truncation and binary-content handling;
- capsule schema serialization and deterministic truncation priority;
- collector isolation, timeout, partial, unavailable, and failure states;
- report-session state transitions and stale-session cleanup;
- scope set/clear behavior and no correlation leakage;
- stable issue fingerprints independent of dynamic IDs/content;
- release/environment resolution and blank-DSN no-op behavior;
- automatic capture classification and duplicate suppression.

### Android Tests

- application initialization does not break receivers/services/activities when Sentry is
  disabled or throws;
- persistent `Report a bug` entry opens the Sentry form through an injectable adapter;
- form callbacks create/finalize/clear one report session;
- screenshot unavailable and collector timeout still submit partial evidence;
- Sentry transport is replaced by a fake/in-memory transport in tests;
- no test contacts Sentry Cloud by default;
- the existing shared JVM and Android unit suites remain green;
- focused and full real-backend instrumentation cover the visible fallback and backend
  diagnostic request.

The physical shake gesture is a physical-device smoke requirement. Emulator tests should
invoke the same form through the visible fallback or adapter rather than depending on an
unreliable synthetic shake.

### Backend Tests

- endpoint authentication, schema validation, rate limit, and idempotency;
- read-only enforcement: diagnostic requests cannot call Todoist, WhatsApp, action, or
  reconciliation mutations;
- exact selection by supplied task/occurrence/action/request identifiers;
- general capsule when optional selectors are absent;
- bounded query time/result size and partial capture;
- backend Sentry-disabled and transport-failure behavior;
- full serialized secret-canary scan;
- existing backend unit/integration tests remain green.

### Contract Tests

- Android request matches backend schema version `1`;
- `report_session_id` and diagnostic IDs round-trip unchanged;
- Android and backend use the same field meanings and timestamp format;
- trace propagation does not replace request IDs or leak authorization.

### Manual Production Canary

Before rollout is complete, verify on the physical target device and deployed VPS:

1. debug-only controlled non-fatal;
2. controlled test crash followed by app restart/upload;
3. controlled ANR in a debug-only path;
4. shake opens the form;
5. visible fallback opens the same form;
6. submitted feedback includes replay and Android diagnostic reference;
7. backend event is correlated by `report_session_id`;
8. offline report uploads after connectivity returns;
9. backend-down report still reaches/caches in the Android project;
10. search all canary event payloads and attachments for seeded secret values;
11. verify release/source mapping and GitHub culprit-commit correlation;
12. verify alert and triage deduplication.

Remove or strictly debug-gate all forced crash/ANR/non-fatal controls after the canary.

## Rollout Phases And Dependencies

The implementation plan must decompose work in this dependency order:

1. **Sentry foundation:** external projects/config, release identity, Android/backend SDK
   initialization, no-op test behavior, source mapping, and controlled crash/non-fatal.
2. **Structured context:** Android Kermit/ring buffer and backend structured logging,
   redaction, scope context, and trace/request propagation.
3. **Native feedback:** Sentry shake form, callbacks, and visible fallback.
4. **Android capsule:** collectors, bounds, screenshot, attachments, and report-session
   coordinator.
5. **Backend capsule:** read-only endpoint/service, database/log collectors, and paired
   event.
6. **Operations:** alerts, GitHub integration, triage job, release-resolution workflow.
7. **Canary and rollout:** physical device, deployed VPS, offline/outage, secret scan,
   CI, and documentation.

Phases 1-3 are sequential. Android and backend work inside phases 2 and 4/5 may be owned
by separate agents only after their shared schema/contracts are committed. Operations
must not begin until event tags, releases, and grouping have been observed in a canary
project.

## Multi-Agent And Multi-Harness Execution Contract

Future implementation may use multiple LLMs or coding harnesses. The plan must therefore:

- assign explicit file ownership to every task;
- use a dedicated worktree for each independently assignable task per
  `docs/notes/agent-workspaces.md`;
- commit shared contracts before parallel Android/backend implementation;
- prohibit parallel edits to the same logging facade, Sentry initializer, schema, plan,
  or handoff file unless ownership is explicitly transferred;
- require each agent to read `AGENTS.md`, the product spec, this design, the implementation
  plan, current relevant decisions, and recent handoffs;
- require current Context7 `library` then `docs` lookups before Sentry, Kermit, Ktor,
  Fastify, Node, Android, Gradle plugin, or GitHub API edits;
- include exact RED/GREEN and regression commands in the implementation plan;
- use fakes/in-memory transports for all automated Sentry tests;
- make external operator steps explicit and never assume a project, DSN, token, alert,
  or integration already exists;
- write a handoff for every task, including no-code investigations;
- integrate child branches only after diff review and relevant verification;
- preserve unrelated dirty files and other agents' edits;
- never mark a plan checkbox complete from code inspection alone when the step requires a
  runtime, test, Sentry dashboard, physical-device, VPS, or CI observation.

Each task handoff must repeat:

- plan/task/checkboxes attempted;
- starting commit, branch, and worktree;
- files changed;
- commands and exact PASS/FAIL/SKIPPED evidence;
- live Sentry/VPS/device actions performed;
- credentials or external operator work still required;
- known failures and next recommended task.

## External Operator Prerequisites

Implementation cannot silently invent or commit these values. A plan must identify who
performs each step:

- create/select the Sentry organization;
- create `buzer-android` and `buzer-backend` projects;
- obtain DSNs;
- create least-privilege source-map/release and triage API tokens;
- store local, VPS, and GitHub Actions secrets;
- configure alert destinations;
- connect Sentry to the GitHub repository;
- approve any billing/retention choice beyond the hosted developer tier;
- deploy backend environment changes;
- install the canary Android build on the physical device.

All code and fake-transport tests should be implementable before live credentials are
available. Live canary and final rollout remain explicitly incomplete until the operator
steps and dashboard evidence pass.

## Documentation Requirements

Implementation must update:

- the implementation plan checkboxes;
- `docs/handoffs/` after each task;
- `docs/notes/local-development.md` with local Sentry/no-op/test commands;
- `docs/notes/vps-deployment.md` with backend environment and release steps;
- `AGENTS.md` if new mandatory verification or triage commands are introduced;
- a decision document for any deviation from Sentry-native feedback, hosted Sentry,
  project split, capture policy, or diagnostic schema.

Do not leave operational knowledge only in Sentry configuration screens or chat
transcripts. Alert names, issue policies, project slugs, environment/release conventions,
and secret locations by name (never value) must be recorded in repository docs.

## References Consulted

- Sentry Android feedback documentation:
  `https://docs.sentry.io/platforms/android/user-feedback/`
- Sentry Android feedback configuration:
  `https://docs.sentry.io/platforms/android/user-feedback/configuration/`
- Sentry Java/Android SDK source and releases:
  `https://github.com/getsentry/sentry-java`
- Sentry shake-to-report announcement dated 2026-04-16:
  `https://sentry.io/changelog/shake-to-report-user-feedback-on-mobile/`
- Firebase Crashlytics Android documentation, evaluated and rejected as the primary tool
  because it does not unify the Node backend and Android issue inbox:
  `https://firebase.google.com/docs/crashlytics/android/get-started`
- Bugsee, Shake, and Luciq/Instabug documentation, evaluated and rejected to avoid a
  second overlapping SDK/dashboard.

Context7 was used for current Sentry Java/Android source-backed documentation after its
local OAuth session was refreshed on 2026-08-21.
