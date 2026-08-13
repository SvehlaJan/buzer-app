# Foreground Enforcement Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hard-block normal Android app access until every capability required for reliable Buzer enforcement is healthy, rechecking on every foreground transition and after every permission or Settings result.

**Architecture:** Replace the unused list-style permission-health model with a pure ordered readiness policy, a thin Android capability reader, a session controller for runtime-permission and Settings results, and a non-dismissible full-screen Compose dialog layered above the continuously composed `BuzerApp`. `MainActivity` remains the single startup/foreground authority: it evaluates readiness synchronously before first composition, suppresses Morning Review entry while blocked, and resumes the preserved Home/Add/Edit composition only after the checker returns `Ready`.

**Tech Stack:** Kotlin, Android API 26–36, `NotificationManager`, `AlarmManager`, `PowerManager`, `Settings`, Activity Result APIs, Compose Multiplatform Material 3, JVM unit tests, Android instrumentation on managed API 34, and `scripts/e2e-real-backend.sh`.

---

## Implementer handoff and hard scope

This plan implements **Change 3 only** from:

- `docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md`

Changes 1 and 2 are already integrated into `master` at or after commit `c83c164`. Do not reimplement Smart Home, pull-to-refresh, or Morning Review. Do not add a Health & permissions navigation destination. Do not change the backend, snapshot contract, Todoist sync, WhatsApp, alarm identities, blocker actions, or Morning Review local-state rules.

The expected starting behavior is:

- `MainActivity` always composes `BuzerApp` and currently calls foreground refresh and Morning Review availability from `onResume`;
- `NotificationChannels.ensureCreated(this)` runs during `MainActivity.onCreate`;
- `AndroidPermissionHealthReader` and `PermissionHealth` exist but are unused by production UI;
- `BuzerApp` keeps Home/Add/Edit navigation and editor drafts in Compose state;
- automated Morning Review is launched by `MainActivity` through `MorningReviewEntryController`;
- the managed real-backend suite currently has 93 tests.

### Required start-of-session checks

From the integrated repository root:

```bash
cat AGENTS.md
cat docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md
cat docs/superpowers/specs/2026-08-02-smart-home-morning-review-readiness-design.md
cat docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md
cat docs/handoffs/2026-08-02-smart-home-and-true-refresh.md
cat docs/handoffs/2026-08-02-daily-morning-review.md
cat docs/notes/agent-workspaces.md
git worktree list
git status --short
```

Create a dedicated worktree from the latest integrated `master`; do not implement in the primary checkout or in this planning worktree:

```bash
git worktree add .worktrees/foreground-readiness -b feature/foreground-readiness master
```

In the worktree, export SDK paths instead of creating or copying `client/local.properties`:

```bash
export ANDROID_HOME=/Users/svehla/Library/Android/sdk
export ANDROID_SDK_ROOT=/Users/svehla/Library/Android/sdk
```

For Android API questions, use the repository-required lookup before changing the API contract:

```bash
npx ctx7@latest library "Android platform" "notification permission exact alarm full-screen intent overlay battery optimization readiness checks"
npx ctx7@latest docs "/websites/developer_android" "request special permissions and recheck in onResume"
android docs search "notification runtime permission"
android docs search "full screen intent Android 14"
android docs search "battery optimization exemption"
```

### Locked product and platform decisions

There are exactly five user-visible readiness steps, evaluated in this order:

1. Notifications
2. Exact alarms
3. Full-screen intent
4. Display over other apps
5. Battery optimization exemption

Notifications remain one user-visible step even though the checker evaluates these subrequirements in order:

1. `POST_NOTIFICATIONS` runtime permission on API 33+;
2. global app notifications enabled;
3. `blockers` channel exists and has at least `IMPORTANCE_HIGH`;
4. `pre_start` channel exists and is not `IMPORTANCE_NONE`;
5. `errors` channel exists and is not `IMPORTANCE_NONE`.

All three channels are required because each carries an existing product-critical path: blocker delivery, pre-start reminders, or visible backend-action failures. Recreating a channel must never overwrite user-selected importance; `NotificationChannels.ensureCreated` remains idempotent.

API behavior is fixed as follows:

| Capability | Required API check | Settings destination |
| --- | --- | --- |
| Notification runtime | API 33+: `checkSelfPermission(POST_NOTIFICATIONS)` | First attempt uses `RequestPermission`; after a denial/attempt, use app notification settings |
| App notifications | All supported APIs: `NotificationManager.areNotificationsEnabled()` | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `Settings.EXTRA_APP_PACKAGE` |
| Channel state | API 26+: `getNotificationChannel(id).importance` | `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` + app package + channel ID |
| Exact alarms | API 31+: `AlarmManager.canScheduleExactAlarms()`; earlier APIs are ready | `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` + `package:` URI |
| Full-screen intent | API 34+: `NotificationManager.canUseFullScreenIntent()`; earlier APIs are ready | `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` + `package:` URI |
| Overlay | API 23+: `Settings.canDrawOverlays(context)` | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` + `package:` URI |
| Battery exemption | API 23+: `PowerManager.isIgnoringBatteryOptimizations(packageName)` | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI |

The app has minSdk 26, compileSdk 36, and targetSdk 35. Add `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to the manifest for the package-scoped exemption request. This is acceptable for this personal sideloaded enforcement app because exact scheduled enforcement is its core function; do not generalize this choice to a future Play Store release without a policy review.

The gate never launches a platform prompt or Settings automatically. Every external action follows an explicit user tap. System Back, tapping outside, and normal app navigation cannot dismiss the gate. If checking or launching fails, show one Retry-only error state and log the exception; do not provide a bypass.

