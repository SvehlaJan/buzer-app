# 2026-06-13 Project-Aware Task Creation

## Task / Plan

- Plan: `docs/superpowers/plans/2026-06-13-project-aware-task-creation.md`
- Branch: `feature/project-aware-task-creation`
- Checkbox status: Tasks 1-9 complete. Task 10 local verification complete through backend, fake backend, shared, Android unit tests, Android assemble, and deployment-path lookup. Device install/live verification is blocked because ADB reports no connected devices.

## Files Changed

- Backend project mirror, Todoist REST create, project sync/API, project-aware task creation validation, commitment ownership, and snapshot ownership:
  - `backend/src/db/migrations.ts`
  - `backend/src/db/schema.sql`
  - `backend/src/http/routes.ts`
  - `backend/src/repositories/projectRepository.ts`
  - `backend/src/repositories/taskRepository.ts`
  - `backend/src/server.ts`
  - `backend/src/services/taskActionService.ts`
  - `backend/src/services/taskSnapshotService.ts`
  - `backend/src/services/todoistSyncService.ts`
  - `backend/src/todoist/httpTodoistAdapter.ts`
  - `backend/src/todoist/todoistAdapter.ts`
  - `backend/src/todoist/todoistTypes.ts`
- Backend tests:
  - `backend/test/db/migrations.test.ts`
  - `backend/test/fakes/fakeTodoistAdapter.ts`
  - `backend/test/http/routes.test.ts`
  - `backend/test/repositories/projectRepository.test.ts`
  - `backend/test/repositories/taskRepository.test.ts`
  - `backend/test/services/taskActionService.test.ts`
  - `backend/test/services/taskSnapshotService.test.ts`
  - `backend/test/services/todoistSyncService.test.ts`
  - `backend/test/todoist/httpTodoistAdapter.test.ts`
- Shared and Android client:
  - `client/gradle/libs.versions.toml`
  - `client/shared/build.gradle.kts`
  - `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
  - `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
  - `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/tasks/ProjectSelectionStore.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Fake backend and docs:
  - `tools/fake-backend/src/server.ts`
  - `tools/fake-backend/src/server.test.ts`
  - `docs/notes/api-contract.md`
  - `docs/superpowers/plans/2026-06-13-project-aware-task-creation.md`
  - `docs/handoffs/2026-06-13-task-3-projects-endpoint.md`
  - `docs/handoffs/2026-06-13-project-aware-task-creation.md`

## Commands Run

Verification:
- Command: `cd backend && npm test`
- Result: PASS
- Evidence: Vitest reported 15 test files passed, 56 tests passed.

Verification:
- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed successfully.

Verification:
- Command: `cd backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` completed and copied `src/db/schema.sql` to `dist/db/schema.sql`.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: Node test runner reported 13 tests passed, 0 failed.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Gradle reported `BUILD SUCCESSFUL`; 70 actionable tasks, 2 executed, 68 up-to-date.

Verification:
- Command: `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install`
- Result: FAIL
- Evidence: Gradle `:androidApp:installDebug` failed with `DeviceException: No connected devices!`.

Verification:
- Command: `adb devices -l`
- Result: PASS
- Evidence: ADB printed `List of devices attached` with no devices listed.

Verification:
- Command: `sed -n '1,220p' docs/notes/vps-deployment.md`
- Result: PASS
- Evidence: Deployment path identified: VPS `oc-vps.taila50381.ts.net`, checkout `/home/skclaw/buzer-app`, manual deploy command `/home/skclaw/buzer-app/scripts/deploy-vps.sh`, backend port `18432`.

## Manual Verification

- Assignable project create result: SKIPPED. Requires device install and live backend deployment.
- Non-assignable project create result: SKIPPED. Requires device install and live backend deployment.
- Alarm scheduling result: SKIPPED. Requires device install and live backend deployment.

## Known Failures / Skipped Checks

- Device install failed because no Android device was visible to ADB.
- Live Todoist/backend smoke was not run.
- VPS deployment was not performed in this session.
- WhatsApp behavior was not exercised.
- Existing unrelated dirty files remain from the prior blocker/overlay watcher work:
  - `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGate.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGateTest.kt`
  - `docs/handoffs/2026-06-13-context-status-and-next-step.md`
  - `docs/handoffs/2026-06-13-real-alarm-verification.md`
  - `docs/notes/2026-06-13-product-backlog.md`

## Assumptions

- Todoist `can_assign_tasks` is the backend source of truth for whether Android must require `assigneeId`.
- Non-assignable projects represent personal commitments owned by the current Buzer user; backend stores `commitment_owner_id` so snapshots still mark them assigned to the current user.
- Android must not silently choose the first project on first open. It only preselects a previously persisted/initial project ID that still exists.
- Todoist REST task responses can carry fixed due datetimes in `due.date`; the adapter now maps and normalizes timezone-bearing due-object datetimes before create verification.

## Final Review Fixes

- Fixed `HttpTodoistAdapter` to read `due.date` as a Todoist due datetime fallback and normalize fractional-second UTC values such as `2026-05-08T16:00:00.000000Z` to `2026-05-08T16:00:00Z`.
- Tightened fake-backend create tests so the assigned create path uses `projectId: "project-test"` instead of the production-invalid `projectId: null`.

## Next Recommended Step

1. Connect and authorize the Android device, rerun `BUZER_DEVICE_SERIAL=56061FDCR005J0 scripts/adb-verify.sh install`.
2. Deploy the backend to the VPS or run against the intended live backend.
3. Run manual Add Task verification for one assignable project and one non-assignable project, then inspect logs/alarms with `scripts/adb-verify.sh`.
4. After live create/alarm verification, proceed to the Home in-progress task UI backlog item.
