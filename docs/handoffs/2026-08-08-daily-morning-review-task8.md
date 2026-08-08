# Daily Morning Review — Task 8 handoff

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`, Task 8.

Completed:
- Replaced the obsolete snooze instrumentation expectation with a local-state assertion: Snooze closes the review, writes a `Snooze` alarm, and records no obsolete server-dismiss route.
- Added the parallel production-path Start expectation: after repairing the owner task, Start closes the review, writes local acknowledgement state, and records no obsolete server-dismiss route.
- Removed the Android/shared client dismissal API, coordinator path, error enum value, constants, and fake/test coverage. The backend compatibility route was not changed.

Files changed:
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/{EnforcementApi,EnforcementCoordinator,BlockerAction}.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

Verification:
- Command: `ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest scripts/e2e-real-backend.sh`
- Result: SKIPPED as a behavioral signal
- Evidence: the unmodified expectation was attempted before deletion; sandbox execution could not create tsx IPC, then the escalated class run reached the managed-device build but its existing broad class run did not reach this test before unrelated pre-existing Home timeouts. Task 9 owns migration/green runtime coverage for this class.
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --tests '*EnforcementCoordinatorTest' --tests '*MorningReview*Test' :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 13s`.
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReview*Test' :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 2s` after adding the Start instrumentation expectation.
- Command: `rg -n "dismissMorningReview|/review/dismiss" client --glob '!**/build/**'`
- Result: PASS
- Evidence: no client matches (exit 1 as expected).
- Command: `git diff --check`
- Result: PASS

Assumption: the planned Task 9 instrumentation migration remains the authority for the full runtime flow; Task 8's focused instrumentation source compiles and now asserts the local Snooze state.

Next: review Task 8, mark its plan checkboxes, then continue with Task 9 instrumentation migration.
