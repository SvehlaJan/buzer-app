# Android Native Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Android-native exact alarms, full-screen blocker screens, boot rescheduling, notification channels, permission health, and task-entry widget.

**Architecture:** Shared KMP code provides task snapshots and state calculations. Android-native code owns scheduling and OS integration. Blocker activities refresh from the backend before showing actions, then call backend endpoints and reconcile alarms.

**Tech Stack:** Android AlarmManager, BroadcastReceiver, full-screen notification intents, Jetpack Compose, Android App Widgets or Glance, Kotlin coroutines, Android unit/instrumentation tests.

---

## Prerequisites

- Complete `2026-05-08-04-kmp-shared-and-android-shell.md`.
- Backend snapshot endpoint exists or a local fake is available.
- Use Context7 for Android exact alarm, full-screen notification, boot receiver, and widget docs before coding.

## Files

- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmScheduler.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidAlarmScheduler.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReceiver.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmIntentFactory.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/NotificationChannels.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/StartBlockerActivity.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/DeadlineBlockerActivity.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/blockers/MorningReviewActivity.kt`
- Create: `client/androidApp/src/main/kotlin/app/buzer/android/widget/AddTaskWidgetProvider.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/AlarmIntentFactoryTest.kt`
- Create: `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt`
- Modify: `client/androidApp/src/main/AndroidManifest.xml`
- Modify: Android shell screens to call enforcement APIs.

## Task 1: Resolve Android Platform Docs

- [x] **Step 1: Fetch exact alarm and full-screen docs**

Run:

```bash
npx ctx7@latest library "Android Developers" "exact alarms full-screen notifications boot receiver app widget Android 14 permissions"
npx ctx7@latest docs "/resolved/android/id" "SCHEDULE_EXACT_ALARM full-screen intent notification boot completed receiver app widget permissions"
```

Record resolved ID and exact permission requirements in `docs/notes/YYYY-MM-DD-android-permission-findings.md`.

- [x] **Step 2: Update Android manifest permissions**

Add these permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

The receiver and blocker activity registrations are added in Tasks 3 and 5.

## Task 2: Define Alarm Model And Intent Factory

- [x] **Step 1: Write `AlarmIntentFactoryTest.kt`**

```kotlin
package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmIntentFactoryTest {
    @Test
    fun createsStableRequestCodes() {
        assertEquals(
            AlarmIntentFactory.requestCode("task-1", AlarmKind.START_BLOCKER),
            AlarmIntentFactory.requestCode("task-1", AlarmKind.START_BLOCKER)
        )
    }

    @Test
    fun usesDifferentCodesForDifferentKinds() {
        val start = AlarmIntentFactory.requestCode("task-1", AlarmKind.START_BLOCKER)
        val deadline = AlarmIntentFactory.requestCode("task-1", AlarmKind.DEADLINE_BLOCKER)
        kotlin.test.assertNotEquals(start, deadline)
    }
}
```

- [x] **Step 2: Implement `AlarmIntentFactory.kt`**

```kotlin
package app.buzer.android.enforcement

enum class AlarmKind {
    MORNING_REVIEW,
    PRE_START_WARNING,
    START_BLOCKER,
    DEADLINE_BLOCKER
}

object AlarmIntentFactory {
    const val EXTRA_TASK_ID = "app.buzer.extra.TASK_ID"
    const val EXTRA_ALARM_KIND = "app.buzer.extra.ALARM_KIND"

    fun requestCode(taskId: String, kind: AlarmKind): Int {
        return "$taskId:${kind.name}".hashCode()
    }
}
```

- [x] **Step 3: Run unit test**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.enforcement.AlarmIntentFactoryTest
```

Expected: pass.

## Task 3: Implement Alarm Scheduler

- [x] **Step 1: Create `AlarmScheduler.kt`**

```kotlin
package app.buzer.android.enforcement

data class ScheduledAlarm(
    val taskId: String,
    val kind: AlarmKind,
    val triggerAtEpochMillis: Long
)

interface AlarmScheduler {
    fun schedule(alarm: ScheduledAlarm)
    fun cancel(taskId: String, kind: AlarmKind)
    fun cancelAllForTask(taskId: String)
}
```

- [x] **Step 2: Implement `AndroidAlarmScheduler.kt`**

