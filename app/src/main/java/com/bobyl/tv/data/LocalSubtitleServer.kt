package com.bobyl.tv.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Session snapshots only: no directory traversal, filesystem access or upstream requests. */
class LocalSubtitleServer(files: List<Pair<String, ByteArray>>) : AutoCloseable {
    init {
        require(files.isNotEmpty() && files.size <= 32)
        require(files.all { CustomSubtitleFormat.supported(it.first) && it.second.size <= 4 * 1024 * 1024 })
        require(files.sumOf { it.second.size.toLong() } <= 16 * 1024 * 1024)
    }
    private val token = UUID.randomUUID().toString()
    private val paths = files.mapIndexed { i, file -> "/$token/$i.${file.first.substringAfterLast('.').lowercase()}" }
    private val content = paths.zip(files.map { it.second.copyOf() }).toMap()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val urls = paths.map { "http://127.0.0.1:${server.localPort}$it" }
    private val workers = Executors.newFixedThreadPool(2)
    private val slots = Semaphore(16)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { socket.close(); continue }
                sockets.add(socket)
                try { workers.execute {
                    try { serve(socket) } finally { sockets.remove(socket); runCatching { socket.close() }; slots.release() }
                } } catch (_: Exception) { sockets.remove(socket); socket.close(); slots.release() }
            }
        }, "Bobyl-local-subtitles").apply { isDaemon = true; start() }
    }
    private fun serve(socket: Socket) {
        runCatching {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val header = StringBuilder()
            while (header.length < 8192 && !header.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return
                header.append(next.toChar())
            }
            val request = header.toString().substringBefore("\r\n").split(' ')
            val method = request.getOrNull(0)
            val path = request.getOrNull(1)
            val bytes = content[path]
            val status = when {
                !header.endsWith("\r\n\r\n") -> "431 Request Header Fields Too Large"
                method !in listOf("GET", "HEAD") -> "405 Method Not Allowed"
                bytes == null -> "404 Not Found"
                else -> "200 OK"
            }
            val body = if (status == "200 OK") bytes!! else ByteArray(0)
            val mime = if (path?.endsWith(".vtt") == true) "text/vtt" else "text/plain"
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") write(body)
                flush()
            }
        }
    }
    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}

internal object CustomSubtitleFormat {
    fun supported(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("srt", "ass", "ssa", "vtt")
}
