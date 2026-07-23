# Unified Task Editor Implementation Plan

> **For implementation:** Required sub-skill: use `superpowers:executing-plans` to execute this plan task-by-task.

**Goal:** Retire the legacy `CommitmentEditor` and `/tasks/:id/reschedule` protocol. Deliver one editable task-editor experience for Add, Edit, Morning Review repair, and deadline-blocker recovery, with immediate validation in every update flow.

**Architecture:** Keep `TaskEditorScaffold` presentation-neutral: it renders the shared form from a draft, validation state, and callbacks, while each thin host owns loading, navigation, mutation intent, and outcome. Add is the only `Create` host and reveals validation on interaction. Edit, Repair, and Blocker are `Update` hosts and validate as soon as their snapshot is loaded. The backend has a single full update mutation; blocker recovery uses it rather than a schedule-only endpoint.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Android activities, Kotlin/JVM and Android instrumentation tests, Node.js/TypeScript, Fastify, SQLite, Vitest.

**Design authority:**

- [Unified task editor design](../specs/2026-07-22-unified-task-editor-design.md)
- [Reschedule endpoint retirement decision](../../decisions/2026-07-22-retire-reschedule-endpoint.md)
- [Deferred conflict-resolution decision](../../decisions/2026-07-22-defer-update-conflict-resolution.md)
- [Original task-editor redesign plan](2026-07-21-task-editor-redesign.md) — superseded where it keeps `CommitmentEditor`, `WheelDateTimeField`, or the dedicated reschedule path.

**Guardrails:**

- Do not alter, start, or use `tools/fake-backend`; `backend/test/e2eServer.ts` and `scripts/e2e-real-backend.sh` are the verification authority.
- This is an atomic personal deployment: backend and Android client must move together. An older APK may be temporarily incompatible with the newly deployed backend.
- Do not add update conflict resolution, auto-rebase, time-zone support, FCM, or nested-checklist editing. Record any newly discovered need for those as a decision/note rather than expanding scope.
- Preserve direct checklist-item editing. For unsupported nested checklists, keep root fields editable and show the existing hierarchy warning with a read-only checklist section.

## Plan status

- [x] Task 1 — centralize validation and presentation data
- [x] Task 2 — make the scaffold universal
- [x] Task 3 — extract the Update host and migrate Edit
- [x] Task 4 — migrate Add without changing Create semantics
- [x] Task 5 — migrate Morning Review repair
- [x] Task 6 — migrate deadline-blocker recovery
- [x] Task 7 — retire legacy editor and reschedule protocol
- [ ] Task 8 — add real e2e coverage and perform release verification

---

## Existing behavior and test migration map

| Existing behavior | Current coverage | Planned replacement / migration |
|---|---|---|
| Task-editor draft and accordion state | `TaskEditorDraftTest`, `TaskEditorScaffoldStateTest`, `TaskEditorScaffoldLayoutTest` | Extend these for the presentation-neutral scaffold, verb-plus-range actions, and validation visibility. |
| Add task validation and create submission | `AddTaskFormValidatorTest`, `AddTaskCreateFlowTest`, `AddTaskSubmitStateTest`, `AddTaskFailurePresentationTest`, and `AddTaskProjectLoadFeedbackTest` | Move to the common validator with `OnInteraction`; preserve quiet initial Add state and create request semantics. |
| Edit task update submission and exit | `EditTaskScreenStateTest`, `EditTaskScreenRequestTest`, `DraftExitPolicyTest` | Extract common update host; assert entry validation, pending state, stale error, and dirty discard behavior. |
| Morning Review repair | `MorningReviewScreen` state tests and its current `CommitmentEditor` helpers | Replace local repair form state with the update host and assert Repair success returns the remaining invalid tasks. |
| Deadline-blocker reschedule | `BlockerScreens` unit tests and fake-only Android test assertions | Replace with a full-snapshot update host. Remove obsolete fake-only `/reschedule` UI assertions; add real e2e coverage instead. |
| Shared/API and backend reschedule contract | `BuzerApiClientJsonTest`, `BuzerApiClientContractTest`, `routes.test.ts`, `taskActionService.test.ts`, `e2eServer.test.ts` | Delete the contract and move its meaningful scheduling/reconciliation cases to `POST /tasks/:id/update`. |