```kotlin
package app.buzer.android.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(alarm: ScheduledAlarm) {
        val pendingIntent = pendingIntent(alarm.taskId, alarm.kind)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            alarm.triggerAtEpochMillis,
            pendingIntent
        )
    }

    override fun cancel(taskId: String, kind: AlarmKind) {
        alarmManager.cancel(pendingIntent(taskId, kind))
    }

    override fun cancelAllForTask(taskId: String) {
        AlarmKind.entries.forEach { cancel(taskId, it) }
    }

    private fun pendingIntent(taskId: String, kind: AlarmKind): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            putExtra(AlarmIntentFactory.EXTRA_ALARM_KIND, kind.name)
        }
        return PendingIntent.getBroadcast(
            context,
            AlarmIntentFactory.requestCode(taskId, kind),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
```

- [x] **Step 3: Register receiver**

Add to manifest:

```xml
<receiver
    android:name=".enforcement.AlarmReceiver"
    android:exported="false" />
```

## Task 4: Implement Notification Channels And Alarm Receiver

- [x] **Step 1: Implement `NotificationChannels.kt`**

```kotlin
package app.buzer.android.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val PRE_START = "pre_start"
    const val BLOCKERS = "blockers"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            PRE_START,
            "Pre-start reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ))
        manager.createNotificationChannel(NotificationChannel(
            BLOCKERS,
            "Commitment blockers",
            NotificationManager.IMPORTANCE_HIGH
        ))
    }
}
```

- [x] **Step 2: Implement `AlarmReceiver.kt`**

```kotlin
package app.buzer.android.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.buzer.android.blockers.DeadlineBlockerActivity
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.blockers.StartBlockerActivity

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val kindName = intent.getStringExtra(AlarmIntentFactory.EXTRA_ALARM_KIND) ?: return
        val kind = AlarmKind.valueOf(kindName)

        val targetIntent = when (kind) {
            AlarmKind.MORNING_REVIEW -> Intent(context, MorningReviewActivity::class.java)
            AlarmKind.START_BLOCKER -> Intent(context, StartBlockerActivity::class.java)
            AlarmKind.DEADLINE_BLOCKER -> Intent(context, DeadlineBlockerActivity::class.java)
            AlarmKind.PRE_START_WARNING -> null
        }

        if (targetIntent != null) {
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (taskId != null) targetIntent.putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            context.startActivity(targetIntent)
        }
    }
}
```

- [x] **Step 3: Build**

```bash
cd client
./gradlew :androidApp:assembleDebug
```

Expected: pass.

## Task 5: Implement Full-Screen Blocker Activities

- [x] **Step 1: Register activities in manifest**

```xml
<activity
    android:name=".blockers.StartBlockerActivity"
    android:excludeFromRecents="true"
    android:showWhenLocked="true"
    android:turnScreenOn="true" />

<activity
    android:name=".blockers.DeadlineBlockerActivity"
    android:excludeFromRecents="true"
    android:showWhenLocked="true"
    android:turnScreenOn="true" />

<activity
    android:name=".blockers.MorningReviewActivity"
    android:excludeFromRecents="true"
    android:showWhenLocked="true"
    android:turnScreenOn="true" />
```

- [ ] **Step 2: Implement blocker activity pattern**

Each blocker activity extends `ComponentActivity`, reads the task ID if present, refreshes snapshot from backend before actions, and renders Compose content.

`StartBlockerActivity` actions:

```kotlin
// I'm starting
// 1. call api.startTask(taskId)
// 2. finish()

// Postpone 5 min
// 1. call api.postponeStart(taskId)
// 2. schedule START_BLOCKER for now + 5 minutes
// 3. finish()
```

`DeadlineBlockerActivity` actions:

```kotlin
// Done
// 1. call api.completeTask(taskId)
// 2. cancel all task alarms
// 3. finish()

// Extend 30 min
// 1. call api.extendTask(taskId)
// 2. refresh snapshot
// 3. schedule new deadline blocker
// 4. finish()

// Reschedule
// 1. show exact deadline and duration form
// 2. call api.rescheduleTask(taskId, request)
// 3. refresh snapshot
// 4. schedule recomputed start/deadline alarms
// 5. finish()
```

`MorningReviewActivity` actions:

```kotlin
// Complete review
// 1. ensure invalid tasks are resolved
// 2. finish()

// Dismiss
// 1. call /review/dismiss
// 2. schedule MORNING_REVIEW for now + 5 minutes
// 3. finish()
```

- [x] **Step 3: Add UI tests or manual-test note**

If Compose activity tests are set up, add tests for button visibility. If not, write `docs/notes/YYYY-MM-DD-blocker-manual-test.md` with the manual launch commands:

```bash
adb shell am start -n app.buzer.android/.blockers.StartBlockerActivity --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.blockers.DeadlineBlockerActivity --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.blockers.MorningReviewActivity
```

## Task 6: Implement Boot Rescheduling

- [x] **Step 1: Implement `BootReceiver.kt`**

