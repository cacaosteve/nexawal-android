package com.nexatrode.nexawal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = NexaOrange,
    onPrimary = NexaOrangeOn,
    secondary = NexaOrange,
    onSecondary = NexaOrangeOn,
    tertiary = NexaOrange,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF171C22),
    onSurface = Color(0xFFF5F7FA),
)

private val LightColorScheme = lightColorScheme(
    primary = NexaOrange,
    onPrimary = NexaOrangeOn,
    secondary = NexaOrange,
    onSecondary = NexaOrangeOn,
    tertiary = NexaOrange,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
)

@Composable
fun NexawalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disable dynamic/system wallpaper colors so neon green isn't overridden by Material You blue.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
