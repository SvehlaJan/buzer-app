# Handoff: Shared BlockerSurface And Full-Screen Blockers

Date: 2026-07-10
Agent: Codex implementation subagent
Plan: `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
Task: Task 7, "Shared BlockerSurface And Full-Screen Blockers"

## Checkbox Status

- [x] Step 1: Add blocker UI model tests
- [x] Step 2: Create shared blocker models
- [x] Step 3: Create `BlockerSurface`
- [x] Step 4: Migrate Start blocker
- [x] Step 5: Migrate Deadline blocker and reschedule entry
- [x] Step 6: Update instrumentation tests
- [x] Step 7: Verify Task 7
- [x] Step 8: Commit

## Files Changed

- `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/blockers/BlockerUiModels.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/blockers/BlockerUiModelsTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BlockerSurface.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
- `docs/handoffs/2026-07-10-shared-blocker-surface-fullscreen.md`

## Commands And Results

Review-fix update, 2026-07-11:
- Fixed SPEC REVIEW finding 1: `BlockerUiModel` window creation now treats `dueDatetime` as deadline/end, uses `startAt` as start, and falls back to `dueDatetime - durationMinutes` only when `startAt` is missing.
- Fixed SPEC REVIEW finding 2: deadline reschedule now submits `Start picker value + durationMinutes` as `RescheduleTaskRequest.dueDatetime`.
- Fixed SPEC REVIEW finding 3: instrumentation now captures and clicks a concrete `Save HH:mm-HH:mm` label rather than a broad `Save ` substring.
- Fixed SPEC REVIEW finding 4: production Start and Deadline blocker instrumentation asserts no `Back` affordance.
- Plan/task/steps attempted: Task 7 review-fix only; no Task 8 or overlay work was started.
- Commit handling: the original Task 7 commit was amended after these review fixes.

Second review-fix update, 2026-07-11:
- Fixed SPEC RE-REVIEW no-dismiss gap: production blocker action-failure dialogs now keep `Retry` visible but omit `Dismiss` and ignore outside/back dismiss requests.
- Preserved dismissable error dialogs for other screens by making `ErrorAlertDialog.dismissLabel` optional with a default of `Dismiss`; only `StartBlockerScreen` and `DeadlineBlockerScreen` pass `dismissLabel = null`.
- Added instrumentation assertions that Start, Deadline, and Deadline reschedule production failure dialogs expose `Retry` and no `Dismiss`.
- Plan/task/steps attempted: Task 7 blocker failure-path no-dismiss review-fix only; no Task 8 or overlay work was started.
- Commit handling: the Task 7 commit was amended again after this re-review fix.

Quality-review fix update, 2026-07-11:
- Fixed P1 system-back escape: production Start and Deadline blocker screens now install a Compose `BackHandler` when `showBack = false`, so system back gestures/buttons are consumed and cannot finish the production blocker.
- Fixed P2 DST formatting: shared blocker window formatting now accepts an IANA timezone id and converts start and end instants independently with `Instant.toLocalDateTime(timeZone)`, so DST-crossing blocks display each endpoint with the correct local offset.
- Android blocker activities now pass `ZoneId.systemDefault().id` into the shared blocker model builder instead of a single offset computed from `dueDatetime`.
- Added a shared unit regression for the Europe/Berlin 2026 spring-forward transition and production instrumentation regressions using `pressBack()` for Start and Deadline blockers.
- Plan/task/steps attempted: Task 7 quality-review blocker fixes only; no Task 8 or overlay work was started.
- Commit handling: the Task 7 commit will be amended again after this quality-review fix.

Quality re-review test-gap update, 2026-07-11:
- Fixed the remaining failure-path instrumentation gap by pressing system Back while the production failure dialog is visible for Start failure, Deadline reschedule failure, and Deadline done failure.
- The assertions verify `Action failed` and `Retry` remain visible, `Dismiss` remains absent, and the production blocker activity remains alive after Back.
- This was test-only: existing `dismissLabel = null`, ignored `onDismissRequest`, and production `BackHandler` behavior already satisfied the new checks.
- Plan/task/steps attempted: Task 7 failure-dialog back-press coverage only; no Task 8 or overlay work was started.
- Commit handling: the Task 7 commit will be amended again after this test-gap fix.

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: Initial worktree was clean before Task 7 edits.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose Material 3" "Surface Column text buttons accessibility full screen blocker Compose"`
- Result: PASS
- Evidence: Resolved `/websites/composables_jetpack-compose_androidx_compose_material3_material3`.

Verification:
- Command: `npx ctx7@latest docs "/websites/composables_jetpack-compose_androidx_compose_material3_material3" "Surface Column text buttons accessibility full screen blocker Compose"`
- Result: PASS
- Evidence: Docs covered Material 3 `Surface` and `TextButton` APIs, including themed surfaces, content slots, enabled state, and button content.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected)
- Evidence: Compile failed because `buildBlockerUiModel`, `BlockerKind`, and `BlockerDisplayMode` did not exist yet.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected)
- Evidence: Compile failed because `descriptionAwareRescheduleRequest` did not exist yet.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Shared blocker model tests and Android unit tests passed after adding shared models, `BlockerSurface`, migrated screens, and description patch helper.