```kotlin
package app.buzer.android.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RescheduleAfterBoot.enqueue(context)
        }
    }
}
```

- [ ] **Step 2: Implement `RescheduleAfterBoot`**

Create `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt`:

```kotlin
package app.buzer.android.enforcement

import android.content.Context
import android.content.Intent
import app.buzer.android.MainActivity

object RescheduleAfterBoot {
    fun enqueue(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_BOOT_RESCHEDULE_REQUIRED, true)
        }
        context.startActivity(intent)
    }

    const val EXTRA_BOOT_RESCHEDULE_REQUIRED = "app.buzer.extra.BOOT_RESCHEDULE_REQUIRED"
}
```

In `MainActivity`, when `EXTRA_BOOT_RESCHEDULE_REQUIRED` is true, refresh the backend snapshot and reschedule local alarms. This keeps the first MVP implementation deterministic without adding WorkManager. If a later agent chooses WorkManager for background-only boot recovery, that agent must write a decision document in `docs/decisions/`.

- [x] **Step 3: Register boot receiver**

```xml
<receiver
    android:name=".enforcement.BootReceiver"
    android:enabled="true"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

## Task 7: Implement Permission Health

- [x] **Step 1: Write `PermissionHealthTest.kt`**

```kotlin
package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertTrue

class PermissionHealthTest {
    @Test
    fun reportsMissingExactAlarmAsError() {
        val result = PermissionHealth.evaluate(
            exactAlarmGranted = false,
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            bootReceiverEnabled = true
        )
        assertTrue(result.any { it.key == "exact_alarm" && it.level == "error" })
    }
}
```

- [x] **Step 2: Implement `PermissionHealth.kt`**

```kotlin
package app.buzer.android.enforcement

data class PermissionHealthItem(
    val key: String,
    val level: String,
    val message: String
)

object PermissionHealth {
    fun evaluate(
        exactAlarmGranted: Boolean,
        notificationsGranted: Boolean,
        batteryOptimizationIgnored: Boolean,
        bootReceiverEnabled: Boolean
    ): List<PermissionHealthItem> {
        val items = mutableListOf<PermissionHealthItem>()
        if (!exactAlarmGranted) items += PermissionHealthItem("exact_alarm", "error", "Exact alarm permission is required.")
        if (!notificationsGranted) items += PermissionHealthItem("notifications", "error", "Notification permission is required.")
        if (!batteryOptimizationIgnored) items += PermissionHealthItem("battery", "warning", "Battery optimization may delay enforcement.")
        if (!bootReceiverEnabled) items += PermissionHealthItem("boot", "warning", "Boot rescheduling is not active.")
        return items.ifEmpty { listOf(PermissionHealthItem("all", "ok", "Device enforcement permissions look ready.")) }
    }
}
```

- [x] **Step 3: Run test**

```bash
cd client
./gradlew :androidApp:testDebugUnitTest --tests app.buzer.android.enforcement.PermissionHealthTest
```

Expected: pass.

## Task 8: Implement Add Task Widget

- [x] **Step 1: Create widget provider**

Implement `AddTaskWidgetProvider` so tapping the widget opens `MainActivity` or a dedicated add-task activity with an extra:

```kotlin
const val EXTRA_OPEN_ADD_TASK = "app.buzer.extra.OPEN_ADD_TASK"
```

- [x] **Step 2: Add widget metadata**

Create widget XML resources for minimum size and label. The widget does not create tasks directly; it opens the required-field add task UI.

- [ ] **Step 3: Manual widget check**

Install debug app and add widget on device. Verify tap opens add task flow.

Record result in `docs/notes/YYYY-MM-DD-widget-manual-test.md`.

## Task 9: Final Verification And Handoff

- [ ] **Step 1: Run automated verification**

```bash
cd client
./gradlew :shared:allTests :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: pass.

- [ ] **Step 2: Run manual device checklist**

On a physical Android device:

```bash
adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n app.buzer.android/.blockers.StartBlockerActivity --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.blockers.DeadlineBlockerActivity --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.blockers.MorningReviewActivity
```

Expected:

- each blocker opens full-screen
- screen can be dismissed only through designed actions
- buttons match the spec
- locked-screen behavior is verified manually if permissions are granted

- [ ] **Step 3: Commit**

```bash
git add client docs/notes docs/decisions docs/superpowers/plans/2026-05-08-05-android-native-enforcement.md
git commit -m "feat: add Android native enforcement"
```

- [ ] **Step 4: Write handoff**

Create `docs/handoffs/YYYY-MM-DD-android-enforcement.md` with automated test results, manual device results, permission state, and remaining device-specific risks.
