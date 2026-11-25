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
 *  - **Artist agreement is derived from the request, not waited for** (2026-11-24). The `artist`
 *    argument is routinely left unset by the model, so the query's own words are matched against
 *    the candidate's artists. Two rules follow from that one piece of evidence:
 *    - a CONTAINED title match whose surplus query words (filler aside) name neither this
 *      candidate's artist nor anything in its raw title is REJECTED, so a title that happens to
 *      sit inside a request for a different artist's song no longer plays;
 *    - a title in a wholly different writing system from the request is ACCEPTED when the artist
 *      agrees, the candidate is Spotify's own first hit, and a non-empty title remainder is left
 *      once the artist's words come out of the query.
 */
@Suppress("TooManyFunctions") // one pure helper per pipeline step
internal object SpotifyMatch {

    const val TOKEN_OVERLAP_THRESHOLD = 0.6
    const val MIN_CONTAINED_TOKENS = 2

    private const val BY_SEPARATOR = " by "

    /** How many near-misses a rejection names. */
    const val CLOSEST_COUNT = 3

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

    @Suppress("ReturnCount") // guard clauses, one worded exit each
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

    /** Where [short] sits inside [long] as a whole run, or -1. */
    private fun indexOfSequence(long: List<String>, short: List<String>): Int {
        if (short.isEmpty() || short.size > long.size) return -1
        return (0..long.size - short.size).firstOrNull { start ->
            short.indices.all { tokensMatch(long[start + it], short[it]) }
        } ?: -1
    }

    private fun containsSequence(long: List<String>, short: List<String>): Boolean =
        indexOfSequence(long, short) >= 0

    // ---------------------------------------------------------------- the checks

    /** Does the candidate TITLE (already raw from Spotify) fit the wanted title? */
    @Suppress("ReturnCount") // guard clauses, one worded exit each
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

    /**
     * Words a spoken request puts around a title. They are never evidence of a different artist,
     * so a leftover that is only filler ("play the mask off song") must not reject a hit.
     */
    private val FILLER = setOf(
        "song", "songs", "track", "tune", "music", "version", "please", "the", "a", "an",
        "by", "on", "for", "me", "some", "that", "it", "spotify",
    )

    /**
     * The wanted tokens a CONTAINED title match left unused, filler dropped; null when the
     * candidate's title was not wholly contained in the wanted tokens.
     *
     * Only the containment branch, deliberately. That is the branch where the wanted side is
     * LONGER than the title it matched, so the surplus is extra information the request carried -
     * usually the artist (music-suggestions-1124 P5: a two-token title sat inside a four-token
     * query, the title matched, and the two words naming a DIFFERENT artist were never looked at,
     * so the wrong artist's song of the same name played). In the overlap branch an unmatched
     * token is a mis-said title word rather than surplus ("keep your head up" against "Keep Ya
     * Head Up"), and rejecting on it would break requests that work today.
     */
    internal fun containmentLeftovers(candidateTitle: String, wantedTitle: String): List<String>? {
        val c = tokens(normalizeCandidateTitle(candidateTitle))
        val w = tokens(normalize(wantedTitle))
        if (c.isEmpty() || w.isEmpty() || c.size >= w.size) return null
        val at = indexOfSequence(w, c)
        if (at < 0) return null
        return (w.take(at) + w.drop(at + c.size)).filterNot { it in FILLER }
    }

    /**
     * Is every token in [leftovers] accounted for by this candidate - as part of an artist's name,
     * or as a word the catalogue's own raw title carries (a "- Live" suffix, a "(Remix)", a
     * "feat. X" tail) that [normalizeCandidateTitle] had stripped before the comparison?
     *
     * Per token, not as one sequence: the surplus words need not be adjacent in the request.
     */
    internal fun leftoversExplained(
        leftovers: List<String>,
        candidateArtists: List<String>,
        candidateRawTitle: String,
    ): Boolean {
        val pool = candidateArtists.flatMap { tokens(normalize(it)) } + tokens(normalize(candidateRawTitle))
        return leftovers.all { l -> pool.any { tokensMatch(it, l) } }
    }

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
        val idx = query.lastIndexOf(BY_SEPARATOR, ignoreCase = true)
        if (idx <= 0) return null
        val title = query.substring(0, idx).trim()
        val artist = query.substring(idx + BY_SEPARATOR.length).trim()
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
            val title = if (split != null && normalize(split.artist.orEmpty()) == normalize(a)) split.title else q
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

    /** The top hits (Spotify's order) as "Title by Artist", for naming what a rejection saw. */
    fun describe(candidates: List<JSONObject>): List<String> =
        candidates.take(CLOSEST_COUNT).mapNotNull { c ->
            val name = c.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val by = artistsOf(c).firstOrNull()?.takeIf { it.isNotBlank() }
            if (by != null) "$name by $by" else name
        }

