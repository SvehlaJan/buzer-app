# Task Editor Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the impractical Add/Edit task form with one fast, draft-safe mobile editor while supporting project-valid assignees and optional schedules for non-self tasks.

**Architecture:** Keep Todoist project/member facts in the backend mirror and extend the existing `/projects`, create, and update contracts rather than adding a second source of truth. Build Add/Edit-only Compose components beside the current shared `CommitmentEditor`; Morning Review repair and blocker reschedule keep that component and their combined picker. The new editor owns a local domain draft, derives every summary/save label from it, and submits one reconciled mutation only after Save.

**Tech Stack:** Kotlin/Compose Material 3, `WheelPickerCompose`, kotlinx serialization, Java time on Android, Kotlin JVM/Android instrumentation tests, Node 22/TypeScript, Fastify 5, SQLite, Vitest, Todoist Sync API.

**Design authority:** `docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md`; Todoist due semantics are amended by `docs/decisions/2026-07-05-todoist-native-due-semantics.md`; recurring-checklist/action rules are amended by `docs/decisions/2026-07-16-occurrence-keyed-recurring-checklists.md`.

---

## Guardrails

- Work in a dedicated worktree from current `master`; do not use the existing
  `.worktrees/e2e-real-backend` worktree.
- Keep `CommitmentEditor.kt`, `WheelDateTimeField.kt`, and their repair/reschedule
  call sites behaviorally unchanged. The replacement is used only by Add/Edit.
- Todoist `due.datetime` is the block **start**. A complete schedule is due plus a
  positive duration; it is not a deadline-minus-duration calculation.
- `currentUserId` is always a valid shared-project assignee. Active synced project
  collaborators are the only other valid shared-project assignees. Personal projects
  display locked `Me` but send `assigneeId = null` to Todoist.
- A non-self assignee may have neither due nor duration. Both must otherwise be set
  together. Self-assigned tasks require both, and an unscheduled task cannot repeat.
- Existing action IDs and captured retry requests are preserved until a domain draft
  value changes; expanding a section or opening/cancelling a dialog does not count.

## File Structure

| Area | Files | Responsibility |
| --- | --- | --- |
| Collaborator mirror | `backend/src/todoist/{todoistTypes,todoistAdapter,httpTodoistAdapter}.ts`, `backend/src/repositories/collaboratorRepository.ts`, `backend/src/db/{schema,migrations}.ts`, `backend/src/services/todoistSyncService.ts` | Sync Todoist collaborator profiles and project-member state, persist it, and make it ready after the forced full mirror sync. |
| Project/API validation | `backend/src/http/routes.ts`, `backend/src/services/taskActionService.ts`, `backend/src/todoist/todoistMutationGateway.ts`, `backend/src/server.ts` | Project projection, membership validation, nullable schedule create/update, and Todoist due/duration clearing. |
| Shared contract | `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt` | Project collaborator DTOs plus explicit nullable schedule patch serialization. |
| Android state/widgets | New `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/{TaskEditorDraft,ScheduleQuickChoices,TaskEditorSchedule,TaskEditorSelectionSheets,TaskEditorScaffold,TaskEditorWheelDialogs}.kt` | Pure draft/formatting logic, Schedule controls, sheets, and Add/Edit-only layout. |
| Screens | `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/{AddTaskScreen,EditTaskScreen,DraftExitPolicy}.kt`, `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`, `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt` | Load project/member choices, apply draft-only selection rules, build create/update requests, and preserve dirty/retry behavior. |
| Harnesses/tests | `backend/test/**`, `tools/fake-backend/src/**`, `client/**/test/**`, `client/**/androidTest/**` | Contract parity and deterministic regression coverage in both fake and real backend E2E lanes. |

### Task 1: Persist and expose Todoist project membership

**Files:**

- Create: `backend/src/repositories/collaboratorRepository.ts`
- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Modify: `backend/src/todoist/todoistTypes.ts`
- Modify: `backend/src/todoist/todoistAdapter.ts`
- Modify: `backend/test/fakes/fakeTodoistAdapter.ts`
- Modify: `backend/test/fakes/e2eTodoistAdapter.ts`
- Test: `backend/test/repositories/collaboratorRepository.test.ts`
- Test: `backend/test/db/migrations.test.ts`

