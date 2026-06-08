# Handoff: API 36 Verification And Migration Merge

Date: 2026-06-08

## Task / Plan

Task: Run the connected instrumentation suite on the new Android 16 / API 36 emulator, audit remaining build warnings (mainly deprecations), then merge the latest-stable dependency migration to `master`.

Plan: `docs/superpowers/plans/2026-06-07-latest-stable-dependency-migration.md` (Task 6 / Task 10) plus the warning-cleanup follow-up in `docs/superpowers/plans/2026-06-08-warning-cleanup.md`.

Status: Complete. API 36 connected instrumentation passed, the build warning audit is clean except one known non-fatal native-strip note, and branch `codex/latest-stable-dependency-migration` was fast-forward merged into `master`.

## Files Changed

- `docs/handoffs/2026-06-08-api36-verification-and-merge.md`
- (merge) fast-forwarded `master` from `6c8e2b0` to `647122d`; no source edits made in this session.

## Commands Run

Verification:
- Command: `/Users/svehla/Library/Android/sdk/emulator/emulator @Buzer_API_36 -no-window -no-snapshot -no-audio -gpu swiftshader_indirect -port 5584`
- Result: PASS
- Evidence: emulator booted; `adb -s emulator-5584` reported `sys.boot_completed=1`, `ro.build.version.sdk=36`, `ro.build.version.release=16`.

Verification:
- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --warning-mode all --rerun-tasks --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 5s`; 101 actionable tasks, 101 executed (no `FROM-CACHE`, build cache not enabled in `client/gradle.properties`). No Kotlin (`w:`) or Gradle deprecation warnings. Only non-task output was the known `Unable to strip ... libandroidx.graphics.path.so` note.

Verification:
- Command: `cd client && ANDROID_SERIAL=emulator-5584 ANDROID_HOME=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:connectedDebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Result: PASS
- Evidence: `Starting 17 tests on Buzer_API_36(AVD) - 16` ... `Finished 17 tests`; `BUILD SUCCESSFUL in 56s`; 0 skipped, 0 failed. No `NoSuchMethodException: android.hardware.input.InputManager.getInstance`.

Verification:
- Command: `git merge --ff-only codex/latest-stable-dependency-migration`
- Result: PASS
- Evidence: `Updating 6c8e2b0..647122d`, fast-forward; `master` now at `647122d`. `client/gradle/wrapper/gradle-wrapper.properties` shows Gradle `9.5.1`; `libs.versions.toml` shows agp `9.2.1`, kotlin `2.4.0`, composeMultiplatform `1.11.1`, ktor `3.5.0`.

Verification:
- Command: `adb -s emulator-5584 emu kill` and `lsof -iTCP:18433 -sTCP:LISTEN -n -P`
- Result: PASS
- Evidence: emulator returned `OK: killing emulator, bye bye`; fake backend port closed afterward.

## Manual Verification

- Independently reran the API 36 connected suite (previously run only in the warning-cleanup session). Confirmed 17/17 pass on a freshly booted `Buzer_API_36` emulator.
- Scoped the run to the emulator via `ANDROID_SERIAL` because a physical Pixel 5 (API 34) was also attached.

## Known Failures Or Skipped Checks

- `client/local.properties` does not exist in the migration worktree; Gradle needed `ANDROID_HOME` to be exported. Left `local.properties` untouched per plan.
- Build emits the non-fatal `libandroidx.graphics.path.so` strip note (prebuilt AndroidX `.so` with no strippable symbols); not actionable in project code.
- Emulator runtime logs warn about `VulkanVirtualQueue` and Guest Angle instability for API > 35; environmental, did not affect boot or tests.
- The branch is local only and was NOT pushed to any remote (no push requested).

## Assumptions

- "Merge everything" meant fast-forwarding `master` to the migration branch HEAD (`647122d`), which includes the dependency migration plus the warning-cleanup and Android 16 emulator-setup commits.
- Three previously untracked session handoffs were committed alongside this one to leave a clean tree.

## Next Recommended Step

Decide whether to push `master`, delete the merged branch `codex/latest-stable-dependency-migration`, and remove its worktree at `.worktrees/latest-stable-dependency-migration`. Re-run the `pixel8Api34` managed-device suite if a regression baseline on API 34 is also wanted before pushing.
