# Latest Stable Dependency Migration Design

Date: 2026-06-07

## Goal

Move the project to the latest stable dependency/toolchain versions that are compatible with the MVP architecture, including the Android Gradle Plugin 9 / Kotlin Multiplatform build migration required by the current Android toolchain.

This is a planning-only design. It does not authorize editing dependency files until an implementation plan is approved.

## Stable-Version Policy

Use stable releases only:

- Include normal releases and patch releases, for example `9.2.1`, `2.4.0`, `1.11.1`.
- Exclude `alpha`, `beta`, `rc`, `dev`, `M`, and compatibility-line artifacts unless explicitly required to resolve a verified breakage.
- Prefer exact versions for app/tooling pins so upgrades are reviewable and reproducible.
- For Node packages, avoid `"latest"` ranges. The lockfile should not be the only place where the chosen version is visible.
- Node dependency choices must match the deployed runtime. The Dockerfile currently uses Node 22, so Node types should target Node 22 rather than the local shell's Node 25.

## Research Sources

- Android Gradle Plugin 9.2 release notes: AGP 9.2 supports API level 36.1 and requires Gradle 9.4.1 or newer.
- Google Maven metadata: latest stable AGP artifact is `9.2.1`; later `9.3.0-alpha*` artifacts are excluded.
- Kotlin AGP 9 migration guide: KMP with AGP 9 is not compatible with the legacy `org.jetbrains.kotlin.multiplatform` plus `com.android.library` pairing.
- Android KMP library plugin docs: `com.android.kotlin.multiplatform.library` is the supported plugin for Android targets in KMP library modules, and test source sets/configuration are explicit.
- Kotlin release docs and Maven metadata: latest stable Kotlin is `2.4.0`.
- Compose Multiplatform compatibility docs and Maven metadata: latest stable Compose Multiplatform is `1.11.1`, mapped to Jetpack Compose `1.11.2`; `1.12.0-alpha01` is excluded.
- AndroidX release notes and Google Maven metadata: Activity Compose `1.13.0`, Compose UI Test `1.11.2`, Lifecycle Runtime KTX `2.10.0`, Espresso `3.7.0`.
- Maven metadata: Ktor `3.5.0`, kotlinx serialization JSON `1.11.0`, kotlinx datetime ordinary stable `0.8.0`, Kermit `2.1.0`.
- npm registry metadata: backend/fake-backend direct dependency latest versions and Node engine constraints.

## Current Project Shape

The client is a Gradle multi-module project:

- `client/settings.gradle.kts` includes `:shared` and `:androidApp`.
- `client/shared/build.gradle.kts` uses `org.jetbrains.kotlin.multiplatform` plus `com.android.library`, which is the deprecated pairing that AGP 9 migration targets.
- `client/shared/src` already uses only `commonMain` and `commonTest`; there are no `src/main`, `src/test`, or `src/androidTest` sources to move in the shared module.
- `client/androidApp/build.gradle.kts` is a normal Android application module and should remain the Android entry point.
- Android instrumentation tests live in `client/androidApp/src/androidTest`, not the shared module.

The backend surfaces are:

- `backend/package.json` uses `"latest"` for most direct dependencies and relies on `package-lock.json` for actual resolved versions.
- `tools/fake-backend/package.json` uses semver ranges.
- `Dockerfile` uses `node:22-alpine`, so Node 22 is the deployment runtime unless changed in a separate decision.

## Target Version Matrix

### Android / KMP Client

| Dependency | Current | Target |
|---|---:|---:|
| Gradle wrapper | `8.14.3` | `9.5.1` |
| Android Gradle Plugin | `8.7.0` | `9.2.1` |
| Android KMP library plugin | not used | `9.2.1` |
| Kotlin plugins | `2.3.0` | `2.4.0` |
| Compose Multiplatform plugin/artifacts | `1.7.3` | `1.11.1` |
| AndroidX Compose UI test | `1.7.6` | `1.11.2` |
| AndroidX Activity Compose | `1.9.3` | `1.13.0` |
| AndroidX Lifecycle Runtime KTX | `2.8.7` | `2.10.0` |
| AndroidX Test core/runner/rules/ext-junit | current | keep current |
| Espresso core | transitive `3.5.0` observed | explicitly add `3.7.0` |
| Ktor client artifacts | `3.4.1` | `3.5.0` |
| kotlinx serialization JSON | `1.10.0` | `1.11.0` |
| kotlinx datetime | `0.7.1` | `0.8.0` |
| Kermit | `2.1.0` | keep current |
| JUnit 4 | `4.13.2` | keep current |

Do not treat Gradle's alpha/RC suggestions as targets:

- AGP `9.3.0-alpha*` is excluded.
- Compose Multiplatform `1.12.0-alpha01` is excluded.
- Compose UI `1.12.0-alpha03` is excluded.
- Lifecycle `2.11.0-rc01` is excluded.
- Gradle `9.6.0-rc-1` is excluded.

### Backend

The backend should replace `"latest"` ranges with explicit direct dependency versions:

