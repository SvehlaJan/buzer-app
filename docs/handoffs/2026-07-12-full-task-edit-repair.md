# Handoff: Full Task Edit Repair

Date: 2026-07-12
Plan: `docs/superpowers/plans/2026-07-12-full-task-edit-repair.md`
Task: Full edit flow for Morning Review repair, plus post-overhaul crash/theme/loading fixes from the same stabilization session.

## Status

- Full task edit design and implementation plan written.
- Morning Review repair now edits title, details, project, assignee, exact time, and duration.
- Project IDs are resolved through `/projects` and displayed as project names.
- Assignee IDs are resolved through configured Android assignee labels.
- Backend supports `POST /tasks/:id/update` and sends Todoist Sync `item_update` with `content`, `description`, `project_id`, `responsible_uid`, `due`, and `duration`.
- Android crash from floating Todoist datetimes without offsets is fixed.
- System bar/theme setup and compact loading indicator were fixed earlier in the same stabilization set.

## Files Changed

- Backend update contract: `backend/src/http/routes.ts`, `backend/src/services/taskActionService.ts`, `backend/src/repositories/taskRepository.ts`, `backend/src/todoist/*`, backend tests.
- Android update contract and UI: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`, `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`, `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/*`, `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`, `AddTaskScreen.kt`.
- Crash/theme/loading fixes: `HomeScreen.kt`, `StatusComponents.kt`, `BuzerColor.kt`, Android manifest/theme resources.
- Fake backend: `tools/fake-backend/src/server.ts`, `server.test.ts`, `fixtures.ts`.
- Docs: this handoff plus full-edit spec and plan.

## Verification

- Command: `npm test` in `backend`
- Result: PASS
- Evidence: 16 test files, 68 tests passed.

- Command: `npm test` in `tools/fake-backend`
- Result: PASS
- Evidence: 17 tests passed, including `/tasks/:id/update`.

- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin --console=plain` in `client`
- Result: PASS
- Evidence: JVM tests, debug APK assembly, and instrumentation-source compilation completed.

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

- Command: `scripts/e2e-fake-backend.sh`
- Result: SKIPPED
- Evidence: user explicitly requested leaving e2e tests for now and doing manual testing.

- Command: `adb -s 56061FDCR005J0 install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- Result: PASS
- Evidence: `Performing Streamed Install` followed by `Success`.

- Command: `adb -s 56061FDCR005J0 shell monkey -p app.buzer.android 1`
- Result: PASS
- Evidence: app launched and `pidof app.buzer.android` returned `29587`.

- Command: `adb -s 56061FDCR005J0 logcat -d -b crash -v time`
- Result: PASS
- Evidence: crash buffer was empty after launch.

- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh logcat 180`
- Result: PASS
- Evidence: app fetched `http://100.105.180.34:18432/tasks/snapshot` with `200 OK`, saved schedule version 5, and logged no fatal exception.

## Known Issues

- Live backend was deployed and verified by the user before final device install.
- The full managed-device e2e lane was intentionally skipped for this iteration per user request.

## Next Recommended Step

Manually test the Morning Review edit flow on the device: open an invalid task, change title/details/project/assignee/time/duration, save, and confirm the task leaves the invalid list after refresh.
