package com.flux.manga.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.flux.manga.ui.navigation.PrototypeWorld

/** Presentation-only extension slots: future badges / overlays, never session data or images. */
data class LiquidThemePalette(
    val pageBackgroundPrimary: Color,
    val pageBackgroundSecondary: Color,
    val ambientShockwaveGlow: Color,
    val glassSurfaceBase: Color,
    val glassSpecularRimBright: Color,
    val glassSpecularRimDim: Color,
    val activePillSurface: Color,
    val detachedPodSurface: Color,
    val activePillBorderGlow: Color,
    val contentPrimaryActive: Color,
    val contentSecondaryMuted: Color,
    val contentSubtle: Color,
    val surfaceInverse: Color,
    val stateWarning: Color,
    val metadata: Map<String, Any> = emptyMap(),
) {
    companion object {
        fun forWorld(world: PrototypeWorld) = at(if (world == PrototypeWorld.SFW) 0f else 1f)
        fun at(fraction: Float): LiquidThemePalette {
            val p = fraction.coerceIn(0f, 1f)
            val glass = GlassTokens.paletteAt(p)
            val nav = LiquidGlassTokens.at(p)
            return LiquidThemePalette(glass.background, lerp(WorldColors.LightSurface, WorldColors.DarkSurface, p),
                nav.activeAmbientGlow, nav.glassSurfaceBase, nav.glassSpecularRimBright, nav.glassSpecularRimDim,
                nav.activePillSurface, glass.fill, nav.activePillBorderGlow, nav.contentPrimaryActive,
                nav.contentSecondaryMuted, nav.contentSubtle, nav.surfaceInverse, nav.accentWarningBadge)
        }
    }
}

enum class DetachedPodSide { Left, Right }

data class DetachedPodState(
    val side: DetachedPodSide,
    val enabled: Boolean,
    val label: String,
    // Buffer only: drag-to-merge, badges and long-press overlays. Existing privacy hold stays in the host.
    val metadata: Map<String, Any> = emptyMap(),
)

enum class MomentumPhase { Idle, Merging, Transfer, Splitting, Settling, Rollback }

internal object LiquidMomentumSpec {
    const val Impact = .38f
    const val MergeEnd = .45f
    const val SplitStart = .35f
    const val SpringStart = .8f
    const val DriveMillis = 500
    const val Damping = .6f
    const val Stiffness = 380f
    const val PodCornerFraction = .38f
    const val ShockwaveWidthDp = 36f
}
