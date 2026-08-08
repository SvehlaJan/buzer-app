# Daily Morning Review final handoff

Date: 2026-08-08

Plan: `docs/superpowers/plans/2026-08-02-daily-morning-review.md`

Branch/worktree: `feature/daily-morning-review` / `/Users/svehla/playground/buzer-app/.worktrees/daily-morning-review`

## Status

Tasks 1–10 and every plan step are complete. The feature was fast-forward merged into local `master` on 2026-08-08 after fresh client and backend verification. The feature worktree and branch were removed. Local `master` has not been pushed.

## Delivered behavior

- Morning Review is local Android authority. The client no longer calls `dismissMorningReview` or `/review/dismiss`; the backend compatibility route remains for old APKs.
- Review state durably stores today's acknowledgement, an optional current-date Snooze, and zero or one scheduled alarm identity.
- Daily review is scheduled at local 08:00 with local-calendar/DST-safe calculations. App-open after 08:00 performs one catch-up launch when today is unacknowledged.
- Early Home acknowledgement removes today's banner and suppresses today's alarm/catch-up. A newly discovered invalid or pending owner task can restore only the Home banner after acknowledgement.
- One global `Snooze 5 min` action closes Review, persists locally, schedules one alarm approximately five minutes ahead, and suppresses catch-up until expiry.
- Deadline and Start blockers outrank Morning Review. A blocked review is replaced with a one-minute priority retry rather than lost.
- The scheduler owns at most one Morning Review PendingIntent, replaces stale identities, uses `setAlarmClock`, and reconciles on boot, package replacement, manual time change, and timezone change.
- Stale alarm occurrences are ignored. A current occurrence is consumed, the next daily occurrence is reconciled, and the Morning Review full-screen notification/activity path is used.
- Missing/revoked exact-alarm permission and a permission-revocation race degrade reconciliation without crashing Home or a system receiver; other failures still propagate.
- Review force-syncs through `/tasks/sync`, keeps cached authority on failure, and exposes Retry. With no cache, `Start day without data` still acknowledges locally.
- Invalid and reconciliation-pending owner tasks block Start and offer repair/retry. Overdue and unassigned tasks do not block Start. Overdue rows remain actionable.
- Home is the only manual entry surface. Home entry permits Back; automated entry blocks Back.
- Automated Review preserves dirty Add/Edit drafts and returns to the exact draft after Start or Snooze.
- Start/Snooze resolve one fresh atomic instant-and-zone snapshot, so a timezone change while Review is open acknowledges or snoozes the correct current local date.
- Weekends use the same flow; there is no weekday special case.
- Home and Morning Review share `OwnerAgendaSelector`, preserving Change 1 Home classification and ordering.

## Key implementation files

Production:

- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewLocalState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduleCalculator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewScheduler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewAlarmHandler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewDependencies.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewContract.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewEntryDecider.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewReconcileReceiver.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewReconcileGuard.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewRuntimeClock.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/morningreview/MorningReviewSessionState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/agenda/OwnerAgendaSelector.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreen.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/MorningReviewScreenState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreenState.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
- `client/androidApp/src/main/AndroidManifest.xml`
- client dismiss cleanup in `EnforcementApi.kt`, `EnforcementCoordinator.kt`, `BlockerAction.kt`, and shared `BuzerApiClient.kt`

Tests and E2E authority:

- JVM suites under `client/androidApp/src/test/kotlin/app/buzer/android/morningreview/`
- selector/Home/Review state tests under `client/androidApp/src/test/kotlin/app/buzer/android/ui/`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/morningreview/MorningReviewAlarmInstrumentationTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/ui/screens/MorningReviewRepairHostTest.kt`
- Add/Edit and task-editor instrumentation isolation corrections
- `backend/test/e2eServer.ts`, `backend/test/e2eServer.test.ts`, and named fixtures in `backend/test/fakes/e2eFixtures*`

## Important implementation decisions

- `MorningReviewRuntimeClock` is an internal instrumentation seam only. Production defaults to `Instant.now()` and `ZoneId.systemDefault()`.
- Acknowledgement and Snooze use synchronous SharedPreferences commit so Review does not close before local authority is durable.
- Alarm occurrence keys are opaque and include reason, local review date, and trigger epoch. Consumers compare typed stored identity rather than parsing arbitrary keys.
- Cached blocker priority is conservative: absent/corrupt cache allows Review; only valid unfinished owner work with a usable occurrence key can defer it.
- The E2E server's named Morning Review scenarios are test-only projections of the real Change 1 fixture model. No backend review state or product endpoint was added.
- Ordinary Home instrumentation explicitly acknowledges today. Morning Review scenarios deliberately reset to unacknowledged state. This prevents test order from changing production catch-up behavior.

## Verification

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: complete shared/Android JVM verification and debug APK assembly succeeded.

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 32 backend files and 406 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript completed without errors.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: backend production build and schema copy completed.

Verification:
- Command: `env -u ANDROID_SERIAL ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: 43/43 tests passed on managed `pixel8Api34` in 3m05s.

