package app.buzer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.ui.blockers.BlockerUiModel

@Composable
fun BlockerSurface(
    model: BlockerUiModel,
    warningMessage: String?,
    checking: Boolean,
    onRefresh: (() -> Unit)? = null,
    onOpenTodoist: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        val scrollState = rememberScrollState()
        LaunchedEffect(model.eyebrow, model.title) {
            scrollState.scrollTo(0)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                text = model.eyebrow,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = model.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = model.blockWindow,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TaskDetailsBlock(
                description = model.description,
                modifier = Modifier.fillMaxWidth()
            )
            model.consequenceText?.let { consequence ->
                Text(
                    text = consequence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            warningMessage?.let { msg ->
                if (onRefresh != null && onOpenTodoist != null) {
                    SessionWarningBanner(
                        message = msg,
                        onRefresh = onRefresh,
                        onOpenTodoist = onOpenTodoist,
                        refreshing = checking
                    )
                } else {
                    WarningStatus(msg)
                }
            }
            if (checking && (warningMessage == null || onRefresh == null)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Checking task state...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                content = actions
            )
        }
    }
}
