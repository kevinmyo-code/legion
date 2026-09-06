package com.kevin.legion.ai

import android.content.Context

/**
 * Last-known health of the user's BYO Gemini key, set from Live-socket and sub-agent call outcomes
 * so the Setup screen can say what is wrong without a network probe.
 *
 * **CORRECTED 2026-09-06. The doc comment here used to say this existed "so the Setup screen can
 * surface a quiet heads-up (key issue seen recently)". It did not, and never had.** A grep for
 * `KeyHealth.lastProblem` across the whole repo, source and tests, returned ZERO readers against
 * eight write sites. The app diagnosed a rate-limited or rejected key correctly, in eight places,
 * and then dropped the diagnosis on the floor. It was also process-lifetime only, so even a reader
 * would have lost it on the next process death.
 *
 * That mattered the moment Kevin said *"i ran out of credits and im probably not gonna top up for a
 * while."* On a quota-exhausted key the Live socket fails its HTTP upgrade, and what a person sees
 * is a wake word that appears to do nothing. The app knew why. Nothing said so.
 *
 * So this now persists, and [com.kevin.legion.ui.assistantAvailabilitySentence] reads it.
 *
 * **[detail] is what makes the sentence true rather than plausible.** The instruction was to check
 * the error, not to assume any failure is quota, so the raw status is stored alongside the verdict:
 * a sentence can then say "HTTP 429" and be checkable, instead of asserting a cause nobody
 * verified. A 429 is genuinely ambiguous between an exhausted quota and a per-minute rate limit -
 * Gemini returns `RESOURCE_EXHAUSTED` for both - and the wording above this must not pretend
 * otherwise.
 *
 * Cleared by [noteOk] on the first clean call, so a key that starts working again stops being
 * reported as broken without anyone having to dismiss anything.
 */
object KeyHealth {
    private const val PREFS = "gemini_key_health"
    private const val KEY_PROBLEM = "last_problem"
    private const val KEY_AT = "last_problem_at"
    private const val KEY_DETAIL = "last_problem_detail"

    /** "rate-limited" (HTTP 429) or "invalid" (400 with API_KEY_INVALID, 401, 403). */
    const val PROBLEM_RATE_LIMITED = "rate-limited"
    const val PROBLEM_INVALID = "invalid"

    /** Longest server message kept. Enough to be recognisable, short enough not to paste a wall of
     *  JSON into a settings screen. */
    private const val DETAIL_MAX = 160

    @Volatile private var appContext: Context? = null

    @Volatile var lastProblem: String? = null; private set
    @Volatile var lastProblemAt: Long = 0L; private set

    /** The status and, when there was one, the server's own message - the evidence behind
     *  [lastProblem]. Empty when the problem was recorded without one. */
    @Volatile var detail: String = ""; private set

    /**
     * Seeds the in-memory copy from disk and gives the note functions somewhere to persist to.
     * Called once from [com.kevin.legion.MidnightApplication.onCreate], same shape as
     * [GeminiKeyProvider.init]. Before it runs, the note functions still update the in-memory copy
     * and simply do not persist - a lost diagnosis is better than a crash in a socket callback.
     */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastProblem = prefs.getString(KEY_PROBLEM, null)?.takeIf { it.isNotBlank() }
        lastProblemAt = prefs.getLong(KEY_AT, 0L)
        detail = prefs.getString(KEY_DETAIL, "").orEmpty()
    }

    /** Test seam; real code calls [init]. */
    internal fun resetForTest(context: Context?) {
        appContext = context?.applicationContext
        lastProblem = null
        lastProblemAt = 0L
        detail = ""
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    /**
     * HTTP 429. [detail] should carry the status and any server message the caller has, so the
     * Setup screen can report what was actually seen rather than a guess at what it meant.
     */
    @JvmOverloads
    fun noteRateLimited(detail: String = "") = record(PROBLEM_RATE_LIMITED, detail)

    /** The key was rejected: 400 with `API_KEY_INVALID`, or 401/403. */
    @JvmOverloads
    fun noteInvalid(detail: String = "") = record(PROBLEM_INVALID, detail)

    /** A call went through. Whatever was wrong is not wrong now. */
    fun noteOk() {
        if (lastProblem == null && detail.isEmpty()) return
        lastProblem = null
        lastProblemAt = 0L
        detail = ""
        persist(null, 0L, "")
    }

    private fun record(problem: String, rawDetail: String) {
        val now = System.currentTimeMillis()
        val trimmed = rawDetail.trim().replace(Regex("\\s+"), " ").take(DETAIL_MAX)
        lastProblem = problem
        lastProblemAt = now
        detail = trimmed
        persist(problem, now, trimmed)
    }

    private fun persist(problem: String?, at: Long, detail: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString(KEY_PROBLEM, problem)
            .putLong(KEY_AT, at)
            .putString(KEY_DETAIL, detail)
            .apply()
    }
}