## Task 1: Centralize editor validation and presentation data

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidation.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorValidationTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorDraft.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`

1. Write failing pure-state tests for all exposure rules. Cover a blank update draft exposing title/project/assignee/schedule errors on entry; a blank Add draft exposing nothing before interaction; touching a field exposing only its relevant error; a submit attempt exposing every error; and clearing the shared banner as soon as the draft becomes valid.

   ```kotlin
   @Test
   fun updateValidationIsVisibleImmediatelyButCreateStaysQuietUntilInteraction() {
       val validation = validateTaskEditor(blankDraft, unavailableProject, unavailableAssignee)

       assertThat(validation.visible(ValidationExposure.OnEntry, emptySet(), false).bannerMessage)
           .isNotNull()
       assertThat(validation.visible(ValidationExposure.OnInteraction, emptySet(), false).bannerMessage)
           .isNull()
   }
   ```

2. Add failing tests for schedule-specific rules: end must be after start, unavailable required selections disable submission, and changing a custom recurrence schedule requires selecting a supported recurrence replacement before submission.

   ```kotlin
   @Test
   fun customRecurrenceCanChangeScheduleOnlyAfterAReplacementRepeatIsSelected() {
       val validation = validateTaskEditor(customRecurrenceDraftWithChangedStart, readyChoices)

       assertThat(validation.repeatError).contains("Choose a supported repeat")
       assertThat(validation.canSubmit).isFalse()
   }
   ```

3. Implement `TaskEditorValidation`, `TaskEditorField`, and `ValidationExposure` as immutable, UI-neutral data. Have one `validateTaskEditor(...)` produce all errors and a visibility projection decide which errors/banner to display. Move any duplicated checks from `AddTaskFormValidator` into this helper; keep the old validator only as a delegating compatibility shim until Task 4 removes it.

4. Add a presentation value that provides `screenTitle`, `primaryVerb`, and optional update-only context/footer. Replace the hard-coded save-label helper with a verb-aware formatter so valid schedules produce strings such as `Repair 10:00–10:30` and invalid schedules produce the bare verb.

   ```kotlin
   fun primaryActionLabel(verb: String, draft: TaskEditorDraft): String =
       scheduleRangeLabel(draft.schedule)?.let { "$verb $it" } ?: verb
   ```

5. Run the focused host tests, then commit the isolated pure-state change.

   ```bash
   ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorValidationTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorDraftTest'
   git add client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor
   git commit -m "feat: centralize task editor validation"
   ```

## Task 2: Make `TaskEditorScaffold` a universal editor shell

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldStateTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldLayoutTest.kt`

1. Add failing tests that render the shell with an Update presentation and assert: Schedule remains first; its collapsed summary is shown; a shared entry-validation banner is visible; invalid fields have field-level errors; and both bottom buttons retain equal width with a long primary label.

   ```kotlin
   composeTestRule.onNodeWithText("Repair 10:00–10:30").assertExists()
   composeTestRule.onNodeWithText("Schedule").assertExists()
   composeTestRule.onNodeWithText("Fix the highlighted fields").assertExists()
   ```

2. Add a failing interaction test that selects a quick date/time/duration choice and opens only the compact Custom icon/button to the existing wheel-picker dialog. It must verify that the draft callback, rather than a scaffold-local draft, receives the change.

3. Replace the Add/Edit-specific scaffold parameters with `TaskEditorPresentation`, visible `TaskEditorValidation`, draft callbacks, and generic `onCancel`/`onPrimaryAction`. Keep accordion and dialog/sheet visibility local to the scaffold. Render title, Schedule, Project, Assignee, Repeat, Notes, and Checklist in that exact order; use the optional host footer after Checklist.

4. Wire visible errors and disabled state from the common validation. Do not put load, request, navigation, repair, or blocker branches in the scaffold. Retain the existing dirty-confirmation ownership in the host, and retain the Custom wheel-picker dialog rather than reintroducing `WheelDateTimeField`.

5. Run the state and layout suite, then commit.

   ```bash
   ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorScaffoldStateTest'
   ./gradlew :androidApp:connectedDebugAndroidTest --tests '*TaskEditorScaffoldLayoutTest'
   git add client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/taskeditor
   git commit -m "refactor: generalize task editor scaffold"
   ```

