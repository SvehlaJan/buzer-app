# Android VPS-Connected Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the Android app to the VPS backend so it can fetch snapshots, persist them locally, reconcile exact alarms, execute blocker actions, and report backend failures.

**Architecture:** Pure snapshot-to-alarm planning lives in `shared`; Android owns file persistence, runtime config, notifications, and `AlarmManager` integration. The Android coordinator is the seam between backend API, snapshot storage, alarm planning, and blocker activities.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Ktor client, kotlinx.serialization, Android AlarmManager, Android notifications, Gradle Kotlin DSL, Android unit tests.

---

## Prerequisites

- Product spec: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Slice design: `docs/superpowers/specs/2026-05-28-android-vps-connected-enforcement-design.md`
- Foundation handoff: `docs/handoffs/2026-05-28-android-native-enforcement-foundation.md`
- Android docs note: `docs/notes/2026-05-28-android-permission-findings.md`

Every worker must read `AGENTS.md`, the product spec, the slice design, this plan, and the latest Android handoff before editing.

## File Map

- Shared alarm planning:
  - Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`
  - Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`
- Android debug config:
  - Modify: `client/androidApp/build.gradle.kts`
  - Modify: `client/gradle/libs.versions.toml`
  - Modify: `docs/notes/local-development.md`
  - Modify: `.gitignore` only if `client/local.properties` is not already ignored.
- Android snapshot persistence:
  - Create: `client/androidApp/src/main/kotlin/app/buzer/android/sync/SnapshotStore.kt`
  - Create: `client/androidApp/src/test/kotlin/app/buzer/android/sync/SnapshotStoreTest.kt`
- Android error reporting:
  - Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerAction.kt`
  - Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`
  - Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt`
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt`
- Android coordinator:
  - Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidEnforcementMapper.kt`
  - Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
  - Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
- Runtime wiring:
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt`
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
  - Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Verification docs:
  - Create: `docs/handoffs/2026-05-28-android-vps-connected-enforcement.md`
  - Modify: `docs/superpowers/plans/2026-05-28-android-vps-connected-enforcement.md`
  - Modify existing manual-test notes when device verification is performed.

## Task 1: Debug Backend Configuration

**Owner:** Android build/config worker.

**Goal:** Inject debug backend URL/token from ignored `client/local.properties` into `BuildConfig`.

- [x] **Step 1: Verify current ignore behavior**

Run:

```bash
git check-ignore client/local.properties
```

Expected: `client/local.properties` is printed. If it is not printed, add `client/local.properties` to `.gitignore` and commit that small fix before continuing.

- [x] **Step 2: Add lifecycle runtime dependency alias**

Modify `client/gradle/libs.versions.toml`.

Add this version entry under `[versions]`:

```toml
lifecycleRuntimeKtx = "2.8.7"
```

Add this library entry under `[libraries]`:

```toml
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
```

- [x] **Step 3: Modify Android Gradle build config**

Modify `client/androidApp/build.gradle.kts`:

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String {
    return localProperties.getProperty(name).orEmpty()
}

