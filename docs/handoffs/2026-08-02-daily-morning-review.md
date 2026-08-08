# Daily Morning Review handoff

Date: 2026-08-04

## Task and status

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Task 1: Persist review identity and calculate local schedule/availability.

- [x] Step 1: RED store round-trip tests.
- [x] Step 2: RED pure schedule/availability matrix.
- [x] Step 3: Confirm focused tests are RED.
- [x] Step 4: Implement typed local state storage.
- [x] Step 5: Implement pure calculations.
- [x] Step 6: Confirm focused tests are GREEN.
- [x] Step 7: Commit Task 1 implementation.

Branch/worktree: `feature/daily-morning-review` /
`/Users/svehla/playground/buzer-app/.worktrees/daily-morning-review`

## Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewLocalState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewLocalStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculatorTest.kt`
- this handoff

## Implementation notes

- `MorningReviewLocalStateStore` keeps raw SharedPreferences behind
  `MorningReviewStateStorage`, exposes whole-state `read()`/`replace()`, uses one
  dedicated preferences file, and uses checked synchronous `commit()`.
- Persisted ISO dates, instants, and alarm reason are parsed independently. Invalid
  optional fields become `null`; an incomplete scheduled-alarm record is ignored.
- `MorningReviewScheduleCalculator` receives its review time, performs no system
  clock/zone reads, uses local calendar `atTime(...).atZone(...)` conversion, and
  produces stable opaque occurrence keys.
- Expired/wrong-date Snooze state is normalized away. Availability reevaluates at
  the earliest upcoming local midnight, local review boundary, or active Snooze
  expiry.
- Follow-up review coverage locks stale/yesterday acknowledgement as unacknowledged
  for today, and locks `today`, banner, and reevaluation changes on both sides of
  local midnight in a fixed UTC zone.
- Calculator comparisons now use the resolved zoned review `Instant` for both
  scheduling and availability, so injected review times inside a DST gap or overlap
  cannot disagree with the alarm trigger. SharedPreferences reads use raw values
  from `all` and discard non-string legacy types before ISO parsing.

## Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewLocalStateTest' --tests '*MorningReviewScheduleCalculatorTest'`
- Result: FAIL (expected RED)
- Evidence: `:androidApp:compileDebugUnitTestKotlin` reported unresolved
  `MorningReviewLocalStateStore`, `MorningReviewScheduleCalculator`, and related
  Morning Review types before production code existed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewLocalStateTest' --tests '*MorningReviewScheduleCalculatorTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; all 16 original focused state/schedule JVM tests passed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewLocalStateTest' --tests '*MorningReviewScheduleCalculatorTest'`
- Result: PASS
- Evidence: follow-up coverage run after adding stale-acknowledgement and
  local-midnight assertions; all 18 focused state/schedule JVM tests passed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewLocalStateTest' --tests '*MorningReviewScheduleCalculatorTest'`
- Result: PASS
- Evidence: review-fix run after direct Europe/Bratislava injected gap/overlap
  tests and non-string raw-preference sanitizer test; all 21 focused JVM tests
  passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Manual verification:
- SKIPPED: this task is pure local-state/schedule foundation only; it changes no
  UI, receiver, or alarm delivery path.

## Known failures or skipped checks

- The first sandboxed Gradle invocation could not create the shared Gradle wrapper
  lock under `~/.gradle`; the required focused command was rerun with the local
  build cache allowed and produced the RED/GREEN evidence above.
- Android instrumentation and full E2E are deferred to the UI/alarm integration
  tasks; no user-visible code changed here.

## Assumptions and next step

- The future scheduler owns persisting the normalized/reconciled state after it
  selects or consumes an alarm; the calculator deliberately remains pure.
- Next: Task 2 should build `MorningReviewScheduler` on the typed store and the
  calculator while preserving its one-alarm invariant.

---

## Task 2 scheduler handoff (2026-08-04)

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Task 2: Maintain exactly one reliable Morning Review alarm.

Status: implementation and focused JVM verification completed; plan checkboxes were intentionally not edited for controller review.

