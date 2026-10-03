package com.kevin.legion.media

import org.json.JSONObject

/**
 * The acceptance check between what was ASKED for and what Spotify's search handed back.
 *
 * Exists because `search()` used to take the most popular of ten hits with no check against the
 * query at all: "Fukk a Interview by Future" played 2Pac, and `play_music` reported success
 * (conversation_audit, 2026-10-01). A search engine always returns something; that is not the same
 * as returning the thing. Anything that does not clear this gate is [SpotifyWebApi.SearchOutcome.NoMatch],
 * and the tool says in words that nothing played (CLAUDE.md §7 outcome-verb rule).
 *
 * Pure and JVM-testable on purpose: no Android, no network.
 *
 * Rules (thresholds are choices, covered by SpotifyMatchTest):
 *  - Normalisation: lowercase; drop "feat./ft./with" tails, parentheticals/brackets and
 *    " - remastered / - live / - radio edit"-style dash suffixes from CANDIDATE titles; drop
 *    punctuation except `*`; collapse whitespace.
 *  - Censored spellings: a token compares equal when, after folding `ck`->`kk` and `c`->`k`, the
 *    two have the same length and every position is equal or a `*` on either side. So `fukk`,
 *    `f*kk`, `f**k` and `fuck` are all one word, while `bike` and `bake` stay different.
 *  - Title match: equal; OR one token sequence contained whole in the other, provided the shorter
 *    has at least [MIN_CONTAINED_TOKENS] tokens (a lone "up" must not match "keep ya head up");
 *    OR matched tokens / max(token counts) >= [TOKEN_OVERLAP_THRESHOLD].
 *    With a confirmed artist the containment minimum drops to one token.
 *  - Artist match (when an artist is known): some candidate artist's tokens contain the wanted
 *    artist's tokens as a whole sequence, or vice versa, after normalisation.
 */
internal object SpotifyMatch {

    const val TOKEN_OVERLAP_THRESHOLD = 0.6
    const val MIN_CONTAINED_TOKENS = 2

    /** What a spoken request resolves to once an optional embedded "X by Y" is considered. */
    data class Wanted(val title: String, val artist: String?)

    // ---------------------------------------------------------------- normalisation

    private val FEAT_TAIL = Regex("""\s+[(\[]?\s*(feat\.?|ft\.?|featuring|with)\s+.*$""")
    private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
    private val DASH_SUFFIX = Regex("""\s+-\s+.*$""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}*\s]""")
    private val SPACES = Regex("""\s+""")

    /** Lowercase, punctuation out (`*` stays: it is a censor mark), whitespace collapsed. */
    fun normalize(s: String): String =
        NON_WORD.replace(s.lowercase().replace('&', ' ').replace("'", ""), " ").let { SPACES.replace(it, " ").trim() }

    /** [normalize] after shedding the decoration a streaming catalogue adds to a title. */
    fun normalizeCandidateTitle(title: String): String {
        val lower = title.lowercase()
        val noBrackets = BRACKETS.replace(lower, " ")
        val noDash = DASH_SUFFIX.replace(noBrackets, "")
        val noFeat = FEAT_TAIL.replace(noDash, "")
        return normalize(noFeat)
    }

    private fun fold(token: String): String = token.replace("ck", "kk").replace('c', 'k')

