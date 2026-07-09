package app.buzer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.pickers.WheelDateTimeField
import app.buzer.android.ui.pickers.WheelDurationField
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import app.buzer.shared.ui.commitment.CommitmentEditorValidation
import app.buzer.shared.ui.commitment.MinuteOfDay
import app.buzer.shared.ui.commitment.formatDurationLabel
import app.buzer.shared.ui.commitment.formatSaveLabel
import java.time.format.DateTimeFormatter

fun commitmentSaveLabel(start: DateTimeSelection?, durationMinutes: Int): String =
    formatSaveLabel(start?.let { MinuteOfDay(it.hour, it.minute) }, durationMinutes)

@Composable
fun CommitmentEditor(
    mode: CommitmentEditorMode,
    title: String,
    onTitleChange: (String) -> Unit,
    titleEditable: Boolean,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionExpanded: Boolean,
    onDescriptionExpandedChange: (Boolean) -> Unit,
    projectContent: @Composable () -> Unit,
    assigneeContent: @Composable () -> Unit,
    start: DateTimeSelection?,
    onStartChange: (DateTimeSelection) -> Unit,
    durationMinutes: Int,
    onDurationChange: (Int) -> Unit,
    validation: CommitmentEditorValidation,
    submitting: Boolean,
    enabled: Boolean,
    submitLabel: String,
    loadingLabel: String,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        validation.bannerMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (titleEditable) {
            BuzerSingleLineTextField(
                value = title,
                onValueChange = onTitleChange,
                label = "Title",
                enabled = enabled && !submitting,
                isError = validation.titleError != null,
                supportingText = validation.titleError,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = if (mode == CommitmentEditorMode.AddTask) "Title" else "Task",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ValidationText(validation.titleError)
            }
        }

        DescriptionEditor(
            description = description,
            onDescriptionChange = onDescriptionChange,
            expanded = descriptionExpanded,
            onExpandedChange = onDescriptionExpandedChange,
            enabled = enabled && !submitting
        )

        ReceiptSection(
            projectContent = projectContent,
            assigneeContent = assigneeContent,
            projectError = validation.projectError,
            assigneeError = validation.assigneeError,
            start = start,
            durationMinutes = durationMinutes
        )

        PickerField(
            label = "Start",
            error = validation.startError
        ) {
            WheelDateTimeField(
                selection = start,
                onSelectionChange = onStartChange,
                label = "Start picker",
                enabled = enabled && !submitting,
                modifier = Modifier.fillMaxWidth()
            )
        }

        PickerField(
            label = "Duration",
            error = validation.durationError
        ) {
            WheelDurationField(
                minutes = durationMinutes,
                onMinutesChange = onDurationChange,
                label = "Duration picker",
                enabled = enabled && !submitting,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = enabled && !submitting,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            LoadingActionButton(
                label = submitLabel,
                loadingLabel = loadingLabel,
                loading = submitting,
                onClick = onSubmit,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DescriptionEditor(
    description: String,
    onDescriptionChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    if (expanded) {
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
            enabled = enabled,
            label = { Text("Details") },
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 3,
            maxLines = 6
        )
        TextButton(
            onClick = { onExpandedChange(false) },
            enabled = enabled
        ) {
            Text("Less")
        }
    } else if (description.isBlank()) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Details")
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = description.trim(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled
        ) {
            Text("More")
        }
    }
}

@Composable
private fun ReceiptSection(
    projectContent: @Composable () -> Unit,
    assigneeContent: @Composable () -> Unit,
    projectError: String?,
    assigneeError: String?,
    start: DateTimeSelection?,
    durationMinutes: Int
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        HorizontalDivider()
        ReceiptSlotRow(label = "Project", content = projectContent)
        ValidationText(projectError)
        ReceiptSlotRow(label = "Assignee", content = assigneeContent)
        ValidationText(assigneeError)
        ReceiptTextRow(label = "Start", value = start?.formatForDisplay() ?: "Not set")
        ReceiptTextRow(label = "Duration", value = formatDurationLabel(durationMinutes))
        ReceiptTextRow(label = "Ends", value = start?.let { formatEndForReceipt(it, durationMinutes) } ?: "Not set")
        HorizontalDivider()
    }
}

@Composable
private fun ReceiptSlotRow(
    label: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun ReceiptTextRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(2f)
        )
    }
}

@Composable
private fun PickerField(
    label: String,
    error: String?,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
        ValidationText(error)
    }
}

@Composable
private fun ValidationText(message: String?) {
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

private val receiptDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")

private fun formatEndForReceipt(start: DateTimeSelection, durationMinutes: Int): String =
    start.date.atTime(start.hour, start.minute)
        .plusMinutes(durationMinutes.toLong())
        .format(receiptDateTimeFormatter)
