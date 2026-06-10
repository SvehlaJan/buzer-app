# 2026-06-10 Operational Readiness

## Task / Plan

- Task: finish the next MVP operational-readiness step after manual live smoke testing.
- Referenced plan: `docs/superpowers/plans/2026-05-08-05-android-native-enforcement.md`, Task 9.
- Referenced prior handoff: `docs/handoffs/2026-06-10-manual-mvp-smoke.md`.
- Result: VPS SQLite backup cron is installed and manually verified. Pixel 5 notification, exact-alarm, full-screen-intent, battery-optimization, package, and refresh/reconcile state were checked. The broad Android manual-device checklist remains partially open because a real reboot/locked-screen timed alarm/widget pass was not completed.

## Files Changed

- `docs/handoffs/2026-06-10-operational-readiness.md`

## Context Read

- `AGENTS.md`
- `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- `docs/superpowers/plans/2026-05-08-05-android-native-enforcement.md`
- `docs/handoffs/2026-06-10-manual-mvp-smoke.md`
- Recent handoff list in `docs/handoffs/`
- `client/androidApp/src/main/AndroidManifest.xml`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReceiver.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`

## Commands Run

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: clean before this handoff file was created.

Verification:
- Command: `npx ctx7@latest library "Android" "adb appops grant POST_NOTIFICATIONS SCHEDULE_EXACT_ALARM USE_FULL_SCREEN_INTENT battery optimization whitelist boot completed permissions"`
- Result: PASS
- Evidence: resolved Android developer documentation source.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android" "POST_NOTIFICATIONS SCHEDULE_EXACT_ALARM USE_FULL_SCREEN_INTENT battery optimization boot completed permissions appops adb"`
- Result: PASS
- Evidence: confirmed manifest/runtime permission expectations for notifications, exact alarms, full-screen intent, and boot completed handling.

Verification:
- Command: VPS cron install for `17 3 * * * cd /home/skclaw/buzer-app && scripts/backup-sqlite.sh >> /home/skclaw/buzer-app/backup.log 2>&1`
- Result: PASS
- Evidence: crontab contains the line exactly once.

Verification:
- Command: `cd /home/skclaw/buzer-app && scripts/backup-sqlite.sh`
- Result: PASS
- Evidence: `Wrote SQLite backup to ./data/backups/buzer-20260610T172753Z.sqlite`

Verification:
- Command: VPS backup listing
- Result: PASS
- Evidence: `./data/backups/buzer-20260609T204755Z.sqlite` and `./data/backups/buzer-20260610T172753Z.sqlite` exist.