- [x] **Step 1: Write repository and migration tests before adding storage.**

  Add tests that migrate an empty database, upsert `user-me`/`user-wife`, mark Wife
  active for `project-test`, mark her deleted for another project, and assert that
  only the active membership is returned. Also assert the new two tables exist after
  migration and that a database already at migration 3 receives the new tables.

  ```ts
  expect(repo.listActiveForProject("project-test")).toEqual([
    { todoistUserId: "user-wife", fullName: "Wife", email: "wife@example.test" }
  ]);
  expect(repo.isActiveMember("project-test", "user-wife")).toBe(true);
  expect(repo.isActiveMember("project-test", "user-former")).toBe(false);
  ```

- [x] **Step 2: Run the focused tests and observe the missing-repository failure.**

  Run from `backend/`:

  ```text
  npx vitest run test/repositories/collaboratorRepository.test.ts test/db/migrations.test.ts
  ```

  Expected: FAIL because `CollaboratorRepository` and its schema tables do not yet
  exist.

- [x] **Step 3: Add the explicit mirror records and migration 4.**

  In `todoistTypes.ts`, add the immutable adapter facts below; do not reuse a task
  assignee as membership evidence.

  ```ts
  export interface TodoistCollaboratorMirror {
    todoistUserId: string;
    fullName: string;
    email: string | null;
    timeZone: string | null;
    imageId: string | null;
    isDeleted: boolean;
    rawJson: unknown;
    updatedAt: string;
  }

  export interface TodoistCollaboratorStateMirror {
    todoistProjectId: string;
    todoistUserId: string;
    state: "active" | "invited";
    role: string | null;
    isDeleted: boolean;
    rawJson: unknown;
    updatedAt: string;
  }
  ```

  Create `todoist_collaborators` keyed by `todoist_user_id` and
  `todoist_project_collaborators` keyed by `(todoist_project_id, todoist_user_id)`.
  Store raw JSON and tombstones in both tables. Add migration 4 to create the tables,
  then have the repository upsert profiles/states and join active, non-deleted
  `state = 'active'` members. Do not add these tables to task snapshot-generation
  triggers: membership is projected only by `/projects`.

- [x] **Step 4: Make `TodoistSyncResult` carry the new facts.**

  ```ts
  export interface TodoistSyncResult {
    syncToken: string;
    tasks: TodoistTaskMirror[];
    projects: TodoistProjectMirror[];
    collaborators: TodoistCollaboratorMirror[];
    collaboratorStates: TodoistCollaboratorStateMirror[];
  }
  ```

  Update every test adapter implementation to return empty arrays until Task 2 makes
  them meaningful. This preserves compile-time exhaustiveness for fakes.

- [x] **Step 5: Run focused tests and commit the persistence layer.**

  Run from `backend/`:

  ```text
  npx vitest run test/repositories/collaboratorRepository.test.ts test/db/migrations.test.ts
  ```

  Expected: PASS.

  ```text
  git add backend/src/db/schema.sql backend/src/db/migrations.ts backend/src/repositories/collaboratorRepository.ts backend/src/todoist/todoistTypes.ts backend/src/todoist/todoistAdapter.ts backend/test/fakes/fakeTodoistAdapter.ts backend/test/fakes/e2eTodoistAdapter.ts backend/test/repositories/collaboratorRepository.test.ts backend/test/db/migrations.test.ts
  git commit -m "feat: mirror Todoist project collaborators"
  ```

### Task 2: Synchronize collaborators and project them through `/projects`

**Files:**

- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/src/server.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/repositories/collaboratorRepository.ts`
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`
- Modify: `backend/test/services/todoistSyncService.test.ts`
- Modify: `backend/test/http/routes.test.ts`

- [x] **Step 1: Add adapter and projection tests.**

  Extend the HTTP-adapter test fixture with a collaborator and collaborator-state and
  assert its Sync request has all four resources. Extend the sync-service test to
  assert profile/member rows are persisted. Add a route test that a shared project
  projects `Me` plus active collaborators and a personal project projects a locked
  `Me` option.

  ```ts
  expect(form.get("resource_types")).toBe(
    JSON.stringify(["items", "projects", "collaborators", "collaborator_states"])
  );
  expect(response.json().projects).toContainEqual(expect.objectContaining({
    id: "project-test",
    collaboratorsReady: true,
    collaborators: [
      { id: "user-me", displayName: "Me", isCurrentUser: true },
      { id: "user-wife", displayName: "Wife", isCurrentUser: false }
    ]
  }));
  ```

- [x] **Step 2: Run the focused tests and observe contract failures.**

  Run from `backend/`:

  ```text
  npx vitest run test/todoist/httpTodoistAdapter.test.ts test/services/todoistSyncService.test.ts test/http/routes.test.ts
  ```

  Expected: FAIL because sync requests only `items`/`projects` and `/projects` lacks
  collaborator data.

