package com.flux.manga.data

import android.graphics.*
import com.flux.manga.core.imageStripPlan
import java.io.IOException

/** One decoder, bounded output and one temporary strip. No plaintext files or pixel FFI. */
internal object PrivateStripDecoder {
    fun decode(decoder: BitmapRegionDecoder, strips: Int, top: Int, bottom: Int,
        sample: Int, checkActive: () -> Unit): Bitmap {
        require(top >= 0 && bottom > top && bottom <= decoder.height && sample > 0)
        val plan = imageStripPlan(decoder.height.toUInt(), strips.toUInt(), top.toUInt(), bottom.toUInt())
        val height = bottom - top
        val output = Bitmap.createBitmap((decoder.width + sample - 1) / sample,
            (height + sample - 1) / sample, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (part in plan) {
                checkActive()
                val start = (part.targetTop.toLong() * output.height / height).toInt()
                val end = ((part.targetTop.toLong() + part.height.toLong()) * output.height / height).toInt()
                if (start == end) continue
                val piece = decoder.decodeRegion(Rect(0, part.sourceTop.toInt(), decoder.width,
                    (part.sourceTop + part.height).toInt()), BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
                    ?: throw IOException("图片切片解码失败")
                try {
                    checkActive()
                    canvas.drawBitmap(piece, null, Rect(0, start, output.width, end), paint)
                } finally { if (piece.isMutable) piece.eraseColor(Color.TRANSPARENT); piece.recycle() }
            }
            checkActive()
            return output
        } catch (error: Throwable) {
            output.eraseColor(Color.TRANSPARENT); output.recycle(); throw error
        }
    }
}
