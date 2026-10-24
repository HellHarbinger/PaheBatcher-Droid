package com.pahebatcher.app.ui.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 11.5E-2 — the anime details destination is an ID-only route (PHASE11_
 * ARCHITECTURE_V2 §10.3), so its `session` must survive the type-safe
 * kotlinx-serialization encoding navigation-compose 2.10.1 uses for route
 * arguments, and two different sessions must be two different destinations.
 *
 * The UI half of the contract (tapping a result hands its OWN session to this
 * destination) is SearchImeTest#tappingAResultHandsItsSessionToTheAnimeDetailsDestination
 * plus the device verification of 11.5E-2 — a JVM unit test cannot compose a
 * NavHost (no Robolectric in the toolchain, and adding one would be a new
 * dependency this slice is forbidden to introduce).
 */
class AnimeDetailsRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `route carries its session through the serialization navigation uses for arguments`() {
        val route = AnimeDetailsRoute(session = "sess-abc")

        val encoded = json.encodeToString(AnimeDetailsRoute.serializer(), route)
        val decoded = json.decodeFromString(AnimeDetailsRoute.serializer(), encoded)

        assertEquals(route, decoded)
        assertEquals("sess-abc", decoded.session)
    }

    @Test
    fun `two different sessions are two different destinations`() {
        assertNotEquals(
            AnimeDetailsRoute(session = "sess-a"),
            AnimeDetailsRoute(session = "sess-b"),
        )
    }
}
