# Atomic Mutation Reconciliation Task 1: Child Identity And Fake Wire Contract

Date: 2026-07-16
Plan/task: Task 1 of `docs/superpowers/plans/2026-07-16-atomic-mutation-reconciliation.md`
Scope: focused child-ID route contract, fake backend wire parity, API documentation only

## Checkbox Status

- [x] Snapshot-local child-ID route test and Todoist-ID resolution assertion
- [x] Fake completed-create response uses `200`; child failure field is `errorCode`
- [x] API documentation uses `type` for child mutations and documents local child IDs
- [x] Focused backend and fake-backend verification

## Changes Made

- Tightened the existing real route test to prove
  `POST /tasks/task-1/checklist/child-1/check` resolves the mirror-local child
  ID and sends Todoist `todoist-child-1` to `itemClose`.
- Preserved the existing production lookup in `TodoistChecklistService`: local
  `TaskRepository.getById(childId)` is attempted before its constrained legacy
  Todoist-ID fallback, and direct-parent ownership is still enforced.
- Changed fake root-create responses from `201` to `200` and changed fake
  child-failure payloads from `code` to `errorCode`.
- Updated API examples/statuses: completed mutations are `200`, update
  mutations use discriminator `type`, and focused checklist route parameters
  are snapshot mirror-local IDs.

## TDD Evidence

The shared worktree already contained the Task 1 real-route lookup fix and its
mirror-local-ID test when this session began. I preserved that concurrent work
instead of removing/reimplementing it. The added exact `itemClose` assertion
passed against the current implementation.

For the fake wire correction, the test was changed first:

Verification:
- Command: `npm test -- server.test.ts`
- Result: FAIL (expected RED)
- Evidence: fake root create returned `201`, while the contract test expected
  `200` (`201 !== 200`).

The minimal fake-server status/field changes were then applied.

## Verification

Verification:
- Command: `npm test -- test/http/routes.test.ts` (from `backend/`)
- Result: PASS
- Evidence: 22 route tests passed, including focused local child ID route and
  exact Todoist `itemClose` target assertion.

Verification:
- Command: `npm test` (from `tools/fake-backend/`)
- Result: PASS
- Evidence: 13 fake-backend contract tests passed; partial edit verifies
  `childOutcomes[1].errorCode === "stale_child"` and update requests use
  `type`.

Verification:
- Command: `npm run build` (from `tools/fake-backend/`)
- Result: PASS
- Evidence: `tsc -p tsconfig.json` completed successfully.

## Second Spec-Review Follow-Up: Client Mutation Correlation

Review found one final fake-wire omission: successful focused outcomes lacked
the `clientMutationId` that real checklist outcomes expose. The focused routes
must return their `actionId` as the child outcome's client mutation ID; the
single-add route must return the request's `clientMutationId`.

Changes made:

- Focused fake edit/delete/check/uncheck outcomes now emit
  `clientMutationId: body.actionId`.
- Fake `POST /tasks/:id/checklist` successful add outcomes now emit
  `clientMutationId: body.clientMutationId`.
- Added direct focused-check and single-add wire-parity assertions alongside
  the existing Todoist-ID/errorCode assertions.

TDD evidence:

Verification:
- Command: `npm test -- server.test.ts` (from `tools/fake-backend/`)
- Result: FAIL (expected RED)
- Evidence: the focused response and direct-add response both omitted their
  expected `clientMutationId` fields.

Verification:
- Command: `npm test` (from `tools/fake-backend/`)
- Result: PASS
- Evidence: 14 tests passed, including the new focused and direct-add
  correlation parity tests.

Verification:
- Command: `npm run build` (from `tools/fake-backend/`)
- Result: PASS
- Evidence: `tsc -p tsconfig.json` completed successfully.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Manual Verification

- Inspected the focused child resolver. It resolves a snapshot-local mirror ID
  first, permits a legacy Todoist-ID fallback only when that resolved child is
  still a direct child of the selected root, and passes only the child’s
  `todoistTaskId` to Todoist commands.

## Known Boundaries / Next Step

- `partial`, `createdRoot`, and per-child outcome behavior deliberately remain
  in real and fake contracts. Task 2 owns their removal under the approved
  completed-or-pending simplification.
- `reconciliation_pending` remains HTTP `202`, matching the backend error
  handler and Task 2 design; Task 1’s `200` correction applies to completed
  mutation responses, including root creation.
- No Android/device verification was needed: this task changes backend/fake
  wire behavior and documentation only.

## Files Touched In This Session

- `backend/test/http/routes.test.ts`
- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `docs/notes/api-contract.md`
- this handoff

## Spec-Review Follow-Up: Fake Successful Child-Outcome Parity

Review found that the fake server still exposed snapshot-local child IDs in
successful `childOutcomes`, omitted `errorCode: null`, and leaked an internal
`created` property on root-create outcomes. Those fields disagreed with the
real backend and documented `MutationResponse` wire contract.

Changes made:

- Fake successful add, update, check, uncheck, and delete outcomes now return
  `childId: "todoist-<mirror-local-id>"` and `errorCode: null`.
- Fake failures retain their `errorCode`; failed root-create children explicitly
  return `childId: null`.
- Removed the undocumented successful-create `created` payload property.
- Updated the fake generation test to deliberately take the next focused route
  path from the snapshot-local child ID, not the outcome's Todoist ID.

TDD evidence:

Verification:
- Command: `npm test -- server.test.ts` (from `tools/fake-backend/`)
- Result: FAIL (expected RED)
- Evidence: focused check returned local `child-one-time-partial-open` and no
  `errorCode`; root-create returned local child ID plus undocumented `created`.

Verification:
- Command: `npm test` (from `tools/fake-backend/`)
- Result: PASS
- Evidence: 13 tests passed, including focused update/check parity and root
  create outcome parity.

Verification:
- Command: `npm run build` (from `tools/fake-backend/`)
- Result: PASS
- Evidence: `tsc -p tsconfig.json` completed successfully.
