package com.flux.manga

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.manga.data.PrivateStripDecoder
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Compiled locally only. No files, accounts or device data; execution needs separate authorization. */
@RunWith(AndroidJUnit4::class)
class PrivateStripDecoderTest {
    private fun <T> withNumberedRows(block: (BitmapRegionDecoder) -> T): T {
        val image = Bitmap.createBitmap(7, 13, Bitmap.Config.ARGB_8888)
        for (y in 0 until 13) for (x in 0 until 7) image.setPixel(x, y, Color.rgb(y * 17, x * 31, 99))
        val output = ByteArrayOutputStream()
        val bytes = try { check(image.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
            finally { image.recycle(); output.close() }
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        return try { block(decoder) } finally { decoder.recycle(); bytes.fill(0) }
    }

    @Test fun realDecoderPreservesAllPixelsAndViewportCrossingRemainder() = withNumberedRows { decoder ->
        val expected = listOf(9, 10, 11, 12, 6, 7, 8, 3, 4, 5, 0, 1, 2)
        for ((top, bottom) in listOf(0 to 13, 2 to 11, 12 to 13)) {
            val result = PrivateStripDecoder.decode(decoder, 4, top, bottom, 1) { }
            try {
                assertEquals(7, result.width); assertEquals(bottom - top, result.height)
                for (y in 0 until result.height) for (x in 0 until result.width)
                    assertEquals(Color.rgb(expected[top + y] * 17, x * 31, 99), result.getPixel(x, y))
            } finally { result.recycle() }
        }
    }

    @Test fun downsampledStripsCoverOutputWithoutTransparentSeams() = withNumberedRows { decoder ->
        val result = PrivateStripDecoder.decode(decoder, 4, 1, 13, 4) { }
        try {
            assertEquals(2, result.width); assertEquals(3, result.height)
            for (y in 0 until result.height) for (x in 0 until result.width)
                assertEquals(255, Color.alpha(result.getPixel(x, y)))
        } finally { result.recycle() }
    }

    @Test fun cancellationBetweenDecodeAndDrawDoesNotPublish() = withNumberedRows { decoder ->
        var calls = 0
        assertThrows(CancellationException::class.java) {
            PrivateStripDecoder.decode(decoder, 4, 0, 13, 1) {
                if (++calls == 2) throw CancellationException("synthetic cancellation")
            }
        }
        assertEquals(2, calls)
    }
}
