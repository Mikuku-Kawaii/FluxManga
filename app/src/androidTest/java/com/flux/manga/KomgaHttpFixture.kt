package com.flux.manga

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.json.JSONObject

/** Synthetic Komga wire subset, using the official 1.29.1 field names. */
internal class KomgaHttpFixture(private val apiKey: String = "synthetic-key", private val basic: Boolean = false) : Closeable {
    data class Request(val method: String, val path: String, val body: String, val validAuth: Boolean)
    val requests = CopyOnWriteArrayList<Request>()
    @Volatile var mode = "normal"
    private val listener = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "komga-fixture").apply { isDaemon = true } }
    val base = "http://127.0.0.1:${listener.localPort}/komga/"
    init {
        executor.execute {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: java.io.IOException) { break }
                sockets += socket
                try { executor.execute { respond(socket) } } catch (_: java.util.concurrent.RejectedExecutionException) { socket.close(); break }
            }
        }
    }
    private fun series(id: String) = """{"id":"$id","name":"ignored filename","booksCount":99,"metadata":{"title":"合成同名","summary":"合成简介","tags":["Tag","tag"," 中文 / &? ","Tag"],"genres":["not-a-tag"]},"unknownFutureField":true}"""
    private fun book(id: String, parent: String = "series1") = """{"id":"$id","seriesId":"$parent","metadata":{"title":"Chapter $id","numberSort":2.5},"media":{"pagesCount":2}}"""
    private fun list(content: String, number: Int = 0, last: Boolean = true) = """{"content":[$content],"number":$number,"size":20,"last":$last}"""
    private fun respond(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) bytes.write(c) }
                return bytes.toString("UTF-8")
            }
            val start = line().split(' ')
            if (start.size < 2) return
            val headers = mutableMapOf<String,String>()
            while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); if(i > 0) headers[h.substring(0,i).lowercase()] = h.substring(i+1).trim() }
            val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var offset = 0
            while(offset < bytes.size) { val n = input.read(bytes, offset, bytes.size-offset); if(n < 0) return; offset += n }
            val request = Request(start[0], start[1], bytes.toString(Charsets.UTF_8), (if (basic) headers["authorization"] == "Basic dXNlcjpwYXNz" && headers["x-api-key"] == null else headers["x-api-key"] == apiKey && headers["authorization"] == null) && headers["cookie"] == null)
            requests += request
            val path = request.path.substringBefore('?')
            val currentMode = mode
            if (currentMode == "slow") Thread.sleep(3000)
            var status = 200
            var mime = "application/json;charset=UTF-8"
            var body = when {
                path == "/komga/api/v2/users/me" -> """{"id":"synthetic-user","email":"unused@example.invalid"}"""
                path == "/komga/api/v1/libraries" -> """[{"id":"lib","name":"合成书库"}]"""
                path == "/komga/api/v1/readlists" -> list("""{"id":"list","name":"合成列表","ordered":true,"filtered":false}""")
                path == "/komga/api/v1/readlists/list/books" -> list("""{"id":"book2","seriesId":"series1","seriesTitle":"合成作品"}""")
                path == "/komga/api/v1/series/list" -> {
                    if (request.method != "POST" || headers["content-type"] != "application/json") status = 400
                    val payload = JSONObject(request.body)
                    val condition = payload.optJSONObject("condition")
                    if (condition != null) {
                        val filters = condition.getJSONArray("allOf")
                        if (filters.getJSONObject(0).getJSONObject("tag").getString("operator") != "is" ||
                            filters.getJSONObject(1).getJSONObject("deleted").getString("operator") != "isFalse") status = 400
                    }
                    val query = payload.optString("fullTextSearch")
                    if(query == "empty") list("")
                    else if(request.path.contains("page=1")) list("", 1)
                    else list("${series("series1")},${series("series2")}", if(currentMode == "wrong-page") 1 else 0, false)
                }
                path == "/komga/api/v2/tags" -> {
                    if (request.method != "GET" || !request.path.contains("include=SERIES") || !request.path.contains("size=20")) status = 400
                    if (request.path.contains("page=1")) list("", 1) else list("\"Tag\",\"tag\"", 0, false)
                }
                path == "/komga/api/v1/series/updated" -> {
                    if (request.method != "GET" || !request.path.contains("deleted=false") || !request.path.contains("size=20")) status = 400
                    if (request.path.contains("page=1")) list(series("series2"), 1)
                    else list("", if (currentMode == "wrong-page") 1 else 0, false)
                }
                path == "/komga/api/v1/series/series1" -> series(if(currentMode == "wrong-id") "series2" else "series1")
                path == "/komga/api/v1/books/list" -> {
                    val filter = JSONObject(request.body).getJSONObject("condition").getJSONObject("seriesId")
                    if (request.method != "POST" || filter.getString("operator") != "is" || filter.getString("value") != "series1") status = 400
                    list("${book("book2", if(currentMode == "wrong-parent") "other" else "series1")},${book("book10")}")
                }
                path == "/komga/api/v1/books/book2" -> book("book2", if(currentMode == "wrong-parent") "other" else "series1")
                path == "/komga/api/v1/books/book2/pages" -> """[{"number":2,"mediaType":"image/png","width":10,"height":20},{"number":${if(currentMode == "duplicate-page") 2 else 1},"mediaType":"image/jpeg"}]"""
                path == "/komga/api/v1/books/book2/pages/1" -> { mime = "image/jpeg"; "synthetic-page-bytes" }
                else -> { status = 404; "{}" }
            }
            if (!request.validAuth) status = 401
            if (currentMode == "forbidden") status = 403
            if (currentMode == "retry" && requests.count { it.path == request.path } == 1) status = 503
            if (currentMode == "malformed") body = "{secret-server-response"
            if (currentMode == "html") { body = "<html>Login</html>"; mime = "text/html" }
            val payload = body.toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status Fixture\r\nContent-Type: $mime\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(payload); flush()
            }
        } catch (_: java.io.IOException) { } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally { sockets -= socket; socket.close() }
    }
    override fun close() { listener.close(); sockets.forEach { it.close() }; executor.shutdownNow() }
}
