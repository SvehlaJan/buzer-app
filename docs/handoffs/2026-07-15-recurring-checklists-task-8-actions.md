# Handoff: Recurring Checklists Task 8 Root Action Safety

Date: 2026-07-15
Task: Task 8 — Root create/edit/done/reschedule/extend occurrence safety
Branch: `feature/recurring-checklists-task3`

## Status And Attempted Steps

- Attempted the Task 8 requirements supplied by the controller. Per controller instruction, I did not read the implementation plan file; therefore I did not directly edit its checkboxes.
- Added backend-owned create recurrence translation and IANA/checklist-cap validation; root creation now uses the explicit Sync `item_add` gateway, with optional draft children.
- Changed Extend +30 to preserve the Todoist block start, increase duration by 30 minutes, append only the managed Buzer activity entry, retain the occurrence, and reject recurring extension.
- Changed reschedule to use explicit `item_update`, preserve/rebuild supported recurring rules in the Todoist time zone, reject custom schedule changes, preserve managed descriptions, and rotate the occurrence.
- Changed edit to use independent `item_move` then `item_update` commands, make null assignment explicit, round-trip the Buzer managed description, and preserve lifecycle for metadata-only edits while rotating schedule or ownership-return changes.
- Changed Done to enforce direct-child completion/hierarchy locally and issue `item_close` for one-time roots or `item_update_date_complete` for recurring roots.
- Added coordinator recovery coverage showing a confirmed `item_move` is not resent after interruption while the unfinished `item_update` is sent.

## Files Changed

- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskActionCoordinator.ts`
- `backend/test/services/taskActionService.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-8-actions.md`

## Required Reads And Research

- Read `AGENTS.md`, approved MVP spec, recurring/checklist design, and Task 5–7 handoffs before edits; worktree was clean at start.
- Context7 lookup completed for current Todoist Sync command semantics. It confirmed dedicated `item_move`, `item_update`, `item_close`, and recurring `item_update_date_complete` command behavior; recurring completion includes `reset_subtasks: true`, and explicit unassignment uses null `responsible_uid`.

## TDD Evidence

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts`
- Result: FAIL (expected RED)
- Evidence: the new Extend test observed no `item_update`; the old implementation issued a legacy reschedule and changed the due start.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: the one-time extension now emits one preserve-due `item_update`, reaches 90 minutes, and appends the single activity line.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts`
- Result: FAIL (expected RED)
- Evidence: new recurring root-creation test found no Sync root `item_add` command.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: create emits the backend-computed `every day at 10:30` recurrence with `due_lang: en` and `Europe/Bratislava` time zone.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts`
- Result: FAIL (expected RED)
- Evidence: recurring reschedule initially emitted no explicit `item_update`; incomplete checklist children could also be closed by Done.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts`
- Result: PASS (GREEN)
- Evidence: supported recurrence reschedule preserves the rule/time zone and rotates the occurrence; incomplete direct children return `children_incomplete` before any root completion command.

## Final Verification

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
- Result: PASS
- Evidence: 3 files, 62 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm test`
- Result: FAIL
- Evidence: 276/281 tests passed. Five existing `test/http/routes.test.ts` assertions still inspect retired legacy fake-adapter arrays (`createdTasks`, `reschedules`, `updates`) rather than the Task 8 explicit gateway calls. No HTTP route code was modified, per Task 8 scope.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: build TypeScript compilation and schema copy completed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification, Assumptions, And Remaining Work

- Manual verification: inspected Sync command construction already covered by the focused adapter suite: `item_update` omits due under preserve, explicit unassignment serializes null, moving is a separate command, and recurring completion hard-codes child reset.
- Assumption: HTTP Task 9 will migrate route-level expectations and action-metadata parsing to the explicit command/action surfaces; this task deliberately changed no route schema or handler.
- Known gap: root actions are not yet all wired through `TaskActionCoordinator` from `TaskActionService`; the coordinator foundation does persist/recover separate move/update steps (covered here), but broad root action metadata wiring and immediate authoritative synchronizer/snapshot response remain needed to satisfy the full Task 8 durability matrix.
- Known skip: no live Todoist, VPS, WhatsApp, Android, or client checks were run.