- [x] **Step 3: Map Sync API collaborator records and force one complete member sync.**

  Request `items`, `projects`, `collaborators`, and `collaborator_states`. Map the
  documented Todoist fields `id`, `full_name`, `email`, `timezone`, `image_id`,
  `project_id`, `user_id`, `state`, `role`, and `is_deleted`; use the response
  timestamp or the current time when Todoist omits `updated_at`.

  Inject `CollaboratorRepository` into `TodoistSyncService`, persist all incoming
  profile/state changes in the same transaction as projects/tasks, and upgrade
  `mirror_contract_version` from 2 to 3 only after a full sync has populated this
  contract. A version below 3 forces `sync_token = "*"`; it is not enough merely to
  have project rows. `TaskRepository.isMirrorContractReady()` remains at version 2 so
  existing blocker actions do not wait on a collaborator-only upgrade; create/update
  membership validation uses the separate collaborator readiness check.

- [x] **Step 4: Project safe client-facing collaborator choices.**

  Add the route-only shape below; do not expose raw Todoist state or avatar IDs.

  ```ts
  type ProjectCollaboratorResponse = {
    id: string;
    displayName: string;
    email: string | null;
    isCurrentUser: boolean;
  };

  // For each active project: current user first, then active members sorted by name/id.
  // Deduplicate the current user if Todoist returns a collaborator record for them.
  ```

  Return `collaboratorsReady: mirrorContractVersion >= 3` with every project. Add
  current user as `{ id: currentUserId, displayName: "Me", isCurrentUser: true }`.
  A personal project has that single locked UI choice; a shared project adds active
  collaborator rows. Wire the repository into `buildServer()` and all test builders.

- [x] **Step 5: Run focused tests, then commit the sync/read-model slice.**

  Run from `backend/`:

  ```text
  npx vitest run test/todoist/httpTodoistAdapter.test.ts test/services/todoistSyncService.test.ts test/http/routes.test.ts
  ```

  Expected: PASS.

  ```text
  git add backend/src/todoist/httpTodoistAdapter.ts backend/src/services/todoistSyncService.ts backend/src/server.ts backend/src/http/routes.ts backend/src/repositories/collaboratorRepository.ts backend/test/todoist/httpTodoistAdapter.test.ts backend/test/services/todoistSyncService.test.ts backend/test/http/routes.test.ts
  git commit -m "feat: expose project-specific assignee choices"
  ```

### Task 3: Make mutation contracts support membership and optional schedules

**Files:**

- Modify: `backend/src/http/routes.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/todoist/todoistMutationGateway.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/test/services/taskActionService.test.ts`
- Modify: `backend/test/http/routes.test.ts`
- Modify: `backend/test/todoist/httpTodoistAdapter.test.ts`

- [x] **Step 1: Write failing backend tests for every new invariant.**

  Add focused cases for: a shared-project assignment to a non-member (422
  `assignee_not_in_project`); a Wife task without due/duration (accepted); a Me task
  without either schedule field (422 with `due_datetime_required` and
  `duration_required`); a partial schedule (422); clearing both fields while changing
  a scheduled task to Wife (accepted); and clearing either field while retaining Me
  (rejected).

  Assert the Todoist command sends both `due: null` and `duration: null` for the
  accepted schedule-clear update and sends `responsible_uid: null` for a personal
  move.

- [x] **Step 2: Run the focused test set and observe strict-contract failures.**

  Run from `backend/`:

  ```text
  npx vitest run test/services/taskActionService.test.ts test/http/routes.test.ts test/todoist/httpTodoistAdapter.test.ts
  ```

  Expected: FAIL because the service requires schedule fields unconditionally, accepts
  arbitrary shared-project IDs, and cannot express nullable root schedule patches.

- [x] **Step 3: Preserve absent-versus-null schedule intent end to end.**

  Change TypeScript `RootEditPatch` to distinguish omitted schedule facts from an
  explicit clear and change its resolved form to nullable values:

  ```ts
  export interface RootEditPatch {
    expectedTaskRevision: string;
    dueDatetime?: string | null;
    durationMinutes?: number | null;
    // title, description, projectId, assigneeId, recurrencePreset, timeZone unchanged
  }

  interface ResolvedRootEdit {
    dueDatetime: string | null;
    durationMinutes: number | null;
  }
  ```

  In the Zod route schema, use nullable-and-optional values for both fields and map
  with an own-property check so `{ dueDatetime: null }` survives. Validate this rule:

  ```ts
  const needsSchedule = project.canAssignTasks && input.assigneeId === currentUserId;
  const hasDue = input.dueDatetime !== null;
  const hasDuration = input.durationMinutes !== null;
  // hasDue must equal hasDuration; needsSchedule requires both true.
  ```

  Reject shared-project create/update with `collaborators_unavailable` while the
  collaborator mirror is below version 3. Once ready, require
  `collaborators.isActiveMember(projectId, assigneeId)` unless the assignee is
  `currentUserId`; personal projects still require a null Todoist assignee. When an
  unscheduled mutation is accepted, force/validate `recurrencePreset = "never"`.

