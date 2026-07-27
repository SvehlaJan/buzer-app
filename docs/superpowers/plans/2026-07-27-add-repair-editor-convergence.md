# Add/Repair Task Editor Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Repair’s suggested-time chips use the same simple `HH:mm` format as Add, and eliminate the remaining avoidable Add/Repair presentation branches without changing the intentional Create-versus-Update behavior.

**Architecture:** `TaskEditorScaffold` remains the only task-form renderer. Add remains the sole Create host and Repair remains an Update host; this plan changes only shared presentation mechanics: quick-time formatting, project-choice status rendering, Repair clock injection, and the redundant Update-footer mode. No endpoint, backend, request DTO, schedule calculation, or enforcement behavior changes.

**Tech Stack:** Kotlin, Compose Material 3, Android JVM tests, local Compose instrumentation tests, and the repository’s real-backend Android E2E script.

---

## Handoff instructions for the implementer

This section is deliberately complete for an implementer who has not seen the earlier audit or chat history.

### Required reading and repository state checks

From the repository root, before editing:

```bash
cat AGENTS.md
cat docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md
cat docs/superpowers/specs/2026-07-22-unified-task-editor-design.md
cat docs/handoffs/2026-07-27-add-repair-editor-audit.md
git worktree list
git status --short
```

The accepted unified-editor design is the specific product authority for this work. If it disagrees with an older plan or source comment, preserve its two mutation intents and its host-boundary differences.

Use an isolated worktree for implementation, per `AGENTS.md` and `docs/notes/agent-workspaces.md`. A fresh worktree does not contain `client/local.properties`; before Gradle commands, export the Android SDK paths rather than adding or committing that file:

```bash
export ANDROID_HOME=/Users/svehla/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk
```

Run Gradle commands from `client/` unless the command explicitly names a repository-root script. Do not use, modify, or count `tools/fake-backend`; `scripts/e2e-real-backend.sh` starts `backend/test/e2eServer.ts` and is the only authorized Android E2E authority.

### Accepted scope decisions

Keep these differences. They are required behavior, not duplication to remove:

| Behavior | Add (Create host) | Repair (Update host) |
| --- | --- | --- |
| Initial draft | Blank task/default project | Complete `TaskSnapshotItem` draft |
| Validation exposure | Quiet until interaction or Add press | All invalid snapshot fields shown on entry |
| Mutation | `CreateTaskRequest` | Full `UpdateTaskRequest` with action/reconciliation retry |
| Checklist/footer | New items only; no activity log | Existing + new items; Activity log after Checklist |
| Success/cancel | Return to normal Add destination | Return to remaining Morning Review tasks; finish when empty |

Change these audited, unnecessary differences:

1. Every quick Start time chip must use bare 24-hour `HH:mm`. The Date row continues to disclose the date. A chip can still select a different date across midnight because its model retains `ScheduleTimeChoice.date`.
2. Add and Repair must present project loading/failure through one inline component. A failed refresh may remain non-blocking when cached projects are usable, but it must remain visibly retryable.
3. Repair must accept an injectable fixed clock, matching Add’s existing `nowProvider`, so quick suggestions are deterministic under test.
4. Update screens must always receive an Activity-footer slot. Remove the boolean that conditionally enables it; an optional footer in the generic scaffold is sufficient for Add.

### Current code baseline and root cause

- [ScheduleQuickChoices.kt](../../../client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt) is already the only schedule-chip implementation. `timeChoiceLabel` prefixes non-today times with `relativeDateLabel`; that is why a Repair draft with a retained non-today snapshot date looks different even though no Repair-specific formatter exists.
- [AddTaskScreen.kt](../../../client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt) already exposes `nowProvider` and captures it with `remember(nowProvider) { nowProvider() }`. [MorningReviewScreen.kt](../../../client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt) directly captures `LocalDateTime.now()` in `MorningReviewRepairEditor`.
- Add presents project loading failures through two `ErrorAlertDialog` branches. Repair overlays `InlineLoadingStatus` and `WarningStatus` in its editor host. Both load the same project facts and have the same Retry intent.
- `TaskEditorPresentation.showUpdateFooter` is always `true` at the three existing `TaskEditorUpdateScreen` call sites (Edit, Repair, deadline blocker). It is `false` only for Add, which does not use `TaskEditorUpdateScreen`.

