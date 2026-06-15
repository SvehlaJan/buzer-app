# 2026-06-13 Real Alarm Verification

## Task / Plan

- Task: run Pixel-device real-alarm verification for overlay/boot-recovery Phase 1 with the user operating the device.
- Plan: `docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md`, Task 7 Steps 5-6.
- Result: real start-alarm and deadline-alarm paths passed on the connected Pixel 10 (`56061FDCR005J0`) after fixing an Android serialization bug and repairing one live backend test fixture row.

## Files Changed

Android/shared code:

- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- `client/shared/build.gradle.kts`
- `client/gradle/libs.versions.toml`
- `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGate.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGateTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`

Docs:

- `docs/notes/2026-06-13-product-backlog.md`
- `docs/handoffs/2026-06-13-real-alarm-verification.md`

Existing untracked before this verification pass:

- `docs/handoffs/2026-06-13-context-status-and-next-step.md`

## What Changed

- Added global sanitized Ktor client HTTP logging at `LogLevel.HEADERS`; `Authorization` is redacted.
- Added targeted Add Task/create-task logs for request metadata and HTTP error bodies without logging tokens or full task titles.
- Fixed Android API JSON encoding to include explicit nullable fields. Root cause: `CreateTaskRequest(projectId=null)` was previously serialized with `projectId` omitted because `explicitNulls=false`, but the backend Zod schema requires `projectId: null`.
- Added a shared serialization test proving `CreateTaskRequest` emits `projectId`, `dueDatetime`, and `durationMinutes` as explicit `null`.
- Added backlog note for showing the currently started task on Home and for avoiding duplicate blocker surfaces.
- Added a bounded overlay resolution watcher. While a task overlay is visible, it refreshes and
  reconciles every 2 seconds for up to 2 minutes, then dismisses if the task is resolved for that
  blocker kind. For start blockers, `started` now counts as resolved; for deadline blockers, only
  completed/deleted counts as resolved.

## Commands Run

Verification:
- Command: `npx ctx7@latest library "Ktor" "How to configure Ktor HttpClient global logging plugin for requests and responses with custom logger and sanitized headers"`
- Result: PASS
- Evidence: resolved `/ktorio/ktor-documentation`.

Verification:
- Command: `npx ctx7@latest docs "/ktorio/ktor-documentation" "Ktor HttpClient Logging plugin custom logger log requests responses headers sanitize Authorization header"`
- Result: PASS
- Evidence: docs confirmed `Logging`, custom `Logger`, `LogLevel`, and `sanitizeHeader`.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.api.BuzerApiClientJsonTest' --console=plain`
- Result: FAIL then PASS
- Evidence: first run failed because null fields were omitted; after `explicitNulls=true`, `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.blockers.BlockerTaskSnapshotGateTest' --console=plain`
- Result: FAIL then PASS
- Evidence: first run failed on missing `isBlockerResolved`; after implementation, `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install`
- Result: PASS
- Evidence: installed both the JSON/logging fix build and the later overlay-watcher build on `56061FDCR005J0`.

Verification:
- Command: ADB app-op/battery setup for overlay, notifications, exact alarms, full-screen intent, and device-idle whitelist
- Result: PASS
- Evidence: later `scripts/adb-verify.sh health` showed `SYSTEM_ALERT_WINDOW: allow`, `SCHEDULE_EXACT_ALARM: allow`, `USE_FULL_SCREEN_INTENT: allow`, and `user,app.buzer.android,10498`.

Verification:
- Command: Android Add Task create retry after JSON fix
- Result: PASS
- Evidence: `POST http://100.105.180.34:18432/tasks` returned `200 OK`; logs included `api create_task success`.

Verification:
- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh alarms`
- Result: PASS
- Evidence after fixture repair: two `RTC_WAKEUP` alarms for `app.buzer.android/.enforcement.AlarmReceiver`.

Verification:
- Command: start alarm at approximately `2026-06-13 14:29:17 Europe/Warsaw`
- Result: PASS
- Evidence: `overlay_start attempted result=true kind=START_BLOCKER`, `overlay shown kind=START_BLOCKER`, `POST /tasks/<id>/start -> 200 OK`, `action success action=START_TASK`, and one deadline alarm remained.

Verification:
- Command: deadline alarm at approximately `2026-06-13 14:34:17 Europe/Warsaw`
- Result: PASS
- Evidence: `overlay_start attempted result=true kind=DEADLINE_BLOCKER`, `overlay shown kind=DEADLINE_BLOCKER`, `overlay_action action=COMPLETE`, `POST /tasks/<id>/done -> 200 OK`, refresh returned `scheduleVersion=5`, `completed=12`, and `reconcile complete ... scheduled=0`.

## Manual Verification

- User operated the device for screen verification and taps.
- Start blocker appeared from a real alarm while the phone was locked; user verified content and tapped `I'm starting`.
- Deadline blocker appeared over a normal foreground-app scenario; user verified content and tapped `Done`.
- Android logs confirmed backend actions and overlay dismissal.

## Known Failures / Skipped Checks

- Live Android Add Task initially failed with backend `400 validation_error`: missing `projectId`. Fixed in Android/shared serialization.
- Live-created Todoist task was still invalid after Todoist sync because Todoist persisted `responsible_uid=null` and `due=null` despite the backend receiving assignee and due values. For this verification, the single test fixture row was repaired directly in backend SQLite to `assignee_id=43186358`, due `2026-06-13T12:34:17.836Z`, duration `5`, and the schedule version was incremented. This is a test-fixture repair, not a product fix.
- Duplicate start handling was observed: the start alarm produced both overlay and activity surfaces. The activity `I'm starting` action succeeded first; the overlay remained and later submitted another `START_TASK` action before dismissing. A polling overlay watcher has been implemented and installed, but the exact original lock-screen-activity-then-overlay sequence has not yet been manually rerun.
- Boot recovery with cached future alarms was not run in this pass.
- Ktor global HTTP logging is useful for debugging but may be too verbose to keep permanently at `HEADERS` level.

## Assumptions

- The connected device (`56061FDCR005J0`, model Pixel 10) is acceptable for the real-device verification even though earlier notes referenced Pixel 5.
- Directly repairing the one live backend mirror row was acceptable to unblock Android alarm-surface verification after recording the separate Todoist-create persistence bug.
- The backlog note is sufficient for the "currently started task should be visible" request until a formal implementation plan is written.

## Next Recommended Steps

1. Fix backend Todoist task creation so tasks created through Android persist exact due datetime and assignment in Todoist sync results, without manual SQLite repair.
2. Prevent duplicate blocker surfaces from submitting duplicate actions or leave-behind overlays.
3. Add Home UI support for showing the currently started/in-progress task.
4. Run the remaining boot-recovery test with a cached snapshot containing a future alarm and confirm alarms are restored after reboot.
5. Decide whether to keep global HTTP logging enabled, gate it to debug builds, or reduce it after the backend create bug is fixed.
