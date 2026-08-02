# Smart Home and True Refresh handoff

## Status

- Plan: `docs/superpowers/plans/2026-08-02-smart-home-and-true-refresh.md`
- Worktree: `/Users/svehla/playground/buzer-app/.worktrees/smart-home-and-true-refresh`
- Branch: `feature/smart-home-and-true-refresh`
- Code HEAD before this documentation update: `f034400 test: verify smart Home end to end`
- Tasks 1–6: implemented and committed.
- Task 7: complete and committed as `f034400`; the final scoped real-backend class passed all 28 tests.
- Task 8: backend/JVM/build gates are green. The full unfiltered real-backend E2E suite remains unrun and its plan checkbox remains open.

## Plan steps attempted

- Task 7 Steps 1–5 completed.
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

Historical status after the first strict three-invocation budget: **BLOCKED**. The user then authorized exactly two more Android invocations; both passed and resolved Task 7.

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
   - Evidence: `deadlineBlocker_rescheduleUsesSharedUpdateUi` passed in its original sequence-dependent position. The sole failure moved to `morningReview_repairCallsFakeBackend`: after the scheduler-aware helper observed Activity `DESTROYED`, its immediate `assertCountEquals(0)` still found one disabled `Invalid assigned fixture` editor semantics node. The budget was exhausted, so the plausible same-boundary `waitForIdle` refinement was intentionally not added or tested.

One sandboxed preflight attempt failed before backend startup or Gradle/instrumentation (`tsx` IPC `listen EPERM`). It did not invoke Android and is not counted in the three Android invocations; the identical escalated command became ledger entry 1.

### Authorized two-run completion ledger

The final narrow refinement calls `composeRule.waitForIdle()` immediately after the scheduler-aware wait observes `Lifecycle.State.DESTROYED`. This drains disposed Activity semantics before callers assert UI absence. It does not add sleep, retry, timeout, forced finish, ignored coverage, or production behavior.

1. `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest#morningReview_repairCallsFakeBackend' scripts/e2e-real-backend.sh`
   - Result: **PASS**, 1/1, `BUILD SUCCESSFUL in 1m 1s`.
2. `PORT=18434 ANDROID_TEST_CLASS='app.buzer.android.blockers.BlockerActivityFakeBackendTest' scripts/e2e-real-backend.sh`
   - Result: **PASS**, 28/28, zero skipped and zero failed, `BUILD SUCCESSFUL in 2m 29s`.

The deterministic Home→deadline-reschedule regression, backend received/completed markers, Android response/coordinator result, Activity finish, and the Morning Review semantics cleanup all passed in the final class sequence.

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

### Remaining work

Task 7 has no remaining work. Do not rerun the discarded scenario-closing or backend-drain hypotheses. Task 8 Step 3 still requires a separately authorized full unfiltered `PORT=18434 scripts/e2e-real-backend.sh` before claiming the whole Change 1 instrumentation suite is green.

## Contract and scope confirmations

- Automatic open/foreground refresh remains mirror-only `GET /tasks/snapshot`.
- Explicit pull, accessibility refresh, and Retry use authoritative `POST /tasks/sync`.
- Assigned-other roots remain excluded; genuinely unassigned roots are presentation-only and non-enforced.
- No Change 2 Morning Review scheduling/redesign or Change 3 health gating was implemented.
- The separate next product step remains planning Change 2, not implementing it in this branch.

## Assumptions

- The accepted Smart Home design and this plan remain product truth.
- The scoped 28-test Task 7 authority is green. The broader Change 1 completion claim still depends on the open Task 8 unfiltered-E2E checkbox.
