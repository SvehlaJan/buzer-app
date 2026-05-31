# Handoff: Client VPS Backend Configuration

Date: 2026-05-31
Agent: Codex GPT-5
Plan/task attempted: configure and verify Android client branch against the deployed VPS backend.

## Scope

- Pulled latest `master` in the main checkout.
- Merged latest `master` into `feat/android-vps-enforcement`.
- Configured ignored `client/local.properties` in the Android enforcement worktree with the Tailscale backend URL and bearer token.
- Added debug-only Android cleartext HTTP allowance because the MVP backend is currently exposed as `http://` inside Tailscale.

## Files Changed

- `client/androidApp/src/debug/AndroidManifest.xml`
- `docs/handoffs/2026-05-31-client-vps-config.md`

Ignored local-only file created:

- `client/local.properties`

## Commands Run

- Command: `git pull --ff-only origin master`
- Result: PASS
- Evidence: main checkout fast-forwarded from `7822147` to `68f948b`.

- Command: `git merge master`
- Result: PASS
- Evidence: `feat/android-vps-enforcement` merged latest deployment changes from `master`.

- Command: `curl --fail --silent --show-error http://oc-vps.taila50381.ts.net:18432/health`
- Result: FAIL
- Evidence: deployed service returned 401, so the current deployed backend does not expose unauthenticated health.

- Command: `curl --fail --silent --show-error http://100.105.180.34:18432/health`
- Result: FAIL
- Evidence: deployed service returned 401, matching the hostname behavior.

- Command: authenticated `GET /tasks/snapshot` via Tailscale IP
- Result: PASS
- Evidence: endpoint returned a task snapshot.

- Command: authenticated `GET /tasks/snapshot` via Tailscale hostname
- Result: PASS
- Evidence: endpoint returned `scheduleVersion=1`, `taskCount=40`, `healthCount=0`.

- Command: `cd client && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 16s`; 70 actionable tasks, 17 executed.

- Command: `adb devices`
- Result: SKIPPED
- Evidence: ADB ran, but no Android device was connected.

## Manual Verification

- Confirmed `client/local.properties` is git-ignored.
- Confirmed `client/local.properties` is not staged or tracked.
- Confirmed authenticated backend access works through the Tailscale hostname configured for the Android app.

## Known Failures Or Skipped Checks

- Real-device install and app launch were not performed because `adb devices` reported no connected devices.
- The deployed backend currently returns 401 for `/health`; add an unauthenticated health route later if deployment monitoring needs it.

## Assumptions

- Debug Android builds may use cleartext HTTP while the backend is Tailscale-only.
- Release builds should not inherit this debug-only cleartext allowance.
- The local bearer token belongs only in ignored `client/local.properties`.

## Next Recommended Step

- Connect the Android device with Tailscale running, install `client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`, and launch the app to verify it refreshes from the VPS backend without blocker error notifications.
