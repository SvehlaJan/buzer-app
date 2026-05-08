# KMP Shared And Android Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the Kotlin Multiplatform / Compose Multiplatform client foundation with shared models, validation, API client, and Android UI shell.

**Architecture:** Shared KMP code owns DTOs, validation, time calculations, and view state. The Android app owns Compose screens, navigation, local persistence for cached snapshots, and calls into Android-native enforcement interfaces that are implemented in the next plan.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Android Gradle Plugin, Kotlinx Serialization, Kotlinx Datetime, Ktor Client, Compose Material 3, JUnit/Kotlin test.

---

## Prerequisites

- Complete `2026-05-08-00-repo-foundation-and-contracts.md`.
- Read `docs/notes/api-contract.md`.
- Use Context7 for current Kotlin Multiplatform, Compose Multiplatform, Ktor Client, and Kotlinx Serialization docs before coding.

## Files

- Create: `client/settings.gradle.kts`
- Create: `client/build.gradle.kts`
- Create: `client/gradle.properties`
- Create: `client/gradle/libs.versions.toml`
- Create: `client/shared/build.gradle.kts`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/TaskModels.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/model/HealthModels.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/time/ScheduleCalculator.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/validation/TaskValidation.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/api/BuzerApiClient.kt`
- Create: `client/shared/src/commonMain/kotlin/app/buzer/shared/state/SnapshotReducer.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/time/ScheduleCalculatorTest.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/validation/TaskValidationTest.kt`
- Create: `client/shared/src/commonTest/kotlin/app/buzer/shared/state/SnapshotReducerTest.kt`
- Create: `client/androidApp/build.gradle.kts`
- Create: `client/androidApp/src/main/AndroidManifest.xml`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`

## Task 1: Resolve Current KMP Docs And Scaffold Project

- [ ] **Step 1: Fetch docs**

Run:

```bash
npx ctx7@latest library "Kotlin Multiplatform" "Compose Multiplatform Android app shared code Gradle setup source sets"
npx ctx7@latest docs "/resolved/kmp/id" "Kotlin Multiplatform Compose Multiplatform Android Gradle setup sourceSets commonMain commonTest androidApp"
npx ctx7@latest library "Ktor Client" "Kotlin Multiplatform HTTP client JSON serialization"
npx ctx7@latest docs "/resolved/ktor/id" "Ktor Client Kotlin Multiplatform JSON content negotiation bearer token"
```

Record resolved IDs and plugin version choices in the handoff.

- [ ] **Step 2: Create Gradle structure**

Create a Compose Multiplatform Android project under `client/` with modules:

```text
client/shared
client/androidApp
```

Use current stable Kotlin, Compose Multiplatform, Android Gradle Plugin, Ktor, Kotlinx Serialization, and Kotlinx Datetime versions from the docs and release notes checked in Step 1. Record exact versions in `docs/notes/2026-05-08-kmp-version-selection.md`.

- [ ] **Step 3: Verify project lists tasks**

Run:

```bash
cd client
./gradlew tasks
```

Expected: Gradle lists tasks for `shared` and `androidApp`.

## Task 2: Implement Shared Models

- [ ] **Step 1: Create `TaskModels.kt`**

```kotlin
package app.buzer.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class CommitmentState {
    planned,
    pre_start_warning,
    start_blocker_active,
    started,
    deadline_blocker_active,
    completed,
    invalid,
    deleted
}

@Serializable
data class TaskSnapshotItem(
    val id: String,
    val todoistTaskId: String,
    val title: String,
    val projectId: String?,
    val assigneeId: String?,
    val creatorId: String?,
    val dueDatetime: String?,
    val durationMinutes: Int?,
    val startAt: String?,
    val state: CommitmentState,
    val isAssignedToUser: Boolean,
    val isCreatedByUser: Boolean,
    val isCompleted: Boolean,
    val isDeleted: Boolean,
    val validationErrors: List<String>,
    val startedAt: String?,
    val startDismissCount: Int,
    val deadlineExtensionCount: Int,
    val escalationSentAt: String?,
    val updatedAt: String
)

@Serializable
data class TasksSnapshotResponse(
    val scheduleVersion: Long,
    val serverTime: String,
    val tasks: List<TaskSnapshotItem>,
    val health: List<HealthStatus>
)

@Serializable
data class CreateTaskRequest(
    val title: String,
    val projectId: String?,
    val assigneeId: String,
    val dueDatetime: String?,
    val durationMinutes: Int?
)

@Serializable
data class RescheduleTaskRequest(
    val dueDatetime: String,
    val durationMinutes: Int
)
```

- [ ] **Step 2: Create `HealthModels.kt`**

```kotlin
package app.buzer.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class HealthLevel {
    ok,
    warning,
    error
}

@Serializable
data class HealthStatus(
    val key: String,
    val level: HealthLevel,
    val message: String
)
```

- [ ] **Step 3: Run shared compilation**

```bash
cd client
./gradlew :shared:compileKotlinMetadata
```

Expected: pass.

## Task 3: Implement Schedule Calculator

- [ ] **Step 1: Write `ScheduleCalculatorTest.kt`**

```kotlin
package app.buzer.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScheduleCalculatorTest {
    @Test
    fun computesStartAtFromDeadlineAndDuration() {
        assertEquals(
            "2026-05-08T15:15:00Z",
            ScheduleCalculator.computeStartAt("2026-05-08T16:00:00Z", 45)
        )
    }

    @Test
    fun rejectsNonPositiveDuration() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleCalculator.computeStartAt("2026-05-08T16:00:00Z", 0)
        }
    }
}
```

- [ ] **Step 2: Implement `ScheduleCalculator.kt`**

