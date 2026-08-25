package com.stastyle.localsummarizer.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF6FD),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8EA),
    onSecondaryContainer = Color(0xFF051F21),
    surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF191C1C),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD9E1),
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFF6FF6FD),
    secondary = Color(0xFFB1CBCE),
    onSecondary = Color(0xFF1B3437),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8EA),
    surface = Color(0xFF101414),
    onSurface = Color(0xFFE0E3E3),
)

@Composable
fun LocalSummarizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
