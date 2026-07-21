# 2026-07-20 E2E Real Backend Task 5: Debug Endpoint Parity

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`
- Task: 5 — Debug-endpoint parity + scenario presets over HTTP.
- Checkbox status: Task 5 steps 1–4 complete; commit step complete after the commit recorded below.
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/e2e-real-backend`

## Files Changed

- `backend/test/e2eServer.ts`
  - Registered the unauthenticated e2e-only `/__scenario`, `/__reset`, `/__calls`,
    `/__fail-actions`, `/__fail-snapshot`, and `/__fail-projects` surface.
  - Added a root `preHandler` that skips `/__`, records the old-fake action metadata
    subset, and applies failure controls before production mutation handling.
- `backend/test/e2eServer.test.ts`
  - Added HTTP conformance coverage for scenario selection, call filtering, forced
    failures, reconciliation-pending presets, and reset monotonicity.

## TDD Evidence

- Added the debug-surface conformance block before implementing `registerDebugSurface`.
- Command: `cd backend && npx vitest run test/e2eServer.test.ts`
- Result: expected RED — 7 new debug tests failed because the debug routes were 404
  and controls were absent; the existing 12 mutation/seeding tests passed.

## Verification

- Command: `cd backend && npx vitest run test/e2eServer.test.ts`
- Result: PASS
- Evidence: 1 test file, 19 tests passed.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 31 test files, 370 tests passed.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed with exit code 0.

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification

- Exercised all debug endpoints through Fastify `app.inject` without authorization,
  while production snapshot/project requests retained bearer authentication.
- Confirmed `after_command_sent_timeout` and `sync_lag_effect_unknown` reach the real
  action coordinator and return 202 `reconciliation_pending`, with the task snapshot
  reporting `reconciliationStatus: "pending"`.
- Confirmed reset clears call history, failure controls, and the selected scenario;
  reset reseeds `task-start` and advances `snapshotGeneration`.

## Assumptions

- `E2E_SCENARIOS` remains the single source of truth for accepted HTTP preset names.
- Failure toggles are intentionally scoped to the e2e harness and do not modify
  production routes or authentication behavior.

## Known Failures / Skipped Checks

- No known failures.
- Android instrumentation was not run: Task 5 changes only backend test-harness code
  and its Vitest conformance suite; no client code or user-visible behavior changed.

## Next Recommended Step

Proceed with Task 6 from the real-backend e2e-server plan: add the e2e server npm script
and perform the real-port smoke check.
