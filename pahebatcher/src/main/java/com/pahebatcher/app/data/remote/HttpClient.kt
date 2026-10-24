package com.pahebatcher.app.data.remote

/**
 * HTTP client interface for AnimePahe API requests.
 * Each method returns the response body, or null on failure.
 * 
 * The production implementation routes requests through the WebView session
 * to satisfy the Cloudflare challenge before reading the body.
 */
interface HttpClient {
    /**
     * Fetch JSON content from URL.
     * @param url The URL to fetch
     * @return JSON string or null on failure
     */
    suspend fun fetchJson(url: String): String?

    /**
     * Fetch HTML content from URL.
     * @param url The URL to fetch
     * @return HTML string or null on failure
     */
    suspend fun fetchHtml(url: String): String?
}