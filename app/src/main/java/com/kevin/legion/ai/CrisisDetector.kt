package com.kevin.legion.ai

/**
 * Backstop detector for genuine distress in the driver's speech (CLAUDE.md sec 9.1,
 * "Crisis path, non-negotiable").
 *
 * **This is the second line of defence, not the first.** The system prompt
 * (AriaBrain.sharedInstructions) carries the real rule, because a model reads
 * intent and this cannot. This exists because a prompt rule is a request, not a
 * guarantee: it can be drifted past mid-conversation, and sec 2 lets an unlocked
 * driver edit the persona. The prompt handles nuance; this handles the case where
 * the prompt was not enough.
 *
 * **Tuned for precision, deliberately, at the cost of recall.** It fires only on
 * phrases that have no innocent reading, and it will MISS distress phrased
 * obliquely ("I don't see the point anymore"). That is the intended trade and it
 * is worth being explicit about why: a false positive drops the character and
 * reads a crisis line at someone who said their commute is killing them, which is
 * alarming, insulting, and the fastest way to get the whole feature switched off.
 * A miss falls back to the prompt rule, which is where the nuanced cases were
 * always going to be caught. Widening this list is not a free safety win - each
 * addition trades a real miss for a real false positive, so weigh both.
 *
 * A car cabin is unusually hostile to naive keyword matching: "this traffic is
 * killing me", "I'm dying to get home", "dead battery", "kill the music" and
 * "suicide doors" (a real body style) are all ordinary car talk. The phrase list
 * and [EXCLUSIONS] below are built around that.
 */
object CrisisDetector {

    /**
     * Phrases with no plausible innocent reading in a car. Matched against
     * normalized text, so punctuation and casing don't matter. Kept as multi-word
     * phrases on purpose: single words ("die", "kill", "dead") are car idioms far
     * more often than they are distress.
     */
    private val PHRASES = listOf(
        "kill myself",
        "killing myself",
        "end my life",
        "ending my life",
        "take my own life",
        "taking my own life",
        "want to die",
        "wanna die",
        "want to be dead",
        "better off dead",
        "better off without me",
        "dont want to live",
        "do not want to live",
        "dont want to be here anymore",
        "no reason to live",
        "nothing to live for",
        "suicidal",
        "suicide",
        "hurt myself",
        "hurting myself",
        "harm myself",
        "harming myself",
        "cut myself",
        "cutting myself",
    )

    /**
     * Innocent phrases that CONTAIN a [PHRASES] entry as a substring. Removed
     * before matching, so the phrase inside them can't fire.
     *
     * "suicide doors" is the load-bearing one: it's a rear-hinged door, standard
     * enthusiast vocabulary, and exactly the kind of thing this app's drivers say
     * out loud. Without this guard, "does the XJ come with suicide doors" trips
     * the crisis path.
     */
    private val EXCLUSIONS = listOf(
        "suicide door",
        "suicide doors",
        "suicide knob",
        "suicide shifter",
    )

    /**
     * True if [text] contains an unambiguous distress phrase.
     *
     * Safe to call on every partial transcript delta: it's a handful of substring
     * scans over a short string, no allocation beyond the normalized copy.
     */
    fun detect(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        val guarded = EXCLUSIONS.fold(normalized) { acc, exclusion -> acc.replace(exclusion, " ") }
        return PHRASES.any { guarded.contains(it) }
    }

    /**
     * Lowercases, drops apostrophes so "don't" matches "dont", replaces every
     * other non-alphanumeric run with a single space, and trims. Apostrophes are
     * dropped rather than spaced because speech transcripts are inconsistent about
     * them ("dont" / "don't" / "do n't").
     */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace("'", "")
            .replace("’", "") // curly apostrophe, common in transcripts
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