- [x] **Step 4: Add duration clearing to the Todoist gateway.**

  Replace the optional duration object with an explicit mutation union and carry it
  through the HTTP adapter:

  ```ts
  export type TodoistDurationMutation =
    | { kind: "preserve" }
    | { kind: "clear" }
    | { kind: "replace"; amount: number; unit: "minute" | "hour" };

  if (input.duration.kind === "clear") args.duration = null;
  if (input.duration.kind === "replace") args.duration = {
    amount: input.duration.amount,
    unit: input.duration.unit
  };
  ```

  Use the existing `TodoistDueMutation.clear` for `due: null`. Update the real adapter
  effect check to compare nullable due/duration and only call recurrence translation
  when a complete schedule exists.

- [x] **Step 5: Run focused tests and commit the mutation-contract slice.**

  Run from `backend/`:

  ```text
  npx vitest run test/services/taskActionService.test.ts test/http/routes.test.ts test/todoist/httpTodoistAdapter.test.ts
  ```

  Expected: PASS.

  ```text
  git add backend/src/http/routes.ts backend/src/services/taskActionService.ts backend/src/todoist/todoistMutationGateway.ts backend/src/todoist/httpTodoistAdapter.ts backend/test/services/taskActionService.test.ts backend/test/http/routes.test.ts backend/test/todoist/httpTodoistAdapter.test.ts
  git commit -m "feat: validate project assignees and optional schedules"
  ```

### Task 4: Update shared Kotlin DTOs and Android validation state

**Files:**

- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`

- [x] **Step 1: Add shared serialization tests before changing the DTOs.**

  Test that a project decodes collaborators/readiness, a create request encodes
  `dueDatetime: null` and `durationMinutes: null`, and an update patch can serialize
  both explicit nulls while leaving omitted schedule facts out of JSON.

  ```kotlin
  assertContains(body, "\"dueDatetime\":null")
  assertContains(body, "\"durationMinutes\":null")
  assertFalse(unchangedRootPatchBody.contains("dueDatetime"))
  ```

- [x] **Step 2: Run shared and form-validator tests to demonstrate the contract gap.**

  Run from `client/`:

  ```text
  ./gradlew :shared:testAndroidHostTest --tests app.buzer.shared.api.BuzerApiClientJsonTest
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.addtask.AddTaskFormValidatorTest
  ```

  Expected: FAIL because create fields are non-nullable, root schedule patches cannot
  represent clear, and the validator requires a schedule for every project.

- [x] **Step 3: Model collaborator choices and nullable schedule patches.**

  Add the serializable model and attach it to `ProjectItem` with safe defaults for
  old snapshot fixtures:

  ```kotlin
  @Serializable
  data class ProjectCollaborator(
      val id: String,
      val displayName: String,
      val email: String? = null,
      val isCurrentUser: Boolean
  )

  data class ProjectItem(
      val id: String,
      val name: String,
      val canAssignTasks: Boolean,
      val shared: Boolean,
      val isInboxProject: Boolean,
      val collaborators: List<ProjectCollaborator> = emptyList(),
      val collaboratorsReady: Boolean = false
  )
  ```

  Make `CreateTaskRequest.dueDatetime` and `.durationMinutes` nullable. Change
  `RootTaskPatch.dueDatetime` and `.durationMinutes` to `NullablePatch<String>` and
  `NullablePatch<Int>` respectively. Add separate serializer helpers for string and
  integer patches so `Absent` omits a field and `Value(null)` writes JSON null.

- [x] **Step 4: Replace static assignee configuration in validation.**

  Delete `AddTaskDependencies.assigneeOptions()` and the `AddTaskAssigneeOption`
  dependency. The validator accepts `ProjectCollaborator?`, determines self with
  `isCurrentUser`, and applies these exact cases:

  ```kotlin
  val scheduleRequired = selectedAssignee?.isCurrentUser == true
  val scheduleComplete = deadline != null && (durationMinutes ?: 0) > 0
  val scheduleEmpty = deadline == null && durationMinutes == null
  // self: require scheduleComplete; non-self: accept scheduleComplete or scheduleEmpty.
  // reject a date/time without a positive duration and a duration without a date/time.
  ```

  For personal projects, select the provided locked Me collaborator in UI state but
  encode `assigneeId = null`. Keep the device time zone internal when a scheduled
  recurrence is serialized; do not add a form field for it.

- [x] **Step 5: Run tests and commit the client contract layer.**

  Run from `client/`:

  ```text
  ./gradlew :shared:testAndroidHostTest --tests app.buzer.shared.api.BuzerApiClientJsonTest
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.addtask.AddTaskFormValidatorTest
  ```

  Expected: PASS.

  ```text
  git add client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientJsonTest.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt
  git commit -m "feat: model task editor collaborator and schedule drafts"
  ```

### Task 5: Build pure Add/Edit draft, schedule, and picker primitives

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorDraft.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoices.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorWheelDialogs.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorDraftTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/ScheduleQuickChoicesTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`

