# Recurring Checklists Task 9: Snapshot And Routes

Plan/task attempted:
- Implemented Task 9 backend snapshot and fixed-route migration from the controller-provided requirements. Per controller instruction, I did not read or edit the plan file itself; its Task 9 checkbox still needs controller-side update.

Changes made:
- Reworked `TaskSnapshotService` to use typed mirror fields, `TaskDescriptionCodec`, `TaskEnforcementPolicy`, and `TaskHierarchyProjector`. Snapshots now contain owned roots only; direct children are ordered nested checklist items with independent revisions and progress.
- Added occurrence key, `r:<mirrorRevision>`, activity log entries, recurrence metadata, hierarchy status/violation, and reconciliation status while preserving schedule/enforcement fields.
- Made all fixed HTTP request bodies/params strict Zod objects. Action/device values are bounded, revision values require `r:<number>`, child drafts/mutations are capped at 30, timezone values are IANA-validated, and unknown child schedule fields are rejected.
- Constructed one server-scoped synchronizer, coordinator, snapshot service, and task action service. Route mutations now use the durable coordinator and return `{ result, snapshot }` on completed mutations.
- Added focused coverage for root-only snapshots, hierarchy violations, typed description/recurrence projection, authentication-before-validation, strict child/timezone validation, mutation snapshots, rate headers/61st request limiting, trusted proxy behavior, and journal redaction.

Files changed:
- `backend/src/services/taskSnapshotService.ts`
- `backend/src/http/routes.ts`
- `backend/src/server.ts`
- `backend/test/services/taskSnapshotService.test.ts`
- `backend/test/http/routes.test.ts`
- `backend/test/http/rateLimit.test.ts`
- this handoff

TDD evidence:
- RED: `npm test -- --run test/services/taskSnapshotService.test.ts`
- Result: FAIL
- Evidence: the new root-only hierarchy projection tests observed three top-level rows and child rows before implementation.
- GREEN: `npm test -- --run test/services/taskSnapshotService.test.ts`
- Result: PASS
- Evidence: 11 tests passed after typed projection implementation.

Verification:
- Command: `npm test -- --run test/http/routes.test.ts test/http/auth.test.ts test/http/rateLimit.test.ts test/services/taskSnapshotService.test.ts`
- Result: PASS
- Evidence: 4 files, 42 tests passed.
- Command: `npm test`
- Result: PASS
- Evidence: 28 files, 315 tests passed.
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.
- Command: `npm run build`
- Result: PASS
- Evidence: production TypeScript build and schema copy completed.

Manual verification:
- Inspected action journal records after a create request; they contain a fingerprint and compact response but not the bearer token, full user description, or managed activity text.

Assumptions/concerns:
- `expectedOccurrenceKey: null` remains accepted for non-revision action endpoints to support migrated roots that have not yet been assigned an occurrence. Revision-bearing updates require a bounded opaque occurrence key and `r:<revision>`.
- Normal external Todoist commands return `202 reconciliation_pending` until the synchronizer observes their effect; this is intentional durable-action behavior. Completed local actions return an authoritative snapshot envelope.

Next recommended step:
- Rerun the final verification commands, update the Task 9 checkbox in the plan (intentionally not read here), then commit the coherent backend-only change.

## Takeover continuation

Plan/task/steps attempted:
- Took over Task 9 from its preserved checkpoint, read the approved spec, Task 9 plan, current handoff, and workspace diff before modifying it.
- Added all five missing focused checklist mutation routes and their auth coverage.
- Normalized successful mutation payloads to the fixed authoritative response shape (`actionId`, `outcome`, `snapshot`, and child outcomes where applicable); 202 reconciliation responses now use the same shape.
- Tightened route occurrence keys to non-empty opaque strings, added fixed `checklistDrafts`, and corrected snapshot recurrence metadata and `childRevision` naming.

