package com.pahebatcher.app.domain.download

import com.pahebatcher.app.data.hls.DownloadErrorClass
import com.pahebatcher.app.domain.stream.ResolutionFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice C — the classification seam, pinned as a TOTALLY deterministic mapping.
 *
 * These assertions exist because the whole point of [FailureCategory] is that
 * it is decided where a failure originates and never inferred afterwards from
 * message text. If a mapping moves, the user-facing reason moves with it, so
 * every branch is written out explicitly rather than asserted as a round trip.
 */
class FailureCategoryTest {

    // ---------------------------------------------------------------------
    // Download stage: DownloadErrorClass -> FailureCategory
    // ---------------------------------------------------------------------

    @Test
    fun `every download-stage class maps to its own category`() {
        assertEquals(
            FailureCategory.SEGMENT_DOWNLOAD_FAILED,
            FailureCategory.fromDownloadErrorClass(DownloadErrorClass.TRANSPORT),
        )
        assertEquals(
            FailureCategory.STREAM_EXPIRED,
            FailureCategory.fromDownloadErrorClass(DownloadErrorClass.EXPIRED),
        )
        assertEquals(
            FailureCategory.NO_CONTENT,
            FailureCategory.fromDownloadErrorClass(DownloadErrorClass.NO_CONTENT),
        )
        assertEquals(
            FailureCategory.DISK_FULL,
            FailureCategory.fromDownloadErrorClass(DownloadErrorClass.DISK_FULL),
        )
        assertEquals(
            FailureCategory.ASSEMBLY_FAILED,
            FailureCategory.fromDownloadErrorClass(DownloadErrorClass.ASSEMBLY),
        )
        assertEquals(
            FailureCategory.IN_RUN_RETRYABLE,
            FailureCategory.fromDownloadErrorClass(DownloadErrorClass.IN_RUN_RETRYABLE),
        )
    }

    @Test
    fun `NONE cannot be turned into a reason it does not have`() {
        // `NONE` means "no download-stage failure was classified" — it reaches a
        // failure branch only by a wiring mistake, and must then admit it knows
        // nothing rather than claim a cause.
        assertEquals(FailureCategory.UNKNOWN, FailureCategory.fromDownloadErrorClass(DownloadErrorClass.NONE))
    }

    // ---------------------------------------------------------------------
    // Resolution stage: ResolutionFailureCode -> FailureCategory
    // ---------------------------------------------------------------------

    @Test
    fun `AUDIO_UNAVAILABLE is the only resolution origin that becomes the audio category`() {
        assertEquals(
            FailureCategory.AUDIO_UNAVAILABLE,
            FailureCategory.fromResolutionCode(ResolutionFailureCode.AUDIO_UNAVAILABLE),
        )
    }

    @Test
    fun `every other resolution origin is honestly a plain resolution failure`() {
        // A page that could not be fetched, a page with no Kwik link, a Kwik
        // page that yielded nothing, and a failure with no assigned code are all
        // "the stream could not be resolved" — none of them is evidence about
        // any audio, and none of them is a more specific claim than that.
        listOf(
            ResolutionFailureCode.PAGE_FETCH_FAILED,
            ResolutionFailureCode.NO_KWIK_LINK,
            ResolutionFailureCode.KWIK_RESOLUTION_FAILED,
            ResolutionFailureCode.UNKNOWN,
        ).forEach { code ->
            assertEquals(
                "origin $code",
                FailureCategory.STREAM_RESOLUTION_FAILED,
                FailureCategory.fromResolutionCode(code),
            )
        }
    }

    @Test
    fun `the audio and non-audio resolution origins never collapse into each other`() {
        // The whole reason this seam exists: the two causes produce the SAME
        // sentence from `AudioSelector.unavailableMessage`, so they must stay
        // distinguishable as typed values.
        assertNotEquals(
            FailureCategory.fromResolutionCode(ResolutionFailureCode.NO_KWIK_LINK),
            FailureCategory.fromResolutionCode(ResolutionFailureCode.AUDIO_UNAVAILABLE),
        )
    }

    // ---------------------------------------------------------------------
    // Labels: what a user is actually shown
    // ---------------------------------------------------------------------

    @Test
    fun `UNKNOWN deliberately renders no label`() {
        assertNull(
            "an unattributable failure must never be dressed up as an attributed one",
            FailureCategory.UNKNOWN.label,
        )
    }

    @Test
    fun `every classifiable category carries a non-blank label`() {
        FailureCategory.entries
            .filter { it != FailureCategory.UNKNOWN }
            .forEach { category ->
                val label = category.label
                assertNotNull("label for $category", label)
                assertTrue("label for $category must not be blank", label!!.isNotBlank())
            }
    }

    @Test
    fun `no two categories share a label`() {
        // A shared label would make two different causes indistinguishable to a
        // user reading a notification, defeating the point of classifying at all.
        val labels = FailureCategory.entries.map { it.label }
        assertEquals(
            "every category needs a distinct rendered label",
            labels.size,
            labels.distinct().size,
        )
    }
}
