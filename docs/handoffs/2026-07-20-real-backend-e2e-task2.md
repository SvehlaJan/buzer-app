# Handoff: Real-Backend E2E Server — Task 2

Date: 2026-07-20

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 2 only: a pure E2E fixture seed that represents the 15 legacy fake
roots, six checklist children, two projects, commitment-state nudges, and occurrence
pins as Todoist mirror facts. The parent controller owns plan progress, so no plan
checkboxes were changed.

Task 2 steps completed:

- Added the required focused fixture tests before production code.
- Confirmed RED from the expected missing `e2eFixtures` module.
- Added the pure `e2eFixtureSeed(now)` module with relative ISO dates on every call.
- Verified the focused suite, full backend suite, and TypeScript typecheck.

## Files changed

- `backend/test/fakes/e2eFixtures.ts` (new)
- `backend/test/fakes/e2eFixtures.test.ts` (new)
- `docs/handoffs/2026-07-20-real-backend-e2e-task2.md` (new)

No production backend code, E2E adapter, existing fake adapter, old fake backend,
client, scripts, CI, or plan file was changed.

## Verification

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
  - Result: FAIL (expected RED)
  - Evidence: Vitest reported `Cannot find module './e2eFixtures.js'` before the
    module was created.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
  - Result: PASS
  - Evidence: 1 test file and 6 tests passed. Coverage includes fixture counts and
    IDs, invalid/hierarchy/recurrence facts, child completion and occurrence pins,
    exact nudges, projects, and relative supplied-time behavior.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 30 test files and 348 tests passed.

## Follow-up fidelity fixes

A quality review found that scheduled seed mirrors did not match the real
`HttpTodoistAdapter` representation because `dueDate` remained null, and that recurring
due instants did not respect their declared local rules. The seed now:

- Aligns every non-null `dueDate` with its `dueDatetime`, including the scheduled child
  and recurring roots.
- Derives the next daily, Monday-weekly, and every-two-days fixture occurrences from
  the supplied `now` at their stated `Europe/Bratislava` wall-clock times.
- Uses the same platform `Intl.DateTimeFormat(...).formatToParts()` pattern as
  `taskRecurrence.ts`; its timezone offset data preserves the local fixture time across
  the March DST transition.
- Returns freshly constructed task and project mirrors on each call.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
  - Result: FAIL (expected RED)
  - Evidence: before the seed implementation changed, the new assertions observed a
    null `dueDate` for a scheduled mirror and a daily recurrence due at Bratislava
    13:00 instead of its declared 10:00.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
  - Result: PASS
  - Evidence: 1 test file and 9 tests passed, including exact local date, weekday, and
    time checks for regular and DST-boundary seeds.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` completed with no diagnostics.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 30 test files and 351 tests passed.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` completed with no diagnostics.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

## Manual verification

- Reviewed each seeded root and child against `tools/fake-backend/src/fixtures.ts`
  and the Task 2 plan facts. The seed declares no backend snapshot state; invalidity,
  hierarchy violation, recurrence description, checklist counts, and reconciliation
  status remain for real backend services to derive or apply in Task 3.
- Confirmed fixture projects retain the Android UI contract names and that every root
  receives the hardcoded blocker-test occurrence key format.

## Assumptions

- Every scheduled E2E mirror keeps `dueDate` aligned with `dueDatetime`, matching the
  real `HttpTodoistAdapter` mirror facts; all non-null fixture dates are relative ISO
  strings derived from the supplied `now`.
- Task 3 will apply the returned nudges and occurrence pins only after production sync,
  as prescribed by the plan.

## Known failures or skipped checks

- Android instrumentation and real-port E2E checks are not applicable until Tasks 3
  through 9 wire and run the E2E server.
- No failures are known within Task 2 scope.

## Next recommended step

Proceed with Task 3: seed the E2E Todoist world from `e2eFixtureSeed(now)`, invoke the
production sync path, then apply nudges and occurrence pins through repositories.

## Follow-up review fix

A spec review found that the fixture-count assertion could not detect a wrong 15-root
set. The focused test now asserts the exact ordered legacy root ID set, including
`task-reconciliation-pending`.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
  - Result: FAIL (expected regression-test proof)
  - Evidence: an intentionally incomplete expected set failed with the missing
    `task-reconciliation-pending` root shown in the received list.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eFixtures.test.ts`
  - Result: PASS
  - Evidence: 1 test file and 6 tests passed with the complete exact ID contract.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` completed with no diagnostics.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 30 test files and 348 tests passed.
