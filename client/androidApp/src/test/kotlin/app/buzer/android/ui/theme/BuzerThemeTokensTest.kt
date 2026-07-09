package app.buzer.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class BuzerThemeTokensTest {
    @Test
    fun spacingValuesMatchRedesignContract() {
        assertEquals(4.dp, Spacing.xs)
        assertEquals(8.dp, Spacing.sm)
        assertEquals(12.dp, Spacing.md)
        assertEquals(16.dp, Spacing.lg)
        assertEquals(24.dp, Spacing.xl)
        assertEquals(32.dp, Spacing.xxl)
        assertEquals(16.dp, Spacing.screen)
        assertEquals(160.dp, Spacing.fabClearance)
    }

    @Test
    fun darkColorSchemeUsesApprovedCoreValues() {
        assertEquals(Color(0xFFB8C7FF), BuzerDarkColorScheme.primary)
        assertEquals(Color(0xFF20305F), BuzerDarkColorScheme.onPrimary)
        assertEquals(Color(0xFF394777), BuzerDarkColorScheme.primaryContainer)
        assertEquals(Color(0xFFDCE2FF), BuzerDarkColorScheme.onPrimaryContainer)
        assertEquals(Color(0xFFC4C6D8), BuzerDarkColorScheme.secondary)
        assertEquals(Color(0xFF2D3042), BuzerDarkColorScheme.onSecondary)
        assertEquals(Color(0xFF111318), BuzerDarkColorScheme.surface)
        assertEquals(Color(0xFFE3E2E9), BuzerDarkColorScheme.onSurface)
        assertEquals(Color(0xFF44464F), BuzerDarkColorScheme.surfaceVariant)
        assertEquals(Color(0xFFC6C6D0), BuzerDarkColorScheme.onSurfaceVariant)
        assertEquals(Color(0xFFFFB4AB), BuzerDarkColorScheme.error)
        assertEquals(Color(0xFF690005), BuzerDarkColorScheme.onError)
    }

    @Test
    fun lightColorSchemeProvidesLegibleStaticFallback() {
        assertEquals(Color(0xFF42558C), BuzerLightColorScheme.primary)
        assertEquals(Color.White, BuzerLightColorScheme.onPrimary)
        assertEquals(Color(0xFFF9F9FF), BuzerLightColorScheme.surface)
        assertEquals(Color(0xFF1B1B21), BuzerLightColorScheme.onSurface)
        assertEquals(Color(0xFFBA1A1A), BuzerLightColorScheme.error)
        assertEquals(Color.White, BuzerLightColorScheme.onError)
    }

    @Test
    fun shapesUseCompactRadii() {
        assertEquals(RoundedCornerShape(4.dp), BuzerShapes.extraSmall)
        assertEquals(RoundedCornerShape(6.dp), BuzerShapes.small)
        assertEquals(RoundedCornerShape(8.dp), BuzerShapes.medium)
        assertEquals(RoundedCornerShape(12.dp), BuzerShapes.large)
        assertEquals(RoundedCornerShape(16.dp), BuzerShapes.extraLarge)
    }
}