## Task 3: Extract the shared Update host and migrate Edit

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreen.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreenStateTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenRequestTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`

1. First write update-host state tests for the contract shared by all three Update callers: validation occurs when a snapshot is supplied, loading/unavailable choices prevent submit but do not hide the form, retry recovers choices, activity log is host-provided, and a successful update invokes a supplied success callback exactly once.

   ```kotlin
   @Test
   fun updateHostShowsEntryValidationBeforeTheUserTouchesAnInvalidSnapshot() {
       val state = taskEditorUpdateState(invalidSnapshot, loadedProjects, loadedCollaborators)

       assertThat(state.validation.bannerMessage).isNotNull()
       assertThat(state.validation.scheduleError).isNotNull()
   }
   ```

2. Add transition tests for `CoordinatorActionResult`: `Pending` leaves the screen open with `Saved; waiting for confirmation` and disables duplicate submission; a confirmed authoritative snapshot invokes success; stale/failed results retain the local draft and display the generic error. Do not implement conflict merge or auto-rebase.

3. Implement `TaskEditorUpdateScreen` as a single composable/state adapter. It accepts a complete `TaskSnapshotItem`, project/collaborator choices and retry callbacks, an action verb, activity-log/footer slot, a suspend full-update callback, and success/cancel callbacks. It owns the update draft, `OnEntry` validation, submit/pending state, and discard confirmation, then delegates all form rendering to `TaskEditorScaffold`.

4. Reduce `EditTaskScreen` to snapshot loading plus this host. Preserve its current full `UpdateTaskRequest` construction, direct checklist mutations, normal back destination, and activity log footer. Replace the edit-only stale wording with the agreed generic stale message, and add the in-entry invalid banner behavior.

5. Run the migrated screen tests and commit.

   ```bash
   ./gradlew :androidApp:testDebugUnitTest --tests '*TaskEditorUpdateScreenStateTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*EditTaskScreenStateTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*EditTaskScreenRequestTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*DraftExitPolicyTest'
   git add client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt client/androidApp/src/test/kotlin/app/buzer/android/ui
   git commit -m "refactor: share update task editor host"
   ```

## Task 4: Move Add onto the common shell without changing Create semantics

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlowTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFailurePresentationTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/AddTaskAssigneePresentationTest.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt` (only after all callers use Task 1 validation)

1. Add failing Add-screen tests showing no error banner on first render, a relevant field error after a user edit, all errors after pressing Add, and an action label of `Add 10:00–10:30` when the schedule is valid.

2. Add a request test proving that the migrated screen still sends the existing create request, preserves selected default project/retry behavior, and leaves the draft intact on a recoverable failure.

3. Switch Add to `TaskEditorScaffold` with `ValidationExposure.OnInteraction`. Track touched fields and submit attempt in the Add host only; the scaffold receives the visibility projection. Continue treating a custom recurrence choice as an immediate local validation requirement rather than silently rewriting recurrence.

4. Delete the obsolete Add-specific validator only after imports and tests are moved. Do not change Create endpoint payloads, navigation destination, or draft-discard behavior.

5. Run the focused suite and commit.

   ```bash
   ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskCreateFlowTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskSubmitStateTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskFailurePresentationTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*AddTaskProjectLoadFeedbackTest'
   git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/AddTaskAssigneePresentationTest.kt
   git rm client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt
   git commit -m "refactor: use common editor for task creation"
   ```

