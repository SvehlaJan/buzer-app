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
