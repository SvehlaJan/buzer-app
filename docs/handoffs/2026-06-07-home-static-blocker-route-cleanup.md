# Handoff: Home Static Blocker Route Cleanup

Date: 2026-06-07

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-07-home-static-blocker-route-cleanup.md`

Status: Complete. All implementation checklist items are marked `[x]`.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/superpowers/plans/2026-06-07-home-static-blocker-route-cleanup.md`
- `docs/handoffs/2026-06-07-home-static-blocker-route-cleanup.md`

## Verification

- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 2s`.

- Command: `cd tools/fake-backend && PORT=18433 npm run dev`
- Result: PASS
- Evidence: fake backend printed `fake backend listening on 18433`.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: FAIL
- Evidence: RED check after adding absent-button assertions failed in `homeScreenShowsLatestSnapshotCounts` because Compose still found one `Start blocker` node.

- Command: `rg -n "AppSection\\.(Start|Deadline|Review)|mode=static_compose_route|onStartBlocker|onDeadlineBlocker" client/androidApp/src`
- Result: PASS
- Evidence: no output after cleanup.

- Command: `rg -n "Start blocker|Deadline blocker" client/androidApp/src/main/kotlin/app/buzer/android/ui client/androidApp/src/androidTest`
- Result: PASS
- Evidence: remaining matches are only the real blocker screen labels in `BlockerScreens.kt` and the new absence assertions in `BlockerActivityFakeBackendTest.kt`.

- Command: `cd client && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 2s`.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: `Finished 17 tests on pixel8Api34`; `0 skipped`; `0 failed`; `BUILD SUCCESSFUL in 1m 39s`.

- Command: `lsof -iTCP:18433 -sTCP:LISTEN -n -P`
- Result: PASS
- Evidence: no output after stopping the fake backend.

## Manual Verification

- No live Todoist, WhatsApp, or VPS destructive checks were performed.
- Reviewed code search results to confirm obsolete static app-shell routes are gone and real blocker screen labels remain.

## Known Failures Or Skipped Checks

- No checks were skipped.
- Gradle still warns that the current Kotlin Multiplatform `com.android.library` setup will need migration before Android Gradle Plugin 9.0.0; this is pre-existing and unrelated to the cleanup.

## Assumptions

- Real blocker activities remain the only supported Start/Deadline blocker entry points.
- Keeping `StartBlockerScreen` and `DeadlineBlockerScreen` is required because the real Android activities still render those composables.

## Next Recommended Step

Run a real device/VPS smoke test across refresh, add strict task, morning review, start, postpone, deadline done, extend, and reschedule.
