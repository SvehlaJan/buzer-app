# Handoff: Dependency Versioning Audit

Date: 2026-06-07

## Task / Plan

Task: Start a major dependency versioning audit with the goal of using the latest stable version of each dependency.

Plan: No implementation plan exists yet. This session used the brainstorming workflow for context gathering and did not edit dependency files.

Status: Planning complete. The current dependency surfaces and latest-stable candidates were identified. The user selected the full latest-stable migration including AGP 9 / KMP build migration. A design spec and implementation plan were written; dependency files were not changed.

## Files Changed

- `docs/handoffs/2026-06-07-dependency-versioning-audit.md`
- `docs/superpowers/specs/2026-06-07-latest-stable-dependency-migration-design.md`
- `docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md`

## Commands Run

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: existing untracked handoffs only before this handoff was added: `docs/handoffs/2026-06-07-project-state-assessment.md`, `docs/handoffs/2026-06-07-real-device-smoke-attempt.md`.

Verification:
- Command: `npm outdated --json` in `backend`
- Result: PASS with outdated packages
- Evidence: lockfile has updates available for `@types/node` `25.6.2 -> 25.9.2`, `better-sqlite3` `12.9.0 -> 12.10.0`, `tsx` `4.21.0 -> 4.22.4`, and `vitest` `4.1.5 -> 4.1.8`.

Verification:
- Command: `npm outdated --json` in `tools/fake-backend`
- Result: PASS with outdated packages
- Evidence: `@fastify/cors` `10.1.0 -> 11.2.0`, `@types/node` `22.19.19 -> 25.9.2`, and `typescript` `5.9.3 -> 6.0.3` were reported by npm metadata. `typescript` 6.0.3 is a major bump and needs verification.

Verification:
- Command: `npx npm-check-updates --jsonUpgraded --target latest` in `backend`
- Result: PASS
- Evidence: `{}` because backend `package.json` uses `"latest"` for most dependencies; lockfile still has stale resolved versions. This means package range policy needs cleanup.

Verification:
- Command: `npx npm-check-updates --jsonUpgraded --target latest` in `tools/fake-backend`
- Result: PASS
- Evidence: suggested `@fastify/cors ^11.2.0`, `fastify ^5.8.5`, `@types/node ^25.9.2`, `tsx ^4.22.4`, and `typescript ^6.0.3`.

Verification:
- Command: `cd client && ./gradlew dependencyUpdates -I ../tools/gradle-dependency-updates.init.gradle -Drevision=release --refresh-dependencies`
- Result: PASS after fixing the temporary init script
- Evidence: Gradle generated `client/build/dependencyUpdates/report.txt`. The temporary init script was removed after the audit.

Verification:
- Command: `npx ctx7@latest library "Android Gradle Plugin" "AGP 9.2 Kotlin Multiplatform shared Android target migration com.android.kotlin.multiplatform.library Gradle 9.4 requirements"`
- Result: PASS but not useful
- Evidence: Context7 did not resolve the official Android Gradle Plugin docs; internet search and official docs were used instead.

Verification:
- Command: `curl -fsS https://repo.maven.apache.org/maven2/org/jetbrains/compose/org.jetbrains.compose.gradle.plugin/maven-metadata.xml`
- Result: PASS
- Evidence: Maven metadata includes stable `1.11.1` and alpha `1.12.0-alpha01`; stable policy selects `1.11.1`.

Verification:
- Command: `curl -fsS https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/multiplatform/org.jetbrains.kotlin.multiplatform.gradle.plugin/maven-metadata.xml`
- Result: PASS
- Evidence: Maven metadata reports Kotlin Gradle plugin latest/release `2.4.0`.

Verification:
- Command: `curl -fsS https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml`
- Result: PASS
- Evidence: Google Maven metadata includes stable AGP `9.2.1` and alpha `9.3.0-alpha10`; stable policy selects `9.2.1`.

Verification:
- Command: `curl -fsS https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml`
- Result: PASS
- Evidence: Maven metadata includes ordinary stable `0.8.0` and compatibility-line `0.8.0-0.6.x-compat`; stable policy selects ordinary `0.8.0`.

Verification:
- Command: `curl -fsS https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml`
- Result: PASS
- Evidence: Maven metadata reports latest/release `1.11.0`.

Verification:
- Command: `curl -fsS https://repo.maven.apache.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml`
- Result: PASS
- Evidence: Maven metadata reports latest/release `3.5.0`.

Verification:
- Command: `curl -fsS https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml`
- Result: PASS
- Evidence: Google Maven metadata reports latest/release `1.13.0`.

Verification:
- Command: `curl -fsS https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/maven-metadata.xml`
- Result: PASS
- Evidence: Google Maven metadata reports `2.11.0-rc01` as latest/release because it is an RC; stable policy selects stable `2.10.0`.

Verification:
- Command: `curl -fsS https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-junit4/maven-metadata.xml`
- Result: PASS
- Evidence: Google Maven metadata reports alpha `1.12.0-alpha03` as latest/release; stable policy selects stable `1.11.2`.

