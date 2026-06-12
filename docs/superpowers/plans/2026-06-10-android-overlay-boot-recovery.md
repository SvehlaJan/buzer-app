# Android Overlay Boot Recovery Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android MVP enforcement recover after reboot without opening the app, add a dev-device overlay blocker that can appear above most foreground apps, and prove both paths with real Pixel 5 manual tests.

**Architecture:** Keep the existing exact-alarm, blocker-activity, and full-screen-notification paths. Add a cached reconciliation API for boot recovery and an overlay-first delivery path for blocker alarms. The overlay is a foreground service with a native `WindowManager` view using `TYPE_APPLICATION_OVERLAY`; existing blocker activities remain the fallback.

**Tech Stack:** Kotlin Android, Kotlin coroutines, Compose Multiplatform UI for existing app screens, native Android `BroadcastReceiver`, `Service`, `WindowManager`, exact alarms, Android JVM tests, Pixel 5 real-device ADB verification.

---

## Research Grounding

Project decision: `docs/decisions/2026-06-10-android-overlay-enforcement-phase-1.md`.

Android references:

- `BroadcastReceiver.goAsync`: https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()
- `SYSTEM_ALERT_WINDOW`: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
- `TYPE_APPLICATION_OVERLAY`: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- Foreground service background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Foreground service types required: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Time-sensitive notifications and full-screen intents: https://developer.android.com/develop/ui/views/notifications/time-sensitive

Local findings:

- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReceiver.kt` delegates to `RescheduleAfterBoot.enqueue(context)`.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt` starts `MainActivity` from the boot receiver.
- Real Pixel 5 reboot test showed Android delivered `BOOT_COMPLETED` but blocked the background activity launch with `BAL_BLOCK`.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt` already has `refreshAndReconcile()` and `loadLatestSnapshot()`, but `reconcile(snapshot)` is private.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt` currently starts blocker activities directly and also posts full-screen notifications.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt` does not yet include overlay permission health.

## File Map

Modify:

- `client/androidApp/src/main/AndroidManifest.xml`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReceiver.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidPermissionHealthReader.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt`
- Existing permission health UI files found by `rg -n "PermissionHealth|exact_alarm|battery|notifications" client/androidApp/src`
- `client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugBlockerLauncherActivity.kt` or debug manifest entry
- Existing tests:
  - `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt`

Create:

- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayPermission.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequest.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/BlockerOverlayController.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BootReconcileRunnerTest.kt`, if the runner is factored with injectable dependencies
- `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequestTest.kt`
- `docs/handoffs/<date>-android-overlay-boot-recovery.md`

## Behavioral Contract

Boot:

- Receiving `BOOT_COMPLETED` must not launch an activity.
- If a cached snapshot exists, alarms are reconciled from cache before any network call.
- A live refresh may run after cached reconciliation, but it must be bounded by timeout and must not prevent `PendingResult.finish()`.
- Logs must make it obvious whether boot recovery received the broadcast, found cache, scheduled alarms, refreshed live state, timed out, or failed.

Overlay:

- Overlay permission missing: no service start attempt; post full-screen notification fallback; health shows an actionable error.
- Overlay permission granted: blocker alarms try to start `OverlayBlockerService`.
- Service displays a full-screen app overlay above most foreground apps.
- Overlay actions call the same coordinator methods as the blocker activities.
- On action completion or backend failure, overlay is removed. Backend failures are still reported through existing `BlockerErrorReporter` behavior.
- Existing blocker activities and notification actions remain available as fallback.

Manual verification:

- The overlay can appear while another normal app is foregrounded.
- A start/deadline blocker appears from an exact alarm while the phone is locked.
- After reboot, cached scheduled alarms are restored without opening Buzer.

---

### Task 1: Add Cached Reconciliation API

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`

- [x] **Step 1: Add failing tests for cached reconciliation**

Add tests that prove:

- `reconcileLatestSnapshot()` returns `false`, logs a cache miss, and does not schedule alarms when no cached snapshot exists.
- `reconcileLatestSnapshot()` returns `true`, cancels existing task alarms, and schedules alarms calculated from the cached snapshot when cache exists.
- The method does not call `api.getSnapshot()`.

Suggested test shape:

