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

- Command: `adb devices`
- Result: BLOCKED
- Evidence: output showed no attached devices, so debug APK install could not be completed from this session.

## Known Issues

- The installed-device step is pending until an Android device is attached.
- Live backend must be deployed from this branch before manually testing task edit save on the device; otherwise the installed app will call a route the deployed backend may not know yet.
- The full managed-device e2e lane was intentionally skipped for this iteration per user request.

## Next Recommended Step

Push the branch, deploy the VPS backend with `BUZER_DEPLOY_BRANCH=feature/android-ui-task-details /home/skclaw/buzer-app/scripts/deploy-vps.sh`, then attach the device and install `client/androidApp/build/outputs/apk/debug/androidApp-debug.apk` for manual testing.
