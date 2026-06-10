# 2026-06-10 Android Overlay Enforcement Phase 1

## Status

Accepted for MVP implementation planning.

## Context

The Pixel 5 reboot test in `docs/handoffs/2026-06-10-boot-completed-reboot-test.md` proved that Android delivers `BOOT_COMPLETED` to Buzer, but the current receiver path tries to start `MainActivity` from the background. Android blocks that launch with `BAL_BLOCK`, so alarms are not recovered after reboot until the user manually opens the app.

The MVP also needs a stronger blocker surface than a normal activity/full-screen notification path. This project runs only on trusted development devices, so the app can require heavier user-granted privileges when they materially improve enforcement.

## Decision

Phase 1 will implement three changes:

1. Replace activity-based boot recovery with background-safe cached reconciliation.
2. Add a `SYSTEM_ALERT_WINDOW` app overlay blocker surface.
3. Verify the result with real device timed tests across lock screen, foreground-app, and reboot scenarios.

Boot recovery should not depend on network availability. On `BOOT_COMPLETED`, the app will first reschedule alarms from the latest cached snapshot, then optionally attempt a live refresh with strict timeout and logging. This avoids the current background activity launch failure and gives the app a recovery path even when the VPS or Tailscale route is temporarily unavailable.

Overlay enforcement will be additive. The existing blocker activities and full-screen notifications remain as fallback surfaces. Alarm delivery should try the overlay path first when overlay permission is granted, then still post the full-screen notification fallback.

## Android Platform Grounding

Research sources:

- Android `BroadcastReceiver.goAsync`: https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
- Android `SYSTEM_ALERT_WINDOW`: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
- Android `TYPE_APPLICATION_OVERLAY`: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- Android foreground service background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android foreground service types required: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Android time-sensitive notifications: https://developer.android.com/develop/ui/views/notifications/time-sensitive

Important constraints from the platform docs:

- `goAsync()` allows a receiver to continue short work after `onReceive`, but the broadcast still has a strict execution window and `PendingResult.finish()` must be called.
- `SYSTEM_ALERT_WINDOW` is a special user-granted permission. Apps request it by sending users to `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, and runtime state is checked with `Settings.canDrawOverlays()`.
- `TYPE_APPLICATION_OVERLAY` can draw above application windows but below critical system windows such as the status bar, IME, and some system UI. Android can change overlay position, size, or visibility at any time.
- Foreground service starts from the background are restricted, but exact alarms, `BOOT_COMPLETED`, user interactions, battery-optimization exemption, and overlay-related cases can be exemptions. Because Buzer targets SDK 35, any new foreground service must declare a valid `foregroundServiceType` and related permission.
- Full-screen intents are not a guarantee while the user is actively using the device; Android can show a heads-up notification instead.

## Implementation Direction

### Boot recovery

Use `BootReceiver.goAsync()` for bounded work:

- Log receipt of `BOOT_COMPLETED`.
- Load the latest cached snapshot.
- Reconcile and schedule alarms from cache immediately.
- Attempt `refreshAndReconcile()` with a strict timeout.
- Always call `PendingResult.finish()`.
- Never start `MainActivity` from the boot receiver.

This keeps boot work short and background-safe while preserving best-effort live state correction.

### Overlay blocker

Add a dedicated overlay service:

- Manifest permissions:
  - `android.permission.SYSTEM_ALERT_WINDOW`
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
- Service declaration:
  - `android:foregroundServiceType="specialUse"`
  - `android:exported="false"`
  - `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explaining the personal commitment blocker use.
- Window type:
  - `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`

The overlay can be implemented as a simple native Android view hosted by `WindowManager`. This avoids Compose lifecycle complexity inside a service and keeps Phase 1 focused.

### Fallback behavior

The overlay is not a security boundary. It cannot cover every system surface and can be disabled by the user. Buzer should therefore:

- Treat overlay permission as a required health item for the stronger dev-device MVP.
- Fall back to existing blocker activities/full-screen notifications when overlay is unavailable or service startup fails.
- Keep detailed `Buzer` log lines for overlay skipped, overlay shown, overlay action submitted, and overlay dismissed.

## Consequences

Positive:

- Reboot no longer silently disables scheduled enforcement.
- Foreground-app use cases get a blocker surface that can appear above most other apps.
- MVP testing becomes clearer because boot and overlay paths emit direct logs.

Tradeoffs:

- The app now requires a special Android permission that must be granted per device.
- A foreground overlay service adds manifest and notification requirements.
- This still cannot block system UI, permission screens, lock credential screens, or every OEM-specific edge case.

## Deferred

- Accessibility-service based blocking.
- Device-owner or kiosk-mode enforcement.
- Play-distribution policy work for special-use foreground service declarations.
- WorkManager adoption for longer boot/network recovery work.
