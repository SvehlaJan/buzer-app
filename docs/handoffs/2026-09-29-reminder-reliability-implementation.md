# Handoff: Reminder reliability implementation (Tasks 0-8)

Date: 2026-09-29
Agent: Claude (subagent-driven execution: controller Claude Opus 5.5; Task 8 docs/verification/handoff by Claude Sonnet 5.5; implementers and reviewers were Sonnet and Haiku subagents)
Plan: `docs/superpowers/plans/2026-09-29-reminder-reliability.md`
Decision: `docs/decisions/2026-09-29-reminder-reliability.md`
Branch / worktree: `feature/reminder-reliability` at `.worktrees/reminder-reliability` (created from `6e18826`)

## Scope

All of Tasks 0-8 of the plan were attempted, in order. Task 8 (this session) attempted Steps 1-8:
docs edits (Steps 1-3), full backend verification (Step 4), full client JVM verification (Step 5),
full instrumentation verification (Step 6), this handoff (Step 7), one commit (Step 8).

The five causes the decision addresses: Morning Review deferred all day by overdue tasks;
floating Todoist due times aborting the whole alarm plan (BUZER-ANDROID-E) and being read as UTC;
the review's opening forced sync hitting Todoist's top-of-the-hour 5xx spike; the boot ANR
(BUZER-ANDROID-D); a post-08:00 reconcile scheduling tomorrow's review (accepted limitation,
decision 8).

**Status: complete except one open item.** Task 8 Step 6 is NOT green: the full instrumentation
suite has 2 real failures caused by Task 6's 30 s retry (see "Open issues"). No product code was
changed to work around them.

## Checkbox status

| Task | Steps | Status |
| --- | --- | --- |
| 0 Workspace setup | 1-5 | all ticked |
| 1 Backend due-time resolver and owner timezone | 1-7 | all ticked |
| 2 Snapshot and escalation use the owner timezone | 1-8 | all ticked |
| 3 Edits compare schedules by instant | 1-5 | all ticked |
| 4 Client strict parser and per-task isolation | 1-11 | all ticked |
| 5 Morning Review priority is Start-only and capped | 1-8 | all ticked |
| 6 Morning Review sync retries once, silently | 1-5 | all ticked |
| 7 Boot runs only the cached reconcile | 1-3 | all ticked |
| 8 Docs, full verification, handoff | 1-5, 7, 8 | ticked |
| 8 | 6 (full instrumentation) | **NOT ticked**: 2 real e2e failures, see below |

## Commits (per task)

| Task | Commits |
| --- | --- |
| 0 | `439b90d` docs: record reminder reliability decision and plan |
| 1 + 2 (one unit) | `289ea7a` fix: resolve floating Todoist times in the owner's timezone; `ad98349` test: pin a non-local timezone in floating-time regression tests (fix round 1) |
| 3 | `26c6126` fix: compare edited schedules by instant so unchanged times are not rewritten; `2ae0a75` fix: keep an unchanged due time on duration-only edits (fix round 1) |
| 4 | `e288963` fix: skip and report tasks with unparseable snapshot times instead of aborting the alarm plan; `e755900` fix: report each invalid-time task once per process (fix round 1) |
| 5 | `2cd4592` fix: let only an active start blocker delay the morning review, for at most 15 minutes |
| 6 | `79be78f` fix: retry the morning review sync once and drop its system notification |
| 7 | `dd71945` fix: run only the cached reconcile at boot to stay inside the broadcast window |
| 8 | the docs commit that contains this file: `docs: record reminder reliability rollout notes and handoff` |

## Files changed

Whole branch (`git diff --stat 6e18826..dd71945`): 38 files, +2631/-154.