```kotlin
@Test
fun reconcileLatestSnapshotSchedulesFromCacheWithoutNetwork() {
    val api = FakeApi(snapshot = liveSnapshot)
    val store = FakeSnapshotStorage(cachedSnapshot)
    val scheduler = FakeAlarmScheduler()
    val coordinator = EnforcementCoordinator(
        api = api,
        snapshotStore = store,
        alarmScheduler = scheduler,
        errorReporter = FakeErrorReporter(),
        nowProvider = { "2026-06-10T08:00:00Z" }
    )

    assertTrue(coordinator.reconcileLatestSnapshot())
    assertEquals(0, api.snapshotCalls)
    assertTrue(scheduler.scheduled.isNotEmpty())
}
```

- [x] **Step 2: Implement the coordinator API**

Keep the private `reconcile(snapshot)` method as the single scheduling implementation and add a public wrapper:

```kotlin
fun reconcileLatestSnapshot(): Boolean {
    val snapshot = loadLatestSnapshot() ?: return false
    reconcile(snapshot)
    logger("cached reconcile complete scheduleVersion=${snapshot.scheduleVersion}")
    return true
}
```

- [x] **Step 3: Verify coordinator tests**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest'
```

Expected: `BUILD SUCCESSFUL`.

### Task 2: Replace Activity-Based Boot Recovery

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReceiver.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt`
- Keep or delete: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt`, depending on whether anything else references it

- [ ] **Step 1: Add boot runner with injectable work function**

Implement a small runner that accepts `Context`, `BroadcastReceiver.PendingResult`, and an injectable suspend block for tests. Production work should create the existing app dependencies and run:

1. `reconcileLatestSnapshot()`
2. `refreshAndReconcile()` inside `withTimeout(8_000)`

Skeleton:

```kotlin
object BootReconcileRunner {
    private const val REFRESH_TIMEOUT_MS = 8_000L

