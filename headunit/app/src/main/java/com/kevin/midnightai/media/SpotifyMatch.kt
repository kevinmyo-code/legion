package com.kevin.midnightai.media

import org.json.JSONObject
import java.text.Normalizer

/**
 * One near-miss a rejection named, with the URI that makes it actionable.
 *
 * The URI is the point (music-suggestions-1124 P3). A rejection's own instruction to the model
 * is to ask which of these was meant - and the rejection used to carry display strings only,
 * so a "yes, that one" had nothing to hand back to `play_music`. Its only route was to
 * re-search by a name it would have to reproduce exactly, in the catalogue's own spelling.
 * Every other music result that names a playable row already carries its URI; this was the
 * inconsistency, not a policy.
 */
data class ClosestHit(val name: String, val artist: String?, val uri: String?) {
    /** "Title by Artist", for the spoken line. */
    val label: String get() = if (artist != null) "$name by $artist" else name
}

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
 *    " - remastered / - live / - radio edit"-style dash suffixes from CANDIDATE titles; fold
 *    diacritics (`Beyoncé` is `beyonce`); turn a letter-standing symbol into the `*` wildcard
 *    (`A$AP` is `a*ap`, which matches `asap`); drop other punctuation except `*`; collapse
 *    whitespace. BOTH sides go through the same function, so a gap between how he says a name and
 *    how Spotify prints it never decides anything.
 *  - Joining words (`and`, `n`, `x`, `plus`, `with`) are skipped on both sides when an ARTIST's
 *    name is matched, because `&` and `+` normalise to nothing: "simon and garfunkel",
 *    "simon garfunkel" and `Simon & Garfunkel` are one artist. Title matching keeps them.
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
    private val APOSTROPHES = Regex("""['’`´]""")
    private val COMBINING = Regex("""\p{Mn}+""")

    /**
     * Symbols a catalogue writes where a letter is said: `A$AP` for "asap", `$uicideboy$` for
     * "suicideboys". Mapped to `*`, which [tokensMatch] already reads as "any letter here", rather
     * than dropped (leaving `aap`) or spaced (leaving `a ap`) - neither of which the request's own
     * spelling can ever match.
     *
     * Only where the symbol TOUCHES a letter, so it is standing in for one. A price (`100$ Bill`)
     * or a title of nothing but symbols keeps its old reading: `***` would be a wildcard that
     * matches every three-letter word there is.
     *
     * A RUN of them is one wildcard per character, because a catalogue writes `Joey Bada$$` for
     * "badass": two letters said, two symbols printed. Collapsing the run to a single `*` would
     * leave `bada*`, five characters against the request's six, and [tokensMatch] compares equal
     * lengths - so the right artist would be a different word.
     */
    private val LETTER_SYMBOL = Regex("""(?<=\p{L})[$@]+|[$@]+(?=\p{L})""")

    /**
     * Lowercase, diacritics folded (`Beyoncé` -> `beyonce`), letter-standing symbols turned into
     * the `*` wildcard, other punctuation out (`*` stays: it is a censor mark), whitespace
     * collapsed.
     *
     * Both sides of every comparison in this file go through this one function, which is the
     * point: a gap between how he says a name and how Spotify prints it must not decide anything.
     * Folding runs BEFORE [NON_WORD], because a combining mark left in place becomes a space and
     * splits the word in half.
     *
     * **An apostrophe-`s` folds to a plain `s`, it is not dropped.** Dropping it broke the common
     * case to chase a rare one: `What's Up` became `what up` while the request "whats up" stayed
     * `whats up`, so the two no longer matched, and `Destiny's Child` stopped matching
     * "destinys child" (blind review round 2, 2026-11-25, finding A). Folding leaves both sides
     * `whats`, which is what the catalogue and a transcript agree on. It gives up telling `reed's`
     * from a plural `reeds`, so a possessive request no longer finds a singular artist - a refusal
     * with closest hits, not a wrong play - and it keeps `Queen` and `Queens Of The Stone Age`
     * apart, which is the collision that matters.
     */
    fun normalize(s: String): String {
        val folded = COMBINING.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
        val stripped = APOSTROPHES.replace(folded.replace('&', ' '), "")
        val letters = LETTER_SYMBOL.replace(stripped) { "*".repeat(it.value.length) }
        return NON_WORD.replace(letters, " ").let { SPACES.replace(it, " ").trim() }
    }

    /** [normalize] after shedding the decoration a streaming catalogue adds to a title. */
    fun normalizeCandidateTitle(title: String): String {
        val lower = title.lowercase()
        val noBrackets = BRACKETS.replace(lower, " ")
        val noDash = DASH_SUFFIX.replace(noBrackets, "")
        val noFeat = FEAT_TAIL.replace(noDash, "")
        return normalize(noFeat)
    }

    /**
     * [normalize] after shedding the decoration a SPOKEN request adds to a title.
     *
     * The mirror of [normalizeCandidateTitle]'s `feat.` strip, and only that one. The brackets and
     * the `- Remastered` dash suffix stay a catalogue's decorations: nobody says them, and a dash
     * in a request is part of what he asked for. Without this the strip ran on one side only, so
     * "mask off feat kendrick" scored against the whole phrase while the catalogue's own
     * "Mask Off (feat. Kendrick Lamar)" had already shed its tail, and the right song was refused
     * (music-fix-1124 review round 2, finding 1: the asymmetry, not a missing word).
     *
     * **Scoring only, and what it sheds is judged by [unstrippedSurplus].** Stripping the tail for
     * scoring alone handed a stranger the song: when the candidate's own title is the LONGER side,
     * [containmentLeftovers] has no sub-run to measure, returns null, and [accepts] read that null
     * as "nothing to explain". So "paper lanterns feat north sail" played `Paper Lanterns Tonight`
     * by Gravel Choir, an artist nobody named, where the base refused it (blind review, 2026-11-25,
     * finding 1). The tail is surplus the request carried either way, so it goes through
     * [leftoversExplained] like any other surplus.
     */
    fun normalizeWantedTitle(title: String): String = normalize(FEAT_TAIL.replace(title.lowercase(), ""))

    /**
     * Every word the UNSTRIPPED request carried that this candidate's title does not cover, filler
     * dropped. Empty when the request had no tail to shed, because then nothing was hidden from
     * [containmentLeftovers] and the ordinary rules already measured the whole request.
     *
     * This is the guard for the whole strip, not just its tail. An earlier round judged only the
     * tail's own body, which is empty when the tail is pure filler, and then a `null` containment
     * still read as "nothing to explain": "come away with me" shed `with me`, scored its two
     * remaining words inside `Come Away Death`, and played a stranger that the base refused (blind
     * review round 2, 2026-11-25, finding B). Measured against the whole request, the surplus is
     * `with`, which `Come Away Death` cannot account for and `Come Away With Me` can.
     *
     * Per token against a pool, so a word the title really carries is consumed once and a repeat is
     * still surplus. Only the request's own words are judged; a title word the request did not say
     * is the containment branch's business and stays as it was.
     */
    internal fun unstrippedSurplus(candidateTitle: String, wantedTitle: String): List<String> {
        if (!FEAT_TAIL.containsMatchIn(wantedTitle.lowercase())) return emptyList()
        val pool = tokens(normalizeCandidateTitle(candidateTitle)).toMutableList()
        return tokens(normalize(wantedTitle)).filter { w ->
            val at = pool.indexOfFirst { tokensMatch(it, w) }
            if (at >= 0) pool.removeAt(at)
            at < 0
        }.filterNot { it in FILLER }
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

    /**
     * Words that only ever JOIN two names. The catalogue prints "Simon & Garfunkel", "Earth, Wind
     * & Fire", "Florence + The Machine", and [normalize] leaves those joiners as nothing at all,
     * while the request says "simon and garfunkel". Either side may carry a joiner the other left
     * out, so an artist's name is matched with them skipped on BOTH sides, and a joiner is never
     * evidence of a different artist.
     *
     * Only the ARTIST comparisons skip them. Title matching stays exact, because
     * [containmentLeftovers] measures its surplus by the matched run's own length.
     */
    private val CONNECTIVES = setOf("and", "n", "x", "plus", "with")

    /**
     * Where [short] sits inside [long] as one run with connectives on either side skipped, as a
     * range over [long]'s own indices, or null when it does not. The range is what a caller takes
     * back out of its list, so it covers the joiners the run swallowed too.
     *
     * At least one token must really match. Skipping a connective is not evidence, so an artist
     * written as nothing BUT connectives (`X`, a real band name and how `XXXTentacion` gets said)
     * would otherwise be "found" in every query at an empty range and confirm every candidate.
     * A spoken "x" still matches a catalogue "X", because [tokensMatch] is tried first.
     */
    // NestedBlockDepth: one walk, one when; splitting it hides the two cursors.
    // ReturnCount: the empty guard, the hit and the miss, one worded exit each.
    @Suppress("NestedBlockDepth", "ReturnCount")
    private fun artistRun(long: List<String>, short: List<String>): IntRange? {
        if (short.isEmpty()) return null
        for (start in long.indices) {
            var i = start
            var j = 0
            var matched = 0
            while (i < long.size && j < short.size) {
                when {
                    tokensMatch(long[i], short[j]) -> { i++; j++; matched++ }
                    long[i] in CONNECTIVES -> i++
                    short[j] in CONNECTIVES -> j++
                    else -> break
                }
            }
            while (j < short.size && short[j] in CONNECTIVES) j++
            if (j == short.size && matched > 0) return start until i
        }
        return null
    }

    // ---------------------------------------------------------------- the checks

    /** Does the candidate TITLE (already raw from Spotify) fit the wanted title? */
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    fun titleMatches(candidateTitle: String, wantedTitle: String, artistConfirmed: Boolean = false): Boolean {
        val c = tokens(normalizeCandidateTitle(candidateTitle))
        val w = tokens(normalizeWantedTitle(wantedTitle))
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
        sequencesEqual(tokens(normalizeCandidateTitle(candidateTitle)), tokens(normalizeWantedTitle(wantedTitle)))

    /**
     * Words a spoken request puts around a title. They are never evidence of a different artist,
     * so a leftover that is only filler ("play the mask off song") must not reject a hit.
     *
     * The command verb and the credit words came in on the music-fix-1124 round-2 review's finding
     * 1, which measured the gap: a right-song, right-artist request was refused because the surplus
     * word it carried was `play`, `now`, `from`, `feat`, `ft` or `featuring`. `play_music`'s own
     * `query` description asks the model for "his words", so echoing the utterance verb-and-all is
     * what the declaration invites, and the review ranked `play` / `now` the likeliest of the set.
     * The credit words are in here as well as stripped by [normalizeWantedTitle], because a request
     * can name a feature without a tail after it ("the mask off featuring one").
     */
    private val FILLER = setOf(
        "song", "songs", "track", "tune", "music", "version", "please", "the", "a", "an",
        "by", "on", "for", "me", "some", "that", "it", "spotify",
        "play", "now", "from", "feat", "ft", "featuring",
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
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    internal fun containmentLeftovers(candidateTitle: String, wantedTitle: String): List<String>? {
        val c = tokens(normalizeCandidateTitle(candidateTitle))
        // The UNSTRIPPED request, deliberately: [normalizeWantedTitle] sheds a `feat. X` tail for
        // SCORING, and if it ran here the words in that tail would never be judged at all. That
        // would undo P5 - "paper lanterns feat north sail" would score as a bare "paper lanterns"
        // and play Hana Reed's song for a featured artist she does not have. The surplus is the one
        // thing this function exists to look at, so it sees every word the request carried; `feat`
        // itself is dropped below as filler, and the NAME after it still has to be explained.
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
        val artistTokens = candidateArtists.flatMap { tokens(normalize(it)) }
        val pool = artistTokens + tokens(normalize(candidateRawTitle))
        return leftovers.withIndex().all { (i, l) ->
            pool.any { tokensMatch(it, l) } || joinsArtistWords(leftovers, i, artistTokens)
        }
    }

    /**
     * Is [leftovers]`[at]` a connective sitting BETWEEN two of this candidate's own artist words?
     *
     * That is the one place a joiner carries no information: "mrs robinson simon and garfunkel"
     * against a catalogue that prints `Simon & Garfunkel`, whose `&` [normalize] left as nothing,
     * so his "and" has no token to match. Deliberately not filler - a surplus "and chill" still
     * names something nobody involved has, and must still reject.
     */
    private fun joinsArtistWords(leftovers: List<String>, at: Int, artistTokens: List<String>): Boolean =
        leftovers[at] in CONNECTIVES &&
            at > 0 && at < leftovers.lastIndex &&
            artistTokens.any { tokensMatch(it, leftovers[at - 1]) } &&
            artistTokens.any { tokensMatch(it, leftovers[at + 1]) }

    fun artistMatches(candidateArtists: List<String>, wantedArtist: String): Boolean {
        val w = tokens(normalize(wantedArtist))
        if (w.isEmpty()) return true
        // Whole-token containment, never raw substrings: "ye" must not accept "Yeat".
        return candidateArtists.any {
            val c = tokens(normalize(it))
            c.isNotEmpty() && (artistRun(c, w) != null || artistRun(w, c) != null)
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

    /** The top hits in Spotify's own order, for naming what a rejection saw. */
    fun describe(candidates: List<JSONObject>): List<ClosestHit> =
        candidates.take(CLOSEST_COUNT).mapNotNull { c ->
            val name = c.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ClosestHit(
                name = name,
                artist = artistsOf(c).firstOrNull()?.takeIf { it.isNotBlank() },
                uri = c.optString("uri").takeIf { it.isNotBlank() },
            )
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
        isTopHit && noSharedScript(candidateTitle, wantedTitle)

    /**
     * The title half of [query] once one of [artistNames]'s own words are taken out of it, or null
     * when nothing but filler is left.
     *
     * Null is the load-bearing case. A request that is ONLY an artist's name has no title in it, so
     * there is no title for any rule here to have matched, and without this a query of "<artist>"
     * would take that artist's top foreign-script track as the song asked for. It is also why one
     * token cannot do two jobs: a single word that names the artist cannot simultaneously be
     * evidence of the artist and the title ("queen" must not accept "Killer Queen" by Queen).
     */
    internal fun titleRemainder(query: String, artistNames: List<String>): String? {
        val q = tokens(normalize(query))
        for (a in artistNames) {
            val run = artistRun(q, tokens(normalize(a))) ?: continue
            return (q.take(run.first) + q.drop(run.last + 1)).filterNot { it in FILLER }
                .joinToString(" ").takeIf { it.isNotBlank() }
        }
        return q.filterNot { it in FILLER }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /**
     * Artist agreement derived from the query itself, returning the title remainder it leaves.
     *
     * Null means no agreement: either no candidate artist's whole name sits inside the query, or
     * one does and consumes the entire query, which proves nothing about a title. The model leaves
     * `artist` unset in practice, so this is the only artist evidence most calls ever have.
     */
    internal fun artistEvidence(query: String, candidateArtists: List<String>): String? {
        val q = tokens(normalize(query))
        for (a in candidateArtists) {
            val run = artistRun(q, tokens(normalize(a))) ?: continue
            val rest = (q.take(run.first) + q.drop(run.last + 1)).filterNot { it in FILLER }
            if (rest.isNotEmpty()) return rest.joinToString(" ")
        }
        return null
    }

    @Suppress("ReturnCount") // guard clauses, one worded exit each
    private fun accepts(c: JSONObject, reading: Wanted, isTopHit: Boolean): Boolean {
        val name = c.optString("name")
        val artists = artistsOf(c)
        if (reading.artist != null) {
            if (!artistMatches(artists, reading.artist)) return false
            if (titleMatches(name, reading.title, artistConfirmed = true)) return true
            // The remainder guard applies here too: a model that put the artist's name in `query`
            // as well leaves no title behind, and must not get that artist's top hit as the song.
            val left = titleRemainder(reading.title, artists + reading.artist) ?: return false
            return crossScriptAccepts(name, left, isTopHit)
        }
        // No artist argument: the query is everything known, so artist agreement is derived from
        // the query's own words rather than waited for. The model left `artist` unset on every
        // one of the four calls that went wrong on 2026-11-24, so nothing here may depend on it.
        val remainder = artistEvidence(reading.title, artists)
        if (titleMatches(name, reading.title, artistConfirmed = remainder != null)) {
            // A tail was shed for scoring, so every word the whole request carried must still be
            // accounted for. Checked before the containment branch because that branch cannot see
            // them: when the candidate's title is the longer side there is no sub-run to measure,
            // and its null must not read as "nothing to explain".
            val surplus = unstrippedSurplus(name, reading.title)
            if (surplus.isNotEmpty() && !leftoversExplained(surplus, artists, name)) return false
            val leftovers = containmentLeftovers(name, reading.title) ?: return true
            return leftovers.isEmpty() || leftoversExplained(leftovers, artists, name)
        }
        return remainder != null && crossScriptAccepts(name, remainder, isTopHit)
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
