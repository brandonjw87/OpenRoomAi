package com.openroomai.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceHi = Color(0xFF1F2530)
    val Mint = Color(0xFF2EE6A8)
    val Violet = Color(0xFF8B7CFF)
    val Amber = Color(0xFFFFC94D)
    val Coral = Color(0xFFFF5370)
    val Sky = Color(0xFF64B5F6)
    val Door = Color(0xFFFFB74D)
    val Text = Color(0xFFE9EDF3)
    val Muted = Color(0xFF8C96A5)
    val Wall = Color(0xFFDDE3EC)
}

private val scheme = darkColorScheme(
    primary = Palette.Mint,
    onPrimary = Color(0xFF00281A),
    secondary = Palette.Violet,
    onSecondary = Color.White,
    tertiary = Palette.Amber,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHi,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHi,
    surfaceContainerLow = Palette.Surface,
    error = Palette.Coral,
    outline = Color(0xFF2E3644),
)

private val type = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun OpenRoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
