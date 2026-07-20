# Handoff: Real-Backend E2E Server — Task 3

Date: 2026-07-20

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 3 only: build an in-process and real-port-capable E2E backend server
against the Task 1 Todoist world and Task 2 fixture seed. The parent controller owns
plan progress, so no plan checkboxes were changed.

Task 3 delivery completed locally:

- Added the two-test, authenticated snapshot/project seed conformance suite before
  implementation and observed the expected missing-module RED result.
- Added `buildE2EServer()` with a migrated `:memory:` database, production
  repositories/services, `E2ETodoistAdapter`, and `DryRunSender` wired into the
  unmodified `buildServer()` at the expected test configuration.
- Seeded the adapter world, pre-upserted fixture mirrors with their pinned local IDs,
  executed the real sync, then applied commitment nudges and root occurrence-key pins.
- Added reset row deletion in one transaction, retaining the same database so snapshot
  generation remains monotonic; reset also clears server control state, call records,
  and the adapter scenario before reseeding.
- Kept the debug surface as the requested empty Task 5 placeholder and added the
  direct-execution listener on `0.0.0.0` with `PORT` defaulting to `18433`.

## Files changed

- `backend/test/e2eServer.ts` (new)
- `backend/test/e2eServer.test.ts` (new)
- `docs/handoffs/2026-07-20-real-backend-e2e-task3.md` (new)

No `backend/src`, Task 1/2 adapter or fixture files, existing fake backend, client,
scripts, CI, or plan file was changed.

## Verification

Verification:

- Command: `cd backend && npx vitest run test/e2eServer.test.ts`
  - Result: FAIL (expected RED)
  - Evidence: Vitest reported `Cannot find module './e2eServer.js'` before the server
    module was created.

Verification:

- Command: `cd backend && npx vitest run test/e2eServer.test.ts`
  - Result: PASS
  - Evidence: 1 test file and 2 tests passed. The fixture snapshot has 15 roots and
    derives started, invalid, hierarchy, recurrence, checklist, reconciliation, and
    occurrence-key state through real services; authorized projects and unauthorized
    snapshots also have the expected responses.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` completed without diagnostics.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: Vitest reported 31 test files and 353 tests passed.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

## Manual verification

- Inspected `ProjectRepository.listActive()` and adjusted the conformance expectation
  to production's alphabetical order: `Test Personal`, then `Test Shared`. The test
  still asserts the exact two fixture ID/name pairs; no production ordering behavior
  was changed.
- Confirmed seeding happens after all production objects are constructed, with local-ID
  mirror upserts before sync and commitment nudges/occurrence pins only after sync.
- Confirmed reset deletes mutable rows rather than recreating the database and that the
  placeholder debug surface receives adapter, recorded calls, controls, snapshots, and
  reset for Task 5 to fill in.

## Assumptions

- The `snapshot_generations` trigger implementation remains the authority for
  monotonic generation across reset; retaining the database while deleting projection
  rows is sufficient and follows the approved plan.
- Task 5 will add observable coverage for reset and the debug surface, whose behavior
  is deliberately outside Task 3 scope.

## Known failures or skipped checks

- A real-port process smoke test and Android instrumentation are intentionally deferred
  to Tasks 5 through 9, which add debug parity and the dedicated runner.
- No failures are known in the Task 3 backend scope.

## Next recommended step

Proceed with Task 4: append the real happy-path mutation conformance suite and correct
only Task 1 world semantics if a real service exposes a mismatch.
