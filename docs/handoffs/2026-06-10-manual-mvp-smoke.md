# 2026-06-10 Manual MVP Smoke

## Task / Plan

- Task: run manual MVP smoke tests on the real Pixel 5 against the live VPS backend.
- Related handoffs:
  - `docs/handoffs/2026-06-09-real-device-vps-smoke.md`
  - `docs/handoffs/2026-06-10-controlled-whatsapp-escalation.md`
- Result: manual smoke passed. Android live refresh, Start blocker start action, Deadline blocker done action, and Android-driven WhatsApp escalation to the user's own number all worked.

## Files Changed

- `docs/handoffs/2026-06-10-manual-mvp-smoke.md`

No app or backend code changed.

## Commands Run

- `git status --short`
- `adb devices -l`
- `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'curl --fail --silent --show-error http://127.0.0.1:18432/health'`
- `ANDROID_SERIAL=11141FDD4002R1 ./gradlew :androidApp:installDebug`
- `adb -s 11141FDD4002R1 logcat -c`
- `adb -s 11141FDD4002R1 shell am start -W -n app.buzer.android/.MainActivity`
- `adb -s 11141FDD4002R1 exec-out uiautomator dump /dev/tty`
- Created live Todoist test tasks through the VPS backend for Start blocker, Deadline blocker, and WhatsApp escalation flows.
- Launched debug blockers with `adb -s 11141FDD4002R1 shell am start -W -n app.buzer.android/.debug.DebugBlockerLauncherActivity ...`.
- Tapped blocker buttons with `adb -s 11141FDD4002R1 shell input tap ...`.
- Queried SQLite inside `buzer-backend` for task, commitment, and escalation rows.
- Temporarily set `WHATSAPP_RECIPIENT_ID=421902359921@c.us` for the self-directed WhatsApp test, then restored `WHATSAPP_RECIPIENT_ID=421904157104@c.us`.
- Completed live Todoist test tasks through `POST /tasks/:id/done`.

## Verification

- Command: `ANDROID_SERIAL=11141FDD4002R1 ./gradlew :androidApp:installDebug`
- Result: PASS
- Evidence: installed `androidApp-debug.apk` on `Pixel 5 - 14`; Gradle reported `BUILD SUCCESSFUL`.

- Command: Android home launch and UI dump
- Result: PASS
- Evidence: home screen showed live snapshot counts (`Invalid: 34`, `Active blockers: 0`, `Starting today: 0`, `Health warnings: 0`) and no `Refresh failed` banner.

- Command: Start blocker debug launch for task `a47bebf0-9732-483e-9277-d26168f53246`, then tap `I'm starting`
- Result: PASS
- Evidence: Android log showed `start_blocker start_clicked`; SQLite showed `state="started"` and non-null `startedAt`. The task was later completed during cleanup.

- Command: Deadline blocker debug launch for task `1d1bc2cc-ad3e-4c8a-b000-430d5852a606`, then tap `Done`
- Result: PASS
- Evidence: Android log showed `deadline_blocker done_clicked`; SQLite showed `state="completed"` and non-null `completedAt`.

- Command: Start blocker debug launch twice for task `4244d0f2-02ef-45fd-ab67-88e17734218b`, tapping `Postpone 5 min` each time
- Result: PASS
- Evidence: after first postpone, SQLite showed `startDismissCount=1` and no escalation row. After second postpone, SQLite showed `startDismissCount=2`, `whatsapp_escalations.status="sent"`, and `escalationSentAt="2026-06-10T17:13:03.216Z"`.

- Command: app-private file check
- Result: PASS
- Evidence: `run-as app.buzer.android ls files` showed `latest_snapshot.json` and `profileInstalled`; no `blocker_action_errors.log` existed.

- Command: final VPS health check
- Result: PASS
- Evidence: `/health` returned `{"database":"ok","whatsapp":{"connected":true,"detail":"ready"}}`.

- Command: final test-task cleanup verification
- Result: PASS
- Evidence: all three live Todoist test tasks were completed through the backend and SQLite showed `state="completed"` for all three.

## Manual Verification

- Confirmed Pixel 5 was connected as `11141FDD4002R1`.
- Confirmed Android blocker UI rendered expected action buttons before tapping.
- Confirmed the self-directed WhatsApp test used `421902359921@c.us`.
- Restored production escalation recipient to `421904157104@c.us` after the self-directed test.

## Known Failures Or Skipped Checks

- Mac-side `curl` to `oc-vps.taila50381.ts.net` failed DNS resolution during this session, and `curl` to `100.105.180.34:18432` hung until killed. VPS loopback health checks and Android app behavior were used instead.
- Did not wait for real alarm timing; used the debug blocker launcher to exercise blocker activities immediately.
- Did not test Android Add Task form manually; test tasks were created through the backend for tighter control and cleanup.
- Did not verify receipt visually on the user's WhatsApp device from this shell.

## Assumptions

- Debug blocker launcher is an acceptable MVP smoke substitute for waiting for real alarms because it exercises the same blocker activities and backend action calls.
- SQLite `whatsapp_escalations.status="sent"` is sufficient backend evidence that `whatsapp-web.js` accepted the self-directed message send.

## Suggested Testing Improvements

- Add a local `scripts/vps-status.sh` that uses SSH loopback to report deployed commit, container status, `/health`, and recent backend errors without relying on local Tailscale HTTP.
- Add `scripts/manual-smoke.sh --mode live-no-whatsapp|live-self-whatsapp` to create controlled tasks, launch debug blockers, verify SQLite state, and always clean up tasks.
- Add an Android debug screen for backend URL, `/health`, latest snapshot time, and buttons to launch Start/Deadline blockers for a selected task.
- Add a small backend admin/debug endpoint or script to create short-lived smoke-test tasks with names and cleanup metadata.

## Next Recommended Step

Finish MVP operational readiness: install the SQLite backup cron on the VPS, then do a permission/setup pass on the Pixel 5 for exact alarms, notifications, full-screen intent, battery optimization, and boot reschedule behavior.
