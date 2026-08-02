# Smart Home and True Refresh handoff

## Status

- Plan: `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md`
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/smart-home-and-true-refresh`
- Branch: `feature/smart-home-and-true-refresh`
- Code HEAD before this documentation update: `6be621a test: harden smart Home E2E authority`
- Tasks 1–6: implemented and committed.
- Task 7: fixture/instrumentation work is committed through `6be621a`, and the final scoped class is green (28/28). Step 3 remains incomplete for the enumerated coverage gaps, so this is not a full Smart Home completion claim.
- Task 8: open. Backend/JVM/build gates were green before the correction pass, while the full unfiltered real-backend E2E suite remains unrun.

## Plan steps attempted

- Task 7 Steps 1–2 and 4–5 completed. Step 3 remains open for the reviewed coverage gaps.
- Task 8 Steps 1–2 and 4–7 completed.
- Task 8 Step 3 remains open: the full unfiltered real-backend E2E suite was outside the bounded isolation rerun authorization.

## Existing commits for Tasks 1–6

- `ed1074e feat: project unassigned task roots`
- `4b6d701 feat: add authoritative task sync`
- `3b4d766 feat: reconcile forced task sync`
- `84725be feat: model complete Home agenda`
- `8d3e734 fix: use pure zoneId in parseInstant fallback`
- `416b067 feat: wire authoritative Home refresh`
- `102c2b6 feat: render continuous smart Home`
- `f034400 test: verify smart Home end to end`

## Task 7 files committed in `f034400`

- `backend/test/fakes/e2eFixtures.ts`
- `backend/test/fakes/e2eFixtures.test.ts`
- `backend/test/e2eServer.ts`
- `backend/test/e2eServer.test.ts`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/FakeBackendClient.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`

The production Home file contains two focused defects exposed by the new instrumentation: the hero now renders `+N overlapping`, and Back to top becomes reachable when the third future header is visible near the end of a tall viewport.

## Implemented coverage

- Deterministic current overlap, four overdue tasks, tomorrow/later/next-year/cross-midnight future tasks, unassigned root plus child, assigned-other exclusion, and incomplete-checklist fixtures.
- Server snapshot assertions for unassigned hierarchy, assigned-other exclusion, and checklist totals.
- Home instrumentation for attention-only Morning Review, Add-only controls, current completion, cached forced-refresh failure and Retry, reaching future groups, Back to top, unique hero/overlap count, overdue expansion, unassigned expansion/Edit, assigned-other exclusion, checklist action/Done eligibility, future Edit, swipe refresh, and refresh accessibility semantics.
- Remaining explicit coverage gaps: sticky-header pinning, Overdue Done/Edit routing, exact opened-task identity for Unassigned/future rows, and cached-content retention during a controlled in-flight refresh.
- Existing blocker editor selectors were migrated to the current computed Repair/Reschedule labels and fixture-pinned occurrence keys.

## Verification

- Command: `npm test -- test/fakes/e2eFixtures.test.ts test/e2eServer.test.ts`
- Result: PASS
- Evidence: final focused backend fixture/server run passed 2 files and 33 tests.

- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL` (latest compile completed in 2s).

- Command: `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest#homeHero_notDuplicated_overlappingCurrentRowsVisible' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: 1/1 test passed after the overlap-count production fix.

- Command: `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest#deadlineBlocker_rescheduleUsesSharedUpdateUi' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: focused method passed 1/1, `BUILD SUCCESSFUL in 37s`.

- Command: `npm test -- test/e2eServer.test.ts`
- Result: PASS
- Evidence: 1 file and 22 tests passed.

- Command: `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
- Result: FAIL
- Evidence: final time-boxed run executed 28 tests; 27 passed and only `deadlineBlocker_rescheduleUsesSharedUpdateUi` failed at `waitForActivityDestroyed` after 45 seconds because the blocker remained `RESUMED`. The same method passes alone. Earlier full-class iterations converged from 22/28 to 23/28 and then 27/28 while selectors and the two proven Home defects were corrected.

- Command: exact captured Android `POST /tasks/task-reschedule/update` payload sent with `curl` to the isolated E2E backend on port 18434
- Result: PASS
- Evidence: HTTP 200 with completed authoritative snapshot in approximately 30 ms. The equivalent full-payload `app.inject` backend test also passes. This localizes the remaining issue to sequence-dependent Android activity/request behavior, not payload validation or backend reconciliation.

- Command: `npm test`
- Result: PASS
- Evidence: final run passed 32 files and 400 tests.

- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript no-emit check exited 0.

- Command: `npm run build`
- Result: PASS
- Evidence: backend TypeScript build exited 0.

- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: final run completed with `BUILD SUCCESSFUL in 2s`.

- Command: `PORT=18434 scripts/e2e-real-backend.sh`
- Result: SKIPPED
- Evidence: the full unfiltered suite was not run because the scoped class remained red and the parent agent ended further test cycles.

- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors; Git emitted only the worktree fsmonitor IPC warning.

- Command: `gh run list --workflow=ci.yml --limit 1`
- Result: SKIPPED
- Evidence: branch was not pushed, so push-triggered CI was not started.

