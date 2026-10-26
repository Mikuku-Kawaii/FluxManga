package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.WorldDataStore
import com.flux.manga.storage.ProtectionState
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only creates isolated UUID namespaces. Does not use business/, delete old fixtures, or change device settings. */
@RunWith(AndroidJUnit4::class)
class StorageProtectionTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    private fun store() = WorldDataStore(InstrumentationRegistry.getInstrumentation().targetContext, "m4-${UUID.randomUUID()}")
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun snapshot(root: File) = if (!root.exists()) emptyMap() else root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to digest(it) }
    @Test fun inspectingFreshNamespaceDoesNotCreateAnything(): Unit = runBlocking {
        val data = store()
        for (world in PrototypeWorld.entries) {
            assertFalse(data.directory(world).exists())
            assertEquals(ProtectionState.NOT_CREATED, data.inspectProtection(world).state)
            assertFalse(data.directory(world).exists())
        }
    }
    @Test fun inspectingExistingKeysDoesNotWriteDatabaseOrEnvelopes(): Unit = runBlocking {
        val data = store()
        for (world in PrototypeWorld.entries) {
            data.prepare(world)
            val before = snapshot(data.directory(world))
            assertEquals(ProtectionState.KEYS_READABLE, data.inspectProtection(world).state)
            assertEquals(before, snapshot(data.directory(world)))
        }
    }
    @Test fun corruptDatabasesAreNotDeletedOrRecreated(): Unit = runBlocking {
        val data = store()
        for (world in PrototypeWorld.entries) {
            data.prepare(world)
            data.databaseFile(world).writeBytes(ByteArray(4096) { 0x5a.toByte() })
            val before = snapshot(data.directory(world))
            try { data.prepare(world); fail("Corrupt fixture must fail") } catch (_: IOException) { }
            assertTrue(data.databaseFile(world).exists())
            // Sidecars may be created by SQLite; every preexisting fixture must still match.
            for ((path, hash) in before) assertEquals(path, hash, digest(File(data.directory(world), path)))
        }
    }
    @Test fun orphanWalDoesNotCreateDatabaseOrKeys(): Unit = runBlocking {
        val data = store(); val world = PrototypeWorld.NSFW
        data.directory(world).mkdirs()
        val wal = File(data.databaseFile(world).path + "-wal").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val before = snapshot(data.directory(world))
        try { data.prepare(world); fail("Incomplete fixture must fail") } catch (_: IOException) { }
        assertFalse(data.databaseFile(world).exists()); assertTrue(wal.exists())
        assertEquals(before, snapshot(data.directory(world)))
    }
}
