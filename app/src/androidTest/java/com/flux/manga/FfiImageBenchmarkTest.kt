package com.flux.manga

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.core.ProbeSession
import com.flux.manga.core.probeResources
import com.flux.manga.ui.probe.ImageInspection
import com.flux.manga.ui.probe.ImageTransfer
import com.flux.manga.ui.probe.ProbeImages
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfiImageBenchmarkTest {
    @Test fun compareEncodedBytesAndOwnedFile(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ffi-images").apply { mkdirs() }
        val samples = JSONArray()
        val fixtures = JSONArray()
        val session = ProbeSession()
        try {
            for ((name, width, height) in listOf(Triple("normal", 1200, 1800), Triple("large", 3000, 10000))) {
                val source = File(directory, "$name.png")
                val output = File(directory, "$name-output.png")
                ProbeFixtures.write(source, width, height)
                val hash = ProbeImages.sha256(source)
                fixtures.put(JSONObject().put("name", name).put("width", width).put("height", height)
                    .put("encodedBytes", source.length()).put("sha256", hash))
                var pixelSignature: String? = null
                try {
                    // Warm both paths first, then alternate order to reduce order/cache bias.
                    for (transfer in ImageTransfer.entries) ProbeImages.inspect(session, source, output, transfer)
                    repeat(3) { iteration ->
                        val order = if (iteration % 2 == 0) ImageTransfer.entries else ImageTransfer.entries.reversed()
                        for (transfer in order) {
                            System.gc()
                            delay(150)
                            val sampler = RssSampler()
                            val measured: ImageInspection
                            try { measured = ProbeImages.inspect(session, source, output, transfer) }
                            finally { sampler.close() }
                            assertEquals(width, measured.sourceWidth)
                            assertEquals(height, measured.sourceHeight)
                            assertEquals(hash, measured.sha256)
                            assertEquals(source.length(), measured.encodedBytes)
                            assertTrue(maxOf(measured.decodedWidth, measured.decodedHeight) <= 2048)
                            if (pixelSignature == null) pixelSignature = measured.pixelSignature
                            else assertEquals(pixelSignature, measured.pixelSignature)
                            assertFalse("Owned output must be deleted after use", output.exists())
                            assertEquals(0uL, probeResources().files)
                            val row = JSONObject().put("fixture", name).put("transfer", transfer.name)
                                .put("iteration", iteration + 1).put("rustMs", measured.rustMs)
                                .put("transferMs", measured.transferMs).put("decodeMs", measured.decodeMs)
                                .put("baselineRssKiB", sampler.baseline).put("peakRssKiB", sampler.peak.get())
                                .put("peakDeltaKiB", sampler.peak.get() - sampler.baseline)
                                .put("rssSamples", sampler.count.get())
                                .put("decodedWidth", measured.decodedWidth).put("decodedHeight", measured.decodedHeight)
                                .put("bitmapBytes", measured.bitmapBytes).put("pixelSignature", measured.pixelSignature)
                            samples.put(row)
                            Log.i("FluxFfiBench", row.toString())
                        }
                    }
                } finally { source.delete() }
            }
        } finally { session.destroy() }
        withTimeout(5_000) {
            while (probeResources().runtimeThreads != 0uL || probeResources().jobs != 0uL) delay(5)
        }
        assertEquals(0uL, probeResources().sessions)
        assertEquals(0uL, probeResources().files)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS[0])
            .put("build", "debug + instrumentation; Rust release")
            .put("rssMethod", "process VmRSS sampled every 5ms through transfer, decode, checksum and cleanup; not an exact peak")
            .put("timingMethod", "one warmup per fixture/path; 3 alternating measured rounds; transfer includes native I/O and UniFFI; decode includes bounds and one sampled bitmap")
            .put("fixtures", fixtures).put("samples", samples).put("resourcesReleased", true)
        File(context.filesDir, "ffi-image-benchmark.json").writeText(report.toString(2))
        Log.i("FluxFfiTest", "PASS both image paths: SHA-256, dimensions, decoded pixels, bitmap/file release; 12 measurements saved")
    }

    private class RssSampler : AutoCloseable {
        val baseline = rss()
        val peak = AtomicLong(baseline)
        val count = AtomicLong(1)
        private val running = AtomicBoolean(true)
        private val thread = Thread({
            while (running.get()) {
                sample()
                Thread.sleep(5)
            }
        }, "flux-rss-sample").apply { start() }

        private fun sample() {
            peak.accumulateAndGet(rss(), ::maxOf)
            count.incrementAndGet()
        }

        override fun close() {
            running.set(false)
            thread.join()
            sample()
        }

        companion object {
            fun rss(): Long {
                val line = File("/proc/self/status").useLines { lines -> lines.first { it.startsWith("VmRSS:") } }
                return line.trim().split(Regex("\\s+"))[1].toLong().also { require(it > 0) }
            }
        }
    }
}
