# Handoff: Recurring Checklists Task 5 Occurrence Reconciliation

Date: 2026-07-15
Plan: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
Task: Task 5 — Reconcile Occurrence Identity And Per-Occurrence Accountability
Branch: `feature/recurring-checklists-task3`

## Status And Attempted Steps

- All Task 5 checkboxes are complete, including the final commit; the P1 ownership follow-up below was amended into that commit.
- Added a pure occurrence reconciler with opaque random-key rotation decisions and a stable normalized recurrence fingerprint.
- Sync is serialized in-process, reads its token inside that queue, advances the token in the same mirror transaction, and marks mirror contract v2 only after a successful full sync.
- External recurring advances with checked eligible direct children enter pending reconciliation, reserve a deterministic system journal action and per-child Todoist `item_uncomplete` commands, then immediately synchronize again before rotating.
- Rotation resets all per-occurrence enforcement fields and increments the schedule version. Leaving configured-user ownership clears active enforcement state and increments the schedule version; returning ownership rotates a new occurrence.
- WhatsApp claims now use `(task_id, occurrence_key)`, so a migrated current occurrence remains claimed while a rotated occurrence can claim once.
- Authorized minimal scope expansions were required for product correctness:
  - `backend/src/server.ts` passes `currentUserId` into `TodoistSyncService`, allowing sync to distinguish this user from any other Todoist assignee.
  - `TaskSnapshotService`, `TaskActionService`, and route tests/routes enforce `503 sync_initializing` before contract v2 while `/health` stays available. Existing route fixtures that exercise normal mutations now explicitly seed a v2 mirror state.

## Files Changed

- `backend/src/services/occurrenceReconciler.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/src/services/escalationService.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/src/services/taskSnapshotService.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/http/routes.ts`
- `backend/src/server.ts`
- `backend/test/services/occurrenceReconciler.test.ts`
- `backend/test/services/todoistSyncService.test.ts`
- `backend/test/services/escalationService.test.ts`
- `backend/test/services/taskSnapshotService.test.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/http/routes.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-5-occurrences.md`

## TDD Evidence

Verification:
- Command: `npm test -- test/services/occurrenceReconciler.test.ts`
- Result: FAIL (expected RED)
- Evidence: module import failed because `occurrenceReconciler` did not exist.

Verification:
- Command: `npm test -- test/services/occurrenceReconciler.test.ts test/services/todoistSyncService.test.ts test/services/escalationService.test.ts`
- Result: FAIL (expected RED)
- Evidence: four intended failures covered contract-v2 readiness, concurrent token reuse, one-time rotation/reset, and per-occurrence escalation claims.

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts`
- Result: FAIL (expected RED)
- Evidence: an externally advanced recurring root sent child uncomplete but performed no follow-up authoritative sync.

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts`
- Result: FAIL (expected RED)
- Evidence: leaving ownership retained started/dismiss/extension/snooze/escalation state and its schedule version.

Verification:
- Command: `npm test -- test/services/taskSnapshotService.test.ts test/services/taskActionService.test.ts test/http/routes.test.ts`
- Result: FAIL (expected RED)
- Evidence: fresh mirror snapshots and mutations returned existing values/errors instead of `503 sync_initializing`.

## Final Verification

Verification:
- Command: `npm test -- test/services/occurrenceReconciler.test.ts test/services/todoistSyncService.test.ts test/services/escalationService.test.ts test/services/taskSnapshotService.test.ts test/services/taskActionService.test.ts test/http/routes.test.ts`
- Result: PASS
- Evidence: 6 test files and 62 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 24 test files and 197 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors in the final Task 5 diff.

## Manual Verification

- Reviewed Todoist's current `item_uncomplete` command requirement (`type`, caller UUID, and task `id`) and Sync API token flow through Context7.
- Reviewed `better-sqlite3` transaction/savepoint behavior through Context7; mirror/token updates remain synchronous inside one transaction and Todoist calls happen only after it commits.
- No live Todoist, WhatsApp, VPS, Android, or production database mutation was performed.

## Known Failures Or Skipped Checks

- Live Todoist recurrence/reset smoke test: SKIPPED; no disposable external Todoist project was authorized.
- Android/client verification: SKIPPED; this task changes backend synchronization, state, and HTTP readiness behavior only; it does not modify `client/` UI.
- Build: SKIPPED; Task 5 requires focused tests, typecheck, and the full backend suite; packaging configuration was unchanged.

## Assumptions And Next Step

- `TaskActionCoordinator` (Task 6) will supply explicit editor-replan/extend sources to the reconciler; Task 5 establishes and tests those exact pure decisions without widening legacy action behavior.
- Proceed with Task 6 only in a separately assigned session after the final Task 5 commit.

## P1 Ownership Review Follow-up

- Corrected the sync ownership predicate to use `TaskEnforcementPolicy.isCompanionOwned` with the current active-project map, plus direct assignment to the configured user. This matches snapshot policy: a persisted companion owner does not override a non-null assignment to another user, while a self-created task in a nonassignable personal project remains owned without a persisted owner ID.

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: the two new regressions initially retained enforcement after reassignment and skipped occurrence initialization for the personal-project task; after the policy-backed predicate, 11/11 sync tests passed.

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts test/domain/taskEnforcementPolicy.test.ts test/services/taskSnapshotService.test.ts test/http/routes.test.ts`
- Result: PASS
- Evidence: 4 test files and 51 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 24 test files and 198 tests passed.

## Reconciliation Review Follow-up

- Reconciliation now captures both the pre-sync task/project-policy snapshot and the post-sync policy, then evaluates every live root in the authoritative mirror. This lets a child-only incremental response confirm a pending recurring rollover, and lets project capability changes affect roots even when Todoist does not include an item update.
- Added regressions for a child-only reset confirmation, a personal-project capability change with no task item (leave ownership), and a capability change with a task item (ownership returned and occurrence rotation).

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: child-only confirmation left the root pending, project-only capability changes retained started enforcement, and project-return rotation retained the old key; after snapshot-based root reconciliation, 13/13 sync tests passed.

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts test/domain/taskEnforcementPolicy.test.ts test/services/taskSnapshotService.test.ts test/services/taskActionService.test.ts test/http/routes.test.ts`
- Result: PASS
- Evidence: 5 test files and 68 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 24 test files and 200 tests passed.

## Bounded Child-Reset Confirmation Follow-up

- The immediate post-uncomplete confirmation is now a single, non-resetting sync pass. If Todoist returns an empty or lagged delta and the local mirror still has checked children, the root remains `pending` and `syncOnce()` returns. A later normal poll/action sync can retry reconciliation; no scheduler or recursive retry loop was added.

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: the new empty-confirmation regression initially reached a third recursive sync and rejected with `unexpected_recursive_sync`; after bounding the follow-up, 14/14 sync tests passed with two sync calls, one command, and a pending root.

Verification:
- Command: `npm test -- test/services/todoistSyncService.test.ts test/services/escalationService.test.ts test/services/taskSnapshotService.test.ts test/services/taskActionService.test.ts test/http/routes.test.ts`
- Result: PASS
- Evidence: 5 test files and 55 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 24 test files and 201 tests passed.
