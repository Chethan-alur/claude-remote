package com.claude.remote.ui.theme

import androidx.compose.ui.graphics.Color

// Brand accent — Claude terracotta.
private val Terracotta = Color(0xFFD97757)
private val TerracottaOnDark = Color(0xFF3A1E12)
private val TerracottaContainer = Color(0xFF5A3522)
private val TerracottaOnContainer = Color(0xFFFFDBC9)

// Dark "dev-tool" surfaces.
private val Bg = Color(0xFF0F1117)
private val Surface = Color(0xFF15181F)
private val SurfaceVariant = Color(0xFF262B36)
private val SurfaceContainer = Color(0xFF1B1F28)
private val OnSurface = Color(0xFFE6E8EE)
private val OnSurfaceVariant = Color(0xFFA8AEBA)
private val Outline = Color(0xFF3A4150)
private val OutlineVariant = Color(0xFF2A2F3A)

private val Secondary = Color(0xFFE0C3B0)
private val OnSecondary = Color(0xFF2A1C12)

private val ErrorRed = Color(0xFFFF6B6B)
private val ErrorContainer = Color(0xFF5A1A1A)
private val OnErrorContainer = Color(0xFFFFD6D6)

/** The single always-on dark scheme. Built once and reused by [ClaudeRemoteTheme]. */
val DarkColors = androidx.compose.material3.darkColorScheme(
    primary = Terracotta,
    onPrimary = TerracottaOnDark,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = TerracottaOnContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    tertiary = Terracotta,
    background = Bg,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = Color(0xFF20242E),
    surfaceContainerHighest = Color(0xFF262B36),
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = ErrorRed,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
)

/**
 * Semantic status colors, centralized so the connection/session indicators are
 * defined once instead of being copy-pasted across screens.
 */
object StatusColors {
    val positive = Color(0xFF22C55E) // connected / running
    val pending = Color(0xFFF59E0B)  // connecting / waiting
    val danger = Color(0xFFEF4444)   // offline / dead / error
    val neutral = Color(0xFF9CA3AF)  // idle
}
