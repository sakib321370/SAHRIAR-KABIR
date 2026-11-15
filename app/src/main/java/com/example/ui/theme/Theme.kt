package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme =
  darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldOnContainer,
    secondary = AmberSecondary,
    onSecondary = AmberOnSecondary,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = AmberOnContainer,
    tertiary = SkyTertiary,
    onTertiary = SkyOnTertiary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = StudioSurface,
    surfaceContainer = StudioSurfaceVariant,
    surfaceContainerHigh = StudioSurfaceHigh,
    outline = StudioBorder,
    outlineVariant = StudioBorder,
    error = CoralError,
    onError = TextPrimary,
    errorContainer = CoralErrorContainer,
    onErrorContainer = CoralOnErrorContainer,
  )

private val StudioShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun BanglaSubTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = StudioDarkColorScheme,
    typography = BanglaSubTypography,
    shapes = StudioShapes,
    content = content,
  )
}
