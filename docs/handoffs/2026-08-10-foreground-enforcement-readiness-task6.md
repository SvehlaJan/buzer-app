# Handoff: Foreground Enforcement Readiness — Task 6

Plan: `docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md` (sibling planning worktree)
Task: Task 6 of 8 — "Integrate readiness into MainActivity without losing app state"
Status: **Steps 1-7 and 9 complete. Step 8 partially green — see finding below.**

## What changed

- Modified: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Commit: `37b9272` — `feat: gate foreground on enforcement readiness`

Implemented exactly per the Task 6 spec:

- Registered `notificationPermissionLauncher` (`RequestPermission`) and `readinessSettingsLauncher`
  (`StartActivityForResult`) as field launchers, alongside the pre-existing `morningReviewLauncher`.
- Added `foregroundReadinessState` (Compose-observable), lazy `readinessController`
  (`ForegroundReadinessController` wired to `AndroidEnforcementReadinessChecker` +
  `SharedPreferencesNotificationPermissionRequestHistory`), and lazy `readinessSettingsIntentFactory`.
- `onCreate` now calls `NotificationChannels.ensureCreated(this)`, then synchronously
  `foregroundReadinessState = readinessController.refresh()` before `setBuzerContent`, so an
  unhealthy launch is blocked from the first composed frame and a healthy launch never flashes
  the gate.
- `setBuzerContent` now wraps `BuzerApp(...)` and `ForegroundReadinessGate(...)` in a
  `Box(Modifier.fillMaxSize())`. `BuzerApp`'s parameter list is byte-identical to before (verified
  via `git diff` — only indentation changed). `App.kt` was not touched.
- `performForegroundReadinessAction()` implements the explicit action routing: reads
  `readinessController.beginPrimaryAction()`, assigns `foregroundReadinessState =
  readinessController.state` (plain assignment, not `applyReadinessState`, since the state is
  mid-action rather than freshly rechecked), dispatches `RequestNotificationPermission` /
  `OpenSettings` / `Retry`, throws `ActivityNotFoundException` when
  `intent.resolveActivity(packageManager) == null`, and catches only `Exception` (not
  `Throwable`), routing failures through `readinessController.onLaunchFailure(error)`.
- `applyReadinessState(state)` centralizes the "on any fresh recheck" behavior: assigns state,
  and on `Ready` calls `refreshMorningReviewAvailability()`; otherwise cancels
  `morningReviewReevaluationJob`.
- `onResume` now runs `morningReviewEntryController.onResume()` →
  `applyReadinessState(readinessController.refresh())` → `refreshEnforcement(forceTodoist =
  false)`, in that order, before any Morning Review evaluation (the old direct
  `refreshMorningReviewAvailability()` call was removed from `onResume` since
  `applyReadinessState` now owns that decision).
- `refreshMorningReviewAvailability()` and `openHomeMorningReview()` both guard at their very top:
  if `foregroundReadinessState != ForegroundReadinessGateState.Ready`, cancel
  `morningReviewReevaluationJob` and return before doing anything else (before
  `MorningReviewReconcileGuard.reconcile(...)` in the availability function).
- `morningReviewLauncher`'s callback now rechecks readiness first
  (`applyReadinessState(readinessController.refresh())`) before conditionally calling
  `refreshMorningReviewAvailability()` via `morningReviewEntryController.onResult()` — this is the
  guard against a capability revoked while Morning Review was open immediately relaunching Review
  above the gate.

## Verification

**Compile (Step 7):**
```
./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin --console=plain
```
Result: PASS (`BUILD SUCCESSFUL`), run twice (once before, once after the final commit).

**Instrumentation (Step 8):**
```
ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh
```
Full-class result: **3/5 pass, 2/5 fail** (managed API 34 / pixel8Api34):

- PASS `healthyStartup_showsHomeWithoutReadinessFlash`
- PASS `restoringOverlay_preservesDirtyEditDraft`
- PASS `settingsIntents_arePackageScopedForEveryDestination`
- FAIL `missingCapabilities_advanceInRequiredOrderOnEveryForeground`
- FAIL `missingCapability_neverLaunchesSettingsWithoutTap_andBackCannotDismiss`

