# Handoff: Unified Task Editor Task 7 — Retire Legacy Editor and Reschedule Protocol

Date: 2026-07-23
Agent: unified_editor_task7
Plan: docs/superpowers/plans/2026-07-22-unified-task-editor.md
Worktree: /Users/svehla/playground/buzer-app/.worktrees/unified-task-editor-task7
Branch: feature/unified-task-editor-task7

## Task / Checkbox Status

- [x] Task 7 — retired the legacy visual editor and dedicated schedule-only protocol atomically.
- [ ] Task 8 — not attempted; it remains responsible for real-backend Android E2E and release verification.

## Scope Attempted

- Moved reusable recurrence-choice and direct-checklist draft/validation/mutation state from
  shared.ui.commitment to shared.ui.taskeditor.TaskEditorModels, then migrated active imports and
  retained model tests.
- Deleted CommitmentEditor, WheelDateTimeField, their dedicated tests, the obsolete
  saveActionLabel bridge/assertions, and the shared CommitmentEditorDraft test fixture.
- Removed RescheduleTaskRequest, its serializer, and BuzerApiClient.rescheduleTask; complete
  UpdateTaskRequest remains the sole client mutation contract.
- Removed the Fastify route/schema and TaskActionService.reschedule. The recurrence helper used
  by full schedule updates now has a neutral task-update name.
- Migrated route/service coverage to POST /tasks/:id/update. The real in-process E2E server asserts
  due/duration results, normal reconciliation, and a same-action retry yielding one journal item_update command.
- Removed TodoistAdapter.rescheduleTask, its input, production/fake implementations, and direct
  adapter tests. Reference proof before deletion: every declaration/implementation/test was reachable
  only from deleted TaskActionService.reschedule; full update behavior already uses
  TodoistMutationGateway.itemUpdate and remains tested.
- Removed the commitment-state transition branch that was exclusive to the retired service action.

## Files Changed

- Backend route/service/domain, Todoist adapter/fake sources, and their direct route/service/e2e/adapter tests.
- Shared TaskModels, BuzerApiClient, API JSON tests, reducer fixture, and new taskeditor model/test files.
- Android legacy editor/wheel sources and direct tests; checklist imports in Add/Edit/Morning Review/Blocker,
  plus the unused Save bridge.
- This handoff and the Task 7 plan checkbox.

## TDD Evidence

RED:

- Command: npm test --prefix backend -- routes.test.ts
  - Result: expected FAIL.
  - Evidence: direct Fastify injection expected 404 but the still-registered endpoint returned 202.
- Command: ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*TaskEditorModelsTest'
  - Result: expected FAIL.
  - Evidence: retained recurrence/checklist symbols were unresolved in shared.ui.taskeditor before
    TaskEditorModels.kt was created.

GREEN:

- Command: npm test --prefix backend -- routes.test.ts e2eServer.test.ts taskActionService.test.ts
  - Result: PASS.
  - Evidence: 3 files / 103 tests, including direct route absence and real e2e update/retry reconciliation.
- Command: ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest --tests '*TaskEditorModelsTest' --tests '*BuzerApiClientJsonTest'
  - Result: PASS.
  - Evidence: moved task-editor models and update serialization pass.

## Verification

Verification:
- Command: npm test --prefix backend
- Result: PASS
- Evidence: 32 files / 384 tests passed.

Verification:
- Command: npm run typecheck --prefix backend
- Result: PASS
- Evidence: tsc -p tsconfig.json --noEmit completed with exit code 0.

Verification:
- Command: ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest
- Result: PASS
- Evidence: BUILD SUCCESSFUL.

Verification:
- Command: ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:testDebugUnitTest :androidApp:compileDebugAndroidTestKotlin
- Result: PASS
- Evidence: BUILD SUCCESSFUL; both Android JVM tests and fake-named Android test source compilation
  passed against Task 6 update selectors without being executed.

Verification:
- Command: rg -n -S 'RescheduleTaskRequest|rescheduleTask|/reschedule|CommitmentEditor|WheelDateTimeField|saveActionLabel' backend/src client/shared/src/commonMain client/androidApp/src/main --glob '!**/build/**'
- Result: PASS
- Evidence: no product-source matches.

Verification:
- Command: rg -n -S 'RescheduleTaskRequest|rescheduleTask|/reschedule|CommitmentEditor|WheelDateTimeField|saveActionLabel' backend/test client/shared/src/commonTest client/androidApp/src/test client/androidApp/src/androidTest --glob '!**/build/**'
- Result: PASS
- Evidence: the sole test-tree match is the deliberate direct HTTP route-absence literal, which
  asserts the retired route returns 404 and is not a product path.

Verification:
- Command: git diff --check
- Result: PASS
- Evidence: no whitespace errors before staging.

## Manual Verification

- None. No device/emulator test was launched; Task 8 owns real-backend runtime verification.

## Known Failures / Skipped Checks

- scripts/e2e-fake-backend.sh was not run; tools/fake-backend and its scripts were not started or modified.
- scripts/e2e-real-backend.sh was not run; Task 8 owns the real-backend E2E/release gate.
- No deployment, push, merge, or APK installation was attempted.
- The first Android JVM run emitted four pre-existing No cast needed warnings in unrelated
  EnforcementCoordinatorTest.kt. The refreshed final Gradle run was successful.

## Atomic Deployment Note

This is an atomic backend/client source cutover. When Task 8 authorizes release, deploy the backend
commit and install/release the matching APK immediately. If rollback is required, revert backend
and APK together to the prior compatible release; no compatibility shim remains for an older APK.

## Next Recommended Step

- Run Task 8 only: real-backend Android E2E and final release gates, then deploy the matching
  backend/client pair atomically if those gates pass.