## File map

| File | Change |
| --- | --- |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt` | Make quick Start time labels date-independent. |
| `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoicesTest.kt` | Lock bare labels and preserve cross-midnight date selection. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt` | Add `nowProvider`, pass it into Repair, and use common project status. |
| `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt` | Prove an invalid Repair editor uses the injected clock. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorProjectLoadStatus.kt` | Create the shared inline loading/warning/retry component. |
| `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/taskeditor/TaskEditorProjectLoadStatusTest.kt` | Test the component’s visible loading, error, and Retry callback behavior. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt` | Replace project-load dialogs/overlay with the common inline status. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidation.kt` | Remove footer-mode data from `TaskEditorPresentation`. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt` | Change the generic footer to a nullable slot and render it after Checklist. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreen.kt` | Require and always forward the Update footer; delete its footer predicate. |
| `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`, `MorningReviewScreen.kt`, `BlockerScreens.kt` | Remove redundant `showUpdateFooter = true`; preserve each existing Activity-log footer. |
| Existing task-editor JVM/layout tests | Delete obsolete boolean assertions and retain Activity-footer placement coverage. |
| `docs/handoffs/2026-07-27-add-repair-editor-convergence.md` | Required completion evidence and known-suite-failure record. |

## Task 1: Use one simple quick-time format and give Repair a testable clock

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoicesTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`

- [ ] **Step 1: Write the failing pure formatter regression.**

  In `ScheduleQuickChoicesTest`, add this test. Also update the existing late-night assertion from `"Tomorrow 00:00"` to `"00:00"`; retain its assertion that `selectQuickStartTime` changes the schedule date to tomorrow.

  ```kotlin
  @Test
  fun quickTimesUseBareTwentyFourHourLabelsForNonTodayDates() {
      val choices = startTimeChoices(
          selectedDate = LocalDate.of(2026, 7, 30),
          selectedTime = null,
          now = LocalDateTime.of(2026, 7, 24, 10, 1)
      )

      assertEquals(listOf("10:05", "10:35", "11:05"), choices.map { it.label })
  }
  ```

- [ ] **Step 2: Verify the formatter test is RED.**

  From `client/`:

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*ScheduleQuickChoicesTest'
  ```

  Expected: FAIL because the first non-today label is currently date-prefixed, for example `"Thu, 30 Jul 10:05"`.

- [ ] **Step 3: Implement the minimal shared formatter change.**

  In `ScheduleQuickChoices.kt`, replace the `timeChoiceLabel` function with:

  ```kotlin
  private fun timeChoiceLabel(dateTime: LocalDateTime): String =
      formatTime(dateTime.toLocalTime())
  ```

  Update the only call in `startTimeChoices` to `label = timeChoiceLabel(dateTime)`. Do not change `ScheduleTimeChoice.date`, `selectQuickStartTime`, `dateChoices`, `scheduleSummary`, or `relativeDateLabel`: those preserve date selection and the collapsed Schedule summary.

- [ ] **Step 4: Verify the pure suite is GREEN.**

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*ScheduleQuickChoicesTest'
  ```

  Expected: PASS. This proves Add, Repair, Edit, and blocker recovery receive the same bare formatter without a named-screen branch.

- [ ] **Step 5: Write the failing Repair host-clock test before changing production code.**

  Add `java.time.LocalDateTime` to `MorningReviewRepairHostTest`. Create a fixture by copying `task(id = "clock", title = "Clock repair")` and changing `dueDatetime = null`, `dueDate = null`, `durationMinutes = null`, and `validationErrors = listOf("missing_due_datetime", "missing_duration")`.

  Render `MorningReviewScreen` with that fixture, its existing `project` loaders, and this new desired argument:

  ```kotlin
  nowProvider = { LocalDateTime.of(2026, 7, 21, 10, 1) }
  ```

  After opening `morning-repair-clock`, assert the entry-expanded Schedule contains `"10:05"`:

  ```kotlin
  composeRule.onNodeWithTag("morning-repair-clock").performClick()
  composeRule.onNodeWithText("10:05").performScrollTo().assertIsDisplayed()
  ```

