# Client & Shared Architecture Deepening Design

Date: 2026-11-11
Status: Approved
Related decision: `docs/decisions/2026-11-11-retire-unused-checklist-and-review-routes.md`
Domain glossary: `CONTEXT.md`

## Overview

This design deepens four areas of `client/shared` and `client/androidApp` stacked atop the deepened backend branch:

1. **Shared Commitment Eligibility & Morning Review Lifecycle Consolidation (Candidate 05)** — Establishes one pure commitment eligibility and interval classification module in `client/shared`, slims unused fields from `SnapshotViewState` and `AlarmPlan`, distinguishes `UnsupportedHierarchy` from `Repair` in Morning Review while aligning Home/Morning Review blocking counts, and collapses Morning Review's micro-modules behind a single `MorningReviewCoordinator` and self-guarded `MorningReviewScheduler`.
2. **Unified Android Task Mutation & Session-Authority Module (Candidate 01)** — Removes `EnforcementApi` pass-through and dead `EnforcementRefreshRunner` + dead `BuzerApiClient` checklist methods, reuses a single process-scoped `BuzerApiClient` (`HttpClient`), unifies operational-vs-defect failure classification, and fixes three live Level 1 authority/warning bugs (`AddTask` error snapshot loss, Home checklist toggle bypassing mutex/warnings, and Morning Review overdue actions swallowing warning messages).
3. **Deepened `TaskEditorUpdateScreen` & Shared Project-Choice Loader (Candidate 03)** — Moves checklist draft state, dirty fingerprinting, replay capture, and `UpdateTaskRequest` building inside `TaskEditorUpdateScreen`, and extracts one shared project/collaborator loader + `TaskEditorProjectLoadStatus` pattern across all four task editors.
4. **Unified Blocker Host Lifecycle & Overlay Failure Handoff (Candidate 06)** — Extracts shared blocker Activity lifecycle/refresh/reconcile coordination across `StartBlockerActivity` and `DeadlineBlockerActivity`, removes dead modal error parameters/states from `BlockerScreens.kt`, and hands off any non-`Success` or timed-out `OverlayBlockerService` action to `StartBlockerActivity` with a sticky `SessionWarningBanner`.

---

## Module 1: Shared Commitment Eligibility & Morning Review Lifecycle (Candidate 05)

### 1. Pure Commitment Eligibility in `client/shared` (`CommitmentEligibility.kt`)
Create `client/shared/src/commonMain/kotlin/app/buzer/shared/scheduling/CommitmentEligibility.kt` as the single source of truth for client-side task classification:
- **Terminal check (`isTerminalCommitment(task)`)**: `task.isDeleted || task.isCompleted || task.state == CommitmentState.completed || task.state == CommitmentState.deleted`.
- **Owner blocking check (`isOwnerBlockingTask(task)`)**: `!isTerminalCommitment(task) && task.isAssignedToUser && (task.state == CommitmentState.invalid || task.hierarchyStatus == HierarchyStatus.unsupported || task.validationErrors.isNotEmpty())`. Note: malformed `planned` tasks (`bad-time`, `bad-duration`) do **not** enter `blocking` cards; they remain fail-closed / contract-defect filtered as in `OwnerAgendaSelectorTest`.
- **Enforceable owner check (`isEnforceableOwnerCommitment(task)`)**: `!isTerminalCommitment(task) && task.isAssignedToUser && task.state != CommitmentState.invalid && task.hierarchyStatus == HierarchyStatus.valid && task.validationErrors.isEmpty()`.
- **Timed agenda window (`timedWindowOrNull(task)`)**: returns `CommitmentWindow(start: Instant, end: Instant)` when `isEnforceableOwnerCommitment(task)`, `start = parseSnapshotInstantOrNull(task.startAt ?: task.dueDatetime)` is non-null, and `durationMinutes != null && durationMinutes > 0`.
- **Active Start-blocker priority check (`hasActiveStartPriorityAt(tasks, now)`)**: returns `true` iff any task with a non-null `timedWindowOrNull(task)` satisfies `now >= window.start && now < window.end && task.startedAt == null && task.state != CommitmentState.started` (covering `planned`, `pre_start_warning`, and `start_blocker_active`). Overdue tasks (`now >= window.end`) return `false` (`2026-09-29-reminder-reliability`: overdue tasks never defer Morning Review).
- Consumers updated to delegate to `CommitmentEligibility`:
  - `AlarmPlanCalculator` (`client/shared`) — uses `isTerminalCommitment` and `isEnforceableOwnerCommitment` while keeping start-only alarm support when `durationMinutes == null` (`schedulesNoDeadlineAlarmWhenDurationIsNull`) and keeping `invalidTimeTaskIds` restricted to unparseable timestamp strings. Remove unused `cancelTaskIds: Set<String>` from `AlarmPlan`.
  - `SnapshotReducer`, `SnapshotDiagnostics`, & `SnapshotViewState` (`client/shared`) — remove unused `activeBlockers`, `inProgressTasks`, `startingToday`, and unused `SnapshotWithCallerDraft` class, keeping `SnapshotViewState(scheduleVersion, snapshotGeneration, serverTime, invalidTasks, healthWarnings, tasks)` and updating `SnapshotDiagnostics` summary formatting accordingly.
  - `OwnerAgendaSelector` (`client/androidApp`) — delegates terminal, owner-blocking, and `timedWindowOrNull` extraction to `CommitmentEligibility` (converting `kotlin.time.Instant` ↔ `java.time.Instant`).
  - `BlockerPriorityResolver` (`client/androidApp`) — delegates to `CommitmentEligibility.hasActiveStartPriorityAt`.

