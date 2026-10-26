package com.flux.manga.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.flux.manga.ui.navigation.PrototypeWorld

/** Keys must be unique and stable. The caller owns selection and navigation. */
data class LiquidNavItem(
    val key: String,
    val label: String,
    val enabled: Boolean = true,
    // Extension buffer only: badges, long-press shortcuts and double-tap-to-top.
    // Do not store credentials, page images or session objects in this presentation metadata.
    val metadata: Map<String, Any> = emptyMap(),
)

/** Semantic roles mapped exclusively from the approved WorldColors / GlassTokens palette. */
@Immutable
data class LiquidGlassTokens(
    val glassSurfaceBase: Color,
    val glassSpecularRimBright: Color,
    val glassSpecularRimDim: Color,
    val activePillSurface: Color,
    val activePillBorderGlow: Color,
    val activeAmbientGlow: Color,
    val contentPrimaryActive: Color,
    val contentSecondaryMuted: Color,
    val contentSubtle: Color,
    val surfaceInverse: Color,
    val accentWarningBadge: Color,
) {
    companion object {
        fun forWorld(world: PrototypeWorld) = at(if (world == PrototypeWorld.SFW) 0f else 1f)

        /** Uses the existing world transition's progress; no second theme animation. */
        fun at(fraction: Float): LiquidGlassTokens {
            val progress = fraction.coerceIn(0f, 1f)
            val palette = GlassTokens.paletteAt(progress)
            val active = lerp(WorldColors.Blue, WorldColors.Copper, progress)
            return LiquidGlassTokens(
                glassSurfaceBase = palette.fill,
                glassSpecularRimBright = lerp(WorldColors.LightBackground, WorldColors.DarkInk, progress).copy(alpha = .70f - .24f * progress),
                glassSpecularRimDim = palette.outline.copy(alpha = .38f),
                activePillSurface = palette.selected.copy(alpha = .32f + .16f * progress),
                activePillBorderGlow = active.copy(alpha = .34f),
                activeAmbientGlow = palette.tint.copy(alpha = .12f),
                contentPrimaryActive = active,
                contentSecondaryMuted = palette.muted,
                contentSubtle = palette.muted.copy(alpha = .68f),
                surfaceInverse = lerp(WorldColors.DarkBackground, WorldColors.LightBackground, progress),
                accentWarningBadge = WorldColors.Copper,
            )
        }
    }
}
