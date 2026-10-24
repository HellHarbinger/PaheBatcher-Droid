package com.pahebatcher.app.ui.settings

import com.pahebatcher.app.data.export.OutputTreeState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The EXACT `Output location` row
 * descriptions — the pure derivation the composed row renders.
 *
 * The rename-less clause is Decision 104.4's second mandated consumer of
 * `output_tree_rename_ok`: once the export path discovered a rename-less
 * provider, every export is routed to the MediaStore default, so the row must
 * stop claiming the configured folder receives exports (the blocking review
 * finding). Destination spelled `Movies/<anime title>` per Gate A — the
 * per-anime directory structure OutputPathNames/MediaStoreExporter really
 * write (Python `sanitize(title)` parity).
 *
 * Every other state stays byte-identical to the original inline mapping —
 * this suite pins all four texts so a future edit cannot silently soften the
 * honesty of any branch.
 */
class OutputLocationDescriptionTest {

    private val tree = "content://com.pahebatcher.app.testdocs/tree/pb_rename_root"

    @Test
    fun notConfigured_namesTheMediaStoreDefault() {
        assertEquals(
            "Not set — exports go to Movies/<anime title> (system default).",
            outputLocationDescription(OutputTreeState.NotConfigured),
        )
    }

    @Test
    fun validWithRenameSupport_namesTheConfiguredFolder() {
        assertEquals(
            "Exports go to: pb_rename_root",
            outputLocationDescription(OutputTreeState.Valid(tree)),
        )
    }

    @Test
    fun validRenameLess_usesThe104_4CantBeUsedClause() {
        assertEquals(
            "This folder can't be used — exports go to Movies/<anime title>.",
            outputLocationDescription(OutputTreeState.Valid(tree, renameOk = false)),
        )
    }

    @Test
    fun unusable_surfacesTheRepositoryReasonAndTheFallback() {
        assertEquals(
            "Problem: folder permission was revoked — exports go to Movies/<anime title> " +
                "until you choose the folder again.",
            outputLocationDescription(
                OutputTreeState.Unusable(tree, "folder permission was revoked"),
            ),
        )
    }
}
