# Handoff: Todoist Task Details Across The Stack

Date: 2026-07-09
Agent: Codex implementation subagent
Plan: `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
Task: Task 1, "Add Todoist Task Details Across The Stack"

## Checkbox Status

- [x] Step 1: Add backend failing tests for Todoist description mirroring
- [x] Step 2: Add backend schema and repository support
- [x] Step 3: Add Todoist adapter description pass-through
- [x] Step 4: Add service and HTTP route support
- [x] Step 5: Add shared Kotlin DTO support
- [x] Step 6: Add Android validator support
- [x] Step 7: Update fake backend fixtures and assertions
- [x] Step 8: Verify Task 1
- [x] Step 9: Commit

## Files Changed

- Backend schema/migration/repository/action/snapshot/sync/Todoist adapter/HTTP route support for Todoist `description`.
- Backend tests for migration, repository fixtures, action service create/reschedule, snapshot output, sync, HTTP routes, and HTTP Todoist adapter payload semantics.
- Shared Kotlin DTOs in `TaskModels.kt` plus JSON tests.
- Android `AddTaskFormValidator` plus validator tests.
- Fake backend task fixtures and server tests.
- Plan checkbox updates in `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`.

## Commands And Results

Verification:
- Command: `git status --short --branch`
- Result: PASS
- Evidence: Started on `feature/android-ui-task-details`; initial worktree was clean.

Verification:
- Command: `npx ctx7@latest library "Todoist API" "Task create update sync API description field support for create task, item_update, and returned task objects"`
- Result: PASS
- Evidence: Resolved `/websites/developer_todoist_api_v1`.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_todoist_api_v1" "Task create update sync API description field support for create task, item_update, and returned task objects"`
- Result: PASS
- Evidence: Confirmed create/update and Sync `item_update` support `description`, and returned task objects include it.

Verification:
- Command: `cd backend && npm test`
- Result: FAIL (RED, after test additions)
- Evidence: Expected failures for missing `todoist_tasks.description`, create/reschedule description pass-through, and snapshot `description`. An earlier first attempt failed due local `better-sqlite3` ABI mismatch; `cd backend && npm install` rebuilt local dependencies and emitted only engine/audit warnings.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, after Kotlin test additions)
- Evidence: Expected compile failures for missing DTO `description` fields and missing `AddTaskFormValidator.validate(description = ...)` parameter.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: FAIL (RED, after fake-backend test additions)
- Evidence: Expected fixture failure: active task description was `undefined` before fixture updates.

Verification:
- Command: `cd backend && npm test`
- Result: PASS
- Evidence: 16 test files passed, 65 tests passed.

Verification:
- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed successfully.

Verification:
- Command: `cd backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` completed and copied `schema.sql` into `dist/db`.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: 16 tests passed.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Build successful; shared Android host tests and androidApp debug unit tests passed/up-to-date.

## Manual Verification

- Reviewed Todoist description semantics against current Context7 Todoist docs.
- Confirmed no Task 2 UI redesign/theme files were created or modified.
- Confirmed reschedule distinguishes omitted description from null/blank/set values through service and HTTP route tests.

## Known Failures Or Skipped Checks

- No emulator/instrumentation lane was run because Task 1 changes DTO/validator behavior only and does not change Android UI surfaces.
- `cd backend && npm install` emitted a Node engine warning because the current local Node is v26.4.0 while backend declares Node 22.x; verification commands still passed afterward.
- `npm install` also reported existing low/moderate audit findings; no package files were changed.

## Assumptions

- Todoist `description` is Todoist-owned visible task data and remains the only backing field for task details.
- Omitted reschedule `description` leaves the mirrored description unchanged; null or blank clears it; nonblank sets trimmed text.
- Create requests normalize omitted description to `null` and reject blank supplied descriptions at the HTTP schema boundary.

## Next Recommended Step

Start Task 2 from the same plan only after this Task 1 commit is available to later UI redesign agents.

## Spec Review Fix

Date: 2026-07-09

Spec review found that shared Kotlin reschedule requests could not distinguish omitted `description` from explicit null because `buzerApiJson` uses `explicitNulls = false`. Fixed only the Task 1 compliance gap:

- `RescheduleTaskRequest(...)` with no description keeps the existing leave-unchanged behavior and emits no `description` key.
- `RescheduleTaskRequest.clearDescription(...)` emits `"description": null` to clear via the backend route.
- `RescheduleTaskRequest.setDescription(..., description = "   ")` emits `"description": ""` to clear via blank value.
- `RescheduleTaskRequest.setDescription(..., description = "  details  ")` emits trimmed text.
- Existing simple reschedule call sites remain source-compatible and leave description unchanged.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest --console=plain`
- Result: FAIL (RED)
- Evidence: Expected compile failure before implementation: missing `RescheduleTaskRequest.clearDescription` and `setDescription` APIs in `BuzerApiClientJsonTest`.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest --console=plain`
- Result: PASS
- Evidence: Build successful after adding the custom reschedule request serializer and patch helpers.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Build successful; shared Android host tests and androidApp debug unit tests passed/up-to-date.

Known failures or skipped checks:
- Backend and fake-backend checks were not rerun for this review fix because no backend or fake-backend files changed and backend route semantics were already covered in the original Task 1 verification.

Next recommended step:
- Re-run spec review for Task 1, then proceed to Task 2 only after approval.
