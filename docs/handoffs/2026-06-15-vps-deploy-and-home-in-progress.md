# 2026-06-15 VPS Deploy And Home In-Progress Task

## Task / Plan

- User requested deployment of the current backend branch, then implementation of the backlog item
  to show the currently started/in-progress task in the Bozer app.
- Relevant plans:
  - `docs/superpowers/plans/2026-06-13-project-aware-task-creation.md`
  - `docs/superpowers/plans/2026-06-14-local-backend-e2e-testing.md`
- Additional current-change commit requested by user: committed the overlay polling watcher work from
  real-alarm verification as `99a8a62 fix: poll overlay blocker resolution`.

## Files Changed

- `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotReducer.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotReducerTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `docs/notes/2026-06-13-product-backlog.md`
- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md`

## What Changed

- Deployed the VPS backend from `feature/project-aware-task-creation` at commit `5e4c2bb`.
- Added `SnapshotViewState.inProgressTasks`, populated from visible tasks with `state=started`.
- Added Home state selection for the soonest-deadline in-progress task, with null due timestamps
  sorted last.
- Home now renders an in-progress section with the active task title, due timestamp, and count of
  additional started tasks.
- Updated the product backlog note to mark the first in-progress Home slice as implemented and
  record remaining UI polish.

## Verification

- Command: `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'BUZER_DEPLOY_BRANCH=feature/project-aware-task-creation /home/skclaw/buzer-app/scripts/deploy-vps.sh'`
- Result: PASS
- Evidence: deploy completed and reported `Buzer backend is healthy at http://127.0.0.1:18432/health`.

- Command: `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'cd /home/skclaw/buzer-app && git rev-parse --short HEAD && git branch --show-current && docker ps --filter name=buzer-backend --format "{{.Names}} {{.Status}} {{.Ports}}" && curl --fail --silent --show-error http://127.0.0.1:18432/health'`
- Result: PASS
- Evidence: VPS reported `5e4c2bb`, branch `feature/project-aware-task-creation`, container
  `buzer-backend Up ... (healthy)`, and health JSON with database `ok` and WhatsApp `ready`.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.blockers.BlockerTaskSnapshotGateTest' --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.state.SnapshotReducerTest' --console=plain`
- Result: RED then PASS
- Evidence: initial failure was missing `inProgressTasks`; after implementation, `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain`
- Result: RED then PASS
- Evidence: initial failure was missing Home in-progress state fields; after implementation,
  `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

## Manual Verification

- VPS health was verified over SSH against the local container health endpoint.
- Android UI was not manually verified on a device in this pass; user plans real-device UI checks
  after UI changes.

## Known Failures / Skipped Checks

- Did not run Android instrumentation tests for this Home-only rendering change.
- Did not install the new Android build on the real device.
- The Home section currently shows the raw due timestamp. Remaining polish: local formatting,
  remaining-time text, and a direct action once an active task/detail route exists.
- `backend/.env.local-smoke` remains ignored and was not committed.

## Assumptions

- The active in-progress task should be the started task with the earliest non-null due timestamp.
- If multiple tasks are started, the Home screen should show the first one plus a count of the rest
  until a richer task list/detail UI exists.

## Next Recommended Steps

1. Install the Android build and manually verify that Home updates after tapping `I'm starting`.
2. Polish the Home in-progress section formatting and add a route/action to the active task flow.
3. Run the remaining real-device add-task, alarm, and overlay checks after the UI pass.

## 2026-06-15 Add Task Empty Projects Follow-Up

### Task / Plan

- User reported that, on the real device, Add Task showed a disabled `Select project` button with
  no loading state or retry action.
- This was investigated during the real-device project-aware Add Task verification.

### Files Changed