`BuzerApp` must remain composed behind the gate so Home/Add/Edit state and typed drafts survive. Use a non-dismissible full-screen Compose `Dialog`; do not conditionally remove `BuzerApp` from composition and do not introduce a separate readiness `Activity`.

## File map

### New production files

- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/EnforcementReadiness.kt` — pure capability snapshot, ordered requirements, result types, and policy.
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/AndroidEnforcementReadinessChecker.kt` — Android service reads and required-channel evaluation input.
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/ReadinessSettingsIntentFactory.kt` — package-scoped Settings intents only.
- `client/androidApp/src/main/kotlin/app/buzer/android/readiness/ForegroundReadinessController.kt` — synchronous gate state, notification-request history, denial, Settings return, retry, and action-in-flight handling.
- `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/ForegroundReadinessScreen.kt` — pure presentation builder plus full-screen non-dismissible dialog.

### Modified/deleted production files

- Modify `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt` — register launchers, evaluate readiness, render gate, guard Morning Review, and recheck on foreground/result.
- Modify `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt` — expose the ordered required-channel specifications without changing channel creation.
- Modify `client/androidApp/src/main/AndroidManifest.xml` — declare battery-optimization request permission.
- Delete `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt`.
- Delete `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidPermissionHealthReader.kt`.

### New tests and instrumentation support

- `client/androidApp/src/test/kotlin/app/buzer/android/readiness/EnforcementReadinessPolicyTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/readiness/ForegroundReadinessControllerTest.kt`
- `client/androidApp/src/test/kotlin/app/buzer/android/ui/ForegroundReadinessScreenStateTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ReadinessDeviceController.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessScreenTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessInstrumentationTest.kt`

### Existing instrumentation that must be migrated in this plan

- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- `client/androidApp/src/androidTest/kotlin/app/buzer/android/taskeditor/UnifiedTaskEditorRealBackendTest.kt`

These suites launch `MainActivity`; their reset paths must explicitly establish a ready managed-device baseline. Do not weaken production checks through test-only BuildConfig flags or intent extras.

### Documentation and verification

- Delete/replace `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt` as part of the test migration.
- Modify `scripts/adb-verify.sh` so permission output also includes `USE_EXACT_ALARM` and retains the existing filtered, app-scoped format.
- Create `docs/handoffs/2026-08-08-foreground-enforcement-readiness.md`.
- Update checkboxes in this plan as work completes.

## Task 1: Define the pure readiness contract and ordering

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/readiness/EnforcementReadiness.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/readiness/EnforcementReadinessPolicyTest.kt`
- Delete after the new suite is green: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt`

- [x] **Step 1: Write the RED policy matrix**

Create tests covering every first-failure boundary and multiple simultaneous failures. Use the following contract names exactly:

```kotlin
enum class EnforcementCapability(val step: Int) {
    Notifications(1),
    ExactAlarms(2),
    FullScreenIntent(3),
    Overlay(4),
    BatteryExemption(5)
}

data class RequiredNotificationChannel(
    val id: String,
    val displayName: String,
    val minimumImportance: Int
)

sealed interface ReadinessRequirement {
    val capability: EnforcementCapability

    data object NotificationRuntime : ReadinessRequirement {
        override val capability = EnforcementCapability.Notifications
    }
    data object AppNotifications : ReadinessRequirement {
        override val capability = EnforcementCapability.Notifications
    }
    data class NotificationChannel(
        val channelId: String,
        val channelName: String
    ) : ReadinessRequirement {
        override val capability = EnforcementCapability.Notifications
    }
    data object ExactAlarms : ReadinessRequirement {
        override val capability = EnforcementCapability.ExactAlarms
    }
    data object FullScreenIntent : ReadinessRequirement {
        override val capability = EnforcementCapability.FullScreenIntent
    }
    data object Overlay : ReadinessRequirement {
        override val capability = EnforcementCapability.Overlay
    }
    data object BatteryExemption : ReadinessRequirement {
        override val capability = EnforcementCapability.BatteryExemption
    }
}

sealed interface ReadinessSettingsDestination {
    data object AppNotifications : ReadinessSettingsDestination
    data class NotificationChannel(val channelId: String) : ReadinessSettingsDestination
    data object ExactAlarms : ReadinessSettingsDestination
    data object FullScreenIntent : ReadinessSettingsDestination
    data object Overlay : ReadinessSettingsDestination
    data object BatteryExemption : ReadinessSettingsDestination
}

sealed interface EnforcementReadiness {
    data object Ready : EnforcementReadiness
    data class NeedsRuntimePermission(
        val requirement: ReadinessRequirement.NotificationRuntime
    ) : EnforcementReadiness
    data class NeedsSettingsAction(
        val requirement: ReadinessRequirement,
        val destination: ReadinessSettingsDestination
    ) : EnforcementReadiness
}

data class EnforcementCapabilitySnapshot(
    val sdkInt: Int,
    val notificationRuntimeGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val notificationChannelImportance: Map<String, Int>,
    val exactAlarmsAllowed: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val overlayAllowed: Boolean,
    val batteryOptimizationExempt: Boolean
)
```

Test at least these cases:

- API 26 with every applicable capability true returns `Ready` without consulting runtime notification, exact-alarm, or full-screen-only requirements;
- API 31 requires exact alarms;
- API 33 first requires runtime notifications;
- API 34 requires full-screen intent after exact alarms;
- global notifications beat every channel and special capability;
- disabled `blockers`, `pre_start`, and `errors` channels each map to their exact channel destination;
- `blockers` importance below `IMPORTANCE_HIGH` fails while `pre_start` and `errors` pass at any importance above `IMPORTANCE_NONE`;
- multiple missing values always return Notifications → Exact → Full-screen → Overlay → Battery;
- all healthy API 34 values return `Ready`.

- [x] **Step 2: Run the new tests to prove RED**

From `client/`:

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*EnforcementReadinessPolicyTest' --console=plain
```

