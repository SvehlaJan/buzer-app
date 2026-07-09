package app.buzer.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val BuzerDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB8C7FF),
    onPrimary = Color(0xFF20305F),
    primaryContainer = Color(0xFF394777),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFC4C6D8),
    onSecondary = Color(0xFF2D3042),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

val BuzerLightColorScheme = lightColorScheme(
    primary = Color(0xFF42558C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF00164A),
    secondary = Color(0xFF5B5E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E2F9),
    onSecondaryContainer = Color(0xFF181B2C),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E2EC),
    onSurfaceVariant = Color(0xFF46464F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
