package com.flux.manga.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

internal enum class ReferenceSymbol { BOOKMARK, DOWNLOAD, PLAY, PREVIOUS, NEXT, BOOK, OPTIONS, UPDATE, MORE }

/** Decorative line icons; the enclosing control owns the accessible action label. */
@Composable
internal fun ReferenceIcon(symbol: ReferenceSymbol, modifier: Modifier = Modifier, filled: Boolean = false) {
    val color = LocalContentColor.current
    Canvas(modifier.size(20.dp).clearAndSetSemantics { }) {
        fun p(x: Float, y: Float) = Offset(size.width * x / 24f, size.height * y / 24f)
        val width = size.width / 12f
        val stroke = Stroke(width, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, p(x, y), p(x2, y2), width, StrokeCap.Round)
        fun polygon(points: List<Offset>): Path = Path().apply {
            moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        when (symbol) {
            ReferenceSymbol.BOOKMARK -> {
                val path = polygon(listOf(p(6f, 3f), p(18f, 3f), p(18f, 21f), p(12f, 17f), p(6f, 21f)))
                if (filled) drawPath(path, color) else drawPath(path, color, style = stroke)
            }
            ReferenceSymbol.PLAY -> drawPath(polygon(listOf(p(7f, 3f), p(21f, 12f), p(7f, 21f))), color)
            ReferenceSymbol.DOWNLOAD -> {
                line(12f, 2f, 12f, 16f); line(7f, 11f, 12f, 16f); line(12f, 16f, 17f, 11f)
                line(3f, 16f, 3f, 22f); line(3f, 22f, 21f, 22f); line(21f, 22f, 21f, 16f)
            }
            ReferenceSymbol.PREVIOUS -> { line(16f, 4f, 8f, 12f); line(8f, 12f, 16f, 20f) }
            ReferenceSymbol.NEXT -> { line(8f, 4f, 16f, 12f); line(16f, 12f, 8f, 20f) }
            ReferenceSymbol.BOOK -> {
                drawPath(polygon(listOf(p(2f, 3f), p(8f, 3f), p(12f, 6f), p(16f, 3f), p(22f, 3f),
                    p(22f, 19f), p(16f, 19f), p(12f, 21f), p(8f, 19f), p(2f, 19f))), color, style = stroke)
                line(12f, 6f, 12f, 20f)
            }
            ReferenceSymbol.OPTIONS -> repeat(3) { i ->
                val y = 5f + i * 7f; val x = if (i == 1) 15f else 8f
                line(3f, y, 21f, y); drawCircle(color, size.width / 10f, p(x, y))
            }
            ReferenceSymbol.UPDATE -> {
                drawArc(color, 35f, 290f, false, p(3f, 3f), androidx.compose.ui.geometry.Size(size.width * .75f, size.height * .75f), style = stroke)
                line(21f, 2f, 21f, 8f); line(15f, 8f, 21f, 8f)
            }
            ReferenceSymbol.MORE -> repeat(3) { index ->
                drawCircle(color, radius = size.width * .09f, center = p(5f + index * 7f, 12f))
            }
        }
    }
}
