package com.pahebatcher.app.data.stream

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.pahebatcher.app.domain.model.StreamInfo
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds the ONE Media3 media source used for direct HLS streaming.
 *
 * Responsibilities (and nothing else):
 *
 * 1. own a single OkHttp transport whose timeouts/redirect policy MIRROR the
 *    proven downloader configuration in [com.pahebatcher.app.data.hls.OkHttpHlsClient]
 *    (connect 15 s, read 60 s, write 60 s, follow redirects, follow SSL
 *    redirects, retry on connection failure) — the same values the download
 *    engine has already exercised against the same CDN;
 * 2. apply [StreamInfo.toHeaders] as the DataSource's default request
 *    properties, so `User-Agent`, `Referer` and (only when non-empty) `Cookie`
 *    are sent on EVERY request this source issues — the playlist, the segments
 *    and the AES-128 key all go through the same [DataSource.Factory];
 * 3. wrap that transport in an [HlsMediaSource] for [StreamInfo.url].
 *
 * The source is created PER STREAM and handed to `player.setMediaSource(...)`.
 * The global `ExoPlayer.Builder(...).setMediaSourceFactory(...)` is deliberately
 * untouched: local MP4 / MediaStore playback keeps its existing
 * `setMediaItems(...)` path and must never be affected by this class.
 *
 * This class is annotated [UnstableApi] rather than opting in silently because
 * it exposes Media3's unstable `MediaSource` type — callers (the player slice)
 * already carry their own `@OptIn`.
 *
 * No logging happens here: neither the URL, the headers, nor the cookie may ever
 * reach Logcat or the on-screen diagnostics Console (AGENTS §10, AppLog §M7).
 */
@UnstableApi
class HlsPlaybackSourceFactory {

    /**
     * One process-wide OkHttp client: OkHttp is thread-safe and its connection
     * pool is meant to be shared, so streaming reuses pooled connections across
     * player entries instead of opening a new pool per playback (AGENTS §34).
     *
     * Values are copied from `OkHttpHlsClient` — do not tune them here.
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * The OkHttp-backed Media3 [DataSource] factory carrying [info]'s headers.
     * Exposed separately from [create] so header behaviour can be asserted
     * against a real wire request without building a player.
     */
    fun createDataSourceFactory(info: StreamInfo): DataSource.Factory =
        OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(info.toHeaders())

    /**
     * The `HlsMediaSource` that plays [info.url] with the header-carrying
     * transport.
     *
     * The [MediaItem] is built HERE (never by the caller) so the source's URI is
     * always exactly [StreamInfo.url] — and `mediaId` is the episode id, which is
     * what keeps playback-position persistence keyed by the SAME id the local
     * path uses (§14: one position key, never a second one).
     *
     * @param mediaId the episode id (position key + `Player.currentMediaItem`).
     * @param title the display title rendered by the player overlay.
     */
    fun create(info: StreamInfo, mediaId: String, title: String): HlsMediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(info.url)
            .setMediaId(mediaId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        return HlsMediaSource.Factory(createDataSourceFactory(info))
            .createMediaSource(mediaItem)
    }
}
