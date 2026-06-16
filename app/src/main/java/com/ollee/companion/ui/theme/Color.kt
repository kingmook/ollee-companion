package com.ollee.companion.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Teal-forward palette used on devices without Material You dynamic color.
val LightColors = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F0),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    tertiaryContainer = Color(0xFFD3E3FF),
    onTertiaryContainer = Color(0xFF041C35),
    background = Color(0xFFF5FBFA),
    surface = Color(0xFFF5FBFA),
    surfaceVariant = Color(0xFFDAE5E4),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF4FDAD9),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFFB0CCCB),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E3FF),
    background = Color(0xFF0E1514),
    surface = Color(0xFF0E1514),
    surfaceVariant = Color(0xFF3F4948),
)

// Semantic accents for status chips (kept separate from the M3 scheme).
val StatusOkContainer = Color(0xFFB8F0C2)
val StatusOkText = Color(0xFF0B3D1A)
val StatusPendingContainer = Color(0xFFFCE6A8)
val StatusPendingText = Color(0xFF4A3500)