- [ ] **Step 6: Verify the new host test is RED for the right reason.**

  ```bash
  ./gradlew :androidApp:compileDebugAndroidTestKotlin
  ```

  Expected: compile failure stating that `MorningReviewScreen` has no `nowProvider` parameter. Correct test imports/fixture errors before proceeding; do not add production code until this is the only intended failure.

- [ ] **Step 7: Inject and capture Repair’s clock once per edited task.**

  Add this defaulted dependency at the end of `MorningReviewScreen`’s parameter list, after `refreshProjects`:

  ```kotlin
  nowProvider: () -> LocalDateTime = { LocalDateTime.now() }
  ```

  Pass it to `MorningReviewRepairEditor`. Add a corresponding `nowProvider: () -> LocalDateTime` parameter there and replace:

  ```kotlin
  val now = remember(task.id) { LocalDateTime.now() }
  ```

  with:

  ```kotlin
  val now = remember(task.id, nowProvider) { nowProvider() }
  ```

  This exactly matches Add’s fixed-for-editor-session clock behavior. It must not refresh while the user changes a field.

- [ ] **Step 8: Verify the focused Repair host test is GREEN.**

  ```bash
  ./gradlew :androidApp:connectedDebugAndroidTest --tests '*MorningReviewRepairHostTest'
  ```

  Expected: PASS. If no connected device is available, compile with `:androidApp:compileDebugAndroidTestKotlin`, document the skipped runtime check, and complete the required real-backend lane in Task 3 on the managed device.

- [ ] **Step 9: Commit the isolated formatter/clock change.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoicesTest.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt
  git commit -m "refactor: align repair time suggestions"
  ```

## Task 2: Make project status and Update footers structural shared UI

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorProjectLoadStatus.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/taskeditor/TaskEditorProjectLoadStatusTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidation.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidationTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateStateTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldLayoutTest.kt`

- [ ] **Step 1: Write the failing common-status instrumentation test.**

  Create `TaskEditorProjectLoadStatusTest` using `createAndroidComposeRule<ComponentActivity>()`. Add this test, including a mutable retry counter so it proves the common component forwards the retry action:

  ```kotlin
  @Test
  fun failureUsesInlineWarningAndForwardsRetry() {
      var retryCount = 0
      composeRule.setContent {
          BuzerTheme {
              TaskEditorProjectLoadStatus(
                  loading = false,
                  message = "Network unavailable",
                  onRetry = { retryCount++ }
              )
          }
      }

      composeRule.onNodeWithText("Projects unavailable: Network unavailable").assertIsDisplayed()
      composeRule.onNodeWithText("Retry").performClick()
      composeRule.runOnIdle { assertEquals(1, retryCount) }
  }
  ```

- [ ] **Step 2: Verify the common-status test is RED.**

  ```bash
  ./gradlew :androidApp:compileDebugAndroidTestKotlin
  ```

  Expected: compile failure because `TaskEditorProjectLoadStatus` does not exist.

- [ ] **Step 3: Implement the one inline project-status component.**

  Create `TaskEditorProjectLoadStatus.kt` in package `app.buzer.android.ui.taskeditor`. Use only existing status primitives; do not duplicate their loading spinner or retry strip.

  ```kotlin
  package app.buzer.android.ui.taskeditor

  import androidx.compose.foundation.layout.Column
  import androidx.compose.runtime.Composable
  import androidx.compose.ui.Modifier
  import app.buzer.android.ui.status.InlineLoadingStatus
  import app.buzer.android.ui.status.WarningStatus

  @Composable
  internal fun TaskEditorProjectLoadStatus(
      loading: Boolean,
      message: String?,
      onRetry: () -> Unit,
      modifier: Modifier = Modifier
  ) {
      Column(modifier = modifier) {
          if (loading) InlineLoadingStatus("Loading projects...")
          message?.let { WarningStatus("Projects unavailable: $it", onRetry = onRetry) }
      }
  }
  ```

