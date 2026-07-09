package app.buzer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.buzer.android.ui.theme.Spacing

data class IconFabAction(
    val contentDescription: String,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit
) {
    init {
        require(contentDescription.isNotBlank()) {
            "Icon-only FAB actions require a non-blank content description."
        }
    }
}

@Composable
fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    ) {
        Text(label)
    }
}

@Composable
fun SecondaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    ) {
        Text(label)
    }
}

@Composable
fun IconFabStack(
    primaryAction: IconFabAction,
    modifier: Modifier = Modifier,
    secondaryActions: List<IconFabAction> = emptyList()
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.End
    ) {
        secondaryActions.forEach { action ->
            SmallFloatingActionButton(
                onClick = action.onClick,
                modifier = Modifier.semantics {
                    contentDescription = action.contentDescription
                }
            ) {
                action.icon()
            }
        }
        FloatingActionButton(
            onClick = primaryAction.onClick,
            modifier = Modifier.semantics {
                contentDescription = primaryAction.contentDescription
            }
        ) {
            primaryAction.icon()
        }
    }
}
