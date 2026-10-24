package com.pahebatcher.app.data.remote.validation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.data.session.AndroidCloudflareSession
import com.pahebatcher.app.data.session.AndroidSessionPersistence
import com.pahebatcher.app.data.stream.KwikResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Quick test: try the user's /f/ Kwik URL directly.
 */
@RunWith(AndroidJUnit4::class)
class KwikDirectUrlTest {

    companion object {
        private const val TAG = "KwikDirectUrl"
    }

    @Test
    fun tryUserKwikUrl() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionPersistence = AndroidSessionPersistence(context)

        val session = AndroidCloudflareSession(context, sessionPersistence)
        val sessionInfo = session.establishSession(forceRefresh = true)
        Log.i(TAG, "Session ready: ${sessionInfo.isReady}, cookies: ${sessionInfo.cookieCount}")
        assertTrue(sessionInfo.isReady)

        // User's URL
        val userUrl = "https://kwik.cx/f/vJVkgoHveKCG"
        Log.i(TAG, "=== Testing user's /f/ URL ===")
        Log.i(TAG, "URL: $userUrl")

        val resolver = KwikResolver(session)
        // 11.5E-4: resolve() now reports the playlist together with the Kwik page it came from.
        val m3u8 = resolver.resolve(userUrl)?.m3u8Url
        Log.i(TAG, "M3U8 result: $m3u8")

        if (m3u8 != null) {
            Log.i(TAG, "=== SUCCESS: /f/ URL RESOLVED ===")
            assertTrue("M3U8 should not be empty", m3u8.isNotEmpty())
            assertTrue("M3U8 should start with https://", m3u8.startsWith("https://"))
            assertTrue("M3U8 should contain .m3u8", m3u8.contains(".m3u8"))
        } else {
            Log.w(TAG, "/f/ URL also failed")
        }

        // Also try the /e/ URL our test got
        val ourUrl = "https://kwik.cx/e/Cu7zh9qq6Uyp"
        Log.i(TAG, "=== Testing /e/ URL ===")
        Log.i(TAG, "URL: $ourUrl")

        val m3u8_2 = resolver.resolve(ourUrl)?.m3u8Url
        Log.i(TAG, "M3U8 result: $m3u8_2")

        session.releaseSession()
    }
}
