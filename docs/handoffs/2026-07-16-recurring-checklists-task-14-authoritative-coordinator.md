# Recurring Checklists Task 14: Authoritative Android Coordinator

Plan/task attempted:

- Implemented Task 14 source and test coverage from `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md` only. Deliberately did not migrate Add, Morning Review, Start/Deadline blocker, overlay, or other UI call sites owned by Tasks 15–16.

Changes made:

- Converted `EnforcementApi` task and focused-child methods to return `MutationResponse` and require fixed, immutable request bodies with action/device/occurrence metadata.
- Replaced coordinator mutation follow-up fetches with `applyMutationResponse`: the returned snapshot is persisted, reconciled through the occurrence-keyed registry, and the triggering notification is cleared only after authority is received.
- Added typed coordinator action states for success, partial, pending, stale, rate-limit retry, and child-in-flight. A 409 saves/reconciles its conflict snapshot; a 429 returns retry timing and a closure that retains the original serialized request/action ID.
- Removed optimistic `cancelAllForTask` on Done. A confirmed returned snapshot now decides cancellation or next recurring alarm scheduling.
- Added focused child check/uncheck operations with one in-flight action per child. A later action must provide its own fresh request/revision; retry re-enters the same child gate.
- Updated `HomeDoneActionHandler` to receive a pre-created request, expose authoritative pending/stale/partial messaging, and retain the coordinator retry closure rather than creating a new action request.

Files changed:

- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerAction.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/HomeDoneActionHandler.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt`
- plan and this handoff

TDD evidence:

- RED command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest.successfulActionAppliesReturnedSnapshotWithoutFollowUpFetch' --console=plain`
  - Result: FAIL before test execution.
  - Evidence: the app was already blocked by the planned Task 11 fixed-contract migration. The original `EnforcementApi` called action client methods without request bodies; Task 15/16 callers also still construct legacy action/editor requests. The new RED test is present and requires the missing authoritative request/response path.

Context7 lookup:

- `npx ctx7@latest library "Kotlin coroutines" "How should a Kotlin Android coordinator model a captured immutable request for retry and preserve cancellation-safe UI state while awaiting a suspend API response?"`
- `npx ctx7@latest docs "/kotlin/kotlinx.coroutines" "How should a Kotlin Android coordinator model a captured immutable request for retry and preserve cancellation-safe UI state while awaiting a suspend API response?"`
- Result: consulted official coroutine cancellation guidance before retaining caller-owned immutable retry closures; no blocking retry or local mutation was added.

Verification:

- Command: `./gradlew :androidApp:compileDebugKotlin --console=plain`
  - Result: FAIL (known planned integration boundary).
  - Evidence: no Task 14 source diagnostic was emitted. The remaining compilation failures are un-migrated calls from `MainActivity`, Start/Deadline/Morning Review blocker activities, overlay, and Task 15 editor/screen construction. Those are explicitly outside this task’s file scope and need the Task 15/16 caller migrations.
- Command: `git diff --check`
  - Result: PASS.
  - Evidence: no whitespace errors.

Manual verification:

- Inspected the Done path to confirm the old `cancelAllForTask` call is gone. Only authoritative response reconciliation mutates the durable alarm registry.
- Inspected error handling to confirm non-429 failures never alter cached checked/done/recurrence state; 409 uses only the server-provided snapshot; 429 retries use the captured request object.

Known failures or skipped checks:

- The required focused Android JVM lane cannot execute until Tasks 15–16 migrate current UI callers to construct fixed action/request metadata and handle the expanded typed result states. This is the same planned Task 11→14/15/16 integration boundary recorded in Tasks 11 and 13, not an architectural drift.
- No instrumentation/e2e lane was attempted while Android compilation is intentionally incomplete.

Assumptions:

- Backend mutation routes return `MutationResponse` for 200/202 and return an authoritative snapshot in a 409 error body, per the fixed Task 9–11 contract.
- Caller-owned draft retention remains in UI state: Task 14 applies the conflict snapshot without replacing any caller draft; Task 15’s Edit/Morning Review state will use `SnapshotReducer.replaceAuthoritative` to render the conflict while preserving its draft.

