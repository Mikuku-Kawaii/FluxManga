package com.flux.manga.ui.glass

/** Display preferences only. Enhancement selections follow the current task, not a world database. */
data class DockEffects(
    val sampling: Boolean = true, val blur: Boolean = true, val tint: Boolean = true,
    val lowEffects: Boolean = false, val reduceMotion: Boolean = false,
    val fusion: Boolean = false, val highlight: Boolean = true,
    val refraction: Boolean = true, val saturation: Boolean = true,
) {
    val backdropEnabled get() = sampling && !lowEffects
    val blurEnabled get() = blur && backdropEnabled
    val saturationEnabled get() = saturation && backdropEnabled
    val fusionEnabled get() = fusion && !lowEffects
    fun highlightEnabled(api: Int) = highlight && !lowEffects && api >= 33
    fun refractionEnabled(api: Int) = refraction && backdropEnabled && api >= 33
    fun unifiedMaterial(api: Int) = fusionEnabled || highlightEnabled(api) || refractionEnabled(api)
}
