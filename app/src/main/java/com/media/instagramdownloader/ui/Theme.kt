package com.media.instagramdownloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Pink = Color(0xFFE1306C)
private val Purple = Color(0xFF833AB4)

private val DarkColors = darkColorScheme(
    primary = Pink,
    secondary = Purple,
    background = Color(0xFF0E0E10),
    surface = Color(0xFF161618),
)

private val LightColors = lightColorScheme(
    primary = Pink,
    secondary = Purple,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
