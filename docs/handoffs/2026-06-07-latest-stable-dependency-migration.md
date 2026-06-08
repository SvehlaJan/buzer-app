# Handoff: Latest Stable Dependency Migration

Date: 2026-06-08

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md`

Status: In progress. Task 1 baseline verification completed before dependency changes.

Attempted plan steps:

- Task 1 Step 1: completed
- Task 1 Step 2: completed
- Task 1 Step 3: completed
- Task 1 Step 4: completed
- Task 1 Step 5: completed
- Task 1 Step 6: completed before implementation worktree creation by committing planning docs on `master`
- Task 2 Step 1: completed
- Task 2 Step 2: completed
- Task 2 Step 3: completed
- Task 2 Step 4: completed
- Task 2 Step 5: completed
- Task 3 Step 1: completed
- Task 3 Step 2: completed
- Task 3 Step 3: completed
- Task 3 Step 4: completed
- Task 3 Step 5: completed
- Task 3 Step 6: completed
- Task 3 Step 7: completed

## Files Changed

- `docs/handoffs/2026-06-07-latest-stable-dependency-migration.md`
- `docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md`
- `client/gradle/wrapper/gradle-wrapper.jar`
- `client/gradle/wrapper/gradle-wrapper.properties`
- `client/gradlew`
- `client/gradlew.bat`
- `client/gradle/libs.versions.toml`
- `client/build.gradle.kts`
- `client/shared/build.gradle.kts`
- `client/androidApp/build.gradle.kts`

## Verification

- Command: `git status --short`
- Result: PASS
- Evidence: implementation worktree was clean before baseline commands.

- Command: `cd client && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 22s`; 83 actionable tasks executed. Gradle emitted the expected pre-existing AGP/KMP deprecation warning.

- Command: `cd backend && npm ci`
- Result: PASS
- Evidence: 344 packages installed. npm emitted existing deprecation warnings for transitive packages including `prebuild-install`, `glob`, and `fluent-ffmpeg`.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: Vitest reported `13 passed (13)` test files and `35 passed (35)` tests.

- Command: `cd backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` exited 0.

- Command: `cd tools/fake-backend && npm ci`
- Result: PASS
- Evidence: 59 packages installed.

- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: Node test runner reported `tests 11`, `pass 11`, `fail 0`.

- Command: `cd tools/fake-backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.json` exited 0.

- Command: `cd client && ./gradlew wrapper --gradle-version=9.5.1`
- Result: PASS
- Evidence: first wrapper run completed with `BUILD SUCCESSFUL in 1s`.

- Command: `cd client && ./gradlew wrapper --gradle-version=9.5.1`
- Result: PASS
- Evidence: second wrapper run downloaded `gradle-9.5.1-bin.zip`, regenerated wrapper files, and completed with `BUILD SUCCESSFUL in 2m 56s`.

- Command: `cd client && ./gradlew --version`
- Result: PASS
- Evidence: output included `Gradle 9.5.1`.

- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 4m 25s`; 68 actionable tasks executed. The expected pre-existing AGP/KMP deprecation warning remains until Task 3.

- Command: `npx ctx7@latest library "Kotlin Multiplatform" "AGP 9 com.android.kotlin.multiplatform.library shared module build.gradle.kts android target sourceSets withHostTest DSL"`
- Result: PASS
- Evidence: resolved official Android Kotlin Multiplatform documentation as `/websites/developer_android_kotlin_multiplatform`.

- Command: `npx ctx7@latest docs "/websites/developer_android_kotlin_multiplatform" "com.android.kotlin.multiplatform.library build.gradle.kts android target compileSdk minSdk withHostTest Kotlin Multiplatform AGP 9 migration sourceSets"`
- Result: PASS
- Evidence: docs confirmed the `com.android.kotlin.multiplatform.library` plugin, AGP `8.10.0+` and KGP `2.0.0+` prerequisites, Android-KMP `kotlin { android { ... } }` DSL, `withHostTest`, and Android source-set configuration.

- Command: `cd client && ./gradlew :shared:tasks --all`
- Result: FAIL
- Evidence: AGP 9 rejected the explicit `org.jetbrains.kotlin.android` plugin in `androidApp`; error stated the Kotlin Android plugin is no longer required and should be removed when using AGP 9 built-in Kotlin support.

- Command: `cd client && ./gradlew :shared:tasks --all`
- Result: PASS
- Evidence: after removing the app Kotlin Android plugin, Gradle configured successfully and listed `testAndroidHostTest - Run unit tests for the androidMain build`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest`
- Result: PASS
- Evidence: shared Android host tests passed. AGP 9 installed Android SDK Build Tools `36.0.0` during this verification.

- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`
- Result: FAIL
- Evidence: app unit-test compilation failed with unresolved `kotlin.test.Test` references after AGP 9 built-in Kotlin removed the prior Kotlin Android plugin behavior.

- Command: `cd client && ./gradlew :androidApp:dependencyInsight --configuration debugUnitTestCompileClasspath --dependency kotlin-test`
- Result: PASS
- Evidence: debug unit-test compile classpath contained plain `org.jetbrains.kotlin:kotlin-test:2.4.0`; adding `org.jetbrains.kotlin:kotlin-test-junit` supplied the JUnit-backed `kotlin.test` adapter needed by the app unit tests.

- Command: `cd client && ./gradlew --stop`
- Result: PASS
- Evidence: stopped the running Gradle daemon before sequential re-verification to avoid Kotlin incremental-cache contention seen during parallel Gradle runs.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 3m 32s`; 86 actionable tasks, with no Kotlin daemon cache-contention warning in the sequential run.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: resumed-session recheck completed with `BUILD SUCCESSFUL in 1s`; 86 actionable tasks, 1 executed and 85 up-to-date.

## Manual Verification

- None.

## Known Failures Or Skipped Checks

- No checks skipped in Task 1, Task 2, or Task 3.
- Task 3 had two expected migration failures that were fixed before commit: AGP 9 rejected the app Kotlin Android plugin, and app JVM tests required `kotlin-test-junit`.
- Parallel Gradle invocations in the same worktree caused Kotlin daemon incremental-cache contention warnings; sequential Gradle verification passed cleanly.

## Assumptions

- Node 22 remains the backend deployment runtime.
- No live Todoist, WhatsApp, VPS, `.env`, local database, or `client/local.properties` data should be touched during this migration.

## Next Recommended Step

Commit Task 3, then proceed to Task 4: upgrade Compose, AndroidX, Ktor, and Kotlinx dependencies.
