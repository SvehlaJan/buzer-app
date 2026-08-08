# Daily Morning Review Task 7 Handoff

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md` (controller owns checkbox updates)

Task attempted: Task 7 — integrate the Home banner, early review entry, and app-open catch-up.

## Changes

- Added the pure `MorningReviewEntryDecider`, which separates Home-banner visibility from the one-shot automated launch guard.
- Extended Home's pure state input with local Morning Review visibility and owner blocking count. A clean unacknowledged day shows `Morning review ready` with `Review`; blocking repair count remains higher priority; refresh Retry remains a separate attention strip.
- Moved Morning Review launch ownership to `MainActivity`. The Activity registers `StartActivityForResult` before lifecycle use and creates Home/Automated intents through `MorningReviewContract`; Compose only invokes the supplied callback.
- Recompute/reconcile availability on creation, resume, snapshots, task mutations, and result return. A single cancellable lifecycle job waits until the calculator-provided calendar boundary; pause and every recomputation replace it. The in-flight flag prevents the creation/resume duplicate catch-up launch.

## Files changed

- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewEntryDecider.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/HomeScreenStateTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/MorningReviewEntryDeciderTest.kt`
- this handoff

## Context7 finding

Used `npx ctx7@latest library` then `docs` for AndroidX Activity Result APIs. `registerForActivityResult` must be registered before the owner reaches `STARTED`; the lifecycle registry activates callbacks and delivers pending results at `ON_START`, removes callbacks at `ON_STOP`, and unregisters at `ON_DESTROY`. Main registers its launcher as an Activity property before `onCreate` and clears its in-flight guard in that callback.

## Verification

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewEntryDeciderTest' --tests '*HomeScreenStateTest'`
- Result: RED
- Evidence: the first test-first run from `client/` failed with the expected unresolved `MorningReviewEntryDecider` and missing Home builder parameters. (An initial invocation from the worktree root only established that the Gradle wrapper resides in `client/`.)

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*HomeScreenStateTest.morningReviewBannerIsNotSuppressedWhileTheSnapshotLoads'`
- Result: RED
- Evidence: the new loading-state banner assertion failed until the state builder propagated local availability without a snapshot.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewEntryDeciderTest' --tests '*HomeScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; 4 EntryDecider tests and 22 HomeScreenState tests passed.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

---

## Follow-up correction: crossed boundary reevaluation

- `MorningReviewBoundaryTiming` now maps positive durations to a normal delay and zero/negative durations to an immediate reevaluation path.
- The one foreground-guarded lifecycle Job is now always created for a foreground availability update. For a crossed boundary or millisecond truncation it calls `yield()` before the one recomputation, avoiding synchronous recursion; calendar availability then supplies the following future boundary.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewEntryDeciderTest'`
- Result: RED then PASS
- Evidence: RED reported the missing boundary timing helper. The pure test covers positive, zero, and negative delay behavior.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewEntryDeciderTest' --tests '*HomeScreenStateTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

## Lifecycle self-review and deferred runtime check

- The launcher is registered before lifecycle use. All launches originate in Main; App/Home contain no `LocalContext.startActivity` path.
- A Home result clears the in-flight guard and recomputes. Task 5's contract/activity retains Home `Cancelled` and Automated no-Back behavior; automated completion returns only through acknowledgement/Snooze.
- The reevaluation job is only armed for a strictly future calculator boundary and is cancelled/replaced on pause and recomputation, preventing a fixed-delay or create/resume duplicate loop.
- Runtime/instrumentation verification is intentionally deferred to Tasks 9/10 as directed. No device/emulator was attached; pending command is the relevant `scripts/e2e-real-backend.sh` scoped run followed by the full unfiltered suite.

## Assumptions and next step

- Owner blocking count is derived from the existing `selectOwnerAgenda(...).blocking` authority, so Home remains independent from unassigned roots.
- Task 8 API cleanup was not touched. The controller should review this Task 7 commit, update plan checkboxes, and continue with the planned E2E migration/verification tasks.

---

## Follow-up correction: foreground gate and Snooze banner suppression

- Home review attention is now entirely gated by `morningReviewBannerVisible`. During an active Snooze, even a nonzero blocking count produces no Morning Review banner. When visibility is true, the blocking count still wins over the clean-ready message.
- Added a pure `MorningReviewEntryController` used by MainActivity. It owns foreground and in-flight state: creation begins background-safe, resume permits evaluation/launch, pause disarms, and result return clears in-flight and requests recomputation.
- Snapshot/action recomputation may still refresh the Compose availability while paused, but the controller prevents catch-up launch and Main prevents re-arming the calendar-boundary Job until foreground resumes.
- Expanded pure coverage for no create launch, resume-once catch-up, in-flight duplicate prevention, pause behavior, result recomputation, Home cancellation, midnight/08:00/Snooze transitions, and active-Snooze blocking suppression. Existing Task 5 contract tests continue to cover Home/Automated source and terminal result extras; no contract behavior changed.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewEntryDeciderTest' --tests '*HomeScreenStateTest'`
- Result: RED then PASS
- Evidence: RED reported missing `MorningReviewEntryController`; after implementation and migration of preexisting direct-attention tests to explicitly provide local banner availability, 10 EntryDecider/controller tests and 23 HomeScreenState tests passed.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.