- Backend: `domain/time.ts` (resolver, `sameDueInstant`, tz param), new `domain/ownerTimeZone.ts`,
  `domain/taskRecurrence.ts` (export `isIanaTimeZone`), `repositories/collaboratorRepository.ts`
  (`getTimeZone`), `services/taskSnapshotService.ts`, `services/escalationService.ts`,
  `services/taskActionService.ts`, `server.ts`, and tests (`time`, `ownerTimeZone`,
  `collaboratorRepository`, `taskSnapshotService`, `escalationService`, `taskActionService`,
  `e2eFixtures` + `e2eServer.ts`).
- Shared: new `time/SnapshotInstant.kt`, `enforcement/AlarmPlanCalculator.kt` (+ tests).
- Android: new `time/SnapshotJavaInstant.kt`, `EnforcementCoordinator.kt`, `HomeScreenState.kt`,
  `OwnerAgendaSelector.kt`, `MorningReviewAlarmHandler.kt`, `MorningReviewScheduleCalculator.kt`,
  `MorningReviewScheduler.kt`, `MorningReviewSessionState.kt`, `MorningReviewActivity.kt`,
  `BootReconcileRunner.kt` (+ unit tests and `MorningReviewAlarmInstrumentationTest`).
- Task 8 commit (docs only):
  - `docs/superpowers/plans/2026-08-02-daily-morning-review.md` (priority text amended)
  - `docs/runbooks/observability-triage.md` (new §4 item 4: backend failures live in Sentry Logs)
  - `docs/notes/vps-deployment.md` (`OWNER_TIMEZONE` in the `.env` shape)
  - `docs/notes/2026-06-12-android-overlay-testing.md` (boot log expectations, controller ruling)
  - `docs/superpowers/plans/2026-09-29-reminder-reliability.md` (checkboxes + Step 6 note)
  - `docs/handoffs/2026-09-29-reminder-reliability-implementation.md` (this file)

Doc facts were checked against the code: `forced_refresh_failed` (`backend/src/http/routes.ts:216`),
`todoist_poll_failed` with `error_kind` and `consecutive_failure_count`
(`backend/src/services/todoistPoller.ts:128-131`), `backend_request_failed`
(`client/shared/.../api/BuzerApiClient.kt:186`), `OWNER_TIMEZONE` (`backend/src/domain/ownerTimeZone.ts`),
boot log lines (`BootReconcileRunner.kt:29-52`).

## Verification

Step 4 (backend), run from `backend`:

- Command: `npm test`
- Result: PASS
- Evidence: `Test Files 39 passed (39)`, `Tests 506 passed (506)` (baseline at Task 0 was 482 tests in 38 files).

- Command: `npm run typecheck`
- Result: PASS
- Evidence: `tsc -p tsconfig.json --noEmit`, exit 0, no output.

Step 5 (client JVM), run from `client` with `ANDROID_HOME`/`ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk`:

- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL` (first run: 1 s, 68 of 74 tasks up to date, so the test tasks did not actually
  execute). They were therefore re-forced with `./gradlew :shared:testAndroidHostTest --rerun :androidApp:testDebugUnitTest --rerun :androidApp:assembleDebug`
  (`BUILD SUCCESSFUL in 2s`). Result XML (timestamps from the re-run): shared 105 tests, androidApp 419 tests,
  0 failures, 0 errors, 0 skipped. `assembleDebug` executed `packageDebug` in the first run.

Step 6 (full instrumentation), from the worktree root, emulator `emulator-5554` (AVD `Buzer_API_36`, Android 16), no `ANDROID_TEST_CLASS`:

- Command: `ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ANDROID_SERIAL=emulator-5554 CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433 scripts/e2e-real-backend.sh`
- Result: **FAIL** (exit 1; 115 tests, 4 failed, 111 passed; `BUILD FAILED in 5m 14s`)
- Evidence (log: `.superpowers/sdd/2026-09-29-reminder-reliability/e2e-full.log`, not committed):
  1. `BlockerActivityFakeBackendTest.morningReview_cleanAndNoDataStartAcknowledgeAndClose`:
     `ComposeTimeoutException: Condition still not satisfied after 15000 ms` at
     `waitForText(...)` called from `BlockerActivityFakeBackendTest.kt:699`
     (`waitForText("Start day without data")` after `fakeBackend.setFailSnapshot(true)`).
  2. `BlockerActivityFakeBackendTest.morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSnapshot`:
     `ComposeTimeoutException: Condition still not satisfied after 15000 ms` at
     `waitForTag(...)` called from `BlockerActivityFakeBackendTest.kt:749`
     (`waitForTag("session-warning-banner")` after `setFailSnapshot(true)` + `scenario.recreate()`).
  3. `EditTaskFakeBackendTest.editTask_retryPreservesTheCapturedRootRequestAndDirtyExitConfirmsDiscard`:
     `Assert failed: The component with TestTag = 'session-warning-banner' is not displayed!` at
     `EditTaskFakeBackendTest.kt:110`.
  4. `ForegroundReadinessScreenTest.systemBackDoesNotDismissTheGate`:
     `RootViewWithoutFocusException: Waited for the root of the view hierarchy to have window focus ... for 10 seconds`
     at `ForegroundReadinessScreenTest.kt:79` (`Espresso.pressBack`).
- `MorningReviewRepairHostTest` and `DeadlineBlockerUpdateHostTest` (the two the plan watched for
  time-label failures) both passed; no fixture migration was needed.

Step 6 re-run to separate flakes from real failures (per controller ruling 3), the three failing classes once:

- Command: `ANDROID_HOME=... ANDROID_SDK_ROOT=... ANDROID_SERIAL=emulator-5554 CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433 ANDROID_TEST_CLASS=app.buzer.android.addtask.EditTaskFakeBackendTest,app.buzer.android.blockers.BlockerActivityFakeBackendTest,app.buzer.android.readiness.ForegroundReadinessScreenTest scripts/e2e-real-backend.sh`
- Result: FAIL (57 tests, 2 failed, 55 passed; log `e2e-rerun.log`)
- Evidence: `EditTaskFakeBackendTest` (both tests) and `ForegroundReadinessScreenTest` (all 12) PASSED, so
  failures 3 and 4 are flakes. Failures 1 and 2 reproduced with the identical assertions, so they are real.

Step 6 diagnosis of failures 1 and 2 (single test with logcat, `e2e-diag.log` / `e2e-diag-logcat.log`):

- Command: same environment, `ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest#morningReview_cleanAndNoDataStartAcknowledgeAndClose'`, with `adb -s emulator-5554 logcat -v time -s Buzer` captured beside it.
- Result: FAIL (same 15 s timeout)
- Evidence: the log shows `force_sync start` then `backend_request_failed ... route=/tasks/snapshot status_code=500` at
  23:41:16.058 and no second `force_sync start` before the test gave up 15 s later. The second attempt is
  scheduled 30 s later by `forceSyncWithRetry(FORCED_SYNC_RETRY_DELAY_MILLIS = 30_000)` (Task 6).

## Tests whose expectations changed

- Task 3 Step 4 (edit tests expecting `due.kind === "replace"` for an unchanged schedule): none. No existing
  test needed its expectation changed; the suite stayed green at 505/505 after the change.
- Other migrated suites (all listed in the plan's table): `backend/test/domain/time.test.ts` (3-arg
  `computeStartAt`/`computeDeadlineAt`); `backend/test/fakes/e2eFixtures.test.ts` (`priorityStart`/`priorityDeadline`
  scenarios removed); `BlockerPriorityResolverTest` and `MorningReviewAlarmHandlerTest` (overdue no longer
  defers; 15-minute cap); `MorningReviewAlarmInstrumentationTest` (old priority test replaced by
  `cachedOverdueTasksNeverDeferReview`). In fix rounds, the floating-time and escalation regression tests were
  pinned to `Asia/Tokyo` so they fail on old code on a CET/CEST host.
