package com.flux.manga

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Loopback only; synthetic replies. Never logs or writes request credentials. */
internal class HttpFixture : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { work -> Thread(work, "http-fixture").apply { isDaemon = true } }
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val starts = CopyOnWriteArrayList<Long>()
    val base = "http://127.0.0.1:${server.localPort}/"

    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                sockets += socket
                try { executor.execute { respond(socket) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { socket.close(); break }
            }
        }
    }
    fun count(path: String) = hits[path]?.get() ?: 0

    private fun respond(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader()
            val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val n = hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            starts += System.nanoTime()
            try {
                var status = 200
                var extra = ""
                var body = "ok"
                when {
                    path.startsWith("/catalog") || path == "/image" -> {
                        if (headers["authorization"] != "Bearer fixture-token" || headers["cookie"] != "fixture=cookie") status = 401
                        else body = when (path) {
                            "/catalog?page=1" -> """{"ids":["same-title-1","same-title-2"],"next":"catalog?page=2"}"""
                            "/catalog?page=2" -> """{"ids":["third"],"next":null}"""
                            else -> "synthetic-image-bytes"
                        }
                    }
                    path == "/anonymous" -> if (headers.containsKey("authorization") || headers.containsKey("cookie")) status = 403
                    path == "/set-cookie" -> extra = "Set-Cookie: should-not-persist=yes\r\n"
                    path == "/unauthorized" -> status = 401
                    path == "/forbidden" -> status = 403
                    path == "/missing" -> status = 404
                    path == "/retry" -> if (n == 1) status = 503
                    path == "/fail" -> status = 503
                    path == "/rate" -> { status = 429; extra = "Retry-After: 1\r\n" }
                    path == "/redirect" -> { status = 302; extra = "Location: ${base}anonymous\r\n" }
                    path == "/slow" -> Thread.sleep(3000)
                    path == "/work" -> Thread.sleep(180)
                    path == "/large" -> body = "x".repeat(2048)
                    path == "/chunked" -> {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n800\r\n${"x".repeat(2048)}\r\n0\r\n\r\n".toByteArray())
                        return
                    }
                    path == "/slow-body" -> {
                        socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                        Thread.sleep(3000)
                        socket.getOutputStream().write("ok".toByteArray())
                        return
                    }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            } finally { active.decrementAndGet() }
        } catch (_: java.io.IOException) { // Expected when the client cancels or times out.
        } catch (_: InterruptedException) {
        } finally { sockets.remove(socket); socket.close() }
    }
    override fun close() { server.close(); sockets.forEach { it.close() }; executor.shutdownNow() }
}
