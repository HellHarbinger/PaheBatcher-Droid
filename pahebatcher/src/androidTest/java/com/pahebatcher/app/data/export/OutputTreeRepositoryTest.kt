package com.pahebatcher.app.data.export

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pahebatcher.app.data.config.AppConfig
import com.pahebatcher.app.data.local.PaheDatabase
import com.pahebatcher.app.testdocs.PBTestDocumentsProvider
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
 * Persisted SAF destination state —
 * validation states, grant lifecycle, and the DataStore keys, against the
 * real test DocumentsProvider and REAL persistable grants.
 *
 * Covered states of [OutputTreeRepository]:
 *  - NotConfigured (no key);
 *  - Unusable "folder permission was revoked" (configured but no grant);
 *  - Valid (grant established + tree document resolves);
 *  - Unusable after a real release (revocation, 103.4);
 *  - Unusable "folder no longer exists" (grant present, folder deleted);
 *  - setOutputTree takes the grant, persists the key, resets rename-ok;
 *  - clearOutputTree clears the key and releases the grant;
 *  - malformed configured value degrades to Unusable (never throws).
 */
@RunWith(AndroidJUnit4::class)
class OutputTreeRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: PaheDatabase
    private lateinit var config: AppConfig
    private lateinit var repository: OutputTreeRepository

    private val renameTree: Uri
        get() = PBTestDocumentsProvider.treeUri(PBTestDocumentsProvider.ROOT_RENAME)

    private val renameRootDir: File
        get() = File(context.getExternalFilesDir("pb_testdocs"), PBTestDocumentsProvider.ROOT_RENAME)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PaheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        config = AppConfig(context)
        // Hygiene: never inherit a previous class's configuration or grant.
        runBlocking {
            OutputTreeRepository(context.contentResolver, config).clearOutputTree()
        }
        SafTestSupport.releasePersistableGrant(context, renameTree)
        repository = OutputTreeRepository(context.contentResolver, config)
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
        database.close()
    }

    // ------------------------------------------------------------------
    // Validation states (92.3/103.4)
    // ------------------------------------------------------------------

    @Test
    fun freshConfig_reportsNotConfigured() = runBlocking {
        val state = repository.validate()
        assertTrue("expected NotConfigured, got $state", state is OutputTreeState.NotConfigured)
        assertTrue(
            "state() must agree with validate()",
            repository.state() is OutputTreeState.NotConfigured,
        )
    }

    @Test
    fun configuredWithoutGrant_reportsUnusableRevoked() = runBlocking {
        config.updateOutputTree(renameTree.toString())

        val state = repository.validate()
        assertTrue("expected Unusable, got $state", state is OutputTreeState.Unusable)
        assertEquals(
            "revocation reason (103.4)",
            "folder permission was revoked",
            (state as OutputTreeState.Unusable).reason,
        )
    }

    @Test
    fun grantedTree_reportsValid() = runBlocking {
        SafTestSupport.establishPersistableGrant(context, renameTree)
        config.updateOutputTree(renameTree.toString())

        val state = repository.validate()
        assertTrue(
            "granted resolvable tree must be Valid, got $state",
            state is OutputTreeState.Valid,
        )
        assertEquals(renameTree.toString(), (state as OutputTreeState.Valid).treeUri)
    }

    @Test
    fun releasedGrant_reportsUnusableRevoked() = runBlocking {
        SafTestSupport.establishPersistableGrant(context, renameTree)
        config.updateOutputTree(renameTree.toString())
        assertTrue(repository.validate() is OutputTreeState.Valid)

        // Real revocation: the key stays, the grant goes (103.4).
        SafTestSupport.releasePersistableGrant(context, renameTree)

        val state = repository.validate()
        assertTrue("revoked grant must be Unusable, got $state", state is OutputTreeState.Unusable)
        assertEquals(
            "folder permission was revoked",
            (state as OutputTreeState.Unusable).reason,
        )
        assertFalse(
            "helper must report the grant gone",
            SafTestSupport.hasPersistableGrant(context, renameTree),
        )
    }

    @Test
    fun deletedTreeFolder_reportsUnusableMissing() = runBlocking {
        // Normalize regardless of method order: the fixture root must exist
        // for the grant+Valid phase, then be removed for the missing phase.
        renameRootDir.mkdirs()
        SafTestSupport.establishPersistableGrant(context, renameTree)
        config.updateOutputTree(renameTree.toString())
        assertTrue(repository.validate() is OutputTreeState.Valid)

        assertTrue("fixture root must exist", renameRootDir.exists())
        assertTrue("fixture root must be removable", renameRootDir.deleteRecursively())
        try {
            val state = repository.validate()
            assertTrue(
                "grant present but folder deleted must be Unusable, got $state",
                state is OutputTreeState.Unusable,
            )
            assertEquals(
                "missing-folder reason (103.4)",
                "folder no longer exists",
                (state as OutputTreeState.Unusable).reason,
            )
        } finally {
            // Recreate for this class's later tests / provider hygiene.
            renameRootDir.mkdirs()
        }
    }

    @Test
    fun malformedConfiguredValue_reportsUnusableNeverThrows() = runBlocking {
        config.updateOutputTree("not-a-uri-at-all")

        val state = repository.validate()
        assertTrue("malformed value must degrade to Unusable, got $state", state is OutputTreeState.Unusable)
    }

    // ------------------------------------------------------------------
    // Persistence (92.3 picker flow, 104.4 rename-ok reset)
    // ------------------------------------------------------------------

    @Test
    fun setOutputTree_takesGrantPersistsKeyAndResetsRenameOk() = runBlocking {
        SafTestSupport.establishPersistableGrant(context, renameTree)
        // Simulate a previous rename-less tree (104.4 resets on selection).
        config.updateOutputTreeRenameOk(false)

        val state = repository.setOutputTree(renameTree.toString())

        assertTrue("setOutputTree must return the validated state, got $state",
            state is OutputTreeState.Valid)
        assertEquals(
            "tree URI persisted",
            renameTree.toString(),
            config.outputTreeUri.first(),
        )
        assertTrue(
            "selecting a tree resets output_tree_rename_ok (104.4)",
            config.outputTreeRenameOk.first(),
        )
        assertTrue(
            "the key's grant must be persisted (setOutputTree took it)",
            SafTestSupport.hasPersistableGrant(context, renameTree),
        )
    }

    @Test
    fun clearOutputTree_clearsKeyReleasesGrantAndReportsNotConfigured() = runBlocking {
        SafTestSupport.establishPersistableGrant(context, renameTree)
        repository.setOutputTree(renameTree.toString())
        assertTrue(repository.state() is OutputTreeState.Valid)

        repository.clearOutputTree()

        assertNull("tree key cleared", config.outputTreeUri.first())
        assertFalse(
            "grant released with the destination",
            SafTestSupport.hasPersistableGrant(context, renameTree),
        )
        assertTrue(
            "state back to NotConfigured",
            repository.state() is OutputTreeState.NotConfigured,
        )
        assertTrue(
            "fresh validation agrees",
            repository.validate() is OutputTreeState.NotConfigured,
        )
    }
}
