package com.claude.remote.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The app theme — an always-on dark "dev-tool" scheme with the Claude terracotta
 * accent. Also paints the system bars to match the dark background and forces
 * light (white) status/nav icons, so there's no white flash or light-on-light bar.
 */
@Composable
fun ClaudeRemoteTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val bar = DarkColors.background.toArgb()
            @Suppress("DEPRECATION")
            window.statusBarColor = bar
            @Suppress("DEPRECATION")
            window.navigationBarColor = bar
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
