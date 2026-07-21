# Handoff: Task Editor Redesign — Task 3 mutation contracts

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 3, Make mutation contracts support membership and optional schedules
- Checkbox status: Task 3 implementation is complete in this worktree. The parent
  controller owns plan-checkbox updates.

## Files Changed

- `backend/src/http/routes.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/todoist/{todoistAdapter,httpTodoistAdapter,todoistMutationGateway}.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/todoist/httpTodoistAdapter.test.ts`
- `backend/test/fakes/{e2eTodoistAdapter,e2eTodoistAdapter.test}.ts`
- This handoff.

## Implementation

- Root update patches now preserve the distinction between omitted schedule fields
  and explicit JSON `null`; resolved root edits carry nullable due/duration values.
- Shared projects require collaborator-mirror readiness. The current user remains a
  valid assignee; another assignee must be an active member or the request fails with
  `assignee_not_in_project`. A not-yet-ready mirror returns
  `collaborators_unavailable` before sending Todoist commands.
- Schedule validation requires due and duration together. Personal/implicit-self and
  shared self assignments require both; a valid active non-self shared assignee may
  save neither. An unscheduled task cannot carry recurrence other than `never`.
- Explicit clear updates send `due: null` and `duration: null`; moves to a personal
  project send `responsible_uid: null` through the existing unassign mutation.
- Replaced the duration object with explicit preserve/clear/replace variants and
  updated the HTTP and E2E Todoist adapters accordingly.

## TDD Evidence

The initial focused service run was RED as intended:

- Command: `cd backend && npx vitest run test/services/taskActionService.test.ts`
- Result: FAIL
- Evidence: 5 new failures showed the previous implementation required Wife
  schedules, accepted non-members, ignored explicit `null` schedule patches, and
  sent replace rather than clear mutations. The subsequent three-file red run also
  showed the HTTP adapter serialized `{ kind: "clear" }` rather than `duration: null`.

## Verification

Verification:

- Command: `cd backend && npx vitest run test/services/taskActionService.test.ts test/http/routes.test.ts test/todoist/httpTodoistAdapter.test.ts`
- Result: PASS
- Evidence: 3 test files, 102 tests passed.

Verification:

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 32 test files, 384 tests passed.

Verification:

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace diagnostics.

## Manual Verification

- Context7 Todoist REST documentation confirmed that `assignee_id: null` clears an
  assignee and that due/duration update fields are explicit request mutations. The
  executable adapter test verifies the Sync command payload uses `due: null` and
  `duration: null` for the clear case.

## Assumptions / Notes

- A personal project has a locked, implicit self owner even though Todoist receives
  `assigneeId = null`; it therefore remains schedule-required. This reconciles the
  accepted design's self-assigned rule with the Todoist personal-project contract.
- The existing E2E Todoist fake implements the shared duration union so the full
  backend typecheck and later real/fake E2E work preserve the same clear semantics.

## Next Recommended Step

Run Task 3 specification and code-quality review, then proceed to Task 4's shared
Kotlin nullable schedule serialization and project-collaborator validation.