- `backend/src/services/todoistSyncService.ts`
- `backend/test/fakes/fakeTodoistAdapter.ts`
- `backend/test/services/todoistSyncService.test.ts`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedback.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`

### Root Cause

- The backend `/projects` endpoint returned `{"projects":[]}` on the VPS.
- The backend database already had a stored Todoist incremental sync token from before the project
  mirror existed. After deploying project support, the sync service kept using that incremental
  token, so Todoist did not send unchanged project records and the new mirror stayed empty.
- Android treated the empty project list as a successful load, leaving `Select project` disabled
  without showing an error or retry.

### What Changed

- Backend: `TodoistSyncService` now forces a full Todoist sync (`sync_token=*`) when the active
  project mirror is empty, allowing upgraded databases to backfill projects.
- Android: Add Task now shows `No projects available. Check Tailscale/backend sync, then retry.`
  with the existing retry action when project loading completes with an empty list and no exception.

### Verification

- Command: `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'token=$(docker exec buzer-backend printenv ANDROID_API_TOKEN); curl --fail --silent --show-error -H "Authorization: Bearer $token" http://127.0.0.1:18432/projects'`
- Result: PASS/diagnostic
- Evidence: returned `{"projects":[]}` before the fix, confirming backend project mirror was empty.

- Command: `cd backend && npm test -- todoistSyncService`
- Result: RED then PASS
- Evidence: initial failure showed `existing-incremental-token` was used instead of `*`; after the
  fix, 4 tests passed.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskProjectLoadFeedbackTest' --console=plain`
- Result: RED then PASS
- Evidence: initial failure was unresolved `AddTaskProjectLoadFeedback`; after implementation,
  `BUILD SUCCESSFUL`.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 15 test files and 58 tests passed.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

### Known Failures / Skipped Checks

- Resolved later in this session: the backend fix was deployed to the VPS, `/projects` returned
  8 projects, and the updated Android build was installed on the Pixel 10.

## 2026-06-15 Post-Create Alarm Scheduling Follow-Up

### Task / Plan

- User created a live task successfully, but no start/deadline blocker appeared after locking the
  phone.
- Investigated the live device and backend state before implementing a fix.

### Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlow.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlowTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`

### Root Cause

- Add Task successfully posted to the backend and returned to Home, but it did not trigger
  `MainActivity.refreshEnforcement()`.
- Since navigation from Add Task to Home is in-app Compose state, `MainActivity.onResume()` did not
  run. Android therefore did not fetch the new snapshot or schedule local alarms for the newly
  created task.
- Device `dumpsys alarm` showed no current pending Buzer alarms, only historical Buzer alarm stats
  from the prior June 13 verification run.

### What Changed

- Added a small successful-create flow helper that calls backend create, then `onTaskCreated`, then
  returns Home.
- `AddTaskScreen` now accepts `onTaskCreated`.
- `BuzerApp` passes `onTaskCreated` through to Add Task.
- `MainActivity` wires `onTaskCreated = ::refreshEnforcement`, so successful task creation now
  immediately refreshes the snapshot and reconciles local alarms.

### Verification

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskCreateFlowTest' --console=plain`
- Result: RED then PASS
- Evidence: initial failure was missing `runSuccessfulAddTaskCreate`; after implementation, focused
  serial rerun passed.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskCreateFlowTest' --tests 'app.buzer.android.ui.addtask.AddTaskProjectLoadFeedbackTest' --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

### Known Failures / Skipped Checks

- A parallel Gradle run briefly corrupted Kotlin incremental caches; `./gradlew --stop` and a serial
  rerun cleared it.
- Resolved later in this session: the updated Android build was installed and verified with a live
  deadline blocker. The start blocker was intentionally not exercised because the saved task's
  `startAt` was already in the past by the time creation completed.

## 2026-06-15 Live Deadline Blocker Verification

### Task / Plan

- Verify that successful Add Task creation now triggers snapshot refresh, local alarm scheduling,
  deadline overlay display, backend completion, and overlay watcher dismissal on Pixel 10
  `56061FDCR005J0`.

### Files Changed

- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md`

### Manual Verification Performed

- User created `test task 2` from the Android Add Task screen with project `Home`, due
  `2026-06-15 22:32 Europe/Warsaw`, and duration `2`.
- The post-create refresh scheduled one deadline alarm. The start alarm did not schedule because
  `startAt=2026-06-15T20:30:00.000Z` was already in the past when the task was saved.
- User locked the phone, waited for the deadline blocker, and tapped Done.

### Verification

- Command: `/bin/zsh -lc 'BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh logcat 260'`
- Result: PASS
- Evidence: logs showed `overlay_start attempted result=true kind=DEADLINE_BLOCKER
  taskId=d7677b00-5445-4d97-95bc-aa2053e139bb` at `22:32:00.143`,
  `deadline_blocker done_clicked` at `22:32:07.142`, `POST
  /tasks/d7677b00-5445-4d97-95bc-aa2053e139bb/done -> 200 OK`, `action success`, and
  `overlay_watcher resolved kind=DEADLINE_BLOCKER` followed by `overlay dismissed`.

- Command: `/bin/zsh -lc 'BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh alarms'`
- Result: PASS
- Evidence: app-scoped alarm dump showed only historical Buzer wakeups after completion; no pending
  live deadline alarm remained for the completed task.

- Command: `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'token=$(docker exec buzer-backend printenv ANDROID_API_TOKEN); curl --fail --silent --show-error -H "Authorization: Bearer $token" http://127.0.0.1:18432/tasks/snapshot'`
- Result: PASS
- Evidence: backend snapshot returned `test task 2` with id
  `d7677b00-5445-4d97-95bc-aa2053e139bb`, Todoist id `6grvFg86GpG8GHQw`, state
  `completed`, `isCompleted=true`, and `updatedAt=2026-06-15T20:32:06.753Z`.

### Known Failures / Skipped Checks

- Start blocker still needs a fresh manual pass with enough lead time, for example due 8 minutes
  out and duration 3 minutes, so `startAt` is still in the future after task creation.
- `test task` from the earlier failed scheduling check remains planned/past-due in the backend and
  Todoist unless cleaned up manually.

## 2026-06-15 Stale Notification Start-Blocker Retest

### Task / Plan

- User reproduced a duplicate start blocker by tapping the blocker notification after already
  tapping Start on the overlay.
- Root cause: the notification content intent launches the fallback `StartBlockerActivity`; that
  activity refreshed state but only used the generic completed/deleted gate, so it did not hide when
  the task had moved to `started`.

### Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGate.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGateTest.kt`
- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md`

### What Changed

- Added an activity-facing gate helper that delegates to the existing kind-aware blocker resolution
  rule.
- `StartBlockerActivity` now hides after refresh when a start blocker is resolved by task state
  `started`, not only when the task is completed/deleted.
- `DeadlineBlockerActivity` now uses the same explicit helper with `DEADLINE_BLOCKER` to keep the
  fallback activity paths symmetrical.

### Verification

- Command: `npx ctx7@latest library "Android" "Android PendingIntent notification contentIntent stale full-screen notification activity should verify current state before showing blocker"`
- Result: PASS
- Evidence: official Android Developers docs source selected as `/websites/developer_android`.

- Command: `npx ctx7@latest docs /websites/developer_android "Android PendingIntent notification contentIntent full-screen intent launches activity when notification tapped stale state apps should handle current state"`
- Result: PASS
- Evidence: docs confirmed `PendingIntent.getActivity` launches an activity and notification launch
  can use `FLAG_ACTIVITY_NEW_TASK` / `FLAG_ACTIVITY_CLEAR_TOP`; fix remains app-side state gating.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.blockers.BlockerTaskSnapshotGateTest' --console=plain`
- Result: RED then PASS
- Evidence: RED failed with unresolved `shouldHideAfterRefresh`; after implementation, focused test
  passed with `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `/bin/zsh -lc 'BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install'`
- Result: PASS
- Evidence: `Installed debug build on 56061FDCR005J0.`

- Command: `/bin/zsh -lc 'BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh logcat 420'`
- Result: PASS
- Evidence: after the user tapped the stale notification, logs showed
  `start_blocker hidden reason=task_resolved taskId=20d4ea8c-84bc-435f-9b00-06805f980035`.
  The deadline blocker then fired, the user tapped Done, `POST
  /tasks/20d4ea8c-84bc-435f-9b00-06805f980035/done` returned `200 OK`, and `action success
  action=COMPLETE_TASK` was logged.

- Command: `ssh -o BatchMode=yes -o ConnectTimeout=8 oc-vps.taila50381.ts.net 'token=$(docker exec buzer-backend printenv ANDROID_API_TOKEN); curl --fail --silent --show-error -H "Authorization: Bearer $token" http://127.0.0.1:18432/tasks/snapshot | python3 -c "...extract task 20d4ea8c-84bc-435f-9b00-06805f980035..."`
- Result: PASS
- Evidence: backend snapshot returned `test task 4`, state `completed`, `isCompleted=True`,
  `startedAt=2026-06-15T20:48:12.147Z`, and `updatedAt=2026-06-15T20:50:02.934Z`.