Next recommended step:

- Request Task 14 spec/code review before committing. Then complete Tasks 15–16’s UI caller migration, re-run Task 13 and Task 14 focused JVM lanes, and resolve only genuine integration failures.

## Quality P1/P2 follow-up

Changes made:

- Separated transport/action failure from local post-response application failure. `LocalReconciliationFailed` carries the returned authoritative snapshot when storage, alarm reconciliation, or notification cleanup fails; it does not report a backend failure or create a retry POST/alarm.
- Serialized snapshot-store and alarm-registry application with a short coordinator-wide critical section only after a network response arrives. Independent child requests still run concurrently over the network. Because `scheduleVersion` is only a max across roots, equal-version snapshots merge per-root/per-child `r:<mirrorRevision>` values; different-child changes survive, while a lower same-child response returns stale authority rather than overwriting a newer cached child.
- Made Home Done render returned authority directly for partial/pending/stale/local-reconciliation states when the cache cannot be trusted.
- Added returned-authority Done coverage proving a one-time completion cancels the old occurrence only, and a recurring completion cancels old identity/schedules the next occurrence, with no snapshot fetch or blanket pre-cancel.

Additional TDD evidence:

- RED command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest' --console=plain`
  - Result: FAIL before test execution at the unchanged Task 15–16 caller migration boundary.
  - Evidence: the new tests require `LocalReconciliationFailed`, ordered response application, throwing store/scheduler fakes, and returned-authority Done handling. The compiler continues to stop before test classes in un-migrated Android UI callers; no Task 14 source diagnostic was emitted after implementation.

Additional manual verification:

- `call()` completes before the local application critical section. The critical section covers only snapshot load/save, schedule-version ordering, registry reconciliation, and notification cleanup; it never surrounds an API request.
- A confirmed Postpone response whose scheduler application fails returns `LocalReconciliationFailed`; it does not enter `failedAction`, so it cannot report a backend error or arm the five-minute retry.
- A 409 conflict uses the same local-application boundary, so a failing store remains distinguishable from a backend stale response and still exposes the server snapshot to the caller.

## Equal-version concurrency correction

- Moved every snapshot-store read used for coordinator reconciliation under the same application lock. `reconcileLatestSnapshot` now loads, compares, and reconciles while locked, so boot recovery cannot re-arm a snapshot that a concurrent mutation has just superseded.
- Replaced the unsafe global schedule-version ordering with the contract's object revisions. Focused check/uncheck passes its target child and expected child revision into application; returned direct-child state is merged by child revision, counts are recalculated from the merged list, and an older same-child response becomes typed stale.
- Added a delayed equal-schedule-version regression: two different child network calls run concurrently, one response is delayed, and the final snapshot retains both checks. A second regression proves a delayed lower child revision cannot overwrite newer cached checked state.

## Snapshot-generation replacement (2026-07-16)

- The preceding revision-based merge was superseded after the durable
  `snapshotGeneration` contract was approved. Coordinator authority is now
  whole-snapshot only: a positive incoming generation saves and reconciles only
  when it is newer than the cached positive generation; an equal generation is
  an idempotent replay that does not write or reconcile; a lower generation
  becomes typed stale authority without touching alarms or storage.
- Cached generation `0` is legacy and is not reconciled at boot. The first
  positive backend snapshot replaces it wholesale. Root/child revisions remain
  opaque server preconditions; Android no longer parses `r:<number>` or merges
  children/roots.
- Added regression coverage for lower-generation root restoration/deletion,
  equal-generation no-reconcile, legacy replacement, and legacy boot skipping.
- Verification is still blocked at `:androidApp:compileDebugKotlin` by planned
  Task 15–16 call-site migrations (MainActivity, blockers, editor forms, and
  screens). The compiler emitted no `EnforcementCoordinator` diagnostic after
  this replacement.
