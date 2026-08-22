# Architecture Decision: Hosted Sentry Observability & Bug Reporting

**Date:** 2026-08-21
**Status:** Superseded
**Superseded by:** `docs/decisions/2026-08-22-lean-observability.md`

---

## Context

Buzer is an aggressive personal accountability companion operating across an Android client (Kotlin Multiplatform/Compose) and a Node.js/Fastify backend on a VPS. When unexpected glitches, alarm issues, background synchronization drops, or crashes occur, diagnosing them requires high-fidelity, correlated telemetry across both the mobile device and the server.

---

## Decision

1. **Hosted Sentry Cloud**:
   - Use hosted Sentry SaaS (`sentry.io`) rather than self-hosting Sentry on the Buzer VPS to prevent telemetry dependencies from consuming VPS resources or failing when the VPS fails.

2. **Two Sentry Projects**:
   - Maintain separate `buzer-android` and `buzer-backend` projects in organization `svehla` (or `jans-gi`). This provides distinct symbolication, alerting rules, platform-specific issue groupings, and stack trace mappings.

3. **Native Shake Gesture with Persistent Visible Fallback**:
   - Use Sentry Android SDK's built-in accelerometer shake gesture for user bug reporting (no additional permissions needed).
   - Provide a persistent visible `Report a bug` entry in the UI (`IconFabStack`) so the feature is always accessible in test automation, emulators, and low-motion contexts.

4. **Broad PII & Task Capture Permitted**:
   - For this single-user personal companion, capturing task titles, descriptions, project names, UI states, screenshots, and session replays is explicitly permitted to maximize debugging fidelity.

5. **Strict Secret Exclusion**:
   - Bearer API tokens, Todoist tokens, Sentry auth tokens, WhatsApp session files/QR codes, and HTTP auth headers are strictly prohibited from entering ring buffers, telemetry payloads, Sentry events, or GitHub issues. Multi-stage sanitization and redaction engines run before buffer insertion and transmission.

6. **Sentry Event Inbox & GitHub Engineering Queue**:
   - Sentry serves as the operational event inbox.
   - GitHub Issues serve as the durable engineering queue.
   - An automated deduplication tool (`tools/sentry-github-sync`) bridges actionable Sentry issues into GitHub with deterministic markers (`<!-- sentry-fingerprint: ... -->`), issue grouping, and regression detection.

7. **No Autonomous Fixes / Deploys**:
   - Automated workflows and triage scripts may create or update GitHub tracking issues, but must never autonomously edit code, commit, merge, or deploy fixes.

8. **Paired Diagnostic Capsules**:
   - User bug reports generate paired Android and backend diagnostic capsules linked via a common `report_session_id`, carrying structured state (`android-diagnostics-v1.json`, `backend-diagnostics-v1.json`), ring buffer logs, and network history.

9. **Best-Effort Telemetry Boundary**:
   - Observability is strictly best-effort. It must never block alarms, blocker screens, boot reconciliation, or user interaction. Missing DSNs, network dropouts, or Sentry outages fail gracefully into no-op mode.

---

## Consequences

- **Positive**: Complete visibility into crashes, ANRs, backend errors, and user-reported bugs with full stack traces and reproduction context.
- **Positive**: Low mental overhead with automated triage syncing to GitHub issues.
- **Operational**: Operator must configure Sentry DSNs and auth tokens in local properties / VPS environment.
