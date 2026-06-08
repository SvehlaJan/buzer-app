# Warning Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove actionable deprecation warnings introduced or exposed by the latest-stable dependency migration, and document warnings that are not directly actionable in this repo.

**Architecture:** Keep this as a small Android-first cleanup after the dependency migration. Fix source-owned warnings first: Compose Gradle dependency notation and Compose UI test v2 APIs. Treat npm transitive deprecations, local Node engine warnings, and Android native-strip output as documentation/monitoring unless they begin failing builds.

**Tech Stack:** Gradle 9.5.1, AGP 9.2.1, Kotlin 2.4.0, Compose Multiplatform 1.11.1, Compose UI Test 1.11.2, Node 22.

---

## Source Context

- Migration handoff: `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`
- Dependency migration plan: `docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md`
- Upstream docs checked:
  - JetBrains Compose Multiplatform docs via Context7: `/jetbrains/compose-multiplatform`
  - Android Compose UI testing v2 migration docs via Context7: `/websites/developer_android_develop_ui_compose_testing`

## Task 1: Replace Deprecated Compose Gradle Dependency Accessors

**Files:**
- Modify: `client/gradle/libs.versions.toml`
- Modify: `client/androidApp/build.gradle.kts`

- [x] **Step 1: Add explicit Compose dependency aliases**

In `client/gradle/libs.versions.toml`, add aliases for the modules currently reached through deprecated plugin accessors:

```toml
compose-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "composeMultiplatform" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version = "1.9.0" }
compose-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "composeMultiplatform" }
compose-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "composeMultiplatform" }
```

Note: `org.jetbrains.compose.material3:material3` currently resolves to `1.9.0` through `compose.material3`, not `1.11.1`. Do not blindly tie Material3 to `composeMultiplatform` unless dependency resolution proves that version exists and matches the current artifact line.

- [x] **Step 2: Replace deprecated accessors**

In `client/androidApp/build.gradle.kts`, replace:

```kotlin
implementation(compose.foundation)
implementation(compose.material3)
implementation(compose.runtime)
implementation(compose.ui)
```

with:

```kotlin
implementation(libs.compose.foundation)
implementation(libs.compose.material3)
implementation(libs.compose.runtime)
implementation(libs.compose.ui)
```

- [x] **Step 3: Verify warning removal**

Run:

```bash
cd client
./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL` and no warnings for deprecated `compose.foundation`, `compose.material3`, `compose.runtime`, or `compose.ui` accessors.

- [x] **Step 4: Commit**

```bash
git add client/gradle/libs.versions.toml client/androidApp/build.gradle.kts docs/superpowers/plans/2026-06-08-warning-cleanup.md
git commit -m "build: replace deprecated Compose dependency accessors"
```

## Task 2: Migrate Compose Instrumentation Tests To v2 Rule API

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`

- [x] **Step 1: Change imports**

In both files, replace:

```kotlin
import androidx.compose.ui.test.junit4.createEmptyComposeRule
```

with:

```kotlin
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
```

- [x] **Step 2: Run instrumentation tests on emulator**

Start the fake backend:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Run:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: all 17 instrumentation tests pass. If tests fail with timing/synchronization symptoms, do not add sleeps first; use the Compose v2 guidance and synchronize through Compose test APIs such as `waitUntil`/clock advancement where needed.

- [x] **Step 3: Stop fake backend and verify port**

```bash
lsof -iTCP:18433 -sTCP:LISTEN -n -P
```

Expected after stopping the server: no output.

- [x] **Step 4: Run physical connected smoke if a device is available**

If a device is connected:

```bash
adb reverse tcp:18433 tcp:18433
cd client
./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://127.0.0.1:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://127.0.0.1:18433
adb reverse --remove tcp:18433
```

Expected: connected tests pass. Record API level in the handoff.

- [x] **Step 5: Commit**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt docs/superpowers/plans/2026-06-08-warning-cleanup.md
git commit -m "test: migrate Compose instrumentation tests to v2 rule"
```

## Task 3: Document Non-Actionable Warnings

**Files:**
- Create: `docs/notes/2026-06-08-remaining-dependency-warnings.md`

- [ ] **Step 1: Create warning note**

Create `docs/notes/2026-06-08-remaining-dependency-warnings.md`:

```markdown
# Remaining Dependency Warnings

Date: 2026-06-08

## Node engine warnings

`npm install` and `npm ci` warn with `EBADENGINE` on local Node 25 because both Node projects intentionally declare:

```json
"engines": {
  "node": "22.x"
}
```

This is expected on machines running Node 25. Use Node 22 for backend/fake-backend development and CI.

## npm transitive deprecations

Backend install currently reports transitive deprecations for:

- `prebuild-install`
- `glob`
- `fluent-ffmpeg`

These are not direct dependencies. Revisit when the direct package that brings them updates or if audit/build output starts failing.

## Android native strip warning

AGP reports that `libandroidx.graphics.path.so` cannot be stripped and is packaged as-is. This appears to come from upgraded AndroidX/Compose transitive native artifacts and is non-fatal. Revisit only if release packaging, APK size, or Play upload validation becomes a problem.

## Android 16 connected harness

The physical connected run passed on Pixel 5 / Android 14 / API 34. Android 16 / API 36 remains unverified until an API 36 physical device is available.
```

- [ ] **Step 2: Commit**

```bash
git add docs/notes/2026-06-08-remaining-dependency-warnings.md docs/superpowers/plans/2026-06-08-warning-cleanup.md
git commit -m "docs: document remaining dependency warnings"
```

## Final Verification

- [x] **Step 1: Run Android warning cleanup verification**

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL` and no deprecated Compose accessor or v1 Compose UI test warnings.

- [x] **Step 2: Run emulator instrumentation**

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest --rerun-tasks \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Expected: all instrumentation tests pass.

- [x] **Step 3: Final status**

```bash
git status --short
```

Expected: clean after final commit.
