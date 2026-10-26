package com.flux.manga.ui.glass

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.flux.manga.ui.navigation.PrototypeWorld

/** Approved palette v2. Accent ink differs from solid button fill in the private world. */
object WorldColors {
    val LightBackground = Color(0xFFFFFFFF)
    val DarkBackground = Color(0xFF181818)
    val LightSurface = Color(0xFFF5F6F8)
    val DarkSurface = Color(0xFF242424)
    val Blue = Color(0xFF145CE6)
    val Orange = Color(0xFFB84D12)
    val Copper = Color(0xFFF0A06B)
    val LightInk = Color(0xFF1A1A1A)
    val DarkInk = Color(0xFFF5F5F5)
    val LightMuted = Color(0xFF666666)
    val DarkMuted = Color(0xFFA3A3A3)
    val LightSelected = Color(0xFFEAF1FF)
    val DarkSelected = Color(0xFF2A170E)
    val LightLine = Color(0xFFE2E5EA)
    val DarkLine = Color(0xFF3A3A3A)
    private val light = lightColorScheme(
        primary = Blue, onPrimary = Color.White, primaryContainer = LightSelected, onPrimaryContainer = Blue,
        secondary = Blue, onSecondary = Color.White, secondaryContainer = LightSelected, onSecondaryContainer = Blue,
        tertiary = Blue, onTertiary = Color.White, tertiaryContainer = LightSelected, onTertiaryContainer = Blue,
        background = LightBackground, onBackground = LightInk, surface = LightBackground, onSurface = LightInk,
        surfaceVariant = LightSurface, onSurfaceVariant = LightMuted, surfaceTint = Blue,
        surfaceContainer = LightSurface, surfaceContainerHigh = LightSurface, surfaceContainerHighest = LightSurface,
        surfaceContainerLow = LightSurface, surfaceContainerLowest = LightBackground, outline = LightMuted, outlineVariant = LightLine)
    private val dark = darkColorScheme(
        primary = Copper, onPrimary = DarkBackground, primaryContainer = DarkSelected, onPrimaryContainer = Copper,
        secondary = Copper, onSecondary = DarkBackground, secondaryContainer = DarkSelected, onSecondaryContainer = Copper,
        tertiary = Orange, onTertiary = Color.White, tertiaryContainer = DarkSelected, onTertiaryContainer = Copper,
        background = DarkBackground, onBackground = DarkInk, surface = DarkBackground, onSurface = DarkInk,
        surfaceVariant = DarkSurface, onSurfaceVariant = DarkMuted, surfaceTint = Orange,
        surfaceContainer = DarkSurface, surfaceContainerHigh = DarkSurface, surfaceContainerHighest = DarkSurface,
        surfaceContainerLow = DarkSurface, surfaceContainerLowest = DarkBackground, outline = DarkMuted, outlineVariant = DarkLine)
    fun scheme(world: PrototypeWorld) = if (world == PrototypeWorld.SFW) light else dark
}