Branch/worktree: `feature/daily-morning-review` /
`/Users/svehla/playground/buzer-app/.worktrees/daily-morning-review`

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSchedulerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/AndroidAlarmSchedulerTest.kt`
- this handoff

### Implementation notes

- `MorningReviewScheduler` injects the Task 1 `MorningReviewLocalStateStore`,
  `AlarmScheduler`, pure calculator, five-minute Snooze duration, and one-minute
  priority-retry duration. `MorningReviewDependencies` is the sole production
  constructor and supplies the 08:00 review time/default durations.
- Every non-stale mutation cancels the fixed legacy `morning-review` identity,
  cancels a different persisted occurrence before arming its replacement, then
  persists the new whole state. A schedule or persistence failure cancels the
  newly attempted desired identity and retains the previous state for the next
  reconciliation.
- Acknowledgement clears Snooze and moves to tomorrow; Snooze creates one global
  local retry; valid Snooze consumption clears its metadata; stale delivery is a
  no-op; priority deferral creates a one-minute `PriorityRetry` occurrence.
- `AndroidAlarmScheduler` now routes `MORNING_REVIEW`, Start, and Deadline through
  `setAlarmClock`; only pre-start warnings use `setExactAndAllowWhileIdle`. The
  pure dispatch seam is covered by a host-JVM test.
- Required Context7 lookup: `npx ctx7@latest library "Android AlarmManager" "setAlarmClock one-shot alarm exact alarm behavior and PendingIntent identity"` initially returned HTTP 500, then resolved to `/websites/developer_android`. The follow-up docs lookup confirmed AlarmManager's exact-alarm guidance and that PendingIntent identity includes its relevant flags; occurrence-keyed request codes/data continue to provide the intended distinct one-shot identities.

### Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest'`
- Result: FAIL (expected RED)
- Evidence: `:androidApp:compileDebugUnitTestKotlin` reported unresolved `MorningReviewScheduler` methods/types and the new alarm dispatch seam before Task 2 production code existed.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest' --tests '*Alarm*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; the 11-case scheduler transaction matrix plus all matching existing alarm tests and the new dispatch classification test passed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Manual verification:
- SKIPPED: Task 2 changes scheduling plumbing but not a receiver/UI surface. Alarm delivery and app-scoped device evidence are assigned to Task 3/Task 9.

### Assumptions, skipped checks, next step

- The Task 3 alarm handler validates current occurrence/date/acknowledgement before calling `consume` or `deferForPriority`; both scheduler methods additionally reject a stale persisted occurrence defensively.
- Full real-backend instrumentation is deferred because this task does not alter the client UI; Task 9 owns the required Morning Review alarm instrumentation.
- Next recommended step: Task 3 can build delivery validation, priority resolution, and reconciliation broadcasts using `MorningReviewDependencies.scheduler(context)`.

---

## Task 2 recovery-review amendment (2026-08-05)

Review found that an equal persisted identity was incorrectly treated as evidence
that the matching AlarmManager alarm remained armed. That is false after reboot or
package replacement: durable preferences survive while the OS alarm table does not.

### Correction

- `replaceAlarm` now always calls `schedule(desired)` after the legacy/different-
  identity cancels and before persistence. The old identity is still cancelled only
  when it differs, so scheduling the same PendingIntent is a replace/re-arm rather
  than an additional Morning Review identity.
- The equal-identity test now proves the re-arm call. Transient scheduler failure
  proves immediate desired-identity cleanup then a successful retry. The storage
  case intentionally writes the desired state and then throws (the dangerous
  SharedPreferences-style outcome); its retry proves that an equal stored identity
  is nevertheless armed again.
- Dispatch coverage now explicitly locks AlarmClock classification for Start,
  Deadline, and Morning Review, with pre-start warning remaining exact/idle.
- Context7 lookup against `/websites/developer_android` reconfirmed that a matching
  `PendingIntent` represents the same operation and `FLAG_UPDATE_CURRENT` retains
  that instance while replacing extras. This supports reusing the same identity for
  the required re-arm path.

