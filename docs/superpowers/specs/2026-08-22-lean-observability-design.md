# Lean Observability Design

Date: 2026-08-22

Status: Approved direction from the 2026-08-22 implementation review; pending written-spec confirmation before implementation.

Supersedes: `docs/superpowers/specs/2026-08-21-observability-and-bug-reporting-design.md`

## Goal

Provide dependable crash, ANR, handled-error, backend-error, and user-feedback reporting
with the smallest maintainable amount of Buzer-owned code. Hosted Sentry remains the
diagnostic inbox, but Sentry owns capture, replay, offline delivery, screenshots, view
hierarchy, and feedback transport. Buzer supplies only product-specific breadcrumbs,
stable identifiers, release metadata, and mandatory credential filtering.

This design is intentionally self-contained for implementation across different agents,
LLMs, harnesses, branches, and sessions.

## Why This Supersedes The Previous Design

The previous design turned a Sentry integration into a custom forensic platform. It
introduced paired Android/backend capsules, multiple ring buffers, request/response body
capture, custom screenshots and thread dumps, a diagnostic HTTP endpoint and SQLite
retention, distributed tracing, mutable diagnostic context across enforcement code, and
a bespoke Sentry-to-GitHub synchronizer.

The implementation review found that this machinery was both costly and incomplete:
production report pairing was not wired, Android log attachments were empty, report-time
state was not actually frozen, Fastify tracing was unsupported, secret filtering missed
Sentry request/user fields, and the GitHub synchronizer was not occurrence-idempotent.
For a one-user application, repairing all of those layers would cost more than the
diagnostic value they add.

## Considered Approaches

### 1. Native-first lean observability — selected

Use Sentry's native Android and Node facilities, Buzer breadcrumbs, request IDs, and a
small sanitizer. This preserves the high-value evidence and deletes most custom state,
transport, storage, and automation.

### 2. Repair the full paired-capsule system — rejected

This would preserve deterministic Android/backend snapshots, but it requires fixing and
maintaining every custom collector, timeout, idempotency boundary, redactor, retention
job, trace bridge, and triage workflow. That is disproportionate for one user.

### 3. Retain an Android-only custom capsule — rejected

This removes backend pairing but still duplicates Sentry screenshots, replay, ANR thread
evidence, breadcrumbs, buffering, size budgeting, and offline transport. The remaining
complexity is still larger than its likely diagnostic value.

## Approved Architecture

```text
Android app                                      Backend VPS
-----------                                      -----------
AppLog -> Logcat + sanitized breadcrumbs         local stdout + sanitized breadcrumbs
       -> selected handled exceptions            selected handled exceptions

Sentry Android SDK                               Sentry Node SDK
- crashes and ANRs                               - route 5xx errors
- error replay                                   - poller/WhatsApp/action failures
- native screenshots/view hierarchy              - startup/process failures
- native shake and feedback form
- offline envelope cache

Both sides carry release, environment, and stable request/task/action identifiers.
There is no report-time backend call and no paired diagnostic capsule.
```

## Android Runtime

Keep a narrow Android observability boundary with only these responsibilities:

- initialize Sentry before activities, receivers, and services;
- configure DSN, environment, release, crashes, ANRs, error replay, native screenshots,
  view hierarchy, and native feedback;
- expose `showFeedback()` for UI surfaces;
- convert sanitized `AppLog` entries to breadcrumbs;
- capture explicitly classified enforcement-critical handled exceptions;
- attach small event-specific tags such as `screen`, `task_id`, `occurrence_key`,
  `action_id`, and `request_id`.

Delete the report session state machine, custom attachments, custom screenshot/thread
collection, diagnostic context store, diagnostic ring buffers, and manual backend report
sender.

### Feedback Availability

The same native Sentry form must be reachable through:

- Sentry's built-in shake gesture on a physical device;
- the existing Home `Report a bug` action;
- a visible `Report a bug` action on the foreground-readiness gate.

The readiness gate must not hide the only visible fallback.

### Android Logging

`AppLog` remains the application-facing API and continues writing through Kermit/Logcat.
Its optional sink forwards sanitized records to Sentry breadcrumbs. A sink failure is
contained and never affects application work. Plain log strings do not create Sentry
issues; selected call sites capture a throwable once at the owning error boundary.

## Backend Runtime

Keep a small Node observability adapter which:

- initializes Sentry from `SENTRY_BACKEND_DSN`, environment, and release before server
  imports;
- captures Fastify route errors which produce 5xx responses;
- captures selected poller, Todoist synchronization, WhatsApp, escalation, startup, and
  shutdown failures;
- adds sanitized breadcrumbs and event-specific correlation tags;
- flushes during graceful shutdown without blocking normal requests.

Fastify remains at version 5.8.5. Current official Sentry Fastify documentation supports
Fastify 3.x/4.x, so this design does not claim automatic Fastify performance tracing.
The explicit Fastify `onError` boundary remains the error-capture authority. Remove
tracing configuration and custom propagation until official Fastify 5 support is
available and verified.

Rely on the Sentry SDK's process-error integrations rather than registering a second set
of manual `uncaughtException` and `unhandledRejection` capture handlers. Application
shutdown signal handling remains product code.

