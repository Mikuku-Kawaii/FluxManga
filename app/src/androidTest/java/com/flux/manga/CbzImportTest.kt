package com.flux.manga

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CbzImportTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val world = PrototypeWorld.SFW
    private fun store() = WorldDataStore(context, "test-cbz-${UUID.randomUUID()}")
    private fun source(bytes: ByteArray = CbzFixtures.valid(), name: String = "Synthetic.cbz") = CbzSource(name) { bytes.inputStream() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private suspend fun expect(reason: ImportFailure, action: suspend () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: CbzException) { assertEquals(reason, e.failure) }
    }

    @Test fun copyValidateNaturalOrderCoverAndReopen_preserveSourceAndDeduplicate(): Unit = runBlocking {
        val store = store(); val library = CbzLibrary(store)
        val original = File(context.cacheDir, "source-${UUID.randomUUID()}.cbz").apply { writeBytes(CbzFixtures.valid()) }
        try {
            val before = hash(original.readBytes()); val modified = original.lastModified()
            val input = CbzSource(original.name) { original.inputStream() }
            val first = library.import(world, input)
            val detail = library.detail(first.key)!!
            assertEquals(CbzFixtures.order, detail.chapter.pages.map { it.entryName })
            assertEquals(listOf(1, 2, 3, 4, 5), detail.chapter.pages.map { it.ordinal })
            assertEquals(CbzFixtures.order, detail.chapter.pages.map { it.id })
            assertTrue(detail.chapter.pages.all { it.manga == first.key && it.chapterId == "cbz-1" && it.width == 96 && it.height == 144 })
            library.cover(detail)!!.let { assertEquals(96, it.width); assertEquals(144, it.height); it.recycle() }
            val second = library.import(world, input)
            assertTrue(second.duplicate); assertEquals(first.key, second.key)
            assertEquals(1, CbzLibrary(store).cards(world).size)
            assertEquals(detail, CbzLibrary(store).detail(first.key))
            assertArrayEquals(before, hash(original.readBytes())); assertEquals(modified, original.lastModified())
            assertEquals(1, library.directory.listFiles()!!.size)
            assertArrayEquals(before, hash(library.directory.listFiles()!!.single().readBytes()))
            assertTrue(naturalPageOrder.compare("p99999999999999999999.png", "p100000000000000000000.png") < 0)
        } finally { original.delete() }
    }

    @Test fun rejectsMalformedEmptyUnsupportedTraversalDuplicateCrcAndLimits(): Unit = runBlocking {
        val library = CbzLibrary(store())
        val png = CbzFixtures.image()
        val valid = CbzFixtures.archive(listOf("page1.png" to png, "page2.png" to png), stored = true)
        val duplicate = valid.copyOf().also { bytes ->
            val from = "page2.png".toByteArray(); val to = "page1.png".toByteArray()
            for (i in 0..bytes.size - from.size) if (from.indices.all { bytes[i + it] == from[it] }) to.copyInto(bytes, i)
        }
        val badCrc = valid.copyOf().also { bytes ->
            val start = (0..bytes.size - png.size).first { i -> png.indices.all { bytes[i + it] == png[it] } }
            bytes[start + png.size - 1] = (bytes[start + png.size - 1].toInt() xor 1).toByte()
        }
        val cases = listOf(
            ImportFailure.CORRUPT to "not a zip".toByteArray(),
            ImportFailure.CORRUPT to valid.copyOf(valid.size - 30),
            ImportFailure.EMPTY to CbzFixtures.archive(listOf("ComicInfo.xml" to byteArrayOf(1))),
            ImportFailure.UNSUPPORTED to CbzFixtures.archive(listOf("page.gif" to png)),
            ImportFailure.CORRUPT to CbzFixtures.archive(listOf("page.png" to byteArrayOf(1, 2, 3))),
            ImportFailure.CORRUPT to CbzFixtures.archive(listOf("../escape.png" to png)),
            ImportFailure.CORRUPT to duplicate,
            ImportFailure.CORRUPT to badCrc,
            ImportFailure.LIMIT to CbzFixtures.archive(listOf("page.png" to ByteArray(33 * 1024 * 1024))),
            ImportFailure.LIMIT to CbzFixtures.archive((1..4097).map { "meta$it.txt" to byteArrayOf() }),
        )
        for ((reason, bytes) in cases) {
            expect(reason) { library.import(world, source(bytes)) }
            assertTrue(library.cards(world).isEmpty()); assertTrue(library.directory.listFiles()!!.isEmpty())
        }
        expect(ImportFailure.NOT_CBZ) { library.import(world, source(name = "not.zip")) }
        expect(ImportFailure.WORLD) { library.import(PrototypeWorld.NSFW, source()) }
        expect(ImportFailure.WORLD) { library.cards(PrototypeWorld.NSFW) }
    }

    @Test fun cancelledAndFailedCopyPublishNothing_permissionAndNoSpaceAreExplicit(): Unit = runBlocking {
        val store = store(); val library = CbzLibrary(store)
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val bytes = CbzFixtures.valid()
        val job = launch(Dispatchers.IO) {
            library.import(world, CbzSource("cancel.cbz") {
                object : ByteArrayInputStream(bytes) {
                    var reads = 0
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (reads++ > 0) { reached.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                        return super.read(b, off, len)
                    }
                }
            })
        }
        assertTrue(reached.await(10, TimeUnit.SECONDS)); job.cancel(); release.countDown(); job.join()
        assertTrue(library.cards(world).isEmpty()); assertTrue(library.directory.listFiles()!!.isEmpty())
        expect(ImportFailure.PERMISSION) { library.import(world, CbzSource("revoked.cbz") { throw SecurityException("synthetic revoked grant") }) }
        val fullDisk = CbzLibrary(store) { file -> object : FileOutputStream(file) {
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("injected write failure", ErrnoException("write", OsConstants.ENOSPC)) }
        } }
        expect(ImportFailure.NO_SPACE) { fullDisk.import(world, source()) }
        expect(ImportFailure.IO) { library.import(world, CbzSource("broken.cbz") { object : InputStream() { override fun read(): Int = throw IOException("synthetic provider read error") } }) }
        try { library.import(world, source()) { false }; fail("Expired session imported") } catch (_: CancellationException) { }
        assertTrue(library.cards(world).isEmpty()); assertTrue(library.directory.listFiles()!!.isEmpty())
    }

    @Test fun missingCopyIsUnavailable_reimportRepairsStableIdentity_andOrphansAreRemoved(): Unit = runBlocking {
        val store = store(); val library = CbzLibrary(store)
        val imported = library.import(world, source())
        val originalDetail = library.detail(imported.key)!!
        assertTrue(library.directory.listFiles()!!.single().delete())
        assertFalse(library.cards(world).single().available)
        expect(ImportFailure.MISSING) { library.cover(originalDetail) }
        val repaired = library.import(world, source())
        assertEquals(imported.key, repaired.key); assertFalse(repaired.duplicate)
        assertEquals(originalDetail.chapter, library.detail(repaired.key)!!.chapter)
        File(library.directory, "${UUID.randomUUID()}.part").writeText("interrupted")
        File(library.directory, "${UUID.randomUUID()}.cbz").writeText("renamed before DB commit")
        val unrelated = File(library.directory, "leave-me.txt").apply { writeText("not an owned archive") }
        assertTrue(CbzLibrary(store).cards(world).single().available)
        assertEquals(2, library.directory.listFiles()!!.size); assertTrue(unrelated.exists())
    }
}

