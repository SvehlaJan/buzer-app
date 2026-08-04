# Back-to-top E2E date assertion handoff

Date: 2026-08-04

## Task and status

Establish the deferred full Smart Home E2E baseline before starting Daily Morning
Review. The first unfiltered run exposed one deterministic, test-only failure in
`homeBackToTopPill_appearsAfterDeepScrolling_scrollsToIndexZero`; the fix and full
baseline are complete.

Worktree: `.worktrees/e2e-back-to-top-date`

Branch: `feature/e2e-back-to-top-date`

## Root cause

The Back-to-top pill appeared and accepted the click. The timeout happened afterward
because the test asserted the implementation-day title `Sunday` / `August 2`, while the
dynamic E2E snapshot correctly rendered `Tuesday` / `August 4` on this run. This was not
a production scroll defect or suite-isolation leak.

The test now captures the displayed Home weekday/date labels before deep scrolling and
requires those exact labels to return after Back to top. The assertion remains strict
without depending on the wall-clock date.

## Files changed

- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- this handoff

## Verification

Verification:
- Command: `PORT=18434 scripts/e2e-real-backend.sh`
- Result: FAIL (expected baseline RED)
- Evidence: 69 tests ran; 68 passed and only the Back-to-top test failed after the pill
  click while waiting for hard-coded `Sunday` at line 343.

Verification:
- Command: focused Back-to-top test before the correction
- Result: FAIL (reproduced RED)
- Evidence: the single test reproduced the same post-click `Sunday` timeout in 1m03s,
  ruling out suite-order dependence.

Verification:
- Command: `./gradlew :androidApp:compileDebugAndroidTestKotlin`
- Result: PASS
- Evidence: the dynamic-label assertion compiled with `BUILD SUCCESSFUL`.

Verification:
- Command: focused Back-to-top test after the correction
- Result: PASS
- Evidence: 1/1 managed-device test passed in 38s with no skip or retry.

Verification:
- Command: `PORT=18434 scripts/e2e-real-backend.sh`
- Result: PASS
- Evidence: all 69 managed-device tests passed with zero skipped/failed in 4m39s.

Verification:
- Command: push-triggered GitHub `ci.yml` run 30948160731 for master `6eed7af`
- Result: PASS
- Evidence: GitHub Actions completed successfully.

## Assumptions and next step

- Port 18434 was used because an unrelated pre-existing E2E server owns port 18433.
- The listener on 18433 and other unrelated orphan processes were not modified.
- Next: merge this test-only branch into `master`, then integrate the accepted Daily
  Morning Review planning branch before creating a fresh Change 2 implementation worktree.
