package com.pahebatcher.app.data.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.support.awaitCondition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 17 §21-A — headers on the wire.
 *
 * The whole point of the OkHttp transport is that the CDN authorises the
 * playlist, the segments AND the AES key by header, so this asserts the header
 * set at the socket: a recording local HTTP server sees exactly what Media3 +
 * OkHttp send.
 *
 * Two levels, both through production code:
 *
 * 1. **the real player** — [HlsPlaybackSourceFactory.create] → `HlsMediaSource`
 *    → `ExoPlayer.prepare()`, waiting for all three resource kinds to arrive;
 *    this is the assertion that the actual playback stack sends them;
 * 2. **the transport alone** — the same factory's `DataSource` opened directly
 *    for each resource kind, a deterministic backstop that does not depend on
 *    Media3's buffering schedule.
 *
 * No Cloudflare, no external network: everything is served from a loopback
 * socket on the device.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class StreamHlsHeaderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var server: RecordingHlsServer
    private lateinit var factory: HlsPlaybackSourceFactory

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    private val userAgent = "Phase17TestAgent/1.0"
    private val referer = "https://kwik.example.invalid/some-page"
    private val cookie = "cloudflare_session=phase17"

    private fun streamInfo(cookies: List<Map<String, String>> = listOf(mapOf("name" to "cloudflare_session", "value" to "phase17"))) =
        StreamInfo(
            url = "${server.baseUrl}/playlist.m3u8",
            cookies = cookies,
            userAgent = userAgent,
            referer = referer,
            title = "",
            audio = "jpn",
            fansub = "",
        )

    @Before
    fun setUp() {
        server = RecordingHlsServer()
        factory = HlsPlaybackSourceFactory()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            player?.release()
            player = null
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }
        server.close()
    }

    // -----------------------------------------------------------------------
    // 1. The real Media3 + OkHttp playback stack
    // -----------------------------------------------------------------------

    @Test
    fun theRealPlaybackStackSendsUserAgentRefererAndCookieOnPlaylistSegmentAndKeyRequests() {
        val source = factory.create(
            info = streamInfo(),
            mediaId = "phase17_header_episode",
            title = "A Title · Episode 1 [SUB]",
        )

        instrumentation.runOnMainSync {
            val exo = ExoPlayer.Builder(context).build()
            player = exo
            // A real output surface so the video renderer advances and Media3
            // keeps loading — without one the codec path is undefined and the
            // segment/key requests could simply never be scheduled.
            val texture = SurfaceTexture(0)
            surfaceTexture = texture
            val output = Surface(texture)
            surface = output
            exo.setVideoSurface(output)
            exo.setMediaSource(source, 0L)
            exo.prepare()
        }

        val allThreeArrived = awaitCondition(30_000) {
            server.requestsFor("/playlist.m3u8").isNotEmpty() &&
                server.requestsFor("/key").isNotEmpty() &&
                server.requestsFor("/seg").isNotEmpty()
        }

        // ExoPlayer must be queried on the main thread (Media3 enforces it).
        var playerError: String? = null
        instrumentation.runOnMainSync { playerError = player?.playerError?.message }

        assertTrue(
            "Media3 must fetch all three resource kinds over the production transport. " +
                "saw=${server.requests().map { it.path }} " +
                "errors=$playerError " +
                "serverFailures=${server.failures()}",
            allThreeArrived,
        )

        assertHeaders(
            label = "playlist",
            request = assertNotNullAndFirst("playlist", server.requestsFor("/playlist.m3u8")),
        )
        assertHeaders(
            label = "AES-128 key",
            request = assertNotNullAndFirst("key", server.requestsFor("/key")),
        )
        assertHeaders(
            label = "segment",
            request = assertNotNullAndFirst("segment", server.requestsFor("/seg")),
        )
    }

    // -----------------------------------------------------------------------
    // 2. The transport alone — deterministic, no player involved
    // -----------------------------------------------------------------------

    @Test
    fun theHeaderCarryingDataSourceSendsTheSameHeadersForEachResourceKind() {
        val info = streamInfo()
        val dataSourceFactory = factory.createDataSourceFactory(info)

        for ((label, path) in listOf(
            "playlist" to "/playlist.m3u8",
            "segment" to "/seg0.ts",
            "key" to "/key",
        )) {
            val before = server.requests().size
            val dataSource = dataSourceFactory.createDataSource()
            try {
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse("${server.baseUrl}$path"))
                    .build()
                dataSource.open(dataSpec)
                val buffer = ByteArray(8 * 1024)
                dataSource.read(buffer, 0, buffer.size)
            } finally {
                dataSource.close()
            }

            val request = server.requests().drop(before).firstOrNull { it.path == path }
            assertNotNull("$label request never reached the server", request)
            assertHeaders(label = label, request = requireNotNull(request))
        }
    }

    // -----------------------------------------------------------------------
    // 3. Cookie is conditional, exactly like StreamInfo.toHeaders()
    // -----------------------------------------------------------------------

    @Test
    fun noCookieHeaderIsSentWhenTheResolverProducedNoCookies() {
        // Production today: StreamInfo.cookies is always empty (Decision 32),
        // yet UA + Referer are enough for the CDN — so the transport must send
        // nothing it was not given, never an invented cookie.
        val info = streamInfo(cookies = emptyList())
        val dataSourceFactory = factory.createDataSourceFactory(info)

        val dataSource = dataSourceFactory.createDataSource()
        try {
            dataSource.open(
                DataSpec.Builder().setUri(Uri.parse("${server.baseUrl}/playlist.m3u8")).build(),
            )
            dataSource.read(ByteArray(8 * 1024), 0, 8 * 1024)
        } finally {
            dataSource.close()
        }

        val request = server.requestsFor("/playlist.m3u8").last()
        assertNotNull(request.header("User-Agent"))
        assertNotNull(request.header("Referer"))
        assertEquals(
            "an empty cookie list must never become a Cookie header",
            null,
            request.header("Cookie"),
        )
    }

    // -----------------------------------------------------------------------

    private fun assertNotNullAndFirst(label: String, requests: List<RecordingHlsServer.Recorded>) =
        requireNotNull(requests.firstOrNull()) {
            "no $label request recorded (saw=${server.requests().map { it.path }})"
        }

    private fun assertHeaders(label: String, request: RecordingHlsServer.Recorded) {
        assertEquals(
            "the $label request must carry the resolver's User-Agent",
            userAgent,
            request.header("User-Agent"),
        )
        assertEquals(
            "the $label request must carry the KWIK page Referer (11.5E-4)",
            referer,
            request.header("Referer"),
        )
        assertEquals(
            "the $label request must carry the Cookie exactly as StreamInfo built it",
            cookie,
            request.header("Cookie"),
        )
    }
}
