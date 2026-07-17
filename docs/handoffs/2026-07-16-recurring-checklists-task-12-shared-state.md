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

## Code-quality follow-up

Plan/task attempted:

- Completed the Task 12 code-quality review follow-up in the pure shared editor models only. AndroidApp callers remain intentionally untouched for Tasks 13–15.

Changes made:

- Replacing a read-only custom recurrence now clears its obsolete custom rule before enabling the selected supported preset.
- Added `ChecklistEditorMutationState` variants for add, edit, delete, check, and uncheck. Every variant requires a nonblank client mutation id; child mutations also require child id and expected child revision.
- Added `retainFailedChecklistMutations`, which preserves each failed or stale-child typed mutation and attaches its returned failure code for user-visible retry handling.

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.ui.commitment.ChecklistEditorModelsTest' --console=plain`
  - Result: FAIL
  - Evidence: compilation failed before implementation because `ChecklistEditorMutationState` and `retainFailedChecklistMutations` were unresolved.
- GREEN: same focused command
  - Result: PASS
  - Evidence: recurrence replacement, all five mutation intents, concurrency-field validation, and failed-intent retention passed.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

Files changed:

- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentEditorModels.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/commitment/ChecklistEditorModelsTest.kt`
- Modify: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- Modify: `docs/handoffs/2026-07-16-recurring-checklists-task-12-shared-state.md`

Assumptions:

- `MutationOutcome.failed` and `MutationOutcome.stale_child` are the retryable outcomes that must retain the local typed intent; successful outcomes are removed.

Next recommended step:

- Continue Task 13 to persist device identity and reconcile Android alarms by `(taskId, occurrenceKey, kind)`.

Manual verification:

- Inspected the boundary that only root tasks enter `SnapshotViewState`; checklist children remain nested under `TaskSnapshotItem.checklistItems`.

Known failures or skipped checks:

- No Android application/emulator lane was run. This task intentionally does not modify AndroidApp; Tasks 13–15 consume the new shared occurrence/action models.

Assumptions:

- `RecurrencePreset.never` is an explicit supported replacement for a custom Todoist recurrence.
- Existing `invalidTasks` is the shared Morning Review collection; pending and unsupported roots are added there without an Android call-site migration.

Next recommended step:

- Continue Task 13 to persist device identity and reconcile Android alarms by `(taskId, occurrenceKey, kind)`.

## Spec-review follow-up

Plan/task attempted:

- Corrected the Task 12 alarm identity and invalid-state gaps found during spec review. No AndroidApp files were changed.

Changes made:

- Added `DesiredAlarmIdentity(taskId, occurrenceKey, kind)` and a stable `DesiredAlarm.identity`; trigger time is explicitly outside identity.
- Made `DesiredAlarm.occurrenceKey` required and nonblank. The calculator skips snapshots without an occurrence key rather than throwing.
- Excluded `CommitmentState.invalid` roots even when `validationErrors` is empty.

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.enforcement.AlarmPlanCalculatorTest' --console=plain`
  - Result: FAIL
  - Evidence: `DesiredAlarmIdentity` and `DesiredAlarm.identity` were unresolved before the stable identity type existed.
- RED: same focused command after adding the missing-key regression
  - Result: FAIL
  - Evidence: a blank occurrence key caused `IllegalArgumentException` while calculating the plan.
- GREEN: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.enforcement.AlarmPlanCalculatorTest' --console=plain`
  - Result: PASS
  - Evidence: strict nonblank construction, key rotation, unchanged identity across Extend, invalid-state filtering, and missing-key skipping passed.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

## Final P1 code-quality follow-up

Plan/task attempted:

- Closed the final Task 12 P1 review finding in the pure shared checklist editor model. AndroidApp remains untouched.

Changes made:

- Added `validateChecklistMutationBatch`, which rejects duplicate `clientMutationId` values across all typed mutation variants.
- Invoked the collection-level validator before `retainFailedChecklistMutations` processes any batch, preventing ambiguous outcome-to-intent mapping.

TDD evidence:

- RED: `./gradlew :shared:testAndroidHostTest --tests 'app.buzer.shared.ui.commitment.ChecklistEditorModelsTest' --console=plain`
  - Result: FAIL
  - Evidence: `validateChecklistMutationBatch` was unresolved before the batch validator existed.
- GREEN: same focused command
  - Result: PASS
  - Evidence: duplicate add/edit IDs are rejected both directly and through failed-mutation retention.

Verification:

- Command: `./gradlew :shared:testAndroidHostTest --console=plain`
  - Result: PASS
  - Evidence: complete shared Android-host suite passed.
- Command: `git diff --check`
  - Result: PASS
  - Evidence: no whitespace errors.

Files changed:

- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentEditorModels.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/commitment/ChecklistEditorModelsTest.kt`
- Modify: `docs/superpowers/plans/2026-07-15-recurring-tasks-checklists-and-action-correctness.md`
- Modify: `docs/handoffs/2026-07-16-recurring-checklists-task-12-shared-state.md`

Next recommended step:

- Continue Task 13 to persist device identity and reconcile Android alarms by `(taskId, occurrenceKey, kind)`.
