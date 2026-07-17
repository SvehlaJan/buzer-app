# Handoff: Recurring Checklists Task 6 Action Coordination

Date: 2026-07-15
Plan: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
Task: Task 6 — Add Durable Root Serialization And Replay Coordination
Branch: `feature/recurring-checklists-task3`

## Status And Attempted Steps

- All Task 6 plan checkboxes are complete.
- Added an in-process root-keyed promise mutex. It preserves per-root submission order, permits different roots to overlap, releases after rejection, and removes inactive root entries.
- Added `TaskActionCoordinator`, which reserves/replays durable action records, synchronizes and validates root revision/occurrence readiness under the mutex, persists command UUIDs before sends, confirms after sync, and returns a pending result for reconciliation uncertainty.
- Replays after a completed action return the journal result without a command or counter update. Timeout retries synchronize first and reuse the previously persisted command UUID only if the effect is still absent.
- Sync failure after a successful command records compact pending context in the journal and marks the root pending; a later effect-confirming sync completes the same action without resending and clears the root pending state.
- The action fingerprint is SHA-256 over canonical JSON containing only action type and domain payload. An explicit `compactResponse` callback prevents full action responses, including task descriptions, from being journaled.
- Added typed `DomainError` factories and a repository operation to confirm a root reconciliation after retry.

## Files Changed

- `backend/src/services/rootTaskMutex.ts`
- `backend/src/services/taskActionCoordinator.ts`
- `backend/src/repositories/actionJournalRepository.ts`
- `backend/src/domain/errors.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/test/services/rootTaskMutex.test.ts`
- `backend/test/services/taskActionCoordinator.test.ts`
- `backend/test/domain/errors.test.ts`
- `backend/test/repositories/taskRepository.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-6-action-coordination.md`

## TDD Evidence

Verification:
- Command: `npm test -- test/services/rootTaskMutex.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: the initial import failed because `rootTaskMutex` did not exist; after implementation 4/4 mutex tests passed.

Verification:
- Command: `npm test -- test/services/taskActionCoordinator.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: the initial import failed because `taskActionCoordinator` did not exist; recovery, replay, two-phone, and compact-response scenarios now pass 11/11.

Verification:
- Command: `npm test -- test/domain/errors.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: all eight required factory calls initially failed as missing and then returned their typed code/status/snapshot context.

Verification:
- Command: `npm test -- test/repositories/taskRepository.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: `confirmOccurrenceReconciliation` was initially missing; the repository suite now passes 8/8.

## Final Verification

Verification:
- Command: `npm test -- test/services/rootTaskMutex.test.ts test/services/taskActionCoordinator.test.ts`
- Result: PASS
- Evidence: 2 test files and 15 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 with no diagnostics.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 27 test files and 225 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Quality Follow-up: Pending Root And Command Result Recovery

- Every `pending` result now calls the root pending-state writer, including a replay where the
  journal was already `reconciliation_pending`. An unrelated state refresh therefore cannot leave
  an unresolved journal entry paired with a root marked confirmed.
- Confirmed command records now persist a tagged JSON result: a void command is represented
  explicitly and restores as `undefined`, while a typed compact value (for example a Todoist item
  ID from an add command) restores exactly. The coordinator no longer writes or returns the
  `{ confirmed: true }` sentinel.

Verification:
- Command: `npm test -- test/services/taskActionCoordinator.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: a pending replay initially left the root confirmed, and crash recovery received no
  Todoist ID; after reassertion and typed result envelopes, 18/18 coordinator tests passed with no
  resend.

Verification:
- Command: `npm test -- test/services/rootTaskMutex.test.ts test/services/taskActionCoordinator.test.ts`
- Result: PASS
- Evidence: 2 files and 22 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 27 files and 232 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## P1 Follow-up: Timeout Effect-Present Recovery

- `responseAfterReconciliation` is now required on every coordinated action. It provides the
  durable/replay response when a prior external command is later confirmed by synchronization.
- The command context also checks for that provider at runtime before reserving or dispatching a
  Todoist command, so untyped callers cannot create a sent action that has no recoverable response.
- A timeout whose later synchronization verifier reports the effect present now marks the action
  completed, persists the compact reconciliation response, and skips the action executor and any
  resend. The earlier `reconciliation_response_missing` throw path was removed.

Verification:
- Command: `npm test -- test/services/taskActionCoordinator.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: a provider-less action initially dispatched and returned pending instead of rejecting;
  after the pre-dispatch guard, the 16-test coordinator suite also confirms timeout→present replay
  returns/stores the provider response without resending.

Verification:
- Command: `npm test -- test/services/rootTaskMutex.test.ts test/services/taskActionCoordinator.test.ts`
- Result: PASS
- Evidence: 2 files and 20 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 27 files and 230 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## P1 Follow-up: Conservative Effect Verification

- A sent external command now completes only when its action supplies `isEffectPresent` and the
  post-send authoritative sync reports that effect as present. The coordinator no longer treats a
  missing verifier as proof of success.
- Action-journal inspection now distinguishes dispatched (`sent` or locally `confirmed`) commands.
  A replay with a dispatched command and no verifier returns the persisted pending result without
  executing or reissuing a Todoist command.
- A timeout replay may reissue its durable UUID only when its verifier returns false after the
  retry synchronization, which is the explicit proof of absence. A present verifier completes
  without sending; an absent verifier permits the durable retry; an omitted verifier remains
  pending.

Verification:
- Command: `npm test -- test/services/taskActionCoordinator.test.ts`
- Result: FAIL (expected RED), then PASS (GREEN)
- Evidence: the new no-op post-send and verifier-less timeout retry tests initially completed and
  resent respectively; after the tri-state verification guard, 14/14 coordinator tests passed.

Verification:
- Command: `npm test -- test/services/rootTaskMutex.test.ts test/services/taskActionCoordinator.test.ts`
- Result: PASS
- Evidence: 2 files and 18 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 27 files and 228 tests passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification

- Reviewed serialization cleanup and confirmed the mutex is only in-process; SQLite action and command journal rows remain the durable recovery authority.
- Reviewed the replay paths: completed records return without sync/send, pending records synchronize before confirmation, and sent timeout records preserve the command UUID.
- No Todoist, WhatsApp, Android, VPS, or production database mutation was performed.

## Assumptions And Known Limits

- Task 6 intentionally defines the coordinator boundary only. Task 7 and Task 8 will supply concrete child/root action adapters and HTTP responses.
- Pending journal recovery returns the compact durable result. Future HTTP action adapters should use `compactResponse` for replay-safe metadata and obtain any full authoritative snapshot through their normal post-action projection path.
- The new `TaskRepository.confirmOccurrenceReconciliation` is the minimal persistence extension needed for Task 6 to clear a confirmed root pending state.

## Next Recommended Step

Proceed with Task 7 in a separate assigned session; do not modify Task 8 or routes as part of Task 6.

## Commit

- `feat: coordinate idempotent task actions`
