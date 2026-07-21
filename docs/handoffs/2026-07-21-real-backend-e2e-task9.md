# Handoff: Real-backend E2E — Task 9 validation

Date: 2026-07-21

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 9 only. Plan checkboxes remain owned by the parent controller and were
not changed. The validation note records all gates and the complete Android triage.

## Files changed

- `docs/notes/2026-07-18-e2e-real-backend-validation.md`
- `docs/handoffs/2026-07-21-real-backend-e2e-task9.md`

No production backend, fixture adapter, old fake, client, runner, CI, or plan file was
changed by this validation task. The pre-existing E2E-only debug parity correction is
commit `2aabcb7`.

## Verification

Verification:

- Command: `cd backend && npm test && npm run typecheck`
  - Result: PASS
  - Evidence: 31 test files / 370 tests passed; TypeScript exited cleanly.

Verification:

- Command: `git diff --stat master -- backend/src backend/test/fakes/fakeTodoistAdapter.ts`
  - Result: PASS
  - Evidence: no output.

Verification:

- Command: elevated `scripts/e2e-fake-backend.sh` with Android SDK environment
  - Result: PASS
  - Evidence: 27 tests, 0 failures, 2 expected `RealBackendScenarioTest` skips.

Verification:

- Command: elevated `scripts/e2e-real-backend.sh` with Android SDK environment
  - Result: PASS WITH DOCUMENTED DISCREPANCIES
  - Evidence: final XML has 27 tests, 4 bucket-c failures, 0 errors/skips; both new
    scenario tests pass. Report/log paths are in the validation note.

Verification:

- Command: `lsof -nP -iTCP:18433 -sTCP:LISTEN`
  - Result: PASS
  - Evidence: no listener after the runner cleanup trap.

## Known failures and next step

The four remaining Android failures are all documented old-fake discrepancies. The
most important follow-up is a separate client/production API-contract decision for an
empty `childMutations` update request; this Task 9 validation intentionally did not
mask it in the real E2E backend.
