package com.flux.manga

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.core.*
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Real Room / Keystore and private files, isolated from the user's queue and books.
 * The page transport is synthetic; this does not validate an OEM foreground service. */
class DownloadPersistenceTest {
    @get:Rule val foreground = PhysicalForegroundRule()

    @Test fun failedDownloadResumesAfterReopenAndCleanupPreservesOtherSourceAndOriginal(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-download-${UUID.randomUUID()}"
        val world = WorldDataStore(context, namespace)
        val directory = world.directory(PrototypeWorld.SFW).apply { mkdirs() }
        val original = File(directory, "synthetic-original.png").apply { writeBytes(CbzFixtures.image()) }
        val originalHash = ReaderFixtures.hash(original)
        val originalTime = original.lastModified()
        val files = DownloadFiles(File(directory, "downloads"))
        var failSecond = true
        val fetched = mutableListOf<String>()
        var released = 0
        val network = object : DownloadNetwork {
            override suspend fun pages(job: ChapterDownload): List<SourcePageResource> {
                val source = SourceContext(SourceWorld.SFW, job.source, "fixture", 1uL, null)
                return (1..3).map { n -> SourcePageResource(SourceMangaKey(SourceWorld.SFW, job.source, job.manga),
                    job.chapter, "p$n", n.toUInt(), "fixture:$n", null, null, null, source) }
            }
            override suspend fun image(job: ChapterDownload, page: SourcePageResource, directory: File): DownloadImage {
                fetched += "${job.source}/${page.id}"
                if (failSecond && page.id == "p2") throw IOException("合成单页失败")
                val temp = File(directory, "fixture-${UUID.randomUUID()}.png").apply { writeBytes(original.readBytes()) }
                return DownloadImage(temp) { released++; temp.delete() }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: DownloadController? = null
        suspend fun idle(c: DownloadController) = withTimeout(15_000) {
            while (!c.state.value.loaded || c.state.value.controlling || c.state.value.jobs.any {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
            }) delay(20)
            check(c.state.value.error == null) { c.state.value.error.orEmpty() }
        }
        try {
            var store = DownloadStore(world.accountImportDatabase())
            var c = DownloadController(scope, { true }, store, files, network).also { controller = it }
            c.start(); idle(c)
            c.enqueue("fixture-a", "work", listOf("chapter" to "合成章节")); idle(c)
            val failed = c.state.value.jobs.single()
            assertEquals(DownloadStatus.FAILED, failed.status)
            assertEquals(1, failed.completed)
            c.close()
            // A fresh store/controller must retain the failed job and not silently restart it.
            store = DownloadStore(WorldDataStore(context, namespace).accountImportDatabase())
            c = DownloadController(scope, { true }, store, files, network).also { controller = it }
            c.start(); idle(c)
            assertEquals(failed, c.state.value.jobs.single())
            failSecond = false
            c.resume(failed.id); idle(c)
            val complete = c.state.value.jobs.single()
            assertEquals(DownloadStatus.COMPLETE, complete.status)
            assertEquals(1, fetched.count { it == "fixture-a/p1" })
            complete.pages.indices.forEach { index ->
                assertTrue(files.valid(complete, index))
                val bitmap = BitmapFactory.decodeFile(files.file(complete, index).path)
                assertNotNull(bitmap); bitmap.recycle()
            }
            c.enqueue("fixture-b", "work", listOf("chapter" to "另一来源同 ID")); idle(c)
            val neighbor = c.state.value.jobs.single { it.source == "fixture-b" }
            c.stop(complete.id, discard = true); idle(c)
            assertFalse(c.state.value.jobs.any { it.id == complete.id })
            assertFalse(files.file(complete, 0).exists())
            assertTrue(files.valid(neighbor, 0))
            assertEquals(originalHash, ReaderFixtures.hash(original))
            assertEquals(originalTime, original.lastModified())
            assertEquals(6, released)
        } finally { controller?.close(); scope.cancel() }
    }
}
