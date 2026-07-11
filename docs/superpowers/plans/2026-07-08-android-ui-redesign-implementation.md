# Android UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved Android UI redesign as a reusable Material 3 component library, add optional Todoist-backed task details end to end, and migrate Home, Add Task, Morning Review, blockers, and overlay without duplicating form or blocker layouts.

**Architecture:** Build shared pure UI state and formatting in `client/shared`, reusable Android composables in `client/androidApp/src/main/kotlin/app/buzer/android/ui/components`, and a single `BuzerTheme` token layer. Add task details only by mirroring and passing through Todoist's existing `description` field before the UI migration so every screen can display optional task details consistently. Migrate screens in thin vertical slices, updating JVM and instrumentation tests in the same task that changes user-visible behavior.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform-compatible Kotlin, Android Jetpack Compose Material 3, Node.js/TypeScript backend, SQLite, Todoist API, fake backend, Gradle Managed Device instrumentation lane.

**Source Specs And Support Docs:**
- `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- `docs/superpowers/specs/2026-07-07-android-ui-redesign.md`
- `docs/design/android-ui-redesign-component-library.md`
- `docs/decisions/2026-07-08-task-description-and-extensible-details.md`

---

## Execution Model For Multiple Agents

This plan is intentionally sequenced. Later tasks assume earlier task commits are merged.

- Run one implementation agent per task unless the controller explicitly splits a task into non-overlapping substeps.
- Agents must read `AGENTS.md`, the two source specs, this plan, and the latest handoffs before editing.
- Every task must update its checkbox state before handoff.
- Every task must write or update a handoff file in `docs/handoffs/`.
- Commit after each task. Use small imperative messages.
- Do not run UI-affecting tasks in parallel against the same screen files.

Recommended ownership:

- Task 1 can run independently after this plan is accepted.
- Tasks 2 and 3 must complete before Tasks 4 through 9.
- Tasks 4, 6, and 8 all depend on Task 3 but should still land sequentially because they touch shared UI state.
- Task 10 must run after all screen migrations.
- Task 11 is final verification and documentation closure.

---

## Current Code Map

Existing UI roots:

- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerTheme.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/Spacing.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDurationField.kt`

Existing Android entry/activity roots:

- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`

Existing tests to migrate with changed behavior:

- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskCreateFlowTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskSubmitStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDateTimeFieldTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDurationFieldTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

Backend and shared model files touched by Todoist task-details support:

- `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- `backend/src/db/schema.sql`
- `backend/src/db/migrations.ts`
- `backend/src/repositories/taskRepository.ts`
- `backend/src/services/taskActionService.ts`
- `backend/src/services/taskSnapshotService.ts`
- `backend/src/services/todoistSyncService.ts`
- `backend/src/todoist/todoistAdapter.ts`
- `backend/src/todoist/todoistTypes.ts`
- `backend/src/todoist/httpTodoistAdapter.ts`
- `backend/src/http/routes.ts`
- `tools/fake-backend/src/fixtures.ts`
- `tools/fake-backend/src/server.ts`
- `tools/fake-backend/src/server.test.ts`

Target UI component files:

- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentFormatters.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentEditorModels.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/blockers/BlockerUiModels.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerColor.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerShape.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerTypography.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerScaffold.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/AttentionStrip.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerButtons.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerCards.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerFields.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/TaskDetailsBlock.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/CommitmentEditor.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BlockerSurface.kt`

---

## Task 1: Add Todoist Task Details Across The Stack

Product wording for this task is "task details." Implementation wording is `description` because Todoist's API and returned task objects use that field name. Do not add a Buzer-only task-details field or companion-owned details store; the backend stores this value only as part of the Todoist task mirror.

Description must flow through both the create path and the reschedule path, because the Commitment Editor keeps details editable in Repair and Reschedule modes.

**Files:**
- Modify: `backend/src/db/schema.sql`
- Modify: `backend/src/db/migrations.ts`
- Modify: `backend/src/repositories/taskRepository.ts`
- Modify: `backend/src/todoist/todoistTypes.ts`
- Modify: `backend/src/todoist/todoistAdapter.ts`
- Modify: `backend/src/todoist/httpTodoistAdapter.ts`
- Modify: `backend/src/services/taskActionService.ts`
- Modify: `backend/src/services/taskSnapshotService.ts`
- Modify: `backend/src/services/todoistSyncService.ts`
- Modify: `backend/src/http/routes.ts`
- Modify: backend tests under `backend/test/**`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Modify: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Modify: `tools/fake-backend/src/fixtures.ts`
- Modify: `tools/fake-backend/src/server.ts`
- Modify: `tools/fake-backend/src/server.test.ts`

- [x] **Step 1: Add backend failing tests for Todoist description mirroring**

Add these assertions to the existing create/snapshot tests rather than creating broad new test files:

```ts
// backend/test/services/taskActionService.test.ts
expect(todoist.createdTasks[0]?.description).toBe("Gate code: 2468");
expect(repo.getByTodoistTaskId("created-1")?.description).toBe("Gate code: 2468");

// reschedule path — the Todoist test double must record reschedule inputs
expect(todoist.rescheduledTasks[0]?.description).toBe("Updated gate code: 1357");
expect(repo.getByTodoistTaskId("rescheduled-1")?.description).toBe("Updated gate code: 1357");

// backend/test/services/taskSnapshotService.test.ts
expect(snapshot.tasks[0]?.description).toBe("Bring printed form");

// backend/test/db/migrations.test.ts
expect(columns("todoist_tasks")).toContain("description");
```

Run: `cd backend && npm test`

Expected before implementation: FAIL with missing `description` property or missing SQLite mirror column.

- [x] **Step 2: Add backend schema and repository support**

In `backend/src/db/schema.sql`, add `description text,` immediately after `title text not null,`. This column mirrors Todoist's field; it is not Buzer-owned enforcement metadata.

In `backend/src/db/migrations.ts`, add this guard after the existing `commitment_owner_id` guard:

```ts
  ensureColumn(db, "todoist_tasks", "description", "text");
```

In `backend/src/repositories/taskRepository.ts`, add `description: string | null;` to `TodoistTaskUpsert`, select `t.description`, insert/update `description`, and include it in `TaskWithCommitment`.

- [x] **Step 3: Add Todoist adapter description pass-through**

In `backend/src/todoist/todoistTypes.ts`, add:

```ts
  description: string | null;
```

to `TodoistTaskMirror`.

In `backend/src/todoist/todoistAdapter.ts`, add:

```ts
  description?: string | null;
```

to `TodoistCreateTaskInput`.

In `backend/src/todoist/httpTodoistAdapter.ts`, include non-blank `description` in the Todoist create payload and parse returned task descriptions:

```ts
if (input.description !== null && input.description !== undefined && input.description.trim().length > 0) {
  body.description = input.description.trim();
}
```

When mapping Todoist tasks, set:

```ts
description: task.description?.trim() ? task.description : null,
```

Add `description?: string | null;` to `TodoistRescheduleInput`. In `httpTodoistAdapter.rescheduleTask`, include `description` in the `item_update` args only when the input field is present: non-blank sends the trimmed value, blank or null sends `""` to clear, and an absent field omits the key so Todoist leaves the description unchanged.

- [x] **Step 4: Add service and HTTP route support**

In `backend/src/services/taskActionService.ts`, add `description: string | null;` to `CreateTaskInput`, pass normalized description into Todoist create, and upsert the returned description.

In `backend/src/http/routes.ts`, extend create request schema:

```ts
description: z.string().trim().min(1).nullable().optional()
```

Normalize omitted values to `null` before calling `TaskActionService.create`.

Extend the reschedule path in the same step: add an optional `description` to `RescheduleTaskInput` and to the reschedule body schema (`description: z.string().trim().nullable().optional()`), pass it through `TaskActionService.reschedule` into `todoist.rescheduleTask`, and update the SQLite mirror (extend `updateTaskSchedule` or add a dedicated repository update) so the mirrored description matches what was sent. Semantics: omitted field leaves the description unchanged; blank or null clears it; non-blank sets the trimmed value.

In `backend/src/services/taskSnapshotService.ts`, include `description` in task snapshot items so clients can render it as task details.

In `backend/src/services/todoistSyncService.ts`, pass `description: task.description` into repository upserts.

- [x] **Step 5: Add shared Kotlin DTO support**

In `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`, add:

```kotlin
val description: String? = null,
```

to `TaskSnapshotItem`, preferably after `title`.

Add to `CreateTaskRequest`:

```kotlin
val description: String? = null,
```

Add the same defaulted field to `RescheduleTaskRequest`.

Keep both defaulted so existing call sites compile while UI migration is in progress.

- [x] **Step 6: Add Android validator support**

In `AddTaskFormValidator.validate`, add a `description: String = ""` parameter and pass a normalized value to `CreateTaskRequest`. UI labels should say `Details`; the DTO field should remain `description` to match Todoist:

```kotlin
private fun String.normalizedOptionalText(): String? =
    trim().takeIf { it.isNotEmpty() }
```

Add test coverage:

```kotlin
@Test
fun trimsOptionalDescriptionIntoCreateRequest() {
    val result = AddTaskFormValidator.validate(
        title = "Buy milk",
        description = "  Gate code: 2468  ",
        selectedProject = assignableProject,
        selectedAssignee = me,
        deadline = deadline,
        durationMinutes = 45,
        zoneId = zone
    )

    assertTrue(result is AddTaskFormResult.Valid)
    assertEquals("Gate code: 2468", result.request.description)
}

@Test
fun omitsBlankOptionalDescription() {
    val result = AddTaskFormValidator.validate(
        title = "Buy milk",
        description = "   ",
        selectedProject = assignableProject,
        selectedAssignee = me,
        deadline = deadline,
        durationMinutes = 45,
        zoneId = zone
    )

    assertTrue(result is AddTaskFormResult.Valid)
    assertEquals(null, result.request.description)
}
```

- [x] **Step 7: Update fake backend fixtures and assertions**

In `tools/fake-backend/src/fixtures.ts`, add Todoist-style `description` to at least one active task, one deadline task, and one invalid task. Use concrete examples:

```ts
description: "Bring the printed form and check opening hours."
```

In `tools/fake-backend/src/server.test.ts`, assert that `POST /tasks` and `POST /tasks/:id/reschedule` record request `description` when supplied.

- [x] **Step 8: Verify Task 1**

Run:

```bash
cd backend
npm test
npm run typecheck
npm run build
```

Expected: PASS.

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: PASS.

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain
```

Expected: PASS.

- [x] **Step 9: Commit**

```bash
git add backend client tools/fake-backend
git commit -m "feat: add Todoist task details support"
```

---

## Task 2: Theme Tokens And Base Component Library

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerTheme.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/Spacing.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerColor.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerShape.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerTypography.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerScaffold.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/AttentionStrip.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerButtons.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerCards.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BuzerFields.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`

- [x] **Step 1: Expand theme tokens**

Replace the thin `BuzerTheme` wrapper with a single Material 3 theme entry point:

```kotlin
@Composable
fun BuzerTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BuzerDarkColorScheme
        else -> BuzerLightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = BuzerTypography,
        shapes = BuzerShapes,
        content = content
    )
}
```

Create `BuzerColor.kt` with dark and light color schemes. Use a soft blue-grey primary direction and Material 3 roles:

```kotlin
val BuzerDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB8C7FF),
    onPrimary = Color(0xFF20305F),
    primaryContainer = Color(0xFF394777),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFC4C6D8),
    onSecondary = Color(0xFF2D3042),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)
```

Add `BuzerLightColorScheme` with `lightColorScheme(...)` mirroring the same roles in the light direction. It does not need polish yet, but it must exist and stay legible; `BuzerTheme` selects it for light mode below Android 12. Material You dynamic color (API 31+) is resolved only inside `BuzerTheme` — minSdk is 26, so the static schemes remain the sub-31 fallback, and a future Compose Multiplatform port falls back to them as well because dynamic color is an Android platform API.

Create `BuzerShape.kt` with compact shape values:

```kotlin
val BuzerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)
```

Create `BuzerTypography.kt` by delegating to `Typography()` with no custom font dependency.

- [x] **Step 2: Expand spacing tokens**

Extend `Spacing.kt`:

```kotlin
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 16.dp
    val fabClearance = 160.dp
}
```

Remapping the existing token values is low-risk: production code currently references `Spacing` in exactly one place (`Spacing.sm` in `WheelDurationField.kt`, unchanged at 8.dp). Screens hardcode dp values today and adopt tokens during their migration tasks.

`fabClearance` must clear two stacked 56dp FABs plus their gap and bottom margin so the last agenda row can scroll fully above the stack; verify the value visually in Task 6 and adjust.

- [x] **Step 3: Add base components**

Create `BuzerScaffold.kt`:

```kotlin
@Composable
fun BuzerScaffold(
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen)
            .padding(bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content
    )
}
```

Create `AttentionStrip.kt` with:

```kotlin
data class AttentionStripModel(
    val message: String,
    val actionLabel: String? = null
)
```

and a composable that uses `Surface(color = MaterialTheme.colorScheme.errorContainer)` for warnings and an optional text button action.

Create `BuzerButtons.kt` with `PrimaryActionButton`, `SecondaryActionButton`, and `IconFabStack`. `IconFabStack` must require content descriptions for icon-only FABs.

Create `BuzerFields.kt` with a thin `BuzerSingleLineTextField` wrapper around `OutlinedTextField` to standardize single-line title fields.

- [x] **Step 4: Restyle status components with theme tokens**

Update `WarningStatus`, `BlockingLoadingState`, `BlockingErrorState`, `LoadingActionButton`, and `ErrorAlertDialog` to use `Spacing`, Material 3 text styles, and the new color scheme. Keep their public signatures stable to avoid widening this task.

- [x] **Step 5: Verify Task 2**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/theme client/androidApp/src/main/kotlin/app/buzer/android/ui/components client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt
git commit -m "feat: add Buzer UI theme primitives"
```

---

## Task 3: Shared Commitment Editor Models And Formatters

**Files:**
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentFormatters.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment/CommitmentEditorModels.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/ui/commitment/CommitmentFormattersTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`

- [x] **Step 1: Add shared formatter tests**

Create `CommitmentFormattersTest.kt`:

```kotlin
class CommitmentFormattersTest {
    @Test
    fun formatsBlockRangeForSameDay() {
        assertEquals("14:30-15:00", formatBlockRange(MinuteOfDay(14, 30), 30))
    }

    @Test
    fun wrapsBlockRangeAcrossMidnight() {
        assertEquals("23:50-00:20", formatBlockRange(MinuteOfDay(23, 50), 30))
    }

    @Test
    fun formatsSubmitLabelFromBlockRange() {
        assertEquals("Save 14:30-15:00", formatSaveLabel(MinuteOfDay(14, 30), 30))
    }

    @Test
    fun saveLabelFallsBackWhenStartMissing() {
        assertEquals("Save task", formatSaveLabel(null, 30))
    }

    @Test
    fun formatsDurationCompactly() {
        assertEquals("30 min", formatDurationLabel(30))
        assertEquals("1h 30m", formatDurationLabel(90))
    }
}
```

Run: `cd client && ./gradlew :shared:testAndroidHostTest --console=plain`

Expected before implementation: FAIL because the formatter file does not exist.

- [x] **Step 2: Implement shared formatters**

Create `CommitmentFormatters.kt` with:

```kotlin
data class MinuteOfDay(val hour: Int, val minute: Int)

private const val MINUTES_PER_DAY = 24 * 60

private fun formatTime(totalMinutes: Int): String {
    val wrapped = ((totalMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    val hour = (wrapped / 60).toString().padStart(2, '0')
    val minute = (wrapped % 60).toString().padStart(2, '0')
    return "$hour:$minute"
}

fun formatBlockRange(start: MinuteOfDay, durationMinutes: Int): String {
    val startMinutes = start.hour * 60 + start.minute
    return "${formatTime(startMinutes)}-${formatTime(startMinutes + durationMinutes)}"
}

fun formatSaveLabel(start: MinuteOfDay?, durationMinutes: Int): String =
    if (start == null) "Save task" else "Save ${formatBlockRange(start, durationMinutes)}"

fun formatDurationLabel(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0 -> "$remainder min"
        remainder == 0 -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}
```

kotlinx-datetime intentionally omits `LocalDateTime` arithmetic (its docs direct arithmetic through `Instant` plus a `TimeZone`), so these formatters use pure minute-of-day math with an explicit midnight wrap instead of any datetime library. Shared models may hold `kotlinx.datetime.LocalDateTime` as plain data, but never do arithmetic on it, and never import `java.time` in `commonMain`.

- [x] **Step 3: Add shared editor models**

Create `CommitmentEditorModels.kt`:

```kotlin
enum class CommitmentEditorMode {
    AddTask,
    Repair,
    Reschedule
}

data class CommitmentEditorDraft(
    val title: String,
    val description: String?,
    val projectName: String?,
    val assigneeLabel: String?,
    val start: LocalDateTime?,
    val durationMinutes: Int,
    val titleEditable: Boolean,
    val descriptionExpanded: Boolean
)

data class CommitmentEditorValidation(
    val bannerMessage: String?,
    val titleError: String?,
    val projectError: String?,
    val assigneeError: String?,
    val startError: String?,
    val durationError: String?
)
```

Keep this model UI-agnostic. It must not import Compose, Android, or `java.time`.

- [x] **Step 4: Update validator to return field-level validation**

Extend `AddTaskFormResult.Invalid` so it can carry a field key while preserving the current message-based behavior:

```kotlin
data class Invalid(
    val message: String,
    val field: AddTaskInvalidField? = null
) : AddTaskFormResult

enum class AddTaskInvalidField {
    Title,
    Project,
    Assignee,
    Start,
    Duration
}
```

Update existing tests to assert the field for missing assignee:

```kotlin
assertEquals(AddTaskInvalidField.Assignee, result.field)
```

- [x] **Step 5: Verify Task 3**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/ui/commitment client/shared/src/commonTest/kotlin/app/buzer/shared/ui/commitment client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask
git commit -m "feat: add commitment editor models"
```

---

## Task 4: Shared CommitmentEditor Composable

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/CommitmentEditor.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/TaskDetailsBlock.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDurationField.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/components/CommitmentEditorStateTest.kt`

- [x] **Step 1: Add pure Android adapter tests for editor labels**

Create `CommitmentEditorStateTest.kt` with tests for:

```kotlin
@Test
fun saveLabelUsesSelectedStartAndDuration() {
    val start = DateTimeSelection(LocalDate.of(2026, 7, 8), 14, 30)
    assertEquals("Save 14:30-15:00", commitmentSaveLabel(start, 30))
}

@Test
fun saveLabelFallsBackWhenStartMissing() {
    assertEquals("Save task", commitmentSaveLabel(null, 30))
}
```

`commitmentSaveLabel` is a thin adapter that maps `DateTimeSelection.hour`/`minute` to the shared `MinuteOfDay` and delegates to `formatSaveLabel`.

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`

Expected before implementation: FAIL.

- [x] **Step 2: Add `TaskDetailsBlock`**

Create a composable that accepts:

```kotlin
@Composable
fun TaskDetailsBlock(
    description: String?,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
)
```

Behavior:

- If `description` is null or blank, render nothing. The UI label should be `Details`; the model field may remain `description` because it is the Todoist API field.
- Otherwise render trimmed text in `MaterialTheme.typography.bodyMedium`.
- Clamp to three lines when collapsed.
- Show a small `TextButton` labeled `More` or `Less` when text is long enough to need expansion.

- [x] **Step 3: Add `CommitmentEditor`**

Create a composable with this shape:

```kotlin
@Composable
fun CommitmentEditor(
    mode: CommitmentEditorMode,
    title: String,
    onTitleChange: (String) -> Unit,
    titleEditable: Boolean,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionExpanded: Boolean,
    onDescriptionExpandedChange: (Boolean) -> Unit,
    projectContent: @Composable () -> Unit,
    assigneeContent: @Composable () -> Unit,
    start: DateTimeSelection?,
    onStartChange: (DateTimeSelection) -> Unit,
    durationMinutes: Int,
    onDurationChange: (Int) -> Unit,
    validation: CommitmentEditorValidation,
    submitting: Boolean,
    enabled: Boolean,
    submitLabel: String,
    loadingLabel: String,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
)
```

Use:

- `BuzerSingleLineTextField` for title when editable.
- A collapsed `OutlinedButton` or text button for task details when empty and collapsed.
- `OutlinedTextField` labeled `Details` when expanded.
- `WheelDateTimeField` and `WheelDurationField` as separate controls.
- A receipt section showing Project, Assignee, Start, Duration, and Ends.
- `LoadingActionButton` for the primary submit button.

- [x] **Step 4: Keep wheel fields accessible**

Update wheel labels to user-facing labels:

- Start field content description: `Start picker`
- Duration field content description: `Duration picker`

Do not include internal labels like `WHEELS`, `EDIT BLOCK`, or test-only wording in visible UI.

- [x] **Step 5: Verify Task 4**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/components client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers client/androidApp/src/test/kotlin/app/buzer/android/ui/components
git commit -m "feat: add shared commitment editor component"
```

---

## Task 5: Migrate Add Task To CommitmentEditor

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`

- [x] **Step 1: Add instrumentation expectations for final Add Task behavior**

Update `AddTaskFakeBackendTest.kt` to assert:

- The title field is a standard single-line Material text field. Locate it in tests by its visible label or a `Modifier.testTag("titleInput")`; do not set a `contentDescription` on an editable text field, because that overrides its TalkBack semantics.
- Optional task details can be opened, typed into, and submitted through Todoist's `description` field.
- Missing assignee shows `Assignee is required before saving`.
- Save button label changes to a block range after start and duration are selected, for example `Save 14:30-15:00`.
- The fake backend receives `description` when supplied.

Run: `scripts/e2e-fake-backend.sh`

Expected before implementation: FAIL on the new Add Task assertions. Running only the Add Task instrumentation class first (Gradle test filtering on the managed-device lane) is acceptable to confirm the failing assertions before paying for the full lane.

- [x] **Step 2: Replace inline Add Task layout with `CommitmentEditor`**

Keep the existing project loading, project menu, assignee options, submit dialog, and retry behavior. Remove only the duplicated field/wheel/receipt layout.

Add local state:

```kotlin
var description by remember { mutableStateOf("") }
var descriptionExpanded by remember { mutableStateOf(false) }
var invalidField by remember { mutableStateOf<AddTaskInvalidField?>(null) }
```

Call validator with `description = description`.

On validation failure:

```kotlin
errorText = if (result.field == AddTaskInvalidField.Assignee) {
    "Assignee is required before saving"
} else {
    result.message
}
invalidField = result.field
```

Pass field-level errors into `CommitmentEditorValidation`.

- [x] **Step 3: Keep Add Task compact**

Use simple header text `Add task`. Remove `Add committed task`.

Do not expand the task details field by default. It opens only when the user taps the details affordance or when existing Todoist details are injected in tests.

Project and assignee controls remain compact and live inside the shared editor slots.

- [x] **Step 4: Verify Task 5**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt
git commit -m "feat: migrate add task to commitment editor"
```

---

## Task 6: Home Timeline Agenda And Current Block Done

**Files:**
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home/HomeTimelineModels.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt` if Home navigation assertions live there
- Modify or create Home instrumentation coverage if a dedicated file exists after test inspection

- [x] **Step 1: Add Home state tests for agenda rows**

Extend `HomeScreenStateTest.kt` with tests for:

```kotlin
@Test
fun buildsDateFirstHeaderFromDeviceClock() { ... assertEquals("Wednesday", state.weekdayLabel) ... }

@Test
fun exposesOverdueDoneAndUpcomingAgendaRows() { ... }

@Test
fun exposesCurrentBlockDoneActionModel() { ... }

@Test
fun attentionStripPrefersInvalidTasksOverHealthWarnings() { ... }

@Test
fun showsEmptyPlaceholderWhenNoTasksToday() { ... }
```

Inject a fixed device clock of `2026-07-08T12:00:00Z` into the state builder — the builder takes `now` (or a clock) as a parameter and must not read the system clock internally. Use task fixtures with:

- Overdue task ended at 11:00.
- Current started task from 11:30 to 12:30.
- Upcoming task from 14:00 to 14:30.
- Completed task earlier in the day.

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`

Expected before implementation: FAIL.

- [x] **Step 2: Add shared Home timeline models**

Create `HomeTimelineModels.kt`:

```kotlin
enum class HomeAgendaRowState {
    Overdue,
    Current,
    Upcoming,
    Done
}

data class HomeAgendaRow(
    val taskId: String,
    val timeLabel: String,
    val title: String,
    val state: HomeAgendaRowState,
    val subline: String,
    val description: String?
)

data class HomeCurrentBlock(
    val taskId: String,
    val title: String,
    val blockWindow: String,
    val remainingLabel: String,
    val description: String?
)

data class HomeAttention(
    val message: String,
    val action: HomeAttentionAction
)

enum class HomeAttentionAction {
    OpenMorningReview,
    ShowHealthDetail,
    RetryRefresh
}
```

Keep time calculations pure and covered in tests.

- [x] **Step 3: Update Home state builder**

Replace count-first `HomeScreenState` with:

```kotlin
data class HomeScreenState(
    val weekdayLabel: String,
    val dateLabel: String,
    val contentState: HomeContentState,
    val statusMessage: String?,
    val attention: HomeAttention?,
    val currentBlock: HomeCurrentBlock?,
    val agendaRows: List<HomeAgendaRow>,
    val canOpenMorningReview: Boolean
)
```

Keep `HomeContentState.Loading`, `Ready`, and `Error`.

Derive `weekdayLabel`, `dateLabel`, and relative labels such as `Overdue - ended 59m ago` from the injected device clock, not from snapshot server time. When `agendaRows` is empty and there is no current block, the Ready state renders the placeholder `No tasks for today`.

No healthy freshness text should be emitted when `refreshInFlight == false` and `refreshError == null`.

- [x] **Step 4: Implement compact Home UI**

Use `BuzerScaffold(bottomClearance = Spacing.fabClearance)`.

Render:

- Weekday as the small header.
- Date as the headline.
- `AttentionStrip` only when `attention != null`.
- Current block hero when `currentBlock != null`, with primary `Done`.
- Timeline agenda list with rail/time/title/subline, not trailing chips.
- Vertical icon-only FAB stack for Add Task and Morning Review.

Add `onDoneCurrentTask: (String) -> Unit` to `HomeScreen` and wire it through `BuzerApp`.

`BuzerApp` currently has no action dependencies — it receives only snapshot state and refresh callbacks — so this task adds the wiring. `MainActivity` supplies `onDoneCurrentTask`, which invokes the same completion path the deadline blocker uses: `EnforcementCoordinator.completeTask(taskId)` obtained via `EnforcementDependencies.coordinator(context)`, followed by a snapshot refresh. The coordinator already owns Todoist completion, local state update, and enforcement reconciliation; do not add a separate fire-and-forget HTTP call from the composable layer.

- [x] **Step 5: Verify Task 6**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS. If the lane has no Home current-block Done assertion, add one in this task before accepting the pass.

- [x] **Step 6: Commit**

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/ui/home client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt client/androidApp/src/androidTest
git commit -m "feat: redesign home agenda"
```

---

## Task 7: Shared BlockerSurface And Full-Screen Blockers

**Files:**
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/ui/blockers/BlockerUiModels.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BlockerSurface.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers/BlockerActionStateTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Add blocker UI model tests**

Create tests that verify:

- Start blocker eyebrow is `Start now`.
- Deadline blocker eyebrow is `Block ended`.
- Block window includes start/end and duration.
- Optional Todoist task details are present in `BlockerUiModel`.
- Production blocker mode does not expose back/dismiss action.

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`

Expected before implementation: FAIL.

- [x] **Step 2: Create shared blocker models**

Create `BlockerUiModels.kt`:

```kotlin
enum class BlockerKind {
    Start,
    Deadline,
    OverlayStart
}

data class BlockerUiModel(
    val kind: BlockerKind,
    val eyebrow: String,
    val title: String,
    val blockWindow: String,
    val description: String?,
    val consequenceText: String?
)
```

- [x] **Step 3: Create `BlockerSurface`**

`BlockerSurface` accepts `BlockerUiModel`, optional warning, action state, and an action-panel slot:

```kotlin
@Composable
fun BlockerSurface(
    model: BlockerUiModel,
    warningMessage: String?,
    checking: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit
)
```

Use `TaskDetailsBlock` for description.

- [x] **Step 4: Migrate Start blocker**

In `StartBlockerScreen`, replace its screen shell with `BlockerSurface`.

Actions remain:

- Primary `I'm starting`.
- Secondary `Postpone 5 min`.
- Consequence copy `Second postpone escalates via WhatsApp.`

Keep `showBack` only for debug/dev contexts. Production full-screen blocker should pass `showBack = false`.

- [x] **Step 5: Migrate Deadline blocker and reschedule entry**

In `DeadlineBlockerScreen`, use `BlockerSurface` for action mode. Actions remain:

- `Done`
- `Extend 30m`
- `Reschedule`

When `Reschedule` is tapped, route to `CommitmentEditor` in `CommitmentEditorMode.Reschedule` instead of the inline wheel layout. Title editing remains disabled unless the caller later supplies editability. Task details are editable in reschedule mode, prefilled from Todoist `description`, and submitted through `RescheduleTaskRequest.description`.

- [x] **Step 6: Update instrumentation tests**

Update `BlockerActivityFakeBackendTest.kt` to assert:

- Start blocker shows task title, block window, and task-details fixture.
- Deadline blocker shows shared layout and action labels.
- Reschedule shows separate Start and Duration pickers and computed save label.
- Action failure still shows retry dialog.

- [x] **Step 7: Verify Task 7**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/ui/blockers client/androidApp/src/main/kotlin/app/buzer/android/ui/components/BlockerSurface.kt client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/blockers client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers
git commit -m "feat: unify blocker surfaces"
```

---

## Task 8: Morning Review Cards And Repair Reuse

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Add Morning Review state tests**

Tests must cover:

- Header text `3 tasks need attention`.
- Progress appears in header only.
- No card contains text matching `left after this task`.
- Invalid task card shows title, plain-language problem, and optional task details.
- Repair opens `CommitmentEditorMode.Repair`.
- All-clear state shows `All commitments are valid` and a single `Done` action.

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`

Expected before implementation: FAIL.

- [x] **Step 2: Replace list/repair duplication**

Keep `MorningReviewContentState` but change rendered ready content:

- Header card/section with count and optional warning.
- Repeated compact repair cards.
- `TaskDetailsBlock` inside each card when Todoist task details exist.
- `Repair` primary action.
- Quiet `Snooze` action.

When a task is being repaired, render `CommitmentEditor` with:

- `mode = CommitmentEditorMode.Repair`
- title editing disabled
- task details editable, prefilled from Todoist `description`, and submitted through the reschedule request
- project/assignee slots as read-only receipt content when available
- Start and Duration wheels active
- submit label from block range

- [x] **Step 3: Keep action failure behavior**

Do not hide action errors inline. Continue to use `ErrorAlertDialog` with retry for repair and snooze failures.

- [x] **Step 4: Verify Task 8**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/MorningReviewScreenStateTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt
git commit -m "feat: redesign morning review cards"
```

---

## Task 9: Compact Overlay Blocker Reusing BlockerSurface

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/BlockerOverlayController.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayComposeHost.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequestTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt` or create overlay instrumentation if more appropriate

- [x] **Step 1: Add overlay model tests**

Update `OverlayBlockerRequestTest.kt` so overlay requests carry:

- task title
- block window
- Todoist task details
- blocker kind

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`

Expected before implementation: FAIL.

- [x] **Step 2: Add a Compose host for the service overlay window**

`BlockerOverlayController` currently builds classic `TextView`/`Button` views added via `WindowManager.addView`. A `ComposeView` cannot render in that window as-is: it requires ViewTree owners that Activities normally provide. Create `OverlayComposeHost`, a small helper that wraps `ComposeView` and attaches its own owners:

- Implement `LifecycleOwner` and `SavedStateRegistryOwner`, attached with `setViewTreeLifecycleOwner` and `setViewTreeSavedStateRegistryOwner` before the view is added to the window.
- Drive the lifecycle to `RESUMED` when the view is attached and `DESTROYED` when it is removed, and dispose the composition on removal.

If this proves unstable on the target device (rendering, input, or lifecycle crashes), stop and report instead of layering workarounds; the approved fallback is restyling the classic views with theme tokens.

- [x] **Step 3: Render compact `BlockerSurface` in overlay**

Replace the `TextView`/`Button` layout in `BlockerOverlayController` with `OverlayComposeHost` rendering compact `BlockerSurface` wrapped in `BuzerTheme`. Use the same `BlockerUiModel` creation path as Start blocker.

Render it as a compact bottom sheet over the existing overlay scrim. Keep actions:

- `I'm starting`
- `Postpone 5 min`

The overlay remains lower priority than full-screen blockers. Do not add new product rules.

- [x] **Step 4: Verify Task 9**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay client/androidApp/src/androidTest
git commit -m "feat: reuse blocker surface in overlay"
```

---

## Task 10: Loading, Error, Health, And Accessibility Polish

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/components/AttentionStrip.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Modify relevant JVM and instrumentation tests touched by visible state behavior

- [x] **Step 1: Add stale/error behavior tests**

Ensure tests cover:

- No cached data plus refresh error shows blocking error with retry.
- Cached data plus refresh error keeps content visible and shows compact warning.
- Healthy Home state has no `now`, `last updated`, or permanent freshness text.
- Failed Add Task save shows dialog with retry and no duplicate inline system error.
- Icon-only FABs have content descriptions.

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`

Expected before implementation: FAIL if any behavior is missing.

- [x] **Step 2: Normalize status components**

Use `AttentionStrip` for compact warnings and `ErrorAlertDialog` for failed user actions. Avoid adding screen-specific warning rows unless the message is unique to that screen.

- [x] **Step 3: Accessibility pass**

Add or verify content descriptions:

- Add Task FAB: `Add task`
- Morning Review FAB: `Morning review`
- Start picker: `Start picker`
- Duration picker: `Duration picker`
- Current block Done: `Finish current task`

Verify long text cannot overlap buttons. Clamp or expand task title/details intentionally.

- [x] **Step 4: Verify Task 10**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui client/androidApp/src/test/kotlin/app/buzer/android/ui client/androidApp/src/androidTest
git commit -m "fix: polish redesigned UI states"
```

---

## Task 11: Final Refactor, Dead Code Removal, And Full Verification

**Files:**
- Inspect and modify only if dead after migrations:
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/reschedule/RescheduleInputParser.kt`
  - obsolete helper methods in `AddTaskScreen.kt`, `BlockerScreens.kt`, and `MorningReviewScreen.kt`
  - unused imports across changed UI files
- Update: `docs/design/android-ui-redesign-component-library.md` if the final component names differ.
- Create or update: `docs/handoffs/2026-07-08-android-ui-redesign-implementation.md`

- [ ] **Step 1: Search for duplicated or obsolete UI fragments**

Run:

```bash
rg -n "Save task|Save reschedule|Save repair|Add committed task|Deadline blocker|Start blocker|No invalid tasks|left after this task|last updated" client/androidApp/src/main/kotlin/app/buzer/android
rg -n '"[^"]*\bnow\b[^"]*"' client/androidApp/src/main/kotlin/app/buzer/android
```

The second command restricts the `now` check to string literals so `now()` calls and comments do not flood the output.

Expected: Only intentional labels remain. `Start blocker` and `Deadline blocker` may remain in debug launcher names, but production visible strings should use approved copy.

- [ ] **Step 2: Search for unused old reschedule parser**

Run:

```bash
rg -n "RescheduleInputParser|parseDuration|durationText" client/androidApp/src
```

Expected: If `RescheduleInputParser` has no real callers, delete it and its test in the same commit.

- [ ] **Step 3: Run full verification**

Run:

```bash
cd backend
npm test
npm run typecheck
npm run build
```

Expected: PASS.

Run:

```bash
cd tools/fake-backend
npm test
```

Expected: PASS.

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain
```

Expected: PASS.

Run:

```bash
scripts/e2e-fake-backend.sh
```

Expected: PASS with all managed-device instrumentation tests passing.

- [ ] **Step 4: Manual screenshot and device sanity check**

Use the deterministic fake backend and capture or visually inspect:

- Home with current block, overdue row, done row, and FAB stack.
- Add Task with collapsed details, expanded details, missing-assignee validation, and computed save label.
- Start blocker with Todoist task details.
- Deadline blocker with reschedule editor.
- Morning Review with invalid task cards and repair editor.
- Overlay compact blocker if overlay permission is enabled on the test device.

Document exact evidence in the handoff.

- [ ] **Step 5: Final handoff**

Create or update `docs/handoffs/2026-07-08-android-ui-redesign-implementation.md` with:

```text
Plan: docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md
Completed tasks:
Files changed:
Verification:
- Command:
- Result:
- Evidence:
Manual verification:
Known failures or skipped checks:
Assumptions:
Next recommended step:
```

- [ ] **Step 6: Commit**

```bash
git add client backend tools/fake-backend docs/design/android-ui-redesign-component-library.md docs/handoffs/2026-07-08-android-ui-redesign-implementation.md
git commit -m "chore: finish android ui redesign"
```

---

## Deferred Work

Widget redesign remains deferred. When revived, it should mirror the Home current/next block language and reuse shared state models where possible. It should not reintroduce a separate scheduling form.

Dedicated health dashboard remains intentionally out of scope for the personal MVP. Health detail can stay in logs plus compact UI warnings.

macOS or web targets are not created by this roadmap. The roadmap prepares for them through shared pure UI models and low-platform-dependency composables.
