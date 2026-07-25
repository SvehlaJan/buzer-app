# Unified Task Editor — Task 8 final verification handoff

Date: 2026-07-24/25
Plan: `docs/superpowers/plans/2026-07-22-unified-task-editor.md`, Task 8.
Worktree: `/Users/svehla/playground/buzer-app/.worktrees/unified-task-editor-task8`
Branch: `feature/unified-task-editor-task8`

## Task / plan status

- [x] Real-backend instrumentation coverage for the full Update path across Edit/Repair/Blocker (invalid entry validation, correction, `POST /update`, pending-confirmation disable, host-specific success behavior).
- [x] Direct-checklist edit coverage in a Blocker scenario, plus the nested-checklist read-only fixture assertion in the Edit host.
- [x] Full required deterministic gates (backend tests, typecheck, shared/Android unit tests, assembleDebug, real-backend E2E for the new test class).
- [x] Local connected-device inspection (non-destructive, no mutation submitted).
- [ ] Full `scripts/e2e-real-backend.sh` (all 46 instrumentation tests, no class filter) is still red — 12 pre-existing failures unrelated to this task's changes. See "Known Failures" below; not fixed in this pass.

## Files changed

- `backend/src/services/taskActionService.ts` — real bug fix (see below).
- `backend/test/fakes/e2eFixtures.ts`, `backend/test/fakes/e2eFixtures.test.ts`, `backend/test/e2eServer.test.ts` — new `task-invalid-blocker` fixture and coverage (carried over from the prior session, verified here).
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/E2EServerClient.kt` — new test-only client for the main backend's E2E adapter.
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/taskeditor/UnifiedTaskEditorRealBackendTest.kt` — new real-backend coverage class, 6 tests (see below), split from the prior session's 5.
- `.claude/settings.json` (both the main checkout's and this worktree's own copy) — allowlisted `scripts/e2e-real-backend.sh`, `npm test`/`npm run typecheck` (bare and `--prefix backend`), the `ANDROID_TEST_CLASS=... scripts/e2e-real-backend.sh` env-var form, and `git merge-base`. Unrelated to product code but necessary friction reduction for agent work in this worktree — the worktree's own `.claude/settings.json` is a tracked file frozen at worktree-creation time and does not pick up edits to the main checkout's copy.
- `docs/handoffs/2026-07-23-unified-task-editor-task8-final-verification.md` — this handoff (rewritten from the prior session's draft).

## Root cause #1 (fixed): occurrenceKey staleness racing a bundled checklist mutation

`TaskActionService.update()` (`backend/src/services/taskActionService.ts`) builds the checklist
sub-action's `expectedOccurrenceKey` from the *pre-root-edit* client-supplied value
(`checklistActionForRootEdit(metadata)`, computed before `executeRootAction()` runs), then reuses
that same stale value for `mutateChecklist()` *after* the root edit completes. For a non-recurring
task, the root edit unconditionally resends `due: {kind:"replace", ...}`; the editor's date/time
picker truncates to whole minutes while the stored `dueDatetime` can carry sub-minute precision
from fixture seeding, so the "unchanged" resubmission reads as an external schedule change to
`occurrenceReconciler.ts`, which rotates `occurrenceKey` as a side effect of the root edit. The
checklist step then validates against the now-superseded key and gets rejected with a 409
`stale_occurrence`, surfaced to the client as a hard update-failure dialog rather than success or
`reconciliation_pending`.

Fix: re-read the task's current `occurrenceKey` via `this.tasks.getById(taskId)` immediately after
`executeRootAction()` returns (confirmed via the coordinator source that this is synchronously
authoritative by then — same DB connection, mutex-serialized), and rebuild the checklist action
through the existing `checklistActionForRootEdit()` with that fresh value instead of the stale one.
Scoped to the `request.rootPatch !== null` path only; the no-root-patch path is untouched since it
has no rotation risk.

Verification:
- Command: `npm test --prefix backend`
- Result: PASS
- Evidence: 32 files / 385 tests passed (one more than the prior session's 385→ wait: 385 total, matching the new fixture/test already counted).

- Command: `npm run typecheck --prefix backend`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

## Root cause #2 (fixed): test-only NPE from androidx.test-core internals

`blockerSupportedDirectChecklistEdit_...` (as inherited from the prior session) originally used
`waitForActivityDestroyed` — a naive 20s/no-tolerance wait — instead of the file's own
`waitForUpdateResolution` (45s, tolerates the pending-confirmation window and a failure dialog),
which the sibling `invalidDeadlineRecovery_...` test already used correctly for the same
launch-blocker-then-submit-reschedule pattern. Swapping to `waitForUpdateResolution` surfaced the
*real* symptom (root cause #1) instead of a misleading timeout.

After fixing root cause #1, a second, unrelated problem appeared: a deterministic (4/4 reproductions)
`NullPointerException: Cannot run onActivity since Activity has been destroyed already`, thrown
entirely from `androidx.test-core` internals (zero frames from this repo's code in every
occurrence). Verified via the actual `ActivityScenario.java` source that this is not
`close()`/`moveToState()` (different message, has its own already-destroyed guard) — confirmed via
[android/android-test#235](https://github.com/android/android-test/issues/235) that this is a
known, unresolved library gap around calling into an activity that self-finished. Two targeted
fixes (reordering assertions; removing the file's only `composeRule.waitForIdle()` call) did not
change the outcome — the exception is not tied to this test's synchronous flow at all.

Root cause: this was the *only* test in the file that both launches a second real Activity via a
standalone `ActivityScenario` **and** later calls `composeRule.setContent{}` (via
`renderRealEditHost`) in the same test method — no sibling test mixes those two patterns. Splitting
the combined test into two separate `@Test` methods (`blockerSupportedDirectChecklistEdit_postsFullUpdate`
and `nestedChecklistHierarchy_staysReadOnlyInEditHost`) removed the structural combination and
resolved it.

Verification:
- Command: `ANDROID_TEST_CLASS='app.buzer.android.taskeditor.UnifiedTaskEditorRealBackendTest' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: `Starting 6 tests on pixel8Api34` ... `Finished 6 tests on pixel8Api34`, `BUILD SUCCESSFUL in 1m 9s`. All 6 tests in the class pass: `invalidEdit_correctsSchedule_postsUpdate_andReturnsToHomeAfterConfirmation`, `invalidRepair_correctsSchedule_postsUpdate_andReturnsToRemainingReview`, `invalidDeadlineRecovery_correctsDuration_postsUpdate_andClosesOnlyAfterConfirmation`, `pendingEdit_disablesDuplicateSubmission_thenUsesConfirmedAuthorityToExit`, `blockerSupportedDirectChecklistEdit_postsFullUpdate`, `nestedChecklistHierarchy_staysReadOnlyInEditHost`.

## Release gates (plan step 3)

- Command: `npm test --prefix backend` — PASS (385 tests).
- Command: `npm run typecheck --prefix backend` — PASS.
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` (from `client/`) — PASS, `BUILD SUCCESSFUL`.
- Command: `ANDROID_TEST_CLASS='app.buzer.android.taskeditor.UnifiedTaskEditorRealBackendTest' scripts/e2e-real-backend.sh` — PASS (6/6, see above).
- Command: `scripts/e2e-real-backend.sh` (full 46-test suite, no filter) — **FAILED, 12/46 failures.** See Known Failures. `BUILD FAILED in 56m 40s`.

## Manual Verification

- Built and installed a debug APK against the local real e2e backend on a connected physical device (Pixel 10, serial `56061FDCR005J0`) via `adb reverse tcp:18433 tcp:18433` and `./gradlew :androidApp:installDebug -PBUZER_BACKEND_BASE_URL=http://127.0.0.1:18433 -PBUZER_BACKEND_TOKEN=test-token`.
- Confirmed the Home screen renders against the local backend (fixture-derived "12 invalid tasks need review" banner, not production data).
- Opened Add Task: confirmed the unified editor renders correctly — Schedule/Project/Assignee/Repeat/Notes/Checklist rows all present and matching source.
- Tapped the Notes accordion row: confirmed it expands correctly (chevron flips, text field appears) with a real touch — this is a useful data point for the pre-existing-failures diagnosis below, since it shows the accordion click mechanism itself works fine in normal use.
- No mutation was submitted (no Save/Add tap), per the plan's explicit boundary.

## Known Failures / Skipped Checks

**12 pre-existing failures in the full `scripts/e2e-real-backend.sh` run, none caused by this task's changes:**

- `AddTaskFakeBackendTest`: `addTask_optionalDetailsAreSubmittedAsDescription`, `addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`, `addTask_dirtyCancelAndDeviceBackRequireDiscardConfirmation`, `addTask_selectorSheetsAndAccordionKeepChangesInDraftUntilAdd`.
- `EditTaskFakeBackendTest`: `editTask_retryPreservesTheCapturedRootRequestAndDirtyExitConfirmsDiscard`.
- `BlockerActivityFakeBackendTest`: `morningReview_repairShowsValidationErrorForMissingDeadline`, `morningReview_repairBackendFailureShowsErrorAndStaysOpen`, `deadlineBlocker_rescheduleUsesSharedUpdateUi`, `deadlineBlocker_rescheduleFailureUsesUpdateAction`, `morningReview_repairCallsFakeBackend`.
- `MorningReviewRepairHostTest`: `successfulRepairsUseTheHostCallbackToReturnToRemainingReviewThenFinishLastTask`, `cancellingRepairReturnsToReviewWithoutInvokingUpdate`.

None of `UnifiedTaskEditorRealBackendTest`'s 6 tests are in this list. Task 7's own handoff
(`docs/handoffs/2026-07-23-unified-task-editor-task7-retire-legacy.md`) explicitly records that
these files were only *compiled* (`compileDebugAndroidTestKotlin`), never executed on a device —
"Task 8 owns real-backend Android E2E and release verification." This is the first time the full
suite has run since the legacy `CommitmentEditor` retirement, and it is surfacing that gap now.

Diagnostic leads gathered (not confirmed to full resolution):

- `TaskEditorScaffold.kt`'s `reduceSectionTap`/`resolvedExpandedSection` lock the expanded accordion
  section to the active validation focus (`validation.focusSection`) when one is present — tapping
  any other disclosure row is a no-op until the invalid field is resolved. Live device testing
  confirmed a plain tap on the Notes row *does* expand it correctly when no validation focus is
  active, so this alone doesn't explain the Add-flow failures (no validation should be active that
  early in `addTask_optionalDetailsAreSubmittedAsDescription`), but it's worth checking whether an
  earlier step in each failing test's sequence unexpectedly triggers one.
  See `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt:82-102`.
- `EditorValueRow` sets `.semantics { contentDescription = "$label: $value" }` on a `.clickable(...)`
  container (`TaskEditorScaffold.kt:596-604`), which Compose's default clickable semantics-merging
  may fold into the row. This did not block a *live device* tap, but may interact differently with
  Compose test node-matching (`onNodeWithText("Notes")` exact match vs. merged node text) — worth
  checking with `substring = true` or a testTag-based match as a first fix attempt.
- The 5 "Semantic Node has no parent layout with a Scroll SemanticsAction" failures (all
  `performScrollTo()` on a target inside `BlockerActivityFakeBackendTest`/`MorningReviewRepairHostTest`)
  suggest the target node may no longer be inside the scaffold's single `Column.verticalScroll` at
  `TaskEditorScaffold.kt:303-309` — worth checking whether these specific hosts still render through
  `TaskEditorScaffold` at all, or through an older/different container.
- Not investigated: the `EditTaskFakeBackendTest` `ComposeTimeoutException` (15s, no specific error) and the
  `addChecklistDraft`/`Checklist item 2` scroll-target-not-found failure.

## Assumptions and operational notes

- Backend/client are an atomic personal deployment per the plan's guardrails; nothing was pushed,
  deployed, or released — this handoff's work is commit-only, pending review.
- No production Todoist account or data was touched. The connected-device inspection used the local
  e2e backend exclusively (verified via the fixture-derived banner content, not production task data).
- `.worktrees/task-editor-redesign` and `.worktrees/e2e-real-backend` were removed as unrelated
  session cleanup (both fully merged into master, clean working trees) — not part of Task 8's scope,
  noted here only because it happened in the same session.
- An orphaned `tools/fake-backend` process from the now-removed `task-editor-redesign` worktree was
  found squatting on the shared e2e port (18433) and killed; unrelated to this task's product code.

## Next Recommended Step

1. Review and commit this work (backend fix + new test coverage + settings sync). Nothing has been
   pushed.
2. Separately scope and fix the 12 pre-existing instrumentation failures above — likely its own
   short task/plan given it spans 4 files with a UI-structure investigation, not a quick patch. The
   diagnostic leads above are a reasonable starting point.
3. Once both are green, the plan's atomic deployment note applies: deploy the backend commit, then
   install/release the matching APK immediately; rollback reverts both together.
