# Handoff: Backend Todoist Sync And REST API

Date: 2026-05-27
Agent: Codex GPT-5
Plan: `docs/superpowers/plans/2026-05-08-02-backend-todoist-sync-and-rest-api.md`

## Scope

- Execute the full backend Todoist sync and REST API plan in `.worktrees/backend-todoist-api` on branch `feat/backend-todoist-api`.
- Ownership was limited to `backend/**`, this handoff, and the backend Todoist plan. `client/**` and `.claude/` were not edited.

## Completed

- Completed all plan checkboxes for Tasks 1-7.
- Resolved Todoist docs with Context7 ID `/websites/developer_todoist_api_v1`.
- Resolved Fastify docs with Context7 ID `/fastify/fastify`.
- Added Todoist adapter contracts, fake adapter, and an HTTP Todoist adapter skeleton using Sync API commands.
- Added Todoist sync service that writes task mirrors and persists the SQLite sync token.
- Added snapshot service aligned with `docs/notes/api-contract.md`.
- Added task action service for create, start, done, postpone-start, extend, and reschedule.
- Added Fastify bearer auth, routes, server builder, device check-in, and review dismiss route.
- Added tests for sync, snapshots, task actions, and HTTP routes.
- Marked all checkboxes complete in the plan.

Files changed:

- `backend/src/http/auth.ts`
- `backend/src/http/routes.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/src/server.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskSnapshotService.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/src/todoist/todoistAdapter.ts`
- `backend/src/todoist/todoistTypes.ts`
- `backend/test/fakes/fakeTodoistAdapter.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/services/taskSnapshotService.test.ts`
- `backend/test/services/todoistSyncService.test.ts`
- `docs/handoffs/2026-05-27-backend-todoist-api.md`
- `docs/superpowers/plans/2026-05-08-02-backend-todoist-sync-and-rest-api.md`

## Verification

- Command: `npx ctx7@latest library "Todoist API" "Sync API task polling due_datetime duration completion update complete tasks"`
- Result: PASS
- Evidence: resolved `/websites/developer_todoist_api_v1` as the best match.

- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Sync API incremental sync task update complete task due_datetime duration"`
- Result: PASS
- Evidence: returned Sync API `item_update` and `item_close` command docs plus REST task update fields including `due_datetime`, `duration`, and `duration_unit`.

- Command: `npx ctx7@latest library "Fastify" "TypeScript routes hooks authentication testing inject"`
- Result: PASS
- Evidence: resolved `/fastify/fastify`.

- Command: `npx ctx7@latest docs "/fastify/fastify" "TypeScript route registration hooks authentication inject testing"`
- Result: PASS
- Evidence: returned TypeScript route hook examples and `inject` testing guidance.

- Command: `cd backend && npm install`
- Result: PASS
- Evidence: installed 154 packages from the existing lockfile; 0 vulnerabilities.

- Command: `cd backend && npm test -- test/services/todoistSyncService.test.ts test/services/taskSnapshotService.test.ts test/services/taskActionService.test.ts test/http/routes.test.ts`
- Result: PASS
- Evidence: 4 test files passed with 8 tests after red/green implementation.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 8 test files passed with 20 tests.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited with status 0.

## Decisions And Assumptions

- The plan listed final commit before handoff, but repository handoff requirements need the handoff committed with the implementation, so this handoff was written before commit.
- `docs/notes/api-contract.md` was not changed because endpoint request and snapshot response fields remained compatible with the existing contract. Action routes return small `{ ok: true }` or action-specific acknowledgement bodies where the contract did not define response bodies.
- Reschedule clears `last_snoozed_at` so Android does not preserve stale blocker snooze timing after the deadline/duration changes.
- Postpone-start records the second and later dismissals as escalation-triggering in local state via `escalation_sent_at`; actual WhatsApp sending remains for the later WhatsApp plan.
- The HTTP Todoist adapter is a credential-free skeleton for production wiring and is tested indirectly through the adapter contract/fake. No real Todoist credentials were used.
- The Sync API adapter uses `item_update` for due/duration changes and `item_close` for completion based on fetched Todoist docs.

