package com.pahebatcher.app.data.hls

import com.pahebatcher.app.domain.hls.HlsBytesResult
import com.pahebatcher.app.domain.hls.HlsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration proof that [HlsHostConcurrencyLimiter] really gates requests issued
 * through the PRODUCTION [OkHttpHlsClient] — not merely that the two classes compile
 * together.
 *
 * A real local HTTP origin counts how many requests are genuinely open against it at
 * once and refuses to answer the first wave until the configured cap has actually been
 * reached. That makes the test fail in BOTH directions:
 *
 *  - if the limiter were not consulted (a wiring regression), every request opens at
 *    once and the observed peak exceeds the cap;
 *  - if the cap were enforced too aggressively, the origin's latch never opens and the
 *    run times out with the peak below the cap.
 *
 * The port is bound on loopback with an ephemeral port and the path carries no
 * credentials, so nothing here touches the network or logs a secret.
 */
class OkHttpHlsClientHostLimiterIntegrationTest {

    private val context = HlsClient.RequestContext(
        userAgent = "HostLimiterTest/1.0",
        referer = "https://example.invalid/",
    )

    private lateinit var origin: CountingOrigin

    @Before
    fun startOrigin() {
        origin = CountingOrigin(limit = LIMIT, holdMs = HOLD_MS)
        origin.start()
    }

    @After
    fun stopOrigin() {
        origin.close()
    }

    @Test
    fun `real HTTP requests to one host never exceed the per-host cap`() = runBlocking {
        val client = OkHttpHlsClient(HlsHostConcurrencyLimiter(LIMIT))

        val results = coroutineScope {
            (0 until REQUESTS).map { i ->
                async(Dispatchers.IO) {
                    client.getBytes(origin.urlFor("seg$i.ts"), context, TIMEOUT_MS)
                }
            }.awaitAll()
        }

        assertEquals(
            "Every request must succeed against the real origin",
            REQUESTS,
            results.count { it is HlsBytesResult.Success },
        )
        assertEquals(
            "The origin must never see more than the cap in flight at once",
            LIMIT,
            origin.peak(),
        )
        assertEquals(
            "The origin must actually have reached the cap, or this proves nothing",
            true,
            origin.reachedCap(),
        )
        assertEquals(
            "No request may be left dangling after the run",
            0,
            origin.current(),
        )
    }

    @Test
    fun `a client without a limiter is not capped`() = runBlocking {
        // Guards the wiring in the other direction: the counterfactual. Without the
        // limiter the origin genuinely sees the unbounded burst Android used to send.
        val client = OkHttpHlsClient(null)

        coroutineScope {
            (0 until REQUESTS).map { i ->
                async(Dispatchers.IO) {
                    client.getBytes(origin.urlFor("seg$i.ts"), context, TIMEOUT_MS)
                }
            }.awaitAll()
        }

        assertTrue(
            "Without a limiter the burst must NOT be capped (peak=${origin.peak()})",
            origin.peak() > LIMIT,
        )
    }

    companion object {
        private const val LIMIT = 3
        private const val REQUESTS = 12
        private const val TIMEOUT_MS = 15_000L

        /**
         * How long the origin keeps a response open once the cap is satisfied. Wide
         * enough that an uncapped burst is actually observable at the counter, but
         * small: the capped case is four waves, so the suite stays about a second.
         */
        private const val HOLD_MS = 250L
    }
}

/**
 * A minimal loopback HTTP origin that records how many requests are simultaneously
 * open, and withholds its first responses until [limit] are concurrently open.
 *
 * Holding the responses open is what turns this into a real observation of in-flight
 * work: a request counts as in flight only while its socket is open and unread, which
 * is exactly the window [HlsHostConcurrencyLimiter] is supposed to bound.
 */
private class CountingOrigin(private val limit: Int, private val holdMs: Long) : AutoCloseable {

    private val serverSocket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "counting-origin").apply { isDaemon = true }
    }
    private val current = AtomicInteger(0)
    private val peak = AtomicInteger(0)
    private val capReached = CountDownLatch(1)
    private val closed = AtomicInteger(0)
    private val port = serverSocket.localPort

    fun start() {
        pool.execute {
            while (closed.get() == 0) {
                try {
                    val socket = serverSocket.accept()
                    pool.execute { handle(socket.getInputStream(), socket.getOutputStream(), socket) }
                } catch (e: Exception) {
                    if (closed.get() != 0) return@execute
                }
            }
        }
    }

    private fun handle(input: InputStream, output: java.io.OutputStream, socket: java.net.Socket) {
        val opened = current.incrementAndGet()
        peak.updateAndGet { maxOf(it, opened) }
        if (opened >= limit) capReached.countDown()
        try {
            // Read and discard the request head so the client sees a real exchange.
            val buffer = ByteArray(1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val head = String(buffer, 0, read)
                if (head.contains("\r\n\r\n")) break
            }

            // Hold the response until the cap is genuinely observed open at once,
            // then keep it open for a further window so a concurrent burst from the
            // caller is actually observable rather than racing past the counter.
            capReached.await(5, TimeUnit.SECONDS)
            Thread.sleep(holdMs)

            val body = "DATA"
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
            output.write(response.toByteArray())
            output.flush()
        } catch (e: Exception) {
            // Client-side cancellation is acceptable here; the accounting still holds.
        } finally {
            current.decrementAndGet()
            try {
                socket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    fun urlFor(path: String): String = "http://127.0.0.1:$port/$path"

    fun peak(): Int = peak.get()

    fun current(): Int = current.get()

    fun reachedCap(): Boolean = capReached.count == 0L

    override fun close() {
        closed.incrementAndGet()
        try {
            serverSocket.close()
        } catch (ignored: Exception) {
        }
        pool.shutdownNow()
    }
}
