package com.flux.manga

import android.util.Log
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.core.ImageProbeRequest
import com.flux.manga.core.ProbeException
import com.flux.manga.core.ProbeRequest
import com.flux.manga.core.ProbeSession
import com.flux.manga.core.probeResources
import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfiProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun openSampleValidation() { compose.useSamplePages() }

    @Test fun asyncDtoErrorsCancellationAndRuntimeRelease(): Unit = runBlocking {
        awaitReleased()
        val before = probeResources()
        val session = ProbeSession()
        try {
            val result = session.run(ProbeRequest("请求-世界/sample/42", 50u, false))
            assertEquals("请求-世界/sample/42", result.requestId)
            assertEquals("Rust async ready · 流光", result.message)
            assertEquals(5u, result.steps)
            try {
                session.run(ProbeRequest("expected-failure", 20u, true))
                fail("Expected typed error")
            } catch (error: ProbeException.FixtureFailure) {
                assertEquals("expected-failure", error.requestId)
            }
            repeat(10) { index ->
                val job = launch { session.run(ProbeRequest("cancel-$index", 10_000u, false)) }
                withTimeout(5_000) { while (probeResources().jobs != 1uL) delay(5) }
                job.cancelAndJoin()
                withTimeout(5_000) { while (probeResources().jobs != 0uL) delay(5) }
            }
            assertEquals(before.cancelled + 10uL, probeResources().cancelled)
            assertEquals(before.completed + 1uL, probeResources().completed)
            assertEquals(before.failed + 1uL, probeResources().failed)
        } finally { session.destroy() }
        awaitReleased()
        val ticks = probeResources().ticks
        delay(100)
        assertEquals(ticks, probeResources().ticks)
        Log.i("FluxFfiTest", "PASS unicode DTO, typed error, 10 cancellations, native jobs/threads/sessions=0, ticks stopped")
    }

    @Test fun pageExitCancelsNativeWork_andReplacementCannotPublishOldResult() {
        compose.onNodeWithTag("open-probe").performClick()
        compose.onNodeWithTag("probe-slow").performClick()
        compose.waitUntil(5_000) { probeResources().jobs == 1uL }
        compose.onNodeWithTag("probe-success").performClick()
        waitForStatus("成功：")
        compose.onNodeWithTag("probe-status").assertTextContains("成功：Rust async ready", substring = true)
        compose.onNodeWithTag("probe-failure").performClick()
        waitForStatus("失败：")
        compose.onNodeWithTag("probe-status").assertTextContains("失败：", substring = true)
        compose.onNodeWithTag("probe-slow").performClick()
        compose.waitUntil(5_000) { probeResources().jobs == 1uL }
        compose.onNodeWithTag("probe-cancel").performClick()
        compose.onNodeWithTag("probe-status").assertTextEquals("已取消")
        runBlocking { awaitReleased() }
        compose.onNodeWithTag("probe-slow").performClick()
        compose.waitUntil(5_000) { probeResources().jobs == 1uL }
        compose.onNodeWithTag("probe-back").performClick()
        compose.onNodeWithTag("root-title").assertTextEquals("表世界 · 书库")
        runBlocking { awaitReleased() }
        val ticks = probeResources().ticks
        compose.onNodeWithTag("open-probe").performClick()
        compose.onNodeWithTag("probe-status").assertTextEquals("等待验证")
        runBlocking { delay(100) }
        assertEquals(ticks, probeResources().ticks)
        Log.i("FluxFfiTest", "PASS replacement, error UI, cancel UI, pop stops Rust work, no stale result, all resources released")
    }

    @Test fun imageCancellationRemovesPartialFile_andMissingInputIsTypedError(): Unit = runBlocking {
        awaitReleased()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ffi-cancel").apply { mkdirs() }
        val source = File(directory, "source.bin")
        val output = File(directory, "copy.bin")
        source.outputStream().use { stream -> repeat(32) { stream.write(ByteArray(64 * 1024) { 42 }) } }
        val session = ProbeSession()
        val before = probeResources().cancelled
        try {
            val job = launch {
                val artifact = session.imageFile(ImageProbeRequest(source.absolutePath, 20u), output.absolutePath)
                artifact.destroy()
                fail("Copy should have been cancelled")
            }
            withTimeout(5_000) { while (probeResources().files != 1uL || output.length() == 0L) delay(5) }
            job.cancelAndJoin()
            withTimeout(5_000) { while (probeResources().jobs != 0uL || probeResources().files != 0uL) delay(5) }
            assertFalse(output.exists())
            assertTrue(source.exists())
            assertEquals(before + 1uL, probeResources().cancelled)
            try {
                session.imageBytes(ImageProbeRequest(File(directory, "missing.png").absolutePath, 0u))
                fail("Missing file should fail")
            } catch (_: ProbeException.Io) { /* expected typed FFI failure */ }
        } finally {
            session.destroy()
            source.delete()
        }
        awaitReleased()
        Log.i("FluxFfiTest", "PASS image cancellation deletes partial output, source preserved, missing input typed Io, all resources released")
    }

    private suspend fun awaitReleased() {
        withTimeout(5_000) {
            while (true) {
                val resources = probeResources()
                if (resources.jobs == 0uL && resources.sessions == 0uL && resources.runtimeThreads == 0uL && resources.files == 0uL) return@withTimeout
                delay(5)
            }
        }
    }

    private fun waitForStatus(prefix: String) {
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("probe-status").fetchSemanticsNode().config[SemanticsProperties.Text]
                .any { it.text.startsWith(prefix) }
        }
    }
}
