package com.pahebatcher.app.data.repository

import com.pahebatcher.app.data.remote.HttpClient
import kotlinx.coroutines.CompletableDeferred

/**
 * Fake HTTP client for testing that returns pre-configured responses.
 * Supports ordered responses for the same pattern (different responses for same URL).
 */
class FakeHttpClient : HttpClient {
    private val jsonResponses = mutableMapOf<String, MutableList<String?>>()
    private val htmlResponses = mutableMapOf<String, MutableList<String?>>()
    val requestedUrls = mutableListOf<String>()

    /**
     * AQR-040: opt-in response HOLD — a test can freeze requests whose URL
     * contains [urlPattern] until it completes the returned latch, making the
     * ORDER of concurrent request completions deterministic (a stale search
     * must be shown landing after a newer one). No gate registered ⇒ every
     * request behaves exactly as before; existing tests are untouched.
     */
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Hold every URL containing [urlPattern] until the returned latch is completed. */
    fun gate(urlPattern: String): CompletableDeferred<Unit> =
        gates.getOrPut(urlPattern) { CompletableDeferred() }

    private suspend fun awaitGates(url: String) {
        for ((pattern, latch) in gates) {
            if (url.contains(pattern)) {
                latch.await()
                return
            }
        }
    }

    fun stubJson(urlPattern: String, response: String?) {
        jsonResponses.getOrPut(urlPattern) { mutableListOf() }.add(response)
    }

    fun stubHtml(urlPattern: String, response: String?) {
        htmlResponses.getOrPut(urlPattern) { mutableListOf() }.add(response)
    }

    override suspend fun fetchJson(url: String): String? {
        requestedUrls.add(url)
        awaitGates(url)
        for ((pattern, responses) in jsonResponses) {
            if (url.contains(pattern)) {
                if (responses.size > 1) {
                    // Return first response and remove it (ordered responses)
                    return responses.removeAt(0)
                }
                return responses.firstOrNull()
            }
        }
        return null
    }

    override suspend fun fetchHtml(url: String): String? {
        requestedUrls.add(url)
        awaitGates(url)
        for ((pattern, responses) in htmlResponses) {
            if (url.contains(pattern)) {
                if (responses.size > 1) {
                    return responses.removeAt(0)
                }
                return responses.firstOrNull()
            }
        }
        return null
    }
}
