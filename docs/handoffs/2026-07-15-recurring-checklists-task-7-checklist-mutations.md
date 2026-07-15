# Handoff: Recurring Checklists Task 7 Checklist Mutations

Date: 2026-07-15
Plan: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
Task: Task 7 — Implement Checklist Creation, Editing, And Focused Check Actions
Branch: `feature/recurring-checklists-task3`

## Status And Attempted Steps

- All Task 7 implementation checkboxes are complete. The commit checkbox is updated after the commit below.
- Added `TodoistChecklistService` as the only product-service child mutation boundary, with the exact `ChildMutation` union from the plan.
- Adds inherit the root project and persist a minimal direct child mirror. Edit, delete, check, and uncheck use explicit Todoist commands and advance only the child mirror revision.
- Child mutations reject scheduling, assignment, parent, and descendant fields; validate the existing projected direct-child tree; enforce the 30-child cap; preserve successful batch commands; and retain every failed/stale draft outcome by `clientMutationId`.
- The optional Task 6 coordinator integration reserves one durable command step per child before its Todoist command, synchronizes before and after, and returns a refreshed snapshot. The legacy action service delegates checklist mutations to this dedicated service.
- Root field/edit/create semantics remain Task 8 as explicitly bounded by that plan task; Task 7 only appends children after a root exists.

## Files Changed

- `backend/src/services/todoistChecklistService.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/test/services/todoistChecklistService.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-7-checklist-mutations.md`

