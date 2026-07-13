# Handoff: Split Date Time Editor

Date: 2026-07-13
Plan: `docs/superpowers/plans/2026-07-13-split-date-time-editor.md`
Task: Show Todoist date-only values in the editor, split date/time picker controls, and validate Morning Review repair immediately.

## Status

- Added `dueDate` to task snapshots for Todoist date-only `due.date` values while keeping `dueDatetime` null so exact-time validation remains strict.
- Added `DateTimeDraftSelection` for partial date/time editor state.
- `CommitmentEditor` now shows two equal start buttons: date and time. Both open the existing unified wheel date-time picker.
- Add Task, Morning Review repair, and Deadline Reschedule now use the shared split date/time editor.
- Morning Review repair initializes date-only tasks with the date visible and time missing, and it shows validation errors as soon as the edit screen opens.

## Files Changed

- Backend snapshot: `backend/src/services/taskSnapshotService.ts`, `backend/test/services/taskSnapshotService.test.ts`
- Shared model: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Android editor/date-time UI: `CommitmentEditor.kt`, `DateTimeSelection.kt`, `WheelDateTimeField.kt`
- Android callers: `AddTaskScreen.kt`, `BlockerScreens.kt`, `MorningReviewScreen.kt`
- Tests: `MorningReviewScreenStateTest.kt`, `DateTimeSelectionTest.kt`, `WheelDateTimeFieldTest.kt`
- Plan: `docs/superpowers/plans/2026-07-13-split-date-time-editor.md`

## Verification

- Command: `npm test -- test/services/taskSnapshotService.test.ts` in `backend`
- Result: PASS
- Evidence: 8 snapshot tests passed, including date-only `dueDate` regression.

- Command: `npm test` in `backend`
- Result: PASS
- Evidence: 16 test files, 70 tests passed.

- Command: `npm run typecheck` in `backend`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` completed.

- Command: `npm run build` in `backend`
- Result: PASS
- Evidence: backend TypeScript build completed.

- Command: `./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.datetime.DateTimeSelectionTest --tests app.buzer.android.ui.pickers.WheelDateTimeFieldTest --tests app.buzer.android.ui.MorningReviewScreenStateTest --console=plain` in `client`
- Result: PASS
- Evidence: focused date-time, picker, and Morning Review state tests passed.

- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain` in `client`
- Result: PASS
- Evidence: shared JVM tests, Android unit tests, and debug APK assembly completed.

- Command: `git diff --check -- <changed fix files>`
- Result: PASS
- Evidence: no whitespace errors in the files changed for this fix.

## Manual Verification

- Not performed.

## Known Failures Or Skipped Checks

- `scripts/e2e-fake-backend.sh` skipped because the user previously requested manual testing instead of e2e for this work.
- Device install skipped because `adb devices` showed no connected devices.
- `docs/superpowers/specs/2026-07-13-backend-internet-exposure-design.md` was already dirty and unrelated; it was intentionally left untouched.

## Assumptions

- `dueDate` is display/edit context only. A task still needs an exact `dueDatetime` before it can become valid.
- Both date and time buttons opening the unified wheel picker matches the requested interaction and avoids introducing a second picker flow.

## Next Recommended Step

- Deploy the backend so `/tasks/snapshot` includes `dueDate`, then install the Android build and manually open a date-only invalid task. The edit screen should show the date button populated and the time button as missing/required.
