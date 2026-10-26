package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldStorageTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val public = PrototypeWorld.SFW
    private val vault = PrototypeWorld.NSFW
    private fun store(name: String = "test-${UUID.randomUUID()}") = WorldDataStore(context, name)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private suspend fun expectFailure(action: suspend () -> Unit) {
        try { action(); fail("Expected storage failure") } catch (_: IOException) { }
    }

    @Test fun generationInvalidationInsideTransaction_preservesBothWorlds(): Unit = runBlocking {
        val data = store()
        data.remember(vault, "sample", "private-original")
        data.remember(public, "sample", "public-original")
        val previous = data.readingDefault(vault)
        var allowed = true
        try {
            data.worldData(vault, { allowed }) { dao ->
                dao.clearHistory("sample")
                dao.putReadingPreference(ReadingPreference(mode = ReadingMode.RTL.name))
                allowed = false
            }
            fail("Expected generation rejection before commit")
        } catch (_: CancellationException) { }
        assertEquals(listOf("private-original"), data.history(vault, "sample"))
        assertEquals(listOf("public-original"), data.history(public, "sample"))
        assertEquals(previous, data.readingDefault(vault))
    }

    @Test fun jobCancellationInsideTransaction_rollsBackWithoutClearingData(): Unit = runBlocking {
        val data = store()
        data.remember(vault, "sample", "private-original")
        val writer = launch {
            val owner = currentCoroutineContext()
            data.worldData(vault) { dao -> dao.clearHistory("sample"); owner.cancel() }
        }
        writer.join()
        assertTrue(writer.isCancelled)
        assertEquals(listOf("private-original"), data.history(vault, "sample"))
    }

    @Test fun catalogHistoryCredentials_reopenAndIsolateSameIdentifiers(): Unit = runBlocking {
        val namespace = "test-${UUID.randomUUID()}"
        val first = store(namespace)
        for (world in PrototypeWorld.entries) {
            first.prepare(world)
            first.remember(world, "sample", "history-${world.name}")
            first.remember(world, "second-source", "other-${world.name}")
            first.putSession(world, "sample", "FAKE_TOKEN_${world.name}".toByteArray())
            first.putSession(world, "second-source", "SECOND_${world.name}".toByteArray())
        }
        val reopened = store(namespace)
        assertNotEquals(reopened.databaseFile(public), reopened.databaseFile(vault))
        for (world in PrototypeWorld.entries) {
            assertEquals(60, reopened.catalog(world).size)
            assertEquals(world, reopened.catalog(world).first().world)
            assertEquals(listOf("history-${world.name}"), reopened.history(world, "sample"))
            assertEquals(listOf("other-${world.name}"), reopened.history(world, "second-source"))
            assertEquals("FAKE_TOKEN_${world.name}", reopened.session(world, "sample")!!.decodeToString())
            assertFalse(reopened.databaseFile(world).readBytes().decodeToString().contains("FAKE_TOKEN"))
        }
        val header = "SQLite format 3\u0000".toByteArray()
        assertArrayEquals(header, reopened.databaseFile(public).readBytes().take(16).toByteArray())
        assertFalse(reopened.databaseFile(vault).readBytes().take(16).toByteArray().contentEquals(header))
        assertFalse(reopened.databaseFile(vault).readBytes().decodeToString().contains("history-NSFW"))
        reopened.clearHistory(public, "sample")
        reopened.putSession(public, "sample", null)
        assertTrue(reopened.history(public, "sample").isEmpty())
        assertNotNull(reopened.session(public, "second-source"))
        assertNotNull(reopened.session(vault, "sample"))
        assertEquals(listOf("history-NSFW"), reopened.history(vault, "sample"))
    }

    @Test fun cache_reopenMemoryCopiesIdentityAndCipherTamper(): Unit = runBlocking {
        val namespace = "test-${UUID.randomUUID()}"
        val first = store(namespace)
        val keys = listOf(
            ResourceKey(SampleKey(public, "sample", "1"), "page-1"),
            ResourceKey(SampleKey(vault, "sample", "1"), "page-1"),
            ResourceKey(SampleKey(vault, "other", "1"), "page-1"),
            ResourceKey(SampleKey(vault, "sample", "2"), "page-1"),
            ResourceKey(SampleKey(vault, "sample", "1"), "page-2"),
        )
        keys.forEachIndexed { i, key -> first.putResource(key, "SYNTHETIC_IMAGE_$i".toByteArray()) }
        first.resource(keys[1])!!.fill(0)
        assertEquals("SYNTHETIC_IMAGE_1", first.resource(keys[1])!!.decodeToString())
        first.clearMemory()
        val reopened = store(namespace)
        keys.forEachIndexed { i, key -> assertEquals("SYNTHETIC_IMAGE_$i", reopened.resource(key)!!.decodeToString()) }
        assertFalse(reopened.cacheFile(keys[1]).readBytes().decodeToString().contains("SYNTHETIC_IMAGE"))
        reopened.clearMemory()
        // Same-world cross-resource replay must fail GCM's identity binding.
        reopened.cacheFile(keys[4]).writeBytes(reopened.cacheFile(keys[1]).readBytes())
        expectFailure { reopened.resource(keys[4]) }
        val file = reopened.cacheFile(keys[1])
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        expectFailure { reopened.resource(keys[1]) }
        assertEquals("SYNTHETIC_IMAGE_0", reopened.resource(keys[0])!!.decodeToString())
    }

    @Test fun missingAndCorruptKeys_failWithoutReplacingDatabase(): Unit = runBlocking {
        val store = store()
        store.remember(vault, "sample", "private-history")
        val db = store.databaseFile(vault)
        val original = digest(db.readBytes())
        val key = store.keys(vault, "database").envelopeFile
        val envelope = key.readBytes()
        assertTrue(key.delete())
        expectFailure { store.prepare(vault) }
        assertArrayEquals(original, digest(db.readBytes()))
        key.writeBytes(envelope.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        expectFailure { store.prepare(vault) }
        assertArrayEquals(original, digest(db.readBytes()))
        key.writeBytes(envelope)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
            load(null); deleteEntry(store.keys(vault, "database").alias)
        }
        expectFailure { store.prepare(vault) }
        assertArrayEquals(original, digest(db.readBytes()))
    }

    @Test fun staleWritesRejected_historyBoundedAndDeduplicated(): Unit = runBlocking {
        val store = store()
        try { store.remember(public, "sample", "expired") { false }; fail("Expired write accepted") }
        catch (_: CancellationException) { }
        assertTrue(store.history(public, "sample").isEmpty())
        repeat(12) { store.remember(public, "sample", "q$it") }
        assertEquals(10, store.history(public, "sample").size)
        store.remember(public, "sample", " q11 ")
        assertEquals(1, store.history(public, "sample").count { it == "q11" })
        try { store.clearHistory(public, "sample") { false }; fail("Expired clear accepted") }
        catch (_: CancellationException) { }
        assertEquals(10, store.history(public, "sample").size)
    }

    // Invoked separately by the verification script, so the second method has a new process.
    @Test fun writeProcessFixture(): Unit = runBlocking {
        val store = store("test-process-reopen")
        for (world in PrototypeWorld.entries) {
            store.clearHistory(world, "sample")
            store.remember(world, "sample", "process-${world.name}")
            store.putSession(world, "sample", "FAKE_PROCESS_${world.name}".toByteArray())
            store.putResource(ResourceKey(SampleKey(world, "sample", "1"), "page"), "PIXELS_${world.name}".toByteArray())
        }
        java.io.File(store.directory(public), "writer-pid").writeText(android.os.Process.myPid().toString())
    }
    @Test fun readProcessFixture(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Run after writeProcessFixture in a separate invocation", InstrumentationRegistry.getArguments().getString("storagePhase") == "read")
        val store = store("test-process-reopen")
        val writer = java.io.File(store.directory(public), "writer-pid").readText().toInt()
        assertNotEquals(writer, android.os.Process.myPid())
        for (world in PrototypeWorld.entries) {
            assertEquals(listOf("process-${world.name}"), store.history(world, "sample"))
            assertEquals("FAKE_PROCESS_${world.name}", store.session(world, "sample")!!.decodeToString())
            assertEquals("PIXELS_${world.name}", store.resource(ResourceKey(SampleKey(world, "sample", "1"), "page"))!!.decodeToString())
        }
        val evidence = java.io.File(context.getExternalFilesDir(null), "m1-storage-evidence").apply { mkdirs() }
        java.io.File(evidence, "process-reopen.txt").writeText("PASS separate instrumentation processes: writer=$writer reader=${android.os.Process.myPid()}; both catalogs, histories, encrypted fake sessions and resource bytes reopened")
    }
}