### Verification

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest' --tests '*AndroidAlarmSchedulerTest'`
- Result: FAIL (expected RED)
- Evidence: before the fix, equal-identity reconciliation emitted only the legacy
  cancel; three tests failed, including the equal-identity re-arm and the
  write-then-throw storage retry recovery case.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest' --tests '*Alarm*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; the scheduler recovery matrix and all alarm tests
  passed after the unconditional re-arm correction.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors before commit.

---

## Task 2 persistence-consistency amendment (2026-08-05)

Review found a second recovery defect in the previous re-arm correction: it cancelled
the desired AlarmManager identity after every persistence exception. Android
preferences can expose updated in-memory values even when durable `commit()` reports
failure, so that cleanup could leave local state pointing at an alarm the scheduler
had just cancelled.

### Refined recovery invariant

- Schedule failure and persistence failure now recover separately. A failed schedule
  for a *different* identity cleans up the attempted desired identity and re-arms the
  prior stored identity; an equal-identity re-arm failure leaves its possibly valid
  existing identity untouched.
- After persistence failure, the scheduler best-effort reads the observable local
  state. If it reports desired, the successfully armed desired alarm remains in
  place. If it reports a different/previous alarm (or none), desired is cancelled
  and the observed alarm is re-armed when present. If observation itself fails, the
  already-successful desired schedule is retained and the read failure is attached to
  the original exception.
- Recovery cleanup/re-arm failures are never silently swallowed: the operation still
  throws its primary failure with each recovery failure added as a suppressed
  exception. The next reconcile remains capable of restoring the one-alarm state.
- The scheduler fake now records active identities as well as calls. Tests lock one
  active identity in ordinary cases; after intentionally failed cleanup they assert
  the inconsistent active set is visible through suppressed error evidence and that a
  subsequent reconcile converges it back to desired.

### Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest'`
- Result: FAIL (expected RED; controller-run)
- Evidence: exactly five intended recovery failures before production changes:
  equal-identity schedule failure, cleanup cancellation failure, write-then-throw
  persistence failure, transient schedule failure, and before-write persistence
  failure.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSchedulerTest' --tests '*Alarm*Test'`
- Result: PASS (controller-run)
- Evidence: `BUILD SUCCESSFUL in 1s`; 38 actionable tasks, 6 executed and 32
  up-to-date. This was the authoritative run from `client/`; the env-prefixed form
  was sandbox-denied before Gradle because of the shared `~/.gradle` lock.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors before follow-up commit.

---

## Task 3 delivery/reconciliation handoff (2026-08-05)

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Task 3: Reconcile system changes and enforce blocker priority at alarm delivery.

Status: implementation and focused verification complete; plan checkboxes were intentionally not edited for controller review.

Branch/worktree: `feature/daily-morning-review` /
`/Users/svehla/playground/buzer-app/.worktrees/daily-morning-review`

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewReconcileReceiver.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- `client/androidApp/src/main/AndroidManifest.xml`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandlerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/BlockerPriorityResolverTest.kt`
- this handoff

### Implementation notes

- `MorningReviewAlarmHandler` validates the opaque delivered occurrence against the persisted typed alarm before mutation, rejects changed local dates and acknowledged Daily occurrences with a local reconcile, defers one minute for an active Deadline/Start, and otherwise consumes the matching occurrence before launch. Cache absence deliberately launches.
- `BlockerPriorityResolver` uses cached `SnapshotViewState` and Todoist-native block semantics (`dueDatetime` is start, deadline is start plus duration). It excludes invalid, pending, unassigned, completed, deleted, unsupported, and started work; deadline beats start.
- `AlarmReceiver` gates only `MORNING_REVIEW`; `Deferred`/`Ignore` return before overlay/notification delivery. Other alarm kinds retain their existing paths.
- The non-exported reconciliation receiver synchronously re-arms only Morning Review local state/alarm scheduling for boot, package replacement, manual clock, and timezone broadcasts. The existing boot receiver remains unchanged.
- Required Context7 lookup: `npx ctx7@latest library "Android BroadcastReceiver" "manifest-declared receiver BOOT_COMPLETED MY_PACKAGE_REPLACED TIME_SET TIMEZONE_CHANGED registration and onReceive synchronous work goAsync constraints"` resolved to official `/websites/developer_android`. Its follow-up official docs confirmed static manifest receivers receive broadcasts while the app is inactive; `goAsync()` creates a pending result specifically for post-`onReceive` asynchronous work. This receiver does only synchronous SharedPreferences and AlarmManager work, so it does not use `goAsync()`.

### Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest'`
- Result: FAIL (expected RED)
- Evidence: before production code, `compileDebugUnitTestKotlin` reported unresolved handler/decision/resolver/priority types.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*BlockerPriorityResolverTest'`
- Result: FAIL (expected RED)
- Evidence: added state-only started-task coverage failed because the initial resolver treated a `started` task with absent `startedAt` as an active Start blocker.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; all seven handler decisions and five priority cases passed.

