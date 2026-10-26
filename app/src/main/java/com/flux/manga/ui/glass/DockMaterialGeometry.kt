package com.flux.manga.ui.glass

import kotlin.math.*

/** Pixel coordinates shared by the GPU backends; no Android calls, content or saved identity. */
internal data class MaterialBounds(val left: Float, val top: Float, val right: Float, val bottom: Float,
    val cornerRadius: Float? = null) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) * .5f
    val cy get() = (top + bottom) * .5f
    val radius get() = cornerRadius ?: (height * .5f)
    fun distance(x: Float, y: Float): Float {
        if (radius <= 0f) return Float.MAX_VALUE
        val qx = abs(x - cx) - width * .5f + radius
        val qy = abs(y - cy) - height * .5f + radius
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - radius
    }
}

internal data class DockMaterialFrame(val circle: MaterialBounds, val pod: MaterialBounds,
    val bud: MaterialBounds = MaterialBounds(0f, 0f, 0f, 0f), val fusionScale: Float = 0f) {
    fun distance(x: Float, y: Float, fusionRadius: Float): Float {
        fun merge(a: Float, b: Float): Float {
            val radius = fusionRadius * fusionScale
            if (radius <= 0f) return min(a, b)
            val h = max(radius - abs(a - b), 0f) / radius
            return min(a, b) - h * h * radius * .25f
        }
        return merge(merge(circle.distance(x, y), pod.distance(x, y)), bud.distance(x, y))
    }
    companion object {
        fun fixed(width: Float, height: Float, switchWidth: Float, gap: Float, cornerRadius: Float) = DockMaterialFrame(
            MaterialBounds(width - switchWidth, 0f, width, height, cornerRadius),
            MaterialBounds(0f, 0f, width - switchWidth - gap, height),
        )
        fun create(width: Float, height: Float, circleSize: Float, gap: Float,
                   circleProgress: Float, podProgress: Float, circleSpeed: Float, podSpeed: Float): DockMaterialFrame {
            val c = circleProgress.coerceIn(0f, 1f); val p = podProgress.coerceIn(0f, 1f)
            fun bounds(x: Float, y: Float, w: Float, h: Float, sx: Float, sy: Float): MaterialBounds {
                val dx = w * (sx - 1f) * .5f; val dy = h * (sy - 1f) * .5f
                return MaterialBounds(x - dx, y - dy, x + w + dx, y + h + dy)
            }
            val cs = circleSpeed.coerceIn(0f, 1f); val ps = podSpeed.coerceIn(0f, 1f)
            val travel = circleSize + gap
            fun lobe(right: Boolean): MaterialBounds {
                val amount = if (right) c else 1f - c
                val diameter = circleSize * sqrt(amount)
                val cx = if (right) width - circleSize / 2f - travel * .65f * (1f - c)
                    else circleSize / 2f + travel * .65f * c
                return bounds(cx - diameter / 2f, (height - diameter) / 2f,
                    diameter, diameter, 1f + .14f * cs, 1f - .08f * cs)
            }
            return DockMaterialFrame(
                lobe(c > .5f),
                bounds(((circleSize + gap) * (1f - p)).roundToInt().toFloat(), 0f,
                    width - circleSize - gap, height, 1f, 1f - .06f * ps),
                lobe(c <= .5f),
                fusionScale = if (c > 0f && c < 1f || p > 0f && p < 1f || cs > 0f || ps > 0f) 1f else 0f,
            )
        }
    }
}