### 2. Distinguish `UnsupportedHierarchy` vs `Repair` in Morning Review & Align Home Counts (Q3)
- In `MorningReviewScreenState.kt`, extend `MorningReviewBlockingCard`:
  ```kotlin
  sealed interface MorningReviewBlockingCard {
      val taskId: String
      val title: String
      val problemText: String
      val description: String?

      data class Repair(
          override val taskId: String,
          override val title: String,
          override val problemText: String,
          override val description: String?
      ) : MorningReviewBlockingCard

      data class UnsupportedHierarchy(
          override val taskId: String,
          override val title: String,
          override val problemText: String,
          override val description: String?
      ) : MorningReviewBlockingCard
  }
  ```
- Classification rule in `blockingCard(task: TaskSnapshotItem)`:
  - If `task.hierarchyStatus == HierarchyStatus.unsupported && task.validationErrors.isEmpty()` and root schedule is present (`parseSnapshotInstantOrNull(task.dueDatetime) != null && (task.durationMinutes ?: 0) > 0`), emit `MorningReviewBlockingCard.UnsupportedHierarchy` with human-readable `hierarchyProblemText(task.hierarchyViolation)`:
    - `"nested_child"` → `"Unsupported hierarchy: subtasks cannot have nested subtasks"`
    - `"too_many_children"` → `"Unsupported hierarchy: more than 30 subtasks"`
    - `"child_has_schedule"` → `"Unsupported hierarchy: a subtask has its own date or time"`
    - `"child_has_duration"` → `"Unsupported hierarchy: a subtask has its own duration"`
    - `"child_has_recurrence"` → `"Unsupported hierarchy: a subtask is recurring"`
    - `"child_has_assignment"` → `"Unsupported hierarchy: a subtask has its own assignee"`
    - `"child_project_mismatch"` → `"Unsupported hierarchy: a subtask is in a different project"`
    - `null` / fallback → `"Unsupported subtask hierarchy — fix in Todoist"`
  - Otherwise (missing start time/duration or root `validationErrors` non-empty), emit `MorningReviewBlockingCard.Repair` as before (allowing root schedule repair in Buzer while checklist stays read-only).
