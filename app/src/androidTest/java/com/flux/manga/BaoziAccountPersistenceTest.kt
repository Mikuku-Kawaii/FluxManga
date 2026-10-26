package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Deferred device coverage. UUID namespace and synthetic inputs; never opens business storage. */
@RunWith(AndroidJUnit4::class)
class BaoziAccountPersistenceTest {
    @Test fun encryptedSessionReopensAndRollbackPreservesBooksProgressAndCheckpoint(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-baozi-account-${UUID.randomUUID()}"
        val data = WorldDataStore(app, namespace)
        val source = baoziAccountId("synthetic-account")
        val account = BaoziAccountStore(data.accountImportDatabase())
        account.activate(source, "synthetic-account", "synthetic-secret-tsid", { true })
        val raw = data.localData { it.session("baozi-account-v1:$source")!! }
        assertFalse(raw.toString(Charsets.UTF_8).contains("synthetic-secret-tsid"))
        assertFalse(raw.toString(Charsets.UTF_8).contains("synthetic-account"))
        val reopened = BaoziAccountStore(WorldDataStore(app, namespace).accountImportDatabase())
        assertEquals("synthetic-secret-tsid", reopened.current { true }!!.token)
        val progress = OnlineReadingProgress(source, "book", "chapter", "page", 3, .25f, "STRIP", 123, true)
        data.worldData(PrototypeWorld.SFW) { it.putOnlineProgress(progress) }
        val task = reopened.begin(source, listOf(BaoziImportItem("book", "合成书目")), true, { true }).job!!
        var gates = 0
        try { reopened.batch(source, task.id) { ++gates <= 3 }; fail() } catch (_: CancellationException) { }
        assertNull(data.localData { it.onlineBook(source, "book") })
        assertEquals(0, reopened.current { true }!!.job!!.cursor)
        reopened.batch(source, task.id, { true })
        assertEquals(progress, data.localData { it.onlineProgress(source, "book") })
        assertFalse(data.localData { it.onlineBook(source, "book")!! }.favorite)
        reopened.logout(source, { true })
        assertNull(reopened.current { true }!!.token)
        assertNotNull(data.localData { it.onlineBook(source, "book") })
    }
}