    fun run(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult,
        work: suspend (Context) -> Unit = ::runProductionWork
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppLog.i("boot_reconcile received")
                work(context.applicationContext)
                AppLog.i("boot_reconcile complete")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.e("boot_reconcile failed ${error.message}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runProductionWork(context: Context) {
        val coordinator = EnforcementDependencies.coordinator(context)
        val cached = coordinator.reconcileLatestSnapshot()
        AppLog.i("boot_reconcile cached=$cached")
        withTimeout(REFRESH_TIMEOUT_MS) {
            coordinator.refreshAndReconcile()
        }
        AppLog.i("boot_reconcile refresh=success")
    }
}
```

Adapt names to the existing logging/dependency helpers found in the Android module. If no `EnforcementDependencies` helper exists, follow the construction pattern currently used by `MainActivity` or blocker activities.

- [ ] **Step 2: Wire `BootReceiver` to `goAsync()`**

Replace the current activity-launch delegation:

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    BootReconcileRunner.run(context.applicationContext, goAsync())
}
```

- [ ] **Step 3: Remove dead boot activity path**

If `RescheduleAfterBoot` is no longer referenced, delete it and remove any stale boot extras from `MainActivity`. Keep this scoped: do not change normal app launch behavior.

- [ ] **Step 4: Verify boot code compiles**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

### Task 3: Add Overlay Permission Health

**Files:**

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayPermission.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidPermissionHealthReader.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt`
- Modify: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt`
- Modify permission health UI files found by search

- [ ] **Step 1: Add pure health tests**

Extend `PermissionHealth.evaluate(...)` with `overlayAllowed: Boolean`.

Add tests:

- Overlay missing returns an `error` item with key `overlay`.
- All permissions granted returns only the existing `all` ok item.

- [ ] **Step 2: Implement overlay permission helper**

```kotlin
object OverlayPermission {
    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

- [ ] **Step 3: Read overlay state in Android health**

Pass `overlayAllowed = OverlayPermission.canDraw(context)` from `AndroidPermissionHealthReader.read(context)`.

- [ ] **Step 4: Add setup affordance**

Update the permission health UI so the overlay error has an action that opens `OverlayPermission.settingsIntent(context)`.

Suggested copy:

- Key: `overlay`
- Severity: `error`
- Message: `Display over other apps permission is required for blocker overlays.`
- Action label: `Open overlay settings`

- [ ] **Step 5: Verify health tests**

Run:

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.PermissionHealthTest'
```

Expected: `BUILD SUCCESSFUL`.

### Task 4: Implement Overlay Service and View

**Files:**

- Modify: `client/androidApp/src/main/AndroidManifest.xml`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequest.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerService.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/BlockerOverlayController.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequestTest.kt`

- [ ] **Step 1: Add manifest declarations**

Add permissions:

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

Add service:

```xml
<service
    android:name=".enforcement.overlay.OverlayBlockerService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="personal commitment blocker overlay" />
</service>
```

- [ ] **Step 2: Add request parser tests**

Create a small request type that serializes/deserializes the same alarm extras used by `AlarmReceiver`.

Required fields:

- alarm kind
- task id for task blockers
- title/body if already available from the alarm extras

Tests should cover start blocker, deadline blocker, morning review, and missing invalid extras.

- [ ] **Step 3: Implement service startup helper**

Expose a helper:

```kotlin
object OverlayBlockerServiceStarter {
    fun tryStart(context: Context, request: OverlayBlockerRequest): Boolean {
        if (!OverlayPermission.canDraw(context)) return false
        return try {
            val intent = OverlayBlockerService.intent(context, request)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (error: RuntimeException) {
            false
        }
    }
}
```

Log skipped and failure reasons at call sites.

- [ ] **Step 4: Implement foreground service**

Service responsibilities:

- Parse `OverlayBlockerRequest`.
- Immediately call `startForeground(...)` with the existing blocker notification channel.
- Add overlay view through `BlockerOverlayController`.
- Remove overlay and stop service on action completion, invalid request, or service destruction.

For SDK 34+ use the special-use foreground-service type when calling `startForeground`.

- [ ] **Step 5: Implement native overlay view**

Use programmatic Android views in `BlockerOverlayController`:

- Root fills the screen.
- Show blocker title, summary, and action buttons.
- Use stable dimensions and large touch targets.
- START blocker actions: `Start now`, `Postpone 5 min`.
- DEADLINE blocker actions: `Done`, `Extend 30 min`, `Open full blocker`.
- MORNING blocker action: `Dismiss`.

`Open full blocker` may launch the existing deadline blocker activity for reschedule in Phase 1.

- [ ] **Step 6: Wire actions to coordinator**

Actions should call:

- `startTask(taskId)`
- `postponeStart(taskId)`
- `completeTask(taskId)`
- `extendTask(taskId)`
- `dismissMorningReview()`

On `CoordinatorActionResult.Success` or `FailedAndHidden`, remove the overlay and stop the service. Preserve existing backend failure reporting through coordinator methods.

### Task 5: Wire Alarm Receiver Overlay-First

**Files:**

- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`

- [ ] **Step 1: Preserve pre-start warnings**

Do not overlay for `PRE_START_WARNING`; keep current notification behavior.

- [ ] **Step 2: Try overlay for blocker alarms**

For `START_BLOCKER`, `DEADLINE_BLOCKER`, and morning review alarms:

1. Build `OverlayBlockerRequest`.
2. Call `OverlayBlockerServiceStarter.tryStart(...)`.
3. Log `overlay_start attempted result=<true|false> kind=<kind> taskId=<id>`.
4. Always post the existing full-screen notification fallback.

- [ ] **Step 3: Avoid direct background activity starts**

Remove direct `context.startActivity(targetIntent)` from the broadcast path unless it is guarded behind an explicitly foreground/debug-only condition. Full-screen notification should be the activity fallback.

- [ ] **Step 4: Verify receiver tests or compile coverage**

If existing tests can cover receiver branching, add them. Otherwise document compile-only coverage in the handoff and rely on manual device verification for broadcast behavior.

### Task 6: Add Debug Launch Path

**Files:**

- Modify or create debug-only launcher under `client/androidApp/src/debug/kotlin/app/buzer/android/debug/`
- Modify: `client/androidApp/src/debug/AndroidManifest.xml`
- Update: `docs/notes/local-development.md` or create `docs/notes/2026-06-10-android-overlay-testing.md`

- [ ] **Step 1: Add a debug overlay launch activity**

Create a debug-only way to launch a sample overlay from ADB without waiting for a real task alarm.

Example ADB command:

```bash
adb -s 11141FDD4002R1 shell am start \
  -n app.buzer.android/.debug.DebugOverlayLauncherActivity \
  --es kind START_BLOCKER \
  --es task_id debug-task
```

- [ ] **Step 2: Document overlay grant commands**

Manual grant path:

```bash
adb -s 11141FDD4002R1 shell am start \
  -a android.settings.action.MANAGE_OVERLAY_PERMISSION \
  -d package:app.buzer.android
```

ADB app-op shortcut for dev devices:

```bash
adb -s 11141FDD4002R1 shell cmd appops set app.buzer.android SYSTEM_ALERT_WINDOW allow
adb -s 11141FDD4002R1 shell cmd appops get app.buzer.android
```

### Task 7: Real Device Verification

**Files:**

- Create/update: `docs/handoffs/<date>-android-overlay-boot-recovery.md`
- Optionally update: `docs/notes/2026-06-10-android-overlay-testing.md`

> Token-efficient evidence: prefer `scripts/adb-verify.sh` for the logcat/alarms/perms/screenshot
> steps below (`logcat-clear`, `boot-log`, `alarms`, `perms`, `screenshot`, `install`, `health`).
> The explicit `adb` commands are kept for clarity, but the helper emits smaller, pre-filtered
> output and scopes to the Pixel 5 via `BUZER_DEVICE_SERIAL=11141FDD4002R1`. See
> `docs/notes/2026-06-12-agent-token-efficient-android-testing.md`.

- [ ] **Step 1: Run JVM tests and debug build**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install on Pixel 5**

```bash
cd client
ANDROID_SERIAL=11141FDD4002R1 ./gradlew :androidApp:installDebug
```

Expected: install succeeds.

- [ ] **Step 3: Grant and verify overlay permission**

Use the settings screen or dev app-op command from Task 6.

Verify:

```bash
adb -s 11141FDD4002R1 shell cmd appops get app.buzer.android | rg SYSTEM_ALERT_WINDOW
```

Expected: overlay app-op is allowed.

- [ ] **Step 4: Direct overlay smoke test**

Open another app, then launch the debug overlay:

```bash
adb -s 11141FDD4002R1 shell am start \
  -n app.buzer.android/.debug.DebugOverlayLauncherActivity \
  --es kind START_BLOCKER \
  --es task_id debug-task
```

Expected:

- Overlay is visible above the foreground app.
- `adb -s 11141FDD4002R1 logcat -d -s Buzer` includes overlay shown and dismissed/action logs.

- [ ] **Step 5: Real timed lock-screen test**

Create a short strict task against the live MVP backend, wait for the exact alarm with the phone locked, and verify:

- blocker surface appears
- action submits successfully or logs a controlled backend failure
- overlay is removed after action
- fallback full-screen notification still exists

- [ ] **Step 6: Real timed foreground-app test**

Open a normal foreground app before the task start/deadline alarm fires.

Expected:

- Overlay appears above the foreground app when permission is granted.
- If overlay start fails, full-screen notification fallback is posted.

- [ ] **Step 7: Boot recovery retest**

With a cached snapshot containing a future alarm:

```bash
adb -s 11141FDD4002R1 logcat -c
adb -s 11141FDD4002R1 reboot
adb -s 11141FDD4002R1 wait-for-device
adb -s 11141FDD4002R1 shell getprop sys.boot_completed
adb -s 11141FDD4002R1 logcat -d -s Buzer
```

Expected:

- Logs include `boot_reconcile received`.
- Logs include `boot_reconcile cached=true` when a cached snapshot exists.
- Logs include either live refresh success or bounded timeout/failure.
- Logs do not show `BAL_BLOCK` for `MainActivity`.
- `dumpsys alarm` shows Buzer alarms restored when the cached snapshot contains scheduled tasks.

## Completion Criteria

- Unit tests for cached reconciliation and permission health pass.
- Debug APK builds.
- Pixel 5 install succeeds.
- Overlay permission can be granted and detected.
- Debug overlay appears above another foreground app.
- Timed lock-screen and foreground-app manual tests are recorded.
- Reboot retest proves boot recovery no longer depends on starting `MainActivity`.
- Handoff contains exact commands, PASS/FAIL/SKIPPED status, and evidence.