- Not migrated (missed by the plan's table): the two `BlockerActivityFakeBackendTest` morning-review tests above.

## Rulings

Controller rulings made during execution (copied verbatim from the ledger):

- Ruling 1: Controller performs Task 0 itself (npm ci, baselines, commit spec+plan) — it is workspace setup with no code — costs nothing if wrong beyond an unreviewed docs-only commit.
- Ruling 2: Tasks 1 and 2 are dispatched, reviewed and committed as one unit — the plan mandates a single commit and T1 alone leaves typecheck red — costs a larger single review surface.
- Ruling 3: Instrumentation runs use emulator-5554 (AVD Buzer_API_36, booted headless by controller with `-port 5554`); the plan's other flags follow docs/notes/2026-06-08-android-16-emulator-setup.md — if the emulator is unusable, the e2e steps are recorded as SKIPPED with the exact remaining command, not faked.
- Ruling: fix the plan-mandated escalation test (and the two floating computeStartAt/computeDeadlineAt tests) to pin Asia/Tokyo — a regression test that cannot fail on the owner's own machine is not a regression test; the spec's intent (floating = owner zone, host-independent) is better served — costs one extra fix round if wrong.
- Ruling: ⚠️ commit trailer — verified myself: `git log -1 --format=%B 289ea7a` ends with the Co-Authored-By trailer — no cost.
- Ruling: ⚠️ pre-existing `Date.parse`/`new Date` in httpTodoistAdapter.ts:569,573 accepted — it only runs for values already carrying Z/offset (floating returns at :567), so there is no timezone ambiguity; the Global Constraint targets floating misreads — costs a literal-wording breach of the constraint if the owner reads it strictly.
- Ruling: accept commit 26c6126's `Co-Authored-By: Claude Haiku 4.5` trailer, no history rewrite — it truthfully names the model that authored the commit; the plan's trailer line is attribution, not behavior — costs a trailer inconsistency the owner can reword before merge.
- Ruling: fix duration-only edits for NON-recurring tasks (due → preserve unless the resolved instant changed); leave the recurring path treating a duration change as a schedule change — spec decision 3 forbids rewriting an unchanged time, but Todoist recurring due strings can embed durations ("for 15 min"), so changing recurring behavior is riskier and out of scope — costs: recurring duration-only edits still rewrite the recurrence as before.
- Ruling: fix — move the dedupe set to a companion object (process-wide, like BlockerErrorReporter.capturedThrowables) with an internal test reset hook, and add a two-instance test — the spec says "once per app process" and the plan's instance field was the plan's own defect — costs global mutable state in tests (mitigated by the reset hook).
- Ruling: every emulator e2e run in this plan (Task 8 Step 6 included) adds `CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433` — with ANDROID_SERIAL set, scripts/e2e-real-backend.sh defaults the app to 127.0.0.1:18433, which the emulator app rejects as cleartext; the script documents this override — costs nothing (script unchanged); record in handoff.
- Ruling: Tasks 6 and 7 go to ONE implementer dispatch (two commits, one review) — both are small Android edits with complete code in their briefs and no shared files; one dispatch saves a context rebuild — costs a slightly larger review surface.
- Ruling: decision 6's "never posts the Backend action failed notification" covers the review's forced-sync failure only; MorningReviewActivity.kt:70 (coordinator create failure) and :84 (cached load failure) keep their notification — they are local configuration failures, not sync failures, and are not in the brief — costs two rare notifications if the owner meant "never" literally.
- Ruling: ⚠️ 30 s wait has no automated coverage — accepted; only a real failing backend could exercise it — costs untested timing, covered by unit tests with 1 ms delay.
- Ruling: Task 8 dispatch also updates docs/notes/2026-06-12-android-overlay-testing.md:80 (expected boot logs still list `boot_reconcile refresh=...`) — Task 7 made it stale and the checklist would mislead boot verification — costs one doc file outside Task 8's list.

Task 8 controller rulings: (1) also update the overlay-testing note (done); (2) the full e2e run must use the emulator
backend override and no class filter (done); (3) on e2e failure re-run the class once, do not fix product code, record
precisely and report DONE_WITH_CONCERNS (done; the instrumentation tests were also left untouched).

## Known limitations / deferred findings

Accepted by design (decision 8 and rulings):
- A reconcile between 08:00 and 08:15 can move the next review to tomorrow; the in-app catch-up still shows today's.
- Recurring tasks: a duration-only edit still rewrites the recurrence.
- `MorningReviewActivity.kt:70` and `:84` still post the "Backend action failed" notification (local failures, not sync).
- The 30 s retry wait has no automated timing coverage.
- **An automated review with no cached snapshot now shows Loading for at least 30 s while the retry waits**
  (no Start/Snooze, no Back during that time). Rare (needs no cache and a failing backend), but this is exactly
  the path exercised by e2e failure 1 below.

Deferred minor review findings (none block the merge):
- Backend time: `time.ts:107-117` silently normalizes out-of-range floating components instead of rejecting them,
  and a malformed non-offset value now throws inside `getSnapshot` (theoretical: the adapter guarantees shapes);
  no DST-boundary tests (gap 2026-03-29T02:30, overlap 2026-10-25T02:30; behavior was probed correct);
  a new `Intl.DateTimeFormat` per `zoneOffsetMs` call (about 4 per floating task per snapshot).
- Backend wiring/tests: `server.ts` collaborator/`ownerTimeZone` wiring has no test; `escalationService` defaults to
  `resolveOwnerTimeZone(null, "")` (empty-string sentinel); the first floating edit test depends on `OWNER_TIMEZONE`
  being unset (seed a self collaborator row to pin it); a malformed client `dueDatetime` now throws a plain
  `Error` (500) in `sameDueInstant` where `scheduleErrors` could return 422; missing tests for an unchanged fixed-UTC
  no-op, an unchanged recurring floating task and the DST boundary.
- Out of scope, pre-existing: `taskRepository.ts:237` orders by the raw `due_datetime` string, mis-sorting floating
  against `Z` values.
- Client: `selectOwnerAgenda(snapshot, now, zoneId)` keeps a now-unused `zoneId` (4 callers outside the brief); no
  test locks Home/agenda dropping offset-less rows, none covers `parseSnapshotJavaInstantOrNull`, and the planner
  test covers the `dueDatetime` path but not `startAt`; cosmetic import order in `HomeScreenState`.
- Morning Review: `MorningReviewDependencies.kt:17` still hardcodes `LocalTime.of(8, 0)` for the calculator while the
  handler uses `DEFAULT_MORNING_REVIEW_TIME`; no cap test with a non-UTC zone or non-default `reviewTime`;
  `overdueTaskNeverDefersReview` ends exactly at `now` (boundary, not clearly overdue) and the early half of the cap
  test does not assert the Defer call; inconsistent KDoc decision references; no on-device coverage of the Start-defer
  path (the JVM scheduler test covers it).
- Retry: the first sync failure leaves no `AppLog` breadcrumb, so masked Todoist 5xx spikes are invisible in Sentry
  Logs; test gaps for the happy path (1 call, no delay), `refreshInFlight` during the wait and cancellation during
  the delay; style nits in `MorningReviewSessionState.kt` (inverted null-means-success helper, missing blank lines).

## Open issues

1. **Two real instrumentation failures (Task 8 Step 6 red).** Cause: Task 6 made the review's failed forced sync wait
   30 s before the second attempt, and the inline warning / "Start day without data" only appear after that second
   failure. Both tests wait 15 s. The suite was last recorded fully green on 2026-09-02 (115/115 at `59ed5c4`, Level 1 branch).
   The plan's "existing suites" table missed `BlockerActivityFakeBackendTest`. Not fixed here (controller ruling:
   record only).
   - `morningReview_cleanAndNoDataStartAcknowledgeAndClose` (line 699): no cache + failing snapshot; needs the second
     failure before "Start day without data" appears.
   - `morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSnapshot` (line 749): failing snapshot after
     `recreate()`; `session-warning-banner` appears only after the second failure.
   - Minimal test-only fix: raise those two waits to more than 30 s (for example 45 s), costing about 30 s each.
     Alternative product change: make the retry delay injectable and set it to about 1 ms in instrumentation.
     Also worth doing: the noData test does not close its scenarios in a `finally`, so a timeout leaks an activity.
   - Re-verify with `ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest`, then the full suite.