Verification:
- Command: `curl -fsS https://dl.google.com/dl/android/maven2/androidx/test/espresso/espresso-core/maven-metadata.xml`
- Result: PASS
- Evidence: Google Maven metadata reports latest/release `3.7.0`.

Verification:
- Command: `npm view @types/node@22 version --json`
- Result: PASS
- Evidence: latest Node 22 type package is `22.19.20`; selected because Docker runtime is Node 22.

Verification:
- Command: `rg -n "TBD|TODO|placeholder|fill in|<[^>]+>" docs/superpowers/specs/2026-06-07-latest-stable-dependency-migration-design.md docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md`
- Result: PASS
- Evidence: no output after plan self-review.

## Internet / Documentation Checks

- Kotlin official release docs: latest stable release line includes Kotlin `2.4.0`, released June 3, 2026.
- Android Gradle Plugin official release notes: AGP `9.2.0` is the April 2026 stable release line and requires at least Gradle `9.4.1`; Google Maven metadata has stable patch `9.2.1`.
- Gradle release page: latest stable Gradle is `9.5.1`.
- JetBrains Compose Multiplatform compatibility docs and Maven metadata: `1.11.1` is the latest stable Compose Multiplatform target and maps to Jetpack Compose `1.11.2`.
- AndroidX Compose UI release notes: stable Compose UI is `1.11.2`; alpha `1.12.0-alpha03` is not considered stable.
- AndroidX Activity release notes: stable Activity / Activity Compose is `1.13.0`.
- AndroidX Lifecycle release notes: stable Lifecycle is `2.10.0`; `2.11.0-rc01` is not stable.
- AndroidX Test release notes: Espresso `3.7.0` is the current stable Espresso release and fixes the previous reflective `InputManager.getInstance` path by using `getSystemService`.
- Ktor release docs: stable Ktor is `3.5.0`.

## Preliminary Latest-Stable Candidates

Client:
- Gradle wrapper: `8.14.3 -> 9.5.1`
- Android Gradle Plugin: `8.7.0 -> 9.2.1`
- Android KMP library plugin: not used -> `9.2.1`
- Kotlin plugins/libraries: `2.3.0 -> 2.4.0`
- Compose Multiplatform plugin/runtime/material/foundation/ui: `1.7.3 -> 1.11.1`
- AndroidX Compose UI test artifacts: `1.7.6 -> 1.11.2`
- AndroidX Activity Compose: `1.9.3 -> 1.13.0`
- AndroidX Lifecycle Runtime KTX: `2.8.7 -> 2.10.0`
- Ktor client artifacts: `3.4.1 -> 3.5.0`
- kotlinx serialization JSON: `1.10.0 -> 1.11.0`
- AndroidX Test core/runner/rules/ext-junit, Kermit, and JUnit already appear current.
- `kotlinx-datetime`: `0.7.1 -> 0.8.0`; the `0.8.0-0.6.x-compat` artifact is excluded by stable-version policy unless ordinary `0.8.0` breaks.

Backend:
- Replace `"latest"` ranges with explicit stable ranges or exact pins as part of policy cleanup.
- Refresh stale lockfile resolutions for `better-sqlite3`, `tsx`, `vitest`, and `@types/node`.
- Use `@types/node@22.19.20`, not Node 25 types, because the Dockerfile deploys `node:22-alpine`.

Fake backend:
- Update Fastify CORS major `10 -> 11`.
- Align Fastify, tsx, TypeScript, and Node type versions with the backend policy after deciding whether `typescript@6.0.3` is acceptable for this project.

## Known Risks

- AGP 9 is not a routine version bump. The current KMP shared module already warns that `org.jetbrains.kotlin.multiplatform` plus `com.android.library` compatibility is deprecated and will not work with most Android Gradle Plugin 9 releases. The plan migrates the shared module to `com.android.kotlin.multiplatform.library`.
- The Android-KMP plugin requires AGP 8.10.0 or newer, so the shared-module plugin migration cannot be tested while keeping AGP at 8.7.0. The plan couples AGP `9.2.1`, Kotlin `2.4.0`, and Android-KMP plugin migration in one build-migration task.
- Compose Multiplatform `1.11.1` includes the refreshed UI testing line mapped to Jetpack Compose `1.11.2`. It may fix the Android 16 Espresso transitive mismatch, but it can also require test code adjustments.
- Node package policy is inconsistent: backend uses `"latest"` ranges, fake-backend uses semver ranges, and client uses exact catalog pins.

## Manual Verification

- No dependency files were changed.
- No live Todoist, WhatsApp, or VPS destructive checks were performed.

## Next Recommended Step

Review the written design and implementation plan:

- `docs/superpowers/specs/2026-06-07-latest-stable-dependency-migration-design.md`
- `docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md`

If approved, execute the plan in a dedicated worktree using subagent-driven development or inline executing-plans. Start with Task 1 baseline verification.
