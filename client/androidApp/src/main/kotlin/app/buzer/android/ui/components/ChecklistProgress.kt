package app.buzer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.ChecklistItem

/** Renders direct-child progress only; callers opt into focused check/uncheck interaction. */
@Composable
fun ChecklistProgress(
    items: List<ChecklistItem>,
    interactive: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (ChecklistItem, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val ordered = items.sortedBy { it.order }
    Text("Checklist ${ordered.count { it.checked }}/${ordered.size}")
    if (!interactive) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ordered.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { checked -> onCheckedChange(item, checked) },
                    enabled = enabled
                )
                Text(item.title, modifier = Modifier.weight(1f))
            }
        }
    }
}