## Next Recommended Step

Resolve the controller-approved remaining Task 8 root-action coordinator wiring and migrate the five route test expectations together with Task 9 HTTP action schemas. Do not treat the current branch as fully Task 8 complete until the full backend suite is green.

## Coordinator-Wiring Follow-up

- Action-aware root create, edit, done, reschedule, and extend calls now use `TaskActionCoordinator` command contexts. A caller that supplies either action metadata field must supply both; those calls reject unless server-scoped `rootActions` coordination is present. Metadata-free calls remain the deliberately narrow Task 9 compatibility bridge.
- Root creation can reserve its durable action before Todoist assigns a root item ID. The coordinator now supports this bounded absent-root phase, and the root is added before post-command effect confirmation.
- Action-aware edits reserve `item_move` and `item_update` as separate durable steps. An interruption after the move recovers without repeating the move and sends only the unfinished update.
- Done now re-reads and validates the root tree inside the coordinator execution closure, after its authoritative pre-command synchronization. This prevents stale direct-child state from being used as the completion gate.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts`
- Result: PASS
- Evidence: 44 tests passed, including action-aware root creation and an interrupted move/update service replay.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
- Result: PASS
- Evidence: 3 files, 63 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript exited 0.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: production build and schema copy completed.

Verification:
- Command: `npm test`
- Result: FAIL (expected, unchanged Task 9 migration boundary)
- Evidence: exactly five `test/http/routes.test.ts` legacy-array assertions failed; all other 277 tests passed. The failures are `handles task action routes and device check-in`, `accepts omitted assignee for non-assignable project creation`, `passes trimmed create descriptions to Todoist`, `passes reschedule descriptions through with omitted, null, blank, and set semantics`, and `updates full task edit payload through update route`.

## Checkpoint Completion Follow-up

- Preserved the prior uncommitted coordinator wiring and verified it before edits: root create/edit/done/reschedule/extend use the action-aware coordinator when supplied action/device metadata, reserve durable action records under the root mutex, and reserve independent `item_move` and `item_update` steps.
- Corrected a concrete action-aware create recovery flaw found with new RED tests. Root and checklist-child `temp_id` values now derive deterministically from the action ID and step key, are persisted in `task_action_commands`, and are reused together with the persisted command UUID after an ambiguous send.
- Corrected a second recovery flaw: a root that exists after a successful root add no longer falsely confirms a create action while an interrupted checklist-child add remains unfinished. Replay restores the confirmed root command result and dispatches only the missing child step.
- Updated only the Task 8 plan checkboxes for implemented service behavior and passing focused/typecheck gates. The four unchecked Task 8 scenario-test checklist items remain intentionally visible because their full enumerated matrices were not added in this checkpoint.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts`
- Result: PASS
- Evidence: 27 tests passed, including RED→GREEN recovery coverage for root and child `temp_id` replay.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
- Result: PASS
- Evidence: 3 files, 65 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: production TypeScript build and schema copy completed.

Verification:
- Command: `npm test`
- Result: FAIL (known Task 9 boundary only)
- Evidence: 279/284 tests passed. Exactly five unchanged `backend/test/http/routes.test.ts` assertions inspect the retired fake-adapter `createdTasks`, `reschedules`, or `updates` arrays; routes returned HTTP 200 and were not changed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Manual verification:
- Inspected the preserved journal entries during the new recovery tests: `item_add` and `child_add:0` retain their persisted UUID/temp-ID identity across retry; confirmed steps are not sent again.

Assumptions and remaining concerns:
- Task 9 owns route schema/metadata parsing and migration of the five HTTP legacy-array assertions; no route, route-test, or fake compatibility mirroring was changed here.
- Task 8's exhaustive scenario-test bullets for every listed Done/reschedule/extend/ownership permutation remain unchecked in the plan, even though the service behavior and focused coverage above are present. Add those matrices only if Task 8 is reopened; do not let them expand into Task 9 route work.

Next recommended step:
- Begin Task 9's authenticated route/schema migration, including the five legacy FakeTodoistAdapter assertion updates, from this committed checkpoint.