Expected: compilation fails because `app.buzer.android.readiness` and its types do not exist.

- [x] **Step 3: Implement the pure policy**

Implement `EnforcementReadinessPolicy.evaluate(snapshot, requiredChannels)` as one ordered return chain. Use API constants as numeric boundaries only (`33`, `31`, `34`) so the file remains free of Android service calls:

```kotlin
object EnforcementReadinessPolicy {
    fun evaluate(
        snapshot: EnforcementCapabilitySnapshot,
        requiredChannels: List<RequiredNotificationChannel>
    ): EnforcementReadiness {
        if (snapshot.sdkInt >= 33 && !snapshot.notificationRuntimeGranted) {
            return EnforcementReadiness.NeedsRuntimePermission(
                ReadinessRequirement.NotificationRuntime
            )
        }
        if (!snapshot.appNotificationsEnabled) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.AppNotifications,
                ReadinessSettingsDestination.AppNotifications
            )
        }
        requiredChannels.firstOrNull { channel ->
            (snapshot.notificationChannelImportance[channel.id] ?: 0) < channel.minimumImportance
        }?.let { channel ->
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel(channel.id, channel.displayName),
                ReadinessSettingsDestination.NotificationChannel(channel.id)
            )
        }
        if (snapshot.sdkInt >= 31 && !snapshot.exactAlarmsAllowed) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.ExactAlarms,
                ReadinessSettingsDestination.ExactAlarms
            )
        }
        if (snapshot.sdkInt >= 34 && !snapshot.fullScreenIntentAllowed) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.FullScreenIntent,
                ReadinessSettingsDestination.FullScreenIntent
            )
        }
        if (!snapshot.overlayAllowed) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.Overlay,
                ReadinessSettingsDestination.Overlay
            )
        }
        if (!snapshot.batteryOptimizationExempt) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.BatteryExemption,
                ReadinessSettingsDestination.BatteryExemption
            )
        }
        return EnforcementReadiness.Ready
    }
}
```

- [x] **Step 4: Run the focused policy suite GREEN**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*EnforcementReadinessPolicyTest' --console=plain
```

Expected: every ordering and API-boundary test passes.

- [x] **Step 5: Remove the obsolete list-model test and commit**

Delete `PermissionHealthTest.kt`; do not delete production legacy files until Task 2 has replaced their only role.

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/readiness/EnforcementReadiness.kt client/androidApp/src/test/kotlin/app/buzer/android/readiness/EnforcementReadinessPolicyTest.kt client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt
git commit -m "test: define enforcement readiness policy"
```

## Task 2: Read Android capabilities and build package-scoped Settings intents

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/readiness/AndroidEnforcementReadinessChecker.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/readiness/ReadinessSettingsIntentFactory.kt`
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt`
- Modify: `client/androidApp/src/main/AndroidManifest.xml`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt`
- Delete: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidPermissionHealthReader.kt`
- Test later at the real platform boundary: `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessInstrumentationTest.kt`

- [x] **Step 1: Expose ordered required-channel specifications**

Keep `ensureCreated` behavior unchanged and add this production list to `NotificationChannels`:

```kotlin
val requiredForEnforcement: List<RequiredNotificationChannel>
    get() = listOf(
        RequiredNotificationChannel(BLOCKERS, "Commitment blockers", NotificationManager.IMPORTANCE_HIGH),
        RequiredNotificationChannel(PRE_START, "Pre-start reminders", NotificationManager.IMPORTANCE_MIN),
        RequiredNotificationChannel(ERRORS, "Backend action failures", NotificationManager.IMPORTANCE_MIN)
    )
```

The list order is part of the readiness contract. Do not mutate channel importance after creation.

- [x] **Step 2: Add the Android checker interface and implementation**

Use this exact boundary:

```kotlin
fun interface EnforcementReadinessChecker {
    fun check(): EnforcementReadiness
}

class AndroidEnforcementReadinessChecker(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : EnforcementReadinessChecker {
    override fun check(): EnforcementReadiness {
        NotificationChannels.ensureCreated(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val snapshot = EnforcementCapabilitySnapshot(
            sdkInt = sdkInt,
            notificationRuntimeGranted = sdkInt < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            appNotificationsEnabled = notifications.areNotificationsEnabled(),
            notificationChannelImportance = NotificationChannels.requiredForEnforcement.associate { channel ->
                channel.id to (notifications.getNotificationChannel(channel.id)?.importance ?: 0)
            },
            exactAlarmsAllowed = sdkInt < 31 ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            fullScreenIntentAllowed = sdkInt < 34 || notifications.canUseFullScreenIntent(),
            overlayAllowed = Settings.canDrawOverlays(context),
            batteryOptimizationExempt = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        )
        return EnforcementReadinessPolicy.evaluate(
            snapshot = snapshot,
            requiredChannels = NotificationChannels.requiredForEnforcement
        )
    }
}
```

Do not catch platform exceptions here. The controller is the single error boundary and must distinguish check failure from a missing capability.

- [x] **Step 3: Implement the Settings intent factory**

Create intents with no `FLAG_ACTIVITY_NEW_TASK`; `MainActivity` launches them through `StartActivityForResult`:

