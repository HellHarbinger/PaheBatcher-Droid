package com.pahebatcher.app.data.local.entity

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for EpisodeDownloadEntity state constants and helper methods.
 *
 */
class EpisodeDownloadEntityTest {

    @Test
    fun stateConstants_areCorrectlyDefined() {
        assertEquals("QUEUED", EpisodeDownloadEntity.STATE_QUEUED)
        assertEquals("RESOLVING", EpisodeDownloadEntity.STATE_RESOLVING)
        assertEquals("DOWNLOADING", EpisodeDownloadEntity.STATE_DOWNLOADING)
        assertEquals("ASSEMBLING", EpisodeDownloadEntity.STATE_ASSEMBLING)
        assertEquals("COMPLETED", EpisodeDownloadEntity.STATE_COMPLETED)
        assertEquals("FAILED", EpisodeDownloadEntity.STATE_FAILED)
        assertEquals("CANCELLED", EpisodeDownloadEntity.STATE_CANCELLED)
    }

    @Test
    fun activeStates_containCorrectStates() {
        val activeStates = listOf(
            EpisodeDownloadEntity.STATE_QUEUED,
            EpisodeDownloadEntity.STATE_RESOLVING,
            EpisodeDownloadEntity.STATE_DOWNLOADING,
            EpisodeDownloadEntity.STATE_ASSEMBLING
        )
        assertEquals(4, activeStates.size)
        assertTrue(activeStates.contains(EpisodeDownloadEntity.STATE_QUEUED))
        assertTrue(activeStates.contains(EpisodeDownloadEntity.STATE_RESOLVING))
        assertTrue(activeStates.contains(EpisodeDownloadEntity.STATE_DOWNLOADING))
        assertTrue(activeStates.contains(EpisodeDownloadEntity.STATE_ASSEMBLING))
        assertFalse(activeStates.contains(EpisodeDownloadEntity.STATE_COMPLETED))
        assertFalse(activeStates.contains(EpisodeDownloadEntity.STATE_FAILED))
        assertFalse(activeStates.contains(EpisodeDownloadEntity.STATE_CANCELLED))
    }

    @Test
    fun terminalStates_containOnly_completed_failed_cancelled() {
        val terminalStates = listOf(
            EpisodeDownloadEntity.STATE_COMPLETED,
            EpisodeDownloadEntity.STATE_FAILED,
            EpisodeDownloadEntity.STATE_CANCELLED
        )
        assertEquals(3, terminalStates.size)
        assertTrue(terminalStates.contains(EpisodeDownloadEntity.STATE_COMPLETED))
        assertTrue(terminalStates.contains(EpisodeDownloadEntity.STATE_FAILED))
        assertTrue(terminalStates.contains(EpisodeDownloadEntity.STATE_CANCELLED))
        assertFalse(terminalStates.contains(EpisodeDownloadEntity.STATE_QUEUED))
        assertFalse(terminalStates.contains(EpisodeDownloadEntity.STATE_DOWNLOADING))
    }

