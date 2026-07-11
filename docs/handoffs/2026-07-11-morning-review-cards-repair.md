# Handoff: Morning Review Cards And Repair Reuse

Date: 2026-07-11
Agent: Codex implementation worker
Plan: `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
Task: Task 8, "Morning Review Cards And Repair Reuse"

## Checkbox Status

- [x] Step 1: Add Morning Review state tests
- [x] Step 2: Replace list/repair duplication
- [x] Step 3: Keep action failure behavior
- [x] Step 4: Verify Task 8
- [x] Step 5: Commit

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
- `docs/handoffs/2026-07-11-morning-review-cards-repair.md`

## Commands Run

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: Initial worktree was clean before Task 8 edits.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose Material 3" "Card Button TextButton AlertDialog Compose test semantics"`
- Result: PASS
- Evidence: Resolved `/websites/composables_jetpack-compose_androidx_compose_material3_material3`.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose testing" "Compose UI test text field performTextInput testTag contentDescription"`
- Result: PASS
- Evidence: Resolved `/websites/developer_android_develop_ui_compose_testing`.

Verification:
- Command: `npx ctx7@latest docs "/websites/composables_jetpack-compose_androidx_compose_material3_material3" "Card TextButton Button AlertDialog dismissButton Compose Material 3"`
- Result: PASS
- Evidence: Docs covered Material 3 Button/TextButton APIs and enabled states.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android_develop_ui_compose_testing" "Compose UI test onNodeWithText performTextInput testTag contentDescription"`
- Result: PASS
- Evidence: Docs covered `onNodeWithText`, click actions, text input, semantics, and content descriptions.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected)
- Evidence: Compile failed because `buildMorningReviewReadyState` and `buildMorningReviewRepairEditorState` did not exist yet.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed after adding Morning Review presentation state, compact repair-card copy, repair editor state, and all-clear state.

Verification:
- Command: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: Instrumentation sources compiled after migrating Morning Review selectors to shared editor labels.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed and `assembleDebug` completed successfully.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 24 tests with 0 skipped and 0 failed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: No whitespace errors.

## Tests Run

- `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`: RED then PASS.
- `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`: PASS.
- `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`: PASS.
- `scripts/e2e-fake-backend.sh`: PASS.

## Manual Verification

- Confirmed Morning Review ready content now shows header copy like `1 task needs attention` / `3 tasks need attention`, with progress only in the header.
- Confirmed invalid task cards render task title, plain-language problem copy, optional Todoist details via `TaskDetailsBlock`, primary `Repair`, and quiet `Snooze`.
- Confirmed repair uses shared `CommitmentEditor` with `CommitmentEditorMode.Repair`, title editing disabled, editable details, read-only project/assignee receipt rows, active Start and Duration pickers, and computed `Save HH:MM-HH:MM` labels.
- Confirmed repair submit calls the shared description-aware reschedule path: unchanged details omit description, blank clears, edited details send trimmed description.
- Confirmed action failures still use `ErrorAlertDialog` with `Retry`; Morning Review failure dialogs still expose `Dismiss`, unlike production Start/Deadline blockers.

## Known Failures Or Skipped Checks

- All required final checks passed.
- All-clear behavior is covered by `MorningReviewScreenStateTest`; there is no fake-backend control in the current instrumentation harness to serve an empty invalid-task Morning Review snapshot, so no instrumentation all-clear test was added.
- The managed-device build emitted the existing native-library strip warning for `libandroidx.graphics.path.so`; packaging and tests continued successfully.

## Assumptions

- `TaskSnapshotItem` currently carries project and assignee IDs, not display names; the repair editor renders those IDs as read-only receipt content when present.
- For repair prefill, `startAt` is preferred. If only `dueDatetime` and `durationMinutes` exist, start is derived as `dueDatetime - durationMinutes`; if duration is missing, start remains unset.
- Morning Review remains dismissable where existing behavior expects it: all-clear `Done`, optional `Back` when `showBack` is true, and dismissable action-error dialog.

## Next Recommended Step

Proceed to Task 9 overlay only after the Task 8 commit is reviewed. Do not include overlay or Task 10 polish changes in this Task 8 branch scope.
