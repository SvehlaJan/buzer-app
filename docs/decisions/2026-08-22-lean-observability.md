# Architecture Decision: Lean Native Sentry Observability

**Date:** 2026-08-22
**Status:** Accepted
**Supersedes:** `docs/decisions/2026-08-21-hosted-sentry-observability.md`

---

## Context

On 2026-08-21, Buzer introduced a custom forensic observability system featuring paired Android and backend diagnostic capsules, in-memory ring buffers, request/response body history capture, custom screenshots and thread dumps, an authenticated backend diagnostic endpoint (`POST /diagnostics/reports`), a SQLite table (`diagnostic_reports`), distributed trace propagation, mutable diagnostic context stores, and an automated Sentry-to-GitHub sync tool (`tools/sentry-github-sync`).

An implementation review revealed that this architecture was disproportionately complex and fragile for a single-user accountability companion:
- Production report pairing across asynchronous mobile and server contexts was complex and race-prone.
- Android log attachments were redundant with Sentry breadcrumbs.
- Fastify 5 was unsupported by Sentry's automatic tracing instrumentation, requiring fragile custom propagation.
- Custom body capture risked leaking bearer tokens or session data if redaction failed.
- The automated GitHub sync bot added polling and state synchronization overhead without providing more value than Sentry's native UI.

Maintaining this bespoke forensic infrastructure represented an unnecessary engineering liability.

---

## Decision

We replace the custom diagnostic capsule, endpoint, database table, and GitHub sync system with a **native-first, lean Sentry architecture**:

1. **Hosted Sentry SaaS**:
   - Retain hosted Sentry (`sentry.io`) with organization `jans-gi`, Android project `buzer-android` (`4511950606172240`), and backend project `buzer-backend` (`4511950617641040`).

2. **Native Android Primitives**:
   - Use Sentry Android SDK for unhandled crashes, ANRs, error replays (100% on error, 0% normal session), native screenshots, and Compose view hierarchies.
   - Use Sentry's native User Feedback dialog, accessible via physical shake gesture and visible `Report a bug` buttons on the Home screen and the non-dismissible Foreground Readiness gate.
   - Forward sanitized `AppLog` records into Sentry breadcrumbs.

3. **Explicit Backend Error Boundaries**:
   - Use Fastify `onError` hook to capture explicit 5xx route errors, attaching `request_id`, HTTP `method`, `route`, and `status_code`. Ignore 4xx errors.
   - Capture background failures explicitly (Todoist poller failures after retry thresholds, WhatsApp disconnection/escalation failures).
   - Disable performance tracing and body capture; log normal traffic to stdout via Pino.

4. **Tag-Based Correlation (No Distributed Trace Headers)**:
   - Correlate client and server events via `request_id` (`X-Request-ID` header) and domain tags (`task_id`, `occurrence_key`, `action_id`, `screen`, `blocker_type`, `marker`).
   - Remove `sentry-trace` and `baggage` header plumbing.

5. **Complete Removal of Custom Machinery**:
   - Delete `DiagnosticContextStore`, `DiagnosticRingBuffer`, `AndroidDiagnosticCollector`, `AndroidAttachmentCollector`, and `ManualReportCoordinator`.
   - Remove `POST /diagnostics/reports` route, repository, and service.
   - Drop the `diagnostic_reports` SQLite table via database migration v6.
   - Delete `tools/sentry-github-sync` and the associated scheduled GitHub Actions workflow.

6. **Direct Sentry UI Triage**:
   - Triage issues directly in Sentry Cloud using filters by release, environment, and correlation tags.
   - Operators create GitHub issues manually only when planning tracked engineering work.

7. **Canonical Release & Environment Identity**:
   - Format: `app.buzer@<version>+<sha>` on both Android and backend.
   - Environments: `development-device`, `production-device`, `development-vps`, `production-vps`.

8. **Strict Credential Redaction & Privacy Boundary**:
   - Maintain Kotlin and TypeScript redactors to sanitize strings, throwable messages, breadcrumbs, tags, and extra metadata.
   - Nullify `event.request` and `event.user` in Sentry `beforeSend` and `beforeBreadcrumb` hooks to guarantee no accidental IP, header, or cookie exposure.

---

## Consequences

- **Positive**: Removed ~8,000+ lines of custom diagnostic collection, buffer management, serialization, endpoint, and sync code.
- **Positive**: Increased mobile and server reliability by eliminating custom report-time state freezing and network calls.
- **Positive**: Zero database bloat or migration maintenance for diagnostic tables.
- **Positive**: Stronger privacy guarantees with nullified SDK request/user objects and no body buffering.
- **Operational**: Triage is done directly in the Sentry UI. GitHub issues are no longer created automatically.

---

## Alternatives Considered

1. **Repair the full paired-capsule system**: Rejected. The maintenance burden of custom collectors, buffer size budgeting, network serialization, endpoint rate limiting, and report pairing across mobile/server is unjustified for a single-user companion.
2. **Retain an Android-only custom capsule**: Rejected. Sentry's native error replay, ANRs, screenshots, view hierarchy, and breadcrumbs already provide superior diagnostic evidence without custom serialization code.
