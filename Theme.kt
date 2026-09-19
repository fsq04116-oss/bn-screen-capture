package com.baining.str.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary             = DreamBlue,
    onPrimary           = DreamInkStrong,
    primaryContainer    = Color(0xFF385B70),
    onPrimaryContainer  = Color(0xFFEAF7FF),
    secondary           = DreamYellow,
    onSecondary         = DreamInkStrong,
    secondaryContainer  = Color(0xFF4A4D32),
    onSecondaryContainer= Color(0xFFFCFFD9),
    tertiary            = DreamPurple,
    onTertiary          = DreamInkStrong,
    tertiaryContainer   = Color(0xFF514A70),
    onTertiaryContainer = Color(0xFFF4EEFF),
    background          = BgDark1,
    onBackground        = Color(0xFFF6F1FA),
    surface             = CardDark,
    onSurface           = Color(0xFFF8F2FB),
    surfaceVariant      = Color(0xFF34334A),
    onSurfaceVariant    = Color(0xFFD8D3E8),
    outline             = Color(0xFF76718D),
    error               = IosRed,
    onError             = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary             = DreamBlue,
    onPrimary           = DreamInkStrong,
    primaryContainer    = IosBlueLight,
    onPrimaryContainer  = DreamInkStrong,
    secondary           = DreamYellow,
    onSecondary         = DreamInkStrong,
    secondaryContainer  = DreamYellow,
    onSecondaryContainer= DreamInkStrong,
    tertiary            = DreamPurple,
    onTertiary          = DreamInkStrong,
    tertiaryContainer   = DreamPink,
    onTertiaryContainer = DreamInkStrong,
    background          = BgLight1,
    onBackground        = DreamInkStrong,
    surface             = CardLight,
    onSurface           = DreamInkStrong,
    surfaceVariant      = Color(0xFFF2EAF7),
    onSurfaceVariant    = DreamMuted,
    outline             = Color(0xFFD9D5E6),
    error               = IosRed,
    onError             = Color.White,
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: Color = DreamBlue,
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val onAccent = if (accentColor.prefersDarkContent()) DreamInkStrong else Color.White
    MaterialTheme(
        colorScheme = base.copy(
            primary = accentColor,
            onPrimary = onAccent,
            primaryContainer = if (darkTheme) accentColor.copy(alpha = 0.34f) else accentColor.copy(alpha = 0.42f),
            onPrimaryContainer = if (darkTheme) Color(0xFFF6F1FA) else DreamInkStrong,
            secondary = base.secondary,
            onSecondary = base.onSecondary,
            tertiary = base.tertiary,
            onTertiary = base.onTertiary,
        ),
        typography = AppTypography,
        content = content
    )
}

private fun Color.prefersDarkContent(): Boolean {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return luminance > 0.58f
}