2. Two flakes (passed on class re-run): `EditTaskFakeBackendTest.editTask_retryPreservesTheCapturedRootRequestAndDirtyExitConfirmsDiscard`
   (banner not displayed) and `ForegroundReadinessScreenTest.systemBackDoesNotDismissTheGate` (no window focus for
   10 s). Unverified hypothesis for the second: an activity leaked by the timed-out morning-review test in the same
   run stole focus (the re-run also had the failing tests before it and still passed, so this is weak).
3. CI was not checked: nothing was pushed (per instruction).

## Manual verification performed

Emulator only (`emulator-5554`, Android 16), through the instrumentation suite above. Nothing was run on a physical
phone, against live Todoist, or on the VPS. The backend was not deployed and nothing was pushed or merged.

## Environment notes

- `scripts/e2e-real-backend.sh` with `ANDROID_SERIAL` targeting an emulator needs
  `CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433`. Without it the app is pointed at `127.0.0.1:18433`, which the
  emulator app rejects as cleartext (`Cleartext HTTP traffic to 127.0.0.1 not permitted`).
- The script leaves its node backend (`npm run e2e-server` -> `tsx` -> `node test/e2eServer.ts`) running after exit.
  After every run, check `lsof -nP -iTCP:18433 -sTCP:LISTEN` and kill only processes whose cwd is this worktree's
  `backend` (all runs in this session were cleaned up; port 18433 was free at the end).
