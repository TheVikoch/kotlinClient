package com.messenger.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFF0EA5E9),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFFF9F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF3F4F6),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onTertiary = Color(0xFF050505),
    onBackground = Color(0xFF050505),
    onSurface = Color(0xFF050505),
    outline = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    secondary = Color(0xFF38BDF8),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B1120),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1F2937),
    onPrimary = Color(0xFF0F172A),
    onSecondary = Color(0xFF0F172A),
    onTertiary = Color(0xFF0F172A),
    onBackground = Color(0xFFE2E8F0),
    onSurface = Color(0xFFE2E8F0),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171)
)

@Composable
fun MessengerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