Verification:
- Command: `./gradlew :androidApp:processDebugMainManifest :androidApp:compileDebugKotlin`
- Result: PASS
- Evidence: manifest receiver declaration and Kotlin production sources compiled successfully.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*Alarm*Test' --tests '*Enforcement*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; existing matching alarm/enforcement JVM tests remained green.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

### Manual verification, known failures, assumptions, next step

Manual verification: the real-backend instrumentation harness was attempted with `scripts/e2e-real-backend.sh`. The sandboxed attempt failed when `tsx` could not create its temporary IPC socket; the required escalated rerun was rejected by the environment's usage-limit approval gate. No workaround was attempted.

Assumption: occurrence keys remain opaque; delivery identity is validated by matching the current persisted typed alarm's key rather than parsing delivery text. `dueDatetime` remains the start and `due + duration` the deadline per `docs/decisions/2026-07-05-todoist-native-due-semantics.md`.

Next recommended step: controller review/commit this Task 3 slice, then Task 4 can extract shared owner-agenda classification without changing this local delivery authority.

---

## Task 3 identity/dispatch correction (2026-08-05)

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Scope: narrowly correct the Task 3 delivery review findings; plan checkboxes remain unchanged for controller review.

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandlerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/BlockerPriorityResolverTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/AlarmReceiverDispatchTest.kt`
- this handoff

### Correction notes

- Morning Review delivery now passes a full `ScheduledAlarmIdentity` from the receiver. The handler requires `taskId == "morning-review"`, `AlarmKind.MORNING_REVIEW`, and the exact persisted occurrence key before any scheduler action.
- `morningReviewOccurrenceKey` is the shared deterministic builder used by calculation, priority-retry scheduling, and handler integrity checking. The handler never parses arbitrary occurrence text; it recomputes the opaque key from persisted typed reason/date/instant and reconciles if that state is inconsistent.
- The handler's `MorningReviewAlarmActions` seam records one intentional action per non-stale branch: stale/wrong identity does nothing; corrupt/acknowledged/wrong-date reconciles; valid/no-cache consumes; Start or Deadline defers. Production delegates that seam to `MorningReviewScheduler`.
- Priority resolution now treats `SnapshotViewState.invalidTasks` as authoritative exclusion in addition to defensive raw-field checks.
- `dispatchAlarmDelivery` is Android-free production dispatch used by `AlarmReceiver`; tests verify handler-before-open for Launch, no open for Deferred/Ignore, and unchanged pre-start/start/deadline paths. Reconcile receiver and manifest remain unchanged.

### Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest' --tests '*AlarmReceiver*Test'`
- Result: FAIL (expected RED)
- Evidence: tests initially failed to compile because the old handler accepted only a string key and neither the scheduler-action nor receiver-dispatch seam existed.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest' --tests '*AlarmReceiver*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; handler identity/action, authoritative-invalid, and dispatch-path tests all passed.

Verification:
- Command: `./gradlew :androidApp:processDebugMainManifest :androidApp:compileDebugKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL` after the receiver continues to use the unchanged manifest registration.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*Alarm*Test' --tests '*Enforcement*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; matching existing alarm and enforcement JVM suites remained green.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors before follow-up commit.

### Assumptions and next step