## Task 8 Spec-Review Corrections

Plan/task attempted:
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`, Task 8 review corrections only. No Task 9 routes, schemas, or fake-adapter compatibility work was started.

Changes made:
- `TaskActionService.update` now includes supplied checklist mutations in its durable root-action payload and executes them through the existing `TodoistChecklistService` using a deterministic derived action ID, the same root coordinator/mutex, and the existing partial child-outcome result shape.
- Action-aware edit now requires both expected occurrence and task revision; action-aware Done and reschedule require expected occurrence. Missing metadata rejects before any command.
- Recurring Done only confirms after a post-command synchronization exposes a rotated occurrence key and changed occurrence due datetime. It no longer treats command issuance as confirmation.
- Extend performs its local duration, lifecycle, and extension-count transaction only after coordinator completion; a reconciliation-pending response leaves local state unchanged.
- An action-aware project move defers local root writes and requires post-sync confirmation of the root plus the exact pre-move direct-child set, including parent linkage, target project, and schedule-free/assignment-free child invariants. Missing evidence remains reconciliation-pending.
- Create validates recurrence presets and every child title before action reservation or root `item_add`; a missing Todoist child ID is a 502 `todoist_child_create_unconfirmed`, never a silent omission.

Files changed:
- `backend/src/services/taskActionService.ts`
- `backend/test/services/taskActionService.test.ts`
- `docs/handoffs/2026-07-15-recurring-checklists-task-8-actions.md`

TDD evidence:
- RED: `npm test -- test/services/taskActionService.test.ts`
  - Result: FAIL (4 focused regressions)
  - Evidence: create sent a root before draft validation; coordinated Extend mutated local state before pending; recurring Done accepted an in-memory command-issued flag; action-aware edit accepted omitted revision/occurrence metadata.
- GREEN: `npm test -- test/services/taskActionService.test.ts`
  - Result: PASS except one existing action-aware edit fixture missing its newly required revision token; fixture was migrated to the stricter contract.
- RED: `npm test -- test/services/taskActionService.test.ts`
  - Result: FAIL (2 focused regressions)
  - Evidence: supplied `checklistMutations` returned `undefined` without dispatching a child action; project move journal completed despite a direct child still reflecting the old project.
- GREEN: `npm test -- test/services/taskActionService.test.ts`
  - Result: PASS
  - Evidence: 35 tests passed, including next-occurrence confirmation, unconfirmed child creation, composed checklist mutation outcomes, project-tree confirmation, and deferred Extend state.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 3 files, 73 tests passed.
- Command: `npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` exited 0.
- Command: `npm run build`
  - Result: PASS
  - Evidence: production TypeScript build and schema copy completed.
- Command: `npm test`
  - Result: FAIL (known Task 9 boundary only)
  - Evidence: 287/292 tests passed. The same five `backend/test/http/routes.test.ts` legacy fake-array assertions failed: `handles task action routes and device check-in`, `accepts omitted assignee for non-assignable project creation`, `passes trimmed create descriptions to Todoist`, `passes reschedule descriptions through with omitted, null, blank, and set semantics`, and `updates full task edit payload through update route`.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

Manual verification:
- Inspected the action-aware move replay fixture: its simulated authoritative sync now updates the mirror only after the unfinished `item_update`, proving that the journal no longer completes from the service's optimistic local mutation.

Assumptions and remaining work:
- Task 9 owns the five route-test migrations and route parsing for action metadata; these corrections deliberately do not alter that boundary.
- Production synchronization remains responsible for applying Todoist's next recurring occurrence and moved child tree to the mirror. The service only confirms after that mirror evidence is present.

Next recommended step:
- Commit this Task 8 review-correction checkpoint, then resume Task 9 route/schema migration from the known five-test boundary.

## Task 8 Final Confirmation And Tree-Validation Corrections

Plan/task attempted:
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`, Task 8 follow-up only. No Task 9 route, schema, or fake-adapter changes were made.