- [ ] **Step 4: Verify the component test is GREEN.**

  ```bash
  ./gradlew :androidApp:connectedDebugAndroidTest --tests '*TaskEditorProjectLoadStatusTest'
  ```

  Expected: PASS. If a device is unavailable, run `:androidApp:compileDebugAndroidTestKotlin` and record the deferred runtime assertion in the handoff.

- [ ] **Step 5: Replace the host-specific project feedback.**

  In `AddTaskScreen`:

  1. Delete `projectLoadDialogMessage`, `projectFeedbackDismissed`, and `hasCachedProjects` state/derived values.
  2. Replace `projectFeedbackMessage` with the unfiltered existing feedback policy:

     ```kotlin
     val projectLoadStatusMessage = AddTaskProjectLoadFeedback.message(
         projectsLoading = projectsLoading,
         projects = projects,
         projectLoadError = projectLoadError
     )
     ```

  3. Remove the assignments to the deleted dialog/dismissed states from both `retryProjects` and its initial `LaunchedEffect`.
  4. Delete the two project-specific `ErrorAlertDialog` blocks. Keep the submit-failure `ErrorAlertDialog` unchanged.
  5. Replace the Add-only `InlineLoadingStatus` inside the editor `Box` with:

     ```kotlin
     TaskEditorProjectLoadStatus(
         loading = projectsLoading,
         message = projectLoadStatusMessage,
         onRetry = ::retryProjects
     )
     ```

     This intentionally exposes a failed refresh even when cached projects remain selectable; `addTaskEditorState` and `AddTaskProjectLoadFeedback.canUseCachedProjects` continue to decide whether submission is allowed.

  In `MorningReviewRepairEditor`, replace the local loading and `WarningStatus("Projects did not load: …")` block with:

  ```kotlin
  TaskEditorProjectLoadStatus(
      loading = choices.loading,
      message = choices.errorMessage,
      onRetry = onRetryChoices
  )
  ```

  Remove now-unused imports only after the file compiles.

- [ ] **Step 6: Remove the redundant Update-footer mode.**

  Make the following exact type/slot changes:

  1. In `TaskEditorValidation.kt`, change `TaskEditorPresentation` to:

     ```kotlin
     internal data class TaskEditorPresentation(
         val screenTitle: String,
         val primaryVerb: String,
         val contextMessage: String? = null
     )
     ```

  2. In `TaskEditorScaffold`, change its footer parameter to nullable:

     ```kotlin
     footerContent: (@Composable () -> Unit)? = null
     ```

     Delete `showFooter` from `TaskEditorScaffoldContent` and replace `if (showFooter) footerContent()` with `footerContent?.invoke()` immediately after the Checklist section.

  3. Delete `shouldRenderTaskEditorUpdateFooter`. In `TaskEditorUpdateScreen`, make `footerContent: @Composable () -> Unit` required (no default) and forward it directly to `TaskEditorScaffold`.
  4. Delete `showUpdateFooter = true` from Edit, Repair, and deadline-blocker presentations. They already pass `{ ActivityLogSection(task) }`; preserve those exact footer slots. Add has no footer argument and remains unchanged.

- [ ] **Step 7: Update the obsolete-mode tests, then run the focused GREEN suites.**

  - Remove `showUpdateFooter` construction/assertions from `TaskEditorValidationTest`, `TaskEditorUpdateScreenStateTest`, and `DeadlineBlockerUpdateStateTest`.
  - In `TaskEditorScaffoldLayoutTest`, remove only `showUpdateFooter = true`; retain `footerContent = { Text("Activity log") }` and its placement assertion after Checklist.
  - Run from `client/`:

    ```bash
    ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskProjectLoadFeedbackTest' --tests '*TaskEditorValidationTest' --tests '*TaskEditorUpdateScreenStateTest' --tests '*DeadlineBlockerUpdateStateTest'
    ./gradlew :androidApp:connectedDebugAndroidTest --tests '*TaskEditorProjectLoadStatusTest' --tests '*TaskEditorScaffoldLayoutTest'
    ```

  Expected: PASS. The compiler must prove every Update call site supplies a footer; no runtime `showUpdateFooter` branch remains.