## Correlation

Retain the backend-generated/accepted `X-Request-ID` and return it in responses. Store
request IDs in compact breadcrumbs and attach them to handled exceptions when available.
Use the following stable Sentry tags only when relevant:

```text
device_id
task_id
occurrence_key
action_id
request_id
screen
blocker_type
app_section
release
environment
```

Do not propagate `sentry-trace` or `baggage` in this iteration. Android and backend events
are investigated by release, time, request ID, task/action identity, and surrounding
breadcrumbs rather than a guaranteed distributed trace or report-session pair.

## Data And Secret Policy

Task-visible and personally identifying content remains permitted when explicitly useful,
but credentials remain prohibited. Never capture:

- Android or Todoist API tokens;
- Sentry auth tokens;
- WhatsApp QR/session/cookie material;
- authorization, cookie, set-cookie, proxy-authorization, or API-key headers;
- environment dumps, local property files, private keys, or GitHub tokens.

Set `sendDefaultPii` to `false`. Do not capture HTTP request or response bodies. The
runtime sanitizer must cover messages, exception values, tags, extras, contexts,
breadcrumbs, request URLs/data/headers when present, and user data when present. Sensitive
headers are removed or replaced with `[REDACTED]` before an event leaves the process.

Keep one Kotlin redactor shared by Android code and one TypeScript redactor shared by the
backend. Deleting the GitHub synchronizer deletes the third redaction implementation.
Keep the existing cross-runtime synthetic redaction corpus where it remains useful.

## Removed Components

Delete or unwind all code and tests whose only purpose is:

- `DiagnosticContextStore` and Android context producers;
- Android diagnostic log/network ring buffers;
- Android capsule, size-budget, screenshot, and thread-dump collectors;
- `ManualReportCoordinator` and temporary report scopes;
- diagnostic report DTOs and Ktor request/response body history;
- `POST /diagnostics/reports`;
- the `diagnostic_reports` table, migration, and repository;
- backend diagnostic capsule collectors and in-memory request/body history;
- Sentry trace/baggage propagation;
- `tools/sentry-github-sync`, its wrapper, workflow, and CI job.

Historical handoffs remain in git for provenance but must be clearly marked as describing
the superseded implementation. Operational docs must describe only the lean runtime.

## Releases And Operations

The hosted resources are fixed:

- organization: `jans-gi`;
- Android project: `buzer-android` (`4511950606172240`);
- backend project: `buzer-backend` (`4511950617641040`).

Use the canonical release identity on both runtimes:

```text
app.buzer@<version>+<12-character-git-sha>
```

Local builds resolve the current git SHA rather than silently using `local` when git is
available. Keep backend TypeScript source-map upload. Android mapping upload is required
only when R8/minification is enabled; source context may remain enabled independently.

Sentry is the inbox. For the current one-user volume, create GitHub issues manually for
actionable work or use Sentry's supported GitHub integration. Do not run a custom polling
bot. Alert rules and live canaries remain operator-owned setup.

## Failure Behavior

- Blank DSN: observability becomes a no-op while local logs and product behavior continue.
- Sentry initialization or transport failure: never fail startup, enforcement, sync, UI,
  or user actions.
- Breadcrumb or capture failure: contain it at the adapter boundary.
- Feedback transport offline: rely on Sentry's envelope cache and retry behavior.
- Backend Sentry unavailable: preserve local stdout and normal HTTP behavior.
- No telemetry call may wait on an upload in an alarm, blocker, receiver, service, or
  mutation path.

## Testing Strategy

Tests should exercise production composition rather than reconstructing alternate graphs.

Required automated coverage:

- blank-DSN and enabled Android initialization configuration;
- `AppLog` breadcrumb forwarding and sink-failure isolation;
- complete Sentry event sanitization, including request headers/data and user fields;
- visible feedback action on Home and on the readiness gate;
- selected Android handled-exception boundaries;
- backend 5xx capture and 4xx exclusion;
- backend poller/WhatsApp/action capture thresholds;
- request ID acceptance/generation/response propagation;
- absence of the diagnostic endpoint and removed triage workflow;
- full shared/Android JVM, backend, and real-backend instrumentation suites.

Live completion still requires a physical-device/VPS canary proving shake feedback,
Android crash/ANR capture, backend error capture, release/source-map correctness, offline
delivery, and absence of prohibited secrets.

## Success Criteria

- A controlled Android crash and ANR arrive in `buzer-android` with correct release and
  useful native evidence.
- Shake, Home, and readiness-gate actions open the same native feedback form.
- Feedback carries error replay and native screenshot/view-hierarchy evidence when
  available and is retained offline by Sentry.
- A controlled backend 5xx and selected background failure arrive in `buzer-backend`.
- Relevant events carry request/task/action/screen breadcrumbs or tags without custom
  capsules or HTTP bodies.
- No tested event contains a prohibited credential.
- The diagnostic endpoint/table, custom collectors, trace bridge, and GitHub sync tool are
  absent.
- All required automated suites pass, push CI is green, and the live canary is documented
  before rollout is called complete.