    @Test
    fun isTerminal_returnsCorrectValues() {
        val entity = EpisodeDownloadEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            episodeNumber = 1.0f,
            episodeSession = "ep-sess-1",
            title = "Episode 1",
            fansub = "Test",
            audio = "sub",
            playUrl = "https://example.com/play",
            createdAt = 0L,
            updatedAt = 0L
        )

        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_COMPLETED).isTerminal())
        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_FAILED).isTerminal())
        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_CANCELLED).isTerminal())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_QUEUED).isTerminal())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_RESOLVING).isTerminal())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING).isTerminal())
        // PAUSED is a deliberate stop, not a finished episode — it must stay
        // non-terminal so reconciliation and the session gates keep it in scope.
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_PAUSED).isTerminal())
    }

    @Test
    fun canResume_returnsCorrectValues() {
        val entity = EpisodeDownloadEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            episodeNumber = 1.0f,
            episodeSession = "ep-sess-1",
            title = "Episode 1",
            fansub = "Test",
            audio = "sub",
            playUrl = "https://example.com/play",
            createdAt = 0L,
            updatedAt = 0L
        )

        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_FAILED).canResume())
        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_QUEUED).canResume())
        // SPEC §20 Resume: a paused row is exactly the row that must be resumable.
        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_PAUSED).canResume())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_COMPLETED).canResume())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING).canResume())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_CANCELLED).canResume())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_ASSEMBLING).canResume())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_RESOLVING).canResume())
    }

    @Test
    fun isPausable_coversExactlyThePausableStates() {
        val entity = EpisodeDownloadEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            episodeNumber = 1.0f,
            episodeSession = "ep-sess-1",
            title = "Episode 1",
            fansub = "Test",
            audio = "sub",
            playUrl = "https://example.com/play",
            createdAt = 0L,
            updatedAt = 0L
        )

        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_QUEUED).isPausable())
        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_RESOLVING).isPausable())
        assertTrue(entity.copy(state = EpisodeDownloadEntity.STATE_DOWNLOADING).isPausable())
        // ASSEMBLING: muxing cannot stop halfway without risking a partial output.
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_ASSEMBLING).isPausable())
        // Terminal rows and an already-paused row have nothing to pause.
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_COMPLETED).isPausable())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_FAILED).isPausable())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_CANCELLED).isPausable())
        assertFalse(entity.copy(state = EpisodeDownloadEntity.STATE_PAUSED).isPausable())
    }

    @Test
    fun progressPercent_withZeroTotal_returnsZero() {
        val entity = EpisodeDownloadEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            episodeNumber = 1.0f,
            episodeSession = "ep-sess-1",
            title = "Episode 1",
            fansub = "Test",
            audio = "sub",
            playUrl = "https://example.com/play",
            createdAt = 0L,
            updatedAt = 0L,
            totalSegments = 0,
            completedSegments = 0
        )
        assertEquals(0, entity.progressPercent())
    }

    @Test
    fun progressPercent_calculatesCorrectly() {
        val entity = EpisodeDownloadEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            episodeNumber = 1.0f,
            episodeSession = "ep-sess-1",
            title = "Episode 1",
            fansub = "Test",
            audio = "sub",
            playUrl = "https://example.com/play",
            createdAt = 0L,
            updatedAt = 0L,
            totalSegments = 100,
            completedSegments = 50
        )
        assertEquals(50, entity.progressPercent())
    }

    @Test
    fun progressPercent_clampsTo100() {
        val entity = EpisodeDownloadEntity(
            episodeId = "ep-1",
            sessionId = "sess-1",
            episodeNumber = 1.0f,
            episodeSession = "ep-sess-1",
            title = "Episode 1",
            fansub = "Test",
            audio = "sub",
            playUrl = "https://example.com/play",
            createdAt = 0L,
            updatedAt = 0L,
            totalSegments = 10,
            completedSegments = 15 // More than total
        )
        assertEquals(100, entity.progressPercent())
    }

    @Test
    fun activeStatesCount_forDownloadWorker_logic() {
        // Verify the logic that DownloadWorker uses to check active count
        val downloading = EpisodeDownloadEntity.STATE_DOWNLOADING
        val assembling = EpisodeDownloadEntity.STATE_ASSEMBLING
        val queued = EpisodeDownloadEntity.STATE_QUEUED
        val completed = EpisodeDownloadEntity.STATE_COMPLETED
        val failed = EpisodeDownloadEntity.STATE_FAILED
        val cancelled = EpisodeDownloadEntity.STATE_CANCELLED

        val isActive = { state: String ->
            state == downloading || state == assembling
        }

        assertTrue(isActive(downloading))
        assertTrue(isActive(assembling))
        assertFalse(isActive(queued))
        assertFalse(isActive(completed))
        assertFalse(isActive(failed))
        assertFalse(isActive(cancelled))
    }
}
