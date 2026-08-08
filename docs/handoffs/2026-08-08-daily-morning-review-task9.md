# Daily Morning Review Task 9 handoff

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`, Task 9. Plan checkboxes intentionally unchanged (controller-owned).

Branch/worktree: `feature/daily-morning-review` / `/Users/svehla/playground/buzer-app/.worktrees/daily-morning-review`.

## Attempted scope

- Step 1: migrated the real-backend repair assertion so repairing the final invalid task proves the Review stays open and reaches Summary with `Start day`.
- Step 2: existing host instrumentation already covers global Snooze, no-data Start, summary transition, overdue controls, and Home-versus-Automated Back affordance. Added deterministic fixture projections for clean, invalid, overdue, and today Review scenarios.
- Step 3: added `MorningReviewAlarmInstrumentationTest` covering manifest action registration, repeated reconciliation's persisted identity, stale-delivery ignore/current-consume behavior, and locally persisted snooze alarm state.

## Files changed

- `backend/test/fakes/e2eFixtures.ts`
- `backend/test/fakes/e2eFixtures.test.ts`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/morningreview/MorningReviewAlarmInstrumentationTest.kt`
- this handoff

## Coverage mapping

- `e2eMorningReviewScenarioSeed`: named clean/task-free, invalid owner, overdue, and today fixture projections derived from the Change 1 seed.
- `BlockerActivityFakeBackendTest.morningReview_repairCallsFakeBackend`: last Repair transitions to Summary without destroying the Review host.
- `MorningReviewRepairHostTest` (pre-existing Task 6 coverage): global Snooze, no-data Start, overdue actionability/Start enabled, Home versus Automated Back, and repair-to-Summary.
- `MorningReviewAlarmInstrumentationTest`: manifest reconciliation broadcasts; repeated reconcile identity; stale/current occurrence path; persisted Morning Review Snooze scheduling.

## Verification

Verification:
- Command: `npm test -- e2eFixtures.test.ts`
- Result: PASS
- Evidence: 14 tests passed, including the new named scenario projection test.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: backend TypeScript typecheck completed with no errors.

Verification:
- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: Android instrumentation sources, including the new alarm class, compile successfully.

Verification:
- Command: `ANDROID_SERIAL=emulator-5554 ANDROID_TEST_CLASS=app.buzer.android.morningreview.MorningReviewAlarmInstrumentationTest scripts/e2e-real-backend.sh`
- Result: FAIL
- Evidence: Gradle reached `:androidApp:connectedDebugAndroidTest`, then reported failing tests without writing individual result XML/report pages. The attached emulator had residual app alarms from prior broad test runs.

Verification:
- Command: `ANDROID_SERIAL=emulator-5554 ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest scripts/e2e-real-backend.sh`
- Result: FAIL
- Evidence: same connected-test infrastructure failure mode; Gradle reported test failures but did not preserve per-test detail in this worktree's connected results directory.

Verification:
- Command: `BUZER_DEVICE_SERIAL=emulator-5554 scripts/adb-verify.sh alarms`
- Result: FAIL
- Evidence: concise app-scoped output showed many stale `app.buzer.android` alarm entries, so it cannot prove the one-Morning-Review-alarm invariant for this run.

## Known blockers / next step

The scoped device runner needs a clean emulator/app state and usable connected-test result output before Task 9 can be considered complete. In particular, the remaining full-flow cases (early acknowledgement/catch-up, priority deferral, new-invalid banner reopening, and draft preservation) require deterministic server scenario selection at `backend/test/e2eServer.ts` or an equivalent test-only configuration seam; that file is outside this task's ownership, so no production/server change was made. Re-run the two mandated scoped commands after clearing the emulator's test package/data and expose selected named fixture scenarios through the E2E server, then extend the real-backend class accordingly.

## Phase A correction