- [x] **Step 1: Write pure tests for the redesigned behavior.**

  Cover injected-clock five-minute rounding, deduplicated date choices, selected-time
  retention, canonical duration choices, cross-midnight summary, `Save task` for an
  unscheduled non-self draft, and `Save 23:30–00:30` for a complete draft. Cover
  reducers where Apply commits a pending date/time/duration but Cancel preserves the
  original draft. Add dirty-exit tests showing Edit project and assignee changes are
  dirty while accordion/dialog state is not.

  ```kotlin
  assertEquals(listOf(15, 30, 60), durationChoices(selectedMinutes = 30))
  assertEquals("Today · 23:30–Tomorrow 00:30 · 1 hr", summary)
  assertEquals(initial, reduceCustomTime(initial, pending = time, apply = false))
  ```

- [x] **Step 2: Run the new JVM tests and observe unresolved symbols.**

  Run from `client/`:

  ```text
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorDraftTest --tests app.buzer.android.ui.taskeditor.ScheduleQuickChoicesTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest
  ```

  Expected: FAIL because the task-editor package and editable Edit fingerprint fields
  do not exist.

- [x] **Step 3: Implement a domain-only editor draft and quick-choice calculator.**

  Keep all clock-dependent values explicit:

  ```kotlin
  internal data class TaskEditorSchedule(
      val date: LocalDate?,
      val hour: Int?,
      val minute: Int?,
      val durationMinutes: Int?
  ) {
      fun isComplete(): Boolean = date != null && hour != null && minute != null && durationMinutes != null
      fun isEmpty(): Boolean = date == null && hour == null && minute == null && durationMinutes == null
  }

  internal fun scheduleQuickChoices(
      draft: TaskEditorSchedule,
      now: LocalDateTime
  ): ScheduleQuickChoices
  ```

  Round a no-selection time up to the next multiple of five; never call
  `LocalDateTime.now()` inside this function or any Compose renderer. Make
  `setNoSchedule` clear all four schedule values and set recurrence to `never` only
  when `assignee.isCurrentUser` is false.

- [x] **Step 4: Add independent WheelPicker dialogs without changing legacy pickers.**

  `TaskEditorWheelDialogs.kt` contains `DateWheelDialog`, `TimeWheelDialog`, and
  `DurationWheelDialog`. Each receives an initial value and holds Compose-local pending
  state. Their Apply callbacks are deliberately typed to their field:

  ```kotlin
  DateWheelDialog(onApply: (LocalDate) -> Unit, onDismiss: () -> Unit)
  TimeWheelDialog(onApply: (hour: Int, minute: Int) -> Unit, onDismiss: () -> Unit)
  DurationWheelDialog(onApply: (Int) -> Unit, onDismiss: () -> Unit)
  ```

  Use a date wheel for Date, two `WheelTextPicker` columns for five-minute Time, and
  the current 0–6-hour/five-minute duration wheels for Duration. Use `AlertDialog`,
  `Apply`, and `Cancel`; `onDismissRequest` calls `onDismiss` without invoking
  `onApply`.

- [x] **Step 5: Update fingerprints, run tests, and commit primitives.**

  Extend `EditTaskDraftFingerprint` and `editTaskDraftFingerprint()` with `projectId`
  and `assigneeId`; snapshot those inputs just like child lists. Run the Step 2 test
  command again. Expected: PASS.

  ```text
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt
  git commit -m "feat: add task editor schedule draft primitives"
  ```

