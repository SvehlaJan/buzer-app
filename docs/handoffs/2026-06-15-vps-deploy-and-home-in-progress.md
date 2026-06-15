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