| Dependency | Target |
|---|---:|
| `@fastify/sensible` | `6.0.4` |
| `better-sqlite3` | `12.10.0` |
| `dotenv` | `17.4.2` |
| `fastify` | `5.8.5` |
| `qrcode-terminal` | `0.12.0` |
| `whatsapp-web.js` | `1.34.7` |
| `zod` | `4.4.3` |
| `@types/better-sqlite3` | `7.6.13` |
| `@types/node` | `22.19.20` |
| `tsx` | `4.22.4` |
| `typescript` | `6.0.3` |
| `vitest` | `4.1.8` |

Add `engines.node` to document the supported backend runtime:

```json
"engines": {
  "node": "22.x"
}
```

### Fake Backend

Align the fake backend with the same runtime policy:

| Dependency | Target |
|---|---:|
| `@fastify/cors` | `11.2.0` |
| `fastify` | `5.8.5` |
| `@types/node` | `22.19.20` |
| `tsx` | `4.22.4` |
| `typescript` | `6.0.3` |

## Migration Strategy

Implement in small commits with verification between each step:

1. Establish version policy and tooling hygiene.
2. Upgrade Gradle wrapper to `9.5.1`.
3. Upgrade AGP to `9.2.1`, upgrade Kotlin to `2.4.0`, and migrate `:shared` from `com.android.library` to `com.android.kotlin.multiplatform.library` in one build-migration step. The Android-KMP plugin requires AGP 8.10.0 or newer, so this cannot be tested while staying on AGP 8.7.0.
4. Upgrade Compose Multiplatform and Compose UI test artifacts together.
5. Upgrade Ktor/kotlinx/AndroidX runtime libraries.
6. Add explicit Espresso `3.7.0` and verify Android 16 connected instrumentation no longer fails in Espresso internals.
7. Verify emulator and connected-device instrumentation after the Android client dependency set is coherent.
8. Replace backend `"latest"` ranges with explicit versions and refresh the backend lockfile.
9. Upgrade fake-backend dependencies and refresh its lockfile.
10. Run full JVM, Android managed-device, connected-device, backend, and fake-backend verification.

## Expected Build Changes

`client/gradle/libs.versions.toml` will need:

- `agp = "9.2.1"`
- new `androidKmpLibrary = "9.2.1"` plugin alias for `com.android.kotlin.multiplatform.library`
- `kotlin = "2.4.0"`
- `composeMultiplatform = "1.11.1"`
- `composeUiTest = "1.11.2"`
- `activityCompose = "1.13.0"`
- `lifecycleRuntimeKtx = "2.10.0"`
- `kotlinxDatetime = "0.8.0"`
- `kotlinxSerialization = "1.11.0"`
- `ktor = "3.5.0"`
- new `androidxEspressoCore = "3.7.0"`

`client/shared/build.gradle.kts` should:

- apply `org.jetbrains.kotlin.multiplatform`
- apply `com.android.kotlin.multiplatform.library`
- stop applying `com.android.library`
- configure the Android target using the new KMP Android plugin DSL
- keep JVM target `17`
- keep shared dependencies in `commonMain` and `commonTest`
- keep Android-specific Ktor engine dependency in the Android source set

The shared module has no Android tests, Java sources, manifest, resources, build types, or flavors today, so the migration should not require source moves or device-test source-set renames there.

`client/androidApp/build.gradle.kts` should remain the Android application module and should keep:

- app namespace/application id
- `buildConfig` values
- managed device configuration
- current Android app source sets
- instrumentation runner and fake-backend instrumentation arguments

## Verification Strategy

Before implementation, record the current baseline:

```bash
cd client
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebugAndroidTest
```

After Android/KMP migration:

```bash
cd client
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
```

For emulator instrumentation, start the fake backend and run:

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

For Android 16 connected-device verification:

```bash
adb reverse tcp:18433 tcp:18433
cd client
./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://127.0.0.1:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://127.0.0.1:18433
```

The connected-device suite must not fail with `NoSuchMethodException: android.hardware.input.InputManager.getInstance []`. If tests fail after the dependency upgrade, failures must be app/test assertions rather than AndroidX/Espresso infrastructure failures.

Backend verification:

```bash
cd backend
npm ci
npm run typecheck
npm test
npm run build
```

Fake-backend verification:

```bash
cd tools/fake-backend
npm ci
npm test
npm run build
```

## Risk Gates

Stop and reassess if any of these occur:

- AGP 9.2.1 cannot configure the shared module with the new Android-KMP plugin.
- Kotlin 2.4.0 and Compose Multiplatform 1.11.1 are incompatible in this project.
- Compose UI test 1.11.2 changes test APIs enough to require broad test rewrites.
- Explicit Espresso 3.7.0 does not remove the Android 16 test-harness failure.
- TypeScript 6.0.3 introduces non-trivial backend/fake-backend type changes.
- `better-sqlite3` native install/build fails in Docker or local Node 22.

## Out Of Scope

- Live Todoist, WhatsApp, or VPS destructive verification.
- Changing the backend deployment runtime away from Node 22.
- Adopting alpha, beta, RC, or dev dependencies.
- Public product behavior changes.
- Broad refactors unrelated to dependency migration.
