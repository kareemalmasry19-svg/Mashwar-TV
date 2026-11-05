package com.mashwar.tv

import androidx.compose.material3.darkColorScheme
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

val GoldBrush = Brush.linearGradient(listOf(GoldDeep, GoldLight, Gold))

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
