# Client & Shared Architecture Deepening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deepen `client/shared` and `client/androidApp` across four modules: shared commitment eligibility + Morning Review lifecycle consolidation, unified Android mutation/session authority (fixing three live Level 1 bugs), internalized `TaskEditorUpdateScreen` checklist/project state, and shared blocker Activity lifecycle + native overlay failure handoff.

**Architecture:** Four sequential tasks executed in `.worktrees/deepen-client` stacked atop `feature/deepen-backend`: (1) extract `CommitmentEligibility` in `client/shared`, slim `SnapshotViewState`/`AlarmPlan`, add `MorningReviewBlockingCard.UnsupportedHierarchy`, self-guard `MorningReviewScheduler`, and consolidate `MainActivity` Morning Review wiring into `MorningReviewCoordinator`; (2) delete `EnforcementApi` + dead refresh/checklist APIs, unify `FailureClassifier`, reuse one `BuzerApiClient`, and route `AddTask`, Home checklist toggle, and Morning Review overdue warnings through authoritative Level 1 coordinators; (3) extract `rememberTaskEditorChoices` + standardize inline `TaskEditorProjectLoadStatus` and internalize checklist draft/fingerprint/replay/request logic inside `TaskEditorUpdateScreen`; (4) extract `BlockerHostController` across `StartBlockerActivity` and `DeadlineBlockerActivity`, remove dead modal error plumbing, and hand off `OverlayBlockerService` non-`Success`/timeout outcomes to `StartBlockerActivity` with `EXTRA_INITIAL_WARNING_MESSAGE`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Ktor Client, Kotlinx Serialization / Coroutines / Datetime, Android SDK (API 36 / minSdk), JUnit / `kotlin.test`, AndroidX Compose UI Test (`scripts/e2e-real-backend.sh`).

**Spec:** `docs/superpowers/specs/2026-11-11-client-architecture-deepening-design.md` (and `docs/decisions/2026-11-11-retire-unused-checklist-and-review-routes.md`).

## Global Constraints

