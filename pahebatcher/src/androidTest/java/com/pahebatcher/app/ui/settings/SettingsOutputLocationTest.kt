package com.pahebatcher.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.export.OutputTreeRepository
import com.pahebatcher.app.data.export.OutputTreeState
import com.pahebatcher.app.data.export.SafTestSupport
import com.pahebatcher.app.testdocs.PBTestDocumentsProvider
import com.pahebatcher.app.ui.awaitCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The `Output location` row's ViewModel projection
 * over the REAL [OutputTreeRepository] — real persistable grants against the
 * test DocumentsProvider, the real DataStore key, and the real validation
 * reasons. The system folder picker itself cannot be driven from tests (like
 * the permission dialog), so the row's picker CONTRACT is verified where it is
 * observable: the picker result arrives as a tree URI string and every state
 * transition the row renders is projected here.
 *
 * Covered:
 *  - without a repository: every action is a no-op, state stays NotConfigured;
 *  - fresh configuration → NotConfigured (MediaStore default);
 *  - a picked tree with a REAL grant → Valid, key persisted, grant held;
 *  - a real grant revocation → Unusable with the repository's exact reason;
 *  - "Default" → NotConfigured, key cleared, grant released;
 *  - a refused take (no grant offer) → honest Unusable, nothing persisted,
 *    scope never killed (the VM guards the first production call of
 *    `setOutputTree`, whose take is unguarded);
 *  - rename-less discovery (104.4) → Valid with `renameOk=false`, rendered
 *    as the mandated "can't be used" clause, live while composed and across
 *    a refresh.
 */
@RunWith(AndroidJUnit4::class)
class SettingsOutputLocationTest {

    private lateinit var context: Context
    private lateinit var appConfig: AppConfig
    private lateinit var repository: OutputTreeRepository