Both failures were re-run in isolation (fresh managed-device instance each time, no shared-process
pollution possible) and failed **identically and deterministically both times** — not a process
crash, a clean `ComposeTimeoutException` waiting for "Step 2 of 5 (Allow exact alarms)". This is
**not** the documented Task 5 flake (that one is a process-crash signature on
`missingCapabilities_advanceInRequiredOrderOnEveryForeground`; what I observed is a clean,
repeatable assertion failure on both tests, in isolation, with no crash).

### Root cause (verified, not MainActivity's bug)

`AndroidManifest.xml` declares both:
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```
`USE_EXACT_ALARM` was added at commit `b8e7efaf` (2026-06-02), long before this plan (Task 1 is
`90859d7`, 2026-08-08) — almost certainly for Morning Review's exact alarm. On API 33+,
`AlarmManager.canScheduleExactAlarms()` returns `true` if the app holds **either**
`SCHEDULE_EXACT_ALARM` (revocable via the `ReadinessDeviceController`'s
`cmd appops set ... SCHEDULE_EXACT_ALARM deny`) **or** `USE_EXACT_ALARM` (a normal, install-time
permission for alarm-clock-category apps — not appops-gated, not user-revocable via Settings).
Direct device verification on a manually booted `Buzer_API_34` AVD:

- `cmd appops set app.buzer.android USE_EXACT_ALARM deny` → `Error: Unknown operation string:
  USE_EXACT_ALARM` (it isn't a real app-op at all; `ReadinessDeviceController.setExactAlarms`
  issues this command but its `runShell` helper doesn't check for errors, so the failure is
  silent).
- After denying `SCHEDULE_EXACT_ALARM` via appops (confirmed via `cmd appops get` →
  `SCHEDULE_EXACT_ALARM: deny`), `dumpsys package app.buzer.android` still shows
  `USE_EXACT_ALARM: granted=true`, and `dumpsys alarm` still shows
  `exactAllowReason=policy_permission` for this app's scheduled alarms — i.e. the OS itself
  confirms exact-alarm scheduling remains allowed purely because of the `USE_EXACT_ALARM` grant.

So it is structurally impossible, given the current manifest, for `ReadinessDeviceController` to
put the app into a state where `EnforcementReadinessPolicy` finds `exactAlarmsAllowed == false`.
Both failing tests require observing "Step 2 of 5 (Allow exact alarms)", which can never render.

**Direct proof MainActivity's own wiring is correct:** with all 5 capabilities denied via the same
`adb shell cmd appops` / `pm revoke` / `cmd deviceidle whitelist` commands the test fixture uses, a
manually launched `MainActivity` correctly showed "Step 1 of 5 — Allow notifications". After
granting only Notifications and re-foregrounding (`am start` on the same task, mirroring the
test's `moveToState(CREATED) → moveToState(RESUMED)`), the gate correctly re-evaluated and
advanced straight to **"Step 3 of 5 — Allow full-screen alerts"** — skipping the unreachable step
2 exactly as the manifest finding predicts, and correctly landing on the next genuinely-denied
capability in order. Screenshots taken via `adb screencap` during this manual check (not
committed; ephemeral diagnostic only). This confirms the readiness re-check, step ordering, and
gate rendering added in Task 6 all work correctly — the two failing tests fail for a reason
entirely outside `MainActivity.kt`, in a manifest permission and a test-fixture assumption that
predate this task.

This is a genuine gap in Tasks 1/2/5 (the readiness policy assumes `ExactAlarms` is a real,
revocable, testable capability; the app's manifest already made it unconditionally available via
`USE_EXACT_ALARM`), not something in Task 6's file scope (`MainActivity.kt` only) to fix. Likely
follow-ups for whoever owns that gap: either drop the `ExactAlarms` readiness step for this app
(since `USE_EXACT_ALARM` already guarantees it), or have `ReadinessDeviceController` model this
capability as untestable and skip asserting on it, or revisit whether `USE_EXACT_ALARM` should be
declared at all.

## Manual verification performed

- Booted `Buzer_API_34` manually (separate from the Gradle managed-device `pixel8Api34` instance
  used by `scripts/e2e-real-backend.sh`) purely for read-only diagnosis via `adb shell dumpsys`,
  `adb shell cmd appops`, and `adb shell am start` + `adb shell screencap`. Killed the emulator
  (`adb emu kill`) after diagnosis; no lasting device state was left running.
- No production or test files were modified as part of this diagnosis — only `MainActivity.kt`
  per Task 6's scope.

## Self-review (per Task 6 prompt checklist)

- Implemented every Step 1-7, 9 bullet; nothing beyond Task 6's scope. Did not touch `App.kt`,
  `AndroidManifest.xml`, `ReadinessDeviceController.kt`, or any of the Task-7-owned fake-backend
  test suites.
- `BuzerApp`'s parameter list confirmed byte-identical via `git diff` (only indentation changed
  from the new `Box` wrapper).
- `refreshMorningReviewAvailability()` guard is the first statement, before
  `MorningReviewReconcileGuard.reconcile(...)`.
- `openHomeMorningReview()` has the same guard as its first statement.
- `morningReviewLauncher` callback rechecks readiness (`applyReadinessState(readinessController
  .refresh())`) before conditionally refreshing Morning Review availability.
- `performForegroundReadinessAction()` is wired only via `ForegroundReadinessGate`'s
  `onPrimaryAction = ::performForegroundReadinessAction` — no other call site.
- Exception handling in `performForegroundReadinessAction()` catches `Exception`, not `Throwable`.
- Step 8: 3/5 scenarios pass cleanly. The other 2 fail deterministically both in the full-class run
  and in isolation, for a documented, verified reason (`USE_EXACT_ALARM` manifest permission)
  unrelated to `MainActivity.kt`'s wiring — not the documented Task 5 crash flake, and not "my bug"
  per the task's own bug-attribution rule (it explicitly is not against the new `MainActivity`
  wiring; direct manual proof above shows that wiring behaving correctly).

## Known failures / follow-up needed

- `ForegroundReadinessInstrumentationTest#missingCapabilities_advanceInRequiredOrderOnEveryForeground`
  and `#missingCapability_neverLaunchesSettingsWithoutTap_andBackCannotDismiss` cannot pass against
  the current `AndroidManifest.xml` (`USE_EXACT_ALARM` permission makes the ExactAlarms readiness
  step permanently satisfied, unreachable-to-test). This needs a decision/fix in Task 5's test
  fixture or the manifest/readiness-policy design — out of Task 6's file scope
  (`MainActivity.kt` only).
- Did not verify whether this same `USE_EXACT_ALARM` gap affects Task 8's regression verification
  plans; flagging for whoever picks that up.

## Commands run

```
git worktree list
git status --short
./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin --console=plain   # PASS (x2)
ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh   # 3/5 pass, 2/5 fail (see above)
ANDROID_TEST_CLASS=...#missingCapabilities_advanceInRequiredOrderOnEveryForeground scripts/e2e-real-backend.sh      # fails identically in isolation
ANDROID_TEST_CLASS=...#missingCapability_neverLaunchesSettingsWithoutTap_andBackCannotDismiss scripts/e2e-real-backend.sh  # fails identically in isolation
git add client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt
git commit -m "feat: gate foreground on enforcement readiness"   # 37b9272
```

## Next recommended step

Hand to whoever owns Task 5/Task 8 follow-up (or open a `docs/decisions/` note) to resolve the
`USE_EXACT_ALARM` vs. readiness-gate `ExactAlarms` step mismatch before those two instrumentation
scenarios can be relied on as regression coverage. Task 7 (migrate the other MainActivity
instrumentation suites to grant readiness capabilities before launch) can proceed independently —
it isn't affected by this gap.