### Task 6: Compose the compact editor scaffold and draft-only selection sheets

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffold.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorSchedule.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorSelectionSheets.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/TaskEditorScaffoldStateTest.kt`

- [x] **Step 1: Write state-level tests for section, selection, and validation behavior.**

  Test that Schedule starts collapsed, opening Notes collapses Schedule, Project and
  Assignee selections update the domain draft and dismiss their sheets, stale
  assignees are replaced by Me when valid, and Save with a self/no-schedule draft
  returns a Schedule-level error with all missing controls highlighted.

  ```kotlin
  assertEquals(EditorSection.Schedule, reduceSection(EditorSection.Notes, EditorSection.Schedule))
  assertEquals(EditorSection.None, reduceSection(EditorSection.Schedule, EditorSection.Schedule))
  assertEquals("Schedule", validation.focusSection)
  ```

- [x] **Step 2: Run the new state test before adding composables.**

  Run from `client/`:

  ```text
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest
  ```

  Expected: FAIL because section/selection reducers do not exist.

- [x] **Step 3: Implement `TaskEditorScaffold` without reusing `CommitmentEditor`.**

  Render a borderless single-line title, Schedule/Project/Assignee/Repeat/Notes/
  Checklist rows, and a sticky Cancel/Save bar. Pass all domain values and callbacks
  in; do not read repositories, generate UUIDs, or call a clock in the scaffold.

  The Schedule row owns the summary and an expanded body with three horizontally
  scrollable quick-choice groups and icon-only Custom controls. Give the controls
  exact semantic labels `Custom date`, `Custom start time`, and `Custom duration`.
  Show `Ends at` only for a complete schedule. Make Notes show one ellipsized line and
  Checklist show `Checklist · done/total done` while collapsed.

- [x] **Step 4: Implement immediate-selection bottom sheets.**

  `ProjectSelectionSheet` filters the provided list by an optional search string.
  `AssigneeSelectionSheet` shows a trailing check and disables itself with retry copy
  if `collaboratorsReady` is false. `RecurrenceSelectionSheet` has the six existing
  presets, displays `Does not repeat` for `never`, and immediately invokes its
  callback/dismisses. Personal Project renders locked Me with no chevron. Keep the
  existing `ChecklistEditor` child mutation controls inside the expanded Checklist
  slot; do not add reordering.

- [x] **Step 5: Run tests and commit the visual component boundary.**

  Run from `client/`:

  ```text
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.taskeditor.TaskEditorScaffoldStateTest --tests app.buzer.android.ui.taskeditor.TaskEditorDraftTest
  ```

  Expected: PASS.

  ```text
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor
  git commit -m "feat: compose compact task editor sections"
  ```

### Task 7: Migrate Add Task to the new draft and collaborator contract

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedback.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlowTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedbackTest.kt`

- [x] **Step 1: Add failing Add-state tests.**

  Cover a personal project selecting locked Me and sending null assignee, a shared
  project selecting Me by default, switching projects invalidating Wife and restoring
  Me, cached collaborator data remaining selectable after a refresh failure, and a
  no-membership shared project disabling Assignee and preventing Save.

- [x] **Step 2: Run focused Add JVM tests.**

  Run from `client/`:

  ```text
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.addtask.AddTaskCreateFlowTest --tests app.buzer.android.ui.addtask.AddTaskSubmitStateTest --tests app.buzer.android.ui.addtask.AddTaskProjectLoadFeedbackTest
  ```

  Expected: FAIL because the current screen uses static BuildConfig assignees and the
  old `CommitmentEditor` receipt.

- [x] **Step 3: Replace static selection state with project-backed draft state.**

  Keep `AddTaskProjectRepository` cache entries as complete `ProjectItem`s, including
  collaborator readiness. On project selection, persist its ID and run the shared
  assignee reducer. Clear `pendingCreateRequest` on every domain change only. On
  initial shared-project selection choose the `isCurrentUser` collaborator; on
  personal selection show locked Me while the request remains unassigned.

- [x] **Step 4: Build and submit through `TaskEditorScaffold`.**

  Replace the Add use of `CommitmentEditor` with the new scaffold. Preserve existing
  project-load retry/error dialogs, create action UUID capture, authoritative snapshot
  handoff, and failure retry. On validation failure, set the schedule focus/field
  highlights or the single Project/Assignee error; do not issue `/tasks`. A valid Wife
  no-schedule draft must produce `CreateTaskRequest(dueDatetime = null,
  durationMinutes = null)` and `Save task`.

- [x] **Step 5: Run Add JVM tests and commit.**

  Re-run the Step 2 command plus

  ```text
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.addtask.AddTaskFormValidatorTest
  ```

  Expected: PASS.

  ```text
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskProjectRepository.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskProjectLoadFeedback.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask
  git commit -m "feat: redesign add task editor"
  ```

