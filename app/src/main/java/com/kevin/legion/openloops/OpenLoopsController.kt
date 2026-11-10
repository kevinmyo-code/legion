package com.kevin.legion.openloops

import android.content.Context
import com.kevin.legion.backend.engine.DjangoOpenLoopsBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.memory.MemoryOutcome
import java.time.LocalDate

/** What the Proposed to-dos list shows: the listing, or why there is none. */
sealed interface LoopsView {
    data class Showing(val listing: Listing) : LoopsView

    data class Unavailable(val sentence: String) : LoopsView
}

/**
 * Proposed to-dos (ADR 0085), one controller for every door (ADR 0035): the brief's line, the
 * Proposed to-dos rows on Settings, Proactive speech, and the voice answers resolved inside
 * `manage_item` ([OpenLoopsToolbox]) all call this, so the hands path and the voice path cannot
 * say different things or do different things.
 *
 * **The engine decides and makes the task.** A yes is `POST track`: the engine makes a dated
 * reminder (only with a date he named) or an undated line on his Todo checklist, idempotently,
 * and records the yes in the same transaction. The phone then pulls that one aspect and re-arms
 * reminders ([afterTrack]), so a dated one is chased like any reminder without waiting for the
 * next app start. A pull that fails leaves the task on the engine; the answer says it reaches this
 * phone at the next sync.
 */