The original real-backend instrumentation limitation remains unchanged: the sandboxed harness could not create the `tsx` IPC socket and escalation was rejected by the environment usage-limit gate; no workaround was attempted. Next: controller review/commit this correction, then continue the plan from Task 4.

---

## Task 5 daily Morning Review orchestration (2026-08-08)

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Scope: Task 5 only. Plan checkboxes were intentionally not edited.

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewContract.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewContractTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- this handoff

### Implementation notes

- `MorningReviewContract` has explicit Home/Automated factories, treats missing or unknown source as Automated, and round-trips Acknowledged/Snoozed/Cancelled result extras.
- The activity parses entry source once. Only Automated configures the blocker window and consumes system Back; Home exposes Back and returns Cancelled.
- The activity synchronously reduces the complete cached `SnapshotViewState` before Compose, then immediately uses `forceSyncAndReconcile()`. A force-sync failure retains cache and is retryable; it never uses ordinary refresh in this flow.
- Start/Snooze go solely through `MorningReviewDependencies.scheduler`, cancel the exact current occurrence notification when present, set distinct results, and remain open with action Retry on failure. No activity path calls the backend review-dismiss API.
- Repair reloads the coordinator cache after its authoritative success instead of deleting a local invalid ID. The prepared overdue completion path creates a UUID action request from the current occurrence key, reloads cache after success, and shares one action-in-flight guard. Task 6 owns rendering these callbacks/state.

### Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewContractTest'`
- Result: FAIL (expected RED)
- Evidence: new test compilation reported unresolved contract/source/result symbols before implementation.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewContractTest'`
- Result: PASS
- Evidence: all three source/result contract tests passed.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReview*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL` after activity/receiver/screen integration.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `rg -n "dismissMorningReview|refreshAndReconcile\\(" client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- Result: PASS
- Evidence: no matches; the activity uses neither obsolete dismissal nor ordinary GET refresh.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Manual verification and E2E: DEFERRED to Tasks 9/10, when the Task 6 rendered flow and alarm integration host tests exist.

### Task 5 review correction (2026-08-08)

- Added `MorningReviewSessionState`/controller, used by the activity for cached authority, forced-sync success/failure retention, action guarding, retry storage, and overdue result handling.
- The activity now constructs Task 4's `buildMorningReviewScreenState` on every composition and passes its complete state plus a task lookup to the transitional screen. Blocking/pending/no-data/global actions therefore use the owner-only state authority.
- Pending Retry calls forced sync. Overdue completion reuses `RetryAfter.retry` rather than allocating another request, and reloads cache for all authority-bearing outcomes.
- Home system and visible Back share the Cancelled-result close path; the compatibility Screen bridge keeps existing host sources compiling until Task 6 migrates them.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewSessionStateTest'`
- Result: FAIL (expected RED)
- Evidence: unresolved session state/controller before implementation.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReview*Test'`
- Result: PASS
- Evidence: session, state, scheduler, handler, and contract focused JVM tests passed.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin` and `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: production and existing instrumentation test sources compile.

Verification:
- Command: `git diff --check` and activity search for `dismissMorningReview(`/`refreshAndReconcile(`
- Result: PASS
- Evidence: no whitespace errors and no obsolete activity calls.

E2E remains deferred to Tasks 9/10.

### Final Task 5 correction (2026-08-08)

- The used session controller now owns forced-sync and terminal acknowledgement/Snooze execution, including retry closures and open-on-failure behavior.
- Pending, Stale, and local-reconciliation-failed overdue results reduce their returned authority directly; only ordinary Success reloads the cache. RetryAfter preserves and recursively uses its supplied retry closure.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReview*Test'`, `./gradlew :androidApp:compileDebugKotlin`, `./gradlew :androidApp:compileDebugAndroidTestKotlin`, `git diff --check`
- Result: PASS
- Evidence: focused JVM tests plus production and instrumentation-source compilation succeeded; no whitespace errors. E2E deferred to Tasks 9/10.

---

## Task 4 quality correction (2026-08-08)

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Scope: Task 4 pure owner-agenda and Morning Review state only. Plan checkboxes were intentionally not edited; Task 5+ files were untouched.

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelectorTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- this handoff

