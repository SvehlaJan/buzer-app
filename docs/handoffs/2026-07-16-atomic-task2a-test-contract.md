# Atomic Mutation Reconciliation — Task 2A Test Contract

Date: 2026-07-16

Plan/task attempted:

- Narrowed Task 2A follow-up from `docs/superpowers/plans/2026-07-16-atomic-mutation-reconciliation.md`.
- Updated backend tests only. No production source or documentation changes were intentionally made in this handoff task.

## Checkbox status

- [x] Successful focused checklist responses assert only authoritative root/snapshot, not public child retry outcomes.
- [x] Deterministic stale/invalid child batches assert terminal validation before a later child command can run.
- [x] Post-send child-create timeout asserts `202 reconciliation_pending`, one durable sent journal command, and replay reuse of the same command UUID/temp identity.
- [x] Focused route asserts completed response has no `childOutcomes` field.
- [x] Three focused backend suites pass.

## Files changed

- `backend/test/services/todoistChecklistService.test.ts`
- `backend/test/services/taskActionService.test.ts`
- `backend/test/http/routes.test.ts`
- this handoff

## Verification

Verification:
- Command: `npm test -- test/services/taskActionService.test.ts test/services/todoistChecklistService.test.ts test/http/routes.test.ts`
- Result: PASS
- Evidence: Vitest reports 3 passed files and 112 passed tests.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual verification

- Inspected the timeout test's journal query: one row is reserved for `child:first:add`; it is first `sent`, then confirmed only by replay with the same command UUID and temporary identity.
- Inspected the focused check route assertion: it uses the snapshot-local child ID, sends Todoist the resolved Todoist child ID, and no longer exposes `childOutcomes`.

## Known boundaries

- This is a focused test-contract handoff. It does not constitute final backend/typecheck/build or device verification.
- The shared Kotlin and Android public-contract simplification belongs to the subsequent atomic plan task; this test-only change does not edit those files.
- No live Todoist/VPS operation was attempted.
- During an earlier superseded Task 2A implementation attempt, I briefly reverted a coordinator hunk while trying to narrow my own changes. The root agent restored the authoritative production coordinator state before this tests-only pass. The focused 112-test result above was run against that restored current source; do not infer a production-source change from this handoff.

## Assumptions

- A repeated HTTP call may invoke Todoist again only with the persisted command UUID/temp identity; Todoist's command UUID idempotency prevents a second external effect. The test verifies the durable command identity, rather than treating the fake adapter as an idempotency implementation.

## Next recommended step

- Continue the atomic-plan implementation from the focused backend contract with the remaining shared/Android public DTO cleanup, then rerun the full backend suite/typecheck/build before final integration verification.
