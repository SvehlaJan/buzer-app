package app.buzer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.buzer.android.ui.theme.Spacing

@Composable
fun TaskDetailsBlock(
    description: String?,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    val trimmed = description?.trim().orEmpty()
    if (trimmed.isBlank()) return

    var expanded by remember(trimmed) { mutableStateOf(initiallyExpanded) }
    var measuredOverflow by remember(trimmed) { mutableStateOf(false) }
    val inferredOverflow = trimmed.length > 160 || trimmed.lineSequence().count() > 3
    val canExpand = measuredOverflow || inferredOverflow

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = "Details",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = trimmed,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded && result.hasVisualOverflow) {
                    measuredOverflow = true
                }
            }
        )
        if (canExpand) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) {
                Text(if (expanded) "Less" else "More")
            }
        }
    }
}
