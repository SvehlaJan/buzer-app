# Handoff: Snapshot-Generation P1 Review Fixes

Date: 2026-07-16
Plan/task: Recurring checklists Task 14 approved durable snapshot-generation correction — P1/P2 review follow-up
Branch: `feature/recurring-checklists-task3`

## Attempted Steps And Scope

- P1a: made `snapshotGeneration` mandatory in every shared network snapshot
  DTO. GET and successful mutation decoding reject a missing field; a 409 must
  include an authoritative snapshot with the field. Only `SnapshotStore`
  accepts a persisted legacy file without it, injects `0`, rewrites the file,
  and logs the migration.
- P1b: wrapped the complete `TaskSnapshotService` projection read (rows,
  active projects, generation) in one synchronous `better-sqlite3` deferred
  transaction. No network call occurs in that transaction.
- P2: added direct backend route assertions for positive generations on GET,
  200 success, 202 pending, and 409 stale responses; added fake-backend
  coverage for every task mutation category and 202/409 responses.
- Did not edit any Task 14 coordinator/action Android source. Existing dirty
  Android files belonging to that task were preserved.

## TDD Evidence

Verification:
- Command: `npm test -- test/services/taskSnapshotService.test.ts` from `backend/`
- Result: FAIL (expected RED), then PASS
- Evidence: RED reported `expected "transaction" to be called 1 times, but got 0 times`; GREEN passed 13 tests after the projection read transaction.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest --tests app.buzer.shared.api.BuzerApiClientErrorTest --tests app.buzer.shared.api.BuzerApiClientJsonTest` from `client/`
- Result: FAIL (expected RED), then PASS
- Evidence: RED showed missing generation decoding successfully to `0` and 409 returning `BuzerApiException`; a final incremental RED showed a 409 without a snapshot was accepted. The final focused suite passed after strict wire/error decoding.

## Research

- Context7: `npx ctx7@latest library "better-sqlite3" "How should a synchronous read-only transaction consistently cover multiple SELECT statements in a better-sqlite3 repository?"`
- Context7: `npx ctx7@latest docs "/wiselibs/better-sqlite3" "Show the transaction API for synchronously wrapping multiple read queries in one SQLite transaction and describe nested transaction behavior."`
- Result: confirmed synchronous `db.transaction` wraps all statements, supports nested savepoints, and must not contain asynchronous/network work. Used `.deferred()` for this read-only projection.

## Verification

Verification:
- Command: `npm test && npm run typecheck && npm run build` from `backend/`
- Result: PASS
- Evidence: 28 files / 326 tests passed; typecheck and production build passed.

Verification:
- Command: `npm test && npm run build` from `tools/fake-backend/`
- Result: PASS
- Evidence: 12 tests passed, including exhaustive mutation-category generation coverage; TypeScript build passed.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest --console=plain` from `client/`
- Result: PASS
- Evidence: complete shared Android-host suite passed.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.sync.SnapshotStoreTest` from `client/`
- Result: SKIPPED/BLOCKED
- Evidence: compilation is currently blocked by unrelated in-progress Task 14/15/16 Android API migration errors in `MainActivity`, blockers, editor UI, and coordinator callers. The storage source/test is isolated and not the cause; e.g. the first errors are old `TaskActionRequest` call signatures in `MainActivity.kt`.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## Documentation And Next Step

- Updated the durable-generation decision, API contract, plan, and prior
  snapshot-generation handoff to correct the earlier unsafe claim that the
  shared wire DTO defaulted missing values to zero.
- The remaining Task 14 work must replace its temporary equal-schedule merge
  with whole-snapshot positive-generation ordering, then resolve the broader
  Android migration compilation failures before Android unit/emulator checks.

## Quality Follow-up

- Updated Android test fixtures to make their `snapshotGeneration` explicit.
- `SnapshotStore` now writes snapshots and legacy-cache migrations through a
  same-directory temporary file and rename. If the one-time legacy rewrite
  fails, it logs the failure and returns the decoded generation-`0` snapshot
  without changing the original cache file.
- The targeted Android storage test remains blocked by the known Task 14–16
  caller migration compile boundary; its new rewrite-failure regression is
  present and uses an injected writer that throws `IOException`.
