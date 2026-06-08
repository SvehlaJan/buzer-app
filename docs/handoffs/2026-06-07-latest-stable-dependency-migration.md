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
- Task 4 Step 1: completed
- Task 4 Step 2: completed
- Task 4 Step 3: completed
- Task 4 Step 4: completed
- Task 4 Step 5: completed
- Task 5 Step 1: completed
- Task 5 Step 2: completed
- Task 5 Step 3: completed
- Task 5 Step 4: completed
- Task 6 Step 1: attempted, skipped because no ADB device was visible
- Task 6 Steps 2-6: skipped because Step 1 did not find a connected device
- Task 7 Step 1: completed
- Task 7 Step 2: completed
- Task 7 Step 3: completed
- Task 7 Step 4: completed, skipped because Docker is unavailable
- Task 7 Step 5: completed
- Task 8 Step 1: completed
- Task 8 Step 2: completed
- Task 8 Step 3: completed
- Task 8 Step 4: completed

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
- `backend/package.json`
- `backend/package-lock.json`
- `tools/fake-backend/package.json`
- `tools/fake-backend/package-lock.json`
- `tools/fake-backend/tsconfig.json`

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

- Command: `cd client && ./gradlew :androidApp:dependencyInsight --configuration debugAndroidTestRuntimeClasspath --dependency espresso-core`
- Result: PASS
- Evidence: resolved `androidx.test.espresso:espresso-core:3.7.0`; Gradle selected it by conflict resolution over Compose UI test's transitive `3.5.0`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest`
- Result: FAIL
- Evidence: `:androidApp:checkDebugAarMetadata` found 7 issues. Upgraded AndroidX dependencies including `androidx.activity:activity-compose:1.13.0`, `androidx.core:core:1.18.0`, and `androidx.navigationevent:navigationevent-android:1.0.0` require consumers to compile against Android API 36 or later while `androidApp` still used `compileSdk = 35`.

- Command: `npx ctx7@latest library "Android Developers" "compileSdk Android Gradle Plugin DSL application module AndroidX AAR metadata requires compile SDK 36"`
- Result: PASS
- Evidence: resolved official Android Developers documentation as `/websites/developer_android`.

- Command: `npx ctx7@latest docs "/websites/developer_android" "compileSdk Android Gradle Plugin DSL application module targetSdk minSdk compile SDK can be updated separately AAR metadata"`
- Result: PASS
- Evidence: docs show `compileSdk = 36` in the Android Gradle Plugin Kotlin DSL and describe `compileSdk` as the API level Gradle uses to compile the app, separate from `targetSdk` and `minSdk`.

- Command: `cd client && ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: after raising `compileSdk` to 36 in both Android modules and leaving app `targetSdk` at 35, Gradle reported `BUILD SUCCESSFUL in 27s`; 101 actionable tasks, 87 executed and 14 up-to-date.

- Command: `cd tools/fake-backend && PORT=18433 npm run dev`
- Result: PASS
- Evidence: fake backend printed `fake backend listening on 18433`.

- Command: `cd client && ./gradlew pixel8Api34DebugAndroidTest --rerun-tasks -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: managed device `pixel8Api34` finished 17 tests with 0 skipped and 0 failed; Gradle reported `BUILD SUCCESSFUL in 2m 4s`. Report path: `client/androidApp/build/reports/androidTests/managedDevice/debug/allDevices/index.html`.

- Command: `lsof -iTCP:18433 -sTCP:LISTEN -n -P`
- Result: PASS
- Evidence: initially showed the fake backend listener on PID `90740`, which was the server started for Task 5.

- Command: `kill 90740`
- Result: PASS
- Evidence: stopped the non-interactive fake backend process after the test run.

- Command: `lsof -iTCP:18433 -sTCP:LISTEN -n -P`
- Result: PASS
- Evidence: no output after stopping the fake backend, indicating port `18433` was closed.

- Command: `adb devices -l`
- Result: SKIPPED
- Evidence: command exited 0 but listed no devices under `List of devices attached`.

- Command: `adb shell getprop ro.build.version.sdk`
- Result: SKIPPED
- Evidence: failed with `adb: no devices/emulators found`.

- Command: `adb shell getprop ro.build.version.release`
- Result: SKIPPED
- Evidence: failed with `adb: no devices/emulators found`.

- Command: `adb wait-for-device`
- Result: SKIPPED
- Evidence: still waiting after about 60 seconds; stopped the wait command with `pkill -f "adb wait-for-device"`.

- Command: `cd backend && npm outdated --json`
- Result: PASS
- Evidence: npm queried the registry and reported backend updates for `better-sqlite3` `12.10.0`, `tsx` `4.22.4`, and `vitest` `4.1.8`; it also reported absolute latest `@types/node` major 25, which was intentionally not selected because the backend runtime is Node 22.

