package com.pahebatcher.app.data.stream

import com.pahebatcher.app.domain.model.ResolutionEntry

/**
 * Quality selection over the parsed play-page sources.
 *
 * One rule for BOTH page shapes (menu and menu-less) — the approved audio
 * architecture (SPEC §22 "Audio selection (per episode)"):
 *
 * 1. **exact audio filter** — keep only entries whose audio equals the
 *    requested audio, compared as the open-set string it is. There is NO
 *    filter-fallback: if nothing matches, the result is `null` and the caller
 *    fails explicitly (the reference's `if not filtered: filtered = entries`
 *    silent substitution, kwik.py:230-232, is deliberately removed);
 * 2. **de-duplication** — source identity is `(audio, resolution, kwikUrl)`;
 *    after the exact filter the audio is fixed, so within a resolution bucket
 *    the FIRST document occurrence wins (Python's per-resolution
 *    `setdefault`/no-replace behavior, kwik.py:233-239), and `fansub` is
 *    descriptive metadata that never affects identity;
 * 3. **quality rule** — best resolution ≤ requested, else the lowest
 *    available (Python kwik.py:249-251).
 *
 * The menu-less branch differs only in how the PARSER labels audio (the
 * URL-label heuristic in [PlayPageParser.parseFallbackEntries]) — selection
 * itself is identical, so the reference's branch-specific selection rules
 * collapse into this one.
 */
object QualitySelector {

    /**
     * Select the best resolution entry for [requestedAudio] at [requestedQuality].
     *
     * @param entries Parsed resolution entries from the play page
     * @param requestedQuality Target quality in pixels (e.g., 1080, 720, 360)
     * @param requestedAudio The exact audio variant requested (open-set string)
     * @return Selected ResolutionEntry, or `null` when no entry offers
     *   [requestedAudio] (the caller reports an explicit failure — never a
     *   different audio)
     */
    fun select(
        entries: List<ResolutionEntry>,
        requestedQuality: Int,
        requestedAudio: String
    ): ResolutionEntry? {
        if (entries.isEmpty()) return null

        // Step 1: exact audio filter — no fallback to other audio values.
        val filtered = entries.filter { it.audio == requestedAudio }
        if (filtered.isEmpty()) return null

        // Step 2: de-duplicate per resolution — first document occurrence of
        // (audio, resolution, …) wins; the audio is already fixed by the filter.
        val qualityMap = LinkedHashMap<Int, ResolutionEntry>()
        for (entry in filtered) {
            if (!qualityMap.containsKey(entry.resolution)) {
                qualityMap[entry.resolution] = entry
            }
        }

        // Step 3: best resolution <= requested, else the lowest available.
        val sortedQualities = qualityMap.keys.sortedDescending()
        val chosenQuality = sortedQualities.firstOrNull { it <= requestedQuality }
            ?: sortedQualities.last()

        return qualityMap[chosenQuality]
    }
}
