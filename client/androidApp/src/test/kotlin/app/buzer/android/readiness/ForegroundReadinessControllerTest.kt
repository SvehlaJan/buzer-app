package app.buzer.android.readiness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pops one scripted result per [check] call so multi-refresh sequences (e.g. Settings
 * advancing from one requirement to the next) can be scripted precisely. Each queued
 * entry is a closure so it can either return a value or throw, modelling both a healthy
 * checker and one that fails (missing Settings activity, SecurityException, etc).
 */
private class QueueEnforcementReadinessChecker(
    private val results: MutableList<() -> EnforcementReadiness>
) : EnforcementReadinessChecker {
    var callCount = 0
        private set

    override fun check(): EnforcementReadiness {
        callCount++
        require(results.isNotEmpty()) { "QueueEnforcementReadinessChecker exhausted after $callCount call(s)" }
        return results.removeAt(0).invoke()
    }
}

private fun queueOf(vararg results: () -> EnforcementReadiness) =
    QueueEnforcementReadinessChecker(results.toMutableList())

private class RecordingErrorReporter {
    data class Report(val marker: String, val error: Exception)

    val reports = mutableListOf<Report>()

    val reporter: (String, Exception) -> Unit = { marker, error -> reports.add(Report(marker, error)) }
}

private val NEEDS_RUNTIME_PERMISSION =
    EnforcementReadiness.NeedsRuntimePermission(ReadinessRequirement.NotificationRuntime)

private val NEEDS_APP_NOTIFICATIONS_SETTINGS = EnforcementReadiness.NeedsSettingsAction(
    ReadinessRequirement.AppNotifications,
    ReadinessSettingsDestination.AppNotifications
)

private val NEEDS_EXACT_ALARMS_SETTINGS = EnforcementReadiness.NeedsSettingsAction(
    ReadinessRequirement.ExactAlarms,
    ReadinessSettingsDestination.ExactAlarms
)

private val NEEDS_FULL_SCREEN_INTENT_SETTINGS = EnforcementReadiness.NeedsSettingsAction(
    ReadinessRequirement.FullScreenIntent,
    ReadinessSettingsDestination.FullScreenIntent
)

class ForegroundReadinessControllerTest {

    // --- Runtime permission prompt path -------------------------------------------

    @Test
    fun firstMissingRuntimeNotificationSurfacesRequestPermissionAction() {
        val checker = queueOf({ NEEDS_RUNTIME_PERMISSION })
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = { _, _ -> }
        )

        val state = controller.refresh()