### Implementation notes

- Selector end-time construction now excludes an otherwise valid task when adding its duration would overflow `Instant`.
- Summary rows retain content during an action but disable every row-level Done action. Blocking and NoData continue to contain no agenda rows.
- `MorningReviewRetryTarget` and `MorningReviewRetryNotice` preserve independent Refresh and Action retry intent. `retryNotices` maintains Refresh-then-Action order and `warning` remains a deterministic compatibility string. Refresh wording distinguishes cached data from unavailable data.
- Row labels retain `HH:mm-HH:mm`, add a local-date suffix such as `(+1d)`, and include both UTC offsets when they change over the interval.

### Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*MorningReviewScreenStateTest'`
- Result: FAIL (expected RED)
- Evidence: `compileDebugUnitTestKotlin` failed on unresolved `MorningReviewRetryTarget` and `retryNotices` before the additive retry model was implemented.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*MorningReviewScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 4s`; the focused selector and Morning Review suites passed after the implementation.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*HomeScreenStateTest' --tests '*MorningReviewScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`; 8 selector, 18 Home state, and 17 Morning Review state tests passed (43 total, zero failures).

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

### Manual verification, assumptions, next step

Manual/UI and E2E verification: intentionally not run. This correction changes pure state and selector behavior only; no rendered client behavior was changed in this task.

Assumption: a positive-duration task can only move forward in local-date order, so the date suffix is positive for valid entries. Task 5/6 can use each retry notice's explicit target for its callback without parsing the compatibility warning.

Next recommended step: merge this focused correction before continuing Morning Review orchestration/rendering work.

An initial attempt to run focused, manifest, and broad Gradle commands concurrently raced on the worktree's Kotlin output directory. The broad suite completed successfully, while the other two reported missing generated classes; both focused and manifest commands were rerun serially and passed as recorded above.

---

## Task 4 shared owner-agenda classification and pure review state (2026-08-05)

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Task 4: Share owner agenda classification and build the pure review model.

Plan checkboxes were intentionally not edited; controller review owns that update.

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelectorTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- this handoff

### Implementation notes

- `selectOwnerAgenda` owns terminal/unsupported exclusion, owner-only filtering,
  authoritative invalid projection, pending/invalid blocking precedence, safe time and
  duration handling, half-open current intervals, and deterministic bucket ordering.
  Genuinely unassigned roots and assigned-other roots are excluded from this selector.
- Home delegates owner blocking/overdue/current/future classification to that selector.
  Its labels, hero choice, overlap ordering, expansion behavior, row models, and
  separate unassigned projection are retained; the added parity case locks that an
  owner repair attention item cannot suppress an expanded Unassigned section.
- `MorningReviewScreenState` is a pure, injected-time/zone model with explicit
  Loading, Blocking, Summary, and NoData phases. Blocking cards hide summary rows;
  clear summaries include all overdue owner rows plus current/today-start entries.
  Start-day and Snooze eligibility are derived without actions, clock reads, or
  navigation mutations.

### Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*HomeScreenStateTest'`
- Result: PASS
- Evidence: the required command was run from `client/`, which is the only location
  containing the Gradle wrapper; 15 baseline Home state tests passed before extraction.
  The same command from the worktree root was not a test failure: it reported
  `zsh: no such file or directory: ./gradlew`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest'`
- Result: FAIL (expected RED)
- Evidence: compilation reported unresolved `selectOwnerAgenda` before the selector
  production file was created.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewScreenStateTest'`
- Result: FAIL (expected RED)
- Evidence: compilation reported unresolved Morning Review phase/card/state symbols
  before the pure state production file was created.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*HomeScreenStateTest' --tests '*MorningReviewScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 5 selector tests, 16 Home state tests, and 10 Morning
  Review state tests passed (31 total, zero failures).

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

### Manual verification and skipped checks

- Manual UI verification: SKIPPED. This task introduces pure classification and state
  models only; it does not yet wire the new Morning Review model into Compose or an
  activity.
