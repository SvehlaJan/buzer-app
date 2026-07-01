# Android Shared Picker Components And Theme Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three copy-pasted date/time/duration input implementations (Add Task, the deadline blocker's reschedule flow, Morning Review's repair flow) with two shared wheel-picker components built on `WheelPickerCompose`, fix the missing inset padding on the three full-screen blocker Activities via one shared Activity entry point, and add a small shared spacing/theme file.

**Architecture:** Add `WheelPickerCompose` as a spike first to de-risk its archived-dependency chain. Build two pure-logic-plus-composable components in a new `ui/pickers` package. Add a `ui/theme` package (`Spacing`, `BuzerTheme`) and a `setBuzerContent` Activity extension that both fixes insets and gives every Activity one shared theme entry point. Migrate the three screens one at a time, deleting duplicated state/dialogs/validation as each one moves over. Delete now-dead millis-conversion helpers last, once nothing references them.

**Tech Stack:** Kotlin, Jetpack Compose, Compose Material3, `WheelPickerCompose` (JitPack), Gradle version catalog, JUnit/kotlin.test.

**Source spec:** `docs/superpowers/specs/2026-07-01-android-shared-picker-components-and-theme-design.md`

---

## File Structure

- Modify: `client/settings.gradle.kts` — add JitPack repository.
- Modify: `client/gradle/libs.versions.toml` — add `WheelPickerCompose` version/library entry.
- Modify: `client/androidApp/build.gradle.kts` — add the dependency.
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/Spacing.kt` — spacing tokens.
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerTheme.kt` — thin `MaterialTheme` wrapper.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt` — add `setBuzerContent`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt` — stop wrapping its own theme/insets (now supplied by `setBuzerContent`).
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` — use `setBuzerContent`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt` — use `setBuzerContent`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt` — use `setBuzerContent`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt` — use `setBuzerContent`.
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDurationField.kt` — hour/minute duration wheel.
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDurationFieldTest.kt`.
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt` — combined date+time wheel.
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDateTimeFieldTest.kt`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt` — accept `durationMinutes: Int`.
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt` — use the two new fields.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt` — `DeadlineBlockerScreen` uses the two new fields.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt` — repair flow uses the two new fields.
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt` — delete dead millis helpers.
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt` — delete the test for the deleted helper.

Out of scope for this plan (see spec): retrofitting `Spacing` onto existing untouched screens, a full design-system/branding pass, and `RescheduleInputParser.kt` (a separate, already-unused file flagged for its own follow-up cleanup, not touched here).

---

## Task 1: Add the WheelPickerCompose dependency (spike)

**Files:**
- Modify: `client/settings.gradle.kts`
- Modify: `client/gradle/libs.versions.toml`
- Modify: `client/androidApp/build.gradle.kts`

This task only adds the dependency and confirms it resolves and compiles — no code uses it yet. This is deliberately isolated so a resolution/version problem with the archived `dev.chrisbanes.snapper` transitive dependency surfaces immediately, not after other work is built on top of it.

- [x] **Step 1: Add the JitPack repository**

Edit `client/settings.gradle.kts`. Current content (relevant block):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

Change the `repositories` block to:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

- [x] **Step 2: Add the version catalog entry**

Edit `client/gradle/libs.versions.toml`. In `[versions]`, add a line after `ktor = "3.5.0"`:

```toml
wheelPickerCompose = "1.1.11"
```

In `[libraries]`, add a line after the `ktor-serialization-kotlinx-json` entry:

```toml
wheel-picker-compose = { module = "com.github.commandiron:WheelPickerCompose", version.ref = "wheelPickerCompose" }
```

- [x] **Step 3: Add the dependency to androidApp**

Edit `client/androidApp/build.gradle.kts`. In the `dependencies { }` block, add after `implementation(libs.compose.ui)`:

```kotlin
    implementation(libs.wheel.picker.compose)
```

- [x] **Step 4: Confirm the dependency resolves and compiles**

Run: `cd client && ./gradlew :androidApp:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

If this fails with a dependency resolution error or a version conflict involving `dev.chrisbanes.snapper`, stop here — do not proceed to Task 4/5 — and report the exact Gradle error back before deciding whether to pin a different snapper/compose version or fall back to building the wheel mechanics directly on `LazyColumn` + `SnapFlingBehavior` instead of this library.

- [x] **Step 5: Commit**

```bash
git add client/settings.gradle.kts client/gradle/libs.versions.toml client/androidApp/build.gradle.kts
git commit -m "build: add WheelPickerCompose dependency"
```

---

## Task 2: Shared theme package (Spacing + BuzerTheme)

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/Spacing.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerTheme.kt`

Both files are pure constants / a thin `MaterialTheme` passthrough with no branching logic, consistent with how this codebase already leaves similar wrapper composables (`InsetSafeContent`, `LoadingActionButton`) unit-test-free and verified via broader screen tests and manual passes instead. No dedicated unit test is added for this task.

- [x] **Step 1: Create the spacing tokens file**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/Spacing.kt`:

```kotlin
package app.buzer.android.ui.theme

import androidx.compose.ui.unit.dp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}
```

- [x] **Step 2: Create the theme wrapper**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/theme/BuzerTheme.kt`:

```kotlin
package app.buzer.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun BuzerTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
```

- [x] **Step 3: Compile**

Run: `cd client && ./gradlew :androidApp:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/theme
git commit -m "feat: add shared spacing tokens and BuzerTheme wrapper"
```

---

## Task 3: Shared Activity entry point and insets fix

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`

`BuzerApp` (in `App.kt`) currently wraps itself in `MaterialTheme { Surface { InsetSafeContent { ... } } }`. If `setBuzerContent` also applies `BuzerTheme`/`InsetSafeContent` around whatever it's given, and `MainActivity` calls `setBuzerContent { BuzerApp(...) }`, `BuzerApp`'s own wrapping would nest `Modifier.safeDrawingPadding()` twice — double-applying system-bar padding. So `BuzerApp` must stop wrapping itself in this task, and rely on `setBuzerContent` for both theme and insets, same as every other Activity.

- [x] **Step 1: Add `setBuzerContent` to StatusComponents.kt**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt`. Add these imports near the top (alongside the existing `androidx.compose.*` imports):