- Export `ANDROID_HOME=/Users/svehla/Library/Android/sdk` and `ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk` when running Gradle in `.worktrees/deepen-client` (`BypassSandbox: true` required for Android SDK and `.git/` writes).
- Preserve existing Compose `testTag` strings verbatim, including kebab-case `"morning-repair-${card.taskId}"`, `"morning-blocking-${card.taskId}"`, `"taskEditorUpdateChecklistSection"`, `"taskEditorUpdateChecklistAccess"` (and use kebab-case `"morning-open-todoist-${card.taskId}"` for the new `UnsupportedHierarchy` button).
- Preserve the exact Home attention banner copy `"1 invalid task needs review"` / `"$count invalid tasks need review"` (`HomeScreenTest.kt` + `HomeScreenStateTest.kt`).
- Overdue commitments (`now >= window.end`) MUST NOT produce active Start-blocker priority (`hasActiveStartPriorityAt` returns `false` for overdue tasks per `2026-09-29-reminder-reliability` and `MorningReviewAlarmInstrumentationTest.cachedOverdueTasksNeverDeferReview`).
- Keep `MorningReviewReconcileReceiver` registered in `AndroidManifest.xml` for `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, and `TIMEZONE_CHANGED`, and keep `MorningReviewRuntimeClock` (`snapshot()`, `now()`, `zoneId()`, `setForTests(...)`) and `AddTaskDependencies.cachedProjectRepository` compatible with existing `androidTest` suites.
- Keep `OverlayBlockerService` completely free of `ComposeView` (`2026-06-10-android-overlay-enforcement-phase-1.md`).
- Final completion requires green `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` AND a full unfiltered `scripts/e2e-real-backend.sh` run.

## Review Focus

1. **Exact-alarm permission revoked mid-session when tapping `"Start day"` (`acknowledge`) or `"Snooze 5 min"` (`snooze`)**: `MorningReviewScheduler` must still persist `lastAcknowledgedDate = today` (or `pendingSnoozeAt`) with `scheduledAlarm = null` when `!canScheduleExactAlarms()` or `AlarmManager` throws `SecurityException`, rather than early-returning as a no-op and trapping `MainActivity` in an infinite `catchUpRequired = true` loop (pinned in Task 1).
2. **`AddTask` failing with `429`/`502`/`504` carrying `error.snapshot` (e.g. post-sync or delivery-unknown snapshot)**: `EnforcementCoordinator.createTask` must reconcile `error.snapshot` into `SnapshotStore` + alarms and propagate it to `MainActivity` while keeping `AddTaskScreen` open with `SessionWarningBanner` (pinned in Task 2).
3. **Home checklist checkbox toggled while `Done` or `Refresh` is in flight, or returning `Warning`/`Conflict`**: `HomeActionController.toggleChild` must serialize behind the same `Mutex`, toggle `interactionInFlight`, and display `result.message` on `homeActionStatusMessage` instead of silently failing (pinned in Task 2).
4. **Retrying a failed `UpdateTask` Save without changing any field or checklist draft vs after editing a checklist item**: `TaskEditorUpdateScreen` must replay the identical captured `UpdateTaskRequest` (same `clientMutationId` UUIDs) when untouched and mint fresh `clientMutationId`s as soon as any root field or checklist draft changes (pinned in Task 3).
5. **`OverlayBlockerService` handing off a `Warning` or timeout to `StartBlockerActivity` whose `onCreate`/`onResume` immediately runs `refreshAndReconcile()`**: `BlockerHostController` must preserve `EXTRA_INITIAL_WARNING_MESSAGE` across non-explicit background/lifecycle refreshes when the task is still unresolved, clearing it only when the task resolves or the user explicitly taps `Refresh` on `SessionWarningBanner` (pinned in Task 4).

---

### Task 1: Shared `CommitmentEligibility`, `UnsupportedHierarchy` Morning Review Card, and Morning Review Lifecycle Consolidation

**Files:**
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/scheduling/CommitmentEligibility.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/scheduling/CommitmentEligibilityTest.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotReducer.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotDiagnostics.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotReducerTest.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotDiagnosticsTest.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewReconcileGuard.kt`
- Delete: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewReconcileGuardTest.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewEntryDecider.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewEntryDeciderTest.kt` (update to test `MorningReviewEntryController` directly)
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewCoordinator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewEntryController.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewReconcileReceiver.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSchedulerTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandlerTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/BlockerPriorityResolverTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/SessionWarningBannerTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelectorTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSessionStateTest.kt`

**Interfaces:**
- Consumes: `TaskSnapshotItem`, `TasksSnapshotResponse`, `parseSnapshotInstantOrNull`.
- Produces:
  - `object CommitmentEligibility` (`app.buzer.shared.scheduling`):
    - `data class CommitmentWindow(val start: Instant, val end: Instant)`
    - `fun isTerminalCommitment(task: TaskSnapshotItem): Boolean`
    - `fun isOwnerBlockingTask(task: TaskSnapshotItem): Boolean`
    - `fun isEnforceableOwnerCommitment(task: TaskSnapshotItem): Boolean`
    - `fun timedWindowOrNull(task: TaskSnapshotItem): CommitmentWindow?`
    - `fun hasActiveStartPriorityAt(tasks: Iterable<TaskSnapshotItem>, now: Instant): Boolean`
  - Slimmed `SnapshotViewState(scheduleVersion, snapshotGeneration = 0, serverTime, invalidTasks, healthWarnings, tasks)` (deleted `activeBlockers`, `inProgressTasks`, `startingToday`, `SnapshotWithCallerDraft`) and slimmed `AlarmPlan(alarms, invalidTimeTaskIds)` (deleted `cancelTaskIds`).
  - `MorningReviewBlockingCard.UnsupportedHierarchy(taskId, title, problemText, description)` alongside `Repair`.
  - Self-guarded `MorningReviewScheduler(..., canScheduleExactAlarms: () -> Boolean = { true })` (no `MorningReviewAlarmActions` wrapper, no `MorningReviewReconcileGuard`).
  - `class MorningReviewCoordinator` (`app.buzer.android.morningreview`) encapsulating `availability`, `onResume()`, `onPause()`, `onActivityResult()`, `openFromHome()`, and `refreshAvailability(snapshot, scope, isReady, onLaunchAutomated)`.

