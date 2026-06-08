# Latest Stable Dependency Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the Android client, backend, and fake backend to latest stable dependency/toolchain versions, including the AGP 9 Android-KMP migration required for the shared module.

**Architecture:** Keep the existing two-module Android client (`:shared`, `:androidApp`) and migrate only the shared KMP Android target from legacy `com.android.library` to `com.android.kotlin.multiplatform.library`. Keep backend deployment on Node 22 and replace dynamic Node dependency ranges with explicit stable versions. Verify every migration layer before moving to the next one.

**Tech Stack:** Gradle 9.5.1, Android Gradle Plugin 9.2.1, Kotlin 2.4.0, Compose Multiplatform 1.11.1, Jetpack Compose UI Test 1.11.2, AndroidX, Ktor 3.5.0, Node 22, TypeScript 6.0.3, Fastify 5.8.5, SQLite.

---

## Source Documents

- Spec: `docs/superpowers/specs/2026-06-07-latest-stable-dependency-migration-design.md`
- Existing audit handoff: `docs/handoffs/2026-06-07-dependency-versioning-audit.md`
- Product truth: `docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`

## Stable-Version Rules

- Do not use alpha, beta, RC, dev, or compatibility-line artifacts unless a task explicitly says to test one as a fallback.
- Do not leave Node dependencies as `"latest"`.
- Keep Node deployment runtime on Node 22.
- Do not touch live Todoist, WhatsApp, VPS credentials, `.env`, SQLite databases, or `client/local.properties`.

## Files To Modify

- `client/gradle/wrapper/gradle-wrapper.properties`: Gradle wrapper version.
- `client/gradle/libs.versions.toml`: Android/Kotlin/Compose/Ktor/AndroidX version catalog.
- `client/build.gradle.kts`: root plugin aliases, including Android-KMP library plugin.
- `client/shared/build.gradle.kts`: migrate shared module to Android-KMP plugin DSL.
- `client/androidApp/build.gradle.kts`: add explicit Espresso dependency and keep app module configuration.
- `backend/package.json`: replace `"latest"` with explicit versions and add Node 22 engine.
- `backend/package-lock.json`: refresh backend resolved dependency graph.
- `tools/fake-backend/package.json`: align explicit versions and add Node 22 engine.
- `tools/fake-backend/package-lock.json`: refresh fake-backend resolved dependency graph.
- `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`: implementation handoff.

## Task 1: Baseline Verification

**Files:**
- Read: `client/gradle/libs.versions.toml`
- Read: `backend/package.json`
- Read: `tools/fake-backend/package.json`
- Create: `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`

- [x] **Step 1: Check workspace state**

Run:

```bash
git status --short
```

Expected: only known documentation files are dirty or untracked. Do not proceed if source/build files are dirty from another agent.

- [x] **Step 2: Run client baseline**

Run:

```bash
cd client
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Run backend baseline**

Run:

```bash
cd backend
npm ci
npm run typecheck
npm test
npm run build
```

Expected: all commands pass.

- [x] **Step 4: Run fake-backend baseline**

Run:

```bash
cd tools/fake-backend
npm ci
npm test
npm run build
```

Expected: all commands pass.

- [x] **Step 5: Record baseline handoff**

Create `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md` with:

```markdown
# Handoff: Latest Stable Dependency Migration

Date: 2026-06-07

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md`

Status: In progress. Baseline verification completed before dependency changes.

## Files Changed

- `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`

## Verification

- Command: `git status --short`
- Result: PASS if exit code was 0; otherwise FAIL
- Evidence: record the dirty-state summary exactly enough to identify source/build files versus documentation-only files.

- Command: `cd client && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`
- Result: PASS if exit code was 0; otherwise FAIL
- Evidence: record `BUILD SUCCESSFUL` or the first failing task and primary error message.

- Command: `cd backend && npm ci && npm run typecheck && npm test && npm run build`
- Result: PASS if all commands exited 0; otherwise FAIL
- Evidence: record the successful script names or the first failing script and primary error message.

- Command: `cd tools/fake-backend && npm ci && npm test && npm run build`
- Result: PASS if all commands exited 0; otherwise FAIL
- Evidence: record the successful script names or the first failing script and primary error message.

## Manual Verification

- None.

## Known Failures Or Skipped Checks

- None yet.

## Assumptions

- Node 22 remains the backend deployment runtime.

## Next Recommended Step

Upgrade the Gradle wrapper.
```

- [x] **Step 6: Commit baseline docs if requested by the lead**

Run only if the lead asks for documentation commits before source edits:

```bash
git add docs/superpowers/specs/2026-06-07-latest-stable-dependency-migration-design.md docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md docs/handoffs/2026-06-07-dependency-versioning-audit.md docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "docs: plan latest stable dependency migration"
```