Additional files changed:
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`

Verification:
- Command: `npm test -- test/services/taskSnapshotService.test.ts test/http/routes.test.ts test/http/auth.test.ts test/http/rateLimit.test.ts`
- Result: PASS
- Evidence: 4 files, 46 tests passed.
- Command: `npm test`
- Result: PASS
- Evidence: 28 files, 319 tests passed.
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.
- Command: `npm run build`
- Result: PASS
- Evidence: production TypeScript build and schema copy completed.

Manual verification:
- Confirmed the red route tests first failed as expected: missing checklist paths produced 404 rather than the required 401/200, the fixed create contract rejected `checklistDrafts`, and a null occurrence key reached service code (503) rather than Zod validation (400).

Known scope boundary:
- No Task 10 fake-backend or Android/client work was started.

Known contract gap:
- `POST /tasks/:id/update` still accepts the earlier flat full-root payload and `checklistMutations`; the Task 9 fixed contract calls for `rootPatch` plus `childMutations`. The checkpoint did not include the service adaptation needed to merge a partial patch safely, so this was not represented as complete in the plan or committed.

Next recommended step:
- Inspect and commit the coherent Task 9 backend and documentation changes with `feat: expose recurring checklist task contracts`.

## Fixed Update-Contract Migration

Plan/task/steps attempted:
- Task 9 fixed update-contract migration from the controller-supplied, user-approved contract. The controller explicitly instructed this session not to read the plan file; I therefore preserved its existing uncommitted checkbox changes rather than risk overwriting another agent's documentation work.

Changes made:
- Replaced the `POST /tasks/:id/update` flat body with the strict fixed shape: `actionId`, `deviceId`, non-empty `expectedOccurrenceKey`, nullable `rootPatch`, and required `childMutations`.
- Added strict `RootEditPatch` parsing: root revision is nested and required only for non-null patches; every root field is optional and cannot appear at the outer level. The route maps the root and discriminated child mutation variants explicitly before reaching the service.
- Replaced the service's flat update input with `UpdateTaskRequest` and `RootEditPatch`. A non-null patch merges only supplied root fields with the mirrored root, reconstructs the managed description/activity text, and remains inside the Task 8 root coordinator. A null patch delegates child mutations to the durable checklist coordinator without requiring a root revision.
- Migrated Task 8 root-edit service fixtures to the fixed request object, preserving their coordinator/recovery assertions.

TDD evidence:
- RED: `npm test -- test/http/routes.test.ts test/services/taskActionService.test.ts`
  - Result: FAIL
  - Evidence: four new contract assertions failed: root-patch requests were rejected by the old flat route, checklist-only requests still required a root revision, and service calls rejected the nested metadata before mapping either patch/mutation path.
- GREEN: `npm test -- test/http/routes.test.ts test/services/taskActionService.test.ts`
  - Result: PASS
  - Evidence: 71 tests passed after strict schema parsing and coordinator-backed request mapping.

Verification:
- Command: `npm test -- test/http/routes.test.ts test/http/auth.test.ts test/http/rateLimit.test.ts test/services/taskSnapshotService.test.ts`
  - Result: PASS
  - Evidence: 4 files, 47 tests passed.
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/services/todoistChecklistService.test.ts test/todoist/httpTodoistAdapter.test.ts`
  - Result: PASS
  - Evidence: 4 files, 129 tests passed.
- Command: `npm run typecheck`
  - Result: PASS
  - Evidence: `tsc -p tsconfig.json --noEmit` exited 0.
- Command: `npm test`
  - Result: PASS
  - Evidence: 28 files, 322 tests passed.
- Command: `npm run build`
  - Result: PASS
  - Evidence: production TypeScript compilation and schema copy completed.

Manual verification:
- Inspected the request boundary: legacy outer root fields are unknown-key validation errors; `rootPatch: null` has no revision field; a non-null patch must carry `expectedTaskRevision`; child variants remain strict discriminated objects.

Known concerns:
- The pre-existing Task 9 plan-file edits were deliberately left untouched because the controller prohibited reading the plan in this migration session. No client or Task 10 files were changed.

Next recommended step:
- Commit the coherent preserved Task 9 snapshot/routes work plus this fixed update-contract migration.

## Recovery verification and commit preparation

Plan/task/steps attempted:
- Recovered the preserved Task 9 checkpoint only. Confirmed the fixed `rootPatch` plus `childMutations` update contract is implemented in the route and action-service layers, with legacy flat root fields rejected and checklist-only updates accepted.
- Updated the stale Task 9 plan note: all Task 9 implementation and verification checkboxes are now complete; only its commit checkbox remains pending until the commit is created.
- Did not begin Task 10.

Files changed in this recovery session:
- `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- `docs/handoffs/2026-07-15-recurring-checklists-task-9-snapshot-routes.md`

Verification:
- Command: `npm test -- test/services/taskSnapshotService.test.ts test/http/routes.test.ts test/http/auth.test.ts test/http/rateLimit.test.ts`
- Result: PASS
- Evidence: 4 files, 47 tests passed.
- Command: `npm test -- test/services/taskActionService.test.ts test/services/taskActionCoordinator.test.ts test/services/todoistChecklistService.test.ts test/todoist/httpTodoistAdapter.test.ts`
- Result: PASS
- Evidence: 4 files, 129 tests passed.
- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.
- Command: `npm run build`
- Result: PASS
- Evidence: TypeScript production build and schema copy completed.
- Command: `npm test`
- Result: PASS
- Evidence: 28 files, 322 tests passed.
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Manual verification:
- Inspected the route-to-service mapping and focused tests: non-null `rootPatch` carries the root revision; `rootPatch: null` skips it and delegates child mutations; outer legacy root fields are rejected by strict schema validation.

Known failures or skipped checks:
- None.

Assumptions:
- The preserved worktree's uncommitted files form the coherent Task 9 change set described by the existing handoff. No unrelated files are staged.

Next recommended step:
- Task 9 was committed as `8a65a55 feat: expose recurring checklist task contracts`; continue with the separately planned Task 10 when directed.
