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