Expected: commit created. If other handoff docs are still untracked and unrelated, leave them untouched unless instructed.

## Task 2: Upgrade Gradle Wrapper

**Files:**
- Modify: `client/gradle/wrapper/gradle-wrapper.properties`
- Modify: `client/gradlew`
- Modify: `client/gradlew.bat`

- [x] **Step 1: Upgrade wrapper**

Run:

```bash
cd client
./gradlew wrapper --gradle-version=9.5.1
./gradlew wrapper --gradle-version=9.5.1
```

Expected: wrapper files update to Gradle `9.5.1`. Running the wrapper task twice is intentional so the second run uses the upgraded wrapper jar/scripts.

- [x] **Step 2: Verify wrapper version**

Run:

```bash
cd client
./gradlew --version
```

Expected: output includes `Gradle 9.5.1`.

- [x] **Step 3: Run a client smoke build**

Run:

```bash
cd client
./gradlew :androidApp:assembleDebugAndroidTest
```

Expected: either `BUILD SUCCESSFUL` or a failure that clearly belongs to existing AGP/KMP incompatibility. If the failure is unrelated to AGP/KMP, stop and debug before proceeding.

- [x] **Step 4: Update handoff**

Append:

```markdown
- Command: `cd client && ./gradlew wrapper --gradle-version=9.5.1 && ./gradlew wrapper --gradle-version=9.5.1`
- Result: PASS if both wrapper commands exited 0; otherwise FAIL
- Evidence: record whether wrapper files changed and the first failing line if either command failed.

- Command: `cd client && ./gradlew --version`
- Result: PASS if the command exited 0 and printed `Gradle 9.5.1`; otherwise FAIL
- Evidence: record the `Gradle 9.5.1` line.
```

- [x] **Step 5: Commit wrapper upgrade**

Run:

```bash
git add client/gradle/wrapper/gradle-wrapper.properties client/gradlew client/gradlew.bat docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "build: upgrade Gradle wrapper"
```

Expected: commit created.

## Task 3: Upgrade AGP/Kotlin And Migrate Shared Module To Android-KMP Plugin

**Files:**
- Modify: `client/gradle/libs.versions.toml`
- Modify: `client/build.gradle.kts`
- Modify: `client/shared/build.gradle.kts`
- Modify: `client/androidApp/build.gradle.kts`

- [x] **Step 1: Update AGP/Kotlin versions and add Android-KMP plugin alias**

In `client/gradle/libs.versions.toml`, update:

```toml
agp = "9.2.1"
kotlin = "2.4.0"
```

Then add this plugin alias:

```toml
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
```

Add this app unit-test adapter alias:

```toml
kotlin-test-junit = { module = "org.jetbrains.kotlin:kotlin-test-junit" }
```

Do not try to test the Android-KMP plugin on AGP `8.7.0`; the official prerequisite is AGP `8.10.0` or newer.

- [x] **Step 2: Expose the plugin at root and remove app Kotlin Android plugin**

In `client/build.gradle.kts`, add:

```kotlin
alias(libs.plugins.android.kotlin.multiplatform.library) apply false
```

Keep the existing Android application/library aliases for now.

In `client/androidApp/build.gradle.kts`, remove the explicit Kotlin Android plugin because AGP 9 has built-in Kotlin support and fails when `org.jetbrains.kotlin.android` is applied:

```kotlin
alias(libs.plugins.kotlin.android)
```

In the same file, change the app unit-test Kotlin dependency from:

```kotlin
testImplementation(libs.kotlin.test)
```

to:

```kotlin
testImplementation(libs.kotlin.test.junit)
```

The app unit tests import `kotlin.test.Test`, which requires the JUnit-backed adapter under AGP 9 built-in Kotlin.

- [x] **Step 3: Replace shared module build script**

Replace `client/shared/build.gradle.kts` with:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "app.buzer.shared"
        compileSdk = 35
        minSdk = 26

        withHostTest {
            isIncludeAndroidResources = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kermit)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.android)
        }
    }
}
```

- [x] **Step 4: Verify Gradle can list shared tasks**

Run:

```bash
cd client
./gradlew :shared:tasks --all
```

Expected: build configures successfully. The Android-KMP shared host-test task is `:shared:testAndroidHostTest`; use that task for all post-migration shared test commands.

- [x] **Step 5: Run shared tests**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest
```

Expected: tests pass.

- [x] **Step 6: Run app test/build smoke**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 7: Commit AGP/Kotlin/KMP build migration**

Run:

```bash
git add client/gradle/libs.versions.toml client/build.gradle.kts client/shared/build.gradle.kts client/androidApp/build.gradle.kts docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "build: migrate shared module to AGP 9 Android KMP"
```