- `POST /__reset` now accepts validated `clean`, `invalid`, `overdue`, and `today` Morning Review fixture projections.
- Android reset clears Morning Review state/snapshot/notifications and API-34 app alarms before choosing a named scenario.
- Alarm instrumentation checks manifest registration with flags `0`, stale/current notification behavior, and stable `nextAlarmClock` replacement.
- Ordinary blocker-test reset acknowledges the current local date after clearing state, so post-08:00 catch-up cannot obscure unrelated Home/direct-blocker tests. Named Morning Review scenarios remain deliberately unacknowledged.
- GMD follow-up migrated repaired-task assertions from title disappearance to absent attention + visible `Start day`; failure-action injection now starts after initial Review load; failure-dialog Back uses the activity dispatcher.
- Invalid-review E2E now asserts the stable disabled global Start control instead of removed `unresolved` copy.

## Phase B enabling seam

- Added internal `MorningReviewRuntimeClock`, defaulting to `Instant.now()` and `ZoneId.systemDefault()`; it is used by MainActivity, Morning Review activity/receiver, and alarm handler dependencies.
- Both owned Android instrumentation fixtures clear the override as part of reset/cleanup.

## Phase B1 coverage

- `morningReview_cleanAndNoDataStartAcknowledgeAndClose`: clean Saturday and no-data Start acknowledgement.
- `morningReview_overdueRowsAreActionableWhileStartIsEnabled`: overdue summary action + enabled Start.
- `morningReview_automatedBackStaysWhileHomeBackCloses`: explicit source-sensitive Back behavior.
- `morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSync`: cached invalid/forced-sync Retry authority.
- `morningReview_snoozeIsFiveMinutesAndSuppressesCatchUpBeforeExpiry`: fixed-clock Snooze duration and pre-expiry Home suppression.
- `morningReview_pre08HomeAcknowledgementRemovesBannerAndPreventsPost08CatchUp`: early Home entry acknowledgement and post-08 suppression.
- `morningReview_post08AppOpenLaunchesOnceThenReturnsHomeWithoutRelaunch`: automated catch-up one-shot and terminal return.
- `morningReview_ownerBlockingMixDisablesStartButUnassignedRefreshEnablesIt`: test-only owner invalid/pending versus unassigned scenario projection.
- `acknowledgedDayNewInvalidSyncRestoresHomeBannerWithoutAutomatedLaunch`: same-day local acknowledgement with post-sync invalid banner reopening.
- `cachedStartPriorityDefersReviewThenClearedCacheAllowsRetryDelivery`: real coordinator SnapshotStore priority defer/retry notification path.
- `cachedStartAndDeadlinePriorityDeferReviewThenClearedCacheAllowsRetryDelivery`: both Start and Deadline fixture authorities.
- `automatedReviewStartPreservesDirtyAddDraft`: automated terminal Start over a typed Add draft.
- `automatedReviewSnoozePreservesDirtyEditDraft`: automated Snooze over a typed Edit draft.

## C1 corrections

- Unconditional blocker-test teardown now resets clock, backend controls, local cache/state, notifications, and app alarms.
- Added `pendingOnly` fixture and owner-pending Start-disabled coverage; stale delivery now asserts no Morning Review notification by ID or title.
- Refresh retry now switches to clean authority and asserts retry clearing/enabled Start; overdue Done asserts exact mutation and retained Start; early acknowledgement/Snooze assert Home remains visible and no same-day Review state.
- Repeated-reconcile alarm instrumentation now parses only API34 active alarm output before Stats/history and fails explicitly unless it finds exactly one Morning Review URI identity.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: production and Android instrumentation Kotlin compilation succeeded.

Verification:
- Command: `npm test -- e2eServer.test.ts e2eFixtures.test.ts` and `npm run typecheck`
- Result: PASS
- Evidence: 40 focused backend tests and TypeScript typecheck passed.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: Android instrumentation sources compiled.

Verification:
- Command: `env -u ANDROID_SERIAL ANDROID_TEST_CLASS=app.buzer.android.morningreview.MorningReviewAlarmInstrumentationTest scripts/e2e-real-backend.sh`
- Result: INCOMPLETE
- Evidence: managed-device build/package completed, but no test-result tail was emitted before the command channel returned; inspect GMD output before relying on it.
