# Handoff: Personal Project Assignee Validation Gap

Date: 2026-07-13
Plan: `docs/superpowers/plans/2026-07-12-full-task-edit-repair.md`
Task: Follow-up bugfix for Morning Review tasks in single-person Todoist projects with no assignee.

## Status

- Root cause confirmed in `TaskSnapshotService`: snapshot validation only treated null-assignee tasks as current-user commitments when `commitmentOwnerId` was already set.
- Todoist personal/single-person projects cannot expose an assignee, so synced tasks in those projects could be flagged with an impossible assignee repair.
- Fixed snapshot generation to load active projects and treat a null-assignee, current-user-created task in a `canAssignTasks=false` project as a current-user commitment for validation and assignment status.
- No Android UI change was needed; the editor is correct to hide assignee for non-assignable projects.

## Files Changed

- `backend/src/services/taskSnapshotService.ts`
- `backend/src/http/routes.ts`
- `backend/test/services/taskSnapshotService.test.ts`

## Verification

- Command: `npm test -- test/services/taskSnapshotService.test.ts` in `backend`
- Result: PASS
- Evidence: 7 tests passed. The new regression test failed first with `isAssignedToUser: false`, then passed after the fix.

- Command: `npm run typecheck` in `backend`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed.

- Command: `npm test` in `backend`
- Result: PASS
- Evidence: 16 test files, 69 tests passed.

- Command: `npm run build` in `backend`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` completed and copied schema.

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification

- Not performed. This is backend snapshot behavior and is covered by the focused regression test.

## Known Failures Or Skipped Checks

- Android/e2e lane not run because no client code changed and the user previously requested manual testing for e2e.
- Live backend deployment still needs to pick up this commit before the device reflects the corrected snapshot behavior.

## Assumptions

- A null-assignee task in an active Todoist project where `canAssignTasks=false`, created by the configured current user, is the user's own commitment because Todoist has no assignable assignee field for that project.

## Next Recommended Step

- Deploy the backend commit to the VPS, then refresh Morning Review. The task should no longer be invalid solely for missing assignee; if it lacks due time or duration, it should remain repairable for those fields.
