# Handoff: Recurring Checklists Task 4 Hierarchy Projection

Date: 2026-07-15
Plan: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
Task: Task 4 — Classify And Project Root Commitments With Checklist Children
Branch: `feature/recurring-checklists-task3`

## Status And Attempted Steps

- All Task 4 checkboxes are complete. Task 5 was not started.
- Added a pure task-enforcement policy with the five specified classifications and the seven stable hierarchy violation codes.
- Added a pure hierarchy projector that returns roots only, ordered direct children, derived progress, the policy classification, and the hierarchy violation without writing SQLite or scheduling alarms.
- Moved current-user personal-project ownership normalization into the policy. `TaskSnapshotService` now consumes that policy method so a configured `commitment_owner_id` and a self-created task in a non-assignable personal project retain the existing companion-ownership behavior.
- Kept all hierarchy fields on the existing uniform task mirror; no table, schema, or checklist-only storage was added.
- Follow-up review added direct coverage for a child whose project differs from its root. The policy
  attaches `child_project_mismatch` to that root as `UNSUPPORTED_HIERARCHY`.
- Quality-review follow-up made the current tree deletion-aware: deleted direct children do not
  count toward the 30-child limit, do not create hierarchy violations, and do not appear in projected
  checklist progress. Completed children remain live and count as checked.

## Files Changed

- `backend/src/domain/taskEnforcementPolicy.ts`
- `backend/src/domain/taskValidation.ts`
- `backend/src/services/taskHierarchyProjector.ts`
- `backend/src/services/taskSnapshotService.ts`
- `backend/test/domain/taskEnforcementPolicy.test.ts`
- `backend/test/domain/taskValidation.test.ts`
- `backend/test/services/taskHierarchyProjector.test.ts`
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-4-hierarchy-projection.md`

## TDD Evidence

Verification:
- Command: `npm test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskHierarchyProjector.test.ts test/domain/taskValidation.test.ts`
- Result: FAIL (expected RED)
- Evidence: both new module imports failed with `Cannot find module`, and normalized companion ownership still produced `assignee_required_for_created_task`.

Verification:
- Command: `npm test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskHierarchyProjector.test.ts test/domain/taskValidation.test.ts`
- Result: PASS (GREEN)
- Evidence: 3 test files and 21 tests passed after implementing the policy, projector, and normalization-aware strict validation.

Verification:
- Command: `npm test -- test/domain/taskEnforcementPolicy.test.ts`
- Result: FAIL (expected RED)
- Evidence: with the project-mismatch guard removed, the new direct-child project test received
  `ROOT_COMMITMENT` with no violation instead of `UNSUPPORTED_HIERARCHY` and
  `child_project_mismatch`.

Verification:
- Command: `npm test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskHierarchyProjector.test.ts test/domain/taskValidation.test.ts`
- Result: PASS (GREEN)
- Evidence: 3 test files and 22 tests passed after restoring the minimal project-mismatch guard.

Verification:
- Command: `npm test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskHierarchyProjector.test.ts`
- Result: FAIL (expected RED)
- Evidence: deleted children were initially still counted: the deleted thirty-first child caused
  `too_many_children`, an invalid deleted child caused `child_has_schedule`, and the projector
  returned the deleted child in its children/progress.

Verification:
- Command: `npm test -- test/domain/taskEnforcementPolicy.test.ts test/services/taskHierarchyProjector.test.ts test/domain/taskValidation.test.ts`
- Result: PASS (GREEN)
- Evidence: 3 test files and 25 tests passed after live-child filtering was applied consistently to
  policy child collection/nested-child detection and projection.

## Final Verification

Verification:
- Command: `npm test -- test/services/taskSnapshotService.test.ts test/domain/taskEnforcementPolicy.test.ts test/services/taskHierarchyProjector.test.ts test/domain/taskValidation.test.ts`
- Result: PASS
- Evidence: 4 test files and 29 tests passed, including the existing companion ownership snapshot coverage.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0 with no diagnostics.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 23 test files and 177 tests passed after the deletion-aware tree coverage.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification

- Self-reviewed policy precedence: ownership is established before root classification; hierarchy violations attach to roots; direct children are never flattened; and project mismatch, schedule, duration, recurrence, assignment, nesting, and count are all represented by stable codes.
- Self-reviewed projector purity: it receives a flat mirror and returns derived data only; it contains no Todoist command, alarm, recurrence mutation, or SQLite access.
- No live Todoist, WhatsApp, VPS, Android, or production database mutation was performed.

## Assumptions And Known Limits

- Stable child ordering uses Todoist's stable task identifier as the tie-breaker after `child_order`.
- This task supplies the policy/projector boundary only. Task 9 remains responsible for replacing the legacy flat snapshot shape with nested root projections, and Task 5 remains unstarted.

## Known Failures Or Skipped Checks

- No known backend failure remains.
- Build was skipped because this Task 4 plan requires focused tests, full backend tests, and typecheck; no build configuration or runtime packaging changed.
- Client/emulator verification was skipped because this task changes backend domain/service code only and no `client/` files or user-visible UI behavior.

## Next Recommended Step

Proceed with Task 5 only in a separately assigned task after reviewing this handoff; do not begin it as part of Task 4.
