# Handoff: Native Android Emulator Testing

Date: 2026-06-01

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-01-native-android-emulator-testing.md`

Status: Implemented native Android instrumentation workflow with Gradle Managed Devices and host fake backend. All plan tasks are complete; connected-emulator fallback was not needed.

## Files Changed

- `AGENTS.md`
- `client/gradle/libs.versions.toml`
- `client/androidApp/build.gradle.kts`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/handoffs/2026-06-01-native-android-emulator-testing.md`
- `docs/notes/2026-06-01-native-android-emulator-testing.md`
- `docs/superpowers/plans/2026-06-01-native-android-emulator-testing.md`

## Implementation Notes

- Added AndroidX Test, AndroidJUnitRunner, Compose UI test dependencies, and managed device `pixel8Api34`.
- Added instrumentation argument `FAKE_BACKEND_BASE_URL` for test helper code only.
- Added native blocker activity tests that launch `StartBlockerActivity` and `DeadlineBlockerActivity`, click the Compose buttons, and assert the host fake backend recorded `/start` and `/done`.
- Added test diagnostics that include the target app blocker error log if action calls are not recorded.
- Fixed `BuzerApiClient` to send explicit JSON `{}` for empty action POSTs and set `expectSuccess = true`.

## Commands Run

Verification:
- Command: `if [ -d tools/android ]; then find tools/android -maxdepth 3 -type f -print; fi`
- Result: PASS
- Evidence: no files printed.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: 5 tests passed.

Verification:
- Command: `cd tools/fake-backend && npm run build`
- Result: PASS
- Evidence: TypeScript build completed.

Verification:
- Command: `cd client && ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `cd client && ./gradlew tasks --all | rg 'pixel8Api34.*DebugAndroidTest|pixel8Api34DebugAndroidTest'`
- Result: PASS
- Evidence: output included `androidApp:pixel8Api34DebugAndroidTest`.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `cd client && ./gradlew :shared:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `cd tools/fake-backend && PORT=18433 npm run dev`
- Result: PASS
- Evidence: server printed `fake backend listening on 18433`.

Verification:
- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: managed Pixel 8 completed 2 tests, 0 failed; Gradle reported `BUILD SUCCESSFUL`.

## Known Failures / Skipped Checks

- `cd client && ./gradlew :shared:allTests` failed because the task does not exist in this project. Correct task used afterward: `:shared:testDebugUnitTest`.
- Connected-emulator fallback was skipped because Gradle Managed Devices passed.
- Context7 first Ktor lookup failed with `fetch failed`; retry with `Ktor` resolved `/ktorio/ktor-documentation`.

## Assumptions

- `test-token` is a non-secret fake backend token.
- Native blocker action tests should assert fake backend calls and should not touch live Todoist or WhatsApp.
- The managed device image is already available locally or installable by the Android Gradle Plugin.

## Next Recommended Step

Add the next native instrumentation slice for failure behavior: run the fake backend with `FAIL_ACTIONS=1`, assert blocker activities hide after backend failure, and assert the blocker error log/notification path records the failure.