### Known Failures / Skipped Checks

- Notification cancellation itself is still not centralized; stale notification taps are now
  harmless because the fallback activities refresh and hide, but the notification may still remain
  until Android/user/app flow clears it.
- Earlier live test tasks may still exist in Todoist/backend unless cleaned up manually.

## 2026-06-15 Blocker Notification Cleanup

### Task / Plan

- Follow-up to stale notification handling: remove task blocker notifications after successful
  blocker actions so old notification taps are less likely in the first place.

### Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerNotificationCleaner.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md`

### What Changed

- Added `BlockerNotificationCleaner` with a no-op test/default implementation and an Android
  implementation that cancels the start/deadline blocker notification IDs for a task.
- `EnforcementCoordinator` now cancels blocker notifications after a successful backend action and
  before snapshot refresh/reconcile.
- Android dependency wiring now passes the real cleaner to the coordinator.

### Verification

- Command: `npx ctx7@latest library "Android" "Android NotificationManager cancel notification by id PendingIntent blocker notification cleanup after successful action"`
- Result: PASS
- Evidence: official Android Developers docs source selected as `/websites/developer_android`.

- Command: `npx ctx7@latest docs /websites/developer_android "NotificationManager cancel(int id) cancel notification by id from app Kotlin"`
- Result: PASS
- Evidence: docs confirmed `NotificationManager.cancel(String tag, int id)` cancels previously
  posted notifications and that notifications are identified by tag/id pairs; the app posts blocker
  notifications without tags, using stable IDs.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest' --console=plain`
- Result: RED then PASS
- Evidence: RED failed with missing `blockerNotificationCleaner` / `BlockerNotificationCleaner`;
  after implementation, focused coordinator tests passed with `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `/bin/zsh -lc 'BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install'`
- Result: PASS
- Evidence: `Installed debug build on 56061FDCR005J0.`

### Known Failures / Skipped Checks

- No fresh live task was created after this cleanup change; verification is unit/build/install only.
- Earlier live test tasks may still exist in Todoist/backend unless cleaned up manually.

## 2026-06-16 Android Loading/Error Status Task 1

### Task / Plan

- Plan: `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md`
- Task: Task 1, Shared Status Models and UI Components.
- Checkbox status: Task 1 steps 1 through 6 are complete. Later tasks remain untouched.

### Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/OperationStatus.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/status/OperationStatusTest.kt`
- `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md`
- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md`

### What Changed

- Added pure shared Android UI status models: `OperationStatus` and `ScreenLoadState`.
- Added reusable Compose status primitives for inset-safe content, inline loading, warnings,
  blocking loading/error screens, loading action buttons, and retryable error dialogs.
- Added focused model tests for loading, failed, and blocking screen load states.
- Committed the three Task 1 code/test files as `250b758 feat: add android ui status primitives`.

### Verification

- Command: `npx ctx7@latest library "Compose Multiplatform" "Compose Material3 AlertDialog Button OutlinedButton CircularProgressIndicator Row Column safeDrawingPadding Modifier padding fillMaxSize fillMaxWidth"`
- Result: PASS
- Evidence: selected official JetBrains Compose Multiplatform docs as `/jetbrains/compose-multiplatform`.

- Command: `npx ctx7@latest docs /jetbrains/compose-multiplatform "Compose Material3 AlertDialog Button OutlinedButton CircularProgressIndicator Row Column safeDrawingPadding Modifier padding fillMaxSize fillMaxWidth"`
- Result: PASS
- Evidence: docs showed Compose layout and `Modifier.safeDrawingPadding()` usage consistent with
  the planned component implementation.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.status.OperationStatusTest' --console=plain`
- Result: RED
- Evidence: Kotlin compilation failed with unresolved references to `OperationStatus` and
  `ScreenLoadState`, as expected before adding production files.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.status.OperationStatusTest' --console=plain`
- Result: PASS
- Evidence: after adding the status model and component files, Gradle reported `BUILD SUCCESSFUL`.