    internal fun tokensMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val x = fold(a)
        val y = fold(b)
        if (x.length != y.length) return false
        return x.indices.all { x[it] == y[it] || x[it] == '*' || y[it] == '*' }
    }

    private fun tokens(normalized: String): List<String> =
        if (normalized.isEmpty()) emptyList() else normalized.split(' ')

    private fun sequencesEqual(a: List<String>, b: List<String>) =
        a.size == b.size && a.indices.all { tokensMatch(a[it], b[it]) }

    private fun containsSequence(long: List<String>, short: List<String>): Boolean {
        if (short.isEmpty() || short.size > long.size) return false
        return (0..long.size - short.size).any { start ->
            short.indices.all { tokensMatch(long[start + it], short[it]) }
        }
    }

    // ---------------------------------------------------------------- the checks

    /** Does the candidate TITLE (already raw from Spotify) fit the wanted title? */
    fun titleMatches(candidateTitle: String, wantedTitle: String, artistConfirmed: Boolean = false): Boolean {
        val c = tokens(normalizeCandidateTitle(candidateTitle))
        val w = tokens(normalize(wantedTitle))
        if (c.isEmpty() || w.isEmpty()) return false
        if (sequencesEqual(c, w)) return true
        val (short, long) = if (c.size <= w.size) c to w else w to c
        // A confirmed artist is independent evidence, so a one-word title contained in the
        // candidate ("Interview" inside "Fukk a Interview", by Future) is allowed; unconfirmed,
        // a lone word proves nothing.
        val minTokens = if (artistConfirmed) 1 else MIN_CONTAINED_TOKENS
        if (short.size >= minTokens && containsSequence(long, short)) return true
        val pool = c.toMutableList()
        var hit = 0
        for (t in w) {
            val i = pool.indexOfFirst { tokensMatch(it, t) }
            if (i >= 0) { hit++; pool.removeAt(i) }
        }
        return hit.toDouble() / maxOf(c.size, w.size) >= TOKEN_OVERLAP_THRESHOLD
    }

    fun titleIsExact(candidateTitle: String, wantedTitle: String): Boolean =
        sequencesEqual(tokens(normalizeCandidateTitle(candidateTitle)), tokens(normalize(wantedTitle)))

    fun artistMatches(candidateArtists: List<String>, wantedArtist: String): Boolean {
        val w = tokens(normalize(wantedArtist))
        if (w.isEmpty()) return true
        // Whole-token containment, never raw substrings: "ye" must not accept "Yeat".
        return candidateArtists.any {
            val c = tokens(normalize(it))
            c.isNotEmpty() && (containsSequence(c, w) || containsSequence(w, c))
        }
    }

    // ---------------------------------------------------------------- the request side

    /**
     * Splits an embedded "title by artist" on the LAST " by ". Null when there is no such split
     * with both halves non-blank.
     */
    fun splitByArtist(query: String): Wanted? {
        val idx = query.lastIndexOf(" by ", ignoreCase = true)
        if (idx <= 0) return null
        val title = query.substring(0, idx).trim()
        val artist = query.substring(idx + 4).trim()
        return if (title.isNotEmpty() && artist.isNotEmpty()) Wanted(title, artist) else null
    }

    /**
     * Every reading of the request that may legitimately accept a candidate. With an explicit
     * [artist] there is one (a trailing " by <artist>" the model left in the title is trimmed).
     * Without one, the embedded split is tried AND the whole string as a bare title, because
     * "Stand By Me" would otherwise be read as the song "Stand" by "Me" and rejected.
     */
    fun readings(query: String, artist: String?): List<Wanted> {
        val q = query.trim()
        val a = artist?.trim()?.takeIf { it.isNotEmpty() }
        if (a != null) {
            val split = splitByArtist(q)
            val title = if (split != null && normalize(split.artist) == normalize(a)) split.title else q
            return listOf(Wanted(title, a))
        }
        return listOfNotNull(splitByArtist(q), Wanted(q, null)).distinct()
    }

    /** The fielded Spotify query for a known artist, e.g. `track:"Mask Off" artist:"Future"`. */
    fun fieldedQuery(type: String, title: String, artist: String): String {
        val field = if (type == "album") "album" else "track"
        fun clean(s: String) = s.replace("\"", " ").let { SPACES.replace(it, " ").trim() }
        return "$field:\"${clean(title)}\" artist:\"${clean(artist)}\""
    }

    // ---------------------------------------------------------------- picking

    private fun artistsOf(c: JSONObject): List<String> =
        c.optJSONArray("artists")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            .orEmpty()

    private fun accepts(c: JSONObject, reading: Wanted): Boolean =
        if (reading.artist == null) {
            titleMatches(c.optString("name"), reading.title)
        } else {
            artistMatches(artistsOf(c), reading.artist) &&
                titleMatches(c.optString("name"), reading.title, artistConfirmed = true)
        }

    /**
     * Picks from [candidates] (Spotify's own order) for a track or album, or null when nothing is
     * acceptable. [isImposter] is the karaoke/tribute filter: applied first, and if it leaves no
     * ACCEPTABLE hit the unfiltered list is tried, so a query that really was for a karaoke cut is
     * honoured.
     *
     * Ranking keeps Spotify's relevance order. Popularity is a tiebreak only when the first
     * accepted hit is an exact title match: then the most popular exact match wins (first on a
     * tie, or when Spotify sent no popularity at all).
     */
    fun pick(
        candidates: List<JSONObject>,
        query: String,
        artist: String?,
        isImposter: (JSONObject) -> Boolean = { false },
    ): JSONObject? {
        val readings = readings(query, artist)
        fun acceptedFrom(pool: List<JSONObject>): List<Pair<JSONObject, Wanted>> =
            pool.mapNotNull { c -> readings.firstOrNull { accepts(c, it) }?.let { c to it } }

        val clean = candidates.filterNot(isImposter)
        val accepted = acceptedFrom(clean).ifEmpty { acceptedFrom(candidates) }
        val first = accepted.firstOrNull() ?: return null
        if (!titleIsExact(first.first.optString("name"), first.second.title)) return first.first
        return accepted
            .filter { titleIsExact(it.first.optString("name"), it.second.title) }
            .maxByOrNull { it.first.optInt("popularity", 0) }
            ?.first
    }
}
