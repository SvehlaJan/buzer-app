# Observability And Bug Reporting Design Handoff

Date: 2026-08-21

## Task And Status

- Task: design a durable bug discovery, diagnostic capture, triage, and continuous repair
  loop for the Android client and Node/VPS backend.
- Canonical design:
  `docs/superpowers/specs/2026-08-21-observability-and-bug-reporting-design.md`.
- Status: interactive and written design approved. The self-contained implementation
  plan now exists at
  `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`; runtime
  implementation has not started.
- Branch: `feature/observability-design`.
- Worktree: `.worktrees/observability-design`.
- Starting commit: `2d75e9d` (`master` at worktree creation).

## Plan/Task/Steps Attempted

- The design phase followed the brainstorming workflow. After explicit written approval,
  planning continued under the writing-plans workflow; see the separate planning handoff.
- Audited the approved product specification, current worktrees/status, recent E2E and
  readiness handoffs, current E2E foundation plan, Kermit logging, Android Gradle and
  manifest configuration, backend startup/logging, and deployment/test references.
- Compared hosted Sentry, Firebase Crashlytics, Sentry plus a custom form, and dedicated
  reporting SDKs including Bugsee, Shake, and Luciq/Instabug.
- Confirmed with current Sentry documentation/source that Android supports a native
  feedback form, global shake invocation without an extra permission, scope context on
  feedback, feedback replay, callbacks, and correlated event IDs.
- Repaired the local Context7 OAuth session and verified both `library` and `docs`
  commands.
- Recorded the approved Android/backend architecture, diagnostic schema, redaction
  policy, endpoint contract, alert/triage loop, repair workflow, tests, rollout phases,
  operator prerequisites, and multi-agent execution contract.

## Approved Decisions

- Hosted Sentry Cloud with separate `buzer-android` and `buzer-backend` projects.
- Sentry-native shake-to-feedback form plus a persistent visible fallback.
- Automatic crash, ANR, selected non-fatal, backend error, trace, breadcrumb/log, and
  feedback collection.
- Feedback-triggered replay plus a paired Android diagnostic capsule and backend
  diagnostic event.
- Full task-visible/PII content may be captured; credentials and authentication material
  remain unconditionally excluded.
- Sentry is the operational inbox; GitHub issues are the durable actionable queue.
- Scheduled triage may file/update issues but may not change code or deploy.
- Observability is best-effort and cannot delay or weaken enforcement.

## Files Changed

- Created
  `docs/superpowers/specs/2026-08-21-observability-and-bug-reporting-design.md`.
- Created this handoff.
- No Android, shared Kotlin, backend, Gradle, package, workflow, deployment, or runtime
  code was changed.

## Commands And Research

Verification:
- Command: `git worktree list --porcelain`
- Result: PASS
- Evidence: existing worktrees were identified before work; this documentation task uses
  `.worktrees/observability-design` on `feature/observability-design`.

Verification:
- Command: `git status --short --branch`
- Result: PASS
- Evidence: primary checkout was clean before the dedicated worktree was created.

Verification:
- Command: repository `rg`, `find`, and `sed` audits over Android/shared/backend sources,
  Gradle catalogs, manifests, plans, notes, handoffs, and recent Git history
- Result: PASS
- Evidence: current Kermit/Logcat diagnostics, `logger: false` backend behavior, test
  authority, and active E2E/readiness context are reflected in the design.

Verification:
- Command: `npx ctx7@latest whoami`
- Result: PASS
- Evidence: authenticated Personal teamspace after refresh.

Verification:
- Command: `npx ctx7@latest library "Sentry Android" ...`
- Result: PASS
- Evidence: resolved `/getsentry/sentry-java` with high-reputation source-backed docs.

Verification:
- Command: `npx ctx7@latest docs "/getsentry/sentry-java" ...` and
  `npx ctx7@latest docs "/websites/sentry_io_platforms" ...`
- Result: PASS
- Evidence: returned current initialization, feedback callbacks/form, scope context,
  breadcrumbs, offline caching, ANR, and replay documentation.

Verification:
- Command: official Sentry/Firebase/Bugsee/Shake/Luciq web documentation review
- Result: PASS
- Evidence: alternatives and the Sentry-native recommendation were checked against
  current primary vendor sources.

Verification:
- Command: product tests/builds/device/VPS/Sentry canary
- Result: SKIPPED
- Evidence: this session changes documentation only; no runtime dependency or behavior was
  modified. The design defines the required automated and live canary verification for
  implementation.

## Context7 Authentication Repair

The CLI initially returned `Invalid or expired OAuth token` while `ctx7 whoami` reported
that a saved session existed and might be expired. The stale session was removed with
`npx ctx7@latest logout`, refreshed with the documented device OAuth flow, and verified
with authenticated `whoami`, `library`, and `docs` calls. No credential value was written
to the repository.

## Assumptions And Constraints

- The user accepts hosted Sentry Cloud and broad capture of personal/task-visible data.
- Secrets remain excluded even though PII capture is permitted.
- External Sentry projects, DSNs, tokens, alerts, GitHub integration, VPS configuration,
  and physical-device canary work do not exist merely because the design names them.
- Current Sentry APIs and stable versions must be re-resolved with Context7 during
  implementation.
- The active E2E foundation implementation may change test infrastructure before this
  feature is implemented; the observability plan must re-audit and build on the integrated
  state rather than duplicate that work.
- The native Sentry form is retained. A custom Compose form or second reporting SDK
  requires a new explicit decision.

## Known Skipped Work

- No Sentry organization/project was created.
- No DSN or auth token was obtained or stored.
- No GitHub/Sentry integration or alert was configured.
- No runtime implementation was performed. The later planning session produced the
  implementation plan linked above.
- No runtime code, tests, deployment, APK install, or canary report was performed.
- The documentation branch has not been merged into `master`.

## Next Recommended Step

1. Choose an execution mode from the planning handoff.
2. Execute shared contract Tasks 1-2 before independently assigning Android/backend
   work.
3. Keep external operator prerequisites and live canary gates open; do not mark the
   feature complete from fake-transport tests alone.
