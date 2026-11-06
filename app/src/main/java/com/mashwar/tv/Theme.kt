package com.mashwar.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Kenue TV palette: deep black with brushed gold.
val Gold = Color(0xFFD4AF37)
val GoldLight = Color(0xFFF3D98B)
val GoldDeep = Color(0xFF9C7A1F)
val Ink = Color(0xFF0A0A0B)
val InkRaised = Color(0xFF151417)
val InkHigh = Color(0xFF1E1C21)
val Cream = Color(0xFFF5F1E6)
val Muted = Color(0xFFB9B2A0)

val GoldShine = Color(0xFFFFF4CC)

/** Metallic gold: dark bronze → bright highlight → rich gold → shadow → highlight. */
val GoldStops = listOf(
    Color(0xFF7A5A12), Color(0xFFC9A13B), GoldShine, Color(0xFFE2BD4E),
    Color(0xFF8C6A1C), Color(0xFFF3D98B), Color(0xFFB8922A),
)
val GoldBrush = Brush.linearGradient(GoldStops)

/** Gold with a slow light sweep across it, for titles and highlights. */
@Composable
fun shimmeringGold(width: Float = 600f): Brush {
    val t = rememberInfiniteTransition(label = "gold-sheen")
    val x by t.animateFloat(
        initialValue = -width, targetValue = width * 2,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "sheen-x",
    )
    return Brush.linearGradient(
        listOf(Color(0xFFB8922A), Gold, GoldShine, Color.White, GoldShine, Gold, Color(0xFFB8922A)),
        start = Offset(x, 0f), end = Offset(x + width / 2, width / 6),
    )
}

val KenueColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2F0B),
    onPrimaryContainer = GoldLight,
    secondary = GoldLight,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2E2611),
    onSecondaryContainer = GoldLight,
    tertiary = GoldLight,
    background = Ink,
    onBackground = Cream,
    surface = Ink,
    onSurface = Cream,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Ink,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkHigh,
    surfaceContainerHighest = Color(0xFF26232A),
    surfaceBright = Color(0xFF2B2830),
    surfaceDim = Ink,
    outline = Color(0x99D4AF37),
    outlineVariant = Color(0x40D4AF37),
)
