# Connected Device Smoke Test — 2026-07-17

## Task / Plan

- Goal: smoke-test the current recurring-checklists/atomic-reconciliation Android build on the connected Pixel 10 (`56061FDCR005J0`), visiting every reachable screen without creating live Todoist data.
- Plan status: device exploration complete; full live-backend coverage remains blocked by the older VPS API response.

## Files Changed

- Added this handoff only.

## Manual Verification

- Real-backend build: Home launched and reached the VPS. Its Add Task screen rendered; its project picker returned live projects; assignee controls, date/time picker, duration, recurrence options, checklist draft, and local required-title validation rendered.
- Deterministic physical-device build: Home agenda, Add Task, Edit Task (including schedule/recurrence/checklist controls and Cancel), Morning Review, Start Blocker, and Deadline Blocker were exercised by the connected-device fake-backend instrumentation suite. Edit Task was additionally opened manually from a fixture agenda row.
- The device was reinstalled with the real-backend configuration after test-only verification.

## Verification

- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk scripts/e2e-connected-device.sh`
  - Result: PASS
  - Evidence: `OK (25 tests)` on Pixel 10 / Android 16, using the local fake backend through scoped ADB reverse. The suite includes Home, Add Task, Morning Review repair/snooze, Start Blocker, Deadline Blocker, success and backend-failure paths.

- Command: manual fake-backend build/install with `-PBUZER_BACKEND_BASE_URL=http://127.0.0.1:18438`, then open an agenda row.
  - Result: PASS
  - Evidence: `EditTaskScreen` rendered fixture title/details, project, assignee, start/end, duration, all recurrence choices, checklist controls, Save and Cancel.

- Command: real-backend build/install using machine-local configuration, followed by launch and `android layout --device=56061FDCR005J0 --pretty`.
  - Result: FAIL (known live contract mismatch)
  - Evidence: Home displays `snapshotGeneration` missing from `TasksSnapshotResponse`; the request itself receives HTTP 200 from the VPS.

## Known Failures / Skipped Checks

- The deployed VPS snapshot response does not contain the now-required `snapshotGeneration`. Consequently live Home refresh, Morning Review data, Edit navigation, Start Blocker, and Deadline Blocker cannot consume authoritative live data. Deploy the matching backend before repeating live smoke coverage.
- Android Back from Add Task exited to the launcher instead of returning Home. This was observed manually; the production blocker-specific Back behavior remains separately covered by the passing instrumentation tests.
- The exported debug blocker launcher forwards task ID but drops `EXTRA_OCCURRENCE_KEY`; a synthetic Start launch logs `start_blocker missing_alarm_identity` and returns immediately. It cannot be used for manual Start/Deadline smoke until it forwards that key.
- No live Todoist task creation, completion, reschedule, WhatsApp escalation, or VPS mutation was attempted.

## Assumptions

- The existing fake backend at port `18438` is the intended deterministic test service. The device test wrapper establishes `adb reverse`; this is deliberate because the debug network-security policy supports host-loopback cleartext, not arbitrary Tailscale HTTP.

## Next Recommended Step

1. Explicitly authorize deployment of the matching backend to the VPS.
2. Repeat the live-device smoke paths using one disposable Todoist project/task, then clean it up.
3. Fix and test Add Task Back navigation plus occurrence-key forwarding in `DebugBlockerLauncherActivity`.