Changes made:
- One-time Done no longer writes `completed_at` or lifecycle state inside the action command executor. Its coordinator effect requires mirror evidence of both a Todoist completion timestamp and completed lifecycle state; a failed/non-observing post-command sync remains `reconciliation_pending` without blocking the occurrence.
- Replaced the fragmented Done and project-move hierarchy predicates with one `directChildTree` validator. It validates the complete typed direct-child contract: same project as root; no date, datetime, rule, language, or timezone; no recurrence; no duration amount/unit/minutes; unassigned; no active grandchild; and at most 30 direct children.

Files changed:
- `backend/src/services/taskActionService.ts`
- `backend/test/services/taskActionService.test.ts`
- `docs/handoffs/2026-07-15-recurring-checklists-task-8-actions.md`

TDD evidence:
- RED: `npm test -- test/services/taskActionService.test.ts`
  - Result: FAIL (2 regressions)
  - Evidence: a failed second sync after `item_close` had already set `completedAt` and lifecycle to completed; Done also accepted a checked direct child with only `dueString`/`dueLang`/`dueTimezone` populated.
- GREEN: `npm test -- test/services/taskActionService.test.ts`
  - Result: PASS
  - Evidence: 37 tests passed. The pending one-time close preserves planned state and occurrence key, while the non-datetime scheduled child returns `unsupported_hierarchy` without `item_close`.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 3 files, 75 tests passed.
- Command: `npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` exited 0.
- Command: `npm run build`
  - Result: PASS
  - Evidence: production TypeScript build and schema copy completed.
- Command: `npm test`
  - Result: FAIL (known Task 9 boundary only)
  - Evidence: 289/294 tests passed. The unchanged failures are exactly the five legacy fake-array assertions in `backend/test/http/routes.test.ts`: `handles task action routes and device check-in`, `accepts omitted assignee for non-assignable project creation`, `passes trimmed create descriptions to Todoist`, `passes reschedule descriptions through with omitted, null, blank, and set semantics`, and `updates full task edit payload through update route`.

Manual verification:
- Inspected the coordinator path: it runs synchronization after the close command before evaluating `hasAuthoritativelyCompleted`; only a synchronized mirror state can satisfy that predicate.

Assumptions and remaining work:
- The synchronizer continues to own the authoritative transition of a Todoist-completed one-time root to `completedAt` plus completed lifecycle. The service intentionally does not synthesize that evidence in an action-aware call.
- Task 9 remains responsible for the known HTTP legacy-array migration; this follow-up leaves that boundary unchanged.

Next recommended step:
- Commit this final Task 8 correction checkpoint, then resume Task 9 from the five documented route-test migrations.

## Uncommitted Root-Action Durability Follow-up

Plan/task attempted:
- Task 8 quality follow-up: mandatory root metadata/coordinator, sync-reconstructed create recovery, and removal of optimistic create/reschedule/non-move-edit mirror writes. No Task 9 files were changed.

Current uncommitted files:
- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskActionCoordinator.ts`
- `backend/src/repositories/actionJournalRepository.ts`
- `backend/test/services/taskActionService.test.ts`

Implemented and verified in targeted tests:
- Root mutations now reject absent action metadata and no longer generate an unjournaled command UUID.
- Action-aware create, reschedule, and non-move edit stay reconciliation-pending under a no-op sync and leave their mirror/occurrence state unchanged.
- Create replay can resolve the Todoist ID persisted in its confirmed command result, locate an authoritative sync-created root with a different local ID, rebind `task_action_journal.root_task_id`, and return that actual local ID.

TDD evidence:
- RED: `npm test -- test/services/taskActionService.test.ts`
  - Result: FAIL
  - Evidence: metadata-free create succeeded; action-aware create/reschedule/non-move edit confirmed using local writes.
- GREEN (new regression subset): `npm test -- test/services/taskActionService.test.ts --testNamePattern "adopts a sync-reconstructed root|rejects metadata-free root creation|keeps action-aware create pending|keeps action-aware reschedule pending|keeps action-aware non-move edit pending"`
  - Result: PASS
  - Evidence: 5 tests passed.

Verification:
- Command: `npm run typecheck`
  - Result: PASS
- Command: `npm run build`
  - Result: PASS
- Command: `npm test -- test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 38 tests passed.
- Command: `npm test`
  - Result: FAIL
  - Evidence: 281/299 passed. In addition to the known five Task 9 HTTP route failures, 13 legacy `taskActionService` tests still invoke now-forbidden metadata-free root actions or expect optimistic mirror writes. They must be migrated to authoritative-sync fixtures before this work can be committed.

