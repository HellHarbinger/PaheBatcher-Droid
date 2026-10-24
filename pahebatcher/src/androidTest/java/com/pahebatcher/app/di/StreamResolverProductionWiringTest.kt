package com.pahebatcher.app.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pahebatcher.app.PaheBatcherApplication
import com.pahebatcher.app.data.stream.KwikResolver
import com.pahebatcher.app.data.stream.StreamResolverImpl
import com.pahebatcher.app.ui.details.AnimeDetailsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 11.5E-3 — Test F: production wiring of the stream resolver.
 *
 * Asserts the REAL dependency graph the app runs on (the very [PaheBatcherApplication]
 * instance's [AppContainer] — the same `AppContainer(this)` the application builds),
 * never a hand-constructed fake:
 *
 * 1. the composition root constructs the EXISTING [StreamResolverImpl] over the
 *    real session chain (no fake resolver and no duplicate resolver in production);
 * 2. [AppViewModelFactory] — the only ViewModel construction path the NavHost
 *    uses — builds [AnimeDetailsViewModel] from that same container, so the
 *    details entry receives the container-bound resolver.
 *
 * Construction performs no network and no WebView creation (the WebView is created
 * lazily inside session establishment), so this is deterministic. The resolver
 * actually EXECUTING through that chain is verified on the physical device
 * (Logcat `StreamResolver` lines for the tapped episode's play page), not here.
 */
@RunWith(AndroidJUnit4::class)
class StreamResolverProductionWiringTest {

    @Test
    fun productionContainerBuildsTheRealStreamResolverForTheDetailsViewModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext

        assertTrue(
            "the target application must be the production composition root, was ${application::class.java.name}",
            application is PaheBatcherApplication,
        )
        val container = (application as PaheBatcherApplication).appContainer

        // 1. The production binding is the EXISTING implementation.
        val resolver = container.streamResolver
        assertTrue(
            "production must bind the real StreamResolverImpl, found ${resolver::class.java.name}",
            resolver is StreamResolverImpl,
        )
        assertEquals(
            "the Kwik step must be the production class itself, not a test double",
            KwikResolver::class.java,
            container.kwikResolver::class.java,
        )

        // 2. …and it is reachable through the production factory path.
        val viewModel = AppViewModelFactory(container).create(AnimeDetailsViewModel::class.java)
        assertNotNull(
            "the anime details entry must be constructible from the production container",
            viewModel,
        )

        // 3. …wired to EXACTLY that container-bound instance (not a stale field,
        //    a different binding or a test double).
        val declaredField = AnimeDetailsViewModel::class.java
            .getDeclaredField("streamResolver")
            .apply { isAccessible = true }
        assertSame(
            "the details ViewModel must receive the container's own resolver instance",
            resolver,
            declaredField.get(viewModel),
        )
    }
}