```kotlin
class ReadinessSettingsIntentFactory(
    private val packageName: String
) {
    private val packageUri: Uri
        get() = Uri.parse("package:$packageName")

    fun create(destination: ReadinessSettingsDestination): Intent = when (destination) {
        ReadinessSettingsDestination.AppNotifications ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        is ReadinessSettingsDestination.NotificationChannel ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, destination.channelId)
        ReadinessSettingsDestination.ExactAlarms ->
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
        ReadinessSettingsDestination.FullScreenIntent ->
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
        ReadinessSettingsDestination.Overlay ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        ReadinessSettingsDestination.BatteryExemption ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
    }
}
```

The caller must call `resolveActivity(packageManager)` before launch. A null resolution is a launch failure, not a reason to open a generic unrelated settings page.

- [x] **Step 4: Add the battery request permission to the manifest**

Add exactly:

```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

Keep the existing notification, exact-alarm, full-screen, overlay, foreground-service, and boot permissions unchanged.

- [x] **Step 5: Delete the unused legacy production model**

Delete `PermissionHealth.kt` and `AndroidPermissionHealthReader.kt`. Verify no source references remain:

```bash
rg -n "PermissionHealth|AndroidPermissionHealthReader" client --glob '!**/build/**'
```

Expected: no matches.

- [x] **Step 6: Compile production and manifest sources**

```bash
./gradlew :androidApp:processDebugMainManifest :androidApp:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL` with API 34 methods guarded by `sdkInt >= 34` and exact-alarm methods guarded by `sdkInt >= 31`.

- [x] **Step 7: Commit the Android reader**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/readiness client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt client/androidApp/src/main/AndroidManifest.xml client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidPermissionHealthReader.kt
git commit -m "feat: inspect enforcement readiness"
```

## Task 3: Model foreground session state, denial, retry, and result rechecks

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/readiness/ForegroundReadinessController.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/readiness/ForegroundReadinessControllerTest.kt`

- [x] **Step 1: Write RED controller tests**

Cover these transitions with a queue-backed fake checker and in-memory request history:

- first missing runtime notification returns a request-permission action;
- beginning that action synchronously records that the prompt was attempted and marks the action in flight;
- a denied/cancelled result rechecks and maps the same missing runtime permission to app notification Settings;
- a process-restored controller with `wasNotificationPermissionRequested=true` also uses Settings rather than prompting again;
- Settings return rechecks the complete policy and can advance from Exact to Full-screen rather than assuming the previous requirement succeeded;
- a healthy refresh returns `Ready`;
- checker exceptions become one Retry-only error state;
- missing Settings activity or launcher exception becomes the same Retry-only error state;
- Retry invokes a fresh check;
- no action starts without `beginPrimaryAction()`.

Use these types exactly:

```kotlin
sealed interface ForegroundReadinessGateState {
    data object Ready : ForegroundReadinessGateState
    data class Blocking(
        val readiness: EnforcementReadiness,
        val actionInFlight: Boolean = false
    ) : ForegroundReadinessGateState
    data class Error(val message: String) : ForegroundReadinessGateState
}

sealed interface ForegroundReadinessAction {
    data object RequestNotificationPermission : ForegroundReadinessAction
    data class OpenSettings(
        val destination: ReadinessSettingsDestination
    ) : ForegroundReadinessAction
    data object Retry : ForegroundReadinessAction
}

interface NotificationPermissionRequestHistory {
    fun wasRequested(): Boolean
    fun markRequested()
}
```

- [x] **Step 2: Run the controller tests RED**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ForegroundReadinessControllerTest' --console=plain
```

Expected: compilation fails because controller/state/history do not exist.

- [x] **Step 3: Implement persistent and in-memory request history**

Use SharedPreferences file `buzer_enforcement_readiness`, key `notification_permission_requested`, and synchronous `commit()`:

```kotlin
class SharedPreferencesNotificationPermissionRequestHistory(
    context: Context
) : NotificationPermissionRequestHistory {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun wasRequested(): Boolean =
        preferences.getBoolean(KEY_NOTIFICATION_REQUESTED, false)

    override fun markRequested() {
        check(preferences.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).commit()) {
            "Could not persist notification permission request history"
        }
    }

    companion object {
        const val PREFERENCES_NAME = "buzer_enforcement_readiness"
        const val KEY_NOTIFICATION_REQUESTED = "notification_permission_requested"
    }
}

class InMemoryNotificationPermissionRequestHistory(
    private var requested: Boolean = false
) : NotificationPermissionRequestHistory {
    override fun wasRequested(): Boolean = requested
    override fun markRequested() { requested = true }
}
```

- [x] **Step 4: Implement the controller**

The controller owns only synchronous state; launchers remain in `MainActivity`:

```kotlin
class ForegroundReadinessController(
    private val checker: EnforcementReadinessChecker,
    private val requestHistory: NotificationPermissionRequestHistory,
    private val reportError: (String, Exception) -> Unit
) {
    var state: ForegroundReadinessGateState = ForegroundReadinessGateState.Error(
        "Device readiness has not been checked"
    )
        private set

    fun refresh(): ForegroundReadinessGateState {
        state = try {
            when (val readiness = checker.check()) {
                EnforcementReadiness.Ready -> ForegroundReadinessGateState.Ready
                is EnforcementReadiness.NeedsRuntimePermission -> {
                    if (requestHistory.wasRequested()) {
                        ForegroundReadinessGateState.Blocking(
                            EnforcementReadiness.NeedsSettingsAction(
                                ReadinessRequirement.AppNotifications,
                                ReadinessSettingsDestination.AppNotifications
                            )
                        )
                    } else {
                        ForegroundReadinessGateState.Blocking(readiness)
                    }
                }
                is EnforcementReadiness.NeedsSettingsAction ->
                    ForegroundReadinessGateState.Blocking(readiness)
            }
        } catch (error: Exception) {
            reportError("readiness_check_failed", error)
            ForegroundReadinessGateState.Error("Buzer couldn't verify device readiness.")
        }
        return state
    }

    fun beginPrimaryAction(): ForegroundReadinessAction? {
        val current = state
        val action = when (current) {
            ForegroundReadinessGateState.Ready -> null
            is ForegroundReadinessGateState.Error -> ForegroundReadinessAction.Retry
            is ForegroundReadinessGateState.Blocking -> when (val readiness = current.readiness) {
                EnforcementReadiness.Ready -> null
                is EnforcementReadiness.NeedsRuntimePermission -> {
                    requestHistory.markRequested()
                    ForegroundReadinessAction.RequestNotificationPermission
                }
                is EnforcementReadiness.NeedsSettingsAction ->
                    ForegroundReadinessAction.OpenSettings(readiness.destination)
            }
        }
        if (action != null && current is ForegroundReadinessGateState.Blocking) {
            state = current.copy(actionInFlight = true)
        }
        return action
    }

    fun onExternalResult(): ForegroundReadinessGateState = refresh()

    fun onLaunchFailure(error: Exception): ForegroundReadinessGateState {
        reportError("readiness_action_launch_failed", error)
        state = ForegroundReadinessGateState.Error("Buzer couldn't open the required system control.")
        return state
    }
}
```

