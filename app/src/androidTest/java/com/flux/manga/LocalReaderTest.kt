package com.flux.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.pages.readerTiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal object ReaderFixtures {
    const val title = "合成阅读器五页"
    fun bytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 12000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (i in 0 until 12) {
            paint.color = if (i % 2 == 0) Color.rgb(30, 90, 160) else Color.rgb(70, 140, 120)
            canvas.drawRect(0f, i * 1000f, 600f, (i + 1) * 1000f, paint)
            paint.color = Color.WHITE; paint.textSize = 64f
            canvas.drawText("STRIP ${i + 1} / 12", 30f, i * 1000f + 150, paint)
        }
        val strip = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        return CbzFixtures.archive(listOf("page10.png" to CbzFixtures.image(color = Color.YELLOW),
            "page3.png" to strip, "page2.jpg" to CbzFixtures.image(Bitmap.CompressFormat.JPEG, Color.RED),
            "page4.webp" to CbzFixtures.image(Bitmap.CompressFormat.WEBP_LOSSLESS, Color.GREEN),
            "page1.png" to CbzFixtures.image(color = Color.BLUE)))
    }
    suspend fun prepare(library: CbzLibrary): MangaKey {
        val bytes = bytes()
        return library.import(PrototypeWorld.SFW, CbzSource("$title.cbz") { bytes.inputStream() }).key
    }
    suspend fun prepareUnique(library: CbzLibrary, directory: File): MangaKey {
        val nonce = UUID.randomUUID().toString()
        val entries = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(bytes().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries += entry.name to zip.readBytes() }
        }
        entries += "validation-id.txt" to nonce.toByteArray()
        directory.mkdirs()
        val original = File(directory, "reader-$nonce.cbz").apply { writeBytes(CbzFixtures.archive(entries)) }
        return library.import(PrototypeWorld.SFW, CbzSource("验收阅读器-$nonce.cbz") { original.inputStream() }).key
    }
    fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

@RunWith(AndroidJUnit4::class)
class LocalReaderTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun regionsAndFormatsAreBounded_missingCopyRetry_preservesOriginal(): Unit = runBlocking {
        val store = WorldDataStore(context, "test-reader-${UUID.randomUUID()}")
        val library = CbzLibrary(store)
        val original = File(store.directory(PrototypeWorld.SFW).apply { mkdirs() }, "synthetic-original.cbz").apply { writeBytes(ReaderFixtures.bytes()) }
        val hash = ReaderFixtures.hash(original); val modified = original.lastModified()
        val key = library.import(PrototypeWorld.SFW, CbzSource("${ReaderFixtures.title}.cbz") { original.inputStream() }).key
        val pages = library.detail(key)!!.chapter.pages
        assertEquals(listOf("page1.png", "page2.jpg", "page3.png", "page4.webp", "page10.png"), pages.map { it.id })
        pages.forEach { page -> library.readPage(page, targetWidth = 1080, wholePage = true).let {
            assertTrue(it.width.toLong() * it.height <= 2_097_152); assertTrue(it.height <= 4096); it.recycle()
        } }
        val tiles = readerTiles(pages).filter { it.pageIndex == 2 }
        assertEquals(0, tiles.first().top); assertEquals(12000, tiles.last().bottom)
        tiles.zipWithNext().forEach { (a,b) -> assertEquals(a.bottom, b.top) }
        tiles.forEach { tile -> library.readPage(tile.page, tile.top, tile.bottom).let {
            assertTrue(it.width.toLong() * it.height <= 2_097_152); assertEquals(600, it.width); it.recycle()
        } }
        val book = store.localData { it.localBook("cbz", key.mangaId)!! }
        val copy = File(library.directory, book.archiveName)
        val held = File(library.directory, "held-test.cbz")
        assertTrue(copy.renameTo(held))
        try { library.readPage(pages.first()); fail("Missing copy accepted") } catch (e: CbzException) { assertEquals(ImportFailure.MISSING, e.failure) }
        assertTrue(held.renameTo(copy))
        library.readPage(pages.first()).recycle()
        assertEquals(hash, ReaderFixtures.hash(original)); assertEquals(modified, original.lastModified())
        val folder = File(context.getExternalFilesDir(null), "m2-reader-evidence").apply { mkdirs() }
        File(folder, "decode-source.txt").writeText("PASS PNG/JPEG/WebP; 600x12000 strip decoded in ${tiles.size} contiguous regions, output <= 2 MP; missing copy retry; original SHA256=$hash and mtime=$modified unchanged")
    }
    @Test fun progressIsDurableAndIsolated_repairKeepsAnchor_andExpiredWriteRejected(): Unit = runBlocking {
        val store = WorldDataStore(context, "test-progress-${UUID.randomUUID()}")
        val library = CbzLibrary(store); val key = ReaderFixtures.prepare(library)
        val page = library.detail(key)!!.chapter.pages[2]
        for (mode in ReadingMode.entries) {
            library.saveProgress(key, page.id, .625f, mode)
            val reopened = CbzLibrary(WorldDataStore(context, store.directory(PrototypeWorld.SFW).parentFile!!.name))
            val saved = reopened.detail(key)!!.card.progress!!
            assertEquals(page.id, saved.pageId); assertEquals(3, saved.ordinal)
            assertEquals(.625f, saved.fraction); assertEquals(mode.name, saved.mode); assertTrue(saved.updatedAt > 0)
        }
        try { library.saveProgress(key, page.id, 0f, ReadingMode.LTR) { false }; fail("Expired write") } catch (_: CancellationException) { }
        for (wrong in listOf(key.copy(sourceId = "other"), key.copy(mangaId = "other"))) {
            try { library.saveProgress(wrong, page.id, 0f, ReadingMode.LTR); fail("Wrong key") } catch (_: IOException) { }
        }
        try { library.saveProgress(key.copy(world = PrototypeWorld.NSFW), page.id, 0f, ReadingMode.LTR); fail("Wrong world") } catch (_: CbzException) { }
        try { library.saveProgress(key, "missing", 0f, ReadingMode.LTR); fail("Wrong page") } catch (_: IOException) { }
        val stored = library.detail(key)!!.card.progress!!
        val copy = File(library.directory, store.localData { it.localBook("cbz", key.mangaId)!!.archiveName })
        assertTrue(copy.delete()); assertEquals(key, ReaderFixtures.prepare(library))
        assertEquals(stored, library.detail(key)!!.card.progress)
    }
}