## Manual verification

No separate screenshot/manual-device pass was performed. The managed-device instrumentation exercised the listed visual interactions against `backend/test/e2eServer.ts`. All E2E work used port 18434; the unrelated server on port 18433/PID 42419 was not touched.

## 2026-08-02 deadline-reschedule isolation follow-up

Historical status after the first strict three-invocation budget: **BLOCKED**. The user then authorized exactly two more Android invocations; both passed and resolved the original deadline-reschedule isolation failure. They did not complete Task 7: Step 3 remains open for the declared coverage gaps.

### Root-cause evidence

The deterministic reproducer now runs the preceding Home swipe-refresh flow and proves these boundaries before starting deadline reschedule:

1. backend `/tasks/sync` request received;
2. backend response completed;
3. Android logged HTTP 200 and persisted the coordinator-applied snapshot;
4. `MainActivity` reached `DESTROYED`.

On the RED deadline update, Fastify recorded action `f099f89b-1951-4c90-8b5f-504b2ee88f34`, received it at `2026-08-02T18:00:36.050Z`, and completed HTTP 200 at `18:00:36.062Z`. Android then emitted no Ktor `http RESPONSE`, no coordinator `mutation applied`, and no Activity-finish marker for 45 seconds. `JobCancellationException` appeared only when test teardown cancelled the request.

The proven cause is the test wait boundary: `waitForActivityDestroyed` used a `SystemClock.sleep` polling loop while the update was launched from the Compose editor coroutine. The polling monopolized the Compose test scheduler. A fast isolated response could win the race before the loop; after Home refresh, the response continuation waited until teardown. The one fix preserves the 45-second `DESTROYED` predicate but services the test scheduler with `composeRule.waitUntil`.

The focused GREEN log then showed the complete expected chain: backend request received, Android HTTP 200, coordinator `mutation applied ... outcome=completed`, and Activity finish. No production file was changed for this fix.

### Strict Android instrumentation ledger

1. `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest#deadlineBlocker_rescheduleUsesSharedUpdateUi' scripts/e2e-real-backend.sh`
   - Result: **FAIL**, deterministic RED 0/1.
   - Evidence: backend response completed HTTP 200 in 12 ms; Android response/coordinator/finish markers absent until teardown cancellation.
2. Same focused command after the single scheduler-aware wait fix.
   - Result: **PASS**, 1/1, `BUILD SUCCESSFUL in 57s`.
   - Evidence: HTTP response, `mutation applied`, and Activity-finish markers all present.
3. `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
   - Result: **FAIL**, 27/28, `BUILD FAILED in 2m 15s`.
   - Superseded evidence: `deadlineBlocker_rescheduleUsesSharedUpdateUi` passed in its original sequence-dependent position. The sole failure moved to `morningReview_repairCallsFakeBackend`, where the then-current test incorrectly expected Activity `DESTROYED` before checking for stale semantics. Later fixture evidence proved three invalid items remain, so Activity destruction is not the final contract.

One sandboxed preflight attempt failed before backend startup or Gradle/instrumentation (`tsx` IPC `listen EPERM`). It did not invoke Android and is not counted in the three Android invocations; the identical escalated command became ledger entry 1.

### Superseded authorized two-run ledger

This ledger records an intermediate, now-superseded refinement that called `composeRule.waitForIdle()` after observing `Lifecycle.State.DESTROYED`. It explains the historical results below but is not the final Morning Review success contract.

1. `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest#morningReview_repairCallsFakeBackend' scripts/e2e-real-backend.sh`
   - Result: **PASS**, 1/1, `BUILD SUCCESSFUL in 1m 1s`.
2. `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
   - Result: **PASS**, 28/28, zero skipped and zero failed, `BUILD SUCCESSFUL in 2m 29s`.

The deterministic Home→deadline-reschedule regression, backend received/completed markers, Android response/coordinator result, Activity finish, and the then-current Morning Review semantics cleanup all passed in that historical class sequence.

Final Morning Review success contract: wait until the repaired `Invalid assigned fixture` title semantics disappear, assert the title is absent, and assert `MorningReviewActivity` remains open because three invalid items remain. Do not restore a post-destruction or `waitForIdle()`-after-destruction assertion for this fixture.

### Cheap verification after the fix

Verification:
- Command: `npm test`
- Result: PASS
- Evidence: 32 files and 400 tests passed.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: TypeScript no-emit check exited 0.

Verification:
- Command: `npm run build`
- Result: PASS
- Evidence: backend TypeScript build exited 0.

Verification:
- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: final rerun completed with `BUILD SUCCESSFUL in 2s`.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors.

## 2026-08-02 mandatory coverage-correction pass

This pass made test/documentation changes only; no production file changed. Each full-class invocation was explicitly authorized and none was retried. The final exact-text correction passed and is committed in `4adcb66`.

Test corrections attempted:

- wait for the deadline update backend response to complete before asserting Activity destruction;
- make the shared error-log wait Compose-scheduler-aware;
- assert both UPDATE_TASK failures write the exact action/task ID and `forced_failure` fragments;
- assert the intended exact `Task update` title, draft-retention message, Retry, Dismiss, and open Activity;
- wait for the repaired Morning Review task semantics to disappear while asserting the Activity remains open, matching the fixture evidence `remainingInvalid=3`;
- assert Back to top returns to the exact weekday/date title and removes the pill.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`.

