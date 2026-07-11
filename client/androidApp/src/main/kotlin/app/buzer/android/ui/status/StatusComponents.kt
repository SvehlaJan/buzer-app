package app.buzer.android.ui.status

import app.buzer.android.ui.components.AttentionStrip
import app.buzer.android.ui.components.AttentionStripModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.buzer.android.ui.theme.BuzerTheme
import app.buzer.android.ui.theme.Spacing

@Composable
fun InsetSafeContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        content()
    }
}

fun ComponentActivity.setBuzerContent(content: @Composable () -> Unit) {
    setContent {
        BuzerTheme {
            InsetSafeContent {
                content()
            }
        }
    }
}

@Composable
fun InlineLoadingStatus(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun WarningStatus(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AttentionStrip(
        model = AttentionStripModel(
            message = message,
            actionLabel = "Retry".takeIf { onRetry != null }
        ),
        modifier = modifier,
        onAction = onRetry
    )
}

@Composable
fun BlockingLoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        InlineLoadingStatus(message)
    }
}

@Composable
fun BlockingErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WarningStatus(message = message, onRetry = onRetry)
    }
}

@Composable
fun LoadingActionButton(
    label: String,
    loadingLabel: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
    ) {
        Text(
            text = if (loading) loadingLabel else label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun ErrorAlertDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    dismissLabel: String? = "Dismiss"
) {
    AlertDialog(
        onDismissRequest = {
            if (dismissLabel != null) {
                onDismiss()
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            if (retryLabel != null && onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(
                        text = retryLabel,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        },
        dismissButton = dismissLabel?.let { label ->
            {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    )
}
