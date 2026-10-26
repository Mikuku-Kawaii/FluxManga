package com.flux.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.manga.data.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Owned synthetic originals only; preparation is a separate process from measurement. */
internal object ContentFixtures {
    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val evidence get() = File(context.getExternalFilesDir(null), "m2-validation-evidence").apply { mkdirs() }
    val library get() = CbzLibrary(WorldDataStore(context))
    const val prefix = "V06-V09 合成"
    fun cards() = runBlocking { library.cards(PrototypeWorld.SFW).filter { it.title.startsWith(prefix) } }
    fun readerId() = JSONObject(File(evidence, "fixtures.json").readText()).getString("readerId")
    fun verifyOriginals() {
        val array = JSONObject(File(evidence,"fixtures.json").readText()).getJSONArray("originals")
        repeat(array.length()) { i ->
            val item = array.getJSONObject(i); val file = File(item.getString("path"))
            check(ReaderFixtures.hash(file) == item.getString("sha256"))
            check(file.lastModified() == item.getLong("mtime"))
        }
    }
}
class ContentFixturePreparationTest {
    @get:org.junit.Rule val physicalForeground = PhysicalForegroundRule()
    @Test fun prepare(): Unit = runBlocking {
        val lib = ContentFixtures.library
        // Reuse fixed files on rerun; never overwrite an existing original.
        val originals = JSONArray()
        suspend fun import(name: String, bytes: () -> ByteArray): MangaKey {
            val file = File(ContentFixtures.evidence, "$name.cbz")
            if (!file.exists()) file.writeBytes(bytes())
            originals.put(JSONObject().put("path",file.absolutePath).put("sha256",ReaderFixtures.hash(file)).put("mtime",file.lastModified()))
            return lib.import(PrototypeWorld.SFW, CbzSource("$name.cbz") { file.inputStream() }).key
        }
        for (i in 0..7) {
            import("${ContentFixtures.prefix} ${i + 1} ${if (i % 2 == 0) "亮封面" else "暗封面"}") {
                val bitmap = Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.drawColor(if (i % 2 == 0) Color.rgb(250,247,231) else Color.rgb(18,22,35))
                for (stripe in 0..8) {
                    paint.color=if (i % 2 == 0) Color.rgb(220,226,240) else Color.rgb(40,55,70)
                    canvas.drawRect(0f,stripe*100f,600f,stripe*100f+30,paint)
                }
                paint.color=if(i%2==0) Color.DKGRAY else Color.WHITE; paint.textSize=60f
                canvas.drawText("SYNTHETIC ${i+1}",40f,450f,paint)
                val image=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }
                bitmap.recycle()
                CbzFixtures.archive(listOf("page1.png" to image,"page2.png" to CbzFixtures.image(),"fixture.txt" to "$i".toByteArray()))
            }
        }
        val reader = import("${ContentFixtures.prefix} 长图阅读") { ReaderFixtures.bytes() }
        lib.saveProgress(reader,"page1.png",0f,ReadingMode.LTR)
        File(ContentFixtures.evidence,"fixtures.json").writeText(JSONObject().put("readerId",reader.mangaId).put("originals",originals).toString(2))
        ContentFixtures.verifyOriginals()
    }
}

