# Handoff: Foreground Enforcement Readiness — Post-Merge Code Review Fixes

Date: 2026-08-12
Plan: `docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md`
Branch/worktree: `feature/foreground-readiness` / `/Users/svehla/playground/buzer-app/.worktrees/foreground-readiness`
Status: P1 correction verified by the focused readiness class and the full unfiltered real-backend E2E suite.

## Scope

- Correct the P1 readiness instrumentation test so its representative foreground recheck does
  not revoke a granted runtime notification permission.
- Correct the controller documentation and this prior handoff's diagnosis. No production code,
  Gradle configuration, or implementation plan changes.

## Context and diagnosis

P2 was previously fixed in `dc84499` (`fix: route request-history persistence failure through
onLaunchFailure`). P1 previously simplified
`ForegroundReadinessInstrumentationTest#missingCapability_advancesToNextRequirementOnForegroundTransition`
to one representative foreground transition; exhaustive five-capability ordering remains covered
by `EnforcementReadinessPolicyTest#cascadeWithEveryCapabilityMissingResolvesInNotificationsExactFullScreenOverlayBatteryOrder`
and sibling JVM policy tests.

The earlier cumulative-resource/Test-Orchestrator-only diagnosis was incorrect. Preserved class and
full-run logs show the instrumentation PID killed by signal 9 about 260 ms after this test starts.
The test's first operation denied every deniable capability, Notifications first. The preceding
`@After` had called `grantAll()`, so that operation revoked granted `POST_NOTIFICATIONS` in the
shared non-orchestrated instrumentation process. Android ordinary runtime-permission revocation
terminates the target UID; that is the direct failure mechanism.

## Correction

The first notification-safe representative hop attempted Overlay -> BatteryExemption: establish
`grantAll()`, deny Overlay and BatteryExemption, assert step 4, grant Overlay, foreground, then
assert step 5. The focused class reached the foreground transition but Home appeared, proving that
BatteryExemption denial is not observable in this managed test context. That attempt failed and
was replaced without changing production code.

The revised hypothesis is FullScreenIntent -> Overlay: establish `grantAll()`, deny
FullScreenIntent and Overlay, launch and assert step 3 (**Allow full-screen alerts**), grant
FullScreenIntent, perform the existing foreground transition, and assert step 4 (**Allow display
over other apps**). It does not touch notification permission state or request history.

`ReadinessDeviceController` now states the precise safety constraint: UiAutomation is more robust
than raw `pm` for the operation, but an ordinary `revokeRuntimePermission` can still terminate the
target UID. Callers in a non-orchestrated instrumentation process must not revoke a currently
granted runtime permission.

## Completed

- P1 correction applied to the representative foreground-transition instrumentation test.
- Corrected the misleading runtime-permission controller comment.
- Corrected this handoff's diagnosis and replaced the former `(pending)` P1 commit reference with
  `434f646`.

## Files changed

- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessInstrumentationTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ReadinessDeviceController.kt`
- `docs/handoffs/2026-08-11-foreground-readiness-review-fixes.md`

## Commits

- `dc84499` — `fix: route request-history persistence failure through onLaunchFailure` (P2)
- `434f646` — P1 test simplification + plan checkbox correction + prior handoff

## Verification

Verification:
- Command: `cd client && ANDROID_HOME=/Users/svehla/Library/Android/sdk ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`; `:androidApp:compileDebugAndroidTestKotlin` completed.

Verification:
- Command: `ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh`
- Result: FAIL/BLOCKED
- Evidence: the focused class did not start. `tsx test/e2eServer.ts` exited with `Error: listen EPERM: operation not permitted` while creating `/var/folders/52/5988f4z55nn_6h9y85brtyb40000gn/T/tsx-501/76596.pipe`; the script then reported `E2E backend exited before becoming ready with status 1`.

Verification:
- Command: `ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh` (Overlay -> BatteryExemption attempt)
- Result: FAIL
- Evidence: after granting Overlay and performing the foreground transition, Home appeared instead of the expected BatteryExemption step, so BatteryExemption denial is not observable in this managed test context.

Verification:
- Command: `ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh` (FullScreenIntent -> Overlay hypothesis)
- Result: BLOCKED
- Evidence: the permitted invocation reused the E2E backend and progressed through Gradle build tasks, ending at `:androidApp:mergeLibDexDebug`; it produced neither an instrumentation success/failure footer nor managed-device test results/reports for the class. No PASS claim can be made from this incomplete execution.

Verification:
- Command: `ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh` (FullScreenIntent -> Overlay hypothesis, later rerun)
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 55s`; all 5 readiness instrumentation tests finished with 0 failures.

Verification:
- Command: `env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: `Finished 109 tests on pixel8Api34`; `BUILD SUCCESSFUL in 6m 16s`; 0 failures.

## Assumptions

- The preserved PID/signal-9 evidence supplied with this task is sufficient RED evidence; the
  destructive notification-revocation case was intentionally not rerun.
- This task preserves the accepted coverage division: JVM tests own exhaustive ordering and
  instrumentation owns one real foreground-recheck hop.

## Next step

Complete the still-pending physical Pixel 10 smoke and `adb-verify.sh health` evidence (Task 8
Step 5), then complete review, integration, push, and CI confirmation (Task 8 Step 9).