### Task 8: Migrate Edit Task while retaining all update and history behavior

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenRequestTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenStateTest.kt`

- [x] **Step 1: Write failing Edit request/state tests.**

  Assert that moving to another project and selecting Wife writes both
  `RootTaskPatch.projectId` and `assigneeId = NullablePatch.Value("user-wife")`; an
  unscheduled Wife edit writes explicit null schedule patches and `never`; a
  non-self-to-self no-schedule Save focuses Schedule rather than submitting; and
  activity-log/checklist mutation behavior remains unchanged.

  ```kotlin
  assertEquals("project-test", request.rootPatch?.projectId)
  assertEquals(NullablePatch.Value("user-wife"), request.rootPatch?.assigneeId)
  assertEquals(NullablePatch.Value(null), request.rootPatch?.dueDatetime)
  assertEquals(NullablePatch.Value(null), request.rootPatch?.durationMinutes)
  ```

- [x] **Step 2: Run focused Edit tests.**

  Run from `client/`:

  ```text
  ./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.ui.screens.EditTaskScreenRequestTest --tests app.buzer.android.ui.screens.EditTaskScreenStateTest --tests app.buzer.android.ui.screens.DraftExitPolicyTest
  ```

  Expected: FAIL because Edit renders Project/Assignee as immutable text and its
  request builder has non-null schedule values.

- [x] **Step 3: Load the same project/member choices as Add.**

  Give `EditTaskScreen` injectable load/refresh project functions for tests and the
  normal `AddTaskDependencies.projectRepository()` defaults for production. Resolve
  the initial selected project by `task.projectId` and the initial assignee by
  `task.assigneeId`, retaining an authoritative existing selection until refreshed
  membership proves it invalid. Render retry/disabled Assignee behavior exactly as
  Add does.

- [x] **Step 4: Build explicit root patches from the complete local draft.**

  Replace the Edit use of `CommitmentEditor` with `TaskEditorScaffold`. Extend
  `updateRequest()` to receive project, selected collaborator, and nullable schedule;
  populate `projectId`, `assigneeId`, due/duration nullable patches, recurrence, and
  the existing child mutation batch in one `UpdateTaskRequest`. Keep its generated
  action ID stable in `pendingRequest`; clear it only after a domain mutation. Retain
  `ActivityLogSection(task)` as an unchanged read-only Edit-only section after
  Checklist.

- [x] **Step 5: Run Edit tests and commit.**

  Re-run the Step 2 command. Expected: PASS.

  ```text
  git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenRequestTest.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenStateTest.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt
  git commit -m "feat: redesign editable task update flow"
  ```

### Task 9: Bring fake and real E2E harnesses to the same contract

**Files:**

- Modify: `tools/fake-backend/src/fixtures.ts`
- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`
- Modify: `backend/test/fakes/fakeTodoistAdapter.ts`
- Modify: `backend/test/fakes/e2eFixtures.ts`
- Modify: `backend/test/fakes/e2eFixtures.test.ts`
- Modify: `backend/test/fakes/e2eTodoistAdapter.ts`
- Modify: `backend/test/fakes/e2eTodoistAdapter.test.ts`
- Modify: `backend/test/e2eServer.test.ts`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Add failing parity and interaction tests.**

  Give the fake and real fixture shared projects `user-me`/`user-wife` collaborator
  data. Test `/projects` parity, membership validation, explicit schedule clearing,
  and optional Wife creation/update. Add instrumentation assertions for collapsed
  Schedule summary, quick choices, each Custom dialog Cancel/Apply, selector-sheet
  dismissal, Notes/Checklist accordion state, project/assignee draft-only mutation,
  cross-midnight Save label, retry preservation, and dirty exit.

- [x] **Step 2: Run focused backend and Android tests to observe missing parity.**

  Run from `backend/`:

  ```text
  npx vitest run test/fakes/e2eFixtures.test.ts test/fakes/e2eTodoistAdapter.test.ts test/e2eServer.test.ts
  ```

  Run from `tools/fake-backend/`:

  ```text
  npm test
  ```

  Run from `client/`:

  ```text
  ./gradlew :androidApp:compileDebugAndroidTestKotlin
  ```

  Expected: FAIL until fixtures and test helpers understand the expanded project
  response and nullable schedule/update fields.