If `markRequested()` fails, let the exception flow into the caller's launch-failure path and do not launch a prompt whose history was not durably recorded.

- [x] **Step 5: Run controller and policy tests GREEN**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*EnforcementReadinessPolicyTest' --tests '*ForegroundReadinessControllerTest' --console=plain
```

- [x] **Step 6: Commit session behavior**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/readiness/ForegroundReadinessController.kt client/androidApp/src/test/kotlin/app/buzer/android/readiness/ForegroundReadinessControllerTest.kt
git commit -m "feat: coordinate foreground readiness"
```

## Task 4: Render the accessible non-dismissible readiness gate

**Files:**
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/ForegroundReadinessScreen.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/ui/ForegroundReadinessScreenStateTest.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessScreenTest.kt`

- [x] **Step 1: Write RED pure presentation tests**

Define and test:

```kotlin
data class ForegroundReadinessScreenState(
    val stepLabel: String?,
    val title: String,
    val body: String,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val action: ForegroundReadinessAction
)

fun buildForegroundReadinessScreenState(
    gateState: ForegroundReadinessGateState
): ForegroundReadinessScreenState?
```

Lock exact copy and actions:

| Requirement | Step | Title | Primary |
| --- | --- | --- | --- |
| Runtime notification | 1 of 5 | `Allow notifications` | `Allow notifications` |
| Global notifications | 1 of 5 | `Turn on Buzer notifications` | `Open settings` |
| Channel | 1 of 5 | `Enable <channel name>` | `Open settings` |
| Exact alarms | 2 of 5 | `Allow exact alarms` | `Open settings` |
| Full-screen intent | 3 of 5 | `Allow full-screen alerts` | `Open settings` |
| Overlay | 4 of 5 | `Allow display over other apps` | `Open settings` |
| Battery | 5 of 5 | `Exclude Buzer from battery optimization` | `Open settings` |
| Error | no step | `Couldn't verify device readiness` | `Retry` |

Bodies must explain why each capability is required for commitment alarms/blockers. `Ready` returns null. An in-flight Blocking state retains content, disables the button, and uses `Opening settings…` or `Requesting permission…` as appropriate.

- [x] **Step 2: Run state tests RED**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ForegroundReadinessScreenStateTest' --console=plain
```

- [x] **Step 3: Implement the pure presentation builder**

Keep copy and requirement mapping outside composable branches. The builder must derive `stepLabel` from `requirement.capability.step`, never from list position.

- [x] **Step 4: Write RED Compose host tests**

Use one `setContent` per test with mutable gate state. Cover:

- dialog title and `Step N of 5` are visible;
- title has heading semantics and receives initial focus;
- only one enabled primary action exists;
- no Back, Close, Skip, Later, or Dismiss control exists;
- system Back does not dismiss the dialog;
- primary callback routes the exact `ForegroundReadinessAction`;
- changing from Exact to Full-screen moves focus to the new heading;
- error renders Retry only;
- 2.0 font scale can scroll to the primary action without clipping.

- [x] **Step 5: Implement the full-screen dialog**

Use this structure:

```kotlin
@Composable
fun ForegroundReadinessGate(
    state: ForegroundReadinessGateState,
    onPrimaryAction: () -> Unit
) {
    val screen = buildForegroundReadinessScreenState(state) ?: return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // safeDrawingPadding + vertically scrollable content
            // heading, step, explanation, and one primary button
        }
    }
}
```

Use `verticalScroll`, `safeDrawingPadding`, repository `Spacing`, Material 3 typography, `semantics { heading() }`, and a `FocusRequester` keyed by title. Do not add illustrations, secondary actions, or a health-detail list.

- [x] **Step 6: Run state and host tests GREEN**

```bash
./gradlew :androidApp:testDebugUnitTest --tests '*ForegroundReadinessScreenStateTest' :androidApp:compileDebugAndroidTestKotlin --console=plain
```

Then from repository root:

```bash
ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessScreenTest scripts/e2e-real-backend.sh
```

Expected: all readiness screen host tests pass.

- [x] **Step 7: Commit the gate UI**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/ForegroundReadinessScreen.kt client/androidApp/src/test/kotlin/app/buzer/android/ui/ForegroundReadinessScreenStateTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessScreenTest.kt
git commit -m "feat: render enforcement readiness gate"
```

## Task 5: Add hermetic device-readiness controls and RED MainActivity coverage

