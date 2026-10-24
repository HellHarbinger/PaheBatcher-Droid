package com.pahebatcher.app.data.session

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.data.remote.parser.AnimePaheParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Extraction-seam regression test for the search JSON content fix.
 *
 * The production JSON capture runs inside a real Android WebView, so it cannot be
 * exercised by JVM unit tests. This test drives the exact production capture scripts
 * ([AndroidCloudflareSession.JSON_BODY_EXTRACTION_JS] and [AndroidCloudflareSession.HTML_CAPTURE_JS])
 * plus the production decoder ([AndroidCloudflareSession.decodeJsStringLiteral]) against
 * a real WebView document — no network, no Cloudflare, no search request.
 *
 * What it proves:
 * - a JSON request's extraction returns the exact raw response body (not DOM HTML);
 * - that raw body parses into non-empty search results;
 * - the DOM serialization (the pre-fix content) differs and remains unusable as JSON;
 * - HTML extraction still returns the full DOM (shared HTML path preserved);
 * - the JS-literal decoder handles null/escape edge cases.
 */
@RunWith(AndroidJUnit4::class)
class WebViewJsonExtractionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView

    /** Raw JSON body with HTML-significant characters, as a real API can return. */
    private val searchJson =
        """{"data":[{"session":"s1","title":"Tom & Jerry <TV>","type":"TV","year":2024,"episodes":12,"score":"8.5"}],"total":1}"""

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext.applicationContext)
            webView.settings.javaScriptEnabled = true
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { webView.destroy() }
    }

    private fun loadAndWait(mimeType: String, content: String) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.countDown()
                }
            }
            webView.loadDataWithBaseURL("https://animepahe.pw/", content, mimeType, "utf-8", null)
        }
        assertTrue("page load timed out", loaded.await(15, TimeUnit.SECONDS))
    }

    private fun evaluate(script: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                result = value
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun evaluateDecoded(script: String): String =
        AndroidCloudflareSession.decodeJsStringLiteral(evaluate(script))

    @Test
    fun jsonExtractionReturnsRawResponseBodyNotDomSerialization() {
        loadAndWait("application/json", searchJson)

        val body = evaluateDecoded(AndroidCloudflareSession.JSON_BODY_EXTRACTION_JS)

        assertEquals(
            "extraction must return the exact response body text",
            searchJson,
            body,
        )
        val parsed = AnimePaheParser.parseSearchResults(body)
        assertEquals("raw JSON body must parse into search rows", 1, parsed.size)
        assertEquals("Tom & Jerry <TV>", parsed[0]["title"])

        // The defect mechanism: DOM serialization is HTML and unusable as JSON.
        val dom = evaluateDecoded(AndroidCloudflareSession.HTML_CAPTURE_JS)
        assertNotEquals(
            "DOM serialization must differ from the raw response body",
            searchJson,
            dom,
        )
        assertTrue("DOM serialization is HTML-wrapped", dom.contains("<html"))
        assertTrue(
            "parser must reject the DOM serialization (pre-fix symptom preserved)",
            AnimePaheParser.parseSearchResults(dom).isEmpty(),
        )
    }

    @Test
    fun htmlExtractionStillReturnsFullDom() {
        loadAndWait("text/html", "<html><body><h1>Play</h1></body></html>")

        val html = evaluateDecoded(AndroidCloudflareSession.HTML_CAPTURE_JS)

        assertTrue("full HTML document expected", html.contains("<html"))
        assertTrue("page body preserved", html.contains("<h1>Play</h1>"))
        assertFalse("must not be an empty capture", html.isEmpty())
    }

    @Test
    fun decoderHandlesNullAndEscapedLiterals() {
        assertEquals("", AndroidCloudflareSession.decodeJsStringLiteral(null))
        assertEquals("", AndroidCloudflareSession.decodeJsStringLiteral("null"))
        // JS string literal for the body a<NL>b
        assertEquals("a\nb", AndroidCloudflareSession.decodeJsStringLiteral("\"a\\nb\""))
        // JS string literal with escaped quote and backslash
        assertEquals(
            "a\"b\\c",
            AndroidCloudflareSession.decodeJsStringLiteral("\"a\\\"b\\\\c\""),
        )
        // Unicode-escaped HTML-significant character
        assertEquals("<", AndroidCloudflareSession.decodeJsStringLiteral("\"\\u003C\""))
        // Unescaped HTML-significant characters round-trip untouched
        assertEquals(
            "Tom & Jerry <TV>",
            AndroidCloudflareSession.decodeJsStringLiteral("\"Tom & Jerry <TV>\""),
        )
    }
}