Verification:
- Command: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: Instrumentation sources compiled after blocker fake-backend assertions were updated.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: FAIL (iteration)
- Evidence: Initial e2e failed in blocker instrumentation after the shared surface changed duration copy (`1h block` vs old minute expectation) and exposed reschedule selector/scroll assumptions.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: FAIL (iteration)
- Evidence: Reschedule editor opened with preserved scroll state after clicking a lower action, hiding the editor header/control assertions. Fixed by resetting `BlockerSurface` scroll when model identity changes and by asserting actual picker controls.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 22 tests with 0 skipped and 0 failed.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Shared Android host tests, Android debug unit tests, and `assembleDebug` completed successfully after final patches.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: No whitespace errors.

Verification:
- Command: `npx ctx7@latest library "kotlinx.datetime" "Instant LocalDateTime duration startAt dueDatetime Kotlin Multiplatform"`
- Result: PASS
- Evidence: Resolved `/kotlin/kotlinx-datetime`.

Verification:
- Command: `npx ctx7@latest docs "/kotlin/kotlinx-datetime" "Instant LocalDateTime duration startAt dueDatetime Kotlin Multiplatform"`
- Result: PASS
- Evidence: Docs confirmed doing arithmetic on `Instant` and converting to `LocalDateTime` only for human-readable output.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected review fix)
- Evidence: Compile failed because new test `rescheduleRequestUsesEditorStartPlusDurationAsDeadline` referenced missing `rescheduleRequestFromEditorStart`; shared blocker model tests were also changed to expect `startAt`/`dueDatetime` start/end semantics.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Shared blocker model tests and Android unit tests passed after fixing block-window semantics and adding `rescheduleRequestFromEditorStart`.

Verification:
- Command: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: Instrumentation sources compiled after adding no-Back assertions and concrete computed save-label matching.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Shared Android host tests, Android debug unit tests, and `assembleDebug` completed successfully after review fixes.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: FAIL (iteration, unrelated)
- Evidence: First review-fix e2e run failed only in `AddTaskFakeBackendTest.addTask_optionalDetailsAreSubmittedAsDescription` waiting for picker `OK`, before Task 7 blocker assertions; rerun was used to distinguish transient picker/emulator behavior from a Task 7 regression.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 22 tests with 0 skipped and 0 failed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: No whitespace errors after review fixes.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose Material 3" "AlertDialog dismissButton confirmButton onDismissRequest retry dialog Compose"`
- Result: PASS
- Evidence: Resolved `/websites/composables_jetpack-compose_androidx_compose_material3_material3`.

Verification:
- Command: `npx ctx7@latest docs "/websites/composables_jetpack-compose_androidx_compose_material3_material3" "AlertDialog dismissButton confirmButton onDismissRequest retry dialog Compose"`
- Result: PASS
- Evidence: Docs confirmed `dismissButton` is optional and `onDismissRequest` controls outside/back dismiss attempts.

Verification:
- Command: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: Instrumentation sources compiled after adding failure-dialog no-`Dismiss` assertions.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: FAIL (RED, expected second review fix)
- Evidence: Managed blocker tests failed in `deadlineBlocker_backendFailureShowsRetryDialogAndWritesErrorLog`, `deadlineBlocker_rescheduleBackendFailureShowsRetryDialogAndWritesErrorLog`, and `startBlocker_backendFailureShowsRetryDialogAndWritesErrorLog` because each failure dialog still exposed a `Dismiss` button.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Shared Android host tests, Android debug unit tests, and `assembleDebug` completed successfully after the non-dismissible blocker dialog fix.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 22 tests with 0 skipped and 0 failed after removing blocker failure-dialog dismiss affordances.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: No whitespace errors after the second review fix.

Verification:
- Command: `npx ctx7@latest library "Jetpack Compose BackHandler" "BackHandler onBackPressedDispatcher Compose Android production blocker"`
- Result: PASS
- Evidence: Resolved `/websites/developer_android_develop_ui_compose`.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android_develop_ui_compose" "BackHandler onBackPressedDispatcher Compose Android production blocker"`
- Result: PASS
- Evidence: Docs showed using `BackHandler(enabled = true)` to intercept system back presses in composables.

Verification:
- Command: `npx ctx7@latest library "kotlinx.datetime" "TimeZone DST Instant toLocalDateTime Kotlin Multiplatform"`
- Result: PASS
- Evidence: Resolved `/kotlin/kotlinx-datetime`.

