package app.buzer.android.ui.taskeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.WarningStatus

@Composable
internal fun TaskEditorProjectLoadStatus(
    loading: Boolean,
    message: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (loading) InlineLoadingStatus("Loading projects...")
        message?.let { WarningStatus("Projects unavailable: $it", onRetry = onRetry) }
    }
}
