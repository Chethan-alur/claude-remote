package com.claude.remote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Default Material 3 type scale; screens override per-use where needed.
val AppTypography = Typography()

/** Monospace style for techy text — session ids, cwd paths, key labels. */
val MonoLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
)
