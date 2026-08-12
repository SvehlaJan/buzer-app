# Handoff: Foreground Enforcement Readiness — Post-Merge Code Review Fixes

Plan: `docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md`
Branch/worktree: `feature/foreground-readiness` / `/Users/svehla/playground/buzer-app/.worktrees/foreground-readiness`
Status: two review findings (P1, P2) evaluated and addressed. P2 fixed cleanly. P1's recommended
fix was implemented and is a genuine improvement, but — confirmed by re-running the actual full
suite, not assumed — it does **not** achieve a single clean unfiltered 109-test run. That remains
an open, well-characterized, out-of-scope infrastructure limitation.

## Context

This session picked up after the previous `docs/handoffs/2026-08-08-foreground-enforcement-readiness.md`
(Task 8's checkpoint handoff) and the physical Pixel 10 smoke test (partially completed by the
user directly on-device). An external code reviewer raised two findings against that state:

- **P1:** the plan's mandatory unfiltered E2E gate (Task 8 Step 4) never produced one clean run;
  combining an isolated pass with an exclusion run doesn't satisfy that literal requirement.
  Recommended: move exhaustive capability-ordering coverage to the existing JVM policy tests and
  simplify the instrumentation test to one representative foreground transition, then rerun the
  complete unfiltered suite.
- **P2:** `performForegroundReadinessAction()`'s call to `beginPrimaryAction()` sits outside its
  `try` block, so a `markRequested()` persistence failure (which deliberately throws) bypasses
  `onLaunchFailure()` and could crash `MainActivity` instead of showing the Retry-only error state.