Verification:
- Command: `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
- Result: FAIL
- Evidence: initial TDD proof ran 28 tests; 25 passed and 3 failed. The two UPDATE_TASK tests timed out in the raw error-log polling helper with an empty file, and Morning Review success timed out expecting Activity destruction even though the success log proved `remainingInvalid=3`.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: after the evidence-driven wait/lifecycle correction, `BUILD SUCCESSFUL in 1s`.

Verification:
- Command: `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
- Result: FAIL
- Evidence: 28 tests ran; 26 passed and 2 failed. Both `morningReview_repairBackendFailureShowsErrorAndStaysOpen` and `deadlineBlocker_rescheduleFailureUsesUpdateAction` reached the expected error log (`action failed action=UPDATE_TASK ... message=forced_failure`) but timed out after 15 seconds waiting for the exact `Task update` UI. The other corrections passed in the same class sequence, including Morning Review success remaining open and the strengthened Back-to-top assertion. No retry was run.

Read-only trace resolved that RED without a production change: the dialog was created, but `waitForText("Task update")` used `substring = true`, matching both the title and the longer message and making `onNode` ambiguous until timeout. The final correction waits for the exact title and exact generic message separately.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: exact-text correction compiled with `BUILD SUCCESSFUL in 1s`.

Verification:
- Command: `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: all 28 tests passed with zero skipped and zero failed; Gradle reported `BUILD SUCCESSFUL`. No retry was run.

## 2026-08-02 deterministic harness quality correction

Root cause: the Home→deadline precondition treated any persisted snapshot-file change plus path-only `/tasks/sync` completion as proof that the explicit forced-sync response had been applied. A racing automatic launch/resume GET could satisfy the file-change predicate first. Separately, `+365 days` does not define an explicit next-calendar-year date, and a same-day 23:30 cross-midnight fixture is already past when the suite starts late at night.

The E2E debug surface now captures exact `{ serverTime, snapshotGeneration }` authority from the actual serialized correlated POST response in Fastify `onSend`, before `onResponse` marks completion. `FakeBackendClient` returns the completed 2xx POST authority after reset, and the Android precondition waits until `latest_snapshot.json` contains both exact values. It no longer uses a baseline file change, path-only completion, sleep, or production-only logs. The next-year fixture is local January 2 at 10:00 in `year + 1`; the cross-midnight fixture starts two local calendar days ahead at 23:30 and ends at 01:00 the following day.

Verification:
- Command: `npm test -- test/fakes/e2eFixtures.test.ts test/e2eServer.test.ts`
- Result: FAIL (expected RED)
- Evidence: 34 passed and 3 failed: response authority was absent, leap-night next-year resolved to February 28 at 23:59, and the local-year-end cross-midnight start was in the past.

Verification:
- Command: `npm test -- test/fakes/e2eFixtures.test.ts test/e2eServer.test.ts`
- Result: PASS
- Evidence: 2 files and 37 tests passed after the minimal harness/fixture correction.

Verification:
- Command: `npm run typecheck`
- Result: PASS
- Evidence: backend TypeScript no-emit check exited 0.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: Kotlin response-authority parsing and the Android-test source compiled with `BUILD SUCCESSFUL in 2s`.

Verification:
- Command: `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: the sole actual instrumentation invocation passed all 28 tests with zero skipped/failed; `BUILD SUCCESSFUL in 2m 27s`. An earlier permission-review timeout ended before process creation and did not invoke Android; no test retry was performed.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: no whitespace errors; Git emitted only the known worktree fsmonitor IPC warning.

### Remaining work

Task 7 Step 3 remains open for sticky-header pinning, explicit Overdue Done/Edit routing, exact opened-task identity for Unassigned/future rows, and cached-content retention during a controlled in-flight refresh. Task 8 Step 3 still requires a separately authorized full unfiltered `PORT=18434 scripts/e2e-real-backend.sh`; do not claim the Smart Home change complete.

## Contract and scope confirmations

- Automatic open/foreground refresh remains mirror-only `GET /tasks/snapshot`.
- Explicit pull, accessibility refresh, and Retry use authoritative `POST /tasks/sync`.
- Assigned-other roots remain excluded; genuinely unassigned roots are presentation-only and non-enforced.
- No Change 2 Morning Review scheduling/redesign or Change 3 health gating was implemented.
- The separate next product step remains planning Change 2, not implementing it in this branch.
- Final integration ancestry must include canonical accepted-design commit `a7c85a6` from `feature/smart-home`. The current untracked duplicate `docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md` is not delivered by this implementation branch and must not be accidentally staged as a second copy.

## Assumptions

- The accepted Smart Home design and this plan remain product truth.
- The scoped 28-test class is green. The broader Change 1 completion claim still depends on Task 7 Step 3's enumerated coverage gaps and the open Task 8 unfiltered-E2E checkbox.
