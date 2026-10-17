package com.bobyl.tv.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Loopback-only, session-scoped redirect server. Never forwards account cookies to players. */
class EpisodeRelay(
    val episodes: List<String>,
    private val resolveSubtitle: ((String) -> String?)? = null,
    private val resolve: (String) -> String,
) : AutoCloseable {
    private val token = UUID.randomUUID().toString()
    init { require(episodes.isNotEmpty() && episodes.all { it.matches(Regex("[0-9]+")) }) }
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val workers = Executors.newFixedThreadPool(3)
    // MX probes several adjacent subtitle extensions concurrently.
    private val slots = Semaphore(32)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val resolved = ConcurrentHashMap<String, Pair<Long, String>>()
    private val knownUrls = ConcurrentHashMap<String, String>()
    private val subtitles = ConcurrentHashMap<String, Pair<Long, String?>>()
    val urls = episodes.associateWith { "http://127.0.0.1:${server.localPort}/$token/$it.mp4" }
    fun subtitleUrl(episode: String, source: String): String {
        require(episode in episodes)
        return urls.getValue(episode).removeSuffix("mp4") + subtitleExtension(source)
    }
    private fun subtitleExtension(source: String): String = java.net.URI(source).path.orEmpty()
        .substringAfterLast('.', "srt").lowercase().takeIf { it in listOf("srt", "ass", "ssa", "vtt") } ?: "srt"
    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { socket.close(); continue }
                sockets.add(socket)
                try { workers.execute {
                    try { serve(socket) } finally { sockets.remove(socket); socket.close(); slots.release() }
                } } catch (_: Exception) { sockets.remove(socket); socket.close(); slots.release() }
            }
        }, "Bobyl-episode-relay").apply { isDaemon = true; start() }
    }
    fun episodeFor(url: String?): String? = urls.entries.firstOrNull { it.value == url }?.key
        ?: url?.let { knownUrls[it] }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val bytes = ArrayList<Byte>()
            while (bytes.size < 8192) {
                val b = input.read()
                if (b < 0) return
                bytes.add(b.toByte())
                if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
            }
            val request = bytes.toByteArray().toString(Charsets.US_ASCII).substringBefore("\r\n").split(' ')
            val method = request.getOrNull(0)
            val path = request.getOrNull(1)
            val episode = episodes.firstOrNull { number ->
                listOf("mp4", "srt", "ass", "ssa", "vtt").any { path == "/$token/$number.$it" }
            }
            if (method !in listOf("GET", "HEAD")) { reply(socket, "405 Method Not Allowed"); return }
            if (episode == null) { reply(socket, "404 Not Found"); return }
            if (path != "/$token/$episode.mp4") {
                val subtitle = synchronized(subtitles) {
                    val cached = subtitles[episode]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }
                    if (cached != null) cached.second else resolveSubtitle?.invoke(episode).also {
                        if (it != null) require(PlaybackParser.isMediaUrl(it) && '\r' !in it && '\n' !in it)
                        subtitles[episode] = System.currentTimeMillis() to it
                    }
                }
                if (subtitle == null || path != "/$token/$episode.${subtitleExtension(subtitle)}") {
                    reply(socket, "404 Not Found"); return
                }
                reply(socket, "307 Temporary Redirect", "Location: $subtitle\r\n")
                return
            }
            // Coalesce MX's HEAD/GET and Range probes; expire URLs independently of catalog cache.
            val url = synchronized(resolved) {
                resolved[episode]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.second
                    ?: resolve(episode).also {
                        require(PlaybackParser.isMediaUrl(it) && '\r' !in it && '\n' !in it)
                        resolved[episode] = System.currentTimeMillis() to it
                        knownUrls[it] = episode
                    }
            }
            reply(socket, "307 Temporary Redirect", "Location: $url\r\n")
        } catch (_: Exception) { runCatching { reply(socket, "502 Bad Gateway") } }
    }
    private fun reply(socket: Socket, status: String, headers: String = "") {
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\n${headers}Cache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            flush()
        }
    }
    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        resolved.clear(); knownUrls.clear()
        subtitles.clear()
    }
}
