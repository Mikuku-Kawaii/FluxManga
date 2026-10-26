package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CbzProcessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val evidence get() = File(context.getExternalFilesDir(null), "m2-cbz-evidence").apply { mkdirs() }
    @Test fun holdInCopyForExternalKill(): Unit = runBlocking {
        assumeTrue(args.getString("cbzProcess") == "hold")
        val library = CbzLibrary(WorldDataStore(context, args.getString("cbzNamespace")!!))
        library.import(PrototypeWorld.SFW, CbzSource("completed.cbz") { CbzFixtures.valid().inputStream() })
        library.import(PrototypeWorld.SFW, CbzSource("interrupted.cbz") {
            object : ByteArrayInputStream(CbzFixtures.valid()) {
                var reads = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (reads++ > 0) {
                        File(evidence, "copy-ready.txt").writeText(android.os.Process.myPid().toString())
                        Thread.sleep(60_000) // Harness kills this process after observing an actual partial copy.
                        error("Harness did not kill the importing process")
                    }
                    return super.read(b, off, len)
                }
            }
        })
    }
    @Test fun reopenAfterExternalKill_removesOnlyUncommittedFiles(): Unit = runBlocking {
        assumeTrue(args.getString("cbzProcess") == "read")
        val library = CbzLibrary(WorldDataStore(context, args.getString("cbzNamespace")!!))
        val writer = File(evidence, "copy-ready.txt").readText().toInt()
        assertNotEquals(writer, android.os.Process.myPid())
        assertEquals(1, library.directory.listFiles()!!.count { it.extension == "part" && it.length() > 0 })
        val cards = library.cards(PrototypeWorld.SFW)
        assertEquals(1, cards.size); assertTrue(cards.single().available)
        assertEquals(1, library.directory.listFiles()!!.size)
        assertEquals(CbzFixtures.order, library.detail(cards.single().key)!!.chapter.pages.map { it.entryName })
        File(evidence, "interrupted-process-results.txt").writeText("PASS SIGKILL during copy: writer=$writer reader=${android.os.Process.myPid()}; nonempty partial existed before recovery; removed partial; one completed book and its natural page order preserved")
    }
}
