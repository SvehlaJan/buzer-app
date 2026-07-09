package app.buzer.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.theme.Spacing

@Composable
fun BuzerScaffold(
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen)
            .padding(bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content
    )
}