- [x] **Step 1: Write failing shared & Android unit tests for `CommitmentEligibility`, `UnsupportedHierarchy` vs `Repair`, aligned Home count, and exact-alarm-denied `MorningReviewScheduler`**

1. Create `CommitmentEligibilityTest.kt` testing `isTerminalCommitment`, `isOwnerBlockingTask` (excluding malformed `planned` tasks without `validationErrors`), `timedWindowOrNull`, and `hasActiveStartPriorityAt` (returning `true` for unstarted `planned`/`pre_start_warning`/`start_blocker_active` inside `[start, end)`, and `false` for `started` or overdue `now >= end`).
2. In `MorningReviewScreenStateTest.kt`, add tests verifying:
   - An owner task with `hierarchyStatus = HierarchyStatus.unsupported`, `validationErrors = emptyList()`, `due = "2026-08-02T12:00:00Z"`, `duration = 30`, `hierarchyViolation = "child_has_schedule"` produces `MorningReviewBlockingCard.UnsupportedHierarchy` with `problemText = "Unsupported hierarchy: a subtask has its own date or time"`.
   - An owner task with `hierarchyStatus = HierarchyStatus.unsupported` AND `validationErrors = listOf("missing_due_datetime", "missing_duration")` continues to produce `MorningReviewBlockingCard.Repair`.
3. In `HomeScreenStateTest.kt`, add a test verifying `buildHomeScreenState(snapshotWithUnsupportedOwner, morningReviewBannerVisible = true)` (with `ownerBlockingCount = null`) produces `"1 invalid task needs review"` matching `MainActivity`.
4. In `MorningReviewSchedulerTest.kt`, add tests with `canScheduleExactAlarms = { false }` (and with `alarmScheduler.schedule` throwing `SecurityException`):
   - `reconcile` is a no-op leaving `stateStore` untouched and scheduling nothing;
   - `acknowledge` persists `lastAcknowledgedDate = today` and `scheduledAlarm = null` without throwing, and a subsequent `reconcile` once `canScheduleExactAlarms = { true }` arms tomorrow's daily alarm;
   - `snooze` and `deferForPriority` persist `pendingSnoozeAt` with `scheduledAlarm = null` without throwing.

- [x] **Step 2: Run `:shared:testAndroidHostTest` and `:androidApp:testDebugUnitTest` to verify failure**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :shared:testAndroidHostTest :androidApp:testDebugUnitTest`
Expected: FAIL on missing `CommitmentEligibility`, missing `MorningReviewBlockingCard.UnsupportedHierarchy`, and missing `canScheduleExactAlarms` parameter.

- [x] **Step 3: Implement `CommitmentEligibility`, slim `SnapshotViewState`/`AlarmPlan`, add `UnsupportedHierarchy` UI, self-guard `MorningReviewScheduler`, and extract `MorningReviewCoordinator`**

1. Implement `CommitmentEligibility.kt`, update `AlarmPlanCalculator.kt`, `SnapshotReducer.kt`, `SnapshotDiagnostics.kt`, `OwnerAgendaSelector.kt`, `BlockerPriorityResolver` (`MorningReviewAlarmHandler.kt`), and update all `SnapshotViewState(...)` / `cancelTaskIds` test helpers listed in **Files**.
2. Implement `MorningReviewBlockingCard.UnsupportedHierarchy` in `MorningReviewScreenState.kt`, render `"Open Todoist"` (`Modifier.testTag("morning-open-todoist-${card.taskId}")`) in `MorningReviewScreen.kt`, and remove `.filter { it.hierarchyStatus != HierarchyStatus.unsupported }` in `HomeScreenState.kt:138`.
3. Fold `MorningReviewReconcileGuard` into `MorningReviewScheduler` (`canScheduleExactAlarms` + `SecurityException` handling), delete `MorningReviewReconcileGuard.kt` and `MorningReviewAlarmActions`, fold `MorningReviewEntryDecider.kt` into `MorningReviewEntryController.kt`, extract `MorningReviewCoordinator.kt`, and simplify `MainActivity.kt`, `MorningReviewReconcileReceiver.kt`, and `BootReconcileRunner.kt`.

- [x] **Step 4: Run shared & Android JVM unit tests**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :shared:testAndroidHostTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit Task 1**

```bash
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client add -A client
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client commit -m "refactor(client): unify commitment eligibility, distinguish unsupported hierarchy, and consolidate Morning Review lifecycle"
```

---

### Task 2: Unified Android Task Mutation & Session Authority (Fixing 3 Live Level 1 Bugs)

**Files:**
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunner.kt`
- Delete: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunnerTest.kt`
- Delete: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/AddTaskOperationalFailureTest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/FailureClassifier.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/FailureClassifierTest.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/shared/src/commonTest/kotlin/app/buzer/shared/api/BuzerApiClientContractTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/tasks/AddTaskDependencies.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/HomeDoneActionHandler.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewSessionState.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeDoneActionHandlerTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewSessionStateTest.kt`

