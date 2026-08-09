package app.buzer.android.readiness

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * The five device-controlled capabilities [AndroidEnforcementReadinessChecker] reads. Mirrors
 * [EnforcementCapability] but lives in the instrumentation source set: this enum only exists to
 * drive [ReadinessDeviceController], not production code.
 */
enum class DeviceReadinessCapability {
    Notifications,
    ExactAlarms,
    FullScreenIntent,
    Overlay,
    BatteryExemption
}

/**
 * Instrumentation-only helper that mutates *real* device permission / app-op / power-management
 * state via `UiAutomation` (its shell-command runner, plus its dedicated runtime-permission API
 * where one exists), so tests can hermetically drive the real
 * [AndroidEnforcementReadinessChecker] through every readiness capability without automating OEM
 * Settings UI and without adding any production test-only flag.
 *
 * Every `grant`/`deny` call here only issues the underlying command. Completion is not proof the
 * platform has finished propagating the new permission/app-op state to this process -- callers
 * must poll the real UI (backed by [AndroidEnforcementReadinessChecker]) with
 * `composeRule.waitUntil` after each call. This class deliberately contains no `Thread.sleep` or
 * other fixed delay.
 */
class ReadinessDeviceController(
    private val packageName: String =
        ApplicationProvider.getApplicationContext<Context>().packageName,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** Grants every capability. Useful to establish a fully-ready baseline before denying one. */
    fun grantAll() {
        DeviceReadinessCapability.entries.forEach(::grant)
    }

    fun grant(capability: DeviceReadinessCapability) {
        setCapabilityState(capability, allow = true)
    }

    fun deny(capability: DeviceReadinessCapability) {
        setCapabilityState(capability, allow = false)
    }

    /**
     * Clears the persisted "already asked for the runtime notification permission" flag so the
     * next readiness check treats the runtime prompt as not-yet-requested.
     */
    fun clearNotificationRequestHistory() {
        editRequestHistory { editor -> editor.remove(REQUEST_HISTORY_KEY) }
    }

    /**
     * Marks the runtime notification permission as already requested, matching what
     * [ForegroundReadinessController] persists after it fires the one-shot runtime prompt. Tests
     * use this to skip straight to the "open app Settings" step instead of a runtime dialog.
     */
    fun markNotificationRequested() {
        editRequestHistory { editor -> editor.putBoolean(REQUEST_HISTORY_KEY, true) }
    }

    private fun setCapabilityState(capability: DeviceReadinessCapability, allow: Boolean) {
        when (capability) {
            DeviceReadinessCapability.Notifications -> setNotifications(allow)
            DeviceReadinessCapability.ExactAlarms -> setExactAlarms(allow)
            DeviceReadinessCapability.FullScreenIntent -> setFullScreenIntent(allow)
            DeviceReadinessCapability.Overlay -> setOverlay(allow)
            DeviceReadinessCapability.BatteryExemption -> setBatteryExemption(allow)
        }
    }

    private fun setNotifications(allow: Boolean) {
        // POST_NOTIFICATIONS is a runtime permission below API 33; there is nothing to grant or
        // revoke via UiAutomation/`appops` on older platforms because the permission does not
        // gate delivery there.
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return
        // Deliberately not `pm grant`/`pm revoke`: this instrumentation-only test module isn't
        // built with Android Test Orchestrator, so the JUnit runner and the app under test share
        // one OS process for the whole class. `pm grant`/`revoke` targets that shared process
        // from outside it via raw shell and can bring it down (observed as a `signal 9` kill of
        // the shared process ~200ms after a `pm revoke` call, compounded by prior real
        // `MainActivity` launches earlier in the same run). `UiAutomation.grantRuntimePermission`
        // / `revokeRuntimePermission` is the API built for mutating a permission held by the very
        // process instrumentation is attached to, and is what the platform itself recommends
        // (`pm revoke` logs "UiAutomation.revokeRuntimePermission() is more robust and should be
        // used instead of 'pm revoke'"). Same precedent as
        // MorningReviewAlarmInstrumentationTest's grantNotifications().
        if (allow) {
            uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            uiAutomation.revokeRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        runShell("cmd appops set $packageName POST_NOTIFICATION ${allowDeny(allow)}")
    }

    private fun setExactAlarms(allow: Boolean) {
        // SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM app-op gating was introduced in API 31 (S).
        if (sdkInt < Build.VERSION_CODES.S) return
        runShell("cmd appops set $packageName SCHEDULE_EXACT_ALARM ${allowDeny(allow)}")
        runShell("cmd appops set $packageName USE_EXACT_ALARM ${allowDeny(allow)}")
    }

    private fun setFullScreenIntent(allow: Boolean) {
        // USE_FULL_SCREEN_INTENT app-op gating was introduced in API 34 (UpsideDownCake).
        if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runShell("cmd appops set $packageName USE_FULL_SCREEN_INTENT ${allowDeny(allow)}")
    }

    private fun setOverlay(allow: Boolean) {
        runShell("cmd appops set $packageName SYSTEM_ALERT_WINDOW ${allowDeny(allow)}")
    }

    private fun setBatteryExemption(allow: Boolean) {
        val sign = if (allow) "+" else "-"
        runShell("cmd deviceidle whitelist $sign$packageName")
    }

    private fun allowDeny(allow: Boolean) = if (allow) "allow" else "deny"

    private fun editRequestHistory(mutate: (SharedPreferences.Editor) -> SharedPreferences.Editor) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(REQUEST_HISTORY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        check(mutate(preferences.edit()).commit()) {
            "Could not persist notification permission request history"
        }
    }

    /**
     * Executes a shell command via `UiAutomation` and fully drains and closes both the returned
     * [ParcelFileDescriptor] and the stream wrapping it, regardless of whether the command's
     * output is needed. Leaving either open leaks a file descriptor per call.
     */
    private fun runShell(command: String) {
        val descriptor: ParcelFileDescriptor = uiAutomation.executeShellCommand(command)
        descriptor.use { openDescriptor ->
            FileInputStream(openDescriptor.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }

    private companion object {
        // Same SharedPreferences file/key ForegroundReadinessController's production history
        // implementation uses (SharedPreferencesNotificationPermissionRequestHistory), read from
        // its public constants rather than duplicated string literals so the two can't drift.
        val REQUEST_HISTORY_PREFERENCES_NAME =
            SharedPreferencesNotificationPermissionRequestHistory.PREFERENCES_NAME
        val REQUEST_HISTORY_KEY =
            SharedPreferencesNotificationPermissionRequestHistory.KEY_NOTIFICATION_REQUESTED
    }
}
