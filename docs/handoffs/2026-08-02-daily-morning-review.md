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
