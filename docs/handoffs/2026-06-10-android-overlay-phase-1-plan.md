# 2026-06-10 Android Overlay Phase 1 Plan

## Task / Plan

- Task: research and plan Phase 1 steps 1, 2, and 3:
  - fix boot recovery in background
  - add `SYSTEM_ALERT_WINDOW` overlay blocker
  - run real timed lock-screen and foreground-app verification
- Plan created: `docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md`
- Decision recorded: `docs/decisions/2026-06-10-android-overlay-enforcement-phase-1.md`
- Current checkbox status: all implementation steps are unchecked; this session completed research and planning only.

## Files Changed

- `docs/decisions/2026-06-10-android-overlay-enforcement-phase-1.md`
- `docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md`
- `docs/handoffs/2026-06-10-android-overlay-phase-1-plan.md`

## Commands Run

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: worktree was clean before this documentation pass.

Verification:
- Command: `sed -n '1,220p' AGENTS.md`
- Result: PASS
- Evidence: confirmed required session routine, docs culture, Context7 requirement, and handoff requirements.

Verification:
- Command: `sed -n '1,220p' docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md`
- Result: PASS
- Evidence: confirmed Android-first MVP, boot rescheduling, full-screen blockers, and enforcement surfaces remain in scope.

Verification:
- Command: `sed -n '1,220p' docs/handoffs/2026-06-10-boot-completed-reboot-test.md`
- Result: PASS
- Evidence: confirmed real Pixel 5 reboot delivered `BOOT_COMPLETED` but Android blocked the app's background launch of `MainActivity` with `BAL_BLOCK`.

Verification:
- Command: `sed -n '1,220p' docs/handoffs/2026-06-10-operational-readiness.md`
- Result: PASS
- Evidence: confirmed current MVP operational state and manual testing context.

Verification:
- Command: `npx ctx7@latest library "Android" "BroadcastReceiver goAsync coroutine boot completed SYSTEM_ALERT_WINDOW TYPE_APPLICATION_OVERLAY foreground service exact alarm background start restrictions Settings canDrawOverlays"`
- Result: PASS
- Evidence: resolved current Android documentation source `/websites/developer_android`.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android" "BroadcastReceiver goAsync boot completed SYSTEM_ALERT_WINDOW TYPE_APPLICATION_OVERLAY Settings canDrawOverlays foreground service exact alarm background start restrictions"`
- Result: PASS
- Evidence: returned Android docs snippets for `goAsync()`, foreground service restrictions, `SYSTEM_ALERT_WINDOW`, and foreground service type requirements.

Verification:
- Command: web research against Android developer docs for `BroadcastReceiver.goAsync`, `SYSTEM_ALERT_WINDOW`, `TYPE_APPLICATION_OVERLAY`, foreground service background-start restrictions, foreground service type requirements, and time-sensitive notifications.
- Result: PASS
- Evidence: source links are recorded in the decision and plan documents.

Verification:
- Command: `rg -n "PermissionHealth|BootReceiver|AlarmReceiver|DebugBlockerLauncherActivity" client/androidApp/src -S`
- Result: PASS
- Evidence: identified the Android files that need boot, overlay, alarm, permission-health, and debug-launch changes.

Verification:
- Command: `sed -n '1,180p' client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Result: PASS
- Evidence: confirmed `refreshAndReconcile()` exists, `loadLatestSnapshot()` exists, and `reconcile(snapshot)` is currently private.

## Tests Run

Verification:
- Command: not run
- Result: SKIPPED
- Evidence: this was a research and planning pass with documentation-only changes. The implementation plan requires `:androidApp:testDebugUnitTest`, `:androidApp:assembleDebug`, Pixel 5 install, overlay smoke test, timed lock-screen test, foreground-app test, and reboot retest.

## Manual Verification

- No new device verification was performed in this session.
- The plan relies on the existing Pixel 5 reboot evidence from `docs/handoffs/2026-06-10-boot-completed-reboot-test.md`.

## Known Failures / Skipped Checks

- Current app behavior still fails boot recovery until the plan is implemented.
- Current app does not request or use `SYSTEM_ALERT_WINDOW`.
- No overlay manual test has been run yet because the implementation does not exist.
- No Gradle tests were run because no production code changed in this planning pass.

## Assumptions

- Buzer remains a personal/dev-device MVP, so requiring manually granted overlay permission is acceptable.
- Overlay enforcement is additive and does not replace existing blocker activity/full-screen notification fallback paths.
- Boot recovery should prioritize cached alarm rescheduling before live network refresh.
- Accessibility service and device-owner enforcement remain deferred until Phase 1 proves whether overlay plus exact alarms is sufficient.

## Next Recommended Step

Execute `docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md` in order. Start with Task 1 because cached reconciliation is the smallest high-value fix and gives boot recovery a testable unit boundary before adding receiver and overlay behavior.