**Files:**
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ReadinessDeviceController.kt`
- Create: `client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessInstrumentationTest.kt`

- [x] **Step 1: Implement the instrumentation-only device controller**

Use `InstrumentationRegistry.getInstrumentation().uiAutomation`. Do not add production test flags. The helper must expose:

```kotlin
enum class DeviceReadinessCapability {
    Notifications,
    ExactAlarms,
    FullScreenIntent,
    Overlay,
    BatteryExemption
}

class ReadinessDeviceController {
    fun grantAll()
    fun deny(capability: DeviceReadinessCapability)
    fun clearNotificationRequestHistory()
    fun markNotificationRequested()
}
```

Implement commands with the target package name:

```text
pm grant <package> android.permission.POST_NOTIFICATIONS
pm revoke <package> android.permission.POST_NOTIFICATIONS
cmd appops set <package> POST_NOTIFICATION allow|deny
cmd appops set <package> SCHEDULE_EXACT_ALARM allow|deny
cmd appops set <package> USE_EXACT_ALARM allow|deny
cmd appops set <package> USE_FULL_SCREEN_INTENT allow|deny
cmd appops set <package> SYSTEM_ALERT_WINDOW allow|deny
cmd deviceidle whitelist +<package>
cmd deviceidle whitelist -<package>
```

Guard the notification permission commands below API 33, exact-alarm operations below API 31, and full-screen operations below API 34. Close every returned `ParcelFileDescriptor`. The helper only performs device-state operations; after each grant/deny call, the instrumentation test must use `composeRule.waitUntil` to poll UI backed by the real `AndroidEnforcementReadinessChecker`. Do not put fixed sleeps in the helper or treat a completed shell command as proof that Android has propagated the state.

The request-history helpers edit `buzer_enforcement_readiness` directly and use `commit()`.

- [x] **Step 2: Write the RED MainActivity instrumentation cases**

Use the real E2E backend and acknowledge today's Morning Review in test reset so readiness is the only startup gate. Add these tests:

1. `healthyStartup_showsHomeWithoutReadinessFlash`
2. `missingCapabilities_advanceInRequiredOrderOnEveryForeground`
3. `missingCapability_neverLaunchesSettingsWithoutTap_andBackCannotDismiss`
4. `restoringOverlay_preservesDirtyEditDraft`
5. `settingsIntents_arePackageScopedForEveryDestination`

For the sequential test:

- deny all five capabilities and persist notification-request history so step 1 uses app Settings rather than opening a runtime dialog;
- launch `MainActivity` and assert Notifications;
- grant Notifications, move scenario to `CREATED` then `RESUMED`, assert Exact alarms;
- grant Exact, repeat lifecycle transition, assert Full-screen;
- grant Full-screen, repeat, assert Overlay;
- grant Overlay, repeat, assert Battery;
- grant Battery, repeat, assert Home and absence of the gate.

For draft preservation:

- start ready;
- open Edit for a stable fixture task and enter a unique title suffix;
- deny Overlay and perform the foreground lifecycle transition;
- assert the gate covers the editor;
- grant Overlay and perform another foreground transition;
- assert the exact typed title remains in the editor.

For no-auto-launch, leave one capability missing, wait through Compose idleness, verify `MainActivity` remains resumed/current, press Back, and verify the same gate remains. Do not automate OEM Settings UI.

- [x] **Step 3: Run the new class to prove RED**

```bash
ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh
```

Expected: compile failures or assertions showing `MainActivity` still exposes Home and Morning Review without the gate.

- [x] **Step 4: Commit the RED instrumentation contract**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness
git commit -m "test: require foreground readiness gating"
```

## Task 6: Integrate readiness into MainActivity without losing app state

**Files:**
- Modify: `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
- Use existing: `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt` without moving its navigation state.

- [x] **Step 1: Register both Activity Result launchers before lifecycle use**

Add field launchers:

```kotlin
private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) {
    applyReadinessState(readinessController.onExternalResult())
}

private val readinessSettingsLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) {
    applyReadinessState(readinessController.onExternalResult())
}
```

Keep the existing Morning Review launcher separate.

- [x] **Step 2: Construct the checker/controller and synchronously evaluate startup**

Add Compose-observable state and lazy dependencies:

```kotlin
private var foregroundReadinessState by mutableStateOf<ForegroundReadinessGateState>(
    ForegroundReadinessGateState.Error("Device readiness has not been checked")
)

private val readinessController by lazy {
    ForegroundReadinessController(
        checker = AndroidEnforcementReadinessChecker(applicationContext),
        requestHistory = SharedPreferencesNotificationPermissionRequestHistory(applicationContext),
        reportError = { marker, error -> AppLog.error("$marker foreground readiness", error) }
    )
}

private val readinessSettingsIntentFactory by lazy {
    ReadinessSettingsIntentFactory(packageName)
}
```

In `onCreate`, call `NotificationChannels.ensureCreated(this)` first, then synchronously assign `foregroundReadinessState = readinessController.refresh()` before `setBuzerContent`. This prevents a healthy-start gate flash and ensures an unhealthy launch is blocked from the first composed frame.

- [x] **Step 3: Keep BuzerApp composed and layer the gate above it**

Inside the existing single `setBuzerContent` call:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    BuzerApp(
        // retain every existing parameter and callback unchanged
    )
    ForegroundReadinessGate(
        state = foregroundReadinessState,
        onPrimaryAction = ::performForegroundReadinessAction
    )
}
```

Do not put readiness state inside `BuzerApp` and do not change `AppSection`, editor keys, or `rememberSaveable` state.

- [x] **Step 4: Implement explicit action routing**

`performForegroundReadinessAction()` must:

