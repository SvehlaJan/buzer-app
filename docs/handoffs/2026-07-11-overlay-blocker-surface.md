# Handoff: Compact Overlay Blocker Reusing BlockerSurface

Date: 2026-07-11
Agent: Codex implementation worker
Plan: `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
Task: Task 9, "Compact Overlay Blocker Reusing BlockerSurface"

## Checkbox Status

- [x] Step 1: Add overlay model tests
- [x] Step 2: Add a Compose host for the service overlay window
- [x] Step 3: Render compact `BlockerSurface` in overlay
- [x] Step 4: Verify Task 9
- [x] Step 5: Commit

Plan/task/steps attempted: Task 9 only. No Task 10 polish or Task 11 cleanup work was started.

## Files Changed

- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/BlockerOverlayController.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayComposeHost.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/BlockerOverlayControllerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequestTest.kt`
- `docs/superpowers/plans/2026-07-08-android-ui-redesign-implementation.md`
- `docs/handoffs/2026-07-11-overlay-blocker-surface.md`

## Commands And Results

Verification:
- Command: `git status --short`
- Result: PASS
- Evidence: Initial worktree was clean before Task 9 edits.

Verification:
- Command: `npx ctx7@latest library "AndroidX ComposeView ViewTreeLifecycleOwner ViewTreeSavedStateRegistryOwner" "ComposeView in WindowManager overlay service lifecycle owners saved state registry"`
- Result: PASS
- Evidence: Resolved `/androidx/androidx` and `/websites/developer_android_develop_ui_compose`.

Verification:
- Command: `npx ctx7@latest docs "/androidx/androidx" "ComposeView ViewTreeLifecycleOwner ViewTreeSavedStateRegistryOwner WindowRecomposer service overlay"`
- Result: PASS
- Evidence: Docs covered `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed` and owner context requirements.

Verification:
- Command: `npx ctx7@latest docs "/androidx/androidx" "LifecycleRegistry SavedStateRegistryController SavedStateRegistryOwner performAttach performRestore currentState RESUMED DESTROYED"`
- Result: PASS
- Evidence: Docs covered `SavedStateRegistryController.performAttach`, `performRestore`, and lifecycle states.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android" "WindowManager.LayoutParams TYPE_APPLICATION_OVERLAY FLAG_KEEP_SCREEN_ON PixelFormat TRANSLUCENT addView removeView service overlay"`
- Result: PASS
- Evidence: Docs covered adding `TYPE_APPLICATION_OVERLAY` views via `WindowManager` and matching overlay window type.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected)
- Evidence: Compile failed because `OverlayBlockerRequest.fromExtras` did not accept `title`, `description`, `dueDatetime`, `durationMinutes`, or `startAt`, and `OverlayBlockerRequest` did not expose `blockerKind` or those fields.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed after adding start-only overlay request model data, snapshot enrichment, `OverlayComposeHost`, and compact Compose overlay rendering.

Verification:
- Command: `cd client && ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain`
- Result: PASS
- Evidence: Instrumentation sources compiled with the overlay changes.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed and `assembleDebug` completed successfully.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 24 tests with 0 skipped and 0 failed. The lane emitted the existing native-library strip warning for `libandroidx.graphics.path.so`; packaging and tests continued successfully.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: No whitespace errors.

Review-fix update, 2026-07-11:
- Fixed CODE QUALITY P2: `BlockerOverlayController.show()` now returns a Boolean and contains `WindowManager.addView()` failures through an injectable `OverlayWindow` facade. On add failure it destroys/disposes the host, leaves `host`/`view` unset, logs `overlay show failed`, and returns `false`.
- `OverlayBlockerService` now checks the `show()` result. If the overlay cannot be shown, it clears the controller, stops the service path, and does not start the resolution watcher.
- Added unit coverage for add failure cleanup, `OverlayBlockerRequest.writeTo()`/`fromIntent()` round trip, snapshot enrichment through `withTask()`, and null snapshot fallback.
- Plan/task/steps attempted: Task 9 review fix only. No Task 10 or Task 11 work was started.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (RED, expected review fix)
- Evidence: Compile failed because the new `BlockerOverlayControllerTest` referenced missing injectable `window`, `hostFactory`, `OverlayWindow`, `OverlayHostHandle`, Boolean `show()` result, and `isShowing`.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: FAIL (iteration)
- Evidence: The new request round-trip test failed because JVM unit tests use Android stub `Intent`; fixed by using a tiny `FakeIntent` subclass that overrides only the extra methods exercised by `writeTo()` and `fromIntent()`.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed after the add-failure containment fix and request-model test adjustments.

Verification:
- Command: `cd client && ./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug --console=plain`
- Result: PASS
- Evidence: Debug unit tests passed and `assembleDebug` completed successfully after the review fix.

Verification:
- Command: `scripts/e2e-fake-backend.sh`
- Result: PASS
- Evidence: Managed `pixel8Api34DebugAndroidTest` finished 24 tests with 0 skipped and 0 failed. The lane emitted the existing native-library strip warning for `libandroidx.graphics.path.so`; packaging and tests continued successfully.

Verification:
- Command: `git diff --check`
- Result: PASS
- Evidence: No whitespace errors after the review fix.

## Manual Verification

- Confirmed `OverlayBlockerRequest` is now start-only and carries task title, Todoist description, due datetime, duration minutes, start time, and `BlockerKind.OverlayStart`.
- Confirmed `OverlayBlockerService` enriches the parsed request from the latest cached snapshot before showing the overlay.
- Confirmed `BlockerOverlayController` no longer builds classic `TextView`/`Button` content; it renders `BlockerSurface` inside `BuzerTheme` as a constrained bottom sheet over a scrim.
- Confirmed overlay actions are limited to `I'm starting` and `Postpone 5 min`, routed to the existing start/postpone coordinator calls.
- Confirmed deadline and morning-review overlay starts are rejected conservatively; their existing full-screen notification/activity fallback remains in `AlarmReceiver`.
- Review-fix confirmation: failed `addView()` no longer escapes `show()`; the fake-window unit test verifies host cleanup and empty controller state.
- Review-fix confirmation: failed overlay show no longer starts the service resolution watcher.

## Known Failures Or Skipped Checks

- No required final checks failed.
- Direct overlay rendering/input verification was skipped. The existing fake-backend instrumentation harness launches activities and does not grant or exercise `SYSTEM_ALERT_WINDOW` for a service-owned `TYPE_APPLICATION_OVERLAY`; adding that would be broader than Task 9. The e2e lane still compiled and ran all existing blocker/activity coverage.
- Because direct overlay rendering was not exercised on-device, the Compose-in-service approach should be treated as needing a manual overlay permission smoke test before relying on it outside this branch.

## Assumptions

- The compact overlay is the Start blocker overlay path only. Deadline and Morning Review overlays were intentionally not implemented because the redesign spec says the overlay uses the Start action model and the task instructions warned not to invent new product behavior.
- Cached snapshot data is the right source for title/window/details at alarm time; if the cache is missing, `BlockerSurface` falls back to `Committed task` and `Schedule unavailable`.
- `DisposeOnViewTreeLifecycleDestroyed` plus explicit `disposeComposition()` on removal is acceptable; double disposal is safe.

## Next Recommended Step

Perform a manual device smoke test with overlay permission enabled: trigger a start blocker while unlocked, confirm the compact bottom sheet renders over the scrim, and tap both `I'm starting` and `Postpone 5 min` in separate runs to verify input and lifecycle behavior.