Both were independently verified against the actual code before any change was made (see
`superpowers:receiving-code-review` discipline — no fix was applied on the reviewer's word alone).

## P2 — fixed

**Commit:** `dc84499` — `fix: route request-history persistence failure through onLaunchFailure`

Verified the exact call chain by reading the real files: `MainActivity.performForegroundReadinessAction()`
called `readinessController.beginPrimaryAction()` outside its `try` block;
`ForegroundReadinessController.beginPrimaryAction()` calls `requestHistory.markRequested()` for the
`NeedsRuntimePermission` branch; `SharedPreferencesNotificationPermissionRequestHistory.markRequested()`
uses `check(preferences.edit()....commit())`, which throws `IllegalStateException` on a `commit()`
failure — by design, matching Task 3's own plan text ("If `markRequested()` fails, let the
exception flow into the caller's launch-failure path"). The implementation didn't actually route
it there; this fixes that gap.

**Fix:** moved the `beginPrimaryAction()` call inside `performForegroundReadinessAction()`'s
`try` block (the `?: return` early-exit for the no-action case behaves identically inside `try`
with no `finally` present). Added `ForegroundReadinessControllerTest#beginPrimaryActionPropagatesRequestHistoryPersistenceFailureRatherThanSwallowingIt`,
which locks the contract `MainActivity`'s fix now relies on: `beginPrimaryAction()` propagates a
`markRequested()` failure rather than swallowing it or corrupting controller state.

Verification:
- Command: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest --tests '*ForegroundReadinessControllerTest' --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; real JUnit XML `tests="14" skipped="0" failures="0" errors="0"` (13 pre-existing + 1 new).

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: aggregated real JUnit XML across the whole `androidApp` JVM suite: `total tests=400 total failures=0 total errors=0` (399 pre-existing + 1 new). No regressions.

## P1 — recommended fix implemented, does not resolve the underlying issue

**What was implemented:**

`ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground`
(4 real internal `MainActivity` relaunches, walking the full 5-step cascade) was renamed to
`missingCapability_advancesToNextRequirementOnForegroundTransition` and reduced to one
representative foreground transition (deny all deniable capabilities → launch → assert step 1 →
grant Notifications → foreground → assert it correctly advances to step 3, skipping the
always-ready ExactAlarms step). The exhaustive ordering assertion this replaced was confirmed
already fully covered, independently, by
`EnforcementReadinessPolicyTest#cascadeWithEveryCapabilityMissingResolvesInNotificationsExactFullScreenOverlayBatteryOrder`
and its sibling boundary tests (pure JVM, no Android framework, already existed from Task 1). The
Blocking→Ready terminal transition this test used to exercise (via Battery, the last capability)
remains covered by `restoringOverlay_preservesDirtyEditDraft` (via Overlay, mid-sequence) and
`healthyStartup_showsHomeWithoutReadinessFlash` (terminal Ready-state rendering) — no coverage was
actually lost, only genuinely redundant re-verification of already-proven ordering logic.

**This is a legitimate, worthwhile change on its own merits** — regardless of its effect on the
crash, it removes duplicate coverage and reduces this one test's own resource footprint in the
shared instrumentation process. It is not being reverted.

**It does not fix the crash.** This was verified empirically, not assumed:

Verification:
- Command: `ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest#missingCapability_advancesToNextRequirementOnForegroundTransition scripts/e2e-real-backend.sh` (isolated)
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 30s`. The test's own correctness is not in question.

Verification:
- Command: `ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh` (5-test class, fresh backend, confirmed via `lsof` before each attempt)
- Result: FAIL (both attempts)
- Evidence: crashed both times with the identical `Process crashed` signature, as the **5th and last** test in this small class — a different absolute position than the full suite's ~66-69, but the same relative pattern: crashes specifically when running after enough other `MainActivity`-launching tests in the same shared process, regardless of this test's own (now much smaller) internal weight.

Verification:
- Command: `env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh` (full 109-test suite, fresh backend confirmed clean via `lsof` beforehand, this test's simplification in place)
- Result: FAIL
- Evidence: `BUILD FAILED in 4m 58s`. Console log: clean progress with only the known
  `AddTaskFakeBackendTest#addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft` race
  recorded as a single non-fatal failure early (`Tests 5/109 completed... (1 failed)`, run
  continued normally — this is the same pre-existing, already-documented ~1-in-2-at-scale static-
  cache race from the original Task 8 handoff, not new) — then
  `ForegroundReadinessInstrumentationTest.missingCapability_advancesToNextRequirementOnForegroundTransition FAILED`
  at `Tests 69/109 completed... (2 failed)`, `Finished 69 tests on pixel8Api34`,
  `Test run failed to complete. Instrumentation run failed due to Process crashed.` **Same
  position (69/109), same signature, as every previous full-run attempt with the original,
  heavier version of this test** — the simplification measurably reduced this one test's own
  relaunch count (4 → 1) but did not shift the crash's cumulative threshold within a 109-test
  budget, because the threshold is driven by the sum of `MainActivity` launches from the ~68
  tests that run *before* this one, most of which are unrelated to readiness and outside this
  plan's scope to touch.

**Conclusion, now with stronger evidence than the original Task 8 handoff had:** the crash is
unambiguously a cumulative shared-instrumentation-process resource-pressure phenomenon (no
Android Test Orchestrator in this repo → every test in a run shares one process → one crash
takes down all remaining tests), not attributable to any single test's design. No change scoped
to a single test file can reliably prevent it. Test Orchestrator (real process-per-test isolation)
remains the only fix that would actually deliver "one clean unfiltered run" — already named in
Task 5's original investigation, reconfirmed in Task 6's and Task 8's handoffs, and reconfirmed
again here. It is a Gradle/test-harness change affecting every instrumentation suite in the app,
explicitly out of scope for this plan, and was not attempted.

**Coverage claim, unchanged from Task 8's original handoff and still accurate:** every one of the
109 tests has been confirmed passing at least once, across isolated runs and combined runs, in
this session and the original Task 8 session. What has never been achieved, in either session, is
all 109 executing successfully within a single uninterrupted process — and per this session's
review, that should not be represented as done. The plan's Task 8 Step 4 checkbox has been
reverted from `[x]` to `[ ]` with an inline note explaining why.

## Files changed this session

- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` (P2)
- `client/androidApp/src/test/kotlin/app/buzer/android/readiness/ForegroundReadinessControllerTest.kt` (P2)
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessInstrumentationTest.kt` (P1)
- `docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md` (Step 4 checkbox correction)
- `docs/handoffs/2026-08-11-foreground-readiness-review-fixes.md` (this file)

## Commits

- `dc84499` — `fix: route request-history persistence failure through onLaunchFailure` (P2)
- (pending) — P1's test simplification + plan checkbox correction + this handoff

## Known failures and next recommended step

- **Test Orchestrator adoption remains the only real fix for the E2E crash.** Explicitly out of
  scope for this plan. A future dedicated task should add it and re-verify every instrumentation
  suite in the app, not just readiness.
- **`AddTaskFakeBackendTest#addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft`**
  recurred once more this session at large scale, consistent with its already-documented ~1-in-2
  frequency and root cause (`AddTaskDependencies.cachedProjectRepository`, an unsynchronized
  JVM-static singleton reset via reflection in three test classes, pre-existing before this plan).
  Still not fixed, still out of scope, still explicitly documented rather than silently ignored.
- **Physical Pixel 10 smoke test (plan Task 8 Step 5):** confirmed in progress directly by the
  user on-device (steps 1-5 of the gate sequence observed working correctly, including a real
  confirmation that `USE_FULL_SCREEN_INTENT`'s appops "default" state resolves to *allowed* on
  this specific device — useful signal beyond what the emulator showed). Draft-preservation check
  and `adb-verify.sh health` evidence were handed to the user to finish independently; status
  pending their report.
- **Task 8 Step 9 (review, integrate, push, confirm CI):** still not done, still requires explicit
  human sign-off, per this session's ongoing scope restriction.
