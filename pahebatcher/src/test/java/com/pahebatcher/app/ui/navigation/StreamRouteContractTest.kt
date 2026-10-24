package com.pahebatcher.app.ui.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Phase 17 §10 — the player route's streaming contract.
 *
 * Navigation may carry ONLY `episodeId` + `PlayerMode`: no play page URL, no
 * resolved `StreamInfo`, no title, no User-Agent, no Referer, no cookies. The
 * strongest shape assertion available without a NavHost (there is no Robolectric
 * in this toolchain — see `AnimeDetailsRouteTest`) is that the route type has
 * exactly those two components, that `mode` survives the type-safe encoding
 * navigation-compose uses for arguments, and that the default is the pre-existing
 * DOWNLOAD mode so every current caller behaves unchanged.
 */
class StreamRouteContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the route carries exactly an episode id and a playback mode`() {
        // Java reflection keeps this dependency-free (no kotlin-reflect in the
        // toolchain) while still pinning the route's exact component list.
        val fields = PlayerRoute::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()

        assertEquals(listOf("episodeId", "mode"), fields)
    }

    @Test
    fun `the default mode keeps every existing caller on local playback`() {
        val route = PlayerRoute(episodeId = "anime-session_1_jpn")

        assertEquals(PlayerMode.DOWNLOAD, route.mode)
    }

    @Test
    fun `both modes round trip through the serialization navigation uses for arguments`() {
        for (mode in PlayerMode.entries) {
            val route = PlayerRoute(episodeId = "anime-session_1_jpn", mode = mode)

            val encoded = json.encodeToString(PlayerRoute.serializer(), route)
            val decoded = json.decodeFromString(PlayerRoute.serializer(), encoded)

            assertEquals(route, decoded)
            assertEquals(mode, decoded.mode)
        }
    }

    @Test
    fun `STREAM and DOWNLOAD are distinct destinations for the same episode`() {
        val download = PlayerRoute("anime-session_1_jpn", PlayerMode.DOWNLOAD)
        val stream = PlayerRoute("anime-session_1_jpn", PlayerMode.STREAM)

        assertNotEquals(download, stream)
    }

    @Test
    fun `the mode set is exactly the two playback paths this build supports`() {
        assertEquals(
            setOf(PlayerMode.DOWNLOAD, PlayerMode.STREAM),
            PlayerMode.entries.toSet(),
        )
    }

    @Test
    fun `two different episodes are two different destinations`() {
        assertNotEquals(
            PlayerRoute("anime-session_1_jpn", PlayerMode.STREAM),
            PlayerRoute("anime-session_2_jpn", PlayerMode.STREAM),
        )
    }
}
