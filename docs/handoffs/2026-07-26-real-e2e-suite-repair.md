# Real-backend E2E suite repair handoff

Date: 2026-07-26

## Task / plan progress

- Plan: `docs/superpowers/plans/2026-07-26-real-e2e-suite-repair.md`
- Attempted Task 1 baseline capture, Task 3 typed main-backend child-failure control, Task 4 Compose-selector repair, and part of Task 5 verification.
- The full unfiltered instrumentation run was red before the last targeted fixes and has not been rerun since. On 2026-07-27, the user explicitly authorized integrating the selected work without spending further time on those remaining E2E failures; the incomplete suite remains documented below.

## Changes made

- Kept `tools/fake-backend` deleted. All Android instrumentation work used `scripts/e2e-real-backend.sh`, which starts `backend/test/e2eServer.ts` with its in-process `E2ETodoistAdapter`.
- Added a one-shot explicit `/__fail-next-child-add` control to the E2E server and adapter. It does not infer failure from a checklist title. `FakeBackendClient` exposes the matching test-only method.
- Updated the Add-task partial-child flow to use that control and retain the failed child draft after root creation.
- Added stable test tags for adaptive schedule quick choices and the Add-checklist control; the tags do not affect visible UI.
- Fixed form fingerprint projection for personal-project assignees and custom recurrence, so untouched update/repair forms do not falsely appear dirty.
- Changed task-editor validation focus to open the bad section on entry without locking the other editable accordions.
- Updated several Compose tests to scroll only body content, not fixed footer actions. The remaining Blocker/Edit class changes still need focused verification.

## Verification

- Command: `npm test --prefix backend -- e2eServer.test.ts`
  Result: PASS
  Evidence: 22 tests passed; includes the explicit one-shot child-add control test.

- Command: `ANDROID_TEST_CLASS='app.buzer.android.ui.screens.MorningReviewRepairHostTest' scripts/e2e-real-backend.sh`
  Result: PASS
  Evidence: `OK (2 tests)`.

- Command: `ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest#addTask_optionalDetailsAreSubmittedAsDescription' scripts/e2e-real-backend.sh`
  Result: PASS
  Evidence: `OK (1 test)`.

- Command: `ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest#addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft' scripts/e2e-real-backend.sh`
  Result: PASS
  Evidence: final focused run `OK (1 test)` after the stable date/start-time/duration selectors were added.

- Command: `scripts/e2e-real-backend.sh`
  Result: FAIL (pre-final-fix run)
  Evidence: 46 tests, 9 failures. The observed failures were Add-task schedule setup, fixed-footer `performScrollTo()` calls in blocker tests, and one Edit retry dialog observation. Some of those test fixes were applied afterwards, but the required unfiltered rerun remains outstanding.

- Command: `ANDROID_SERIAL=56061FDCR005J0 ./gradlew :androidApp:installDebug`
  Result: PASS
  Evidence: Gradle installed `androidApp-debug.apk` on the connected Pixel 10.

- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install`
  Result: PASS
  Evidence: `Installed debug build on 56061FDCR005J0.`

- Command: `git diff --check`
  Result: PASS
  Evidence: no whitespace errors.

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL` (2026-07-27 consolidation verification).

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:assembleDebug`
  Result: PASS
  Evidence: `BUILD SUCCESSFUL` (2026-07-27 consolidation verification).

## Files changed

- `backend/test/e2eServer.ts`
- `backend/test/e2eServer.test.ts`
- `backend/test/fakes/e2eTodoistAdapter.ts`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt` (pre-existing repair work, still requires focused rerun)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/ChecklistEditor.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/{BlockerScreens,EditTaskScreen,MorningReviewScreen}.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/{TaskEditorScaffold,TaskEditorSchedule,TaskEditorUpdateScreen}.kt`
- related JVM state tests and this plan/handoff.

## Known remaining work

1. Run the Add-task class after the final schedule tag changes:
   `ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest' scripts/e2e-real-backend.sh`.
2. Run and repair only evidence-backed failures from the Edit-task and Blocker classes.
3. Run the unfiltered `scripts/e2e-real-backend.sh` and require green before a future release-quality E2E claim. This is intentionally deferred by the user during the repository consolidation.

## Device state

The connected Pixel 10 (`56061FDCR005J0`) has the debug APK built from this **uncommitted repair worktree** installed. The first installation could not load because this worktree has no gitignored `client/local.properties`, so Gradle compiled empty backend URL/token values and coordinator creation failed. It was rebuilt with the primary checkout's local Gradle properties passed at build time, without copying or exposing them.

Verification:

- Command: rebuilt `:androidApp:installDebug` with local backend Gradle properties, then launched the app and ran `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh logcat 120`
  Result: PASS
  Evidence: snapshot request returned HTTP 200; the app saved schedule version 2710, reconciled three alarms, and updated main state with 12 invalid tasks.

No production backend deploy or database change was made; the new child-failure endpoint exists only in the test E2E server.

## Follow-up UI review (2026-07-27)

The Add, Edit, Repair, and blocker-update forms all render the same `TaskEditorScaffold` and `ScheduleEditor`. A reported visual difference in Repair's start-time suggestions is produced by the common adaptive generator: a repair begins with the task's retained date/time, whereas Add starts blank at the rounded current time. When a repair selection is not today (or a suggestion crosses midnight), the shared generator labels a chip with `Tomorrow`/a date and its selection can change the date. This is technically shared code but not the desired field-level consistency. Recommended follow-up: keep recommendations adaptive, but make Start-time chips time-only and constrain them to the selected Date; date movement should happen only in the Date section. The intentional mode differences should remain limited to immediate validation, Repair/Save wording, activity history, and update-versus-create submission.
