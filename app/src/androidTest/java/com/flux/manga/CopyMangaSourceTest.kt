package com.flux.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flux.manga.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic loopback fixture only; no account, user files or external service. Temporary fixture files only. */
@RunWith(AndroidJUnit4::class)
class CopyMangaSourceTest {
    @Test fun recommendationsAndDetailCrossFfiWithSourceIdentity(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 8000
            val worker = Executors.newSingleThreadExecutor()
            val result = worker.submit {
                val comic = """{"path_word":"synthetic","name":"合成作品","brief":"合成简介"}"""
                for ((path, body) in listOf(
                    "api/v3/recs?pos=3200102&limit=21&offset=0&platform=3" to
                        """{"code":200,"results":{"total":1,"offset":0,"limit":21,"list":[{"comic":$comic}]}}""",
                    "api/v3/comic2/synthetic?platform=3" to """{"code":200,"results":{"comic":$comic}}""")) {
                    server.accept().use { socket ->
                        socket.soTimeout = 8000
                        val reader = socket.getInputStream().bufferedReader()
                        assertEquals("GET /$path HTTP/1.1", reader.readLine())
                        val headers = buildList { while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; add(line.lowercase()) } }
                        assertTrue(headers.contains("platform: 3"))
                        assertFalse(headers.any { it.startsWith("x-api-key:") || it.startsWith("cookie:") })
                        val data = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(data); flush()
                        }
                    }
                }
            }
            val context = SourceContext(SourceWorld.SFW, "copy-synthetic", "test-process", 9uL, null)
            val source = CopyMangaSource(context, "http://127.0.0.1:${server.localPort}/", SourceHttpConfig(8000u, 1u, 0u, 0u, 65536u))
            try {
                assertTrue(source.capabilities().recommendations)
                assertTrue(source.capabilities().pages)
                val page = source.recommendations(context, SourcePageRequest.Start)
                assertEquals(context, page.context)
                assertEquals(SourceContinuation.End, page.continuation)
                assertEquals("copy-synthetic", page.items.single().key.sourceId)
                val detail = source.detail(context, page.items.single().key)
                assertEquals("合成简介", detail.description)
                assertNull(detail.tags)
                result.get(10, TimeUnit.SECONDS)
            } finally { source.invalidate(); source.destroy(); worker.shutdownNow() }
        }
    }
    @Test fun groupedChapterAndCoverBindingsUseOwnedImageFile(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 8000
            val base = "http://127.0.0.1:${server.localPort}/"
            val worker = Executors.newSingleThreadExecutor()
            val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==", android.util.Base64.DEFAULT)
            val result = worker.submit {
                val book = """{"code":200,"results":{"comic":{"path_word":"book","name":"合成作品","cover":"${base}cover.png"},"groups":{"main":{"path_word":"main","name":"正文","count":1}}}}"""
                val chapters = """{"code":200,"results":{"offset":0,"limit":100,"total":1,"list":[{"uuid":"chapter-1","name":"第 1.5 话","comic_path_word":"book","group_path_word":"main"}]}}"""
                for ((index, path) in listOf("api/v3/comic2/book?platform=3", "api/v3/comic/book/group/main/chapters?limit=100&offset=0&platform=3", "cover.png").withIndex()) {
                    server.accept().use { socket ->
                        socket.soTimeout = 8000
                        val reader = socket.getInputStream().bufferedReader()
                        assertEquals("GET /$path HTTP/1.1", reader.readLine())
                        val headers = buildList { while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; add(line.lowercase()) } }
                        if (index == 2) assertFalse(headers.any { it.startsWith("authorization:") || it.startsWith("cookie:") || it.startsWith("platform:") })
                        val bytes = if (index == 2) png else (if (index == 0) book else chapters).toByteArray(Charsets.UTF_8)
                        val mime = if (index == 2) "image/png" else "application/json"
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                        }
                    }
                }
            }
            val context = SourceContext(SourceWorld.SFW, "copy-cover-synthetic", "isolated", 1uL, null)
            val key = SourceMangaKey(SourceWorld.SFW, context.sourceId, "book")
            val source = CopyMangaSource(context, base, SourceHttpConfig(8000u, 2u, 0u, 0u, 65536u))
            val directory = java.nio.file.Files.createTempDirectory(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "copy-cover-test-").toFile()
            try {
                val book = source.book(context, key)
                assertEquals("main", book.groups!!.single().id)
                assertEquals(key, book.groups!!.single().manga)
                val chapters = source.groupChapters(context, key, "main", SourcePageRequest.Start)
                assertEquals("第 1.5 话", chapters.items.single().title)
                val file = source.coverFile(context, key, directory.path)
                val path = file.path()
                file.use {
                    assertArrayEquals(png, java.io.File(path).readBytes())
                    val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                    assertNotNull(bitmap); assertEquals(1, bitmap.width); bitmap.recycle()
                }
                assertFalse(java.io.File(path).exists())
                result.get(10, TimeUnit.SECONDS)
            } finally { source.invalidate(); source.destroy(); worker.shutdownNow(); directory.delete() }
        }
    }
    @Test fun bodyPageOrderImageOwnershipAndProgressReopen(): Unit = runBlocking {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "copy-reader-fixture-").toFile()
        val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==", android.util.Base64.DEFAULT)
        ServerSocket(0).use { server ->
            server.soTimeout = 8000
            val base = "http://127.0.0.1:${server.localPort}/"
            val worker = Executors.newSingleThreadExecutor()
            val result = worker.submit {
                val comic = """{"path_word":"work","name":"合成阅读"}"""
                val detail = """{"code":200,"results":{"comic":$comic}}"""
                val body = """{"code":200,"results":{"comic":$comic,"chapter":{"uuid":"ch","comic_path_word":"work","words":[1,0],"contents":[{"uuid":"last","url":"${base}last.png"},{"uuid":"first","url":"${base}first.png"}]}}}"""
                for ((index, path) in listOf("api/v3/comic2/work?platform=3", "api/v3/comic/work/chapter2/ch?platform=3", "first.png").withIndex()) {
                    server.accept().use { socket ->
                        socket.soTimeout = 8000
                        val input = socket.getInputStream().bufferedReader()
                        assertEquals("GET /$path HTTP/1.1", input.readLine())
                        val headers = buildList { while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; add(line.lowercase()) } }
                        if (index == 2) assertFalse(headers.any { it.startsWith("authorization:") || it.startsWith("cookie:") || it.startsWith("platform:") })
                        val bytes = if (index == 2) png else (if (index == 0) detail else body).toByteArray()
                        val mime = if (index == 2) "image/png" else "application/json"
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                        }
                    }
                }
            }
            val context = SourceContext(SourceWorld.SFW, "copy-reader-fixture", "isolated", 1uL, null)
            val key = SourceMangaKey(context.world, context.sourceId, "work")
            val source = CopyMangaSource(context, base, SourceHttpConfig(8000u, 1u, 0u, 0u, 65536u))
            val databaseFile = java.io.File(directory, "fixture.db")
            fun database() = androidx.room.Room.databaseBuilder(app, com.flux.manga.data.WorldDatabase::class.java, databaseFile.path).build()
            try {
                val pages = source.pages(context, key, "ch", SourcePageRequest.Start)
                assertEquals(listOf("ch:uuid:first", "ch:uuid:last"), pages.items.map { it.id })
                assertTrue(pages.items.all { it.manga == key && it.context == context })
                val image = source.pageFile(context, pages.items.first(), directory.path)
                val path = image.path()
                image.use {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                    assertNotNull(bitmap); assertEquals(1, bitmap.height); bitmap.recycle()
                }
                assertFalse(java.io.File(path).exists())
                val stored = com.flux.manga.data.OnlineReadingProgress(key.sourceId, key.mangaId, "ch", pages.items[1].id, 2, .4f, "STRIP", 1L, true)
                val first = database()
                try {
                    first.data().putOnlineProgress(stored)
                    first.data().putOnlineProgress(stored.copy(sourceId = "komga-fixture", ordinal = 1, fraction = .1f))
                } finally { first.close() }
                val reopened = database()
                try {
                    assertEquals(stored, reopened.data().onlineProgress(key.sourceId, key.mangaId))
                    assertEquals(1 to .4f, com.flux.manga.data.onlineReadingAnchor(pages.items, reopened.data().onlineProgress(key.sourceId, key.mangaId)))
                    assertEquals(.1f, reopened.data().onlineProgress("komga-fixture", key.mangaId)!!.fraction)
                } finally { reopened.close() }
                result.get(10, TimeUnit.SECONDS)
            } finally {
                source.invalidate(); source.destroy(); worker.shutdownNow()
                // Only this test's randomly created database and empty fixture directory.
                android.database.sqlite.SQLiteDatabase.deleteDatabase(databaseFile)
                directory.delete()
            }
        }
    }

}
