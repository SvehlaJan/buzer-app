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