## Open Issues

- Todoist current user identity, collaborators, projects, and assignment metadata beyond task mirrors are not yet persisted.
- No background polling scheduler is started from `server.ts`; `TodoistSyncService.syncOnce()` is implemented for a later worker/scheduler wiring.
- WhatsApp escalation delivery is not implemented in this plan.

## Next Step

- Execute the WhatsApp escalation/backend worker plan, or add the polling scheduler that calls `TodoistSyncService.syncOnce()` every 5 minutes.

---

## Review Blocker Fixes

Date: 2026-05-27
Agent: Codex GPT-5
Plan/task attempted: review blockers for `docs/superpowers/plans/2026-05-08-02-backend-todoist-sync-and-rest-api.md` after initial Tasks 1-7 implementation.

### Scope

- Fixed self-assigned task creation validation before Todoist calls.
- Fixed Todoist completed/deleted sync to promote local commitment lifecycle state.
- Fixed snapshot terminal state reporting for completed/deleted tasks.
- Fixed HTTP Todoist `item_add` payload shape so `temp_id` is a command-object sibling of `type`/`uuid`/`args`.
- Mapped Todoist `checked`, `is_deleted`, and `completed_at` fields in the HTTP adapter.
- Added a testable 5-minute Todoist poller and wired it only in runtime startup, not in `buildServer` route-injection tests.
- Loaded `.env` in runtime startup and aligned server startup to prefer documented `TODOIST_USER_ID`.

### Files Changed

- `backend/src/http/routes.ts`
- `backend/src/server.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskSnapshotService.ts`
- `backend/src/services/todoistPoller.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/services/taskSnapshotService.test.ts`
- `backend/test/services/todoistPoller.test.ts`
- `backend/test/services/todoistSyncService.test.ts`
- `backend/test/todoist/httpTodoistAdapter.test.ts`
- `docs/handoffs/2026-05-27-backend-todoist-api.md`

### Commands Run

- Command: `npx ctx7@latest library "Todoist API" "Todoist Sync API item_add temp_id command object checked is_deleted completed_at task fields"`
- Result: PASS
- Evidence: resolved `/websites/developer_todoist_api_v1` as the best match.

- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Todoist Sync API item_add temp_id command object checked is_deleted completed_at task fields"`
- Result: PASS
- Evidence: docs show Sync API command objects with `temp_id` at command-object level and task fields including `checked`, `is_deleted`, and `completed_at`.

- Command: `cd backend && npm test -- test/services/taskActionService.test.ts test/http/routes.test.ts test/services/todoistSyncService.test.ts test/services/taskSnapshotService.test.ts test/todoist/httpTodoistAdapter.test.ts test/services/todoistPoller.test.ts`
- Result: FAIL then PASS
- Evidence: initial red run failed on the expected review blockers; after fixes, 6 test files passed with 16 tests.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 10 test files passed with 28 tests.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited with status 0.

### Manual Verification

- Reviewed `git diff` to confirm changes stayed within backend and approved docs ownership.
- Confirmed `buildServer` does not start the Todoist poller; the poller is only started in the runtime `server.ts` entrypoint.
- Confirmed `.env.example` already documents `TODOIST_USER_ID`; runtime now prefers that name and accepts `TODOIST_CURRENT_USER_ID` only as a compatibility fallback.

### Known Failures Or Skipped Checks

- No real Todoist credentials were used.
- No live Todoist API call was made; HTTP adapter behavior is covered with mocked `fetch`.

### Assumptions

- Deleted Todoist tasks should take precedence over completed state if both terminal markers are present.
- A Todoist task with `checked: true` but no `completed_at` should use `updated_at` as the local completion timestamp fallback.

### Next Recommended Step

- Run the backend service locally with real Todoist credentials in a non-test environment to verify the first live sync and command execution against a personal Todoist sandbox project.
