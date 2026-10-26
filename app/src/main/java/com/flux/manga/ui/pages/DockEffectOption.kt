package com.flux.manga.ui.pages

import com.flux.manga.ui.glass.DockEffects

/** One control changes one preference; effective rendering may be gated by other preferences. */
internal enum class DockEffectOption(val label: String, val tag: String) {
    LOW("低效果", "dock-low-effects"), MOTION("减少动画", "dock-reduce-motion"),
    SAMPLING("背景采样", "dock-sampling"), BLUR("模糊 16dp", "dock-blur"), TINT("微染色", "dock-tint"),
    FUSION("形状融合", "dock-fusion"), HIGHLIGHT("连续高光", "dock-highlight"),
    REFRACTION("边缘折射", "dock-refraction"), SATURATION("背景饱和度", "dock-saturation");

    fun checked(value: DockEffects) = when (this) {
        LOW -> value.lowEffects; MOTION -> value.reduceMotion; SAMPLING -> value.sampling
        BLUR -> value.blur; TINT -> value.tint
        FUSION -> value.fusion; HIGHLIGHT -> value.highlight; REFRACTION -> value.refraction; SATURATION -> value.saturation
    }
    fun supported(api: Int) = api >= 33 || (this != HIGHLIGHT && this != REFRACTION)
    fun enabled(value: DockEffects, api: Int = 33) = supported(api) &&
        (!value.lowEffects || this == LOW || this == MOTION || this == TINT)
    fun changed(value: DockEffects, checked: Boolean, api: Int = 33): DockEffects {
        if (!enabled(value, api)) return value
        return when (this) {
            LOW -> value.copy(lowEffects = checked); MOTION -> value.copy(reduceMotion = checked)
            SAMPLING -> value.copy(sampling = checked); BLUR -> value.copy(blur = checked); TINT -> value.copy(tint = checked)
            FUSION -> value.copy(fusion = checked); HIGHLIGHT -> value.copy(highlight = checked)
            REFRACTION -> value.copy(refraction = checked); SATURATION -> value.copy(saturation = checked)
        }
    }
    fun description(value: DockEffects, api: Int = 33): String {
        if (!supported(api)) return "需要 Android 13 或更高版本；基础外观仍可使用。"
        if (value.lowEffects && !enabled(value, api)) return "低效果下暂停，已保留原选择。"
        return when (this) {
        LOW -> "使用不透明底色，暂停背景处理与增强材质；关闭后恢复原效果选择。"
        MOTION -> "简化底栏与空间切换动画；不改变低效果选项。"
        SAMPLING -> if (value.lowEffects) "低效果下暂停，已保留原选择。" else "让底栏材质使用当前背景；不处理漫画正文。"
        BLUR -> when {
            value.lowEffects -> "低效果下暂停，已保留原选择。"
            !value.sampling -> "需开启背景采样才生效；当前只保留选择。"
            else -> "模糊底栏背景，文字与漫画保持清晰。"
        }
        TINT -> "为底栏添加轻微主题色。"
        FUSION -> if (api >= 33) "为功能舱与固定切换键使用平滑材质轮廓；不改变位置或点击区域。"
            else "通过柔化遮罩连接靠近的形状；不改变点击区域。"
        HIGHLIGHT -> "沿共同轮廓增加方向高光，不拉伸文字与图标。"
        REFRACTION -> if (!value.sampling) "需开启背景采样才生效；当前只保留选择。"
            else "轻微弯曲底栏边缘的背景，正文与前景保持清晰。"
        SATURATION -> if (!value.sampling) "需开启背景采样才生效；当前只保留选择。"
            else "背景饱和度 1.35 倍、亮度 1.05 倍，可独立于模糊开启。"
        }
    }
}
