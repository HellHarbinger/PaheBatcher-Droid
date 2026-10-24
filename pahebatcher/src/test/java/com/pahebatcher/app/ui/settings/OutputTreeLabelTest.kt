package com.pahebatcher.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [outputTreeLabel] — the `Output location` row's
 * folder identity. It is pure string math (deliberately no `android.net.Uri`,
 * no IPC), so the exact rendering the Settings row shows is verified here on
 * the JVM.
 *
 * Real shapes covered: the external-storage tree document ID the system picker
 * produces (`primary%3APaheBatcher` → `primary:PaheBatcher`, the SAME decoded
 * form the picker itself displays), a path containing an encoded separator,
 * a tree URI with trailing document segments, and malformed input degrading to
 * the verbatim value (the row's Unusable reason explains the rest — the label
 * never invents a friendly name).
 */
class OutputTreeLabelTest {

    @Test
    fun `decodes the external storage tree document id`() {
        assertEquals(
            "primary:PaheBatcher",
            outputTreeLabel(
                "content://com.android.externalstorage.documents/tree/primary%3APaheBatcher",
            ),
        )
    }

    @Test
    fun `decodes an encoded path separator inside the tree id`() {
        // A folder path below `primary:` percent-encodes "/" as %2F inside the
        // tree document ID — decoding must not cut the id at that separator.
        assertEquals(
            "primary:Anime/Exports",
            outputTreeLabel(
                "content://com.android.externalstorage.documents/tree/primary%3AAnime%2FExports",
            ),
        )
    }

    @Test
    fun `ignores trailing document segments after the tree segment`() {
        assertEquals(
            "primary:X",
            outputTreeLabel("content://authority/tree/primary%3AX/documents/child"),
        )
    }

    @Test
    fun `malformed uri without a tree segment returns verbatim`() {
        assertEquals("not-a-uri-at-all", outputTreeLabel("not-a-uri-at-all"))
        assertEquals("", outputTreeLabel(""))
    }

    @Test
    fun `illegal percent encoding falls back to the encoded id`() {
        // URLDecoder throws on "%zz" — the label degrades to the encoded form
        // instead of propagating (the row must never crash on bad input).
        assertEquals("%zz", outputTreeLabel("content://authority/tree/%zz"))
    }
}
