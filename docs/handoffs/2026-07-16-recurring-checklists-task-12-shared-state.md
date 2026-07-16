# Recurring Checklists Task 12: Shared State And Alarm Plans

Plan/task attempted:

- Completed Task 12 from `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md` in shared Kotlin only. AndroidApp callers remain intentionally untouched for Tasks 13–15.

Changes made:

- Added occurrence identity to desired alarms and excluded unsupported, pending, invalid, and terminal roots from new alarm plans. Pending roots additionally request per-task cancellation.
- Made the snapshot reducer surface unsupported and reconciliation-pending roots in the existing Morning Review list, preserve checklist nesting, and expose immutable authoritative replacement that retains a caller-owned draft.
- Added safe diagnostics counts for unsupported and pending roots without task content.
- Added numbered recurrence choices, custom-recurrence replacement gating, checklist draft validation/failed-draft retention, and Home/blocker progress/action gating models.

Files changed:

- `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotReducer.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotDiagnostics.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentEditorModels.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/blockers/BlockerUiModels.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotReducerTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotDiagnosticsTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/blockers/BlockerUiModelsTest.kt`
- `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/commitment/ChecklistEditorModelsTest.kt`

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.state.SnapshotReducerTest' --tests 'app.buzer.shared.enforcement.AlarmPlanCalculatorTest' --console=plain`
  - Result: FAIL
  - Evidence: `DesiredAlarm` lacked `occurrenceKey`.
- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.state.SnapshotReducerTest' --console=plain`
  - Result: FAIL
  - Evidence: unsupported and pending planned roots were missing from Morning Review.
- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.ui.commitment.ChecklistEditorModelsTest' --console=plain`
  - Result: FAIL
  - Evidence: recurrence/checklist model types did not exist; later blank-ID validation test failed before the validator rejected it.

Context7 lookup:

- Command: `npx ctx7@latest library "Kotlin" "Kotlin data classes immutable state replacement semantics and sealed interfaces in shared Kotlin Multiplatform models"`
- Command: `npx ctx7@latest docs "/jetbrains/kotlin" "Kotlin data classes immutable state replacement semantics and sealed interfaces in shared Kotlin Multiplatform models"`
- Result: consulted official Kotlin material on immutable data-class copy semantics and sealed interfaces before adding shared state models.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.enforcement.AlarmPlanCalculatorTest' --console=plain`
  - Result: PASS
  - Evidence: occurrence rotation, rollover, pending cancellation, checklist-only stability, extension timing, and exclusions passed.
- Command: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.state.SnapshotReducerTest' --tests 'app.buzer.shared.state.SnapshotDiagnosticsTest' --console=plain`
  - Result: PASS
  - Evidence: Morning Review and immutable stale-authority replacement behavior passed.
- Command: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.ui.commitment.ChecklistEditorModelsTest' --tests 'app.buzer.shared.ui.blockers.BlockerUiModelsTest' --console=plain`
  - Result: PASS
  - Evidence: recurrence/checklist and progress/action gating passed.
- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

Manual verification:

- Inspected the boundary that only root tasks enter `SnapshotViewState`; checklist children remain nested under `TaskSnapshotItem.checklistItems`.

Known failures or skipped checks:

- No Android application/emulator lane was run. This task intentionally does not modify AndroidApp; Tasks 13–15 consume the new shared occurrence/action models.

Assumptions:

- `RecurrencePreset.never` is an explicit supported replacement for a custom Todoist recurrence.
- Existing `invalidTasks` is the shared Morning Review collection; pending and unsupported roots are added there without an Android call-site migration.

Next recommended step:

- Continue Task 13 to persist device identity and reconcile Android alarms by `(taskId, occurrenceKey, kind)`.
