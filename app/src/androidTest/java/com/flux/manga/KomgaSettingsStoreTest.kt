package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Prepared for the deferred device round. Each test uses a new isolated namespace. */
@RunWith(AndroidJUnit4::class)
class KomgaSettingsStoreTest {
    @Test fun encryptedConfigurationReopensWithoutPlaintextKey_andRejectedUpdateKeepsOldRecord(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-komga-settings-${UUID.randomUUID()}"
        val data = WorldDataStore(context, namespace)
        val store = KomgaSettingsStore(data)
        val configuration = KomgaConfiguration("komga-${UUID.randomUUID()}", "https://example.test/komga/", "synthetic-key-${UUID.randomUUID()}")
        store.save(configuration) { true }
        val reopened = KomgaSettingsStore(WorldDataStore(context, namespace)).load()!!
        assertEquals(configuration.sourceId, reopened.sourceId)
        assertEquals(configuration.baseUrl, reopened.baseUrl)
        assertEquals(configuration.apiKey, reopened.apiKey)
        assertFalse(data.databaseFile(PrototypeWorld.SFW).readBytes().toString(Charsets.ISO_8859_1).contains(configuration.apiKey))
        try {
            store.save(KomgaConfiguration("komga-${UUID.randomUUID()}", "https://other.test/", "replacement-key")) { false }
            fail("Expired owner must not replace saved configuration")
        } catch (_: CancellationException) { }
        assertEquals(configuration.sourceId, store.load()!!.sourceId)
    }
    @Test fun unreadablePayloadRemainsInStore(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = WorldDataStore(context, "test-komga-settings-${UUID.randomUUID()}")
        val payload = "synthetic invalid JSON".toByteArray()
        data.putSession(PrototypeWorld.SFW, "komga-user-configuration-v1", payload)
        try { KomgaSettingsStore(data).load(); fail("Invalid config must fail") }
        catch (_: java.io.IOException) { }
        assertArrayEquals(payload, data.session(PrototypeWorld.SFW, "komga-user-configuration-v1"))
    }
    @Test fun legacyApiKeyUpgradesInPlaceAndBasicCheckpointRollbackPreservesProgress(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-komga-account-${UUID.randomUUID()}"
        val data = WorldDataStore(context, namespace)
        val id = "komga-${UUID.randomUUID()}"
        val legacy = org.json.JSONObject().put("version", 1).put("sourceId", id).put("baseUrl", "https://fixture.invalid/")
            .put("apiKey", "synthetic-legacy-secret").toString().toByteArray()
        data.putSession(PrototypeWorld.SFW, "komga-user-configuration-v1", legacy)
        val settings = KomgaSettingsStore(data); val accounts = settings.accounts
        val loaded = settings.load()!!
        assertEquals(id, accounts.activate(loaded, "userA") { true }.sourceId)
        val basic = accounts.activate(KomgaConfiguration("komga-${UUID.randomUUID()}", loaded.baseUrl,
            "synthetic-basic-password", KomgaAuth.BASIC, "fixture@example.invalid"), "userA") { true }
        assertEquals(id, basic.sourceId)
        val raw = data.localData { it.session("komga-user-configuration-v1")!! }
        assertFalse(raw.toString(Charsets.UTF_8).contains("synthetic-basic-password"))
        assertFalse(raw.toString(Charsets.UTF_8).contains("fixture@example.invalid"))
        val reopened = KomgaSettingsStore(WorldDataStore(context, namespace))
        assertEquals(KomgaAuth.BASIC, reopened.load()!!.auth)
        assertEquals("synthetic-basic-password", reopened.load()!!.apiKey)
        val progress = OnlineReadingProgress(id, "s", "b", "p", 2, .4f, "STRIP", 19, true)
        data.localData { it.putOnlineProgress(progress) }
        val list = com.flux.manga.core.KomgaAuthorizedList("l", "合成列表", com.flux.manga.core.KomgaListKind.READ_LIST, true, false)
        val items = listOf(com.flux.manga.core.KomgaImportEntry("s", "合成作品", "b", 0u))
        val task = reopened.accounts.begin(basic, list, items, true) { true }
        var gates = 0
        try { reopened.accounts.commit(basic, task, items, null) { ++gates <= 3 }; fail() } catch (_: CancellationException) { }
        assertNull(data.localData { it.onlineBook(id, "s") }); assertEquals(task, reopened.accounts.task(id) { true })
        reopened.accounts.commit(basic, task, items, null) { true }
        assertEquals(progress, data.localData { it.onlineProgress(id, "s") }); assertFalse(data.localData { it.onlineBook(id, "s")!! }.favorite)
        reopened.accounts.logout(basic) { true }; assertNull(reopened.load())
        assertEquals(progress, data.localData { it.onlineProgress(id, "s") }); assertTrue(reopened.accounts.task(id) { true }!!.done)
    }
}