Expected: commit created.

## Task 4: Upgrade Compose, AndroidX, Ktor, And Kotlinx Versions

**Files:**
- Modify: `client/gradle/libs.versions.toml`
- Modify: `client/androidApp/build.gradle.kts`
- Modify: `client/shared/build.gradle.kts`

- [x] **Step 1: Update version catalog**

Edit the remaining `client/gradle/libs.versions.toml` versions to:

```toml
[versions]
agp = "9.2.1"
activityCompose = "1.13.0"
androidxEspresso = "3.7.0"
androidxTestCore = "1.7.0"
androidxTestExt = "1.3.0"
androidxTestRunner = "1.7.0"
androidxTestRules = "1.7.0"
composeMultiplatform = "1.11.1"
composeUiTest = "1.11.2"
junit = "4.13.2"
kermit = "2.1.0"
kotlin = "2.4.0"
lifecycleRuntimeKtx = "2.10.0"
kotlinxDatetime = "0.8.0"
kotlinxSerialization = "1.11.0"
ktor = "3.5.0"
```

Add this library alias:

```toml
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "androidxEspresso" }
```

Keep existing plugin aliases, including:

```toml
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
```

Also raise Android module `compileSdk` values to `36` if required by AAR metadata from the upgraded AndroidX dependencies. Keep `targetSdk` unchanged unless a later task explicitly opts into the newer runtime behavior.

- [x] **Step 2: Add explicit Espresso dependency**

In `client/androidApp/build.gradle.kts`, add:

```kotlin
androidTestImplementation(libs.androidx.test.espresso.core)
```

Place it with the other `androidTestImplementation` dependencies.

- [x] **Step 3: Verify dependency resolution**

Run:

```bash
cd client
./gradlew :androidApp:dependencyInsight --configuration debugAndroidTestRuntimeClasspath --dependency espresso-core
```

Expected: runtime resolves `androidx.test.espresso:espresso-core:3.7.0`. If Compose UI test still forces an older version, add a strict constraint for Espresso `3.7.0` in `androidTestImplementation` and rerun this step.

- [x] **Step 4: Run client JVM/build verification**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit client dependency upgrades**

Run:

```bash
git add client/gradle/libs.versions.toml client/androidApp/build.gradle.kts client/shared/build.gradle.kts docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "build: upgrade Android client dependencies"
```

Expected: commit created.

## Task 5: Verify Emulator Instrumentation

**Files:**
- Modify: `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`

- [x] **Step 1: Start fake backend**

Run:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Expected: server prints that it is listening on `18433`. Keep it running for the next step.

- [x] **Step 2: Run managed-device tests**

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

Expected: all instrumentation tests pass on `pixel8Api34`.

- [x] **Step 3: Stop fake backend**

Stop the dev server with `Ctrl-C`, then run:

```bash
lsof -iTCP:18433 -sTCP:LISTEN -n -P
```

Expected: no output.

- [x] **Step 4: Commit emulator verification evidence**

Run:

```bash
git add docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "test: verify Android dependency migration on emulator"
```

Expected: commit created, unless no handoff change was made.

## Task 6: Verify Android 16 Connected Test Harness

**Files:**
- Modify: `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`

> Current run note: skipped on 2026-06-08 because ADB reported no connected devices and `adb wait-for-device` did not resolve after about 60 seconds. Leave the checkboxes open until a physical device is visible to ADB.

- [ ] **Step 1: Confirm physical device**

Run:

```bash
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
```

Expected: physical device is connected. Preferred target is Pixel 10 / Android 16 / API 36 if available.

- [ ] **Step 2: Start fake backend**

Run:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Expected: fake backend listening on `18433`.

- [ ] **Step 3: Configure reverse tunnel**

Run:

```bash
adb reverse tcp:18433 tcp:18433
adb reverse --list
```

Expected: output includes `tcp:18433 tcp:18433`.

- [ ] **Step 4: Run connected instrumentation**

Run:

```bash
cd client
./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://127.0.0.1:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://127.0.0.1:18433
```

Expected: the suite either passes or fails on app/test assertions. It must not fail with:

```text
NoSuchMethodException: android.hardware.input.InputManager.getInstance []
```

- [ ] **Step 5: Stop fake backend**

Stop the dev server with `Ctrl-C`, then run:

```bash
lsof -iTCP:18433 -sTCP:LISTEN -n -P
```

Expected: no output.

- [ ] **Step 6: Commit connected-test verification evidence**

Run:

```bash
git add docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "test: verify Android 16 connected test harness"
```

Expected: commit created, unless no handoff change was made.

## Task 7: Upgrade Backend Node Dependencies

**Files:**
- Modify: `backend/package.json`
- Modify: `backend/package-lock.json`