```kotlin
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.buzer.android.ui.theme.BuzerTheme
```

Add this function after `InsetSafeContent` (after its closing `}` at what is currently line 34):

```kotlin
fun ComponentActivity.setBuzerContent(content: @Composable () -> Unit) {
    setContent {
        BuzerTheme {
            InsetSafeContent {
                content()
            }
        }
    }
}
```

- [x] **Step 2: Remove BuzerApp's own theme/insets wrapping**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`. Replace:

```kotlin
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            val initialSection = if (openAddTask) AppSection.Add else AppSection.Home
            var section by remember { mutableStateOf(initialSection) }
            fun openMorningReview() {
                AppLog.info("ui navigate section=Review source=home mode=activity")
                context.startActivity(Intent(context, MorningReviewActivity::class.java))
            }

            InsetSafeContent {
                val homeState = buildBuzerHomeState(
                    snapshot = snapshot,
                    homeRefreshInFlight = homeRefreshInFlight,
                    homeRefreshError = homeRefreshError
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    when (section) {
                        AppSection.Home -> HomeScreen(
                            onAddTask = {
                                AppLog.debug("ui navigate section=Add source=home")
                                section = AppSection.Add
                            },
                            onMorningReview = {
                                openMorningReview()
                            },
                            onRetryRefresh = onRetryHomeRefresh,
                            state = homeState
                        )

                        AppSection.Add -> AddTaskScreen(
                            onBack = { section = AppSection.Home },
                            onTaskCreated = onTaskCreated
                        )
                    }
                }
            }
        }
    }
```

with:

```kotlin
    Surface(modifier = Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val initialSection = if (openAddTask) AppSection.Add else AppSection.Home
        var section by remember { mutableStateOf(initialSection) }
        fun openMorningReview() {
            AppLog.info("ui navigate section=Review source=home mode=activity")
            context.startActivity(Intent(context, MorningReviewActivity::class.java))
        }

        val homeState = buildBuzerHomeState(
            snapshot = snapshot,
            homeRefreshInFlight = homeRefreshInFlight,
            homeRefreshError = homeRefreshError
        )

        Column(modifier = Modifier.fillMaxSize()) {
            when (section) {
                AppSection.Home -> HomeScreen(
                    onAddTask = {
                        AppLog.debug("ui navigate section=Add source=home")
                        section = AppSection.Add
                    },
                    onMorningReview = {
                        openMorningReview()
                    },
                    onRetryRefresh = onRetryHomeRefresh,
                    state = homeState
                )

                AppSection.Add -> AddTaskScreen(
                    onBack = { section = AppSection.Home },
                    onTaskCreated = onTaskCreated
                )
            }
        }
    }
```

Remove the now-unused imports `androidx.compose.material3.MaterialTheme` and `app.buzer.android.ui.status.InsetSafeContent` from this file (the `Surface`/`Column`/etc. imports stay).

