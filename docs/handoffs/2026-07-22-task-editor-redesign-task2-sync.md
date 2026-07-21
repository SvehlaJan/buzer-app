# Handoff: Task Editor Redesign — Task 2 collaborator sync and project choices

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 2, Synchronize collaborators and project them through `/projects`
- Checkbox status: Task 2 implementation steps are complete in this worktree. The
  parent controller owns plan-checkbox updates, so the plan file is unchanged.

## Files Changed

- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/src/server.ts`
- `backend/src/http/routes.ts`
- `backend/src/repositories/collaboratorRepository.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/test/todoist/httpTodoistAdapter.test.ts`
- `backend/test/services/todoistSyncService.test.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/repositories/taskRepository.test.ts`
- `docs/handoffs/2026-07-22-task-editor-redesign-task2-sync.md`

## Implementation

- The Sync adapter requests and maps `collaborators` and `collaborator_states`,
  including all documented profile/state fields. Missing record timestamps use the
  Sync response timestamp fallback/current time.
- Sync persists profile and membership records in its existing transaction with
  tasks/projects. Mirror contract versions below 3 force `sync_token = "*"`; only a
  successful full sync advances the mirror to version 3.
- `GET /projects` now reports collaborator readiness and exposes only safe assignee
  choices: current user (`Me`) first, deduplicated active shared-project members
  next, and only locked `Me` for personal projects.
- The task-mirror readiness threshold remains version 2, corrected to accept version
  2 **or newer**. This prevents existing task actions from becoming unavailable once
  the collaborator mirror advances to version 3.

## Verification

Verification:

- Command: `cd backend && npx vitest run test/todoist/httpTodoistAdapter.test.ts test/services/todoistSyncService.test.ts test/http/routes.test.ts test/repositories/taskRepository.test.ts`
  - Result: PASS
  - Evidence: 4 test files, 66 tests passed.

Verification:

- Command: `cd backend && npm test`
  - Result: PASS
  - Evidence: 32 test files, 375 tests passed.

Verification:

- Command: `cd backend && npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:

- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace diagnostics.

## TDD Evidence

- Initial focused command: `cd backend && npx vitest run test/todoist/httpTodoistAdapter.test.ts test/services/todoistSyncService.test.ts test/http/routes.test.ts`
  - Result: RED as intended.
  - Evidence: the old adapter requested only items/projects, the sync service retained
    the v2 mirror contract and did not persist collaborators, and `/projects` lacked
    readiness/member choices.

## Manual Verification

- Context7 lookup of the Todoist Sync API confirmed `collaborators` and
  `collaborator_states` response resources and their profile/state field shapes.
- Inspected the project route response to ensure raw collaborator-state and avatar
  fields stay server-only.

## Assumptions / Concerns

- An empty collaborator array in a successful full Sync response is a valid populated
  member contract; the typed adapter always returns the arrays even when empty.
- Task 3 owns membership validation during create/update. It can use
  `CollaboratorRepository.isMirrorContractReady()` without changing action readiness.

## Next Recommended Step

Run the Task 2 spec and code-quality review, then proceed to Task 3 mutation
validation and optional-schedule handling.