class OpenLoopsController(
    /** Null when this device has no engine address or token. Asked per call. */
    private val backend: () -> OpenLoopsBackend?,
    /** Pulls the aspect a yes wrote into ("reminder" or "todo") and re-arms; throws on failure. */
    private val afterTrack: suspend (taskKind: String) -> Unit = {},
) {

    suspend fun view(): LoopsView {
        val engine = backend() ?: return LoopsView.Unavailable(NO_ENGINE)
        return when (val got = engine.list()) {
            is MemoryOutcome.Ok -> LoopsView.Showing(got.value).also { remember(got.value) }
            else -> LoopsView.Unavailable(sentence(got))
        }
    }

    /**
     * The spoken brief's section ([OpenLoopsBrief.forVoice]), or null for none. Unreadable says so
     * in words that forbid the subject. Counts each proposal as carried (the engine's `offer`).
     */
    suspend fun voiceLine(): String? {
        val engine = backend() ?: return null
        return when (val got = engine.offer()) {
            is MemoryOutcome.Ok -> {
                remember(got.value)
                if (got.value.on) OpenLoopsBrief.forVoice(got.value.proposals) else null
            }
            is MemoryOutcome.Refused -> null // a recruiter, the work laptop: no such subject here
            else -> OpenLoopsBrief.UNREADABLE_FOR_VOICE
        }
    }

    /** The Brief modal's section text, or null for none. Counts nothing. */
    suspend fun screenLine(): String? = when (val v = view()) {
        is LoopsView.Showing -> if (v.listing.on) OpenLoopsBrief.forScreen(v.listing.proposals) else null
        is LoopsView.Unavailable -> if (backend() == null) null else OpenLoopsBrief.UNREADABLE_FOR_SCREEN
    }

    suspend fun track(id: String, dueDate: LocalDate? = null): LoopAnswer {
        val engine = backend() ?: return LoopAnswer(false, "Nothing was tracked. $NO_ENGINE")
        return when (val got = engine.track(id, dueDate)) {
            is MemoryOutcome.Ok -> {
                forget(id)
                val synced = runCatching { afterTrack(got.value.taskKind) }.isSuccess
                LoopAnswer(true, got.value.words + if (synced) "" else " $NEXT_SYNC")
            }
            else -> LoopAnswer(false, sentence(got))
        }
    }

    suspend fun dismiss(id: String): LoopAnswer {
        val engine = backend() ?: return LoopAnswer(false, "Nothing was changed. $NO_ENGINE")
        return when (val got = engine.dismiss(id)) {
            is MemoryOutcome.Ok -> LoopAnswer(true, got.value).also { forget(id) }
            else -> LoopAnswer(false, sentence(got))
        }
    }

    /** "stop proposing tasks" (false) and the switch on Settings, Proactive speech. */
    suspend fun setProposing(on: Boolean): LoopAnswer {
        val engine = backend() ?: return LoopAnswer(false, "Nothing was changed. $NO_ENGINE")
        return when (val got = engine.setProposing(on)) {
            is MemoryOutcome.Ok -> LoopAnswer(true, got.value).also { if (!on) Hint.pending = false }
            else -> LoopAnswer(false, sentence(got))
        }
    }

    /** What a spoken task name resolved to among the pending proposals. */
    sealed interface Match {
        data object None : Match

        data class One(val proposal: Proposal) : Match

        data class Many(val proposals: List<Proposal>) : Match
    }

    /**
     * The pending proposal [spoken] names, read from the engine NOW (never a stale copy), so a yes
     * works cold, in a later conversation, as well as right after the brief. Skips the call when
     * this process already knows there is nothing pending ([Hint]). Unreadable is [Match.None]:
     * the caller then does what `manage_item` always did.
     */
    @Suppress("ReturnCount") // nothing pending, no engine, unreadable, nothing said: each is no match
    suspend fun match(spoken: String): Match {
        if (Hint.pending == false) return Match.None
        val engine = backend() ?: return Match.None
        val listing = (engine.list() as? MemoryOutcome.Ok)?.value ?: return Match.None
        remember(listing)
        val said = OpenLoopsWords.task(spoken)
        if (said.isEmpty()) return Match.None
        // Equality, not containment: "remove call the dentist office" (a real list line) or "add
        // call the dentist about the crown" are not answers to "Call the dentist".
        val hits = listing.proposals.filter { OpenLoopsWords.task(it.title) == said }
        return when (hits.size) {
            0 -> Match.None
            1 -> Match.One(hits.single())
            else -> Match.Many(hits)
        }
    }

    private fun remember(listing: Listing) {
        Hint.pending = listing.on && listing.proposing && listing.proposals.isNotEmpty()
    }

    private fun forget(@Suppress("UNUSED_PARAMETER") id: String) {
        // The next list read sets the hint again; until then a further match asks the engine.
        Hint.pending = null
    }

    private fun sentence(got: MemoryOutcome<*>): String = when (got) {
        is MemoryOutcome.Unreachable -> "Nothing was changed. The engine could not be reached: ${got.sentence}"
        is MemoryOutcome.Refused -> got.sentence
        is MemoryOutcome.Unconfirmed -> "It is not known whether that took: ${got.sentence}"
        is MemoryOutcome.Ok -> ""
    }

    /**
     * Whether this process has seen pending proposals: null unknown (ask the engine), false none
     * (skip the call inside `manage_item`), true some. Only an optimisation for the voice path; a
     * list read always asks the engine.
     */
    object Hint {
        @Volatile
        var pending: Boolean? = null
    }

    companion object {
        const val NO_ENGINE = "This phone is not signed in to the engine."
        const val NEXT_SYNC = "It reaches this phone at the next sync."

        fun forContext(context: Context): OpenLoopsController {
            val app = context.applicationContext
            return OpenLoopsController(
                backend = {
                    EngineHttp(EngineConfig(app)).takeIf { it.isUsable() }?.let { DjangoOpenLoopsBackend(it) }
                },
                afterTrack = { kind -> OpenLoopsSync.pull(app, kind) },
            )
        }
    }
}

/** The content words of a task, the phone's copy of the engine's key words (`open_loops/gate.py`). */
object OpenLoopsWords {
    private val STOP = setOf(
        "a", "an", "the", "to", "my", "our", "his", "her", "their", "your", "for", "of", "on",
        "in", "at", "and", "i", "me", "need", "needs", "should", "gotta", "have", "has", "got",
        "must", "will", "ill", "im", "be", "go", "going", "want", "wanna", "really", "just",
        "please", "some", "this", "that", "it", "by", "up",
    )

    fun content(text: String): Set<String> =
        Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.filterNot { it in STOP }.toSet()

    private val DATE_WORDS = setOf(
        "january", "jan", "february", "feb", "march", "mar", "april", "apr", "may", "june", "jun",
        "july", "jul", "august", "aug", "september", "sep", "sept", "october", "oct", "november",
        "nov", "december", "dec", "st", "nd", "rd", "th",
    )

    /** The task's words without its date, so "renew the registration" (date passed separately)
     * names "Renew the registration by November 20". */
    fun task(text: String): Set<String> =
        content(text).filterNot { it in DATE_WORDS || ORDINAL.matches(it) }.toSet()

    private val ORDINAL = Regex("[0-9]+(st|nd|rd|th)?")
}
