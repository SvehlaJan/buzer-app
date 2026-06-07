# Handoff: KMP Kermit Logging

Date: 2026-06-02

## Task / Plan

Task: Add better logging after real-device smoke showed the backend snapshot had invalid tasks while the visible Morning review UI still showed none.

Plan: No standalone plan file was created for this small diagnostic slice. The work followed the existing MVP spec and recent real-device smoke context.

Status: Implemented and verified. Follow-up routing and home-counter fixes also implemented and verified with fake-backend managed-device tests.

## Files Changed

- `client/gradle/libs.versions.toml`
- `client/shared/build.gradle.kts`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/diagnostics/AppLog.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotDiagnostics.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotDiagnosticsTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunner.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/sync/SnapshotStore.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

## Commands Run

Verification:
- Command: `npx ctx7@latest library "Android Logcat" "Android Kotlin app logging best practices Log tag android.util.Log exception stack traces structured diagnostic logs"`
- Result: PASS
- Evidence: resolved official Android Developer docs.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android" "Android Kotlin app logging best practices Log tag android.util.Log exception stack traces structured diagnostic logs"`
- Result: PASS
- Evidence: confirmed Android logging conventions around tags and diagnostics.

Verification:
- Command: `npx ctx7@latest library "Kotlin Multiplatform" "Kotlin Multiplatform logging best practices expect actual shared code Android iOS JVM Compose Multiplatform"`
- Result: PASS
- Evidence: resolved `/jetbrains/kotlin-multiplatform-dev-docs`.

Verification:
- Command: `npx ctx7@latest library "Kermit" "Kotlin Multiplatform Kermit logging library setup best practices Android Logcat shared code"`
- Result: PASS
- Evidence: resolved `/touchlab/kermit`.

Verification:
- Command: `npx ctx7@latest docs "/touchlab/kermit" "Kotlin Multiplatform Kermit logging library setup best practices Android Logcat shared code Logger.withTag severity log writers"`
- Result: PASS
- Evidence: confirmed Kermit common dependency, platform writers, severities, tags, and logger injection guidance.

Verification:
- Command: `cd client && ./gradlew :shared:testDebugUnitTest --tests app.buzer.shared.state.SnapshotDiagnosticsTest`
- Result: FAIL
- Evidence: RED check failed with unresolved `SnapshotDiagnostics`.

Verification:
- Command: `cd client && ./gradlew :shared:testDebugUnitTest --tests app.buzer.shared.state.SnapshotDiagnosticsTest :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `cd client && ./gradlew :androidApp:assembleDebug`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `android run --device=56061FDCR005J0 --type=ACTIVITY --activity=.MainActivity --apks=client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- Result: PASS
- Evidence: Android CLI installed and launched the debug APK successfully.

Verification:
- Command: `adb logcat -d -v time -s 'Buzer:*'`
- Result: PASS
- Evidence: Kermit logs appeared in Logcat with count-only diagnostics: `tasks=40`, `invalid=34`, `healthWarnings=0`, and no task titles.

Verification:
- Command: `cd tools/fake-backend && PORT=18433 npm run dev`
- Result: PASS
- Evidence: fake backend started with `fake backend listening on 18433`.

Verification:
- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --tests app.buzer.android.blockers.BlockerActivityFakeBackendTest.homeMorningReviewButtonOpensLiveMorningReview -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: FAIL
- Evidence: Gradle Managed Device tasks do not accept `--tests`: `Unknown command-line option '--tests'`.

Verification:
- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: FAIL
- Evidence: RED check after adding `homeMorningReviewButtonOpensLiveMorningReview` failed with `ComposeTimeoutException: Condition still not satisfied after 5000 ms`.

Verification:
- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: after routing the home Morning review button to `MorningReviewActivity`, `Finished 15 tests on pixel8Api34`; `0 skipped`; `0 failed`; `BUILD SUCCESSFUL in 1m 33s`.

Verification:
- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: FAIL
- Evidence: RED check after adding `homeScreenShowsLatestSnapshotCounts` failed with `ComposeTimeoutException: Condition still not satisfied after 5000 ms`.

Verification:
- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: after wiring `MainActivity` refresh results into `BuzerApp` and `HomeScreen`, `Finished 16 tests on pixel8Api34`; `0 skipped`; `0 failed`; `BUILD SUCCESSFUL in 1m 45s`.

Verification:
- Command: `cd client && ./gradlew :shared:testDebugUnitTest --tests app.buzer.shared.state.SnapshotDiagnosticsTest :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`.

Verification:
- Command: `lsof -iTCP:18433 -sTCP:LISTEN -n -P`
- Result: PASS
- Evidence: after stopping the fake backend process, no listener remained on port `18433`.

## Manual Verification

- Installed and launched the app on the connected phone through Android CLI.
- Confirmed the app refreshed from the live VPS backend.
- Confirmed log output records refresh start/fetch/save/reconcile/complete and runner skip due to duplicate active refresh.
- Confirmed the log output does not include task titles, bearer tokens, raw JSON, or WhatsApp content.
- Added an automated fake-backend test that proves the home-screen Morning review button opens the live Android activity path and displays the invalid fixture.
- Added an automated fake-backend test that proves the home screen eventually displays reduced snapshot counts from the fake backend: `Invalid: 1`, `Active blockers: 0`, `Starting today: 1`, `Health warnings: 0`.

## Known Findings

- Fixed: the home-screen `Morning review` button now starts `MorningReviewActivity` from inside the app, while the activity remains `exported=false`.
- Fixed: `MainActivity` now reduces successful refresh snapshots into Compose state and passes that state into `BuzerApp`, so home counters update after refresh.
- New UI navigation logs explicitly mark these paths with `mode=static_compose_route`.
- Start and deadline home buttons still use static Compose routes for now because launching real blocker activities requires task IDs.

## Known Failures Or Skipped Checks

- No destructive live Todoist actions were performed.
- `adb` was used only for logcat and one tap during manual verification because Android CLI does not expose logcat or touch input.
- The fake backend initially had to be killed with escalation because the original long-running session stdin was unavailable to the tool.

## Assumptions

- Kermit `2.1.0` is acceptable as the KMP logging library because it is current, maintained, documented, and supports Android Logcat plus future Apple targets.
- Count-only snapshot diagnostics are safe enough for local development logs; task titles and raw payloads remain intentionally excluded.

## Next Recommended Step

Improve the home screen and blocker debug buttons now that live snapshot state reaches the app shell. The next narrow slices are: show a backend/config error banner when refresh fails, and either remove the static Start/Deadline blocker demo buttons from the user-facing home screen or convert them into task-backed debug pickers.