Manual verification:
- The recovery regression asserts the journal root association changes to `sync-root` after the retry's authoritative sync.

Next recommended step:
- Migrate the 13 listed legacy TaskActionService fixtures to action metadata plus deterministic authoritative-sync setup, then rerun the combined focused suite, full backend status, and commit. Do not reintroduce a metadata-free production fallback.

## Root-Action Durability Completion

Plan/task attempted:
- Completed the Task 8 root-action durability quality follow-up and marked its dedicated Task 8 plan checkbox. No Task 9 routes or fakes changed.

Changes made:
- Removed the metadata-free root-command UUID fallback. Create/edit/done/reschedule/extend now require action/device metadata and the server-scoped root coordinator.
- Removed optimistic mirror writes from action-aware create, reschedule, non-move edit, and the remaining post-Extend state path. No-op synchronization produces `reconciliation_pending` rather than a synthetic success.
- Added create root reassociation: the coordinator can rebind its journal root ID after sync discovers the Todoist ID recorded by confirmed `item_add`, including a different local mirror ID after process recovery.
- Migrated all 13 legacy `TaskActionService` fixtures to action metadata and explicit no-op authoritative-sync expectations. They assert emitted Todoist commands plus pending/no-local-write behavior rather than using the removed direct path.

Files changed:
- `backend/src/repositories/actionJournalRepository.ts`
- `backend/src/services/taskActionCoordinator.ts`
- `backend/src/services/taskActionService.ts`
- `backend/test/services/taskActionService.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-8-actions.md`

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 3 files, 80 tests passed.
- Command: `npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` exited 0.
- Command: `npm run build`
  - Result: PASS
  - Evidence: production TypeScript build and schema copy completed.
- Command: `npm test`
  - Result: FAIL (Task 9 boundary only)
  - Evidence: 294/299 tests passed. Exactly five `backend/test/http/routes.test.ts` legacy route fixtures fail because they do not yet supply the now-mandatory action metadata/coordinator wiring: `handles task action routes and device check-in`, `accepts omitted assignee for non-assignable project creation`, `passes trimmed create descriptions to Todoist`, `passes reschedule descriptions through with omitted, null, blank, and set semantics`, and `updates full task edit payload through update route`.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

Manual verification:
- The crash-reconstruction regression verifies both returned `sync-root` identity and the durable journal `root_task_id` reassignment after retry.

Next recommended step:
- Task 9 should add authenticated route action metadata parsing and the server coordinator dependencies, then migrate the five route fixtures. Do not weaken root-action durability for compatibility.

## Root Lifecycle And Canonical-Response Follow-up

Plan/task attempted:
- Completed the Task 8 quality follow-up for Start/Postpone coordination, Extend finalization, and canonical create responses. No Task 9 files changed.

Changes made:
- Start and Postpone now require action/device/occurrence metadata and execute through the durable coordinator. Postpone's state increment and escalation run in the journaled executor, so a completed action replay does not double-count or re-escalate.
- The coordinator supports an idempotent post-confirmation finalizer. Extend uses it to set started state, increment the extension counter, and advance schedule version exactly once after authoritative duration confirmation.
- Create requests opt into an authoritative completion response, returning the canonical sync-reconstructed local root ID rather than the provisional deterministic ID.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/todoist/httpTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 3 files, 80 tests passed.
- Command: `npm run typecheck`
  - Result: PASS
- Command: `npm run build`
  - Result: PASS
- Command: `npm test`
  - Result: FAIL (Task 9 boundary only)
  - Evidence: 293/299 passed. Six route fixtures now await Task 9 metadata/coordinator wiring, the previous five plus `sends escalation through postpone route on second start postpone`.
- Command: `git diff --check`
  - Result: PASS

Next recommended step:
- Task 9 must wire action metadata/coordinator for Start and Postpone as well as the existing root mutation routes; do not restore metadata-free calls.