```kotlin
package app.buzer.shared.time

import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.minutes

object ScheduleCalculator {
    fun computeStartAt(dueDatetime: String, durationMinutes: Int): String {
        require(durationMinutes > 0) { "durationMinutes must be positive" }
        val due = Instant.parse(dueDatetime)
        return due.minus(durationMinutes.minutes).toString()
    }

    fun extendDeadlineByThirtyMinutes(dueDatetime: String): String {
        return Instant.parse(dueDatetime).plus(30.minutes).toString()
    }
}
```

- [ ] **Step 3: Run shared test**

```bash
cd client
./gradlew :shared:allTests --tests app.buzer.shared.time.ScheduleCalculatorTest
```

Expected: pass.

## Task 4: Implement Shared Validation

- [ ] **Step 1: Write `TaskValidationTest.kt`**

```kotlin
package app.buzer.shared.validation

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskValidationTest {
    @Test
    fun requiresAssigneeForCreatedTask() {
        val result = TaskValidation.validate(
            title = "Buy milk",
            creatorId = "self",
            assigneeId = null,
            dueDatetime = "2026-05-08T16:00:00Z",
            durationMinutes = 10,
            currentUserId = "self"
        )
        assertEquals(listOf("assignee_required_for_created_task"), result.errors)
    }

    @Test
    fun requiresDeadlineAndDurationWhenAssignedToSelf() {
        val result = TaskValidation.validate(
            title = "Book service",
            creatorId = "wife",
            assigneeId = "self",
            dueDatetime = null,
            durationMinutes = null,
            currentUserId = "self"
        )
        assertEquals(listOf("due_datetime_required", "duration_required"), result.errors)
    }
}
```

- [ ] **Step 2: Implement `TaskValidation.kt`**

```kotlin
package app.buzer.shared.validation

data class ValidationResult(val valid: Boolean, val errors: List<String>)

object TaskValidation {
    fun validate(
        title: String,
        creatorId: String?,
        assigneeId: String?,
        dueDatetime: String?,
        durationMinutes: Int?,
        currentUserId: String
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val createdByUser = creatorId == currentUserId
        val assignedToUser = assigneeId == currentUserId

        if (title.isBlank()) errors += "title_required"
        if (createdByUser && assigneeId == null) errors += "assignee_required_for_created_task"
        if (assignedToUser && dueDatetime == null) errors += "due_datetime_required"
        if (assignedToUser && durationMinutes == null) errors += "duration_required"
        if (assignedToUser && durationMinutes != null && durationMinutes <= 0) errors += "duration_must_be_positive_minutes"

        return ValidationResult(errors.isEmpty(), errors)
    }
}
```

- [ ] **Step 3: Run validation test**

```bash
cd client
./gradlew :shared:allTests --tests app.buzer.shared.validation.TaskValidationTest
```

Expected: pass.

## Task 5: Implement API Client

- [ ] **Step 1: Create `BuzerApiClient.kt`**

```kotlin
package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class BuzerApiClient(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = defaultHttpClient(token)
) {
    suspend fun getSnapshot(): TasksSnapshotResponse =
        httpClient.get("$baseUrl/tasks/snapshot").body()

    suspend fun createTask(request: CreateTaskRequest) {
        httpClient.post("$baseUrl/tasks") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun startTask(id: String) {
        httpClient.post("$baseUrl/tasks/$id/start")
    }

    suspend fun completeTask(id: String) {
        httpClient.post("$baseUrl/tasks/$id/done")
    }

    suspend fun postponeStart(id: String) {
        httpClient.post("$baseUrl/tasks/$id/postpone-start")
    }

    suspend fun extendTask(id: String) {
        httpClient.post("$baseUrl/tasks/$id/extend")
    }

    suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest) {
        httpClient.post("$baseUrl/tasks/$id/reschedule") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }
}

private fun defaultHttpClient(token: String): HttpClient =
    HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        defaultRequest {
            bearerAuth(token)
        }
    }
```

- [ ] **Step 2: Compile shared module**

```bash
cd client
./gradlew :shared:compileKotlinMetadata
```

Expected: pass.

## Task 6: Build Android Shell Screens

- [ ] **Step 1: Implement `MainActivity.kt`**

```kotlin
package app.buzer.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.buzer.android.ui.BuzerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BuzerApp()
        }
    }
}
```

- [ ] **Step 2: Implement `App.kt`**

```kotlin
package app.buzer.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import app.buzer.android.ui.screens.HomeScreen

@Composable
fun BuzerApp() {
    MaterialTheme {
        HomeScreen()
    }
}
```

- [ ] **Step 3: Implement first-pass `HomeScreen.kt`**

```kotlin
package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Today")
        Button(onClick = {}) {
            Text("Add task")
        }
    }
}
```

- [ ] **Step 4: Create screen stubs**

Create `AddTaskScreen.kt`, `MorningReviewScreen.kt`, and `BlockerScreens.kt` with composable functions that compile and contain the button labels from the spec: "I'm starting", "Postpone 5 min", "Done", "Extend 30 min", and "Reschedule".

- [ ] **Step 5: Build Android debug app**

```bash
cd client
./gradlew :androidApp:assembleDebug
```

Expected: pass.

## Task 7: Final Verification And Handoff

- [ ] **Step 1: Run client verification**

```bash
cd client
./gradlew :shared:allTests :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: pass.

- [ ] **Step 2: Commit**

```bash
git add client docs/notes/2026-05-08-kmp-version-selection.md docs/superpowers/plans/2026-05-08-04-kmp-shared-and-android-shell.md
git commit -m "feat: add KMP Android client shell"
```

- [ ] **Step 3: Write handoff**

Create `docs/handoffs/YYYY-MM-DD-kmp-android-shell.md` with exact Gradle/plugin versions, commands run, and UI surfaces created.