Verification:
- Command: `env -u ANDROID_SERIAL ANDROID_TEST_CLASS='app.buzer.android.morningreview.MorningReviewAlarmInstrumentationTest' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: 5/5 tests passed on managed `pixel8Api34` in 44s after direct notification-permission setup.

Verification:
- Command: `env -u ANDROID_SERIAL ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest,app.buzer.android.addtask.EditTaskFakeBackendTest' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: 13/13 editor flows passed after ordinary Home resets acknowledged today.

Verification:
- Command: `env -u ANDROID_SERIAL ANDROID_TEST_CLASS='app.buzer.android.ui.screens.MorningReviewRepairHostTest' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: 8/8 Compose host tests passed after stable lazy-list selectors and one-content state transitions.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests '*MorningReviewReconcileGuardTest'`
- Result: PASS
- Evidence: denied permission skips/reports, a `SecurityException` race is contained/reported, permitted reconciliation runs, and non-permission failures still escape.

Verification:
- Command: `env -u ANDROID_SERIAL ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest#morningReview_startUsesCurrentZoneAfterTimezoneChangesWhileOpen' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: Start acknowledged 2026-08-09 after the open Activity moved atomically from the Bratislava 2026-08-08 local date to the Kiritimati 2026-08-09 local date.

Verification:
- Command: `env -u ANDROID_SERIAL scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: final unfiltered run passed 93/93 tests on managed `pixel8Api34`; build successful in 5m06s.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

Verification:
- Command: `rg -n 'dismissMorningReview|/review/dismiss' client --glob '!**/build/**'`
- Result: PASS
- Evidence: no client references found.

Verification:
- Command: `scripts/adb-verify.sh alarms`
- Result: SKIPPED
- Evidence: no persistent connected device remained after managed-device execution. The managed alarm class directly verified `nextAlarmClock` and exactly one active app-owned Morning Review identity from the API 34 active alarm section.

Verification:
- Command: `gh run list --workflow=ci.yml --limit 1`
- Result: SKIPPED
- Evidence: the feature branch was not pushed, so no push-triggered CI run exists for these commits.

## Test isolation corrections learned during final verification

- Add/Edit E2E resets now clear cached snapshot authority, restore the runtime clock, and acknowledge today before launching ordinary Home.
- Compose host tests use one `setContent` per method and mutate observed state instead of replacing content.
- Lazy lists are scrolled before accessing off-screen semantics; selectors use stable tags/content descriptions rather than duplicate visible text.
- The large-font test restores resource configuration in `finally`, preventing font scale from leaking into later classes.
- Notification permission is granted through `UiAutomation.grantRuntimePermission` and verified enabled before alarm notification assertions.

## Known failures, skips, and next step

- No known product, JVM, backend, or instrumentation failures remain.
- Manual persistent-device alarm inspection was skipped; managed-device instrumentation is the recorded alarm evidence.
- CI was not triggered because the branch was not pushed.
- No Change 3 behavior was implemented: no readiness gating, first-unlock catch-up, same-day critical-invalid notification, repeating alarm, server review state, or completed-history feature.
- Integration verification: local `master` was updated with `git pull --ff-only`, fast-forwarded to the feature tip, and passed the complete shared/Android JVM checks, debug APK assembly, and all 406 backend tests.
- Recommended next step: push `master`, confirm `ci.yml`, then redeploy the backend so production exposes the `/tasks/sync` endpoint used by Morning Review.
