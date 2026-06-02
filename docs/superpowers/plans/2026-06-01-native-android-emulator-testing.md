# Native Android Emulator Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace the ADB coordinate-driven emulator workflow with native Android instrumentation tests that run through Gradle Managed Devices against the host fake backend.

**Architecture:** Keep `tools/fake-backend` as the HTTP integration boundary and have Android instrumentation tests talk to it through `http://10.0.2.2:18433`. Use AndroidX Test, ActivityScenario, Compose UI testing, and Gradle Managed Devices instead of host-side taps or layout parsing.

**Tech Stack:** Kotlin Multiplatform / Compose Multiplatform Android app, AndroidX Test, AndroidJUnitRunner, Compose UI Test, Gradle Managed Devices, Node.js/TypeScript Fastify fake backend.

---

## Reference Documents

- Product spec: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Native testing design: `docs/superpowers/specs/2026-06-01-native-android-emulator-testing-design.md`
- Research note: `docs/notes/2026-05-31-android-emulator-testing-research.md`
- Android testing skill inspection: `docs/notes/2026-05-31-android-testing-skill-inspection.md`

## Tooling Sources

- AndroidX Test setup: `https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/test-setup`
- AndroidJUnitRunner: `https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner`
- Compose UI testing: `https://developer.android.com/develop/ui/compose/testing`
- Gradle Managed Devices: `https://developer.android.com/studio/test/gradle-managed-devices`
- UI Automator, later only: `https://developer.android.com/training/testing/other-components/ui-automator`

## Target File Structure

- Keep: `tools/fake-backend/**`
  - Host fake backend used by native Android instrumentation tests.
- Delete: `tools/android/**`
  - Removed ADB coordinate-driven scripts; do not recreate them in this plan.
- Modify: `client/gradle/libs.versions.toml`
  - Add AndroidX Test and Compose UI test dependency aliases.
- Modify: `client/androidApp/build.gradle.kts`
  - Add `testInstrumentationRunner`.
  - Add managed device `pixel8Api34`.
  - Add Android test dependencies.
  - Continue using Gradle properties to generate fake-backend `BuildConfig` values for the app under test.
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
  - Tiny test-side HTTP client for `/__reset` and `/__calls`.
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
  - Native Compose/instrumentation tests for start and deadline blocker actions.
- Create or update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
  - Human commands and safety rules.
- Create or update: `docs/handoffs/YYYY-MM-DD-native-android-emulator-testing.md`
  - Session handoff with verification.

---

### Task 1: Confirm Cleanup And Fake Backend Baseline

**Files:**
- Verify deleted: `tools/android/emulator-smoke.sh`
- Verify deleted: `tools/android/blocker-actions-smoke.sh`
- Verify deleted: `tools/android/lib/emulator-common.sh`
- Verify existing: `tools/fake-backend/package.json`
- Verify existing: `tools/fake-backend/src/server.ts`
- Verify existing: `tools/fake-backend/src/server.test.ts`

- [x] **Step 1: Verify ADB scripts are gone**

Run:

```bash
if [ -d tools/android ]; then find tools/android -maxdepth 3 -type f -print; fi
```

Expected: command prints nothing, or `tools/android` does not exist.

- [x] **Step 2: Verify fake backend tests**

Run:

```bash
cd tools/fake-backend && npm test
```

Expected: all fake backend tests pass.

- [x] **Step 3: Verify fake backend build**

Run:

```bash
cd tools/fake-backend && npm run build
```

Expected: `tsc -p tsconfig.json` succeeds.

---

### Task 2: Add Android Test Dependencies

**Files:**
- Modify: `client/gradle/libs.versions.toml`
- Modify: `client/androidApp/build.gradle.kts`

- [x] **Step 1: Inspect existing version catalog**

Run:

```bash
sed -n '1,220p' client/gradle/libs.versions.toml
```

Expected: existing AndroidX, Kotlin, Compose, and test dependency aliases are visible.

- [x] **Step 2: Add version aliases**

In `client/gradle/libs.versions.toml`, add versions if missing:

```toml
androidx-test-core = "1.7.0"
androidx-test-ext-junit = "1.3.0"
androidx-test-runner = "1.7.0"
androidx-test-rules = "1.7.0"
```

Use the latest versions already present in the catalog if this repository already pins newer AndroidX Test versions.

- [x] **Step 3: Add library aliases**