- [x] **Step 3: Update deterministic fixture behavior, not production shortcuts.**

  Make fake/real project responses match the shared `ProjectItem` JSON exactly. In
  both test Todoist worlds, clear duration as well as due when the mutation requests
  it. Make fake `/tasks/:id/update` apply project and assignee fields and reject an
  invalid shared-project member. Do not change the existing magic `[partial-failure]`
  convention; the real-backend lane must continue to record it as the known bucket-c
  difference.

- [x] **Step 4: Migrate instrumentation by semantics rather than old controls.**

  Replace direct `Start picker`/outlined-button assumptions in
  `AddTaskFakeBackendTest` with semantic labels from the new editor. Create
  `EditTaskFakeBackendTest` that opens an existing root, changes Project and Assignee,
  verifies no call occurs before Save, and verifies the one `/tasks/:id/update` body
  carries the complete root patch. Keep `BlockerActivityFakeBackendTest` assertions
  against the legacy shared `CommitmentEditor`/combined picker so a visual change does
  not leak into repair or reschedule.

- [x] **Step 5: Run focused suites and commit parity coverage.**

  Re-run the Step 2 commands. Expected: PASS for focused tests and Android test
  compilation.

  ```text
  git add tools/fake-backend/src backend/test/fakes backend/test/e2eServer.test.ts client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
  git commit -m "test: cover redesigned task editor contracts"
  ```

### Task 10: Full verification, design evidence, and handoff

**Files:**

- Modify: `docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md`
- Create: `docs/handoffs/2026-07-21-task-editor-redesign-implementation.md`

- [x] **Step 1: Run the full backend gate.**

  Run from `backend/`:

  ```text
  npm test
  npm run typecheck
  ```

  Expected: PASS. Record test-file/test-count output and confirm the real E2E server
  conformance tests include project collaborator data.

- [ ] **Step 2: Run all required JVM/build gates.**

  Run from `client/`:

  ```text
  ./gradlew :shared:testAndroidHostTest
  ./gradlew :androidApp:testDebugUnitTest
  ./gradlew :androidApp:assembleDebug
  ```

  Expected: PASS.

- [ ] **Step 3: Run deterministic Android fake-backend E2E.**

  Run from repository root with the documented Android SDK environment:

  ```text
  scripts/e2e-fake-backend.sh
  ```

  Expected: all migrated UI tests pass; the two real-backend scenario tests self-skip.

- [ ] **Step 4: Run the complementary real-backend E2E lane and classify results.**

  Run from repository root with the documented Android SDK environment:

  ```text
  scripts/e2e-real-backend.sh
  ```

  Expected: all redesigned-editor tests pass against real business logic. The only
  acceptable pre-existing failure is
  `addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`, documented as the
  fake-only `[partial-failure]` bucket-c convention. Any other failure is fixed before
  handoff.

- [ ] **Step 5: Perform targeted manual verification and write the handoff.**

  On the connected device/emulator, inspect: collapsed Schedule; one-tap quick choice;
  Custom date/time/duration Apply and Cancel; personal locked Me; shared Me/Wife
  selection; Wife `No schedule`; self-assignment validation; cross-midnight summary;
  Project/Assignee changes staying local until Save; and an existing repair/reschedule
  form still using its old picker. Capture the exact command results and report the
  real lane's known discrepancy if present.

  ```text
  git add docs/superpowers/specs/2026-07-21-task-editor-redesign-design.md docs/handoffs/2026-07-21-task-editor-redesign-implementation.md
  git commit -m "docs: hand off task editor redesign"
  ```

## Plan Self-Review

| Design requirement | Plan coverage |
| --- | --- |
| Equal editable Add/Edit fields and a single Save mutation | Tasks 4, 7, and 8 |
| Project-valid collaborators and personal locked Me | Tasks 1, 2, 3, 4, 7, and 9 |
| Optional non-self schedule with strict self schedule | Tasks 3, 4, 5, 7, 8, and 9 |
| Adaptive Schedule, separate five-minute dialogs, cross-midnight copy | Tasks 5, 6, and 9 |
| Accordion, compact selectors, Notes/Checklist summaries, custom recurrence | Tasks 5, 6, 7, 8, and 9 |
| Draft/retry/dirty state and no time-zone UI | Tasks 4, 5, 7, and 8 |
| Repair/reschedule regression protection | Tasks 6, 9, and 10 |
| Fake and real E2E verification after merged baseline | Tasks 9 and 10 |

The plan deliberately leaves the legacy `CommitmentEditor` and its wheel fields in
place for repair/reschedule. It never creates an immediate Project/Assignee mutation,
an additional task-update endpoint, an avatar download path, or a time-zone form
control.
