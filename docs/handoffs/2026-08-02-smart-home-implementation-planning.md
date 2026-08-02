# Smart Home And True Refresh Implementation Planning Handoff

Date: 2026-08-02
Branch/worktree: `feature/smart-home` / `/Users/svehla/playground/buzer-app/.worktrees/smart-home`

## Task and status

Prepared the independently executable implementation plan for Change 1, Smart Home and authoritative pull-to-refresh, from the accepted reduced-scope design.

Plan: `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md`

Status:

- [x] Read repository working agreement, accepted base/product design, reduced Change 1 design, recent handoffs, and workspace guidance.
- [x] Mapped current backend ownership/snapshot/sync routes and Android Home/refresh/coordinator code.
- [x] Researched current Material 3 pull-to-refresh and Fastify async route/testing contracts through Context7.
- [x] Resolved forced-sync concurrency: preserve queued `syncOnce`, add coalesced manual `syncNow`, and share one service instance with production polling.
- [x] Wrote a self-contained test-first implementation plan with exact scope, files, contracts, commands, commits, E2E migration, and completion criteria.
- [x] Self-reviewed for placeholders, missing accepted behavior, type/contract consistency, and whitespace errors.
- [ ] Implementation has not started; this handoff is documentation/planning only.

## Files changed

- `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md` — new self-contained Change 1 implementation plan.
- `docs/handoffs/2026-08-02-smart-home-implementation-planning.md` — this planning handoff.

No production or test source files changed.

## Important implementation decisions captured in the plan

- `ROOT_UNASSIGNED` applies only to null-assignee roots in synchronized `canAssignTasks` projects that are not implicitly owner-owned. It skips strict schedule validation but retains checklist hierarchy validation.
- Roots assigned to another user remain `NOT_OWNED` and excluded.
- `TodoistSyncService.syncOnce()` keeps existing queued semantics. A separate `syncNow()` coalesces overlapping manual pulls. HTTP actions, manual sync, and the production poller must share the same service instance.
- Automatic foreground/open refresh stays `GET /tasks/snapshot`; pull, accessibility refresh, and Retry use authenticated `POST /tasks/sync`.
- Home state is a pure function over the complete snapshot plus injected `Instant`, `ZoneId`, and saveable expansion flags.
- Review attention and cached-refresh failure are separate models so neither action hides the other.
- Home is one `PullToRefreshBox` plus one `LazyColumn`; Add is the only persistent FAB.
- Back to top appears deterministically at the third future-date header.
- Change 2 Morning Review scheduling/flow and Change 3 readiness gating are explicitly excluded.

## Commands and evidence

Verification:
- Command: `sed -n '275,355p' backend/test/services/todoistSyncService.test.ts`
- Result: PASS
- Evidence: confirmed existing concurrent `syncOnce()` callers are deliberately queued and consume successive sync tokens; the plan preserves this contract.

Verification:
- Command: repository source inspections using `rg`/`sed` across Home, snapshot policy/projection, sync service, HTTP routes, Android API/coordinator, E2E fixtures, and existing Home tests
- Result: PASS
- Evidence: all file paths and named test suites in the plan were resolved in the current tree; no removed fake-backend tool is used.

Verification:
- Command: `npx ctx7@latest library/docs` for AndroidX Compose Material 3 and Fastify
- Result: PASS
- Evidence: confirmed `PullToRefreshBox` overlay/state usage, accessible alternate refresh action guidance, Fastify async error propagation, and `app.inject` route testing.

Verification:
- Command: `rg -n "TBD|TODO|FIXME|similar to|appropriate error|handle edge|write tests for" docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md`
- Result: PASS
- Evidence: no unresolved implementation placeholders; only intentional references to the removed `tools/fake-backend` prohibition were found by the broader audit.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors in the plan draft before this handoff was added.

Verification:
- Command: backend/client unit, build, and Android E2E suites
- Result: SKIPPED
- Evidence: documentation-only planning session; no runtime source or test behavior changed. The plan mandates focused RED/GREEN tests plus full backend, JVM/build, scoped real-backend E2E, and unfiltered real-backend E2E verification during implementation.

## Assumptions

- The implementing agent starts in a new dedicated worktree and follows the start-of-session routine in `AGENTS.md`.
- The accepted design dated 2026-08-02 is newer product truth for this feature and does not alter unrelated base-spec behavior.
- The historical class name `BlockerActivityFakeBackendTest` remains until a separate rename, but its runtime authority is `scripts/e2e-real-backend.sh` with `backend/test/e2eServer.ts`.

## Known failures or skipped checks

- No known planning failure.
- Runtime tests were intentionally skipped because no production/test code changed.
- Push-triggered CI was not run because this planning branch was not pushed in this session.

## Next recommended step

Give `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md` to the implementation agent and execute it task-by-task with `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Do not combine Change 2 or Change 3 into the implementation branch.
