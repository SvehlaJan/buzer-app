# Handoff: Native Android Failure Path Testing

Date: 2026-06-01

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-01-native-android-failure-path-testing.md`

Status: Complete.

## Files Changed

- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/notes/2026-06-01-native-android-emulator-testing.md`
- `docs/superpowers/plans/2026-06-01-native-android-failure-path-testing.md`
- `docs/handoffs/2026-06-01-native-android-failure-path-testing.md`

Outside the repo:

- Created Codex skill `android-emulator-testing` at `/Users/svehla/.codex/skills/android-emulator-testing`.

## Implementation Notes

- Added `POST /__fail-actions` to the fake backend so instrumentation tests can force backend action failures per test while keeping happy-path tests in the same suite.
- Added fake-backend unit coverage proving the runtime toggle returns 500 for actions and still records calls.
- Extended `FakeBackendClient` with `setFailActions(enabled)`.
- Added native instrumentation tests for start and deadline blocker backend failures.
- Failure tests assert that the activity reaches `Lifecycle.State.DESTROYED`, the fake backend records the action POST, and the target app `blocker_action_errors.log` contains the expected action/task plus HTTP `500`.

## Commands Run

Verification:
- Command: `python3 /Users/svehla/.codex/skills/.system/skill-creator/scripts/init_skill.py android-emulator-testing --path /Users/svehla/.codex/skills ...`
- Result: PASS
- Evidence: skill directory, `SKILL.md`, and `agents/openai.yaml` created.

Verification:
- Command: `python3 /Users/svehla/.codex/skills/.system/skill-creator/scripts/quick_validate.py /Users/svehla/.codex/skills/android-emulator-testing`
- Result: FAIL
- Evidence: validator could not start because this Python environment lacks `yaml` / PyYAML. Manual inspection confirmed frontmatter has `name` and `description` and the skill folder contains only `SKILL.md` plus `agents/openai.yaml`.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: 6 tests passed. The first sandboxed attempt failed with known `tsx` IPC `EPERM`; rerun outside sandbox passed.

Verification:
- Command: `cd tools/fake-backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.json` completed.

Verification:
- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `cd tools/fake-backend && PORT=18433 npm run dev`
- Result: PASS
- Evidence: server printed `fake backend listening on 18433`.

Verification:
- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: managed Pixel 8 completed 4 tests, 0 skipped, 0 failed; Gradle reported `BUILD SUCCESSFUL`.

Verification:
- Command: `cd client && ./gradlew :shared:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

## Known Failures / Skipped Checks

- Official skill validator was blocked by missing PyYAML in available Python runtimes.
- Notification UI itself was not asserted; the slice verifies the error-log side of `BlockerErrorReporter`. Notification shade/system UI should be tested later with UI Automator if needed.

## Assumptions

- `test-token` is intentionally non-secret.
- Error log assertions are sufficient for this slice because existing unit tests cover reporter behavior and Android notification display is a separate system UI concern.
- Dynamic fake-backend failure toggling is preferable to running the whole suite with `FAIL_ACTIONS=1` because the suite includes both happy and failure tests.

## Next Recommended Step

Add native instrumentation coverage for `Extend 30 min` success and backend failure, then move to `Postpone 5 min` failure behavior and retry scheduling evidence.