- Command: `git diff --cached --check`
- Result: PASS
- Evidence: no whitespace errors were reported before commit.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.status.OperationStatusTest' --console=plain`
- Result: PASS
- Evidence: pre-commit rerun reported `BUILD SUCCESSFUL`.

### Manual Verification

- No device or visual UI verification was performed for this shared primitive-only task.
- Self-review checked the cached diff and confirmed the code commit contained only the three Task 1
  files requested by the task.

### Known Failures / Skipped Checks

- Did not run the full Android unit suite or assemble task; verification was the focused Task 1 test.
- Documentation files were updated after the Task 1 code commit and are intentionally not included
  in commit `250b758`.

### Assumptions

- The plan-provided Compose component signatures are the intended stable API for later wiring tasks.
- `ErrorAlertDialog` keeps retry optional by rendering a confirm button only when both retry label
  and callback are provided.

### Next Recommended Step

- Proceed with Task 2: wire loading, refresh, and error states into Home.

## 2026-06-16 Task 2 Home Loading, Refresh, And Error States

### Task / Plan

- Plan: `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md`
- Task: Task 2, Home Loading, Refresh, and Error States.
- Checkbox status: Steps 1 through 8 completed and marked `[x]` in the plan after the code commit.

### Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md` (uncommitted)
- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md` (uncommitted)

### What Changed

- Added explicit `HomeContentState` values for loading, ready, and blocking error.
- Updated `buildHomeScreenState` to distinguish first-load loading, no-data refresh error, ready refresh, and ready stale-data warning.
- Updated Home UI to keep the headline visible, use shared loading/warning/error components, hide counts/actions while loading or blocked by no-data error, and expose retry.
- Added MainActivity Home refresh in-flight/error state and retry wiring.
- Wrapped the normal app content in `InsetSafeContent`.
- Built Home state once in `BuzerApp` and applied `homeStatusMessage` only as an override.

### Verification

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain`
- Result: RED
- Evidence: compile failed as expected with unresolved `HomeContentState`, missing `refreshInFlight`/`refreshError` parameters, and missing `contentState`.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL` after implementation.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

### Manual Verification

- Not run on a device or emulator in this pass. Verification was limited to focused and broad JVM/unit test commands.

### Known Failures / Skipped Checks

- Skipped real-device visual verification of Android 15+ system-bar safe area behavior.
- Skipped instrumentation tests; Task 2 only changed Home/App state/rendering and MainActivity refresh wiring.

### Assumptions

- Retrying after a no-snapshot refresh error should clear the previous Home refresh error/status while the retry is in flight, so Home returns to the blocking loading state until the retry succeeds or fails.
- `homeStatusMessage` remains an override for existing activity-level refresh messages; derived stale/refreshing status is used when no override is present.

### Commit

- `0c7e5d3 feat: show home loading and refresh states`

### Next Recommended Step

1. Controller can review/stage the uncommitted plan and handoff updates, then proceed to Task 3.

## 2026-06-16 Task 2 Review Fix: Derived Home Refresh Errors

### Task / Plan

- Follow-up review fix for Task 2 in `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md`.
- Issue: generic `homeStatusMessage = "Refresh failed"` masked derived Home states from `homeRefreshError`.

### Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md` (uncommitted)

### What Changed

- Removed `homeStatusMessage` from `MainActivity` and `BuzerApp`.
- Added `buildBuzerHomeState` so app-level Home state always uses the derived `buildHomeScreenState` refresh behavior.
- Added pure tests for app-level no-snapshot refresh error and stale-data refresh failure rendering.
- Preserved refresh failure reporting through `homeRefreshError`.

### Verification

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain`
- Result: RED
- Evidence: compile failed as expected with unresolved `buildBuzerHomeState` before implementation.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `git diff --cached --check`
- Result: PASS
- Evidence: no whitespace errors before commit.

### Manual Verification

- Not run on a device or emulator. Verification was JVM/unit focused.

### Known Failures / Skipped Checks

- Skipped visual verification of Home rendering; pure state tests cover the masking regression.

### Commit

- `10d631c fix: use derived home refresh errors`

### Next Recommended Step

1. Controller can review/stage the uncommitted handoff/plan docs and proceed to Task 3.

## 2026-06-16 Task 2 Review Fix: Refresh Started Race

### Task / Plan

- Follow-up review fix for Task 2 in `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md`.
- Issue: `MainActivity.refreshEnforcement()` set `homeRefreshInFlight` and cleared `homeRefreshError` after `EnforcementRefreshRunner.refresh` launched the coroutine, allowing a fast completion/failure to be overwritten.

### Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunner.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunnerTest.kt`
- `docs/handoffs/2026-06-15-vps-deploy-and-home-in-progress.md` (uncommitted)