Verification:
- Command: `npx ctx7@latest docs "/kotlin/kotlinx-datetime" "TimeZone DST Instant toLocalDateTime Kotlin Multiplatform"`
- Result: PASS
- Evidence: Docs covered `TimeZone.of(...)` and converting each `Instant` to `LocalDateTime` with a specified `TimeZone`, including DST-aware conversion.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected quality fix)
- Evidence: Compile failed because new shared test `blockWindowFormatsEachEndpointWithItsDstOffset` called missing `buildBlockerUiModel(..., timeZoneId = "Europe/Berlin")`.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Shared Android host tests and Android debug unit tests passed after adding timezone-id formatting and updating Android blocker callers.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: FAIL (RED, expected quality fix)
- Evidence: Managed blocker tests failed in `startBlocker_systemBackDoesNotCloseProductionBlocker` and `deadlineBlocker_systemBackDoesNotCloseProductionBlocker`; `pressBack()` killed the app with `NoActivityResumedException`.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Shared Android host tests, Android debug unit tests, and `assembleDebug` completed successfully after adding the production blocker `BackHandler`.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 24 tests with 0 skipped and 0 failed after the back-handler fix.

Verification:
- Command: `npx ctx7@latest library "AndroidX Espresso" "pressBack instrumentation dialog stays open Compose AlertDialog"`
- Result: PASS
- Evidence: Resolved `/androidx/androidx`.

Verification:
- Command: `npx ctx7@latest docs "/androidx/androidx" "Espresso pressBack instrumentation dialog stays open Compose AlertDialog"`
- Result: PASS
- Evidence: Docs covered simulated Back presses and Compose `AlertDialog` `onDismissRequest`.

Verification:
- Command: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: Instrumentation sources compiled after adding failure-dialog `pressBack()` assertions.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS (test-only coverage update)
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 24 tests with 0 skipped and 0 failed; no production RED was observed because existing blocker failure dialogs already ignored Back dismiss while visible.

Verification:
- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Shared Android host tests, Android debug unit tests, and `assembleDebug` completed successfully after the test-only coverage update.

## Manual Verification

- Confirmed Task 7 only was implemented; no Task 8 Morning Review redesign or overlay migration was started.
- Confirmed `BlockerUiModel` includes the blocker kind, eyebrow, title, block window, optional Todoist details, and consequence text.
- Review-fix confirmation: `dueDatetime` is treated as block deadline/end, `startAt` is treated as block start, and missing `startAt` falls back to `dueDatetime - durationMinutes`.
- Confirmed Start blocker uses `Start now`, task title/window/details, `I'm starting`, `Postpone 5 min`, and `Second postpone escalates via WhatsApp.`
- Confirmed Deadline blocker uses `Block ended`, task title/window/details, `Done`, `Extend 30m`, and `Reschedule`.
- Confirmed production activities still pass `showBack = false`.
- Second review-fix confirmation: production blocker action-failure dialogs are retry-only; they expose no `Dismiss` button and ignore dialog dismiss requests.
- Quality-review confirmation: production Start and Deadline blockers consume Android system back when `showBack = false`; instrumentation verifies the activity remains open after `pressBack()`.
- Quality-review confirmation: blocker windows crossing DST transitions format start and end separately in the requested timezone instead of applying the deadline offset to both endpoints.
- Quality re-review confirmation: production failure dialogs for Start, Deadline reschedule, and Deadline done remain visible and keep the blocker open after system Back while the retry dialog is showing.
- Confirmed deadline reschedule uses `CommitmentEditorMode.Reschedule` with title editing disabled and details prefilled from `description`.
- Review-fix confirmation: deadline reschedule submits the computed block end/deadline instant, not the selected start instant.
- Confirmed reschedule description patch semantics: unchanged omits `description`, blank clears it, non-blank sets the trimmed value.
- Confirmed blocker activities update the model from cached and refreshed snapshots and keep the existing hide-if-resolved gate.

## Known Failures Or Skipped Checks

- No required final checks were skipped.
- The e2e lane emitted the existing `libandroidx.graphics.path.so` strip warning; packaging and tests completed successfully.
- The old instrumentation-only validation test for a missing reschedule start was removed because Task 7 requires the shared editor picker/save-label behavior, not that legacy inline validation path. Required reschedule coverage now asserts Start picker, Duration picker, computed save label, and backend request.

## Assumptions

- A plain `timeZoneOffsetMinutes` value keeps shared blocker formatting platform-agnostic while Android activities compute the device offset with `java.time`.
- Fallback UI for missing snapshots uses `Committed task` and `Schedule unavailable` rather than crashing; activities replace it when local or refreshed snapshot data is available.
- `BlockerDisplayMode.Production.showBackAction == false` captures the production no-dismiss contract while debug/dev callers may still opt into `showBack`.
- Review-fix update: the production no-dismiss contract is now also covered at activity level by instrumentation assertions that Start and Deadline blockers expose no `Back` text.
- Second review-fix update: `ErrorAlertDialog` remains dismissible by default for existing non-blocker screens; blocker screens opt out by passing `dismissLabel = null`.
- Quality-review update: shared blocker formatting now defaults to UTC when no timezone id is supplied or an invalid id is provided; Android production callers pass the device timezone id.

## Next Recommended Step

Proceed to Task 8 only after this Task 7 commit is reviewed. Do not infer overlay work from this handoff; overlay remains a later task.
