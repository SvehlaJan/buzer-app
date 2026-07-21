# Handoff: Task Editor Redesign — Task 1 collaborator mirror

Date: 2026-07-22

## Task / Plan

- Plan: `docs/superpowers/plans/2026-07-21-task-editor-redesign.md`
- Task: Task 1, Persist and expose Todoist project membership
- Checkbox status: all Task 1 implementation steps completed in this worktree. The
  parent controller owns plan-checkbox edits, so the plan file is unchanged.

## Files Changed

- `backend/src/db/schema.sql`
- `backend/src/db/migrations.ts`
- `backend/src/repositories/collaboratorRepository.ts`
- `backend/src/todoist/todoistTypes.ts`
- `backend/src/todoist/todoistAdapter.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/test/fakes/fakeTodoistAdapter.ts`
- `backend/test/fakes/e2eTodoistAdapter.ts`
- `backend/test/repositories/collaboratorRepository.test.ts`
- `backend/test/db/migrations.test.ts`
- `docs/handoffs/2026-07-22-task-editor-redesign-task1-collaborators.md`

## Implementation

- Added version-4 SQLite migration and schema tables for collaborator profiles and
  project membership mirrors, including raw JSON and deletion tombstones.
- Added `CollaboratorRepository` profile/state upserts plus active, non-deleted
  project-membership queries.
- Added exact collaborator mirror DTOs and made sync-result collaborator arrays
  mandatory.
- Updated fake adapters to return empty collaborator arrays until Task 2 owns real
  resource mapping.
- Added the minimal, parent-authorized HTTP adapter return shape (`[]` arrays) solely
  to preserve the mandatory type contract. It does not request or map collaborator
  resources; Task 2 owns that behavior.

## Verification

Verification:

- Command: `cd backend && npx vitest run test/repositories/collaboratorRepository.test.ts test/db/migrations.test.ts`
- Result: PASS
- Evidence: 2 test files, 11 tests passed.

Verification:

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 32 test files, 372 tests passed.

Verification:

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

Verification:

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace diagnostics.

## TDD Evidence

- Initial focused command: `cd backend && npx vitest run test/repositories/collaboratorRepository.test.ts test/db/migrations.test.ts`
- Result: RED as intended.
- Evidence: `CollaboratorRepository` module was missing; migration assertions also
  reported both missing collaborator tables and missing migration version 4.

## Manual Verification

- Not applicable: this task is backend persistence/type-contract work. The focused
  in-memory SQLite migration/repository tests cover the executable data path.

## Assumptions / Concerns

- Membership remains outside snapshot-generation triggers because it is projected
  only through the future `/projects` read-model work.
- The HTTP adapter deliberately returns empty collaborator arrays without requesting
  their Todoist Sync resources. Task 2 must map and persist the actual resources.

## Self-Review

- Read-only review found no actionable issues: migration 4 is explicit, membership
  tables are excluded from snapshot triggers, active queries filter both membership
  and profile tombstones, mirror DTOs match the required shape, and HTTP Sync
  request/resource mapping remains unchanged.

## Next Recommended Step

Execute Task 2: request/map Todoist collaborator resources, persist them through
`CollaboratorRepository`, and project active memberships from `/projects`.
