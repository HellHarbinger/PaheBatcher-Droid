package com.pahebatcher.app.data.stream

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 17 §21-A — a minimal recording HTTP/1.1 server for the header-on-wire
 * test.
 *
 * Deliberately hand-rolled on a raw [ServerSocket] instead of pulling in
 * MockWebServer: the project has no test-only HTTP dependency, and the
 * Phase 17 brief forbids introducing one just for this test.
 *
 * Behaviour kept to exactly what a Media3 + OkHttp HLS playback stack needs:
 * one response per connection (`Connection: close`), a 200 for the playlist,
 * the segments and the AES key, and a 404 for anything else — so an unintended
 * request is visible as a 404 rather than as silence.
 *
 * [resources] lets a test serve its OWN fixture (a master playlist with a
 * subtitle rendition, its media playlist, its WebVTT…). It is checked first,
 * so a fixture path replaces the built-in one, and with the default (empty)
 * map the behaviour is byte-for-byte what the original server did.
 *
 * @property resources path → body served with a content type derived from the
 *   path extension.
 */
internal class RecordingHlsServer(
    private val resources: Map<String, ByteArray> = emptyMap(),
) : Closeable {

    data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    ) {
        /** Case-insensitive header lookup (HTTP field names are case-insensitive). */
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private val serverSocket = ServerSocket()
    private val recorded = CopyOnWriteArrayList<Recorded>()
    private val failures = CopyOnWriteArrayList<String>()

    @Volatile
    private var running = true

    private val acceptThread: Thread

    val baseUrl: String

    init {
        serverSocket.reuseAddress = true
        // Bind to 127.0.0.1 explicitly: `InetAddress.getLoopbackAddress()` may
        // resolve to the IPv6 `::1`, and the baseUrl below says 127.0.0.1 — a
        // mismatch there is a silent ECONNREFUSED rather than a failed assert.
        serverSocket.bind(InetSocketAddress(InetAddress.getByName(IPv4_LOOPBACK), 0))
        baseUrl = "http://$IPv4_LOOPBACK:${serverSocket.localPort}"
        acceptThread = Thread({ acceptLoop() }, "phase17-hls-server").apply {
            isDaemon = true
            start()
        }
    }

    /** Every request seen so far, in arrival order. */
    fun requests(): List<Recorded> = recorded.toList()

    /** Requests whose path contains [needle]. */
    fun requestsFor(needle: String): List<Recorded> =
        recorded.filter { it.path.contains(needle) }

    private fun acceptLoop() {
        while (running) {
            val connection = try {
                serverSocket.accept()
            } catch (error: Exception) {
                break
            }
            Thread({ handle(connection) }, "phase17-hls-connection").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(connection: java.net.Socket) {
        try {
            connection.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
                    }
                }

                val path = target.substringBefore('?')
                recorded += Recorded(method = method, path = path, headers = headers)

                val body = bodyFor(path)
                val out: OutputStream = socket.getOutputStream()
                if (body == null) {
                    out.write(
                        ("HTTP/1.1 404 Not Found\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(),
                    )
                } else {
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: ${contentTypeFor(path)}\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray())
                    out.write(body)
                }
                out.flush()
            }
        } catch (error: Exception) {
            failures += error.message ?: error.javaClass.simpleName
        }
    }

    private fun bodyFor(path: String): ByteArray? = resources[path] ?: when (path) {
        // Exactly one playlist path, so anything else — notably the deliberately
        // missing URL a playback-error test points at — is a real 404.
        "/playlist.m3u8" -> PLAYLIST.toByteArray()
        else -> when {
            path.startsWith("/seg") -> SEGMENT
            path.startsWith("/key") -> AES_KEY
            path.endsWith(".mp4") -> SEGMENT
            else -> null
        }
    }

    private fun contentTypeFor(path: String): String = when {
        path.endsWith(".vtt") -> "text/vtt"
        path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        path.startsWith("/key") -> "application/octet-stream"
        else -> "video/mp2t"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (bytes.isEmpty()) null else bytes.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.append(value.toChar())
        }
        return bytes.toString()
    }

    /** Problems seen while serving — surfaced as test diagnostics, never swallowed. */
    fun failures(): List<String> = failures.toList()

    override fun close() {
        running = false
        try {
            serverSocket.close()
        } catch (_: Exception) {
        }
        acceptThread.interrupt()
    }

    companion object {
        /** The loopback the client side of every test URL uses. */
        private const val IPv4_LOOPBACK = "127.0.0.1"

        /**
         * A three-segment VOD media playlist with an AES-128 key, so the test
         * exercises all three resource kinds Media3 fetches: playlist, key and
         * segment.
         */
        private val PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="/key",IV=0x00000000000000000000000000000001
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
            #EXTINF:4.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        /** Eight MPEG-TS packets worth of sync bytes — enough to be fetched and parsed. */
        private val SEGMENT = ByteArray(188 * 8) { 0x47.toByte() }

        /** Exactly 16 bytes, as an AES-128 key must be. */
        private val AES_KEY = ByteArray(16) { it.toByte() }
    }
}