**Interfaces:**
- Consumes: `BuzerApiClient`, `SnapshotReducer` (Task 1).
- Produces:
  - `interface EnforcementBackendClient` (in `EnforcementCoordinator.kt`, implemented directly by `BuzerApiClient` — or `TaskBackendClient` in `client/shared` implemented by `BuzerApiClient`): `fetchSnapshot(forceRefresh: Boolean)`, `createTask(request: CreateTaskRequest)`, `startTask`, `postponeTask`, `completeTask`, `extendTask`, `updateTask`, `checkChecklistItem`, `uncheckChecklistItem` (`addChecklistItem`, `updateChecklistItem`, `deleteChecklistItem`, `AddChecklistItemRequest`, `UpdateChecklistItemRequest` deleted).
  - `EnforcementCoordinator.createTask(request: CreateTaskRequest): CoordinatorActionResult` (plus `BlockerAction.CREATE_TASK`).
  - `object FailureClassifier`: `fun isOperationalFailure(error: Throwable): Boolean`.
  - `HomeDoneActionHandler` (with added `toggleChild(rootId: String, item: ChecklistItem)` method alongside `refresh` and `completeTask`, all serialized under its `Mutex`).
  - `MorningReviewSessionController.handleOverdueResult` preserving `actionError = result.message` on `Warning`, `Conflict`, and `LocalReconciliationFailed` even when `result.snapshot != null`.

- [x] **Step 1: Write failing unit tests for the 3 live Level 1 bugs and `FailureClassifier`**

1. Create `FailureClassifierTest.kt` migrating operational vs defect cases from `AddTaskOperationalFailureTest.kt` plus `forced_refresh_failed`, `backend_busy`, `todoist_rejected`, `delivery_unknown`, `accepted_stale`, `sync_initializing`, and 500 `internal_error`.
2. In `EnforcementCoordinatorTest.kt`:
   - `createTaskAppliesAuthoritativeSnapshotAndSchedulesAlarmsOnSuccess`
   - `createTaskReconcilesErrorSnapshotAndReturnsWarningWhenBackendReturnsDeliveryUnknownOrAcceptedStale` (`BuzerApiException` 502/504 with `error.snapshot` updates `SnapshotStore` and returns `CoordinatorActionResult.Warning(..., snapshot = reconciled)`).
3. In `HomeDoneActionHandlerTest.kt`:
   - `toggleChildSerializesWithMutexUpdatesSnapshotAndSurfacesWarningMessageOnNonSuccess` (verifies `setInteractionInFlight(true -> false)`, `applySnapshot` when `result.snapshot != null`, and `setStatusMessage(result.message)` on `Warning`/`Conflict`/`TransportFailure`/`ChildActionInFlight`).
