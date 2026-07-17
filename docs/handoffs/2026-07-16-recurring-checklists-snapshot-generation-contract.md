# Recurring Checklists: Durable Snapshot Generation Contract

Plan/task attempted:

- Implemented the user-approved additive snapshot-generation contract correction
  beside Task 14 in
  `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`.
- Scope was backend, shared contract model/tests, fake backend, docs, decision,
  and this handoff. The pre-existing uncommitted Task 14 Android files were not
  edited or reverted.

Architecture:

- SQLite migration v3 creates `snapshot_generations(id = 1, generation)` with
  baseline `1`, after the legacy v2 backfills complete. `AFTER INSERT`,
  `AFTER UPDATE`, and `AFTER DELETE` triggers on `todoist_tasks`,
  `commitment_states`, and `todoist_projects` increment it in the same SQLite
  transaction as the projected write. A rollback therefore advances neither
  table state nor generation.
- `TaskSnapshotService` includes the durable generation in every snapshot, so
  GET snapshots, successful/202 mutation responses, and route-generated 409
  snapshots all expose it.
- The shared DTO decodes absent `snapshotGeneration` as `0` solely for an
  already persisted pre-v3 local snapshot. Zero is documented as unorderable
  legacy authority; the next positive server snapshot replaces it. New backend
  and fake-backend responses always include a positive value.

Files changed:

- `backend/src/db/migrations.ts`
- `backend/src/repositories/snapshotGenerationRepository.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/src/services/taskSnapshotService.ts`
- `backend/test/db/migrations.test.ts`
- `backend/test/services/taskSnapshotService.test.ts`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- shared API/state/enforcement contract fixtures under
  `client/shared/src/commonTest/kotlin/`
- `tools/fake-backend/src/fixtures.ts`
- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `docs/notes/api-contract.md`
- `docs/decisions/2026-07-16-durable-snapshot-generation.md`
- plan and this handoff

TDD evidence:

- RED command: `npm test -- migrations.test.ts` from `backend/`
  - Result: FAIL.
  - Evidence: `SqliteError: no such table: snapshot_generations`.
- RED command: `npm test -- server.test.ts` from `tools/fake-backend/`
  - Result: FAIL.
  - Evidence: snapshot generation was absent: `undefined !== NaN`.
- RED command:
  `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientContractTest.snapshotDecodesFixedRecurringChecklistFields' --console=plain`
  from `client/`
  - Result: FAIL.
  - Evidence: `Unresolved reference 'snapshotGeneration'` before the shared DTO
    property existed.

Context7 lookup:

- `npx ctx7@latest library "SQLite" "How can SQLite triggers transactionally increment a single global generation counter whenever tracked tables change, including INSERT UPDATE DELETE, and how should migrations create it?"`
- `npx ctx7@latest docs "/websites/sqlite_docs" "How can SQLite triggers transactionally increment a single global generation counter whenever tracked tables change, including INSERT UPDATE DELETE, and how should migrations create it?"`
- Result: consulted SQLite trigger synchronization examples. The implementation
  uses `AFTER` triggers because their update shares the originating SQLite
  transaction; the v3 migration test proves rollback behavior.

Verification:

- Command: `npm test -- migrations.test.ts taskSnapshotService.test.ts && npm run typecheck && npm run build` from `backend/`
  - Result: PASS.
  - Evidence: 21 focused tests passed; TypeScript typecheck and production build passed.
- Command: `npm test` from `backend/`
  - Result: PASS.
  - Evidence: all 28 backend test files / 325 tests passed.
- Command: `npm test -- server.test.ts && npm run build` from `tools/fake-backend/`
  - Result: PASS.
  - Evidence: all 11 fake-backend contract tests and TypeScript build passed.
- Command: `./gradlew :shared:testAndroidHostTest --console=plain` from `client/`
  - Result: PASS.
  - Evidence: complete shared Android-host suite built and passed.

Manual verification:

- Inspected every fake snapshot-affecting task mutation route: create, start,
  done/rollover, postpone, extend, reschedule, root/child update, add child,
  and focused child mutations all advance generation exactly once before their
  authoritative response is produced. Replay/pending/stale paths do not mutate
  state and retain the current generation.
- Inspected the backend route error handler: all 409 and 202 bodies obtain their
  snapshot through `TaskSnapshotService`, so the new field is not route-specific.

Known failures or skipped checks:

- Full backend suite, full Android JVM/assemble, and emulator lane have not yet
  been rerun for this contract correction; they belong to the subsequent Task
  14/15/16 integration and Task 17 full verification.
- Task 14 still contains its pre-approved temporary equal-`scheduleVersion`
  merge in uncommitted Android source. Its follow-up must apply whole snapshots
  in positive `snapshotGeneration` order and cover legacy zero cache handling;
  this backend/shared/fake task intentionally did not edit AndroidApp source.

P1 review correction (2026-07-16):

- The earlier statement that the shared DTO itself decoded an absent generation
  as `0` was incorrect and unsafe for network authority. The follow-up makes
  the wire field required and confines that compatibility behavior to local
  `SnapshotStore` migration.
- The earlier verification list predates this review hardening. The follow-up
  added RED→GREEN coverage for missing GET/mutation/409 generations, explicit
  legacy-file migration, a single backend projection-read transaction, direct
  GET/success/202/409 route fields, and all fake mutation categories. Its
  current Android `SnapshotStore` unit-test command cannot compile because
  unrelated in-progress Task 14/15/16 Android callers still use the previous
  coordinator API; the focused shared suite remains runnable.

Assumptions:

- `todoist_tasks`, `commitment_states`, and `todoist_projects` are the complete
  current source projection for `TasksSnapshotResponse`; a future projected
  table needs all three v3-style triggers in the same migration.
- The counter is concurrency/order metadata, never authentication or a client
  write token; root/child revisions remain request preconditions.

Next recommended step:

- Amend the Task 14 Android coordinator to use this generation as the sole
  whole-snapshot ordering token, retain draft behavior, then run its focused
  Android JVM tests before continuing Tasks 15–17. Do not commit this contract
  change until code/spec review is complete.
