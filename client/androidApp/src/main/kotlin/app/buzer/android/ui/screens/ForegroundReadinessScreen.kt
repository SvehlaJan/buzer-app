package app.buzer.android.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.buzer.android.readiness.EnforcementReadiness
import app.buzer.android.readiness.ForegroundReadinessAction
import app.buzer.android.readiness.ForegroundReadinessGateState
import app.buzer.android.readiness.ReadinessRequirement
import app.buzer.android.ui.theme.Spacing

/**
 * Pure presentation model for the foreground enforcement readiness gate. All copy and
 * requirement-to-copy mapping live in plain functions below, outside any composable branch,
 * so they can be locked down by [ForegroundReadinessScreenStateTest]-style unit tests without a
 * Compose runtime.
 */
data class ForegroundReadinessScreenState(
    val stepLabel: String?,
    val title: String,
    val body: String,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val action: ForegroundReadinessAction
)

/**
 * Builds the screen state for a given gate state, or `null` when the app is ready and no gate
 * should render.
 */
fun buildForegroundReadinessScreenState(
    gateState: ForegroundReadinessGateState
): ForegroundReadinessScreenState? = when (gateState) {
    ForegroundReadinessGateState.Ready -> null
    is ForegroundReadinessGateState.Error -> ForegroundReadinessScreenState(
        stepLabel = null,
        title = "Couldn't verify device readiness",
        body = gateState.message,
        primaryLabel = "Retry",
        primaryEnabled = true,
        action = ForegroundReadinessAction.Retry
    )
    is ForegroundReadinessGateState.Blocking -> buildBlockingScreenState(gateState)
}

private fun buildBlockingScreenState(
    blocking: ForegroundReadinessGateState.Blocking
): ForegroundReadinessScreenState {
    val readiness = blocking.readiness
    val requirement: ReadinessRequirement = when (readiness) {
        EnforcementReadiness.Ready ->
            error("ForegroundReadinessGateState.Blocking cannot wrap EnforcementReadiness.Ready")
        is EnforcementReadiness.NeedsRuntimePermission -> readiness.requirement
        is EnforcementReadiness.NeedsSettingsAction -> readiness.requirement
    }
    val action: ForegroundReadinessAction = when (readiness) {
        EnforcementReadiness.Ready ->
            error("ForegroundReadinessGateState.Blocking cannot wrap EnforcementReadiness.Ready")
        is EnforcementReadiness.NeedsRuntimePermission ->
            ForegroundReadinessAction.RequestNotificationPermission
        is EnforcementReadiness.NeedsSettingsAction ->
            ForegroundReadinessAction.OpenSettings(readiness.destination)
    }
    val copy = requirementCopy(requirement)
    val idlePrimaryLabel = if (requirement is ReadinessRequirement.NotificationRuntime) {
        "Allow notifications"
    } else {
        "Open settings"
    }
    return ForegroundReadinessScreenState(
        // Derived from the requirement's own capability step, never from where this branch sits
        // in a `when`/list -- ExactAlarms is step 2 despite being the fourth requirement variant.
        stepLabel = "${requirement.capability.step} of 5",
        title = copy.title,
        body = copy.body,
        primaryLabel = if (blocking.actionInFlight) inFlightLabel(action) else idlePrimaryLabel,
        primaryEnabled = !blocking.actionInFlight,
        action = action
    )
}

private fun inFlightLabel(action: ForegroundReadinessAction): String = when (action) {
    ForegroundReadinessAction.RequestNotificationPermission -> "Requesting permission…"
    is ForegroundReadinessAction.OpenSettings -> "Opening settings…"
    ForegroundReadinessAction.Retry -> "Retrying…"
}

private data class RequirementCopy(val title: String, val body: String)

private fun requirementCopy(requirement: ReadinessRequirement): RequirementCopy = when (requirement) {
    ReadinessRequirement.NotificationRuntime -> RequirementCopy(
        title = "Allow notifications",
        body = "Buzer needs permission to send notifications so it can alert you the moment a " +
            "commitment blocker fires. Without it, Buzer can't notify you when a blocker starts."
    )
    ReadinessRequirement.AppNotifications -> RequirementCopy(
        title = "Turn on Buzer notifications",
        body = "Notifications for Buzer are turned off at the system level. Without them, Buzer " +
            "can't alert you when a commitment blocker fires. Turn them back on in system settings."
    )
    is ReadinessRequirement.NotificationChannel -> RequirementCopy(
        title = "Enable ${requirement.channelName}",
        body = "The \"${requirement.channelName}\" notification channel is silenced. Without it, " +
            "Buzer can't alert you when a commitment blocker fires. Enable it in system settings."
    )
    ReadinessRequirement.ExactAlarms -> RequirementCopy(
        title = "Allow exact alarms",
        body = "Buzer needs permission to schedule exact alarms so commitment blockers fire at " +
            "precisely the right time. Without it, your enforcement alarms may fire late or not at all."
    )
    ReadinessRequirement.FullScreenIntent -> RequirementCopy(
        title = "Allow full-screen alerts",
        body = "Buzer needs permission to show full-screen alerts so a blocker can take over your " +
            "screen the moment a commitment is due. Without it, a blocker can't take over the screen at the scheduled time."
    )
    ReadinessRequirement.Overlay -> RequirementCopy(
        title = "Allow display over other apps",
        body = "Buzer needs permission to display over other apps so a blocker can appear above " +
            "whatever you're doing. Without it, Buzer can't show a blocker above other apps."
    )
    ReadinessRequirement.BatteryExemption -> RequirementCopy(
        title = "Exclude Buzer from battery optimization",
        body = "Buzer needs to be excluded from battery optimization so Android doesn't delay or " +
            "kill scheduled enforcement. Without this, Android may delay or kill your alarms and blockers."
    )
}

/**
 * Full-screen, non-dismissible readiness gate. Renders nothing (and blocks nothing) once the
 * device is ready. There is deliberately no Back/Close/Skip/Later control anywhere in this tree:
 * the only way past the gate is completing the requested system action.
 */
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
            val headingFocusRequester = remember { FocusRequester() }
            LaunchedEffect(screen.title) {
                headingFocusRequester.requestFocus()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                screen.stepLabel?.let { stepLabel ->
                    Text(
                        text = "Step $stepLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("readiness-step")
                    )
                }
                Text(
                    text = screen.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .semantics { heading() }
                        .focusRequester(headingFocusRequester)
                        .focusable()
                        .testTag("readiness-title")
                )
                Text(
                    text = screen.body,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onPrimaryAction,
                    enabled = screen.primaryEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("readiness-primary-action")
                ) {
                    Text(screen.primaryLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