### What Changed

- Added `onStarted` to `EnforcementRefreshRunner.refresh`, called after the active-job guard and before `launch`.
- Preserved overlapping refresh skip behavior: skipped refreshes return `null` and do not call `onStarted`.
- Moved `MainActivity` Home refresh state setup into `onStarted`, before the coroutine can complete.
- Added runner tests for callback ordering and skipped overlapping refresh behavior.

### Verification

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementRefreshRunnerTest' --console=plain`
- Result: RED
- Evidence: compile failed as expected with missing `onStarted` parameter before implementation.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementRefreshRunnerTest' --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.HomeScreenStateTest' --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

- Command: `git diff --cached --check`
- Result: PASS
- Evidence: no whitespace errors before commit.

### Manual Verification

- Not run on a device or emulator. Verification was JVM/unit focused.

### Known Failures / Skipped Checks

- Skipped visual/device verification; the fix is scoped to refresh runner ordering and MainActivity state timing.

### Commit

- `b828f8b fix: set home refresh state before launch`

### Next Recommended Step

1. Controller can review/stage the uncommitted handoff/plan docs and proceed to the next task.

## 2026-06-15 Android Loading/Error/Insets Implementation

### Task / Plan

- Executed `docs/superpowers/plans/2026-06-15-android-loading-error-and-insets.md`.
- All checkboxes complete: Task 1 (Shared Status Models and UI Components), Task 2 (Home Loading,
  Refresh, and Error States), Task 3 (Add Task Project and Submit Feedback), Task 4 (Blocker
  Checking and Action Progress), Task 5 (Morning Review Loading and Action Feedback), and Task 6
  (Final Verification, Device Install, and Handoff).

### Files Changed

- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunner.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/OperationStatus.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunnerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/status/OperationStatusTest.kt`

### Verification

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 16s`, 70 actionable tasks (5 executed, 65 up-to-date).

- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install`
- Result: PASS
- Evidence: `Installed debug build on 56061FDCR005J0.`

### Manual Verification

- System bar insets (Home): PASS. Screenshot on Pixel 10 shows the "Today" title and content
  starting clearly below the status bar in both the loading state and the loaded state.
- System bar insets (Add Task): PASS. Screenshot shows "Add committed task" title and the project
  picker fully below the status bar; launched directly via
  `am start -n app.buzer.android/.MainActivity --ez app.buzer.extra.OPEN_ADD_TASK true`.
- System bar insets (blocker, live trigger): SKIPPED: no fresh blocker was triggered after the
  insets change. Home reported `Active blockers: 0` for the duration of this session.
- Add Task project error path: SKIPPED: project load failure path not manually triggered because
  it would interrupt the active device/backend test setup. The device's Tailscale connection to
  the backend (`100.105.180.34:18432`) was observed dropping and reconnecting on its own during
  this session; the drop incidentally exercised the Home blocking-error state (`ConnectException`,
  retryable error message, visible "Retry" button, no status-bar overlap — see logcat evidence
  below), but by the time Add Task was opened the connection had recovered (`add_task projects
  load_success count=8`), so a deliberate disconnect was not forced to avoid disrupting the live
  device/backend session.
- Incidental Home error-state evidence (not a deliberate trigger): logcat showed
  `java.net.ConnectException: Failed to connect to /100.105.180.34:18432` followed by `main refresh
  failed`; the resulting screenshot showed the red error text and a "Retry" button below the status
  bar, confirming the Task 2 blocking-error UI and inset handling work together correctly.

### Known Failures / Skipped Checks

- Live blocker inset check and deliberate Add Task project-error trigger were not performed this
  session (see SKIPPED notes above). Recommend re-running these two checks the next time a live
  blocker fires or a controlled backend outage can be scheduled without disrupting active use.