- In `MorningReviewScreen.kt`:
  - `MorningReviewBlockingCard.Repair` keeps its existing kebab-case `testTag = "morning-repair-${card.taskId}"` and `"Repair"` label.
  - `MorningReviewBlockingCard.UnsupportedHierarchy` renders an `"Open Todoist"` `TextButton` with kebab-case `testTag = "morning-open-todoist-${card.taskId}"` (`enabled = !screenState.actionInFlight`) invoking `onOpenTodoist()`.
- In `HomeScreenState.kt`:
  - Change `val reviewTasks = ownerAgenda.blocking` (removing `.filter { it.hierarchyStatus != HierarchyStatus.unsupported }`) so `buildHomeScreenState`'s default `blockingCount` matches `MainActivity.morningReviewBlockingCount()` (`ownerAgenda.blocking.size`). Preserve the exact Home attention copy `"1 invalid task needs review"` / `"$count invalid tasks need review"`.

### 3. Consolidate Morning Review Lifecycle Sprawl
- **Self-guarding `MorningReviewScheduler`**:
  - Inject `canScheduleExactAlarms: () -> Boolean = { true }` into `MorningReviewScheduler` (wired to `ExactAlarmCapability.canScheduleExactAlarms(context)` in `MorningReviewDependencies.scheduler(context)`), deleting `MorningReviewReconcileGuard.kt` and `MorningReviewAlarmActions` (`MorningReviewSchedulerActions`).
  - When `!canScheduleExactAlarms()` (or when `alarmScheduler.schedule` throws `SecurityException` due to mid-call permission revocation):
    - `reconcile`: logs `AppLog.warn` and returns as a no-op without mutating `stateStore`.
    - `acknowledge` & `consume`: still persist the normalized local state (`lastAcknowledgedDate = today` / cleared snooze) with `scheduledAlarm = null` (cancelling legacy/previous OS identities best-effort and skipping `alarmScheduler.schedule`) so tapping `"Start day"` never loops in `catchUpRequired = true`, and a later `reconcile` re-arms tomorrow's alarm once permission returns.
    - `snooze` & `deferForPriority`: still persist `pendingSnoozeDate`/`pendingSnoozeAt` with `scheduledAlarm = null` (logging `AppLog.warn` and skipping OS `schedule`) so foreground catch-up honors the deferral without crashing.
  - When `canScheduleExactAlarms() == true`, non-`SecurityException` failures from `alarmScheduler.schedule` or `stateStore.replace` preserve the existing rollback/recovery + rethrow behavior tested in `MorningReviewSchedulerTest`.
- **Fold `MorningReviewEntryDecider` into `MorningReviewEntryController`**:
  - Delete standalone 22-line `MorningReviewEntryDecider.kt`.
