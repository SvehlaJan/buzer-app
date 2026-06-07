# Handoff: Home Refresh Error Banner

Date: 2026-06-07

## Task / Plan

Task: Continue improving emulator coverage and fix the app by showing a visible home-screen failure state when the backend snapshot refresh fails.

Plan: No standalone plan file was created for this narrow follow-up slice. It follows the next recommended step from `docs/handoffs/2026-06-02-kmp-kermit-logging.md`.

Status: Implemented and verified.

## Files Changed

- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`

## Verification

- Command: `cd tools/fake-backend && npm test`
- Result: FAIL
- Evidence: RED check after adding `test helper can toggle forced snapshot failures at runtime` failed because `/__fail-snapshot` returned `404` instead of `200`.

- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: `tests 11`; `pass 11`; `fail 0`; `duration_ms 338.388584`.

- Command: `cd tools/fake-backend && PORT=18433 npm run dev`
- Result: PASS
- Evidence: fake backend started with `fake backend listening on 18433`.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: FAIL
- Evidence: RED check after adding `homeScreenShowsRefreshFailureBanner` failed with `ComposeTimeoutException: Condition still not satisfied after 5000 ms`.

- Command: `cd client && ./gradlew :shared:testDebugUnitTest --tests app.buzer.shared.state.SnapshotDiagnosticsTest :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 3s`.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: `Finished 17 tests on pixel8Api34`; `0 skipped`; `0 failed`; `BUILD SUCCESSFUL in 1m 38s`.

## Manual Verification

- No live Todoist, WhatsApp, or VPS destructive checks were performed.
- Confirmed the fake backend process was stopped after the run.

## Known Findings

- `MainActivity` now sets a simple home status message, `Refresh failed`, when coordinator creation is missing or refresh fails.
- Successful snapshot refresh clears the home status message.
- The fake backend now supports `/__fail-snapshot` for deterministic snapshot failure tests.

## Known Failures Or Skipped Checks

- No real-device run was performed for this slice because the fake-backend managed-device test covers the behavior without live data.
- The home status message is intentionally simple text for now. It can later become a styled banner with retry affordance.

## Assumptions

- A generic `Refresh failed` message is sufficient for the MVP UI while detailed diagnostics remain in Kermit logs and `blocker_action_errors.log`.

## Next Recommended Step

Decide whether the user-facing home screen should remove the static Start/Deadline blocker buttons or replace them with task-backed debug pickers. The current buttons still open static Compose routes and are not yet useful for real task enforcement.