Verification:
- Command: VPS loopback health check
- Result: PASS
- Evidence: `/health` returned `ok=true`, `checks.database=ok`, and `checks.whatsapp.connected=true`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell pm grant app.buzer.android android.permission.POST_NOTIFICATIONS`
- Result: PASS
- Evidence: no error.

Verification:
- Command: `adb -s 11141FDD4002R1 shell cmd appops set app.buzer.android POST_NOTIFICATION allow`
- Result: PASS
- Evidence: later appops read showed `POST_NOTIFICATION: allow`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell cmd appops set app.buzer.android SCHEDULE_EXACT_ALARM allow`
- Result: PASS
- Evidence: later appops read showed `SCHEDULE_EXACT_ALARM: allow`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell cmd appops set app.buzer.android USE_FULL_SCREEN_INTENT allow`
- Result: PASS
- Evidence: later appops read showed `USE_FULL_SCREEN_INTENT: allow`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell dumpsys deviceidle whitelist +app.buzer.android`
- Result: PASS
- Evidence: command returned `Added: app.buzer.android`; later whitelist read showed `user,app.buzer.android,11064`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell am broadcast -a android.intent.action.BOOT_COMPLETED -n app.buzer.android/.enforcement.BootReceiver`
- Result: SKIPPED
- Evidence: Android rejected the synthetic broadcast with `java.lang.SecurityException: Permission Denial: not allowed to send broadcast android.intent.action.BOOT_COMPLETED from pid=..., uid=2000`. This was not retried with a real phone reboot.

Verification:
- Command: `adb -s 11141FDD4002R1 shell am force-stop app.buzer.android`
- Result: PASS
- Evidence: no error.

Verification:
- Command: `adb -s 11141FDD4002R1 shell am start -W -n app.buzer.android/.MainActivity`
- Result: PASS
- Evidence: `LaunchState: COLD`, `Status: ok`, `Activity: app.buzer.android/.MainActivity`.

Verification:
- Command: `adb -s 11141FDD4002R1 logcat -d -s Buzer`
- Result: PASS
- Evidence: app log showed `main refresh requested`, `refresh fetched scheduleVersion=4 ... tasks=48 visible=39 invalid=34 active=0 startingToday=0 completed=9`, `reconcile complete cancelled=48 scheduled=0 preStart=0 start=0 deadline=0`, `refresh complete scheduleVersion=4`, and `refresh_runner completed`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell dumpsys alarm | rg -i -C 4 'app.buzer.android|AlarmReceiver'`
- Result: PASS
- Evidence: app-related `AlarmReceiver` records were under `Recent problems` with `Reason=alarm_cancelled`, consistent with the app log `cancelled=48 scheduled=0`. No active-task alarms were expected because the live snapshot had `active=0` and `startingToday=0`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell dumpsys package app.buzer.android | rg -i 'POST_NOTIFICATIONS|RECEIVE_BOOT_COMPLETED|SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|BootReceiver|AlarmReceiver'`
- Result: PASS
- Evidence: manifest/package state included `BootReceiver`; permissions included `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, and `RECEIVE_BOOT_COMPLETED`; runtime state showed `POST_NOTIFICATIONS: granted=true`, `RECEIVE_BOOT_COMPLETED: granted=true`, `USE_EXACT_ALARM: granted=true`, and `USE_FULL_SCREEN_INTENT: granted=true`. `SCHEDULE_EXACT_ALARM: granted=false` at package permission level is expected with the appop separately set to `allow`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell cmd appops get app.buzer.android`
- Result: PASS
- Evidence: `POST_NOTIFICATION: allow`, `SCHEDULE_EXACT_ALARM: allow`, `USE_FULL_SCREEN_INTENT: allow`. `SYSTEM_ALERT_WINDOW` remains default/rejected and is not requested by the app.

Verification:
- Command: `adb -s 11141FDD4002R1 shell dumpsys deviceidle whitelist | rg 'app.buzer.android'`
- Result: PASS
- Evidence: `user,app.buzer.android,11064`.

## Manual Verification

- Confirmed the Pixel 5 is connected as `11141FDD4002R1`.
- Confirmed Android package and app-op state after applying the MVP permission setup.
- Confirmed cold-start app refresh can reach the live VPS backend and reconcile alarms.
- Confirmed the VPS backup script can create a new SQLite backup and the cron entry is installed.
- Confirmed VPS loopback `/health` sees SQLite and WhatsApp ready.

## Known Failures / Skipped Checks

- Synthetic `BOOT_COMPLETED` broadcast was blocked by Android security policy. A real reboot was not performed in this pass.
- Locked-screen timed-alarm behavior was not re-tested after the permission setup.
- Widget behavior was not re-tested in this pass.
- Android Add Task form was not manually re-tested in this pass.
- No automated Gradle test suite was rerun because no production code changed during this operational pass.

## Assumptions

- The app's current `BootReceiver` behavior is acceptable for MVP: boot starts `MainActivity` with `EXTRA_BOOT_RESCHEDULE_REQUIRED`, and `MainActivity` performs the same refresh/reconcile path verified on cold start.
- With the current live snapshot having no active/starting-today tasks, `scheduled=0` is the correct alarm reconciliation result.
- `SYSTEM_ALERT_WINDOW` is not required because the MVP blocker path uses full-screen activities/notifications, not overlays.

## Next Recommended Step

Run one real timed-alarm rehearsal on the Pixel 5: create a short-lived strict task through the app or backend, lock the phone before the start/deadline time, confirm the full-screen blocker appears from the alarm, complete the task through the blocker, and confirm the backend/Todoist state updates. After that, do a quick Android Add Task and widget entry pass, then call the MVP operational smoke complete.