- call `beginPrimaryAction()` only from the user tap;
- update Compose state immediately so duplicate taps are disabled;
- launch `POST_NOTIFICATIONS` only for `RequestNotificationPermission`;
- build and resolve the exact Settings intent for `OpenSettings`;
- call `refresh()` for Retry;
- catch only `Exception` from history/intent/launcher work, call `onLaunchFailure`, and keep the gate visible;
- never launch Settings from `onCreate`, `onResume`, state building, or composition.

Use:

```kotlin
if (intent.resolveActivity(packageManager) == null) {
    throw ActivityNotFoundException("No activity resolves ${intent.action}")
}
```

- [x] **Step 5: Recheck on every foreground transition**

At the start of `onResume`, after `super.onResume()` and before any Morning Review evaluation:

```kotlin
morningReviewEntryController.onResume()
applyReadinessState(readinessController.refresh())
refreshEnforcement(forceTodoist = false)
```

`applyReadinessState` must update state and:

- when `Ready`, call `refreshMorningReviewAvailability()` so post-08:00 catch-up resumes;
- when blocked/error, cancel `morningReviewReevaluationJob` and do not evaluate or launch Morning Review.

Keep ordinary mirror refresh active behind the gate so Home is current when access resumes.

- [x] **Step 6: Guard every Morning Review entry path in MainActivity**

Add a readiness guard to both `refreshMorningReviewAvailability()` and `openHomeMorningReview()`:

```kotlin
if (foregroundReadinessState != ForegroundReadinessGateState.Ready) {
    morningReviewReevaluationJob?.cancel()
    morningReviewReevaluationJob = null
    return
}
```

The Morning Review Activity Result callback must recheck readiness before refreshing availability. This prevents a revoked capability discovered while Review was open from immediately relaunching Review above the readiness gate.

- [x] **Step 7: Compile production and instrumentation sources**

```bash
./gradlew :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin --console=plain
```

- [x] **Step 8: Run the readiness instrumentation GREEN**

```bash
ANDROID_TEST_CLASS=app.buzer.android.readiness.ForegroundReadinessInstrumentationTest scripts/e2e-real-backend.sh
```

Expected: all five dedicated scenarios pass on managed API 34.

- [x] **Step 9: Commit MainActivity integration**

```bash
git add client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt
git commit -m "feat: gate foreground on enforcement readiness"
```

## Task 7: Migrate every existing MainActivity instrumentation suite