- [ ] **Step 8: Commit the host-convergence change.**

  ```bash
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt client/androidApp/src/test/kotlin/app/buzer/android/ui client/androidApp/src/androidTest/kotlin/app/buzer/android/ui
  git commit -m "refactor: converge task editor host feedback"
  ```

## Task 3: Required verification and delivery record

**Files:**
- Create: `docs/handoffs/2026-07-27-add-repair-editor-convergence.md`

- [ ] **Step 1: Run all affected JVM/build suites.**

  From `client/`:

  ```bash
  ./gradlew :androidApp:testDebugUnitTest --tests '*ScheduleQuickChoicesTest' --tests '*AddTaskScreenStateTest' --tests '*AddTaskProjectLoadFeedbackTest' --tests '*MorningReviewScreenStateTest' --tests '*TaskEditorValidationTest' --tests '*TaskEditorScaffoldStateTest' --tests '*TaskEditorUpdateScreenStateTest' --tests '*DeadlineBlockerUpdateStateTest'
  ./gradlew :shared:testAndroidHostTest
  ./gradlew :androidApp:assembleDebug
  ```

  Expected: all commands PASS.

- [ ] **Step 2: Run the focused authoritative Android integration class.**

  From the repository root:

  ```bash
  ANDROID_TEST_CLASS='app.buzer.android.taskeditor.UnifiedTaskEditorRealBackendTest' scripts/e2e-real-backend.sh
  ```

  Expected: PASS. In particular, `invalidRepair_correctsSchedule_postsUpdate_andReturnsToRemainingReview` must still post the complete update, remove only the repaired item, and keep the Morning Review activity open for the remaining item.

- [ ] **Step 3: Run the mandatory unfiltered real-backend E2E suite.**

  ```bash
  scripts/e2e-real-backend.sh
  ```

  Record the exact result. The 2026-07-23 release handoff reported 12 pre-existing unrelated failures in the full suite; if they recur, list the precise failed test names and distinguish them from this plan’s focused class/component tests. Do not call the work complete merely because the focused class passes.

- [ ] **Step 4: Do a targeted device/emulator visual check.**

  Against the non-destructive E2E backend, open Morning Review and Repair an invalid task. Verify:

  - Schedule opens because the invalid snapshot lacks schedule fields.
  - Start time chips show only `HH:mm`: no `Today`, `Tomorrow`, weekday, or calendar-date prefix.
  - The separate Date row remains visible and selecting a cross-midnight quick time still advances that row’s date.
  - A project fetch failure displays one inline `Projects unavailable: …` strip with Retry; Add no longer opens a project-load dialog.

  Capture app-scoped evidence with the repository helper, selecting a device if necessary:

  ```bash
  scripts/adb-verify.sh health
  ```

- [ ] **Step 5: Write the required handoff and commit delivery documentation.**

  The handoff must include plan checkbox status, changed files, every RED/GREEN command and result, focused and full E2E results, device evidence, skipped checks, baseline failures, and the explicit decision to preserve Create/Update behavior.

  ```bash
  git add docs/handoffs/2026-07-27-add-repair-editor-convergence.md docs/superpowers/plans/2026-07-27-add-repair-editor-convergence.md
  git commit -m "docs: record editor convergence verification"
  ```

## Plan self-review

- **Scope coverage:** Task 1 implements the requested simple Repair format by changing the shared function rather than adding a Repair exception; it also makes Repair’s quick-time source deterministic. Task 2 removes the two audited avoidable host branches: project status UI and `showUpdateFooter`. Task 3 covers every verification lane required by `AGENTS.md` for a user-visible Android change.
- **Preserved behavior:** The table above is normative. Do not unify Create and Update validation, request construction, checklist content, navigation, or success reduction.
- **TDD ordering:** Every production behavior change has a preceding failing test and explicit RED command. The Repair-clock test now precedes the API change, rather than compiling after it.
- **No hidden dependencies:** This plan specifies repository root versus `client/` working directories, Android SDK setup for worktrees, current source root causes, exact file paths, test classes, E2E authority, and the existing full-suite baseline caveat.
