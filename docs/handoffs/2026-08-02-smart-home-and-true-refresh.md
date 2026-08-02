# Smart Home and True Refresh handoff

## Status

- Plan: `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md`
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/smart-home-and-true-refresh`
- Branch: `feature/smart-home-and-true-refresh`
- HEAD at handoff: `102c2b6 feat: render continuous smart Home`
- Tasks 1–6: implemented and committed.
- Task 7: fixture and instrumentation implementation complete, but not committed because the scoped instrumentation class is 27/28 rather than green.
- Task 8: stopped before full regression gates by the parent-agent time box after the scoped failure.

## Plan steps attempted

- Task 7 Steps 1–3 completed.
- Task 7 Step 4 attempted repeatedly; final result is one sequence-dependent failure.
- Task 7 Step 5 skipped because the required scoped class is red.
- Task 8 hygiene and this handoff were completed. Backend-wide, client-wide, and unfiltered E2E gates were skipped after the stop directive.

## Existing commits for Tasks 1–6

- `ed1074e feat: project unassigned task roots`
- `4b6d701 feat: add authoritative task sync`
- `3b4d766 feat: reconcile forced task sync`
- `84725be feat: model complete Home agenda`
- `8d3e734 fix: use pure zoneId in parseInstant fallback`
- `416b067 feat: wire authoritative Home refresh`
- `102c2b6 feat: render continuous smart Home`

## Uncommitted Task 7 files

- `backend/test/fakes/e2eFixtures.ts`
- `backend/test/fakes/e2eFixtures.test.ts`
- `backend/test/e2eServer.ts`
- `backend/test/e2eServer.test.ts`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`

The production Home file contains two focused defects exposed by the new instrumentation: the hero now renders `+N overlapping`, and Back to top becomes reachable when the third future header is visible near the end of a tall viewport.

## Implemented coverage

- Deterministic current overlap, four overdue tasks, tomorrow/later/next-year/cross-midnight future tasks, unassigned root plus child, assigned-other exclusion, and incomplete-checklist fixtures.
- Server snapshot assertions for unassigned hierarchy, assigned-other exclusion, and checklist totals.
- Home instrumentation for attention-only Morning Review, Add-only controls, current completion, cached forced-refresh failure and Retry, sticky future groups, Back to top, unique hero/overlap count, overdue expansion, unassigned expansion/Edit, assigned-other exclusion, checklist action/Done eligibility, future Edit, swipe refresh, and refresh accessibility semantics.
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
- Result: SKIPPED
- Evidence: Task 8 stopped after the scoped E2E failure by parent-agent time box.

- Command: `npm run typecheck`
- Result: SKIPPED
- Evidence: Task 8 stopped after the scoped E2E failure by parent-agent time box.

- Command: `npm run build`
- Result: SKIPPED
- Evidence: Task 8 stopped after the scoped E2E failure by parent-agent time box.

- Command: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: SKIPPED
- Evidence: Task 8 stopped after the scoped E2E failure by parent-agent time box.

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

## Known failure and recommended follow-up

The remaining failure is order/sequence dependent. `deadlineBlocker_rescheduleUsesSharedUpdateUi` passes in isolation and its exact request completes through both backend injection and real HTTP, but the Activity stays resumed when the whole 28-test class runs. Two hypotheses—closing Home `ActivityScenario` instances after each test and draining in-flight actions before `/__reset`—were tested and did not fix it; those speculative changes were removed.

Recommended next step: inspect the Android blocker/editor coroutine and Activity lifecycle state immediately before/after the successful update response when this method runs after `homeSwipeToRefresh_recordsSync`, then make the smallest proven isolation or lifecycle correction. Do not weaken the assertion or commit Task 7 until the scoped class passes.

## Contract and scope confirmations

- Automatic open/foreground refresh remains mirror-only `GET /tasks/snapshot`.
- Explicit pull, accessibility refresh, and Retry use authoritative `POST /tasks/sync`.
- Assigned-other roots remain excluded; genuinely unassigned roots are presentation-only and non-enforced.
- No Change 2 Morning Review scheduling/redesign or Change 3 health gating was implemented.
- The separate next product step remains planning Change 2, not implementing it in this branch.

## Assumptions

- The accepted Smart Home design and this plan remain product truth.
- A red scoped instrumentation authority blocks claiming Change 1 complete even though 27/28 tests and all focused backend checks pass.