- **Unify `BootReceiver` + `MorningReviewReconcileReceiver` System Reconciliation**:
  - Keep `MorningReviewReconcileReceiver` registered in `AndroidManifest.xml` for `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, and `TIMEZONE_CHANGED` (preserving `MorningReviewAlarmInstrumentationTest.manifestReceiverIsRegisteredForEverySystemReconciliationEvent`), calling `MorningReviewDependencies.scheduler(context).reconcile(...)` directly without `MorningReviewReconcileGuard`.
- **Extract `MorningReviewCoordinator` (`app/buzer/android/morningreview/MorningReviewCoordinator.kt`)**:
  - Owns `availability` calculation, `MorningReviewEntryController`, foreground boundary re-evaluation coroutine (`armReevaluation`), and `MorningReviewScheduler.reconcile(...)` behind one cohesive host helper used by `MainActivity`, replacing 8 scattered Morning Review fields/methods in `MainActivity`.
  - Keep `MorningReviewRuntimeClock` as the test clock seam read by `MorningReviewCoordinator` and `MorningReviewActivity` (removing direct `MorningReviewRuntimeClock` calls from `MainActivity` while keeping the 5 `androidTest` suites that call `MorningReviewRuntimeClock.setForTests(...)` untouched).

---

## Module 2: Unified Android Task Mutation & Session Authority (Candidate 01)

### 1. Delete Redundant Adapters & Dead API Surface
- Delete `EnforcementApi.kt` (`RemoteEnforcementApi` pass-through). `EnforcementCoordinator` depends on a narrow `EnforcementBackendClient` interface implemented directly by `BuzerApiClient` (so unit tests still supply a lightweight fake without spinning up Ktor, while production has zero extra wrapper classes).
- Delete `EnforcementRefreshRunner.kt` and `EnforcementRefreshRunnerTest.kt` (zero production callers).
- Delete `BuzerApiClient.addChecklistItem`, `updateChecklistItem`, `deleteChecklistItem`, plus `AddChecklistItemRequest` and `UpdateChecklistItemRequest` in `TaskModels.kt`.
- Make `EnforcementDependencies.apiClient(context)` return a single cached `BuzerApiClient` instance (keyed by `(baseUrl, bearerToken)` so test overrides still work) instead of allocating a fresh unclosed `HttpClient(CIO)` on every dependency lookup; share that same `BuzerApiClient` with `AddTaskDependencies`.

### 2. Unify Operational-vs-Defect Classification (`FailureClassifier.kt`)
- Create `app/buzer/android/enforcement/FailureClassifier.kt` consolidating operational vs internal-defect classification across `EnforcementCoordinator`, `BlockerErrorReporter`, and `AddTaskProjectRepository`.
- Delete `isAddTaskOperationalFailure` in `AddTaskDependencies.kt` (migrating `AddTaskOperationalFailureTest.kt` assertions into `FailureClassifierTest.kt`).

### 3. Fix Live Bug 1: Route `createTask` Through `EnforcementCoordinator`
- Add `suspend fun createTask(request: CreateTaskRequest): CoordinatorActionResult` on `EnforcementCoordinator`:
  - Calls `api.createTask(request)` inside `runTodoistMutation(action = BlockerAction.CREATE_TASK, taskId = null, actionVerb = "Task creation")`.
  - On HTTP `200`: calls `applyAuthoritativeSnapshot(response.snapshot)` once and returns `CoordinatorActionResult.Success(appliedSnapshot)`.
  - On `BuzerApiException` carrying `error.snapshot` (`409`, `422`, `429`, `502`, `504`, `503`): applies `error.snapshot` to `SnapshotStore` + alarms (just like `updateTask`, `completeTask`, `extendTask`, `toggleChecklistItem`) and returns the typed `CoordinatorActionResult` (`Warning` / `Conflict` / `LocalReconciliationFailed`)!
- Update `AddTaskScreen` (`onCreateTask: suspend (CreateTaskRequest) -> CoordinatorActionResult`) and `App.kt`/`MainActivity.kt` so `AddTaskScreen` uses `CoordinatorActionResult` directly:
  - On `Success`: notifies `MainActivity` (`snapshotViewState = SnapshotReducer.reduce(result.snapshot)`) and navigates back.
  - On `Warning` / `Conflict` / `TransportFailure` / `LocalReconciliationFailed`: updates `MainActivity`'s `snapshotViewState` if `result.snapshot != null` and shows the sticky `SessionWarningBanner` (`Refresh` + `Open Todoist`) in `AddTaskScreen`!
  - Preserve `AddTaskDependencies.cachedProjectRepository` field name (or update the two reflective resets in `UnifiedTaskEditorRealBackendTest.kt` and `EditTaskFakeBackendTest.kt` alongside adding a clean `@VisibleForTesting fun resetForTests()`).

### 4. Fix Live Bug 2: Route Home Checklist Toggle Through `HomeActionController`
- Deepen `HomeDoneActionHandler` (renamed to `HomeActionController` in `HomeDoneActionHandler.kt` or `HomeActionController.kt`) so it owns all three Home mutations/reads behind its single `Mutex`:
  - `refresh(forceTodoist: Boolean)`
  - `completeTask(taskId: String, expectedOccurrenceKey: String?)`
  - `toggleChild(rootId: String, item: ChecklistItem)`
- `toggleChild` sets `setInteractionInFlight(true)` while active, clears status on `CoordinatorActionResult.Success`, updates `snapshotViewState` when `result.snapshot != null`, and surfaces `result.message` on `homeActionStatusMessage` (`SessionWarningBanner`) for `Warning`, `Conflict`, `TransportFailure`, `LocalReconciliationFailed`, and `ChildActionInFlight`!

### 5. Fix Live Bug 3: Preserve Morning Review Overdue Action Warnings
- In `MorningReviewSessionState.kt` (`MorningReviewSessionController.handleOverdueResult`), replace `applyAuthority(result.snapshot)` on non-`Success` outcomes (`Conflict`, `Warning`, `LocalReconciliationFailed`) with a helper that applies `result.snapshot` (when non-null) **and** sets `actionError = result.message` while clearing `actionInFlight = false`! Only `CoordinatorActionResult.Success` clears `actionError = null`.

---

## Module 3: Deepened `TaskEditorUpdateScreen` & Shared Project-Choice Loader (Candidate 03)

### 1. Shared Project-Choice Hook & Inline Status (`ProjectChoicesState.kt`)
- Extract `rememberTaskEditorChoices(repository: TaskEditorProjectRepository, requireNonEmptyProjects: Boolean = false): TaskEditorChoicesHandle` (wrapping `TaskEditorChoices` + `retry()`) in `app/buzer/android/ui/taskeditor/ProjectChoicesState.kt`, shared by `EditTaskScreen`, `DeadlineBlockerScreen`, `MorningReviewScreen`, and `AddTaskScreen`.
- Behavior invariants:
  - Retains cached `projects` across failed `retry()` refreshes (`AddTaskProjectLoadFeedback.canUseCachedProjects == true`) so previously loaded projects and collaborators remain selectable while `errorMessage` surfaces the inline retryable banner.
  - When `requireNonEmptyProjects == true` (`AddTaskScreen`, which cannot submit without a project), uses `AddTaskProjectLoadFeedback.message(...)` so an empty project list with no exception still displays `"No projects available. Check Tailscale/backend sync, then retry."`.
  - When `requireNonEmptyProjects == false` (`EditTaskScreen`, `DeadlineBlockerScreen`, `MorningReviewScreen`, which can still save against the task's existing `projectId`), `errorMessage` is set only when loading fails, rendered via `TaskEditorProjectLoadStatus(loading = choices.loading, message = choices.errorMessage, onRetry = choicesHandle::retry)` (`"Projects unavailable: $message"`).
- Standardize all four editors on `TaskEditorProjectLoadStatus` (`Q4b-1`):
  - Delete both modal `ErrorAlertDialog` blocks (`"Projects unavailable"` and `"Projects did not load"`) and `dismissedProjectDialogMessage` state in `EditTaskScreen.kt`.
  - Replace duplicate `InlineLoadingStatus("Loading projects...")` + `WarningStatus("Projects did not load: ...")` in `BlockerScreens.kt` (`DeadlineBlockerUpdateEditor`) with `TaskEditorProjectLoadStatus`.

### 2. Internalize Checklist Draft State & Request Building in `TaskEditorUpdateScreen`
- Move `ExistingChecklistDraft`, `ChecklistDraft`, `validateChecklistDrafts`, `editTaskDraftFingerprint`, `CapturedEditTaskUpdate`, `captureEditTaskUpdate`, and `updateRequest(...)` into `app/buzer/android/ui/taskeditor/TaskEditorUpdateModel.kt` and internalize all checklist draft state (`existingDrafts`, `deletedChildren`, `addedDrafts`, `checklistError`) and checklist editor composables (`ExistingChecklistEditor`, `ChecklistEditor`) inside `TaskEditorUpdateScreen`!
- New narrow `TaskEditorUpdateScreen` signature:
  ```kotlin
  @Composable
  internal fun TaskEditorUpdateScreen(
      task: TaskSnapshotItem,
      choices: TaskEditorChoices,
      presentation: TaskEditorUpdatePresentation,
      now: LocalDateTime = LocalDateTime.now(),
      initialAddedDrafts: List<ChecklistDraft> = emptyList(),
      onRetryChoices: () -> Unit,
      onRefreshWarning: suspend () -> Result<TaskSnapshotItem?>,
      onUpdate: suspend (UpdateTaskRequest) -> CoordinatorActionResult,
      onSuccess: (SnapshotResponse) -> Unit,
      onCancel: () -> Unit,
      footerContent: @Composable () -> Unit = {},
      modifier: Modifier = Modifier
  )
  ```
- **Preserved semantics across all 3 hosts (`EditTaskScreen`, `DeadlineBlockerScreen`, `MorningReviewScreen`)**:
  - Dirty-draft tracking compares `editTaskDraftFingerprint(...)` (across title, description, project, assignee, schedule, recurrence, and checklist drafts) against the baseline fingerprint of `activeTask` so authoritative snapshot updates re-seed clean editors and preserve dirty drafts.
  - Replay safety (`captureEditTaskUpdate`): if the user clicks Save again without editing any field or checklist draft after a warning/failure, the exact captured `UpdateTaskRequest` (including `clientMutationId` on `childMutations`) is resent; any edit clears the captured request and mints fresh UUIDs on the next Save.
  - All existing Compose `testTag` values (`taskEditorUpdateChecklistSection`, `taskEditorUpdateChecklistAccess`, `editTaskTitleInput`, etc.) remain identical so `UnifiedTaskEditorRealBackendTest` and `EditTaskFakeBackendTest` require no selector changes (only removing any obsolete modal project-dialog assertions if present).

---

## Module 4: Unified Blocker Host Lifecycle & Overlay Failure Handoff (Candidate 06)

### 1. Shared Blocker Host Controller (`BlockerHostController.kt`)
- Extract `BlockerHostController` in `app/buzer/android/blockers/BlockerHostController.kt` encapsulating the duplicated host state and lifecycle logic shared by `StartBlockerActivity` and `DeadlineBlockerActivity`:
  - `taskState`, `snapshotGate` (`BlockerTaskSnapshotGate`), `actionState` (`BlockerActionState`), `warningMessage` (seeded optionally from `EXTRA_INITIAL_WARNING_MESSAGE`), `coordinator` initialization with `BlockerErrorReporter` fallback.
  - `bindIntent(intent)` (supporting both `onCreate` and `onNewIntent` so `FLAG_ACTIVITY_CLEAR_TOP` handoffs from `OverlayBlockerService` update `taskId`, `expectedOccurrenceKey`, and `warningMessage` cleanly!).
  - `refresh(explicitUserRefresh: Boolean)`:
    - When `explicitUserRefresh == false` (initial/resume load): calls `coordinator.refreshAndReconcile()`, closes (`onFinish()`) if resolved, and **preserves** any existing `warningMessage` (such as an `OverlayBlockerService` handoff warning) when the task is still unresolved!
    - When `explicitUserRefresh == true` (user tapped `Refresh` on `SessionWarningBanner`): calls `coordinator.forceSyncAndReconcile()`, clears `warningMessage = null` on success, or sets `warningMessage` + reports via `BlockerErrorReporter` on failure.
  - `runBlockerMutation(loadingLabel, block)` handling `CoordinatorActionResult` outcomes uniformly (`Success` reconciles/finishes; `Warning`/`Conflict`/`TransportFailure`/`LocalReconciliationFailed`/`ChildActionInFlight` re-enables actions and sets `warningMessage = result.message`).
- Clean up `BlockerScreens.kt`:
  - Delete unused `BlockerActionState.Failed` variant.
  - Delete dead `errorMessage`, `onDismissError`, and `onRetryAction` parameters and dead `ErrorAlertDialog` blocks from `StartBlockerScreen` and `DeadlineBlockerScreen`.

### 2. `OverlayBlockerService` Failure Handoff (`Q4a-1`)
- Add `const val EXTRA_INITIAL_WARNING_MESSAGE = "extra_initial_warning_message"` on `StartBlockerActivity` (and `StartBlockerActivity.createIntent(context, taskId, occurrenceKey, initialWarningMessage = null)`).
- Update `OverlayBlockerService.handleAction` (`OverlayBlockerService.kt:171-224`):
  - Capture the `CoordinatorActionResult` returned by `coordinator.startTask(...)` / `coordinator.postponeStart(...)`.
  - On `CoordinatorActionResult.Success`: dismiss overlay (`stopOverlay(generation)`).
  - On any non-`Success` `CoordinatorActionResult`: launch `StartBlockerActivity.createIntent(this@OverlayBlockerService, taskId, occurrenceKey, initialWarningMessage = result.message)` with `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP` and dismiss the native overlay.
  - On `TimeoutCancellationException`: log warning, launch `StartBlockerActivity` with `initialWarningMessage = "Action timed out. Refresh or check Todoist before retrying."`, and dismiss the native overlay.
  - On unexpected `Throwable`: report via `BlockerErrorReporter`, launch `StartBlockerActivity` with `initialWarningMessage = error.message ?: "Internal error. Refresh or check Todoist."`, and dismiss the native overlay.
  - Extract a small testable decision/launcher seam (`OverlayActionOutcomeHandler` or injected `launchWarningFallback: (taskId: String, occurrenceKey: String?, message: String) -> Unit`) so unit tests verify every non-`Success` and timeout path deterministically without Robolectric `WindowManager`.

---

## Test Migration Inventory

| Suite | Changes |
|---|---|
| `client/shared/src/commonTest/.../AlarmPlanCalculatorTest.kt`, `SnapshotReducerTest.kt`, new `CommitmentEligibilityTest.kt` | Add `CommitmentEligibilityTest`; remove assertions on deleted `AlarmPlan.cancelTaskIds` and deleted `SnapshotViewState.{activeBlockers,inProgressTasks,startingToday}` / `SnapshotWithCallerDraft`. |
| `client/androidApp/src/test/.../MorningReviewScreenStateTest.kt`, `HomeScreenStateTest.kt`, `MorningReviewAlarmHandlerTest.kt`, `MorningReviewSchedulerTest.kt`, `MorningReviewSessionControllerTest.kt` | Add tests for `MorningReviewBlockingCard.UnsupportedHierarchy` vs `Repair`, aligned Home blocking count, self-guarded `MorningReviewScheduler` when exact alarms are disallowed, and `MorningReviewSessionController` preserving `actionError` on `Warning`/`Conflict`/`LocalReconciliationFailed`. |
| `client/androidApp/src/test/.../EnforcementCoordinatorTest.kt`, `HomeDoneActionHandlerTest.kt`, `BlockerActionStateTest.kt`, new `OverlayActionOutcomeTest.kt`, new `FailureClassifierTest.kt` | Test `EnforcementCoordinator.createTask` (both 200 and non-2xx with `error.snapshot`), `HomeActionController.toggleChild` mutex + warning surface, deleted `BlockerActionState.Failed` cleanup, `TaskEditorUpdateModel` request/fingerprint/replay helpers, and `OverlayBlockerService` failure handoff to `StartBlockerActivity`. Delete `EnforcementRefreshRunnerTest.kt` and replace `AddTaskOperationalFailureTest.kt` with `FailureClassifierTest.kt`. |
| `client/androidApp/src/androidTest/...` (`MorningReviewAlarmInstrumentationTest.kt`, `UnifiedTaskEditorRealBackendTest.kt`, `EditTaskFakeBackendTest.kt`, `BlockerActivityFakeBackendTest.kt`, `AddTaskFakeBackendTest.kt`, `HomeScreenTest.kt`) | Verify `MorningReviewReconcileReceiver` manifest test remains green, keep `AddTaskDependencies.cachedProjectRepository` / `resetForTests()` compatible, update any test constructing `SnapshotViewState(...)` directly if present, and run full `scripts/e2e-real-backend.sh` as final authority. |