android {
    namespace = "app.buzer.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.buzer.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"

        buildConfigField("String", "BUZER_BACKEND_BASE_URL", "\"${localProperty("BUZER_BACKEND_BASE_URL")}\"")
        buildConfigField("String", "BUZER_BACKEND_TOKEN", "\"${localProperty("BUZER_BACKEND_TOKEN")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

Add the lifecycle dependency to the existing dependencies block:

```kotlin
implementation(libs.androidx.lifecycle.runtime.ktx)
```

- [x] **Step 4: Document local config keys**

Modify `docs/notes/local-development.md` and add:

````markdown
## Android Debug Backend Configuration

The Android debug app reads VPS backend settings from ignored `client/local.properties`:

```properties
BUZER_BACKEND_BASE_URL=https://your-vps.example
BUZER_BACKEND_TOKEN=your-dev-token
```

Do not commit real values. If either value is blank, the Android app shows a local configuration error instead of calling the backend.
````

- [x] **Step 5: Build**

Run:

```bash
cd client
./gradlew :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit**

```bash
git add client/androidApp/build.gradle.kts client/gradle/libs.versions.toml docs/notes/local-development.md .gitignore
git commit -m "chore: add Android debug backend config"
```

If `.gitignore` was unchanged, omit it from `git add`.

## Task 2: Shared Alarm Plan Calculator

**Owner:** Shared KMP worker.

**Goal:** Add Android-free alarm planning from backend snapshots.

- [x] **Step 1: Write failing tests**

Create `client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt`:

```kotlin
package app.buzer.shared.enforcement

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlarmPlanCalculatorTest {
    @Test
    fun schedulesFutureAlarmsForValidActiveTask() {
        val snapshot = snapshot(
            task(
                id = "task-1",
                dueDatetime = "2026-05-28T12:00:00Z",
                durationMinutes = 60,
                startAt = "2026-05-28T11:00:00Z"
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertEquals(
            listOf(
                DesiredAlarm("task-1", DesiredAlarmKind.PRE_START_WARNING, "2026-05-28T10:00:00Z"),
                DesiredAlarm("task-1", DesiredAlarmKind.START_BLOCKER, "2026-05-28T11:00:00Z"),
                DesiredAlarm("task-1", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T12:00:00Z")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun cancelsCompletedAndDeletedTasks() {
        val snapshot = snapshot(
            task(id = "done", state = CommitmentState.completed, isCompleted = true),
            task(id = "deleted", state = CommitmentState.deleted, isDeleted = true)
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertTrue(plan.alarms.isEmpty())
        assertEquals(setOf("done", "deleted"), plan.cancelTaskIds)
    }

    @Test
    fun skipsInvalidTasksForPerTaskAlarms() {
        val snapshot = snapshot(
            task(
                id = "invalid",
                state = CommitmentState.invalid,
                dueDatetime = null,
                durationMinutes = null,
                startAt = null,
                validationErrors = listOf("exact_due_datetime_required")
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertTrue(plan.alarms.isEmpty())
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    private fun snapshot(vararg tasks: TaskSnapshotItem): TasksSnapshotResponse =
        TasksSnapshotResponse(
            scheduleVersion = 1,
            serverTime = "2026-05-28T09:00:00Z",
            tasks = tasks.toList(),
            health = emptyList<HealthStatus>()
        )

    private fun task(
        id: String,
        state: CommitmentState = CommitmentState.planned,
        dueDatetime: String? = "2026-05-28T12:00:00Z",
        durationMinutes: Int? = 60,
        startAt: String? = "2026-05-28T11:00:00Z",
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
        validationErrors: List<String> = emptyList()
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = "Task $id",
            projectId = null,
            assigneeId = "user-self",
            creatorId = "user-self",
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            startAt = startAt,
            state = state,
            isAssignedToUser = true,
            isCreatedByUser = true,
            isCompleted = isCompleted,
            isDeleted = isDeleted,
            validationErrors = validationErrors,
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-28T09:00:00Z"
        )
}
```

- [x] **Step 2: Run tests to verify failure**

```bash
cd client
./gradlew :shared:testDebugUnitTest --tests app.buzer.shared.enforcement.AlarmPlanCalculatorTest
```

Expected: FAIL with unresolved `AlarmPlanCalculator`, `DesiredAlarm`, or `DesiredAlarmKind`.

- [x] **Step 3: Implement alarm planning**

Create `client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt`:

```kotlin
package app.buzer.shared.enforcement

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

enum class DesiredAlarmKind {
    PRE_START_WARNING,
    START_BLOCKER,
    DEADLINE_BLOCKER
}

data class DesiredAlarm(
    val taskId: String,
    val kind: DesiredAlarmKind,
    val triggerAtIso: String
)

data class AlarmPlan(
    val alarms: List<DesiredAlarm>,
    val cancelTaskIds: Set<String>
)

object AlarmPlanCalculator {
    fun calculate(snapshot: TasksSnapshotResponse, nowIso: String): AlarmPlan {
        val now = Instant.parse(nowIso)
        val alarms = mutableListOf<DesiredAlarm>()
        val cancelTaskIds = mutableSetOf<String>()

        for (task in snapshot.tasks) {
            if (task.isCompleted || task.isDeleted || task.state == CommitmentState.completed || task.state == CommitmentState.deleted) {
                cancelTaskIds += task.id
                continue
            }
            if (!task.isAssignedToUser || task.validationErrors.isNotEmpty()) {
                continue
            }
            alarms += desiredAlarmsFor(task, now)
        }

        return AlarmPlan(
            alarms = alarms.sortedWith(compareBy<DesiredAlarm> { Instant.parse(it.triggerAtIso) }.thenBy { it.taskId }.thenBy { it.kind.name }),
            cancelTaskIds = cancelTaskIds
        )
    }

    private fun desiredAlarmsFor(task: TaskSnapshotItem, now: Instant): List<DesiredAlarm> {
        val startAt = task.startAt?.let(Instant::parse)
        val dueAt = task.dueDatetime?.let(Instant::parse)
        val alarms = mutableListOf<DesiredAlarm>()

        if (startAt != null) {
            val preStartAt = startAt.minus(1.hours)
            if (preStartAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.PRE_START_WARNING, preStartAt.toString())
            }
            if (startAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, startAt.toString())
            }
        }
        if (dueAt != null && dueAt > now) {
            alarms += DesiredAlarm(task.id, DesiredAlarmKind.DEADLINE_BLOCKER, dueAt.toString())
        }

        return alarms
    }
}
```

- [x] **Step 4: Run tests to verify pass**

```bash
cd client
./gradlew :shared:testDebugUnitTest --tests app.buzer.shared.enforcement.AlarmPlanCalculatorTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit**

```bash
git add client/shared/src/commonMain/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculator.kt client/shared/src/commonTest/kotlin/app/buzer/shared/enforcement/AlarmPlanCalculatorTest.kt
git commit -m "feat: add shared alarm planning"
```

## Task 3: Snapshot File Store

**Owner:** Android persistence worker.

**Goal:** Persist the latest backend snapshot as app-private JSON.

- [x] **Step 1: Write failing tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/sync/SnapshotStoreTest.kt`:

```kotlin
package app.buzer.android.sync

import androidx.test.core.app.ApplicationProvider
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotStoreTest {
    @Test
    fun returnsNullWhenSnapshotFileIsMissing() {
        val store = SnapshotStore(ApplicationProvider.getApplicationContext(), fileName = "missing_snapshot.json")

        assertNull(store.load())
    }

    @Test
    fun roundTripsSnapshotJson() {
        val store = SnapshotStore(ApplicationProvider.getApplicationContext(), fileName = "round_trip_snapshot.json")
        val snapshot = TasksSnapshotResponse(
            scheduleVersion = 7,
            serverTime = "2026-05-28T09:00:00Z",
            tasks = emptyList(),
            health = emptyList<HealthStatus>()
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun corruptedSnapshotIsRecoverable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fileName = "corrupted_snapshot.json"
        context.filesDir.resolve(fileName).writeText("{not-json")
        val store = SnapshotStore(context, fileName = fileName)

        val result = runCatching { store.load() }

        assertTrue(result.isFailure)
        store.save(TasksSnapshotResponse(1, "2026-05-28T09:00:00Z", emptyList(), emptyList()))
        assertEquals(1, store.load()?.scheduleVersion)
    }
}
```

- [x] **Step 2: Run tests to verify failure**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.sync.SnapshotStoreTest
```

Expected: FAIL with unresolved `SnapshotStore`.

- [x] **Step 3: Implement snapshot store**

Create `client/androidApp/src/main/kotlin/app/buzer/android/sync/SnapshotStore.kt`:

```kotlin
package app.buzer.android.sync

import android.content.Context
import app.buzer.shared.model.TasksSnapshotResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SnapshotStorage {
    fun save(snapshot: TasksSnapshotResponse)
    fun load(): TasksSnapshotResponse?
}

class SnapshotStore(
    context: Context,
    private val fileName: String = "latest_snapshot.json",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
) : SnapshotStorage {
    private val file = context.filesDir.resolve(fileName)

    override fun save(snapshot: TasksSnapshotResponse) {
        file.writeText(json.encodeToString(snapshot))
    }

    override fun load(): TasksSnapshotResponse? {
        if (!file.exists()) return null
        return json.decodeFromString<TasksSnapshotResponse>(file.readText())
    }
}
```

- [x] **Step 4: Run tests to verify pass**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.sync.SnapshotStoreTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/sync/SnapshotStore.kt client/androidApp/src/test/kotlin/app/buzer/android/sync/SnapshotStoreTest.kt
git commit -m "feat: persist Android task snapshots"
```

## Task 4: Blocker Error Reporter

**Owner:** Android error-reporting worker.

**Goal:** Report failed blocker backend actions through a system notification and local error log.

- [x] **Step 1: Write failing tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt`:

```kotlin
package app.buzer.android.enforcement

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertTrue

class BlockerErrorReporterTest {
    @Test
    fun writesFailureToLocalLog() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fileName = "blocker_errors_test.log"
        context.filesDir.resolve(fileName).delete()
        val reporter = BlockerErrorReporter(context, fileName = fileName)

        reporter.report(
            BlockerAction.POSTPONE_START,
            taskId = "task-1",
            message = "backend unavailable",
            showNotification = false
        )

        val text = context.filesDir.resolve(fileName).readText()
        assertTrue(text.contains("POSTPONE_START"))
        assertTrue(text.contains("task-1"))
        assertTrue(text.contains("backend unavailable"))
    }
}
```

- [x] **Step 2: Run test to verify failure**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.enforcement.BlockerErrorReporterTest
```

Expected: FAIL with unresolved `BlockerErrorReporter` or `BlockerAction`.

- [x] **Step 3: Add error channel and reporter**

Modify `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt`:

```kotlin
object NotificationChannels {
    const val PRE_START = "pre_start"
    const val BLOCKERS = "blockers"
    const val ERRORS = "errors"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(PRE_START, "Pre-start reminders", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(BLOCKERS, "Commitment blockers", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(ERRORS, "Backend action failures", NotificationManager.IMPORTANCE_HIGH))
    }
}
```

Create `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerAction.kt`:

```kotlin
package app.buzer.android.enforcement

enum class BlockerAction {
    START_TASK,
    POSTPONE_START,
    COMPLETE_TASK,
    EXTEND_TASK,
    RESCHEDULE_TASK,
    MORNING_REVIEW_DISMISS,
    REFRESH_SNAPSHOT
}
```

Create `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt`:

```kotlin
package app.buzer.android.enforcement

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.time.Instant

interface BlockerErrorReporting {
    fun report(
        action: BlockerAction,
        taskId: String?,
        message: String,
        showNotification: Boolean = true
    )
}

class BlockerErrorReporter(
    private val context: Context,
    private val fileName: String = "blocker_action_errors.log"
) : BlockerErrorReporting {
    private val file = context.filesDir.resolve(fileName)

    override fun report(
        action: BlockerAction,
        taskId: String?,
        message: String,
        showNotification: Boolean = true
    ) {
        val timestamp = Instant.now().toString()
        file.appendText("$timestamp action=${action.name} taskId=${taskId.orEmpty()} message=${message.replace('\n', ' ')}\n")
        if (showNotification) {
            showNotification(action, message)
        }
    }

    private fun showNotification(action: BlockerAction, message: String) {
        NotificationChannels.ensureCreated(context)
        val notification = Notification.Builder(context, NotificationChannels.ERRORS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Buzer backend action failed")
            .setContentText("Check VPS/backend health. ${action.name}: $message")
            .setStyle(Notification.BigTextStyle().bigText("Check VPS/backend health. ${action.name}: $message"))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(ERROR_NOTIFICATION_ID, notification)
    }

    private companion object {
        const val ERROR_NOTIFICATION_ID = 9417
    }
}
```

- [x] **Step 4: Run test to verify pass**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.enforcement.BlockerErrorReporterTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerAction.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BlockerErrorReporter.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BlockerErrorReporterTest.kt
git commit -m "feat: report blocker action failures"
```

## Task 5: Enforcement Coordinator And Alarm Mapping

**Owner:** Android coordinator worker.

**Goal:** Fetch snapshots, persist them, reconcile alarms, and define backend action failure behavior.

- [x] **Step 1: Write coordinator tests**

Create `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt` with fakes instead of Android `AlarmManager`:

```kotlin
package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnforcementCoordinatorTest {
    @Test
    fun schedulesAlarmsAfterSuccessfulRefresh() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val store = FakeSnapshotStore()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1"))),
            snapshotStore = store,
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        coordinator.refreshAndReconcile()

        assertEquals(3, scheduler.scheduled.size)
        assertEquals(1, store.saved?.scheduleVersion)
    }

    @Test
    fun cancelsCompletedTaskAlarmsAfterRefresh() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("done", state = CommitmentState.completed, isCompleted = true))),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        coordinator.refreshAndReconcile()

        assertEquals(listOf("done"), scheduler.cancelledAll)
    }

    @Test
    fun postponeFailureSchedulesFiveMinuteRetryAndReportsError() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1")), failPostpone = true),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.postponeStart("task-1")

        assertTrue(result is CoordinatorActionResult.FailedAndHidden)
        assertEquals(AlarmKind.START_BLOCKER, scheduler.scheduled.single().kind)
        assertEquals("2026-05-28T09:35:00Z", java.time.Instant.ofEpochMilli(scheduler.scheduled.single().triggerAtEpochMillis).toString())
        assertEquals(BlockerAction.POSTPONE_START, reporter.errors.single().action)
    }

    @Test
    fun deadlineFailureReportsErrorWithoutRetry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1")), failComplete = true),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.completeTask("task-1")

        assertTrue(result is CoordinatorActionResult.FailedAndHidden)
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(BlockerAction.COMPLETE_TASK, reporter.errors.single().action)
    }

    private fun snapshot(vararg tasks: TaskSnapshotItem) = TasksSnapshotResponse(1, "2026-05-28T09:00:00Z", tasks.toList(), emptyList<HealthStatus>())
    private fun task(id: String, state: CommitmentState = CommitmentState.planned, isCompleted: Boolean = false) = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = "Task $id",
        projectId = null,
        assigneeId = "user-self",
        creatorId = "user-self",
        dueDatetime = "2026-05-28T12:00:00Z",
        durationMinutes = 60,
        startAt = "2026-05-28T11:00:00Z",
        state = state,
        isAssignedToUser = true,
        isCreatedByUser = true,
        isCompleted = isCompleted,
        isDeleted = false,
        validationErrors = emptyList(),
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-05-28T09:00:00Z"
    )

    private class FakeBuzerApi(
        private val snapshot: TasksSnapshotResponse,
        private val failPostpone: Boolean = false,
        private val failComplete: Boolean = false
    ) : EnforcementApi {
        override suspend fun getSnapshot(): TasksSnapshotResponse = snapshot
        override suspend fun startTask(id: String) = Unit
        override suspend fun postponeStart(id: String) {
            if (failPostpone) error("backend unavailable")
        }
        override suspend fun completeTask(id: String) {
            if (failComplete) error("backend unavailable")
        }
        override suspend fun extendTask(id: String) = Unit
        override suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest) = Unit
    }

    private class FakeSnapshotStore : SnapshotStorage {
        var saved: TasksSnapshotResponse? = null
        override fun save(snapshot: TasksSnapshotResponse) {
            saved = snapshot
        }
        override fun load(): TasksSnapshotResponse? = saved
    }

    private class RecordingAlarmScheduler : AlarmScheduler {
        val scheduled = mutableListOf<ScheduledAlarm>()
        val cancelledAll = mutableListOf<String>()
        override fun schedule(alarm: ScheduledAlarm) {
            scheduled += alarm
        }
        override fun cancel(taskId: String, kind: AlarmKind) = Unit
        override fun cancelAllForTask(taskId: String) {
            cancelledAll += taskId
        }
    }

    private data class ErrorRecord(
        val action: BlockerAction,
        val taskId: String?,
        val message: String
    )

    private class RecordingErrorReporter : BlockerErrorReporting {
        val errors = mutableListOf<ErrorRecord>()
        override fun report(action: BlockerAction, taskId: String?, message: String, showNotification: Boolean) {
            errors += ErrorRecord(action, taskId, message)
        }
    }
}
```

The fake classes are intentionally local to this test file so production code only needs the small interfaces in Step 3.

- [x] **Step 2: Run test to verify failure**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.enforcement.EnforcementCoordinatorTest
```

Expected: FAIL with unresolved `EnforcementCoordinator`.

- [x] **Step 3: Introduce API abstraction and mapper**

Create `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidEnforcementMapper.kt`:

```kotlin
package app.buzer.android.enforcement

import app.buzer.shared.enforcement.DesiredAlarm
import app.buzer.shared.enforcement.DesiredAlarmKind
import java.time.Instant

object AndroidEnforcementMapper {
    fun toScheduledAlarm(alarm: DesiredAlarm): ScheduledAlarm =
        ScheduledAlarm(
            taskId = alarm.taskId,
            kind = toAlarmKind(alarm.kind),
            triggerAtEpochMillis = Instant.parse(alarm.triggerAtIso).toEpochMilli()
        )

    fun toAlarmKind(kind: DesiredAlarmKind): AlarmKind =
        when (kind) {
            DesiredAlarmKind.PRE_START_WARNING -> AlarmKind.PRE_START_WARNING
            DesiredAlarmKind.START_BLOCKER -> AlarmKind.START_BLOCKER
            DesiredAlarmKind.DEADLINE_BLOCKER -> AlarmKind.DEADLINE_BLOCKER
        }
}
```

Create `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt`:

```kotlin
package app.buzer.android.enforcement

import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse

interface EnforcementApi {
    suspend fun getSnapshot(): TasksSnapshotResponse
    suspend fun startTask(id: String)
    suspend fun postponeStart(id: String)
    suspend fun completeTask(id: String)
    suspend fun extendTask(id: String)
    suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest)
}

class BuzerEnforcementApi(private val client: BuzerApiClient) : EnforcementApi {
    override suspend fun getSnapshot() = client.getSnapshot()
    override suspend fun startTask(id: String) = client.startTask(id)
    override suspend fun postponeStart(id: String) = client.postponeStart(id)
    override suspend fun completeTask(id: String) = client.completeTask(id)
    override suspend fun extendTask(id: String) = client.extendTask(id)
    override suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest) = client.rescheduleTask(id, request)
}
```

- [x] **Step 4: Implement coordinator**

Create `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`:

```kotlin
package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.enforcement.AlarmPlanCalculator
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse
import java.time.Instant

sealed interface CoordinatorActionResult {
    data object Success : CoordinatorActionResult
    data class FailedAndHidden(val message: String) : CoordinatorActionResult
}

class EnforcementCoordinator(
    private val api: EnforcementApi,
    private val snapshotStore: SnapshotStorage,
    private val alarmScheduler: AlarmScheduler,
    private val errorReporter: BlockerErrorReporting,
    private val nowProvider: () -> String = { Instant.now().toString() }
) {
    suspend fun refreshAndReconcile(): TasksSnapshotResponse {
        val snapshot = api.getSnapshot()
        snapshotStore.save(snapshot)
        reconcile(snapshot)
        return snapshot
    }

    fun loadLatestSnapshot(): TasksSnapshotResponse? = snapshotStore.load()

    suspend fun startTask(taskId: String): CoordinatorActionResult =
        runBackendAction(BlockerAction.START_TASK, taskId, retryStartInFiveMinutes = false) {
            api.startTask(taskId)
        }

    suspend fun postponeStart(taskId: String): CoordinatorActionResult =
        runBackendAction(BlockerAction.POSTPONE_START, taskId, retryStartInFiveMinutes = true) {
            api.postponeStart(taskId)
        }

    suspend fun completeTask(taskId: String): CoordinatorActionResult =
        runBackendAction(BlockerAction.COMPLETE_TASK, taskId, retryStartInFiveMinutes = false) {
            api.completeTask(taskId)
            alarmScheduler.cancelAllForTask(taskId)
        }

    suspend fun extendTask(taskId: String): CoordinatorActionResult =
        runBackendAction(BlockerAction.EXTEND_TASK, taskId, retryStartInFiveMinutes = false) {
            api.extendTask(taskId)
        }

    suspend fun rescheduleTask(taskId: String, request: RescheduleTaskRequest): CoordinatorActionResult =
        runBackendAction(BlockerAction.RESCHEDULE_TASK, taskId, retryStartInFiveMinutes = false) {
            api.rescheduleTask(taskId, request)
        }

    private fun reconcile(snapshot: TasksSnapshotResponse) {
        val plan = AlarmPlanCalculator.calculate(snapshot, nowProvider())
        plan.cancelTaskIds.forEach(alarmScheduler::cancelAllForTask)
        plan.alarms.map(AndroidEnforcementMapper::toScheduledAlarm).forEach(alarmScheduler::schedule)
    }

    private suspend fun runBackendAction(
        action: BlockerAction,
        taskId: String,
        retryStartInFiveMinutes: Boolean,
        call: suspend () -> Unit
    ): CoordinatorActionResult {
        return try {
            call()
            refreshAndReconcile()
            CoordinatorActionResult.Success
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "unknown error"
            errorReporter.report(action, taskId, message)
            if (retryStartInFiveMinutes) {
                alarmScheduler.schedule(
                    ScheduledAlarm(
                        taskId = taskId,
                        kind = AlarmKind.START_BLOCKER,
                        triggerAtEpochMillis = Instant.parse(nowProvider()).plusSeconds(5 * 60).toEpochMilli()
                    )
                )
            }
            CoordinatorActionResult.FailedAndHidden(message)
        }
    }
}
```

- [x] **Step 5: Run coordinator tests**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.enforcement.EnforcementCoordinatorTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidEnforcementMapper.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementApi.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt
git commit -m "feat: coordinate Android enforcement sync"
```

## Task 6: Main Activity Refresh Wiring

**Owner:** Android runtime wiring worker.

**Goal:** Create the coordinator from debug config and refresh/reconcile on app open/resume.

- [x] **Step 1: Add coordinator factory**

Create `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt`:

```kotlin
package app.buzer.android.enforcement

import android.content.Context
import app.buzer.android.BuildConfig
import app.buzer.android.sync.SnapshotStore
import app.buzer.shared.api.BuzerApiClient

object EnforcementDependencies {
    fun configError(): String? {
        if (BuildConfig.BUZER_BACKEND_BASE_URL.isBlank()) return "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        if (BuildConfig.BUZER_BACKEND_TOKEN.isBlank()) return "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        return null
    }

    fun coordinator(context: Context): EnforcementCoordinator {
        val error = configError()
        require(error == null) { error }
        val client = BuzerApiClient(
            baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/'),
            token = BuildConfig.BUZER_BACKEND_TOKEN
        )
        return EnforcementCoordinator(
            api = BuzerEnforcementApi(client),
            snapshotStore = SnapshotStore(context),
            alarmScheduler = AndroidAlarmScheduler(context),
            errorReporter = BlockerErrorReporter(context)
        )
    }
}
```

- [x] **Step 2: Wire MainActivity lifecycle refresh**

Modify `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` to:

- create the coordinator only when config is present,
- run `refreshAndReconcile()` in `lifecycleScope.launch` from `onCreate` and `onResume`,
- report refresh failures through `BlockerErrorReporter`,
- keep rendering `BuzerApp(...)`.

Use this shape:

```kotlin
private var coordinator: EnforcementCoordinator? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    NotificationChannels.ensureCreated(this)
    coordinator = runCatching { EnforcementDependencies.coordinator(applicationContext) }.getOrNull()
    refreshEnforcement()
    setContent { BuzerApp(openAddTask = openAddTask, bootRescheduleRequired = bootRescheduleRequired) }
}

override fun onResume() {
    super.onResume()
    refreshEnforcement()
}

private fun refreshEnforcement() {
    val coordinator = coordinator ?: return
    lifecycleScope.launch {
        runCatching { coordinator.refreshAndReconcile() }
            .onFailure {
                BlockerErrorReporter(applicationContext).report(
                    BlockerAction.REFRESH_SNAPSHOT,
                    taskId = null,
                    message = it.message ?: "snapshot refresh failed"
                )
            }
    }
}
```

- [x] **Step 3: Build**

```bash
cd client
./gradlew :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementDependencies.kt
git commit -m "feat: refresh Android enforcement on app open"
```

## Task 7: Blocker Activity Backend Actions

**Owner:** Android blocker wiring worker.

**Goal:** Make blocker buttons call the coordinator and hide after success or failure.

- [ ] **Step 1: Wire StartBlockerActivity**

Modify `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`:

- create coordinator with `EnforcementDependencies.coordinator(applicationContext)`,
- on create, call `refreshAndReconcile()` in `lifecycleScope.launch`,
- on `I'm starting`, call `coordinator.startTask(taskId)`, then `finish()`,
- on `Postpone 5 min`, call `coordinator.postponeStart(taskId)`, then `finish()`,
- if config or coordinator creation fails, report with `BlockerErrorReporter`, then `finish()`.

Use `androidx.lifecycle.lifecycleScope`.

- [ ] **Step 2: Wire DeadlineBlockerActivity**

Modify `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`:

- on `Done`, call `coordinator.completeTask(taskId)`, then `finish()`,
- on `Extend 30 min`, call `coordinator.extendTask(taskId)`, then `finish()`,
- keep `Reschedule` as a local unsupported action for this slice: report `RESCHEDULE_TASK` with message `Reschedule form is not implemented in this slice`, then `finish()`.

Do not schedule deadline retries on failure; `EnforcementCoordinator` already implements that policy.

- [ ] **Step 3: Wire MorningReviewActivity**

Modify `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`:

- on create, call `refreshAndReconcile()` if config is present,
- keep resolve-selected as `finish()` for this slice,
- keep snooze as current local 5-minute morning-review schedule.

- [ ] **Step 4: Build**

```bash
cd client
./gradlew :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt
git commit -m "feat: wire blocker actions to backend"
```

## Task 8: Boot Reschedule Completion And Manual Notes

**Owner:** Android integration worker.

**Goal:** Ensure boot launches the same refresh/reconcile path and update manual verification docs.

- [ ] **Step 1: Verify boot path**

Inspect `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt` and `MainActivity.kt`.

Expected behavior:

- `BootReceiver` calls `RescheduleAfterBoot.enqueue(context)`.
- `RescheduleAfterBoot` starts `MainActivity` with `EXTRA_BOOT_RESCHEDULE_REQUIRED`.
- `MainActivity.onCreate()` calls `refreshEnforcement()`.

If this behavior is not true after Task 6, fix it.

- [ ] **Step 2: Update manual blocker test note**

Modify `docs/notes/2026-05-28-blocker-manual-test.md` and add:

````markdown
## Backend-Connected Checks

Before running these checks, configure `client/local.properties` with `BUZER_BACKEND_BASE_URL` and `BUZER_BACKEND_TOKEN`.

```bash
adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n app.buzer.android/.MainActivity
adb shell am start -n app.buzer.android/.blockers.StartBlockerActivity --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.blockers.DeadlineBlockerActivity --es app.buzer.extra.TASK_ID task-1
```

Expected:

- MainActivity refreshes backend snapshot and schedules alarms.
- Start blocker buttons call backend and hide.
- Deadline blocker Done/Extend call backend and hide.
- With backend stopped, blocker action failure creates a notification and app-private error log.
````

- [ ] **Step 3: Run full Android verification**

```bash
cd client
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt docs/notes/2026-05-28-blocker-manual-test.md
git commit -m "docs: update Android enforcement manual checks"
```

If no code changed in Step 1, only commit the docs note.

## Task 9: Final Review, Handoff, And Plan Status

**Owner:** Final integration worker.

**Goal:** Complete documentation, verification, and review readiness for the slice.

- [ ] **Step 1: Update this plan**

Mark completed steps in `docs/superpowers/plans/2026-05-28-android-vps-connected-enforcement.md`.

- [ ] **Step 2: Write handoff**

Create `docs/handoffs/2026-05-28-android-vps-connected-enforcement.md` with:

```markdown
# Handoff: Android VPS-Connected Enforcement

Date: 2026-05-28
Agent: Codex implementation agent
Plan: `docs/superpowers/plans/2026-05-28-android-vps-connected-enforcement.md`

## Scope

## Completed

## Files Changed

## Verification

- Command: `cd client && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS/FAIL/SKIPPED
- Evidence:

## Manual Verification

## Known Failures Or Skipped Checks

## Assumptions

## Next Recommended Step
```

- [ ] **Step 3: Run final verification**

```bash
cd client
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
git diff --check
git status --short
```

Expected:

- Gradle command: `BUILD SUCCESSFUL`
- `git diff --check`: no output, exit 0
- `git status --short`: only intended files before commit

- [ ] **Step 4: Commit final docs**

```bash
git add docs/superpowers/plans/2026-05-28-android-vps-connected-enforcement.md docs/handoffs/2026-05-28-android-vps-connected-enforcement.md
git commit -m "docs: hand off Android VPS enforcement"
```

## Final Acceptance Criteria

- Debug Android build reads backend URL/token from ignored `client/local.properties`.
- Shared module has tested pure alarm planning.
- Android stores latest snapshot as app-private JSON.
- Android coordinator fetches snapshots and reconciles exact alarms.
- MainActivity refreshes/reconciles on open/resume.
- Start and deadline blocker actions call backend through the coordinator.
- Failed blocker actions hide the blocker, create a notification, and write local error log.
- Failed start postpone schedules a local 5-minute start blocker retry.
- Failed deadline actions do not schedule retries.
- Full Android automated verification passes.
- Manual device checks are documented, and actual device results are recorded if performed.
