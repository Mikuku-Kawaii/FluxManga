package com.flux.manga.data

import android.graphics.*
import com.flux.manga.core.ImageStripRegion
import com.flux.manga.core.imageStripPlan
import java.io.IOException

internal object PrivateStripDecoder {
    private const val BAND_PIXELS = 1_048_576

    fun decode(decoder: BitmapRegionDecoder, strips: Int, top: Int, bottom: Int,
        sample: Int, checkActive: () -> Unit): Bitmap {
        require(top >= 0 && bottom > top && bottom <= decoder.height && sample > 0)
        val plan = imageStripPlan(decoder.height.toUInt(), strips.toUInt(), top.toUInt(), bottom.toUInt())
        val height = bottom - top
        val width = decoder.width
        val output = Bitmap.createBitmap((width + sample - 1) / sample,
            (height + sample - 1) / sample, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            if (strips == 0) {
                // Unshuffled offline pages can still use the codec's native subsampling.
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }
                val piece = decoder.decodeRegion(Rect(0, top, width, bottom), options)
                    ?: throw IOException("图片区域解码失败")
                try {
                    checkActive()
                    canvas.drawBitmap(piece, null, Rect(0, 0, output.width, output.height), Paint(Paint.FILTER_BITMAP_FLAG))
                } finally { clear(piece) }
            } else if (sample == 1) {
                restoreRows(decoder, plan, 0, height, canvas, checkActive)
            } else {
                // Filter across restored strip boundaries using the same scale for the entire viewport.
                val scaleY = output.height.toFloat() / height
                val bandRows = (BAND_PIXELS.toLong() * output.height / width / height).toInt().coerceAtLeast(1)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                for (start in 0 until output.height step bandRows) {
                    val end = minOf(start + bandRows, output.height)
                    // Include both neighbours of the first and last output pixel centres.
                    val first = (((start + 0.5) * height / output.height).toInt() - 1).coerceAtLeast(0)
                    val last = (((end - 0.5) * height / output.height).toInt() + 2).coerceAtMost(height)
                    val band = Bitmap.createBitmap(width, last - first, Bitmap.Config.ARGB_8888)
                    try {
                        restoreRows(decoder, plan, first, last, Canvas(band), checkActive)
                        canvas.save()
                        canvas.clipRect(0, start, output.width, end)
                        canvas.drawBitmap(band, null, RectF(0f, first * scaleY, output.width.toFloat(), last * scaleY), paint)
                        canvas.restore()
                    } finally { clear(band) }
                }
            }
            checkActive()
            return output
        } catch (error: Throwable) {
            clear(output)
            throw error
        }
    }

    private fun restoreRows(decoder: BitmapRegionDecoder, plan: List<ImageStripRegion>,
        first: Int, last: Int, canvas: Canvas, checkActive: () -> Unit) {
        val width = decoder.width
        val options = BitmapFactory.Options().apply { inMutable = true }
        for (part in plan) {
            val target = part.targetTop.toInt()
            val start = maxOf(target, first)
            val end = minOf(target + part.height.toInt(), last)
            if (start >= end) continue
            checkActive()
            val y = part.sourceTop.toInt() + start - target
            val rows = end - start
            // WebP chroma reconstruction needs an even origin and neighbouring rows at each edge.
            val from = ((y and -2) - 2).coerceAtLeast(0)
            val to = (((y + rows + 1) and -2) + 2).coerceAtMost(decoder.height)
            val piece = decoder.decodeRegion(Rect(0, from, width, to), options)
                ?: throw IOException("图片切片解码失败")
            try {
                checkActive()
                val source = Rect(0, y - from, width, y - from + rows)
                canvas.drawBitmap(piece, source, Rect(0, start - first, width, end - first), null)
            } finally { clear(piece) }
        }
    }

    private fun clear(bitmap: Bitmap) {
        if (bitmap.isMutable) bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.recycle()
    }
}