**Files:**
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt`
- Modify: `client/androidApp/src/androidTest/kotlin/app/buzer/android/taskeditor/UnifiedTaskEditorRealBackendTest.kt`

- [x] **Step 1: Establish ready state in each suite's existing reset boundary**

Instantiate `ReadinessDeviceController` once per class and call `grantAll()` before each `MainActivity` launch. Put the call in:

- `AddTaskFakeBackendTest.resetTestState()`;
- `EditTaskFakeBackendTest.resetTestState()`;
- `BlockerActivityFakeBackendTest.resetTestState(...)`;
- `UnifiedTaskEditorRealBackendTest.resetAuthoritativeState()`.

Also clear notification request history so tests that explicitly manipulate permission behavior start deterministically. Do not add duplicate shell helpers to individual classes.

- [x] **Step 2: Compile all migrated instrumentation**

```bash
./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain
```

- [x] **Step 3: Run Add/Edit suites together**

```bash
ANDROID_TEST_CLASS='app.buzer.android.addtask.AddTaskFakeBackendTest,app.buzer.android.addtask.EditTaskFakeBackendTest' scripts/e2e-real-backend.sh
```

Expected: every Add/Edit test passes without seeing the readiness gate.

- [x] **Step 4: Run the mixed Home/blocker/Morning Review suite**

```bash
ANDROID_TEST_CLASS=app.buzer.android.blockers.BlockerActivityFakeBackendTest scripts/e2e-real-backend.sh
```

Expected: every test passes, including automated Morning Review catch-up and editor-draft preservation. A readiness grant must not acknowledge Morning Review or alter its local clock.

- [x] **Step 5: Run unified editor integration**

```bash
ANDROID_TEST_CLASS=app.buzer.android.taskeditor.UnifiedTaskEditorRealBackendTest scripts/e2e-real-backend.sh
```

- [x] **Step 6: Commit the test migration**

```bash
git add client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/AddTaskFakeBackendTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/addtask/EditTaskFakeBackendTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/blockers/BlockerActivityFakeBackendTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/taskeditor/UnifiedTaskEditorRealBackendTest.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ReadinessDeviceController.kt client/androidApp/src/androidTest/kotlin/app/buzer/android/readiness/ForegroundReadinessInstrumentationTest.kt
git commit -m "test: isolate foreground readiness state"
```

## Task 8: Improve device evidence and complete regression verification

**Files:**
- Modify: `scripts/adb-verify.sh`
- Create: `docs/handoffs/2026-08-08-foreground-enforcement-readiness.md`
- Modify: `docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md`

- [x] **Step 1: Include both exact-alarm operations in compact permission evidence**

Change the existing app-op filter from:

```text
POST_NOTIFICATION|SCHEDULE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW
```

to:

```text
POST_NOTIFICATION|SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|USE_FULL_SCREEN_INTENT|SYSTEM_ALERT_WINDOW
```

Do not broaden `adb-verify.sh health` to raw unfiltered dumpsys output.

- [x] **Step 2: Run the complete client JVM/build gate**

From `client/`:

```bash
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:compileDebugAndroidTestKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL` and all readiness, Home, Morning Review, editor, scheduler, and enforcement unit tests pass.

- [x] **Step 3: Run backend verification even though backend code is unchanged**

From `backend/`:

```bash
npm test
npm run typecheck
npm run build
```

Expected: all existing backend tests, TypeScript checking, and build pass. Record the exact test count in the handoff.

- [x] **Step 4: Run the full unfiltered real-backend E2E suite**

From repository root:

```bash
env -u ANDROID_SERIAL -u ANDROID_TEST_CLASS scripts/e2e-real-backend.sh
```

Expected: the full suite passes with the new readiness tests included. Scoped passes are not sufficient because the gate changes every `MainActivity` startup.

**Achieved 2026-08-13.** The original crash was caused by in-process revocation of granted
`POST_NOTIFICATIONS`, which terminates the target UID; it was not a cumulative-resource or
Test-Orchestrator-only issue. The representative instrumentation hop now uses
FullScreenIntent -> Overlay without touching notifications. The focused readiness class passed
5/5, and the unfiltered command above passed 109/109 (`Finished 109 tests on pixel8Api34`,
`BUILD SUCCESSFUL in 6m 16s`). See
`docs/handoffs/2026-08-11-foreground-readiness-review-fixes.md` for the correction evidence.

- [ ] **Step 5: Perform the physical-device sequential setup smoke**

Build/install with the four existing production `BUZER_*` Gradle properties supplied to the worktree invocation; do not copy secrets into the worktree. On the connected Pixel 10:

1. launch with one or more missing capabilities;
2. confirm the gate shows one step at a time and never opens Settings without a tap;
3. grant Notifications → Exact alarms → Full-screen alerts → Overlay → Battery exemption;
4. confirm Home appears after the final return;
5. background/foreground once and confirm healthy Home returns without a gate flash;
6. open Edit, type a harmless unsaved suffix, revoke one capability, foreground the app, restore it, and confirm the draft survives;
7. run:

```bash
BUZER_DEVICE_SERIAL=<serial> scripts/adb-verify.sh health
```

Record only filtered app-op/whitelist/alarm evidence. Do not modify live Todoist tasks during this smoke.

- [x] **Step 6: Audit scope and repository hygiene**

```bash
git diff --check
git status --short
rg -n "PermissionHealth|AndroidPermissionHealthReader|Health & permissions" client --glob '!**/build/**'
rg -n "ForegroundReadinessGate|AndroidEnforcementReadinessChecker" client/androidApp/src/main client/androidApp/src/test client/androidApp/src/androidTest
git log --oneline --decorate -12
```

Expected:

- no whitespace errors or unrelated files;
- no legacy permission-health model or navigation copy;
- one production checker and one gate integration;
- no backend product changes;
- no automatic Settings launch, bypass action, test-only BuildConfig flag, or separate readiness Activity.

- [x] **Step 7: Write the required handoff**

Create `docs/handoffs/2026-08-08-foreground-enforcement-readiness.md` containing:

- plan/task checkbox status;
- branch/worktree and final commits;
- every production/test/documentation file changed;
- exact commands and PASS/FAIL/SKIPPED evidence;
- API-level behavior for 26, 31, 33, and 34+;
- notification channel requirements;
- runtime denial → app Settings behavior;
- foreground and Settings-result rechecks;
- Morning Review suppression/resumption;
- preserved Add/Edit draft evidence;
- physical-device verification and any skipped permission step;
- known failures and next recommended step.

Use the repository format for every verification entry:

```text
Verification:
- Command: <exact command>
- Result: PASS/FAIL/SKIPPED
- Evidence: <important output or reason>
```

- [x] **Step 8: Commit final documentation and progress**

Mark only actually completed steps in this plan.

```bash
git add scripts/adb-verify.sh docs/handoffs/2026-08-08-foreground-enforcement-readiness.md docs/superpowers/plans/2026-08-08-foreground-enforcement-readiness.md
git commit -m "docs: hand off foreground readiness"
```

- [x] **Step 9: Review, integrate, push, and confirm CI**

After code/spec review, merge the implementation branch into `master`, push, and run:

```bash
gh run list --workflow=ci.yml --limit 1
gh run watch <run-id> --exit-status
```

Do not report completion over a red CI run. The repository CI does not run managed-device instrumentation, so the local unfiltered E2E result remains mandatory release evidence.

**Completed 2026-08-13.** Merge commit `77e9a98` (`merge: add foreground enforcement readiness`)
was pushed to `master`. The post-merge focused readiness class passed all 5 tests (`BUILD
SUCCESSFUL in 52s`). GitHub Actions CI run `31732884633` passed: client in 3m 21s and backend in
44s.

## Completion criteria

Change 3 is complete only when all of the following are true:

- a healthy launch reaches Home without a transient readiness screen;
- every `MainActivity` background-to-foreground transition rechecks all capabilities;
- every runtime-permission and Settings result rechecks the complete ordered set;
- runtime notification permission is requested only after an explicit tap;
- a recorded denial/attempt routes subsequent notification recovery through app Settings;
- global notifications and all three required channels are checked;
- exact-alarm, full-screen-intent, overlay, and battery capabilities use the specified API guards and package-scoped Settings intents;
- missing capabilities hard-block normal app access one at a time in the accepted order;
- Back, outside taps, and hidden underlying controls cannot bypass the gate;
- unexpected check/launch failures show Retry only;
- Morning Review cannot auto-launch or open manually while readiness is blocked, then resumes catch-up after readiness becomes healthy;
- Home/Add/Edit stays composed and an unsaved editor draft survives the gate;
- the obsolete permission-health reader/model/test is removed rather than left as parallel authority;
- all existing MainActivity instrumentation establishes readiness explicitly and remains green;
- focused readiness tests and the full unfiltered real-backend suite pass;
- the physical Pixel 10 smoke and `adb-verify.sh health` evidence are recorded;
- no backend, Todoist, WhatsApp, Change 1, or Change 2 product behavior is mixed into the branch.