- Command: `cd backend && npm view @types/node@22 version --json`
- Result: PASS
- Evidence: latest available Node 22 type line ended at `22.19.20`.

- Command: `cd backend && npm install`
- Result: PASS
- Evidence: removed 2 packages, changed 21 packages, audited 343 packages, and found 0 vulnerabilities. npm emitted `EBADENGINE` because the local workstation runs Node `v25.9.0` while the project now declares `node: 22.x`.

- Command: `cd backend && npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit` exited 0.

- Command: `cd backend && npm test`
- Result: PASS
- Evidence: Vitest `4.1.8` reported `13 passed (13)` test files and `35 passed (35)` tests.

- Command: `cd backend && npm run build`
- Result: PASS
- Evidence: `tsc -p tsconfig.build.json` exited 0.

- Command: `cd backend && docker --version`
- Result: SKIPPED
- Evidence: failed with `zsh:1: command not found: docker`, so the Docker image build was not available in this environment.

- Command: `cd tools/fake-backend && npm outdated --json`
- Result: PASS
- Evidence: npm queried the registry and reported fake-backend updates for `@fastify/cors` `11.2.0`, `typescript` `6.0.3`, and Node 22 type line `@types/node` `22.19.20`; absolute latest `@types/node` major 25 was intentionally not selected because the runtime is Node 22.

- Command: `cd tools/fake-backend && npm install`
- Result: PASS
- Evidence: removed 2 packages, changed 3 packages, audited 58 packages, and found 0 vulnerabilities. npm emitted `EBADENGINE` because the local workstation runs Node `v25.9.0` while the project now declares `node: 22.x`.

- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: Node test runner reported `tests 11`, `pass 11`, `fail 0`.

- Command: `cd tools/fake-backend && npm run build`
- Result: FAIL
- Evidence: TypeScript 6 failed with `TS5011`: common source directory was `./src`, so `rootDir` must be explicitly set to adjust the `outDir` layout.

- Command: `npx ctx7@latest library "TypeScript" "TypeScript 6 TS5011 common source directory rootDir setting must be explicitly set migration"`
- Result: PASS
- Evidence: resolved official TypeScript docs as `/microsoft/typescript`.

- Command: `npx ctx7@latest docs "/microsoft/typescript/v6.0.2" "TS5011 common source directory rootDir setting must be explicitly set outDir migration TypeScript 6"`
- Result: PASS
- Evidence: docs examples show TS5011 when an `outDir` project lacks explicit `rootDir` and TypeScript infers a common source directory.

- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: after adding `rootDir = "src"`, Node test runner still reported `tests 11`, `pass 11`, `fail 0`.

- Command: `cd tools/fake-backend && npm run build`
- Result: PASS
- Evidence: after adding `rootDir = "src"`, `tsc -p tsconfig.json` exited 0.

## Manual Verification

- None.

## Known Failures Or Skipped Checks

- No checks skipped in Task 1, Task 2, or Task 3.
- Task 3 had two expected migration failures that were fixed before commit: AGP 9 rejected the app Kotlin Android plugin, and app JVM tests required `kotlin-test-junit`.
- Parallel Gradle invocations in the same worktree caused Kotlin daemon incremental-cache contention warnings; sequential Gradle verification passed cleanly.
- Task 4 had one build failure fixed before commit: upgraded AndroidX artifacts require `compileSdk >= 36`.
- Task 4 verification still emits warnings for deprecated Compose dependency accessors (`compose.foundation`, `compose.material3`, `compose.runtime`, `compose.ui`), deprecated Compose test `createEmptyComposeRule`, and an unstripped `libandroidx.graphics.path.so`; these did not fail the build.
- Task 5 managed-device instrumentation passed. The fake backend was stopped with `kill <pid>` because the original non-TTY server session did not expose stdin for `Ctrl-C`.
- Task 6 connected Android 16 harness verification was skipped because no ADB device or emulator was visible. No fake backend was started for Task 6, and no connected instrumentation command was run.
- Task 7 Docker build verification was skipped because Docker is not installed.
- Task 7 npm install was run from local Node `v25.9.0`, which produced an expected `EBADENGINE` warning after adding the intended Node 22 engine.
- Task 8 npm install was run from local Node `v25.9.0`, which produced the same expected `EBADENGINE` warning after adding the intended Node 22 engine.
- Task 8 had one TypeScript 6 migration failure fixed before commit: fake-backend `tsconfig.json` now sets `rootDir` explicitly to `src`.

## Assumptions

- Node 22 remains the backend deployment runtime.
- No live Todoist, WhatsApp, VPS, `.env`, local database, or `client/local.properties` data should be touched during this migration.

## Next Recommended Step

Proceed to Task 9: final full verification. Re-run Task 6 later when a physical Android 16 device is visible to ADB.