- `scripts/e2e-real-backend.sh`: DEFERRED to Tasks 9/10, where the rendered Morning
  Review and alarm delivery flows exist. This task has no relevant scoped rendered
  class yet.

### Assumptions and next step

- Snapshot `dueDatetime` remains the existing Change 1 agenda start instant, preserving
  accepted Home presentation behavior; no scheduling semantics were reinterpreted.
- Task 5 should orchestrate cached/forced authority updates and actions around
  `buildMorningReviewScreenState`; Task 6 should replace the legacy raw-task Compose
  branching with this explicit state model.

### Task 4 review amendments (2026-08-05)

- Authoritative unsupported owner tasks now remain in the selector's blocking bucket
  for Morning Review Repair, while timed selector buckets exclude them and Home filters
  them from its accepted repair-attention presentation.
- `CommitmentState.deleted` is terminal even when `isDeleted` is false. Today summary
  rows now include owner entries that started today and have since become overdue, as
  well as cross-midnight current entries; duplicate IDs are removed before ordering.
- The expanded matrix also locks cached `loading=true`, global action disabling while
  retaining Blocking/NoData content, direct ID tie-breaking, and Repair-card problem
  and trimmed-description presentation.
- Home's separate Unassigned projection now also excludes
  `CommitmentState.deleted` records when `isDeleted` is false, matching the selector's
  terminal semantics.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*HomeScreenStateTest' --tests '*MorningReviewScreenStateTest'`
- Result: FAIL (expected RED)
- Evidence: 38 focused tests ran with four expected behavioral failures: unsupported
  authoritative invalid omission, `state=deleted` omission, and missing same-day
  overdue Today rows (including its dependent Repair-state assertion).

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*HomeScreenStateTest' --tests '*MorningReviewScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 7 selector, 17 Home, and 14 Morning Review tests
  passed (38 total, zero failures).

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*HomeScreenStateTest'`
- Result: FAIL (expected RED)
- Evidence: the added state-deleted/unassigned regression rendered one Unassigned row
  before Home's active-task terminal filter was corrected.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*OwnerAgendaSelectorTest' --tests '*HomeScreenStateTest' --tests '*MorningReviewScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 7 selector, 18 Home, and 14 Morning Review tests
  passed (39 total, zero failures).

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

---

## Task 3 runtime-hardening correction (2026-08-05)

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Scope: narrowly correct Task 3 runtime failure handling; plan checkboxes remain unchanged for controller review.

### Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandlerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/BlockerPriorityResolverTest.kt`
- this handoff

### Correction notes

- Priority now excludes blank task occurrence keys, matching `AlarmPlanCalculator`; a legacy/unkeyed cache task cannot suppress the review in favor of an undeliverable blocker.
- Cache load/reduction is caught narrowly at the priority boundary. A corrupt snapshot logs a `priority` report and is treated as unavailable, allowing the valid due review to consume and launch.
- Scheduler action failures are caught per branch and reported with operation plus occurrence/reason/review-date context through `AppLog.warn`: failed reconcile remains Ignore, failed defer remains Deferred, and failed consume remains Launch. Stale identity still makes no call and no report.

### Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest' --tests '*AlarmReceiver*Test'`
- Result: FAIL (expected RED)
- Evidence: before production hardening, the new throwing-loader/action reporter tests failed to compile because `MorningReviewAlarmHandler` did not yet accept the injected error reporter.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewAlarmHandlerTest' --tests '*BlockerPriorityResolverTest' --tests '*AlarmReceiver*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; focused tests cover blank-key eligibility, corrupt cache, and reconcile/defer/consume fallback decisions with recorded reports.

Verification:
- Command: `./gradlew :androidApp:processDebugMainManifest :androidApp:compileDebugKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*Alarm*Test' --tests '*Enforcement*Test'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; matching alarm/enforcement tests remained green.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors before follow-up commit.

### Assumptions and next step

The original real-backend instrumentation limitation remains unchanged: the sandboxed harness could not create the `tsx` IPC socket and escalation was rejected by the environment usage-limit gate; no workaround was attempted. Next: controller review/commit this correction, then continue the plan from Task 4.
