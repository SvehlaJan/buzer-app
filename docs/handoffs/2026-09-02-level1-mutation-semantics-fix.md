# Level 1 backend mutation-semantics repair handoff

Date: 2026-09-02
Status: DONE — ready for parent review and integration

## Task and plan status

- Parent plan: `docs/superpowers/plans/2026-08-30-level-1-mutation-simplification.md`
- Repair scope: Tasks 5–8 mutation preparation, dependent checklist creation, backend
  Issue ownership, and local Postpone/escalation behavior.
- Attempted steps: add in-FIFO SQLite precondition validation; resolve Todoist temporary
  IDs between separate command requests; report command-response parse defects exactly
  once; remove escalation exception swallowing; preserve queue deadline and lifecycle
  behavior.
- Worktree: `.worktrees/level1-fix-mutation-semantics`
- Branch: `feature/level1-fix-mutation-semantics`

## What changed

- `TaskMutationRunner` accepts a narrow `prepareCommands` closure and invokes it only
  after FIFO admission, immediately before the first command. Expected 4xx/domain
  conflicts emit a terminal validation log without a Sentry Issue; internal preparation
  defects emit one terminal failure log and one backend Issue.
- `TaskActionService` re-reads completion eligibility, occurrence keys, task revisions,
  and combined-edit child revisions inside the FIFO. `TodoistChecklistService` similarly
  re-reads the root/tree and validates occurrence plus child revisions there. A revision
  changed while waiting now rejects without a Todoist send.
- The runner keeps only a per-run temporary-to-permanent ID map. After an acknowledged
  `item_add`, later child commands replace a temporary parent ID with the permanent ID
  returned by Todoist. The E2E adapter's cross-request temp-ID map was removed so the
  fixture can no longer hide the production defect.
- `TodoistCommandExecutor` treats an acknowledged `item_add` without a permanent ID as a
  parse/response-shape defect. Command parse defects remain visible as
  `delivery_unknown`, perform the existing best-effort refresh, and create exactly one
  backend Issue.
- `TaskActionService` no longer catches every escalation exception. The expected sender
  failure remains captured and converted to `EscalationResult.status = failed` inside
  `EscalationService`; an unexpected DB/invariant failure now reaches Fastify after the
  local postpone has already persisted.

## Files changed

- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskMutationRunner.ts`
- `backend/src/services/todoistChecklistService.ts`
- `backend/src/services/todoistCommandExecutor.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/test/fakes/e2eTodoistAdapter.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/services/taskMutationRunner.test.ts`
- `backend/test/services/todoistChecklistService.test.ts`
- `backend/test/services/todoistCommandExecutor.test.ts`
- `backend/test/todoist/httpTodoistAdapter.test.ts`
- `docs/handoffs/2026-09-02-level1-mutation-semantics-fix.md`

The local ownership manifest is ignored and was not committed.

## Documentation lookup

Verification:
- Command: `npx ctx7@latest library "Todoist Sync API" "item_add temp_id command result real id sync_status separate sync requests"`
- Result: PASS
- Evidence: resolved `/websites/developer_todoist_api_v1` as the current official API reference.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Sync API item_add temp_id uuid response temp_id_mapping and whether temp ids persist across separate requests"`
- Result: PASS
- Evidence: temporary IDs resolve dependent commands only within one Sync request;
  `temp_id_mapping` supplies the permanent ID required by later separate requests.

## Red/green evidence

Verification:
- Command: `npm test -- test/services/taskMutationRunner.test.ts test/services/todoistCommandExecutor.test.ts test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts`
- Result: FAIL (expected RED)
- Evidence: 7 new regressions failed: preparation API absent, stale queued root/child
  changes still sent, temporary root ID reached a later child request, missing temp
  mapping reported success, parse produced no Issue, and escalation defects were swallowed.

Verification:
- Command: `npm test -- test/services/taskMutationRunner.test.ts test/services/todoistCommandExecutor.test.ts test/services/todoistChecklistService.test.ts test/services/taskActionService.test.ts test/services/escalationService.test.ts test/services/escalationServiceDiagnostics.test.ts test/accountability/whatsappDiagnostics.test.ts`
- Result: PASS
- Evidence: 7 files and 98 tests passed after the repair.

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskMutationRunner.test.ts`
- Result: PASS
- Evidence: 2 files and 41 tests passed after adding the final queued-completion regression.

## Spec-review follow-up

- Successful `item_add` now requires a non-empty string permanent ID. Null, empty,
  whitespace-only, and non-string mappings become a parse defect, stop dependent child
  commands, create one backend Issue through the runner, and return
  `delivery_unknown`.
- Missing and structurally malformed `sync_status[uuid]` values now become parse
  defects. Recognized non-`ok` strings and error objects remain explicit
  `command_rejected` outcomes.
- A prepared mutation emits `todoist_operation_started` only after preparation succeeds,
  with the exact bounded `command_count`, immediately before the first send. A rejected
  pre-send preparation emits no lifecycle start or terminal pair; successful work still
  emits exactly one start and one terminal record.

Verification:
- Command: `npm test -- test/services/todoistCommandExecutor.test.ts test/todoist/httpTodoistAdapter.test.ts test/services/taskMutationRunner.test.ts`
- Result: FAIL (expected RED)
- Evidence: 15 focused failures covered empty/whitespace/non-string permanent IDs,
  missing/malformed Sync status, dependent child suppression, parse capture ownership,
  and prepared lifecycle count/order.

Verification:
- Command: `npm test -- test/services/todoistCommandExecutor.test.ts test/todoist/httpTodoistAdapter.test.ts test/services/taskMutationRunner.test.ts`
- Result: PASS
- Evidence: 3 files and 82 tests passed after the review fixes, including empty and
  whitespace Sync status cases.

## Final verification

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: all 38 backend test files and 480 tests passed after the spec-review fixes.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript completed with no diagnostics.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: production TypeScript build and schema copy completed successfully.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Verification:
- Command: manual Todoist/production/Sentry canary
- Result: SKIPPED
- Evidence: this repair used fakes and local tests only; no live Todoist mutation,
  deployment, or Sentry issue status change was authorized for this subtask.

## Assumptions, known concerns, and next step

- The permanent ID returned for every successful `item_add` is required. A missing
  `temp_id_mapping` entry is conservatively treated as response parsing uncertainty.
- Request-shape and deterministic domain validation may still run before queue admission;
  every mutable SQLite occurrence/task/child precondition is re-read inside the FIFO.
- `npm ci` warned that the worktree used Node 26.4.0 while `package.json` declares Node
  22.x, and reported the repository's dependency audit findings. Tests, typecheck, and
  build nevertheless passed; no dependency versions were changed.
- Parent should run independent spec and code-quality review, integrate the commit, and
  then continue with Android authority/warning fixes. Production canaries and Sentry
  closure remain gated by the full coordinated release plan.
