package com.swipeyt.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF8B2FC9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2DAFF),
    onPrimaryContainer = Color(0xFF2E004E),
    secondary = Color(0xFFC2185B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3E001D),
    tertiary = Color(0xFF00696E),
    tertiaryContainer = Color(0xFF6FF6FD),
    background = Color(0xFFFFF7FE),
    surface = Color(0xFFFFF7FE),
    surfaceContainer = Color(0xFFF7E9FB),
    surfaceContainerHigh = Color(0xFFF1E3F5),
    surfaceContainerHighest = Color(0xFFEBDDEF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0B6FF),
    onPrimary = Color(0xFF4A007F),
    primaryContainer = Color(0xFF6A16A6),
    onPrimaryContainer = Color(0xFFF2DAFF),
    secondary = Color(0xFFFFB1C8),
    onSecondary = Color(0xFF650033),
    secondaryContainer = Color(0xFF8E0046),
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFF4CD9E0),
    tertiaryContainer = Color(0xFF004F53),
    background = Color(0xFF15111A),
    surface = Color(0xFF15111A),
    surfaceContainer = Color(0xFF221C28),
    surfaceContainerHigh = Color(0xFF2C2632),
    surfaceContainerHighest = Color(0xFF37303D),
)

@Composable
fun SwipeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = SwipeTypography,
        content = content,
    )
}
