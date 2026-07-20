# Handoff: Real-Backend E2E Server — Task 1

Date: 2026-07-20

Plan: `docs/superpowers/plans/2026-07-18-real-backend-e2e-server.md`

## Scope and status

Attempted Task 1 only: the test-tree-only stateful Todoist world and its adapter
scenario presets. Plan checkboxes were intentionally not changed; the parent controller
owns plan progress.

Task 1 steps completed:

- Wrote the required eight focused tests before implementation.
- Confirmed the RED failure was the expected missing `e2eTodoistAdapter` module.
- Implemented `E2ETodoistWorld` and `E2ETodoistAdapter` directly against
  `TodoistAdapter` with the two Phase 1 fault presets.
- Ran focused, type, and full backend verification.
- Reviewed the touched-file scope and whitespace before staging.

## Files changed

- `backend/test/fakes/e2eTodoistAdapter.ts` (new)
- `backend/test/fakes/e2eTodoistAdapter.test.ts` (new)
- `docs/handoffs/2026-07-20-real-backend-e2e-task1.md` (new)

No production backend code, existing fake adapter, existing tests, fixture server,
scripts, client, CI, or plan file was changed.

## Verification

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 1 test file, 8 tests passed.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` completed with no diagnostics, including
    the direct `TodoistAdapter` implementation.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 29 test files and 337 tests passed.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

## TDD evidence

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: FAIL (expected RED)
  - Evidence: Vitest reported `Cannot find module './e2eTodoistAdapter.js'` from the
    new test file before any tests executed.

## Manual verification

- Inspected the final adapter against the current `TodoistAdapter` and
  `TodoistMutationGateway` interfaces. Every interface method is present and all
  mutation entry points invoke the common scenario guard before changing the world.
- Confirmed the adapter returns defensive clones from `load`, `state`, `getTask`, and
  non-lagging `sync`; no backend snapshot, replay, checklist, or reconciliation logic
  was added to the world.

## Assumptions

- The plan's Todoist-side recurrence approximation is intentional: daily/default is
  `+1` day, weekday-compatible rules are `+7`, monthly is `+30`, and yearly is `+365`.
- Legacy adapter methods map to their world counterparts solely for interface and
  future E2E-server compatibility; Task 1 adds no server wiring.

## Known failures or skipped checks

- Android and real-port E2E verification are not applicable until Tasks 2 through 9
  add fixtures, server wiring, and the execution script.
- No failures are known within Task 1 scope.

## Follow-up review fix

A Task 1 spec review found that legacy `updateTask` moved a task even when its
`moveCommandUuid` was absent. The real `HttpTodoistAdapter` makes that move only when
the identity is supplied. The E2E adapter now applies the same conditional behavior.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: FAIL (expected RED)
  - Evidence: the new legacy-update test observed `project-personal` after an update
    without `moveCommandUuid`, where `project-test` was required.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 1 test file and 9 tests passed; the test also confirms that a supplied
    `moveCommandUuid` still moves the task.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: no TypeScript diagnostics.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 29 test files and 338 tests passed.

## Follow-up fidelity fixes

A code-quality review found three Todoist-boundary fidelity gaps in the Task 1 world.
All remain test-tree-only:

- Datetime due replacements and root additions now set `dueDate` to the same raw
  Todoist date value as `HttpTodoistAdapter`; clearing removes both date fields.
  Recurring completion advances an existing `dueDate` alongside `dueDatetime`.
- `durationMinutes` now follows production normalization: minutes remain unchanged,
  hours multiply by 60, and unsupported units yield `null`.
- Accepted command UUIDs are recorded after a successful mutation. Replays return the
  stored result (including the original `itemAdd` ID) without mutating state; a timeout
  is guarded before ledger insertion, so retrying its UUID after reset executes.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: FAIL (expected RED)
  - Evidence: 4 expected failures exposed missing root `dueDate`, raw hour duration,
    duplicate `itemAdd` ID allocation, and stale recurring `dueDate` behavior.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 1 test file and 12 tests passed, including the new due, duration,
    idempotence, replayed-close, and timeout-retry coverage.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: no TypeScript diagnostics.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 29 test files and 341 tests passed.

## Follow-up reset lifecycle fix

`E2ETodoistWorld.load()` is used by the planned E2E reset flow while the adapter
instance is retained. The accepted-command ledger now observes a world-load generation
and clears itself before the next command when the world is replaced. This keeps the
world responsible only for its own lifecycle and the adapter responsible for its replay
ledger.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: FAIL (expected RED)
  - Evidence: replaying a prior `itemAdd` UUID after a fresh `world.load` returned its
    stale result while the new world contained zero tasks.

Verification:

- Command: `cd backend && npx vitest run test/fakes/e2eTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 1 test file and 13 tests passed; the replay now adds a valid task to the
    reloaded world.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: no TypeScript diagnostics.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 29 test files and 342 tests passed.

## Next recommended step

Proceed with Task 2 in the same isolated worktree: add the fixture seed module and its
focused tests, leaving this adapter and all existing fake-adapter call sites untouched.