    private val renameTree
        get() = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_RENAME)

    private val renameRootDir: File
        get() = File(context.getExternalFilesDir("pb_testdocs"), PBTestDocumentsProvider.ROOT_RENAME)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        appConfig = AppConfig(context)
        repository = OutputTreeRepository(context.contentResolver, appConfig)
        // Fixture root must exist for the Valid phases (mirrors
        // OutputTreeRepositoryTest's normalization).
        if (!renameRootDir.exists()) renameRootDir.mkdirs()
        // Hygiene: never inherit a previous class's configuration or grant.
        runBlocking { repository.clearOutputTree() }
        SafTestSupport.releasePersistableGrant(context, renameTree)
    }

    @After
    fun teardown() {
        runBlocking {
            try {
                repository.clearOutputTree()
            } catch (_: Exception) {
            }
        }
        SafTestSupport.releasePersistableGrant(context, renameTree)
        if (!renameRootDir.exists()) renameRootDir.mkdirs()
    }

    // ------------------------------------------------------------------
    // Nullable-repository contract (directly-constructed test VMs)
    // ------------------------------------------------------------------

    @Test
    fun withoutRepository_staysNotConfiguredAndActionsAreNoOps() {
        val vm = SettingsViewModel(appConfig = appConfig)

        vm.onOutputTreePicked(renameTree.toString())
        vm.useDefaultOutputLocation()
        vm.refreshOutputLocation()
        Thread.sleep(300)

        assertTrue(
            "state must stay NotConfigured without a repository",
            vm.outputLocationState.value is OutputTreeState.NotConfigured,
        )
        runBlocking {
            assertNull(
                "no repository → no persistence side effect",
                appConfig.outputTreeUri.first(),
            )
        }
    }

    // ------------------------------------------------------------------
    // Picker-flow states (92 picker contract, 103.4 validation reasons)
    // ------------------------------------------------------------------

    @Test
    fun freshConfiguration_projectsNotConfigured() {
        val vm = SettingsViewModel(appConfig = appConfig, outputTreeRepository = repository)

        assertTrue(
            "unset destination must project NotConfigured, got ${vm.outputLocationState.value}",
            vm.outputLocationState.value is OutputTreeState.NotConfigured,
        )
        runBlocking {
            assertNull("the key must start unset", appConfig.outputTreeUri.first())
            assertTrue(
                "fresh validation agrees",
                repository.validate() is OutputTreeState.NotConfigured,
            )
        }
    }

    @Test
    fun pickedTreeWithRealGrant_projectsValidAndPersists() {
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val vm = SettingsViewModel(appConfig = appConfig, outputTreeRepository = repository)

        // The picker result arrives here as a tree URI string (the screen's
        // contract) — the SAME call the OpenDocumentTree callback makes.
        vm.onOutputTreePicked(renameTree.toString())
        awaitCondition("Valid after the picker result") {
            vm.outputLocationState.value is OutputTreeState.Valid
        }

        val state = vm.outputLocationState.value as OutputTreeState.Valid
        assertEquals("the Valid state carries the picked tree", renameTree.toString(), state.treeUri)
        runBlocking {
            assertEquals(
                "output_tree_uri persisted (92.3)",
                renameTree.toString(),
                appConfig.outputTreeUri.first(),
            )
        }
        assertTrue(
            "the grant the row's pick established must be persisted",
            SafTestSupport.hasPersistableGrant(context, renameTree),
        )
    }

    @Test
    fun revokedFolder_projectsTheRealReasonOnRefresh() {
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val vm = SettingsViewModel(appConfig = appConfig, outputTreeRepository = repository)
        vm.onOutputTreePicked(renameTree.toString())
        awaitCondition("Valid first") {
            vm.outputLocationState.value is OutputTreeState.Valid
        }

        // Real revocation (103.4): the key stays, the grant goes — the resume
        // refresh must report the repository's exact reason, never a guess.
        SafTestSupport.releasePersistableGrant(context, renameTree)
        vm.refreshOutputLocation()
        awaitCondition("Unusable after the real revocation") {
            vm.outputLocationState.value is OutputTreeState.Unusable
        }

        val state = vm.outputLocationState.value as OutputTreeState.Unusable
        assertEquals("folder permission was revoked", state.reason)
        assertEquals("the Unusable state keeps the configured tree", renameTree.toString(), state.treeUri)
    }

    @Test
    fun useDefault_revertsToNotConfiguredAndReleasesTheGrant() {
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val vm = SettingsViewModel(appConfig = appConfig, outputTreeRepository = repository)
        vm.onOutputTreePicked(renameTree.toString())
        awaitCondition("Valid first") {
            vm.outputLocationState.value is OutputTreeState.Valid
        }

        vm.useDefaultOutputLocation()
        awaitCondition("NotConfigured after the revert") {
            vm.outputLocationState.value is OutputTreeState.NotConfigured
        }

        runBlocking {
            assertNull("the key must be cleared", appConfig.outputTreeUri.first())
            assertTrue(
                "fresh validation agrees",
                repository.validate() is OutputTreeState.NotConfigured,
            )
        }
        assertFalse(
            "the persistable grant must be released with the destination",
            SafTestSupport.hasPersistableGrant(context, renameTree),
        )
    }

    @Test
    fun pickWithoutGrant_projectsUnusableAndNeverCrashes() {
        // No picker offer, no persisted grant → takePersistableUriPermission
        // refuses with SecurityException. The VM guards the FIRST production
        // call of setOutputTree (whose take is unguarded) so the row shows an
        // honest reason instead of killing the viewModelScope.
        val vm = SettingsViewModel(appConfig = appConfig, outputTreeRepository = repository)

        vm.onOutputTreePicked(renameTree.toString())
        awaitCondition("Unusable after the refused take") {
            vm.outputLocationState.value is OutputTreeState.Unusable
        }

        val state = vm.outputLocationState.value as OutputTreeState.Unusable
        assertEquals("folder access denied", state.reason)
        runBlocking {
            assertNull(
                "92.3: take first — nothing persisted when the take failed",
                appConfig.outputTreeUri.first(),
            )
        }
        // The next projection (a lifecycle resume) lands back on the truth:
        // nothing IS configured.
        vm.refreshOutputLocation()
        awaitCondition("NotConfigured on the next refresh") {
            vm.outputLocationState.value is OutputTreeState.NotConfigured
        }
    }

    // ------------------------------------------------------------------
    // DECISIONS 104.4 — the Settings consumer of `output_tree_rename_ok`
    // ------------------------------------------------------------------

    @Test
    fun renameLessDiscovery_projectsTheCantBeUsedClauseAndSurvivesRefresh() {
        // The export path discovered a rename-less provider and persisted
        // `output_tree_rename_ok = false` — exactly what SafExporter writes
        // (SafExporterTest's renameUnsupported case). The tree itself stays
        // Valid, but every export is now routed to the MediaStore default:
        // the Settings state must stop claiming the folder receives exports,
        // both for the live in-session flip AND across a lifecycle-resume
        // refresh (the row's two real observation points).
        SafTestSupport.establishPersistableGrant(context, renameTree)
        val vm = SettingsViewModel(appConfig = appConfig, outputTreeRepository = repository)
        vm.onOutputTreePicked(renameTree.toString())
        awaitCondition("Valid first (renameOk defaults true after a pick)") {
            vm.outputLocationState.value is OutputTreeState.Valid
        }

        runBlocking { appConfig.updateOutputTreeRenameOk(false) }

        awaitCondition("the flag flip re-projected while the screen stays composed") {
            val state = vm.outputLocationState.value
            state is OutputTreeState.Valid && !state.renameOk
        }
        assertEquals(
            "the row must render 104.4's clause, not the destination claim",
            "This folder can't be used — exports go to Movies/<anime title>.",
            outputLocationDescription(vm.outputLocationState.value),
        )

        // Fresh validate (resume refresh) must carry the flag too — validate()
        // is the projection's state source and reads it on every call.
        vm.refreshOutputLocation()
        awaitCondition("refresh keeps the rename-less projection") {
            val state = vm.outputLocationState.value
            state is OutputTreeState.Valid && !state.renameOk
        }
        assertEquals(
            "still the same tree the grant covers",
            renameTree.toString(),
            (vm.outputLocationState.value as OutputTreeState.Valid).treeUri,
        )
    }
}