- Worktree builds need `ANDROID_HOME` and `ANDROID_SDK_ROOT` exported (`client/local.properties` is gitignored).
- Gradle reports the JVM test tasks `UP-TO-DATE` when inputs are unchanged; use `--rerun` on the task to force real execution.

## Assumptions

- The retry tests' 15 s waits are the only cause of the two real failures (supported by the logcat trace: no second
  sync attempt within the 15 s window).
- `.superpowers/` (ledger, briefs, logs) is workspace scratch and is intentionally not committed.
- `docs/notes/2026-09-23-morning-review-deferred-by-overdue-tasks.md` and
  `docs/handoffs/2026-09-23-morning-review-delivery-investigation.md` remain untracked in the primary checkout; the
  decision doc links to them. They were not committed from this branch, as the plan says.

## Next step

1. Fix the two `BlockerActivityFakeBackendTest` morning-review tests (see Open issues 1), re-run that class and then
   the full `scripts/e2e-real-backend.sh` suite until it is green, and tick Task 8 Step 6.
2. Then review and, with the owner's explicit go-ahead, follow the plan's Rollout: merge to `master` and push, check
   `gh run list --workflow=ci.yml --limit 1`, deploy the backend (`scripts/deploy-vps.sh`, which alone stops
   BUZER-ANDROID-E on the installed APK), install the new APK, and do the live Sentry checks (no new
   BUZER-ANDROID-E; `overlay_start skipped ... kind=MORNING_REVIEW` at 08:00; on 2026-10-11 the two floating tasks log
   `overlay_start attempted ... kind=START_BLOCKER` at 09:00 CEST).
3. Commit the two 2026-09-23 investigation docs from the primary checkout to `master` separately if the owner wants
   them kept.
