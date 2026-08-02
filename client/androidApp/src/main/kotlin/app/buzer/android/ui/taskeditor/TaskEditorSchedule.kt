package app.buzer.android.ui.taskeditor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.buzer.android.ui.theme.Spacing
import java.time.LocalDateTime

@Composable
internal fun ScheduleEditor(
    draft: TaskEditorDraft,
    now: LocalDateTime,
    validation: TaskEditorScheduleValidation,
    onScheduleChange: (TaskEditorSchedule) -> Unit,
    onNoSchedule: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var customDialog by remember { mutableStateOf<ScheduleCustomDialog?>(null) }
    val choices = scheduleQuickChoices(draft.schedule, now)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        scheduleValidationMessage(validation)?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        QuickChoiceGroup(
            label = "Date",
            isError = validation.highlightDate,
            enabled = enabled,
            customDescription = "Custom date",
            onCustom = { customDialog = ScheduleCustomDialog.Date }
        ) {
            choices.dates.forEachIndexed { index, choice ->
                QuickChoice(
                    label = choice.label,
                    selected = draft.schedule.date == choice.date,
                    enabled = enabled,
                    modifier = Modifier.testTag("dateQuickChoice$index"),
                    onClick = { onScheduleChange(draft.schedule.copy(date = choice.date)) }
                )
            }
        }
        QuickChoiceGroup(
            label = "Start time",
            isError = validation.highlightStartTime,
            enabled = enabled,
            customDescription = "Custom start time",
            onCustom = { customDialog = ScheduleCustomDialog.Time }
        ) {
            choices.startTimes.forEachIndexed { index, choice ->
                QuickChoice(
                    label = choice.label,
                    selected = draft.schedule.date == choice.date && draft.schedule.selectedTime() == choice.time,
                    enabled = enabled,
                    modifier = Modifier.testTag("startTimeQuickChoice$index"),
                    onClick = {
                        onScheduleChange(selectQuickStartTime(draft, choice).schedule)
                    }
                )
            }
        }
        QuickChoiceGroup(
            label = "Duration",
            isError = validation.highlightDuration,
            enabled = enabled,
            customDescription = "Custom duration",
            onCustom = { customDialog = ScheduleCustomDialog.Duration }
        ) {
            choices.durationMinutes.forEachIndexed { index, duration ->
                QuickChoice(
                    label = formatDurationLabel(duration),
                    selected = draft.schedule.durationMinutes == duration,
                    enabled = enabled,
                    modifier = Modifier.testTag("durationQuickChoice$index"),
                    onClick = { onScheduleChange(draft.schedule.copy(durationMinutes = duration)) }
                )
            }
        }
        if (draft.schedule.isComplete()) {
            val start = draft.schedule.startDateTime()!!
            val end = start.plusMinutes(draft.schedule.durationMinutes!!.toLong())
            Text(
                "Ends at %02d:%02d".format(end.hour, end.minute),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (draft.assignee?.isCurrentUser == false) {
            AssistChip(
                onClick = onNoSchedule,
                enabled = enabled,
                label = { Text("No schedule") },
                colors = AssistChipDefaults.assistChipColors()
            )
        }
    }

    when (customDialog) {
        null -> Unit
        ScheduleCustomDialog.Date -> DateWheelDialog(
            initialDate = draft.schedule.date ?: now.toLocalDate(),
            onApply = { date ->
                onScheduleChange(draft.schedule.copy(date = date))
                customDialog = null
            },
            onDismiss = { customDialog = null }
        )
        ScheduleCustomDialog.Time -> TimeWheelDialog(
            initialHour = draft.schedule.hour ?: now.hour,
            initialMinute = draft.schedule.minute ?: now.minute,
            onApply = { hour, minute ->
                onScheduleChange(draft.schedule.copy(hour = hour, minute = minute))
                customDialog = null
            },
            onDismiss = { customDialog = null }
        )
        ScheduleCustomDialog.Duration -> DurationWheelDialog(
            initialDurationMinutes = draft.schedule.durationMinutes ?: 30,
            onApply = { duration ->
                onScheduleChange(draft.schedule.copy(durationMinutes = duration))
                customDialog = null
            },
            onDismiss = { customDialog = null }
        )
    }
}

private enum class ScheduleCustomDialog { Date, Time, Duration }

@Composable
private fun QuickChoiceGroup(
    label: String,
    isError: Boolean,
    enabled: Boolean,
    customDescription: String,
    onCustom: () -> Unit,
    choices: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                choices()
            }
            IconButton(
                onClick = onCustom,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = customDescription }
            ) {
                Text("…")
            }
        }
    }
}

@Composable
private fun QuickChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { this.selected = selected },
        label = { Text(label) },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            AssistChipDefaults.assistChipColors()
        }
    )
}