## Task 5: Replace Morning Review repair with the Update host

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/MorningReviewActivity.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewActivityTest.kt` (create if activity behavior lacks coverage)

1. Write failing tests for opening Repair on an invalid task: it shows the shared, immediately validated editor; all root fields are editable; an activity log appears after Checklist; and the Repair primary action contains the valid time range.

2. Add a result-flow test for Repair success: call the existing full update operation, remove only the repaired task from the invalid list, return to the remaining review list, and finish only when the list becomes empty. Add a cancellation test that returns to the review without mutating the task.

3. Delete `MorningReviewRepairEditorState`, `MorningReviewRepairValidation`, date/time draft selection helpers, and all local `CommitmentEditor` wiring. Use `TaskEditorUpdateScreen` with `primaryVerb = "Repair"`, project/collaborator retry state, and an `ActivityLogSection(task)` footer.

4. Keep Morning Review responsible only for selecting the invalid task, fetching/refreshing its snapshot and choices, sending `coordinator.updateTask`, and applying the success list transition. A task with an unsupported nested checklist must still allow root repair while presenting the hierarchy warning/read-only checklist.

5. Run the focused tests and commit.

   ```bash
   ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewScreenStateTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewActivityTest'
   git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/MorningReviewActivity.kt client/androidApp/src/test/kotlin/app/buzer/android/ui
   git commit -m "refactor: use shared editor for morning repair"
   ```

## Task 6: Use the Update host for full deadline-blocker recovery

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerAction.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerTaskSnapshotGateTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt` (remove only obsolete reschedule-widget assertion; do not invoke or alter the fake backend)

1. Add unit tests for the blocker adapter: a complete `TaskSnapshotItem` opens the full Update editor with `Reschedule` as the verb; an invalid snapshot exposes validation immediately; every root field and supported direct checklist mutation can be changed; and the activity log appears after Checklist.

2. Add tests for blocker safety: no complete snapshot means the editor is not shown and instead offers Refresh/Retry; a pending update keeps the editor with its waiting message; a successful authoritative confirmation closes the blocker; and both top Back and bottom Cancel follow the same discard-confirmation path to blocker actions. Keep Android system Back consumed.

3. Retain the complete snapshot in `DeadlineBlockerActivity` whenever `updateModelFromSnapshot()` runs, rather than retaining only `BlockerUiModel` and revision. Pass that snapshot to `DeadlineBlockerScreen`; when it is absent or stale, request a refresh instead of constructing a partial form.

4. Replace `onReschedule` and `coordinator.rescheduleTask` with the existing full `coordinator.updateTask`. Remove `BlockerAction.RESCHEDULE_TASK`, schedule-only request factories, read-only title logic, placeholder Project/Assignee controls, and the legacy `CommitmentEditor` panel. Keep the existing receipt/close-on-success behavior through the shared update outcome.

5. Run unit tests (without the fake server) and commit.

   ```bash
   ./gradlew :androidApp:testDebugUnitTest --tests '*DeadlineBlockerUpdateStateTest'
   ./gradlew :androidApp:testDebugUnitTest --tests '*BlockerTaskSnapshotGateTest'
   git add client/androidApp/src/main/kotlin/app/buzer/android/blockers client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement client/androidApp/src/test/kotlin/app/buzer/android/blockers client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DeadlineBlockerUpdateStateTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
   git commit -m "refactor: use full editor for deadline recovery"
   ```

## Task 7: Retire `CommitmentEditor` and the dedicated reschedule protocol atomically

**Files:**

- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/CommitmentEditor.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt`
- Replace/move: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentEditorModels.kt` → `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/taskeditor/TaskEditorModels.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/ChecklistEditor.kt` and every import of retained checklist/recurrence types
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/test/e2eServer.test.ts`

1. Before deleting implementation, migrate a real e2e-server test that formerly schedules through `/tasks/:id/reschedule` to a complete `/tasks/:id/update` request. It must assert the returned task has the new due time/duration, the request is reconciled through the normal update action, and retries remain idempotent.

   ```ts
   const result = await postTaskUpdate(server, "task-reschedule", {
     actionId,
     rootPatch: {
       expectedTaskRevision: task.taskRevision,
       dueDatetime: "2026-07-12T10:00:00",
       durationMinutes: 30,
     },
     childMutations: [],
   });
   expect(result.status).toBe(202);
   ```

2. Move useful service and route coverage from schedule-only requests to `TaskActionService.update` and `POST /tasks/:id/update`. Remove the reschedule route from the exact route list and delete tests that only specify the obsolete request shape. Keep authoritative e2e-server coverage for success, pending confirmation, stale revision, and action-journal reconciliation.

3. Delete `RescheduleTaskRequest` and its serializer from `TaskModels.kt`, `BuzerApiClient.rescheduleTask`, `EnforcementApi.rescheduleTask`, `EnforcementCoordinator.rescheduleTask`, the backend route body/schema, and `TaskActionService.reschedule`. Delete helpers only reachable from that path. Use `rg -n "RescheduleTaskRequest|rescheduleTask|/reschedule|CommitmentEditor|WheelDateTimeField"` to prove no product references remain.

4. Preserve reusable non-editor types when retiring `CommitmentEditorModels.kt`: move `ChecklistEditorDraft`, `ChecklistDraftValidation`, `ChecklistEditorMutationState`, batch validation, and supported recurrence-choice state into `shared/ui/taskeditor/TaskEditorModels.kt`, update imports, and add/relocate their tests. Do not retain a file or type named `CommitmentEditor`.

5. Run shared and backend contract tests, then commit the removal as one coherent protocol cutover.

   ```bash
   ./gradlew :shared:testAndroidHostTest
   npm test --prefix backend
   npm run typecheck --prefix backend
   rg -n "RescheduleTaskRequest|rescheduleTask|/reschedule|CommitmentEditor|WheelDateTimeField" client backend --glob '!**/build/**'
   git add client/shared client/androidApp backend
   git commit -m "refactor: retire legacy task reschedule flow"
   ```

## Task 8: Add real end-to-end coverage and perform release verification

**Files:**

- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/taskeditor/UnifiedTaskEditorRealBackendTest.kt`
- Create or modify: a test-only real-e2e HTTP helper under `client/androidApp/src/androidTest/kotlin/app/buzer/android/e2e/` as needed, named for the e2e server rather than the deprecated fake backend
- Modify: `docs/handoffs/2026-07-22-unified-task-editor-implementation.md`

1. Add real-backend instrumentation coverage for the full Update path. Use the local e2e server’s scenario controls to verify an invalid Edit/Repair/Blocker snapshot shows entry validation, the user can correct it, `POST /update` is issued, pending confirmation disables resubmission, and a confirmed update produces the correct host-specific success behavior.

2. Include one direct-checklist edit in a Repair or Blocker real-e2e scenario. Include the nested-checklist fixture assertion that root editing remains available while the nested checklist warning is shown. Do not make tests call `tools/fake-backend` or its endpoints.

3. Run the full required deterministic gates. Treat a new regression as blocking; document only pre-existing, evidenced lane limitations in the handoff.

   ```bash
   npm test --prefix backend
   npm run typecheck --prefix backend
   ./gradlew :shared:testAndroidHostTest
   ./gradlew :androidApp:testDebugUnitTest
   ./gradlew :androidApp:assembleDebug
   scripts/e2e-real-backend.sh
   ```

4. On a connected device only after the local verification passes, build/install the debug APK against a local real e2e backend and inspect the non-destructive entry/validation state. Do not use the production Todoist account or submit a mutation without a separate explicit request.

5. Record the exact commit range, test outputs, skipped checks, migration/deployment order, and rollback note in the implementation handoff. Push only after the atomic backend/client change and all required verification gates pass.

   ```text
   Deployment order: deploy backend commit, then install/release the matching APK immediately.
   Rollback: revert both backend and APK to the prior compatible release together.
   ```

## Final acceptance checklist

- [ ] There is no `CommitmentEditor`, `WheelDateTimeField`, `RescheduleTaskRequest`, `rescheduleTask`, or `/tasks/:id/reschedule` product path.
- [ ] Add, Edit, Repair, and Blocker render the same editable field order and common quick-choice/custom wheel-picker interactions.
- [ ] Schedule is first and collapsed state summarizes the current selection.
- [ ] Edit, Repair, and Blocker validate immediately on entry; Add validates only after interaction or submit.
- [ ] Repair returns to its remaining invalid-task list; Blocker closes only after authoritative update confirmation; Edit returns normally.
- [ ] Update hosts show Activity log after Checklist, retain drafts during pending/stale/failed outcomes, and do not implement conflict merging.
- [ ] Missing/stale blocker snapshots show Refresh/Retry rather than a partial editor.
- [ ] Project/collaborator choice loading never hides the editor; save is disabled until required selections are verified.
- [ ] Backend, shared, Android unit, assemble, and real-e2e gates pass without using `tools/fake-backend`.