4. In `MorningReviewSessionStateTest.kt`:
   - `overdueActionWarningWithSnapshotUpdatesSnapshotWhileKeepingActionErrorVisible` (verifies `session.state.snapshot` updates to the warning's snapshot AND `session.state.actionError == result.message`, `actionInFlight == false`).

- [x] **Step 2: Run `:shared:testAndroidHostTest` and `:androidApp:testDebugUnitTest` to verify failure**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :shared:testAndroidHostTest :androidApp:testDebugUnitTest`
Expected: FAIL on missing `FailureClassifier`, missing `coordinator.createTask`, missing `toggleChild`, and `MorningReviewSessionStateTest` clearing `actionError` to `null`.

- [x] **Step 3: Remove dead APIs/wrappers, cache shared `BuzerApiClient`, implement `FailureClassifier`, and fix all 3 bugs**

1. Remove `addChecklistItem`, `updateChecklistItem`, `deleteChecklistItem` from `BuzerApiClient.kt` and `AddChecklistItemRequest`, `UpdateChecklistItemRequest` from `TaskModels.kt` (updating `BuzerApiClientContractTest.kt` accordingly); have `BuzerApiClient` implement `TaskBackendClient` (`client/shared/src/commonMain/kotlin/app/buzer/shared/api/TaskBackendClient.kt`) and delete `EnforcementApi.kt` and `EnforcementRefreshRunner.kt` + `EnforcementRefreshRunnerTest.kt`.
2. Cache a single `BuzerApiClient` per `(baseUrl, bearerToken)` inside `EnforcementDependencies.apiClient(context)` and reuse it in `AddTaskDependencies` (preserving `private var cachedProjectRepository` field name for existing `androidTest` reflection helpers).
3. Implement `FailureClassifier.kt` and wire `EnforcementCoordinator`, `BlockerErrorReporter`, `AddTaskDependencies`, and `AddTaskScreen` to it (deleting `isAddTaskOperationalFailure` in `AddTaskScreen.kt` and deleting `AddTaskOperationalFailureTest.kt`).
4. Add `EnforcementCoordinator.createTask(request: CreateTaskRequest): CoordinatorActionResult` and wire `MainActivity` → `BuzerApp` → `AddTaskScreen` (`onCreateTask: suspend (CreateTaskRequest) -> CoordinatorActionResult`) so `AddTaskScreen` handles `Success` vs `Warning`/`Conflict`/`TransportFailure`/`LocalReconciliationFailed` identically to `TaskEditorUpdateScreen`.
5. Add `toggleChild(rootId: String, item: ChecklistItem)` to `HomeDoneActionHandler` and call it from `MainActivity.toggleCurrentChild`.
6. Update `MorningReviewSessionController.handleOverdueResult` (`MorningReviewSessionState.kt`) so non-`Success` results with a snapshot update `snapshot` while preserving `actionError = result.message`.

- [x] **Step 4: Run shared & Android JVM unit tests**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :shared:testAndroidHostTest :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit Task 2**

```bash
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client add -A client
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client commit -m "fix(client): route AddTask, Home checklist toggle, and Morning Review warnings through session authority"
```

---

### Task 3: Deepen `TaskEditorUpdateScreen` & Standardize Inline Project-Choice Loading

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/ProjectChoicesState.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateModel.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/taskeditor/TaskEditorUpdateScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/EditTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt` (`DeadlineBlockerUpdateEditor`)
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt` (`MorningReviewRepairEditor`)
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/DraftExitPolicy.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/EditTaskScreenRequestTest.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/screens/DraftExitPolicyTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/taskeditor/ProjectChoicesStateTest.kt`

**Interfaces:**
- Consumes: `TaskEditorProjectLoadStatus`, `AddTaskProjectLoadFeedback`, `CoordinatorActionResult`, `ChecklistDraft` (`TaskModels.kt`), `ChecklistEditorDraft` & `validateChecklistDrafts` (`TaskEditorModels.kt` — kept in `client/shared`).
- Produces:
  - `data class TaskEditorChoicesHandle(val choices: TaskEditorChoices, val feedbackMessage: String?, val retry: () -> Unit)` and `@Composable internal fun rememberTaskEditorChoices(repository: TaskEditorProjectRepository, requireNonEmptyProjects: Boolean = false): TaskEditorChoicesHandle` (backed by pure `reduceProjectChoicesLoad(...)` helper in `ProjectChoicesState.kt`).
  - `TaskEditorUpdateModel.kt` owning `ExistingChecklistDraft`, `toEditorDraft`, `CapturedEditTaskUpdate`, `captureEditTaskUpdate`, and `buildUpdateTaskRequest(...)` (with `ChecklistDraftFingerprint` and `editTaskDraftFingerprint` shared cleanly with `DraftExitPolicy.kt`).
  - Deepened `TaskEditorUpdateScreen(task, choices, presentation, now, initialAddedDrafts: List<ChecklistEditorDraft> = emptyList(), onRetryChoices, onRefreshWarning, onUpdate, onSuccess, onCancel, footerContent, modifier)` owning all checklist draft state, dirty detection, replay-UUID capture, `ExistingChecklistEditor`, `ChecklistEditor`, and `TaskEditorProjectLoadStatus`.

- [ ] **Step 1: Write failing unit tests for `ProjectChoicesState` and `TaskEditorUpdateModel` replay/fingerprint invariants**

1. Create `ProjectChoicesStateTest.kt`:
   - `updateEditorKeepsCachedProjectsSelectableAndExposesErrorMessageOnRefreshFailure` (`requireNonEmptyProjects = false`)
   - `updateEditorDoesNotShowWarningWhenProjectsListIsEmptyWithoutError` (`requireNonEmptyProjects = false`)
   - `addTaskEditorShowsNoProjectsAvailableMessageWhenLoadedEmpty` (`requireNonEmptyProjects = true`)
2. Add/expand tests in `EditTaskScreenRequestTest.kt` (and `DraftExitPolicyTest.kt` if needed) verifying:
   - `captureEditTaskUpdate` reuses identical `clientMutationId` values on `childMutations` when `fingerprint` is unchanged and mints fresh `clientMutationId`s as soon as an added/existing/deleted checklist item or root field changes.

- [ ] **Step 2: Run `:androidApp:testDebugUnitTest` to verify failure**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :androidApp:testDebugUnitTest`
Expected: FAIL on missing `ProjectChoicesState` / `reduceProjectChoicesLoad`.

- [ ] **Step 3: Implement `ProjectChoicesState`, `TaskEditorUpdateModel`, and deepened `TaskEditorUpdateScreen`, and slim all 4 editor hosts**

1. Create `ProjectChoicesState.kt` and wire `EditTaskScreen`, `DeadlineBlockerUpdateEditor`, `MorningReviewRepairEditor`, and `AddTaskScreen` to `rememberTaskEditorChoices` + `TaskEditorProjectLoadStatus` — deleting the two modal `ErrorAlertDialog` blocks (`"Projects unavailable"` and `"Projects did not load"`) in `EditTaskScreen.kt` and deleting the duplicate `InlineLoadingStatus` + `"Projects did not load"` `WarningStatus` in `BlockerScreens.kt`.
2. Move `androidApp` checklist update models (`ExistingChecklistDraft`, `toEditorDraft`), replay capture (`CapturedEditTaskUpdate`, `captureEditTaskUpdate`), and `buildUpdateTaskRequest` (keeping `ChecklistDraft` and `ChecklistEditorDraft`/`validateChecklistDrafts` in `client/shared`) into `TaskEditorUpdateModel.kt` (updating `BlockerActionStateTest.kt` and `EditTaskScreenRequestTest.kt` to import `buildUpdateTaskRequest`), internalize checklist state + `ExistingChecklistEditor` + `ChecklistEditor` inside `TaskEditorUpdateScreen.kt`, and delete the ~330 lines of triplicated state/request plumbing across `EditTaskScreen.kt`, `BlockerScreens.kt`, and `MorningReviewScreen.kt`.

- [ ] **Step 4: Run `:androidApp:testDebugUnitTest` and `:androidApp:assembleDebug`**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit Task 3**

```bash
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client add -A client
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client commit -m "refactor(client): internalize checklist state in TaskEditorUpdateScreen and standardize inline project status"
```

---

### Task 4: Unify Blocker Host Lifecycle & Hand Off Native Overlay Failures to `StartBlockerActivity`

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/BlockerHostController.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/blockers/BlockerHostControllerTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayActionOutcomeTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`

**Interfaces:**
- Consumes: `EnforcementCoordinator`, `BlockerTaskSnapshotGate`, `CoordinatorActionResult`, `BlockerErrorReporter`.
- Produces:
  - `StartBlockerActivity.EXTRA_INITIAL_WARNING_MESSAGE = "extra_initial_warning_message"` and `StartBlockerActivity.createIntent(context, taskId, occurrenceKey, initialWarningMessage = null)`.
  - `class BlockerHostController` (`app.buzer.android.blockers`):
    - Manages `taskState`, `actionState` (`BlockerActionState.Idle` | `Loading` — `BlockerActionState.Failed` deleted), `warningMessage`, `bindTarget(taskId, occurrenceKey, initialWarningMessage)`, `refresh(explicitUserRefresh: Boolean)`, and `runMutation(loadingLabel, block)`.
    - Preserves `warningMessage` across `refresh(explicitUserRefresh = false)` while the task remains unresolved; clears `warningMessage` on `refresh(explicitUserRefresh = true)` success or finishes when resolved.
  - Pure `resolveOverlayActionOutcome(result: Result<CoordinatorActionResult>): OverlayActionDisposition` (`Dismiss` vs `EscalateToStartBlocker(warningMessage: String, reportDefect: Throwable?)`) used by `OverlayBlockerService.handleAction`.

- [ ] **Step 1: Write failing unit tests for `BlockerHostController` initial-warning preservation and `OverlayBlockerService` failure handoff**

1. Create `BlockerHostControllerTest.kt` testing:
   - `initialWarningFromOverlaySurvivesLifecycleRefreshWhileTaskRemainsUnresolvedAndClearsOnExplicitUserRefresh`
   - `rebindFromNewIntentUpdatesTaskOccurrenceAndInitialWarning`
   - `resolvedSnapshotDuringRefreshCallsOnResolvedAndFinishes`
   - `nonSuccessMutationSetsWarningMessageAndReenablesActions`
2. Create `OverlayActionOutcomeTest.kt` testing `resolveOverlayActionOutcome` across:
   - `CoordinatorActionResult.Success` → `OverlayActionDisposition.Dismiss`
   - `Warning` / `Conflict` / `TransportFailure` / `LocalReconciliationFailed` / `ChildActionInFlight` → `EscalateToStartBlocker(warningMessage = result.message, reportDefect = null)`
   - `TimeoutCancellationException` → `EscalateToStartBlocker(warningMessage = "Action timed out. Refresh or check Todoist before retrying.", reportDefect = null)`
   - Unexpected `IllegalStateException("boom")` → `EscalateToStartBlocker(warningMessage = "boom", reportDefect = error)`.

- [ ] **Step 2: Run `:androidApp:testDebugUnitTest` to verify failure**

Run: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :androidApp:testDebugUnitTest`
Expected: FAIL on missing `BlockerHostController` and `resolveOverlayActionOutcome`.

- [ ] **Step 3: Implement `BlockerHostController`, clean dead modal error plumbing in `BlockerScreens.kt`, and wire `OverlayBlockerService` failure handoff**

1. Remove `BlockerActionState.Failed` and dead `errorMessage` / `onDismissError` / `onRetryAction` parameters + dead `ErrorAlertDialog` blocks from `StartBlockerScreen` and `DeadlineBlockerScreen` in `BlockerScreens.kt`.
2. Implement `BlockerHostController.kt` and refactor `StartBlockerActivity.kt` (adding `EXTRA_INITIAL_WARNING_MESSAGE`, `createIntent(...)`, and `onNewIntent`) and `DeadlineBlockerActivity.kt` (adding `onNewIntent`) to delegate to `BlockerHostController`.
3. Implement `resolveOverlayActionOutcome` and update `OverlayBlockerService.handleAction` (`OverlayBlockerService.kt`) so any non-`Success` result, `TimeoutCancellationException`, or unexpected `Throwable` launches `StartBlockerActivity.createIntent(..., initialWarningMessage = disposition.warningMessage)` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` before dismissing the native overlay.

- [ ] **Step 4: Run full client JVM unit tests, `assembleDebug`, and `compileDebugAndroidTestKotlin` (with full unfiltered `scripts/e2e-real-backend.sh` executed in whole-branch verification)**

Run:
1. `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client/gradlew -p /Users/svehla/playground/buzer-app/.worktrees/deepen-client/client :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL across shared JVM tests, Android JVM tests, debug APK assembly, and `androidTest` Kotlin compilation (followed by full unfiltered `scripts/e2e-real-backend.sh` in whole-branch verification).

- [ ] **Step 5: Commit Task 4**

```bash
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client add -A client
git -C /Users/svehla/playground/buzer-app/.worktrees/deepen-client commit -m "refactor(client): unify blocker host lifecycle and escalate overlay failures to StartBlockerActivity"
```
