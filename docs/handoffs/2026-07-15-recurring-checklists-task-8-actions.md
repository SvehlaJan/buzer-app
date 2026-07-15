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
