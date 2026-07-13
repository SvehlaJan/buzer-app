# Handoff: Todoist Null Assignee Update Failure

Date: 2026-07-13
Plan: follow-up bugfix from `docs/superpowers/plans/2026-07-12-full-task-edit-repair.md`
Task: Investigate live API error when saving Morning Review repair for a task in a non-assignable Todoist project.

## Status

- Device logs showed `POST /tasks/cb34c443-e800-49fd-a88d-aa657bc13762/update` returned `500 Internal Server Error`.
- Backend response was `{"code":"internal_error","message":"todoist_command_failed:[object Object]"}`.
- The failing task was `Objednať Oftal v Brne`, in project `Personal` (`canAssignTasks=false`), invalid only for missing duration.
- Root cause: `HttpTodoistAdapter.updateTask` sent `responsible_uid: ""` when `assigneeId` was null. Todoist Sync rejects that for task update commands.
- Fixed `updateTask` to omit `responsible_uid` when no assignee is present.

## Files Changed

- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/test/todoist/httpTodoistAdapter.test.ts`

## Verification

- Command: `npm test -- test/todoist/httpTodoistAdapter.test.ts` in `backend`
- Result: PASS
- Evidence: 11 tests passed. The new regression failed first with `responsible_uid` received as an empty string, then passed after the fix.

- Command: `npm test` in `backend`
- Result: PASS
- Evidence: 16 test files, 71 tests passed.

- Command: `npm run typecheck` in `backend`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed.

- Command: `npm run build` in `backend`
- Result: PASS
- Evidence: backend TypeScript build completed.

- Command: `git diff --check -- backend/src/todoist/httpTodoistAdapter.ts backend/test/todoist/httpTodoistAdapter.test.ts`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification

- Device log inspection confirmed the failing endpoint, task id, and server-side 500 response.
- VPS `/projects` confirmed the task project `Personal` is non-assignable.

## Known Failures Or Skipped Checks

- Live retry not performed yet. The fix must be deployed to the VPS before the same save can be retried successfully.
- `docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md` was already dirty and unrelated; it was intentionally left untouched.

## Assumptions

- Todoist Sync accepts omission of `responsible_uid` for no-assignee updates, matching existing create behavior that omits `assignee_id` when no assignee is supported.

## Next Recommended Step

- Deploy this commit to the VPS and retry saving the same Morning Review repair on the device.
