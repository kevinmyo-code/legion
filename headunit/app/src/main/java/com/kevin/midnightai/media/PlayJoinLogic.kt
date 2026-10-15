package com.kevin.midnightai.media

/**
 * Decides when a repeated `play_music` is the SAME request as one already in flight (F7,
 * 2026-10-15). `play_music` answers "still lining up" when a play outlives the tool's time budget;
 * the old line told the driver to "ask me again", and the re-ask began a NEW play that superseded
 * and silenced the first one ([PlayRequests]). A repeat of the same request now joins the play in
 * flight instead of restarting it. Pure, so the matching rule is a JVM test.
 */
object PlayJoinLogic {

    /**
     * A key equal for requests the driver would call the same: case, runs of whitespace and edge
     * punctuation do not matter; the kind, the artist and an explicit URI do. A different artist or
     * type is a different request.
     */
    fun key(query: String, type: String, artist: String?, knownUri: String?): String {
        fun norm(s: String?): String =
            s.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return listOf(norm(type), norm(query), norm(artist), knownUri.orEmpty().trim()).joinToString("|")
    }
}