## TDD Evidence

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts`
- Result: FAIL (expected RED)
- Evidence: module import failed because `TodoistChecklistService` did not exist.

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts`
- Result: FAIL (expected RED)
- Evidence: the new 28-child batch regression rejected the second add and exposed projected-count double counting.

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts`
- Result: FAIL (expected RED)
- Evidence: the durable add regression returned `reconciliation_pending` because the post-command effect verifier did not recognize newly created children.

## Final Verification

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: PASS
- Evidence: 2 test files and 27 tests passed, covering add/edit/delete/check/uncheck, stale/different-child batch handling, forbidden fields, 30-item limit, idempotent requested state, concurrent children, command journaling, and refusal of metadata-bearing unjournaled actions.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 without diagnostics.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 28 backend test files and 244 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors before documentation/commit finalization.

## Manual Verification

- Inspected the exact Sync gateway method selection: `item_update`, `item_delete`, `item_close`, and `item_uncomplete`; no child mutation can supply a parent, due, duration, recurrence, assignee, or descendants.
- Inspected the durable action-journal rows generated for a two-child add batch: one confirmed step per child and no snapshot descriptions in the compact journal response.
- No live Todoist, WhatsApp, VPS, Android, or production database mutation was performed.

## Assumptions And Known Limits

- The service has a direct fallback only for legacy callers without action metadata. Any metadata-bearing request requires the Task 6 coordinator, preventing a retry-capable request from bypassing the durable action/command journal. The production mutation route planned in Task 9 supplies that coordinator.
- Root create/edit patch validation is intentionally not implemented here: the Task 8 API contract adds root fields and checklist drafts, then composes this service after the root command succeeds.

## Next Recommended Step

Proceed with Task 8 only. Keep all root create/edit/done/reschedule/extend semantics out of any follow-up checklist-only work.

## Review Corrections

Task: Task 7 spec-review corrections (no Task 8/root semantics or HTTP routes attempted).

### Changes

- Coordinated checklist effect verification now checks only durable command steps reserved for this action. A stale/rejected child before command is returned as a terminal child outcome and cannot turn a successfully dispatched sibling into `reconciliation_pending`.
- Completed coordinated replays rehydrate the current root and authoritative snapshot around the compact durable child-outcome response.
- `TaskActionService` accepts explicit checklist dependencies and requires coordinator, synchronizer, and snapshot providers for focused check/uncheck actions; it no longer silently delegates those production-facing actions through no-op synchronization.
- Runtime mutation validation now enforces the exact discriminated-union keys and required fields, rejecting all variant-crossing and unknown fields, including project and parent-ID fields.
- Direct-child hierarchy validation now rejects every mirrored due, recurrence, duration, and assignment fact, including `dueString`, `dueLang`, `dueTimezone`, and `durationUnit`.

### Files Changed

- `backend/src/repositories/actionJournalRepository.ts`
- `backend/src/services/taskActionCoordinator.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/services/todoistChecklistService.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/services/todoistChecklistService.test.ts`
- `docs/handoffs/2026-07-15-recurring-checklists-task-7-checklist-mutations.md`

### TDD Evidence

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: FAIL (expected RED)
- Evidence: 12 new regressions failed: a mixed stale/safe coordinated batch returned `reconciliation_pending`; compact replay omitted `root`; extra/missing union fields were accepted; typed `dueString`, `dueLang`, `dueTimezone`, and `durationUnit` facts bypassed hierarchy rejection; and the TaskActionService façade had not forwarded coordinator/sync/snapshot dependencies.

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: 2 files and 47 tests passed after the scoped corrections.

### Final Verification

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: PASS
- Evidence: 2 test files, 47 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 without diagnostics.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 28 backend test files and 264 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

### Manual Verification, Assumptions, And Next Step

- Manual verification: inspected that command-effect context is sourced from durable per-action command rows, so a pre-command stale mutation has no step to verify while a dispatched sibling remains verified.
- Assumption: nullable `description` remains the precise optional-description representation for `add` and `edit` variants; no additional fields are accepted.
- Known skips: no live Todoist, WhatsApp, Android, VPS, or HTTP-route mutation was performed; focused routes remain Task 9 scope.
- Next recommended step: continue with Task 8 only; use `TaskActionServiceOptions.checklist` when composing a future focused-action route so its coordinator and sync/snapshot providers are the production instances.

## Critical Recovery Correction

Task: Task 7 critical spec re-review correction (interrupted mixed child-batch replay only; no Task 8 work attempted).

### Changes

- The coordinator now supplies durable reserved command-step context to reconciliation response builders, not only effect verifiers.
- Checklist replay reconstructs outcomes by `clientMutationId`: effects from reserved sibling steps are completed only after verification; mutations without a durable command are revalidated locally and retain stale or exact local-validation failures. An otherwise unprovable no-command mutation is conservatively returned as failed.
- Added interrupted processing-journal regressions for both a stale child draft and a local title-validation failure alongside a confirmed safe sibling command.

### Files Changed

- `backend/src/services/taskActionCoordinator.ts`
- `backend/src/services/todoistChecklistService.ts`
- `backend/test/services/todoistChecklistService.test.ts`
- `docs/handoffs/2026-07-15-recurring-checklists-task-7-checklist-mutations.md`

### TDD Evidence

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts`
- Result: FAIL (expected RED)
- Evidence: a seeded processing action with one confirmed safe command replayed as `completed` and incorrectly changed the stale no-command child outcome to `completed`.

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts`
- Result: FAIL (expected RED)
- Evidence: after the first recovery fix, the no-command invalid-title draft remained failed but lost its `child_title_required` error code, returning the generic Todoist mutation failure instead.

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: 2 test files and 49 tests passed, including stale and local-failure interrupted mixed-batch replay cases.

### Final Verification

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 without diagnostics.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 28 backend test files and 266 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

### Manual Verification, Assumptions, And Next Step

- Manual verification: inspected the seeded processing journal to confirm only `child:child-two:check` was durable, while the stale/invalid first child had no command row. Replay preserved that distinction in its per-child result.
- Assumption: without a durable command and without a safely reproducible no-op, returning a generic failed outcome is safer than inferring external success.
- Known skips: no live Todoist, WhatsApp, Android, VPS, or HTTP-route mutation was performed; focused routes remain Task 9 scope.
- Next recommended step: proceed with Task 8 only.

## Code-Quality Correction: Mandatory Durable Checklist Actions

Task: Task 7 code-quality correction (mandatory action metadata/coordinator and normalized edit verification only; no Task 8/routes attempted).

### Changes

- Removed the direct Todoist mutation path from `TodoistChecklistService`: every product child mutation now requires action metadata and a durable coordinator, so fresh unjournaled command UUIDs cannot be issued.
- `TaskActionService` now enforces action metadata and coordinator/synchronizer/snapshot dependencies for every checklist mutation, not only focused check/uncheck actions.
- All normal checklist service tests now use coordinated metadata; the raw service is retained only to assert metadata/coordinator rejection and never executes an uncoordinated command.
- Effect verification normalizes edit titles with the same trimming rule used for Todoist send and mirror storage, so a whitespace-padded title completes and replays normally.
- Updated the old transport-failure batch unit scenario to a deterministic local validation failure: a sent command that throws remains durably uncertain by design, while a local failure remains a truthful partial outcome.

### Files Changed

- `backend/src/services/taskActionService.ts`
- `backend/src/services/todoistChecklistService.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/services/todoistChecklistService.test.ts`
- `docs/handoffs/2026-07-15-recurring-checklists-task-7-checklist-mutations.md`

### TDD Evidence

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts`
- Result: FAIL (expected RED)
- Evidence: all five metadata-free add/edit/delete/check/uncheck mutations executed successfully and emitted Todoist commands; the whitespace-padded coordinated edit ended in `reconciliation_pending` because effect verification compared the raw title.

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: 2 test files and 60 tests passed. New cases cover all five metadata-free variants at both service boundaries, a real coordinated synchronizer path, and immediate plus replayed whitespace-title edit completion.

### Final Verification

Verification:
- Command: `npm test -- test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: PASS
- Evidence: 2 test files and 60 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 without diagnostics.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` and schema copy completed successfully.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 28 backend test files and 277 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

### Manual Verification, Assumptions, And Next Step

- Manual verification: inspected the sole `send` helper and confirmed it can only invoke `TaskActionCommandContext.executeCommand`; no direct random-UUID Todoist command remains.
- Assumption: a transport exception after durable `sent` remains reconciliation-pending rather than becoming a failed child result, because external Todoist effect remains unknown; deterministic pre-command validation failures remain partial outcomes.
- Known skips: no live Todoist, WhatsApp, Android, VPS, or HTTP-route mutation was performed; focused routes remain Task 9 scope.
- Next recommended step: proceed with Task 8 only.
