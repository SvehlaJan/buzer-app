# Handoff: Morning Review Invalid Task Repair

Date: 2026-06-02

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-02-morning-review-invalid-task-repair.md`

Status: Complete. All plan checkboxes are marked `[x]`.

Subagent-driven development was planned, but this slice was completed locally because the work stayed within one fake-backend route, one Android coordinator path, one morning-review UI/activity path, and one instrumentation suite.

## Files Changed

- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/main/AndroidManifest.xml`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/notes/2026-06-01-native-android-emulator-testing.md`
- `docs/superpowers/specs/2026-06-02-morning-review-invalid-task-repair-design.md`
- `docs/superpowers/plans/2026-06-02-morning-review-invalid-task-repair.md`
- `docs/handoffs/2026-06-02-morning-review-invalid-task-repair.md`

## Commits

- `6979803 docs: plan morning review repair flow`
- `cab246f test: add fake backend review dismiss route`
- `13aeb8e feat: add morning review dismiss action`
- `344a9a7 feat: wire morning review repair actions`
- `b8e7efa test: cover morning review repair flow`

## Verification

- Command: `npx ctx7@latest library "AndroidX Compose UI Test" "Android Compose UI testing onNodeWithText LazyColumn semantics performClick managed device instrumentation"`
- Result: PASS
- Evidence: resolved `/websites/developer_android_develop_ui_compose_testing`.

- Command: `npx ctx7@latest docs "/websites/developer_android_develop_ui_compose_testing" "Android Compose UI testing onNodeWithText LazyColumn semantics performClick managed device instrumentation"`
- Result: PASS
- Evidence: docs confirmed Compose semantics-driven UI testing with `onNodeWithText`, `performClick`, and activity-backed test rules.

- Command: `cd tools/fake-backend && npm test`
- Result: FAIL
- Evidence: RED check after adding tests showed `/review/dismiss` returned `404`; expected failures were `review dismiss is recorded` and `forced action failures also fail review dismiss and still record call`.

- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: `tests 10`; `pass 10`; `fail 0`; `duration_ms 344.453375`.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest`
- Result: FAIL
- Evidence: RED check after adding coordinator test failed to compile with unresolved `dismissMorningReview` and `MORNING_REVIEW_TASK_ID`.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 2s`.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 2s`.

- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: FAIL
- Evidence: instrumentation compile initially failed on unavailable Compose test imports: first `fetchSemanticsNodes`, then `assertExists`.

- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: after switching the async text wait helper to poll `assertIsDisplayed`, `BUILD SUCCESSFUL in 1s`.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: FAIL
- Evidence: first managed-device run failed in `morningReview_snoozeCallsFakeBackendAndHides` with `SecurityException: Caller app.buzer.android needs to hold android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM to set exact alarms.`

- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: after declaring `USE_EXACT_ALARM`, `BUILD SUCCESSFUL in 1s`.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: `Finished 14 tests on pixel8Api34`; `0 skipped`; `0 failed`; `BUILD SUCCESSFUL in 1m 34s`.

## Manual Verification

- Reviewed staged diffs before each commit and kept commits scoped.
- Confirmed fake backend was reachable from the emulator through `10.0.2.2:18433`.
- Stopped the fake backend after final verification.

## Known Failures Or Skipped Checks

- The first exact-alarm managed-device run failed until `USE_EXACT_ALARM` was added. This is now documented in `docs/notes/2026-06-01-native-android-emulator-testing.md`.
- No live Todoist or WhatsApp destructive checks were run.

## Assumptions

- `USE_EXACT_ALARM` is acceptable for this personal dev-mode MVP. This would need policy review before any public Play Store distribution.
- Removing a repaired invalid task from the in-memory morning-review list on successful repair is acceptable immediate feedback even though the fake backend snapshot continues to return the invalid fixture.
- Reassign/delete/complete invalid-task actions remain out of scope for this slice.

## Next Recommended Step

Run a real VPS/device smoke test across the current MVP path: refresh snapshot, add strict task, show morning review invalid fixture from real data when available, start, deadline, extend, reschedule, done, and confirm backend logs for review dismiss and repair actions.
