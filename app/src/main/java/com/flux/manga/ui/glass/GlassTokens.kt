package com.flux.manga.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Shared material roles, not an implementation of Apple's material. */
data class GlassPalette(
    val background: Color, val ink: Color, val muted: Color,
    val fill: Color, val tint: Color, val tintAlpha: Float,
    val foreground: Color, val selected: Color, val outline: Color,
)

object GlassTokens {
    const val SwitchCornerDp = 24f
    const val BlurRadiusDp = 16f
    // Opt-in M5 starting values; device visual/performance acceptance remains pending.
    const val FusionRadiusDp = 12f
    const val ThresholdBlurDp = 6f
    const val ContourWidthDp = 1f
    const val RefractionEdgeDp = 14f
    const val RefractionAmountDp = 4f
    const val MaterialPaddingDp = 18f
    const val Saturation = 1.35f
    const val Brightness = 1.05f
    val Light = GlassPalette(
        WorldColors.LightBackground, WorldColors.LightInk, WorldColors.LightMuted,
        WorldColors.LightBackground.copy(alpha = .10f), WorldColors.Blue, .025f,
        WorldColors.LightInk, WorldColors.LightSelected, WorldColors.LightLine,
    )
    val Dark = GlassPalette(
        WorldColors.DarkBackground, WorldColors.DarkInk, WorldColors.DarkMuted,
        WorldColors.DarkSurface.copy(alpha = .12f), WorldColors.Orange, .025f,
        WorldColors.DarkInk, WorldColors.DarkSelected, WorldColors.DarkLine,
    )
    const val CircleDamping = .70f
    const val PodDamping = .78f
    const val CircleStiffness = 380f
    const val PodStiffness = 420f
    const val WorldMorphDamping = .82f
    const val WorldMorphStiffness = 520f
    const val WorldRevealMillis = 520

    /** Only dock colors blend; page content remains owned by the committed world. */
    fun paletteAt(fraction: Float): GlassPalette {
        val p = fraction.coerceIn(0f, 1f)
        return GlassPalette(lerp(Light.background, Dark.background, p), lerp(Light.ink, Dark.ink, p),
            lerp(Light.muted, Dark.muted, p), lerp(Light.fill, Dark.fill, p), lerp(Light.tint, Dark.tint, p),
            Light.tintAlpha + (Dark.tintAlpha - Light.tintAlpha) * p,
            lerp(Light.foreground, Dark.foreground, p), lerp(Light.selected, Dark.selected, p),
            lerp(Light.outline, Dark.outline, p))
    }
}
