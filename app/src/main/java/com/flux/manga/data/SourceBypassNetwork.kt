package com.flux.manga.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import com.flux.manga.core.configureSourceNetwork
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** No process-wide network binding. Only opted-in source sockets use a physical network.
 * The loopback relay carries encrypted TLS bytes; HTTP/auth/cookies remain in Rust/WebView. */
internal object SourceBypassNetwork {
    private lateinit var app: Context
    private var nativeTunnel: DirectTunnel? = null
    // Relays wait on sockets in both directions. An elastic IO view keeps idle
    // tunnels from exhausting the app's ordinary IO dispatcher (32 pairs + listeners).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(72))
    private val slots = Semaphore(32)

    @Synchronized fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        for (kind in SourceKind.entries) {
            if (enabled(kind)) {
                // A failed local listener must not silently revert a saved direct route to VPN.
                val proxy = try { tunnel().authenticatedUrl } catch (_: IOException) { "http://127.0.0.1:1" }
                prefixes(kind).forEach { configureSourceNetwork(it, proxy) }
            }
        }
    }

    fun enabled(kind: SourceKind): Boolean = SourceSelection(app, kind.world).bypassVpn(kind)

    private fun prefixes(kind: SourceKind): List<String> = if (kind == SourceKind.BAOZI) listOf("baozi-", "baozimh-") else listOf(when (kind) {
        SourceKind.KOMGA -> "komga-"
        SourceKind.COPY -> "copymanga-"
        SourceKind.BAOZI -> "baozi-"
        SourceKind.GUAZI -> "guazi-"
        SourceKind.KUAIKAN -> "kuaikan-"
        SourceKind.MANHUAGUI -> "manhuagui-"
        SourceKind.JM -> "jmcomic-"
        SourceKind.PICA -> "picacg-"
        SourceKind.EH -> "ehentai-"
        SourceKind.EX -> "exhentai-"
    })

    fun kind(scope: String): SourceKind? = SourceKind.of(scope) ?: when (scope.lowercase()) {
        "komga" -> SourceKind.KOMGA
        "copy", "copymanga" -> SourceKind.COPY
        "baozi" -> SourceKind.BAOZI
        "jm", "jmcomic" -> SourceKind.JM
        "pica", "picacg" -> SourceKind.PICA
        "eh", "ehentai" -> SourceKind.EH
        "ex", "exhentai" -> SourceKind.EX
        else -> null
    }

    @Synchronized private fun tunnel(): DirectTunnel = nativeTunnel ?: DirectTunnel(null).also { nativeTunnel = it }

    suspend fun set(kind: SourceKind, value: Boolean, active: () -> Boolean) = withContext(Dispatchers.IO) {
        val proxy = if (value) tunnel().authenticatedUrl else null
        withContext(NonCancellable) {
            SourceSelection(app, kind.world).setBypassVpn(kind, value, active)
            prefixes(kind).forEach { configureSourceNetwork(it, proxy) }
        }
    }

    /** Dedicated short-lived browser relay; unauthenticated CONNECT is restricted
     * to origins explicitly requested by the sole active login WebView. */
    fun browser(scope: String, hosts: Set<String>): DirectTunnel? = kind(scope)?.takeIf(::enabled)?.let { DirectTunnel(hosts) }

    internal class DirectTunnel internal constructor(browserHosts: Set<String>?) : Closeable {
        private val manager = app.getSystemService(ConnectivityManager::class.java)
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val token = UUID.randomUUID().toString()
        private val authorization = "Basic " + Base64.encodeToString("flux:$token".toByteArray(), Base64.NO_WRAP)
        private val allowedHosts = browserHosts?.let { ConcurrentHashMap.newKeySet<String>().apply { addAll(it) } }
        private val connections = ConcurrentHashMap<Socket, Network?>()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val job = SupervisorJob(scope.coroutineContext[Job])
        private val work = CoroutineScope(scope.coroutineContext + job)
        val proxyAddress get() = "http://127.0.0.1:${server.localPort}"
        val authenticatedUrl get() = "http://flux:$token@127.0.0.1:${server.localPort}"
        @Volatile private var closed = false
        fun allowBrowserHost(host: String?) { if (!closed && host != null) allowedHosts?.add(host.lowercase()) }
        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                connections.forEach { (socket, bound) -> if (bound == network) runCatching { socket.close() } }
            }
        }
        init {
            try {
                manager.registerNetworkCallback(NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            } catch (error: Exception) { server.close(); job.cancel(); throw IOException("无法准备直连网络", error) }
            work.launch {
                while (isActive && !closed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    if (!slots.tryAcquire()) { socket.close(); continue }
                    sockets.add(socket)
                    work.launch { relay(socket) }.invokeOnCompletion {
                        sockets.remove(socket); runCatching { socket.close() }; slots.release()
                    }
                }
            }
        }
        private fun network(): Network = manager.allNetworks.filter { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.sortedByDescending { network ->
            val caps = manager.getNetworkCapabilities(network)
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 4 else 0) +
                (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 2 else 0)
        }.firstOrNull() ?: throw IOException("没有可用的直连网络")

        private fun connect(network: Network, host: String, port: Int): Socket {
            // Preserve the existing Manhuagui alias workaround, resolving it on
            // the SAME physical network instead of using the VPN's Fake-IP DNS.
            val addresses = if (host in setOf("www.manhuagui.com", "tw.manhuagui.com")) {
                runCatching { network.getAllByName("www.mhgui.com") }.getOrElse { network.getAllByName(host) }
            } else network.getAllByName(host)
            var failure: IOException? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            for (address in addresses) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (closed || remaining <= 0) break
                val socket = network.socketFactory.createSocket()
                connections[socket] = network
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, port), minOf(remaining, 3_000).toInt())
                    return socket
                } catch (error: IOException) { failure = error; connections.remove(socket); socket.close() }
            }
            throw failure ?: IOException("直连不可用")
        }

        private suspend fun relay(local: Socket) {
            var remote: Socket? = null
            var established = false
            try {
                local.soTimeout = 10_000
                local.tcpNoDelay = true
                // Read only CONNECT headers, never consume the first TLS bytes.
                val input = local.getInputStream()
                val header = StringBuilder()
                while (header.length < 16_384 && !header.endsWith("\r\n\r\n")) {
                    val next = input.read(); if (next == -1) return
                    header.append(next.toChar())
                }
                val lines = header.toString().split("\r\n")
                val request = lines.first().split(' ')
                if (!header.endsWith("\r\n\r\n") || request.size != 3 || request[0] != "CONNECT") throw IOException("Invalid tunnel")
                val target = URI("https://${request[1]}")
                val host = target.host?.lowercase() ?: throw IOException("Invalid host")
                val port = target.port
                if (target.userInfo != null || port !in 1..65535 || target.path.isNotEmpty() ||
                    (host in setOf("127.0.0.1", "localhost", "[::1]") && port == server.localPort)) throw IOException("Invalid target")
                if (allowedHosts != null) {
                    if (host !in allowedHosts) throw IOException("Unrequested browser host")
                } else if (lines.none { it.substringBefore(':').equals("Proxy-Authorization", true) &&
                        it.substringAfter(':').trim() == authorization }) throw IOException("Unauthorized tunnel")
                val network = network()
                connections[local] = network
                remote = connect(network, host, port)
                val upstream = remote
                local.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                established = true
                local.soTimeout = 90_000; upstream.soTimeout = 90_000
                coroutineScope {
                    val upload = launch {
                        try { input.copyTo(upstream.getOutputStream(), 16_384) }
                        catch (_: IOException) { }
                        finally { runCatching { upstream.shutdownOutput() } }
                    }
                    try { upstream.getInputStream().copyTo(local.getOutputStream(), 16_384) }
                    finally { local.close(); upstream.close(); upload.cancel() }
                }
            } catch (_: IOException) {
                if (!established) runCatching { local.getOutputStream().write("HTTP/1.1 502 Direct network unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
            } catch (_: SecurityException) {
                // Android/VPN policy can deny use of the selected network.
            } finally {
                connections.remove(local); runCatching { local.close() }
                remote?.let { connections.remove(it); runCatching { it.close() } }
            }
        }
        override fun close() {
            closed = true; runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }; sockets.clear()
            connections.keys.forEach { runCatching { it.close() } }; connections.clear()
            manager.unregisterNetworkCallback(callback); job.cancel(); allowedHosts?.clear()
        }
    }
}
