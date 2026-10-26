package com.flux.manga

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLibraryProcessTest {
    @Test fun writeFavorite(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("libraryPhase") == "write")
        val namespace = args.getString("libraryNamespace")!!
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = CbzLibrary(WorldDataStore(context, namespace))
        val (key, _) = LocalLibraryFixtures.prepare(library)
        library.setFavorite(key, true)
        val detail = library.detail(key)!!
        val folder = File(context.getExternalFilesDir(null), "m2-library-evidence").apply { mkdirs() }
        File(folder, "favorite-process.json").writeText(JSONObject().put("namespace", namespace)
            .put("mangaId", key.mangaId).put("sha256", detail.sha256).put("writer", Process.myPid()).toString())
        assertTrue(detail.card.favorite)
    }
    @Test fun readAndRemoveFavorite(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("libraryPhase") == "read")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "m2-library-evidence")
        val saved = JSONObject(File(folder, "favorite-process.json").readText())
        assertEquals(args.getString("libraryNamespace"), saved.getString("namespace"))
        assertNotEquals(saved.getInt("writer"), Process.myPid())
        val store = WorldDataStore(context, saved.getString("namespace"))
        val key = MangaKey(PrototypeWorld.SFW, "cbz", saved.getString("mangaId"))
        val library = CbzLibrary(store)
        assertTrue(library.detail(key)!!.card.favorite)
        assertEquals(saved.getString("sha256"), library.detail(key)!!.sha256)
        assertEquals(1, library.detail(key)!!.chapter.pages.size)
        library.setFavorite(key, false)
        assertFalse(CbzLibrary(store).detail(key)!!.card.favorite)
        File(folder, "favorite-process-result.txt").writeText("PASS favorite persisted across independent processes ${saved.getInt("writer")} -> ${Process.myPid()}; same archive SHA256 and page metadata; unfavorite persisted on reopen")
    }
}
