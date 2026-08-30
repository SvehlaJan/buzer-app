# Level 1 mutation simplification analysis and planning handoff

Date: 2026-08-30
Status: Design approved; implementation not started

## Task and plan status

- Requested work: extremely thorough analysis and implementation plan for Level 1
  simplification, retaining the custom Android UI and adding troubleshootable Sentry
  logs.
- Ambiguous product decisions were grilled with numbered choices and an explicit
  recommendation.
- Approved design:
  `docs/superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md`
- Accepted decision:
  `docs/decisions/2026-08-30-level-1-mutation-simplification.md`
- Executable TDD plan:
  `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- Plan steps attempted: analysis, code/test mapping, decision resolution, design,
  implementation planning, and self-review only. Tasks 0-16 have not been executed.

## Final approved behavior

- Keep custom Android action screens.
- Disable a complete action screen during a mutation or forced Refresh, for at most 15
  seconds; restore normal controls immediately on any response.
- Keep warnings only in the current app session. They remain until a successful manual
  Refresh and offer `Refresh` plus `Open Todoist`.
- Never show rejected, delivery-unknown, or accepted-but-stale actions as successful.
- Remove automatic Android retry, pending state, replay, reconciliation, effect
  verification, journal persistence, and root mutex.
- Serialize Todoist operations through one process-local FIFO. Queue wait is part of the
  deadline. A mutation owns its slot through its single post-command sync.
- Retry each Todoist command at most once for transport/timeout/429/5xx with the exact
  same in-memory UUID. Never retry explicit Todoist rejection.
- Stop a multi-command action on its first failure. Do not roll back or compensate.
- Treat every explicit command rejection as potentially partial. Do not expose an
  acknowledged-command count or tell the user that retry is safe.
- Success requires Todoist acknowledgement plus one successful post-command sync. Do
  not compare intended and observed effects.
- Remove `POST /tasks/sync`. Cached snapshot GET remains default; explicit Refresh uses
  `GET /tasks/snapshot?forceRefresh=true` and fails visibly on live-sync failure.
- Poll every minute. Todoist webhooks are now recorded as backlog work.
- Keep Buzer recurring Done `reset_subtasks:true`, but remove automatic repair after a
  recurrence is completed outside Buzer; checked children may carry forward.
- Keep Start/Postpone as direct SQLite actions and WhatsApp escalation's independent
  per-occurrence claim.

## Observability decisions

- Upgrade backend `@sentry/node` 8.53.0 to reviewed 10.72.0 and enable structured logs.
- Add structured Android attributes and propagate one ephemeral `request_id` through
  `X-Request-ID` for correlation only.
- Log every mutation lifecycle, every failed one-minute poll and first recovery, and one
  aggregate summary for a changed poll. Do not log successful empty polls or merely
  slow operations.
- Expected network/timeout/rate-limit/Todoist rejection/delivery-unknown/accepted-stale
  outcomes are warning logs, not Issues.
- Parsing, state-update, invariant, and other internal failures create one Issue in the
  owning component. The other component records a correlated warning only.
- Never log titles, descriptions, payloads, bodies, tokens, headers, raw URLs, or task
  dumps. Changed summaries use bounded ID-only lists and counts.

## Architecture and code inventory

Directly deletable core units currently contain 887 production and 1,110 test lines:

- `backend/src/repositories/actionJournalRepository.ts`: 416 production lines
- `backend/src/services/taskActionCoordinator.ts`: 419 production lines
- `backend/src/services/rootTaskMutex.ts`: 52 production lines
- their three tests: 1,110 lines total

The broader current coupling includes `TaskActionService`, `TodoistChecklistService`,
`TodoistSyncService`, poller/server/routes, schema/migrations/repositories, Kotlin wire
models/API, `EnforcementCoordinator`, `BlockerErrorReporter`, Home/Blocker/Morning Review
and editor state, alarms/agenda filtering, and the real-backend fixture/instrumentation.

Expected net reduction remains a planning estimate only: roughly 1,000-1,700 production
lines and 1,500-3,000 tests after adding the FIFO, typed errors, one-shot runner,
structured logging, and uncertainty coverage. Task 16 requires measured gross and net
counts rather than repeating this estimate.

Critical implementation constraint: `TodoistSyncService.syncOnce` must be a raw
non-queuing primitive. Only poll, forced Refresh, and mutation top-level entry points
acquire the FIFO. A queued mutation calling a queueing sync would deadlock.

## Files changed in this planning session

- Created `docs/decisions/2026-08-30-level-1-mutation-simplification.md`.
- Created `docs/superpowers/specs/2026-08-30-level-1-mutation-simplification-design.md`.
- Created `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`.
- Updated `docs/notes/2026-06-13-product-backlog.md` with Todoist webhook invalidation.
- Replaced this handoff with approved/current guidance.
- No product code, database, deployment, Todoist state, or Sentry issue was changed.
- Marked
  `docs/handoffs/2026-08-25-sentry-new-issues-and-recurring-done-recommendations.md`
  as historical diagnosis with superseded repair guidance.

## Sentry issues and closure gates

- `BUZER-ANDROID-4`: close only after journal/pending removal, production recurring Done
  with child reset, and a formerly stuck task becoming actionable.
- `BUZER-ANDROID-5`: close only after sync POST removal and production forced-refresh /
  15-second UI verification.
- `BUZER-ANDROID-7`: close only after pre-command sync removal, typed retry/rejection
  tests, and a production mutation canary.
- `BUZER-ANDROID-8`: close only after operational Android failures are warning-only,
  internal Android failures still report, and a canary has no paired Issue.

Use Sentry MCP only after deployment and verification. If authentication is unavailable,
ask the owner to reauthenticate. Never use a browser workaround.

## Verification

Verification:
- Command: `git worktree list`
- Result: PASS
- Evidence: existing worktrees were inventoried; planning stayed in the primary checkout
  and did not alter them.

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: only the pre-existing 2026-08-25 untracked handoff and this planning work
  were present; no product files were modified.

Verification:
- Command: repository-wide `rg --files`/`rg -n` mapping and focused source inspection
- Result: PASS
- Evidence: backend persistence/coordinator/sync/routes/tests and Android shared/API/
  coordinator/UI/instrumentation surfaces are enumerated in Tasks 2-15 of the plan.

Verification:
- Command: Context7 Todoist, Ktor, Sentry Node, and Sentry Android documentation lookups
- Result: PASS
- Evidence: Todoist command UUID retry semantics, Ktor request-level timeout support,
  and both SDK structured-log APIs informed the approved plan.

Verification:
- Command: self-review search for rejected choices across the new decision/spec/plan and
  this handoff
- Result: PASS
- Evidence: the old five-minute, effect-verification, no-backend-upgrade, and safe-retry
  recommendations were removed from current guidance.

Verification:
- Command: product/backend/Android test suites
- Result: SKIPPED
- Evidence: documentation-only planning session; no implementation code changed.

## Assumptions and next step

- Backend DNS/DSN routing was fixed by the owner but still requires a release canary.
- The exact production SQLite version and deploy backup commands must be rechecked
  before implementing migration v7.
- The recommended next step is to execute the written plan in a dedicated worktree,
  stopping at its five review checkpoints. No Sentry issue should be closed during local
  implementation.