- [ ] **Step 1: Replace backend dependency ranges**

Update `backend/package.json` to use exact versions:

```json
"dependencies": {
  "@fastify/sensible": "6.0.4",
  "better-sqlite3": "12.10.0",
  "dotenv": "17.4.2",
  "fastify": "5.8.5",
  "qrcode-terminal": "0.12.0",
  "whatsapp-web.js": "1.34.7",
  "zod": "4.4.3"
},
"devDependencies": {
  "@types/better-sqlite3": "7.6.13",
  "@types/node": "22.19.20",
  "tsx": "4.22.4",
  "typescript": "6.0.3",
  "vitest": "4.1.8"
},
"engines": {
  "node": "22.x"
}
```

Keep existing scripts unchanged.

- [ ] **Step 2: Refresh backend lockfile**

Run:

```bash
cd backend
npm install
```

Expected: `package-lock.json` updates and install succeeds.

- [ ] **Step 3: Verify backend**

Run:

```bash
cd backend
npm run typecheck
npm test
npm run build
```

Expected: all commands pass.

- [ ] **Step 4: Verify Docker build if Docker is available**

Run:

```bash
docker build -t buzer-backend-dependency-audit .
```

Expected: image builds. If Docker is unavailable, mark this check `SKIPPED` in the handoff with the exact reason.

- [ ] **Step 5: Commit backend dependency upgrade**

Run:

```bash
git add backend/package.json backend/package-lock.json docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "build: upgrade backend dependencies"
```

Expected: commit created.

## Task 8: Upgrade Fake Backend Node Dependencies

**Files:**
- Modify: `tools/fake-backend/package.json`
- Modify: `tools/fake-backend/package-lock.json`

- [ ] **Step 1: Replace fake-backend dependency ranges**

Update `tools/fake-backend/package.json` to use exact versions:

```json
"dependencies": {
  "@fastify/cors": "11.2.0",
  "fastify": "5.8.5"
},
"devDependencies": {
  "@types/node": "22.19.20",
  "tsx": "4.22.4",
  "typescript": "6.0.3"
},
"engines": {
  "node": "22.x"
}
```

Keep existing scripts unchanged.

- [ ] **Step 2: Refresh fake-backend lockfile**

Run:

```bash
cd tools/fake-backend
npm install
```

Expected: `package-lock.json` updates and install succeeds.

- [ ] **Step 3: Verify fake backend**

Run:

```bash
cd tools/fake-backend
npm test
npm run build
```

Expected: all commands pass.

- [ ] **Step 4: Commit fake-backend dependency upgrade**

Run:

```bash
git add tools/fake-backend/package.json tools/fake-backend/package-lock.json docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "build: upgrade fake backend dependencies"
```

Expected: commit created.

## Task 9: Final Full Verification

**Files:**
- Modify: `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`

- [ ] **Step 1: Run full backend verification**

Run:

```bash
cd backend
npm ci
npm run typecheck
npm test
npm run build
```

Expected: all commands pass.

- [ ] **Step 2: Run full fake-backend verification**

Run:

```bash
cd tools/fake-backend
npm ci
npm test
npm run build
```

Expected: all commands pass.

- [ ] **Step 3: Run full client JVM/build verification**

Run:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run managed-device instrumentation**

Start fake backend:

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

Expected: all instrumentation tests pass.

- [ ] **Step 5: Stop fake backend**

Run:

```bash
lsof -iTCP:18433 -sTCP:LISTEN -n -P
```

Expected after stopping server: no output.

- [ ] **Step 6: Final dependency audit**

Run:

```bash
cd backend
npm outdated --json
```

Expected: either `{}` or only intentionally excluded packages. `npm outdated` exits non-zero when packages are outdated, so read the JSON.

Run:

```bash
cd tools/fake-backend
npm outdated --json
```

Expected: either `{}` or only intentionally excluded packages.

- [ ] **Step 7: Final git status**

Run:

```bash
git status --short
```

Expected: only intentional tracked changes or a clean tree after final commit.

- [ ] **Step 8: Commit final verification handoff**

Run:

```bash
git add docs/handoffs/2026-06-07-latest-stable-dependency-migration.md
git commit -m "docs: record dependency migration verification"
```

Expected: commit created, unless no handoff change was made.

## Task 10: Merge/Push Decision

**Files:**
- Read: `git log --oneline --decorate -10`
- Read: `git status --short`

- [ ] **Step 1: Review commits**

Run:

```bash
git log --oneline --decorate -10
```

Expected: migration commits are coherent and ordered.

- [ ] **Step 2: Decide integration path**

Ask the lead whether to:

```text
1. Merge to master and push.
2. Open a PR.
3. Leave the branch for review.
```

Expected: do not merge or push without explicit user approval.