    private fun artistsOf(c: JSONObject): List<String> =
        c.optJSONArray("artists")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            .orEmpty()

    // ---------------------------------------------------------------- across scripts

    /** The writing systems [s]'s letters are in, ignoring punctuation, digits and spaces. */
    private fun scriptsOf(s: String): Set<Character.UnicodeScript> {
        val out = mutableSetOf<Character.UnicodeScript>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isLetter(cp)) {
                val script = Character.UnicodeScript.of(cp)
                val neutral = script == Character.UnicodeScript.COMMON ||
                    script == Character.UnicodeScript.INHERITED ||
                    script == Character.UnicodeScript.UNKNOWN
                if (!neutral) out.add(script)
            }
            i += Character.charCount(cp)
        }
        return out
    }

    /**
     * True when these two titles are written in entirely different writing systems, so no token
     * rule in this file can ever relate them: every comparison here is over normalised Latin-ish
     * tokens, and a translated or romanised title shares not one token with the catalogue's own
     * spelling. Scoring zero is then a fact about the alphabets, not evidence about the song.
     */
    internal fun noSharedScript(a: String, b: String): Boolean {
        val x = scriptsOf(a)
        val y = scriptsOf(b)
        return x.isNotEmpty() && y.isNotEmpty() && x.intersect(y).isEmpty()
    }

    /**
     * The confirmed-artist escape from a cross-script title (music-suggestions-1124 P2: the right
     * track, by the right artist, came back as Spotify's own first hit and the gate rejected it
     * because the asked-for title was in another script).
     *
     * All three conditions carry weight. The artist is independent evidence, already established
     * by the caller. [isTopHit] keeps this to Spotify's own best answer for the query, so it can
     * never promote hit three. And [wantedTitle] must be a non-empty REMAINDER after the artist's
     * own words: without that, a query naming only an artist would "match" that artist's top
     * foreign-script track and play it as the song he asked for.
     */
    private fun crossScriptAccepts(candidateTitle: String, wantedTitle: String, isTopHit: Boolean): Boolean =
        isTopHit && wantedTitle.isNotBlank() && noSharedScript(candidateTitle, wantedTitle)

    /**
     * What is left of [query] once some candidate artist's own words are taken out of it - the
     * title half of a request that put both in one string. Blank when the query was only the
     * artist, which is what stops [crossScriptAccepts] from firing on one.
     */
    internal fun titleRemainder(query: String, candidateArtists: List<String>): String {
        val q = tokens(normalize(query))
        for (a in candidateArtists) {
            val at = indexOfSequence(q, tokens(normalize(a)))
            if (at >= 0) {
                val artistSize = tokens(normalize(a)).size
                return (q.take(at) + q.drop(at + artistSize)).filterNot { it in FILLER }.joinToString(" ")
            }
        }
        return query
    }

    @Suppress("ReturnCount") // guard clauses, one worded exit each
    private fun accepts(c: JSONObject, reading: Wanted, isTopHit: Boolean): Boolean {
        val name = c.optString("name")
        val artists = artistsOf(c)
        if (reading.artist != null) {
            if (!artistMatches(artists, reading.artist)) return false
            if (titleMatches(name, reading.title, artistConfirmed = true)) return true
            return crossScriptAccepts(name, reading.title, isTopHit)
        }
        // No artist argument: the query is everything known, so artist agreement is derived from
        // the query's own words rather than waited for. The model left `artist` unset on every
        // one of the four calls that went wrong on 2026-11-24, so nothing here may depend on it.
        val artistInQuery = artistMatches(artists, reading.title)
        if (titleMatches(name, reading.title, artistConfirmed = artistInQuery)) {
            val leftovers = containmentLeftovers(name, reading.title) ?: return true
            return leftovers.isEmpty() || leftoversExplained(leftovers, artists, name)
        }
        return artistInQuery && crossScriptAccepts(name, titleRemainder(reading.title, artists), isTopHit)
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
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    fun pick(
        candidates: List<JSONObject>,
        query: String,
        artist: String?,
        isImposter: (JSONObject) -> Boolean = { false },
    ): JSONObject? {
        val readings = readings(query, artist)
        // Spotify's own first answer for the query, taken from the UNFILTERED list: the
        // cross-script rule leans on "this is the best hit Spotify had", which the imposter
        // filter's ordering must not be able to redefine.
        val topHit = candidates.firstOrNull()
        fun acceptedFrom(pool: List<JSONObject>): List<Pair<JSONObject, Wanted>> =
            pool.mapNotNull { c -> readings.firstOrNull { accepts(c, it, c === topHit) }?.let { c to it } }

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
