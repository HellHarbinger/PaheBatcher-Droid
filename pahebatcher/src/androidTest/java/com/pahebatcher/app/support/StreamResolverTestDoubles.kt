package com.pahebatcher.app.support

import com.pahebatcher.app.domain.model.StreamInfo
import com.pahebatcher.app.domain.stream.StreamResolver

/**
 * 11.5E-7 test doubles for [StreamResolver] (androidTest ONLY — mocks are
 * permitted in tests and must never ship, AGENTS §5).
 *
 * The retry tests need to observe EXACTLY what a retry resolved — which
 * `playUrl`, quality and audio were requested — and to control failure and
 * cancellation timing, which no production resolver can be asked to do.
 */

/** One recorded `resolve()` invocation. */
data class StreamResolverCall(
    val playUrl: String,
    val quality: Int,
    val audio: String,
)

/** The "fresh" stream every happy-path retry test expects to be persisted. */
object RetryTestStream {
    const val M3U8_URL = "https://cdn.local.test/retry/fresh.m3u8"
    const val USER_AGENT = "FreshRetryUA"
    const val REFERER = "https://kwik.cx/e/fresh-retry"

    fun streamInfo(title: String = "Retry Fresh :: animepahe") = StreamInfo(
        url = M3U8_URL,
        userAgent = USER_AGENT,
        referer = REFERER,
        title = title,
    )
}

/**
 * Recording fake resolver: every `resolve()` call is captured, then [onResolve]
 * decides the outcome (return a stream, throw, or suspend on a gate).
 *
 * Thread-safe recording: retry tests may invoke it from concurrent coroutines
 * (double-retry test).
 */
class RecordStreamResolver(
    private val onResolve: suspend (StreamResolverCall) -> StreamInfo = { RetryTestStream.streamInfo() },
) : StreamResolver {

    val calls: MutableList<StreamResolverCall> =
        java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun resolve(playUrl: String, quality: Int, audio: String): StreamInfo {
        val call = StreamResolverCall(playUrl, quality, audio)
        calls.add(call)
        return onResolve(call)
    }
}