        assertEquals(ForegroundReadinessGateState.Blocking(NEEDS_RUNTIME_PERMISSION), state)
        assertEquals(ForegroundReadinessAction.RequestNotificationPermission, controller.beginPrimaryAction())
    }

    @Test
    fun beginningRequestPermissionActionMarksHistoryAndActionInFlightSynchronously() {
        val checker = queueOf({ NEEDS_RUNTIME_PERMISSION })
        val history = InMemoryNotificationPermissionRequestHistory()
        val controller = ForegroundReadinessController(checker, history, reportError = { _, _ -> })
        controller.refresh()

        val action = controller.beginPrimaryAction()

        assertEquals(ForegroundReadinessAction.RequestNotificationPermission, action)
        assertTrue(history.wasRequested())
        assertEquals(
            ForegroundReadinessGateState.Blocking(NEEDS_RUNTIME_PERMISSION, actionInFlight = true),
            controller.state
        )
    }

    @Test
    fun beginPrimaryActionPropagatesRequestHistoryPersistenceFailureRatherThanSwallowingIt() {
        val checker = queueOf({ NEEDS_RUNTIME_PERMISSION })
        val history = object : NotificationPermissionRequestHistory {
            override fun wasRequested(): Boolean = false
            override fun markRequested(): Nothing =
                throw IllegalStateException("Could not persist notification permission request history")
        }
        val controller = ForegroundReadinessController(checker, history, reportError = { _, _ -> })
        val stateBeforeAction = controller.refresh()

        assertFailsWith<IllegalStateException> { controller.beginPrimaryAction() }

        // The controller must not have silently caught the failure and returned an action as if
        // the request history were durably recorded, nor corrupted its own state in the process —
        // callers (MainActivity) are the ones responsible for catching this and routing it through
        // onLaunchFailure(), matching a prompt whose history failed to persist never being launched.
        assertEquals(stateBeforeAction, controller.state)
    }

    // --- Denied/cancelled prompt routes to Settings, using recorded history --------

    @Test
    fun deniedPromptRechecksAndRoutesSameMissingRuntimePermissionToAppNotificationSettings() {
        val checker = queueOf({ NEEDS_RUNTIME_PERMISSION }, { NEEDS_RUNTIME_PERMISSION })
        val history = InMemoryNotificationPermissionRequestHistory()
        val controller = ForegroundReadinessController(checker, history, reportError = { _, _ -> })
        controller.refresh()
        controller.beginPrimaryAction() // launches the prompt; records history synchronously

        val stateAfterDenial = controller.onExternalResult()

        val blocking = assertIs<ForegroundReadinessGateState.Blocking>(stateAfterDenial)
        val readiness = assertIs<EnforcementReadiness.NeedsSettingsAction>(blocking.readiness)
        assertEquals(ReadinessRequirement.AppNotifications, readiness.requirement)
        assertEquals(ReadinessSettingsDestination.AppNotifications, readiness.destination)
        assertEquals(
            ForegroundReadinessGateState.Blocking(NEEDS_APP_NOTIFICATIONS_SETTINGS),
            stateAfterDenial
        )
        assertEquals(2, checker.callCount)
    }

    @Test
    fun processRestoredControllerWithPriorRequestHistoryRoutesToSettingsOnFirstRefresh() {
        val checker = queueOf({ NEEDS_RUNTIME_PERMISSION })
        val history = InMemoryNotificationPermissionRequestHistory(requested = true)
        val controller = ForegroundReadinessController(checker, history, reportError = { _, _ -> })

        val state = controller.refresh()

        assertEquals(ForegroundReadinessGateState.Blocking(NEEDS_APP_NOTIFICATIONS_SETTINGS), state)
        assertEquals(1, checker.callCount)
    }

    // --- Settings return rechecks the full policy, never assumes success -----------

    @Test
    fun settingsReturnRechecksFullPolicyAndAdvancesFromExactAlarmsToFullScreenIntent() {
        val checker = queueOf({ NEEDS_EXACT_ALARMS_SETTINGS }, { NEEDS_FULL_SCREEN_INTENT_SETTINGS })
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = { _, _ -> }
        )
        controller.refresh()
        val action = controller.beginPrimaryAction()
        assertEquals(
            ForegroundReadinessAction.OpenSettings(ReadinessSettingsDestination.ExactAlarms),
            action
        )

        val stateAfterSettingsReturn = controller.onExternalResult()

        assertEquals(
            ForegroundReadinessGateState.Blocking(NEEDS_FULL_SCREEN_INTENT_SETTINGS),
            stateAfterSettingsReturn
        )
        assertEquals(2, checker.callCount)
    }

    // --- Healthy refresh -------------------------------------------------------------

    @Test
    fun healthyRefreshReturnsReady() {
        val checker = queueOf({ EnforcementReadiness.Ready })
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = { _, _ -> }
        )

        val state = controller.refresh()

        assertEquals(ForegroundReadinessGateState.Ready, state)
        assertEquals(ForegroundReadinessGateState.Ready, controller.state)
        assertNull(controller.beginPrimaryAction())
    }

    // --- Checker exceptions become a single Retry-only error state -----------------

    @Test
    fun checkerExceptionBecomesRetryOnlyErrorStateAndReportsMarker() {
        val thrown = IllegalStateException("boom")
        val checker = queueOf({ throw thrown })
        val reporter = RecordingErrorReporter()
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = reporter.reporter
        )

        val state = controller.refresh()

        assertEquals(
            ForegroundReadinessGateState.Error("Buzer couldn't verify device readiness."),
            state
        )
        assertEquals(1, reporter.reports.size)
        assertEquals("readiness_check_failed", reporter.reports.single().marker)
        assertSame(thrown, reporter.reports.single().error)
        assertEquals(ForegroundReadinessAction.Retry, controller.beginPrimaryAction())
    }

    // --- Missing Settings activity / launcher exception maps to the same error -----

    @Test
    fun launchFailureBecomesSameRetryOnlyErrorStateRegardlessOfPriorState() {
        val checker = queueOf({ NEEDS_EXACT_ALARMS_SETTINGS })
        val reporter = RecordingErrorReporter()
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = reporter.reporter
        )
        controller.refresh()
        controller.beginPrimaryAction() // e.g. MainActivity about to launch the Settings intent
        val thrown = IllegalStateException("resolveActivity was null")

        val state = controller.onLaunchFailure(thrown)

        assertEquals(
            ForegroundReadinessGateState.Error("Buzer couldn't open the required system control."),
            state
        )
        assertEquals(1, reporter.reports.size)
        assertEquals("readiness_action_launch_failed", reporter.reports.single().marker)
        assertSame(thrown, reporter.reports.single().error)
        assertEquals(ForegroundReadinessAction.Retry, controller.beginPrimaryAction())
    }

    @Test
    fun launchFailureOverridesAReadyStateToo() {
        val checker = queueOf({ EnforcementReadiness.Ready })
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = { _, _ -> }
        )
        controller.refresh()
        assertEquals(ForegroundReadinessGateState.Ready, controller.state)

        val state = controller.onLaunchFailure(IllegalStateException("unexpected"))

        assertEquals(
            ForegroundReadinessGateState.Error("Buzer couldn't open the required system control."),
            state
        )
    }

    // --- Retry invokes a fresh check --------------------------------------------------

    @Test
    fun retryActionFromErrorStateLeadsToAFreshCheckOnNextRefresh() {
        val checker = queueOf({ throw IllegalStateException("boom") }, { EnforcementReadiness.Ready })
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = { _, _ -> }
        )
        controller.refresh()
        assertIs<ForegroundReadinessGateState.Error>(controller.state)

        val action = controller.beginPrimaryAction()
        assertEquals(ForegroundReadinessAction.Retry, action)
        assertEquals(1, checker.callCount) // Retry alone must not have re-invoked the checker yet

        val stateAfterRetry = controller.refresh()

        assertEquals(ForegroundReadinessGateState.Ready, stateAfterRetry)
        assertEquals(2, checker.callCount)
    }

    // --- beginPrimaryAction() from Ready is a no-op ------------------------------------

    @Test
    fun readyStateHasNoPrimaryAction() {
        val checker = queueOf({ EnforcementReadiness.Ready })
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = { _, _ -> }
        )
        controller.refresh()

        assertNull(controller.beginPrimaryAction())
    }

    // --- No action starts without beginPrimaryAction() ---------------------------------

    @Test
    fun repeatedRefreshNeverMarksActionInFlightOrHistoryWithoutBeginPrimaryAction() {
        val checker = queueOf(
            { NEEDS_RUNTIME_PERMISSION },
            { NEEDS_RUNTIME_PERMISSION },
            { NEEDS_RUNTIME_PERMISSION }
        )
        val history = InMemoryNotificationPermissionRequestHistory()
        val controller = ForegroundReadinessController(checker, history, reportError = { _, _ -> })

        repeat(3) {
            val state = controller.refresh()
            val blocking = assertIs<ForegroundReadinessGateState.Blocking>(state)
            assertFalse(blocking.actionInFlight)
        }

        assertFalse(history.wasRequested())
    }

    @Test
    fun repeatedRefreshOnSettingsBlockingStateNeverSetsActionInFlight() {
        val checker = queueOf({ NEEDS_EXACT_ALARMS_SETTINGS }, { NEEDS_EXACT_ALARMS_SETTINGS })
        val controller = ForegroundReadinessController(
            checker,
            InMemoryNotificationPermissionRequestHistory(),
            reportError = { _, _ -> }
        )

        controller.refresh()
        val secondState = controller.refresh()

        val blocking = assertIs<ForegroundReadinessGateState.Blocking>(secondState)
        assertFalse(blocking.actionInFlight)
    }
}