- [x] **Step 3: Wire MainActivity through setBuzerContent**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`. Replace:

```kotlin
import androidx.activity.compose.setContent
```

with:

```kotlin
import app.buzer.android.ui.status.setBuzerContent
```

Replace:

```kotlin
        setContent {
            BuzerApp(
```

with:

```kotlin
        setBuzerContent {
            BuzerApp(
```

(the rest of the `BuzerApp(...)` call and its closing `}` and `)` are unchanged).

- [x] **Step 4: Wire StartBlockerActivity through setBuzerContent**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`. Replace:

```kotlin
import androidx.activity.compose.setContent
```

with:

```kotlin
import app.buzer.android.ui.status.setBuzerContent
```

In `showBlocker`, replace:

```kotlin
        setContent {
            StartBlockerScreen(
```

with:

```kotlin
        setBuzerContent {
            StartBlockerScreen(
```

- [x] **Step 5: Wire DeadlineBlockerActivity through setBuzerContent**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`. Same replacement as Step 4: swap the `androidx.activity.compose.setContent` import for `app.buzer.android.ui.status.setBuzerContent`, and in `showBlocker`, change `setContent {` to `setBuzerContent {` (the call wraps `DeadlineBlockerScreen(...)`).

- [x] **Step 6: Wire MorningReviewActivity through setBuzerContent**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`. Same replacement: swap the `androidx.activity.compose.setContent` import for `app.buzer.android.ui.status.setBuzerContent`, and change `setContent {` to `setBuzerContent {` (the call wraps `MorningReviewScreen(...)`).

- [x] **Step 7: Run the full test suite and assemble**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

Run: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 8: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/status/StatusComponents.kt \
        client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt \
        client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt \
        client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt \
        client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt \
        client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt
git commit -m "fix: apply inset-safe padding to all activities via shared entry point"
```

---

## Task 4: WheelDurationField

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDurationField.kt`
- Test: `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDurationFieldTest.kt`

- [x] **Step 1: Write the failing test**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDurationFieldTest.kt`:

```kotlin
package app.buzer.android.ui.pickers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WheelDurationFieldTest {
    @Test
    fun combinesHoursAndMinutesIntoTotalMinutes() {
        assertEquals(90, totalDurationMinutes(hours = 1, minutes = 30))
        assertEquals(0, totalDurationMinutes(hours = 0, minutes = 0))
        assertEquals(415, totalDurationMinutes(hours = 6, minutes = 55))
    }

    @Test
    fun startsWheelAtExactMinuteStep() {
        assertEquals(1 to 30, durationWheelStart(90))
        assertEquals(0 to 0, durationWheelStart(0))
    }

    @Test
    fun roundsWheelStartToNearestFiveMinuteStep() {
        assertEquals(0 to 5, durationWheelStart(7))
        assertEquals(0 to 10, durationWheelStart(12))
    }

    @Test
    fun clampsWheelStartToMaxRange() {
        assertEquals(6 to 55, durationWheelStart(999))
    }

    @Test
    fun formatsDurationForDisplay() {
        assertEquals("1h 30m", formatDuration(90))
        assertEquals("0h 5m", formatDuration(5))
    }

    @Test
    fun disallowsZeroDuration() {
        assertFalse(canConfirmDuration(hours = 0, minutes = 0))
        assertTrue(canConfirmDuration(hours = 0, minutes = 5))
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.pickers.WheelDurationFieldTest' --console=plain`
Expected: compile failure — `totalDurationMinutes`, `durationWheelStart`, `formatDuration`, and `canConfirmDuration` are unresolved references.

- [x] **Step 3: Implement WheelDurationField**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDurationField.kt`:

```kotlin
package app.buzer.android.ui.pickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.theme.Spacing
import com.commandiron.wheel_picker_compose.core.WheelTextPicker
import kotlin.math.abs

private val HOURS_RANGE = 0..6
private val MINUTE_STEPS = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)

internal fun totalDurationMinutes(hours: Int, minutes: Int): Int = hours * 60 + minutes

internal fun durationWheelStart(totalMinutes: Int): Pair<Int, Int> {
    val maxMinutes = HOURS_RANGE.last * 60 + MINUTE_STEPS.last
    val clamped = totalMinutes.coerceIn(0, maxMinutes)
    val hours = (clamped / 60).coerceIn(HOURS_RANGE)
    val remainder = clamped % 60
    val minutes = MINUTE_STEPS.minByOrNull { abs(it - remainder) } ?: 0
    return hours to minutes
}

internal fun formatDuration(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "${hours}h ${minutes}m"
}

internal fun canConfirmDuration(hours: Int, minutes: Int): Boolean =
    totalDurationMinutes(hours, minutes) > 0

@Composable
fun WheelDurationField(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var dialogVisible by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { dialogVisible = true },
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label }
    ) {
        Text(formatDuration(minutes))
    }

    if (dialogVisible) {
        val start = remember(minutes) { durationWheelStart(minutes) }
        var pendingHours by remember { mutableIntStateOf(start.first) }
        var pendingMinutes by remember { mutableIntStateOf(start.second) }

        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text(label) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    WheelTextPicker(
                        texts = HOURS_RANGE.map { "${it}h" },
                        startIndex = HOURS_RANGE.indexOf(start.first),
                        size = DpSize(96.dp, 128.dp),
                        onScrollFinished = { index ->
                            pendingHours = HOURS_RANGE.elementAt(index)
                            null
                        }
                    )
                    WheelTextPicker(
                        texts = MINUTE_STEPS.map { "${it}m" },
                        startIndex = MINUTE_STEPS.indexOf(start.second),
                        size = DpSize(96.dp, 128.dp),
                        onScrollFinished = { index ->
                            pendingMinutes = MINUTE_STEPS[index]
                            null
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMinutesChange(totalDurationMinutes(pendingHours, pendingMinutes))
                        dialogVisible = false
                    },
                    enabled = canConfirmDuration(pendingHours, pendingMinutes)
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
```

If the compiler reports that `WheelTextPicker`'s parameters don't match (e.g. a required `rowCount` or `style` with no default), check the actual signature with
`npx ctx7@latest docs /commandiron/wheelpickercompose "WheelTextPicker composable full parameter list and defaults"` and adjust the call — the parameter names/behavior described here (`texts`, `startIndex`, `size`, `onScrollFinished`) are confirmed from the library's documented usage examples, but exact default values were not independently verified against the library's source.

- [x] **Step 4: Run the test and verify GREEN**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.pickers.WheelDurationFieldTest' --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add a Compose Preview for device-free visual verification** — deferred: `androidApp` has no `androidx.compose.ui:ui-tooling-preview`/`ui-tooling` dependency and no existing `@Preview` usage anywhere in the module; adding it is out of this task's two-file scope. Tracked as a follow-up (see spawned background task) rather than silently skipped.

Append to `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDurationField.kt`:

```kotlin
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun WheelDurationFieldPreview() {
    app.buzer.android.ui.theme.BuzerTheme {
        WheelDurationField(minutes = 30, onMinutesChange = {}, label = "Duration minutes input")
    }
}
```

- [x] **Step 6: Run the broad unit test suite**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 7: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDurationField.kt \
        client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDurationFieldTest.kt
git commit -m "feat: add WheelDurationField hour/minute picker"
```

---

## Task 5: WheelDateTimeField

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt`
- Test: `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDateTimeFieldTest.kt`

- [ ] **Step 1: Write the failing test**

Create `client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDateTimeFieldTest.kt`:

```kotlin
package app.buzer.android.ui.pickers

import app.buzer.android.ui.datetime.DateTimeSelection
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class WheelDateTimeFieldTest {
    @Test
    fun convertsLocalDateTimeToDateTimeSelection() {
        val dateTime = LocalDateTime.of(2026, 6, 2, 18, 30)

        val selection = toDateTimeSelection(dateTime)

        assertEquals(LocalDate.of(2026, 6, 2), selection.date)
        assertEquals(18, selection.hour)
        assertEquals(30, selection.minute)
    }

    @Test
    fun convertsDateTimeSelectionToLocalDateTime() {
        val selection = DateTimeSelection(date = LocalDate.of(2026, 6, 2), hour = 18, minute = 30)

        assertEquals(LocalDateTime.of(2026, 6, 2, 18, 30), toLocalDateTime(selection))
    }

    @Test
    fun defaultsToOneHourFromNowWhenNoSelection() {
        val now = LocalDateTime.of(2026, 6, 2, 9, 0)

        assertEquals(LocalDateTime.of(2026, 6, 2, 10, 0), startDateTimeFor(selection = null, now = now))
    }

    @Test
    fun usesExistingSelectionWhenPresent() {
        val now = LocalDateTime.of(2026, 6, 2, 9, 0)
        val selection = DateTimeSelection(date = LocalDate.of(2026, 6, 3), hour = 8, minute = 15)

        assertEquals(LocalDateTime.of(2026, 6, 3, 8, 15), startDateTimeFor(selection, now))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.pickers.WheelDateTimeFieldTest' --console=plain`
Expected: compile failure — `toDateTimeSelection`, `toLocalDateTime`, and `startDateTimeFor` are unresolved references.

- [ ] **Step 3: Implement WheelDateTimeField**

Create `client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt`:

```kotlin
package app.buzer.android.ui.pickers

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.datetime.DateTimeSelection
import com.commandiron.wheel_picker_compose.WheelDateTimePicker
import java.time.LocalDateTime

internal fun toDateTimeSelection(dateTime: LocalDateTime): DateTimeSelection =
    DateTimeSelection(date = dateTime.toLocalDate(), hour = dateTime.hour, minute = dateTime.minute)

internal fun toLocalDateTime(selection: DateTimeSelection): LocalDateTime =
    selection.date.atTime(selection.hour, selection.minute)

internal fun startDateTimeFor(selection: DateTimeSelection?, now: LocalDateTime): LocalDateTime =
    selection?.let(::toLocalDateTime) ?: now.plusHours(1)

@Composable
fun WheelDateTimeField(
    selection: DateTimeSelection?,
    onSelectionChange: (DateTimeSelection) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var dialogVisible by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { dialogVisible = true },
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label }
    ) {
        Text(selection?.formatForDisplay() ?: "No deadline selected")
    }

    if (dialogVisible) {
        val start = remember(selection) { startDateTimeFor(selection, LocalDateTime.now()) }
        var pending by remember { mutableStateOf(start) }

        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text(label) },
            text = {
                WheelDateTimePicker(
                    startDateTime = start,
                    size = DpSize(256.dp, 128.dp)
                ) { snapped ->
                    pending = snapped
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSelectionChange(toDateTimeSelection(pending))
                        dialogVisible = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
```

If the compiler reports missing required parameters on `WheelDateTimePicker` (for example if `minDateTime`/`maxDateTime`/`yearsRange`/`timeFormat` turn out not to have defaults), check
`npx ctx7@latest docs /commandiron/wheelpickercompose "WheelDateTimePicker composable full parameter list and defaults"` and add the missing ones — a reasonable default `yearsRange` is `IntRange(LocalDateTime.now().year, LocalDateTime.now().year + 5)` and a reasonable default `timeFormat` is `TimeFormat.HOUR_24`, matching the 24-hour `TimePicker` behavior being replaced.

- [ ] **Step 4: Run the test and verify GREEN**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.pickers.WheelDateTimeFieldTest' --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add a Compose Preview** — skip this step. `androidApp` has no `androidx.compose.ui:ui-tooling-preview`/`ui-tooling` dependency and no existing `@Preview` usage anywhere in the module (confirmed during Task 4); adding one is out of scope here and tracked as a separate follow-up. Do not attempt to add the dependency yourself.

(Reference only, not to be added — kept here for when preview tooling is added later:)

```kotlin
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun WheelDateTimeFieldPreview() {
    app.buzer.android.ui.theme.BuzerTheme {
        WheelDateTimeField(selection = null, onSelectionChange = {}, label = "Deadline picker")
    }
}
```

- [ ] **Step 6: Run the broad unit test suite**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/pickers/WheelDateTimeField.kt \
        client/androidApp/src/test/kotlin/app/buzer/android/ui/pickers/WheelDateTimeFieldTest.kt
git commit -m "feat: add WheelDateTimeField combined date and time picker"
```

---

## Task 6: Migrate AddTaskScreen and AddTaskFormValidator

**Files:**
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`

`AddTaskFormValidator.validate` currently takes `durationText: String` and parses/validates it itself. Once `AddTaskScreen` holds an `Int` from `WheelDurationField` (which already guarantees a positive value before it can be confirmed), that parsing is dead weight — the validator should take the `Int` directly.

- [ ] **Step 1: Update the validator test to the new signature (RED)**

Edit `client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt`. Replace every `durationText = "45"` / `durationText = "30"` / positional `"45"` argument with `durationMinutes = 45` / `durationMinutes = 30` (named), and delete the `rejectsInvalidDuration` test entirely (that state — a non-numeric or non-positive duration string — can no longer occur once the caller passes a validated `Int`). The full updated file:

```kotlin
package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.ProjectItem
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddTaskFormValidatorTest {
    private val me = AddTaskAssigneeOption(label = "Me", id = "user-me")
    private val deadline = DateTimeSelection(LocalDate.of(2026, 6, 2), 18, 30)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val assignableProject = ProjectItem(
        id = "project-family",
        name = "Family",
        canAssignTasks = true,
        shared = true,
        isInboxProject = false
    )
    private val personalProject = ProjectItem(
        id = "project-personal",
        name = "Personal",
        canAssignTasks = false,
        shared = false,
        isInboxProject = false
    )

    @Test
    fun buildsAssignedCreateTaskRequestForAssignableProject() {
        val result = AddTaskFormValidator.validate(
            title = " Buy milk ",
            selectedProject = assignableProject,
            selectedAssignee = me,
            deadline = deadline,
            durationMinutes = 45,
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("Buy milk", result.request.title)
        assertEquals("project-family", result.request.projectId)
        assertEquals("user-me", result.request.assigneeId)
        assertEquals("2026-06-02T16:30:00Z", result.request.dueDatetime)
        assertEquals(45, result.request.durationMinutes)
    }

    @Test
    fun buildsPersonalCreateTaskRequestForNonAssignableProject() {
        val result = AddTaskFormValidator.validate(
            title = " Personal task ",
            selectedProject = personalProject,
            selectedAssignee = null,
            deadline = deadline,
            durationMinutes = 30,
            zoneId = zone
        )

        assertTrue(result is AddTaskFormResult.Valid)
        assertEquals("project-personal", result.request.projectId)
        assertEquals(null, result.request.assigneeId)
    }

    @Test
    fun rejectsBlankTitle() {
        val result = AddTaskFormValidator.validate("", assignableProject, me, deadline, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Title is required"), result)
    }

    @Test
    fun rejectsMissingProject() {
        val result = AddTaskFormValidator.validate("Buy milk", null, me, deadline, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Project is required"), result)
    }

    @Test
    fun rejectsMissingAssigneeForAssignableProject() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, null, deadline, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Assignee is required for this project"), result)
    }

    @Test
    fun rejectsBlankAssigneeId() {
        val result = AddTaskFormValidator.validate(
            title = "Buy milk",
            selectedProject = assignableProject,
            selectedAssignee = AddTaskAssigneeOption("Me", ""),
            deadline = deadline,
            durationMinutes = 45,
            zoneId = zone
        )

        assertEquals(AddTaskFormResult.Invalid("Me assignee ID is not configured"), result)
    }

    @Test
    fun rejectsMissingDeadline() {
        val result = AddTaskFormValidator.validate("Buy milk", assignableProject, me, null, 45, zone)

        assertEquals(AddTaskFormResult.Invalid("Deadline is required"), result)
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskFormValidatorTest' --console=plain`
Expected: compile failure — no `validate` overload matches `durationMinutes: Int` yet.

- [ ] **Step 3: Update the validator**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt`. Replace the whole `validate` function:

```kotlin
    fun validate(
        title: String,
        selectedProject: ProjectItem?,
        selectedAssignee: AddTaskAssigneeOption?,
        deadline: DateTimeSelection?,
        durationText: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required")
        }
        if (selectedProject == null) {
            return AddTaskFormResult.Invalid("Project is required")
        }
        if (selectedProject.canAssignTasks) {
            if (selectedAssignee == null) {
                return AddTaskFormResult.Invalid("Assignee is required for this project")
            }
            if (selectedAssignee.id.isBlank()) {
                return AddTaskFormResult.Invalid("${selectedAssignee.label} assignee ID is not configured")
            }
        }
        if (deadline == null) {
            return AddTaskFormResult.Invalid("Deadline is required")
        }

        val duration = durationText.trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            return AddTaskFormResult.Invalid("Duration must be a positive whole number of minutes")
        }

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = trimmedTitle,
                projectId = selectedProject.id,
                assigneeId = selectedAssignee?.id?.takeIf { selectedProject.canAssignTasks },
                dueDatetime = deadline.toIsoInstantString(zoneId),
                durationMinutes = duration
            )
        )
    }
```

with:

```kotlin
    fun validate(
        title: String,
        selectedProject: ProjectItem?,
        selectedAssignee: AddTaskAssigneeOption?,
        deadline: DateTimeSelection?,
        durationMinutes: Int,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required")
        }
        if (selectedProject == null) {
            return AddTaskFormResult.Invalid("Project is required")
        }
        if (selectedProject.canAssignTasks) {
            if (selectedAssignee == null) {
                return AddTaskFormResult.Invalid("Assignee is required for this project")
            }
            if (selectedAssignee.id.isBlank()) {
                return AddTaskFormResult.Invalid("${selectedAssignee.label} assignee ID is not configured")
            }
        }
        if (deadline == null) {
            return AddTaskFormResult.Invalid("Deadline is required")
        }

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = trimmedTitle,
                projectId = selectedProject.id,
                assigneeId = selectedAssignee?.id?.takeIf { selectedProject.canAssignTasks },
                dueDatetime = deadline.toIsoInstantString(zoneId),
                durationMinutes = durationMinutes
            )
        )
    }
```

- [ ] **Step 4: Run the test and verify GREEN**

Run: `cd client && ./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.ui.addtask.AddTaskFormValidatorTest' --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Migrate AddTaskScreen's state and imports**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt`.

Remove these imports (all become unused once the steps below remove their only call sites):

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.text.input.KeyboardType
import app.buzer.android.ui.datetime.datePickerMillisToLocalDate
import app.buzer.android.ui.datetime.localDateToDatePickerMillis
import java.time.LocalDate
import java.time.LocalDateTime
```

Add:

```kotlin
import app.buzer.android.ui.pickers.WheelDateTimeField
import app.buzer.android.ui.pickers.WheelDurationField
```

Replace this state block:

```kotlin
    var durationMinutes by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitDialogMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val defaultDateTime = remember { LocalDateTime.now().plusHours(1) }
```

with:

```kotlin
    var durationMinutes by remember { mutableStateOf(30) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitDialogMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
```

Replace the deadline button and duration text field:

```kotlin
        OutlinedButton(
            onClick = {
                showDatePicker = true
                errorText = null
            },
            enabled = !submitState.formDisabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Deadline picker" }
        ) {
            Text(deadline?.formatForDisplay() ?: "No deadline selected")
        }

        OutlinedTextField(
            value = durationMinutes,
            onValueChange = {
                durationMinutes = it
                errorText = null
            },
            enabled = !submitState.formDisabled,
            label = { Text("Duration minutes") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Duration minutes input" }
        )
```

with:

```kotlin
        WheelDateTimeField(
            selection = deadline,
            onSelectionChange = {
                deadline = it
                errorText = null
            },
            label = "Deadline picker",
            enabled = !submitState.formDisabled,
            modifier = Modifier.fillMaxWidth()
        )

        WheelDurationField(
            minutes = durationMinutes,
            onMinutesChange = {
                durationMinutes = it
                errorText = null
            },
            label = "Duration minutes input",
            enabled = !submitState.formDisabled,
            modifier = Modifier.fillMaxWidth()
        )
```

Delete the entire date/time picker dialog block (originally after the `Column { ... }` closing brace, before `projectLoadDialogMessage?.let { ... }`):

```kotlin
    val initialDate = deadline?.date ?: LocalDate.now()
    val initialHour = deadline?.hour ?: defaultDateTime.hour
    val initialMinute = deadline?.minute ?: 0

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToDatePickerMillis(initialDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            pendingDate = datePickerMillisToLocalDate(selectedMillis)
                            showDatePicker = false
                            showTimePicker = true
                        }
                    },
                    enabled = !submitState.formDisabled && datePickerState.selectedDateMillis != null
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select deadline time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deadline = DateTimeSelection(
                            date = pendingDate ?: initialDate,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute
                        )
                        showTimePicker = false
                        pendingDate = null
                        errorText = null
                    },
                    enabled = !submitState.formDisabled
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        pendingDate = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

```

(delete this whole block; the `projectLoadDialogMessage?.let { ... }` and `submitDialogMessage?.let { ... }` blocks that follow it are unchanged).

Finally, in `validateAndSubmit()`, the call to `AddTaskFormValidator.validate(title, selectedProject, selectedAssignee, deadline, durationMinutes)` already matches positionally — no change needed there since `durationMinutes` is now an `Int` variable matching the validator's new `Int` parameter.

- [ ] **Step 6: Add a Compose Preview for AddTaskScreen** — skip this step. `androidApp` has no `androidx.compose.ui:ui-tooling-preview`/`ui-tooling` dependency and no existing `@Preview` usage anywhere in the module (confirmed during Task 4); adding one is out of scope here and tracked as a separate follow-up. Do not attempt to add the dependency yourself.

(Reference only, not to be added — kept here for when preview tooling is added later:)

```kotlin
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun AddTaskScreenPreview() {
    app.buzer.android.ui.theme.BuzerTheme {
        AddTaskScreen(
            onBack = {},
            loadProjects = { emptyList() },
            refreshProjects = { emptyList() },
            initialSelectedProjectId = null,
            persistSelectedProjectId = {}
        )
    }
}
```

- [ ] **Step 7: Run tests and assemble**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

Run: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidator.kt \
        client/androidApp/src/test/kotlin/app/buzer/android/ui/addtask/AddTaskFormValidatorTest.kt \
        client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/AddTaskScreen.kt
git commit -m "feat: use wheel pickers for Add Task deadline and duration"
```

---

## Task 7: Migrate DeadlineBlockerScreen's reschedule flow

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`

- [ ] **Step 1: Update imports**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt`. Remove:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.text.input.KeyboardType
import app.buzer.android.ui.datetime.datePickerMillisToLocalDate
import app.buzer.android.ui.datetime.localDateToDatePickerMillis
import java.time.LocalDate
import java.time.LocalDateTime
```

Add:

```kotlin
import app.buzer.android.ui.pickers.WheelDateTimeField
import app.buzer.android.ui.pickers.WheelDurationField
```

- [ ] **Step 2: Replace the reschedule state and form fields**

Replace this state block in `DeadlineBlockerScreen`:

```kotlin
    var rescheduleVisible by remember { mutableStateOf(false) }
    var deadline by remember { mutableStateOf<DateTimeSelection?>(null) }
    var durationText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val defaultDateTime = remember { LocalDateTime.now().plusHours(1) }
```

with:

```kotlin
    var rescheduleVisible by remember { mutableStateOf(false) }
    var deadline by remember { mutableStateOf<DateTimeSelection?>(null) }
    var durationMinutes by remember { mutableStateOf(30) }
    var errorText by remember { mutableStateOf<String?>(null) }
```

Replace the reschedule form fields and save button:

```kotlin
            OutlinedButton(
                onClick = {
                    showDatePicker = true
                    errorText = null
                },
                enabled = !actionState.actionsDisabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "New deadline picker" }
            ) {
                Text(deadline?.formatForDisplay() ?: "No deadline selected")
            }
            OutlinedTextField(
                value = durationText,
                onValueChange = {
                    durationText = it
                    errorText = null
                },
                label = { Text("Duration minutes") },
                singleLine = true,
                enabled = !actionState.actionsDisabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Duration minutes input" }
            )
            errorText?.let { Text(it) }
            LoadingActionButton(
                label = "Save reschedule",
                loadingLabel = actionState.loadingLabel ?: "Rescheduling...",
                loading = actionState is BlockerActionState.Loading,
                onClick = {
                    val selectedDeadline = deadline
                    val duration = durationText.trim().toIntOrNull()
                    when {
                        selectedDeadline == null -> errorText = "Deadline is required"
                        duration == null || duration <= 0 -> {
                            errorText = "Duration must be a positive whole number of minutes"
                        }
                        else -> {
                            onReschedule(
                                RescheduleTaskRequest(
                                    dueDatetime = selectedDeadline.toIsoInstantString(),
                                    durationMinutes = duration
                                )
                            )
                        }
                    }
                },
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
```

with:

```kotlin
            WheelDateTimeField(
                selection = deadline,
                onSelectionChange = {
                    deadline = it
                    errorText = null
                },
                label = "New deadline picker",
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
            WheelDurationField(
                minutes = durationMinutes,
                onMinutesChange = {
                    durationMinutes = it
                    errorText = null
                },
                label = "Duration minutes input",
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
            errorText?.let { Text(it) }
            LoadingActionButton(
                label = "Save reschedule",
                loadingLabel = actionState.loadingLabel ?: "Rescheduling...",
                loading = actionState is BlockerActionState.Loading,
                onClick = {
                    val selectedDeadline = deadline
                    if (selectedDeadline == null) {
                        errorText = "Deadline is required"
                    } else {
                        onReschedule(
                            RescheduleTaskRequest(
                                dueDatetime = selectedDeadline.toIsoInstantString(),
                                durationMinutes = durationMinutes
                            )
                        )
                    }
                },
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
```

- [ ] **Step 3: Delete the date/time picker dialog block**

Delete the block at the bottom of `DeadlineBlockerScreen` (after the `errorMessage?.let { ... }` block, before the function's closing `}`):

```kotlin
    val initialDate = deadline?.date ?: LocalDate.now()
    val initialHour = deadline?.hour ?: defaultDateTime.hour
    val initialMinute = deadline?.minute ?: 0

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToDatePickerMillis(initialDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            pendingDate = datePickerMillisToLocalDate(selectedMillis)
                            showDatePicker = false
                            showTimePicker = true
                        }
                    },
                    enabled = datePickerState.selectedDateMillis != null
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select deadline time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deadline = DateTimeSelection(
                            date = pendingDate ?: initialDate,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute
                        )
                        showTimePicker = false
                        pendingDate = null
                        errorText = null
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        pendingDate = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
```

- [ ] **Step 4: Run tests and assemble**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

Run: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/BlockerScreens.kt
git commit -m "feat: use wheel pickers for deadline blocker reschedule"
```

---

## Task 8: Migrate MorningReviewScreen's repair flow

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`

- [ ] **Step 1: Update imports**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`. Remove:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.text.input.KeyboardType
import app.buzer.android.ui.datetime.datePickerMillisToLocalDate
import app.buzer.android.ui.datetime.localDateToDatePickerMillis
import java.time.LocalDate
import java.time.LocalDateTime
```

Add:

```kotlin
import app.buzer.android.ui.pickers.WheelDateTimeField
import app.buzer.android.ui.pickers.WheelDurationField
```

- [ ] **Step 2: Replace the repair state and form fields**

Replace this state block:

```kotlin
    var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }
    var deadline by remember(repairingTask?.id) { mutableStateOf<DateTimeSelection?>(null) }
    var durationText by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.durationMinutes?.toString().orEmpty())
    }
    var errorText by remember(repairingTask?.id) { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val defaultDateTime = remember { LocalDateTime.now().plusHours(1) }
```

with:

```kotlin
    var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }
    var deadline by remember(repairingTask?.id) { mutableStateOf<DateTimeSelection?>(null) }
    var durationMinutes by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.durationMinutes ?: 30)
    }
    var errorText by remember(repairingTask?.id) { mutableStateOf<String?>(null) }
```

Replace the repair form fields and save button:

```kotlin
            OutlinedButton(
                onClick = {
                    showDatePicker = true
                    errorText = null
                },
                enabled = !repairing && !snoozing,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Repair deadline picker" }
            ) {
                Text(deadline?.formatForDisplay() ?: "No deadline selected")
            }
            OutlinedTextField(
                value = durationText,
                onValueChange = {
                    durationText = it
                    errorText = null
                },
                label = { Text("Duration minutes") },
                singleLine = true,
                enabled = !repairing && !snoozing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Repair duration minutes input" }
            )
            errorText?.let { Text(it) }
            LoadingActionButton(
                label = "Save repair",
                loadingLabel = "Saving...",
                loading = repairing,
                onClick = {
                    val selectedDeadline = deadline
                    val duration = durationText.trim().toIntOrNull()
                    when {
                        selectedDeadline == null -> errorText = "Deadline is required"
                        duration == null || duration <= 0 -> {
                            errorText = "Duration must be a positive whole number of minutes"
                        }
                        else -> {
                            onRepair(
                                task.id,
                                RescheduleTaskRequest(
                                    dueDatetime = selectedDeadline.toIsoInstantString(),
                                    durationMinutes = duration
                                )
                            )
                        }
                    }
                },
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            )
```

with:

```kotlin
            WheelDateTimeField(
                selection = deadline,
                onSelectionChange = {
                    deadline = it
                    errorText = null
                },
                label = "Repair deadline picker",
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            )
            WheelDurationField(
                minutes = durationMinutes,
                onMinutesChange = {
                    durationMinutes = it
                    errorText = null
                },
                label = "Repair duration minutes input",
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            )
            errorText?.let { Text(it) }
            LoadingActionButton(
                label = "Save repair",
                loadingLabel = "Saving...",
                loading = repairing,
                onClick = {
                    val selectedDeadline = deadline
                    if (selectedDeadline == null) {
                        errorText = "Deadline is required"
                    } else {
                        onRepair(
                            task.id,
                            RescheduleTaskRequest(
                                dueDatetime = selectedDeadline.toIsoInstantString(),
                                durationMinutes = durationMinutes
                            )
                        )
                    }
                },
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            )
```

- [ ] **Step 3: Delete the date/time picker dialog block**

Delete the block at the bottom of `MorningReviewScreen` (after the `actionErrorMessage?.let { ... }` block, before the function's closing `}`) — same shape as Task 7 Step 3, referencing this file's `deadline`/`defaultDateTime`/`showDatePicker`/`showTimePicker`/`pendingDate` names:

```kotlin
    val initialDate = deadline?.date ?: LocalDate.now()
    val initialHour = deadline?.hour ?: defaultDateTime.hour
    val initialMinute = deadline?.minute ?: 0

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToDatePickerMillis(initialDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            pendingDate = datePickerMillisToLocalDate(selectedMillis)
                            showDatePicker = false
                            showTimePicker = true
                        }
                    },
                    enabled = datePickerState.selectedDateMillis != null
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select deadline time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deadline = DateTimeSelection(
                            date = pendingDate ?: initialDate,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute
                        )
                        showTimePicker = false
                        pendingDate = null
                        errorText = null
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        pendingDate = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
```

- [ ] **Step 4: Run tests and assemble**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

Run: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt
git commit -m "feat: use wheel pickers for Morning Review repair"
```

---

## Task 9: Delete dead millis-conversion helpers

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt`

- [ ] **Step 1: Confirm nothing else references the helpers**

Run: `grep -rn "datePickerMillisToLocalDate\|localDateToDatePickerMillis" client/androidApp/src`
Expected: no matches outside `DateTimeSelection.kt` and `DateTimeSelectionTest.kt` (Tasks 6-8 removed the only call sites).

- [ ] **Step 2: Delete the helpers**

Edit `client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt`. Replace the whole file:

```kotlin
package app.buzer.android.ui.datetime

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DateTimeSelection(
    val date: LocalDate,
    val hour: Int,
    val minute: Int
) {
    fun toIsoInstantString(zoneId: ZoneId = ZoneId.systemDefault()): String =
        date.atTime(hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toString()

    fun formatForDisplay(): String =
        date.atTime(hour, minute).format(displayFormatter)
}

private val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
```

- [ ] **Step 3: Delete the obsolete test**

Edit `client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt`. Remove the `convertsDatePickerUtcMillisToLocalDateWithoutDeviceTimezoneShift` test and the now-unused `ZoneId`-for-UTC-conversion import usage that only it needed (keep `import java.time.ZoneId` since `convertsLocalSelectionToIsoInstant` still uses it). The full updated file:

```kotlin
package app.buzer.android.ui.datetime

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DateTimeSelectionTest {
    @Test
    fun convertsLocalSelectionToIsoInstant() {
        val selection = DateTimeSelection(
            date = LocalDate.of(2026, 6, 2),
            hour = 18,
            minute = 30
        )

        assertEquals(
            "2026-06-02T16:30:00Z",
            selection.toIsoInstantString(ZoneId.of("Europe/Warsaw"))
        )
    }

    @Test
    fun formatsLocalSelectionForDisplay() {
        val selection = DateTimeSelection(
            date = LocalDate.of(2026, 6, 2),
            hour = 8,
            minute = 5
        )

        assertEquals("2026-06-02 08:05", selection.formatForDisplay())
    }
}
```

- [ ] **Step 4: Run tests and assemble**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`.

Run: `cd client && ./gradlew :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/datetime/DateTimeSelection.kt \
        client/androidApp/src/test/kotlin/app/buzer/android/ui/datetime/DateTimeSelectionTest.kt
git commit -m "refactor: delete dead millis-conversion helpers"
```

---

## Task 10: Full verification, device install, and handoff

**Files:**
- Create: `docs/handoffs/2026-07-01-android-shared-picker-components-and-theme.md`

- [ ] **Step 1: Run the full test suite**

Run: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Point the build at a local backend over LAN**

Since the dev machine and test device are not guaranteed to be on Tailscale, but are confirmed to be on the same local network: start the backend locally (`cd backend && npm start`, or whatever the project's existing local-run command is per `docs/handoffs/2026-06-14-local-backend-e2e-testing.md`), find the Mac's LAN IP (`ipconfig getifaddr en0` on macOS Wi-Fi, or the equivalent for the active interface), and set `BUZER_BACKEND_BASE_URL=http://<mac-lan-ip>:<port>` in `client/local.properties`.

- [ ] **Step 3: Install on the connected device**

Run: `cd client && ./gradlew :androidApp:installDebug --console=plain` (or the project's existing `scripts/adb-verify.sh install` helper if the device serial needs to be specified).
Expected: install succeeds.

- [ ] **Step 4: Manually verify insets on all three blocker Activities**

Trigger a start blocker, a deadline blocker, and open Morning Review (via Home's "Morning review" action) on the real device. Confirm none of the three show content under the status bar or navigation bar — this was the original gap this plan closes.

- [ ] **Step 5: Manually verify the wheel pickers**

On the device: open Add Task and confirm the duration wheel (hours 0-6, minutes in 5-minute steps) and the combined date+time wheel both scroll, snap to a value, and the confirmed value appears on the field button. Trigger a deadline blocker's Reschedule flow and Morning Review's Repair flow and confirm the same for each.

- [ ] **Step 6: Write the handoff**

Create `docs/handoffs/2026-07-01-android-shared-picker-components-and-theme.md` following the template in `docs/handoffs/README.md`, recording: the plan reference, files changed per task, every verification command and result (including the RED/GREEN evidence from Tasks 4-6), the manual insets/wheel-picker verification results from Steps 4-5, and any open issues (for example, if `WheelTextPicker`/`WheelDateTimePicker`'s actual required parameters differed from what Tasks 4-5 assumed, record what changed and why).

- [ ] **Step 7: Commit the handoff**

```bash
git add docs/handoffs/2026-07-01-android-shared-picker-components-and-theme.md
git commit -m "docs: record shared picker components and theme verification"
```

- [ ] **Step 8: Push the branch**

Run: `git push`
Expected: push succeeds to `origin/feature/project-aware-task-creation`.

---

## Plan Self-Review

Spec coverage:

- Dependency + spike: Task 1.
- `WheelDurationField` (hour/minute, 5-min step, 0-guard): Task 4.
- `WheelDateTimeField` (combined wheel, `DateTimeSelection` mapping): Task 5.
- `AddTaskScreen`/`DeadlineBlockerScreen`/`MorningReviewScreen` migration: Tasks 6-8.
- `setBuzerContent` shared entry point fixing the 3-Activity inset gap: Task 3.
- `ui/theme` (`Spacing`, `BuzerTheme`): Task 2.
- Dead millis-helper cleanup: Task 9.
- Compose `@Preview`s for device-free verification: Tasks 4, 5, 6.
- LAN-based manual verification given the Tailscale constraint: Task 10.

Placeholder scan: no TBD/TODO markers; every code step shows complete code; the two spots calling out third-party-library parameter uncertainty (Tasks 4 and 5) give a concrete fallback command and a concrete reasonable default rather than leaving it open-ended.

Type consistency: `WheelDurationField(minutes: Int, onMinutesChange: (Int) -> Unit, ...)` and `WheelDateTimeField(selection: DateTimeSelection?, onSelectionChange: (DateTimeSelection) -> Unit, ...)` are defined in Tasks 4-5 and used with the same names/types in Tasks 6-8. `AddTaskFormValidator.validate`'s `durationMinutes: Int` parameter (Task 6) matches how `AddTaskScreen` calls it. `setBuzerContent` (Task 3) is defined once and used identically in all four Activities.
