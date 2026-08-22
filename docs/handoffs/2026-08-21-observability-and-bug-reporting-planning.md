# Observability And Bug Reporting Planning Handoff

> [!WARNING]
> **SUPERSEDED (2026-08-22):** This planning handoff has been superseded by `docs/superpowers/specs/2026-08-22-lean-observability-design.md`, `docs/decisions/2026-08-22-lean-observability.md`, and `docs/superpowers/plans/2026-08-22-lean-observability.md`. It is retained for historical provenance only.

Date: 2026-08-21

## Task And Status

- Task: translate the approved observability and bug-reporting design into a
  self-contained, multi-agent/multi-harness implementation plan.
- Design:
  `docs/superpowers/specs/2026-08-21-observability-and-bug-reporting-design.md`.
- Plan:
  `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`.
- Status: plan written and self-reviewed; runtime implementation has not started.
- Branch: `feature/observability-design`.
- Worktree: `.worktrees/observability-design`.
- Baseline: `master` commit `2d75e9d`; design commit `0f17e1a`.

## Plan/Task/Steps Attempted

- Followed `superpowers:writing-plans` after the user explicitly approved the written
  design.
- Re-audited repository worktrees/status, shared Kermit logging and Ktor client, Android
  application/manifest/Home UI/enforcement wiring and tests, backend Fastify/routes/
  migrations/poller/WhatsApp/deployment/CI, the real-backend E2E authority, and current
  operational runbooks.
- Re-resolved current Sentry Android, Sentry Node, Kermit, Sentry API, feedback, Gradle
  plugin, and release documentation.
- Defined 13 dependency-ordered tasks with explicit file ownership, RED/GREEN commands,
  commit boundaries, integration gates, full E2E verification, and operator-only live
  prerequisites.
- Performed a line-by-line design/plan comparison and corrected endpoint schema fields,
  capsule names/statuses/priorities, sampling, reserved Android event-ID handling,
  backend disabled behavior, poll-failure capture, and triage ordering.
- Added links to the approved design/plan in `AGENTS.md` so later agents discover them in
  the required start-of-session routine.

## Files Changed

- Created
  `docs/superpowers/plans/2026-08-21-observability-and-bug-reporting.md`.
- Created this planning handoff.
- Updated `AGENTS.md` with the observability design/plan entry point and rollout warning.
- Updated `docs/handoffs/2026-08-21-observability-and-bug-reporting-design.md` to record
  written approval and the plan transition.
- No Android, shared Kotlin, backend, package lock, Gradle, workflow, deployment, or
  runtime behavior was changed.

## Current Version And API Decisions

- Sentry Android SDK: `8.53.0`.
- Sentry Android Gradle Plugin: `6.19.0`.
- Sentry Node SDK: `10.70.0`.
- Sentry CLI: `3.6.2`.
- Kermit remains `2.1.0`.
- Android uses explicit early initialization, native `Sentry.feedback().show()`, built-in
  shake, native feedback callbacks, error replay, and paired diagnostic events.
- Node uses ESM `--import` preloading before Fastify imports.
- Each implementation task must re-run Context7 immediately before editing SDK/API code;
  version/API drift requires a documented plan update.

## Verification

Verification:
- Command: `git worktree list --porcelain` and `git status --short`
- Result: PASS
- Evidence: planning continued in `.worktrees/observability-design`; only the intended
  documentation files were dirty before commit.

Verification:
- Command: repository `rg`, `sed`, and `wc` audit over named Android/shared/backend/test/
  build/deploy files
- Result: PASS
- Evidence: every planned path maps to the current repository or is explicitly marked as
  a new file; existing `HomeScreenTest`, `BlockerErrorReporterTest`, backend route/rate/
  migration tests, and `scripts/e2e-real-backend.sh` are included.

Verification:
- Command: `npx ctx7@latest library ...` then `npx ctx7@latest docs ...` for Sentry
  Android, Sentry Node, Kermit, and Sentry API
- Result: PASS
- Evidence: confirmed current manual initialization, native feedback/shake callbacks,
  Kermit custom writer/fan-out APIs, Node ESM preload/logging/tracing, and API auth/webhook
  behavior.

Verification:
- Command: official Sentry Android/Gradle/Node release-page review
- Result: PASS
- Evidence: pinned Android SDK `8.53.0`, Gradle plugin `6.19.0`, Node SDK `10.70.0`,
  and Sentry CLI `3.6.2` as current non-prerelease versions on 2026-08-21.

Verification:
- Command: plan placeholder/fence/heading/spec-coverage scans and `git diff --check`
- Result: PASS
- Evidence: no unresolved task placeholders or unfinished-work markers; Markdown fences are
  balanced; all approved design sections have an implementation or live-operator gate.

Verification:
- Command: product tests/builds/device/VPS/Sentry canary
- Result: SKIPPED
- Evidence: this session changes documentation only. Tasks 1-13 name the exact automated,
  physical-device, VPS, Sentry dashboard, triage, and CI gates required for completion.

## Assumptions And Constraints

- The implementation must start from the latest integrated `master`, not blindly from
  the plan's `2d75e9d` baseline.
- Active E2E foundation work may rename helpers. Task 10 explicitly requires re-audit and
  prohibits restoring removed fake-backend infrastructure.
- Code and fake transports can be implemented without Sentry credentials. Live rollout
  cannot be completed without the user/operator creating projects, storing secrets,
  configuring alerts/integration, deploying the VPS, and running a physical canary.
- The native Sentry form remains mandatory. A custom Compose form or second reporting SDK
  requires a new decision.
- PII/task data is allowed; authentication and secret material remains prohibited.

## Known Skipped Work

- No Sentry organization/project, DSN, token, alert, GitHub integration, or source-map
  upload was created.
- No dependency was installed and no code/test/workflow/runtime implementation began.
- No APK was built or installed and no Sentry/VPS/physical-device canary ran.
- This branch has not been merged into `master`.

## Next Recommended Step

Choose one execution mode:

1. **Subagent-Driven (recommended):** use `superpowers:subagent-driven-development` in
   this task, beginning with sequential shared Tasks 1-2, then independently owned
   Android/backend worktrees when the plan permits parallelism.
2. **Inline Execution:** use `superpowers:executing-plans` in this task and implement the
   same checkboxes sequentially with review checkpoints.

Whichever mode is selected, first rebase the documentation/plan on the latest integrated
`master`, create the Task 1 worktree/manifest, and leave all live rollout checkboxes open
until dashboard/device/VPS evidence exists.