In `client/gradle/libs.versions.toml`, add:

```toml
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test-core" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidx-test-ext-junit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidx-test-runner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidx-test-rules" }
```

- [x] **Step 4: Add Android test dependencies**

In `client/androidApp/build.gradle.kts`, add:

```kotlin
androidTestImplementation(libs.androidx.test.core)
androidTestImplementation(libs.androidx.test.ext.junit)
androidTestImplementation(libs.androidx.test.runner)
androidTestImplementation(libs.androidx.test.rules)
androidTestImplementation(compose.uiTestJUnit4)
debugImplementation(compose.uiTestManifest)
```

- [x] **Step 5: Verify dependency resolution**

Run:

```bash
cd client && ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Configure AndroidJUnitRunner And Managed Device

**Files:**
- Modify: `client/androidApp/build.gradle.kts`

- [x] **Step 1: Add test runner**

Inside `android.defaultConfig`, add:

```kotlin
testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```

- [x] **Step 2: Confirm app backend config still comes from Gradle properties**

Confirm `client/androidApp/build.gradle.kts` still contains:

```kotlin
buildConfigField("String", "BUZER_BACKEND_BASE_URL", "\"${backendConfigValue("BUZER_BACKEND_BASE_URL")}\"")
buildConfigField("String", "BUZER_BACKEND_TOKEN", "\"${backendConfigValue("BUZER_BACKEND_TOKEN")}\"")
```

Do not add AndroidX Test imports or instrumentation argument reads to production `main` code.

- [x] **Step 3: Add managed device**

Inside `android.testOptions`, keep `unitTests.isIncludeAndroidResources = true` and add:

```kotlin
managedDevices {
    localDevices {
        create("pixel8Api34") {
            device = "Pixel 8"
            apiLevel = 34
            systemImageSource = "google"
        }
    }
}
```

- [x] **Step 4: Verify Gradle sees the managed-device task**

Run:

```bash
cd client && ./gradlew tasks --all | rg 'pixel8Api34.*DebugAndroidTest|pixel8Api34DebugAndroidTest'
```

Expected: output includes `pixel8Api34DebugAndroidTest`.

---

### Task 4: Add Test-Side Fake Backend URL Argument

**Files:**
- Modify: `client/androidApp/build.gradle.kts`

- [x] **Step 1: Add test helper base URL argument**

Inside `android.defaultConfig`, add:

```kotlin
testInstrumentationRunnerArguments["FAKE_BACKEND_BASE_URL"] =
    providers.gradleProperty("FAKE_BACKEND_BASE_URL").orNull ?: "http://10.0.2.2:18433"
```

This value is for `androidTest` helper code only. The app under test still uses `BUZER_BACKEND_BASE_URL` and `BUZER_BACKEND_TOKEN` through `BuildConfig`.

- [x] **Step 2: Verify debug build**

Run:

```bash
cd client && ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 5: Add Fake Backend Test Client

**Files:**
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`

- [x] **Step 1: Create client**

Create `FakeBackendClient.kt`:

```kotlin
package app.buzer.android.blockers

import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL

class FakeBackendClient(
    private val baseUrl: String = InstrumentationRegistry.getArguments()
        .getString("FAKE_BACKEND_BASE_URL", "http://10.0.2.2:18433")
) {
    fun resetCalls() {
        request("POST", "/__reset")
    }

    fun calls(): String = request("GET", "/__calls")

    fun assertRecorded(path: String) {
        val body = calls()
        check(body.contains("\"path\":\"$path\"")) {
            "Expected fake backend call $path, got $body"
        }
    }

    private fun request(method: String, path: String): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) {
                "$method $path failed with HTTP $status: $body"
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}
```

- [x] **Step 2: Verify test APK compile**

Run:

```bash
cd client && ./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Add Blocker Activity Instrumentation Tests

**Files:**
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Create test file**

Create `BlockerActivityFakeBackendTest.kt`:

```kotlin
package app.buzer.android.blockers

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.enforcement.AlarmIntentFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockerActivityFakeBackendTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Test
    fun startBlocker_startingCallsFakeBackend() {
        fakeBackend.resetCalls()

        launchStartBlocker("task-start")

        composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
        composeRule.onNodeWithText("Postpone 5 min").assertIsDisplayed()
        composeRule.onNodeWithText("I'm starting").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                fakeBackend.calls().contains("/tasks/task-start/start")
            }.getOrDefault(false)
        }
        fakeBackend.assertRecorded("/tasks/task-start/start")
    }

    @Test
    fun deadlineBlocker_doneCallsFakeBackend() {
        fakeBackend.resetCalls()

        launchDeadlineBlocker("task-deadline")

        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Extend 30 min").assertIsDisplayed()
        composeRule.onNodeWithText("Reschedule").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                fakeBackend.calls().contains("/tasks/task-deadline/done")
            }.getOrDefault(false)
        }
        fakeBackend.assertRecorded("/tasks/task-deadline/done")
    }

    private fun launchStartBlocker(taskId: String) {
        ActivityScenario.launch<StartBlockerActivity>(
            Intent(ApplicationProvider.getApplicationContext(), StartBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            }
        )
    }

    private fun launchDeadlineBlocker(taskId: String) {
        ActivityScenario.launch<DeadlineBlockerActivity>(
            Intent(ApplicationProvider.getApplicationContext(), DeadlineBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            }
        )
    }
}
```

- [x] **Step 2: Verify compile**

Run:

```bash
cd client && ./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 7: Run Native Android Tests Against Fake Backend

**Files:**
- Modify as needed based on test failures from Task 6.
- Create or update: `docs/handoffs/YYYY-MM-DD-native-android-emulator-testing.md`

- [x] **Step 1: Start fake backend**

Run in a separate terminal:

```bash
cd tools/fake-backend && PORT=18433 npm run dev
```

Expected:

```text
fake backend listening on 18433
```

- [x] **Step 2: Run managed-device tests**

Run:

```bash
cd client && ./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: `BUILD SUCCESSFUL` and test report path in Gradle output.

- [x] **Step 3: Connected-emulator fallback was not needed**

Run:

```bash
cd client && ./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: `BUILD SUCCESSFUL`.

Only use this as a fallback. The primary workflow is Gradle Managed Devices. This session did not need the fallback because `pixel8Api34DebugAndroidTest` passed.

- [x] **Step 4: Record results in handoff**

The handoff must include:

```text
Verification:
- Command: cd tools/fake-backend && npm test
- Result: PASS/FAIL/SKIPPED
- Evidence:

- Command: cd client && ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
- Result: PASS/FAIL/SKIPPED
- Evidence:

- Command: cd client && ./gradlew pixel8Api34DebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
- Result: PASS/FAIL/SKIPPED
- Evidence:
```

---

### Task 8: Update Documentation And AGENTS.md

**Files:**
- Create or update: `docs/notes/2026-06-01-native-android-emulator-testing.md`
- Modify: `AGENTS.md`

- [x] **Step 1: Write workflow note**

Create `docs/notes/2026-06-01-native-android-emulator-testing.md`:

````markdown
# Native Android Emulator Testing

Date: 2026-06-01

## Fake Backend

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

## Native Android Tests

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

## Fallback Connected Emulator

```bash
cd client
./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

## Rules

- Use native Android instrumentation tests for blocker UI/action verification.
- Keep fake backend action tests off live Todoist and WhatsApp.
- Do not use host-side coordinate taps as the primary test workflow.
- Use UI Automator later only for system UI behavior.
````

- [x] **Step 2: Update AGENTS.md**

Under `## Testing Expectations`, add:

```markdown
Android enforcement agents should prefer native Android instrumentation tests over host-side ADB scripts. For blocker activity/action changes, start `tools/fake-backend` locally and run `pixel8Api34DebugAndroidTest` with `BUZER_BACKEND_BASE_URL=http://10.0.2.2:18433`, `BUZER_BACKEND_TOKEN=test-token`, and `FAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`. Do not use live Todoist or WhatsApp for destructive action tests.
```

- [x] **Step 3: Verify no ADB coordinate scripts remain**

Run:

```bash
if [ -d tools/android ]; then find tools/android -maxdepth 3 -type f -print; fi
```

Expected: no files.

---

## Plan Self-Review

- Spec coverage: covers native Android test dependencies, AndroidJUnitRunner, Gradle Managed Devices, host fake backend integration, blocker action tests, docs, and cleanup of ADB scripts.
- Safety coverage: destructive actions only hit fake backend.
- Deferred scope: UI Automator, exact alarms, boot receiver, notifications, full-screen lockscreen behavior, widgets, and CI are intentionally later tasks.
- Known risk: Gradle Managed Devices may need local SDK image downloads on the first run.
