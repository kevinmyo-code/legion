package com.kevin.legion.service.cascade

import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Speculative answers (Kevin approved, 2026-11-15): at a LIKELY end of his turn, before it is
 * confirmed, the text model is already asked; if the turn ends with the same words the answer is
 * kept, otherwise it is thrown away. The rules that make that honest (CLAUDE.md sec 7: no outcome
 * may be asserted or caused by a guess) are enforced here, not by the caller:
 * - **Nothing is spoken before confirmation.** Every listener event of a draft (text, read-through
 *   marks, first-part timing) is held and replayed, in order, only at [SpeculativeAnswer.adopt].
 * - **No tool runs before confirmation.** The loop waits at its `hold` before it announces or runs
 *   any tool call ([GeminiTextLoop.answer]); a draft that is discarded there runs nothing.
 * - **Every discarded call is costed.** A draft's usage lines are held too ([UsageHold]) and go out
 *   with `discarded=true` when it is dropped, even for a round that had finished.
 * - **Bounded waste.** One draft in flight per conversation (the caller keeps one), a minimum
 *   transcript ([SpeculationRules.longEnough]) and a setting to switch it off.
 *
 * Known gap: Google Search runs inside the model call itself and cannot be held; a draft that used
 * it has read the web before confirmation. Its read-through mark is applied only at adoption, and a
 * discarded draft's search result is never seen by anyone.
 */
internal object SpeculationRules {
    /** Under this many letters and digits there is nothing worth answering early ("um", "so"). */
    const val MIN_CHARS = 5

    /**
     * The cloud voice's early signal: this much silence after his last speech (the phone ends the
     * turn at [com.kevin.legion.service.turn.EndOfTurnPolicy.BASE_MS], 700 ms, or later).
     */
    const val CLOUD_SILENCE_MS = 300L

    /**
     * Most cloud drafts one utterance may start. Each likely end of turn that he then talks past
     * is a paid transcription and model call thrown away; past this the normal call still happens.
     */
    const val MAX_CLOUD_DRAFTS = 3

    /** How often the cloud silence check runs while he is speaking. */
    const val CLOUD_POLL_MS = 60L

    /** Under this much kept audio a draft transcription is not worth paying for. */
    const val CLOUD_MIN_AUDIO_BYTES = 400 * 32

    private val TRAILING = Regex("[\\s.,!?;:…]+$")
    private val SPACES = Regex("\\s+")

    /**
     * The words a draft was made from and the confirmed words are the same turn: identical after
     * dropping trailing punctuation and whitespace (Kyutai's flush adds the final `.` or `?`) and
     * collapsing runs of spaces. Anything else, a word or a digit more or less, is a different turn.
     */
    fun sameText(draft: String, confirmed: String): Boolean = norm(draft) == norm(confirmed)

    fun norm(s: String): String = SPACES.replace(TRAILING.replace(s.trim(), ""), " ")

    fun longEnough(text: String): Boolean = text.count { it.isLetterOrDigit() } >= MIN_CHARS
}

/**
 * One speculative turn in [CascadeSession]: the utterance it is for, what started it, and its parts.
 * On the laptop the words come from the service (`stt.maybe_end`); on the cloud voice a draft
 * transcription of the audio so far comes first ([stt]), and is the turn's transcript when he says
 * nothing more before the phone ends the turn ([audioConfirmed]).
 */
internal class TurnDraft(
    val utt: TurnUtterance,
    /** `eot` (the laptop decided, flushing), `maybe` (the laptop's lower threshold), `silence` (cloud). */
    val trigger: String,
    val startedAtMs: Long,
    /** Cloud: the time of his last speech when the audio was taken; later speech means a new turn. */
    val speechAtSnapshotMs: Long? = null,
) {
    @Volatile var heard: String? = null
    @Volatile var answer: SpeculativeAnswer? = null
    @Volatile var stt: Deferred<SttResult>? = null
    @Volatile var sttHold: UsageHold? = null
    @Volatile var audioConfirmed = false
    var historySize = -1
    var preamble = ""
    val resumed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Drops what is still speculative; a transcript already adopted stays billed as heard. */
    fun drop() {
        answer?.discard()
        if (!audioConfirmed) stt?.cancel()
        // A no-op when the transcript was already released as the turn's.
        sttHold?.release(discarded = true)
    }
}

/**
 * Usage lines held until a draft's fate is known, then released: as they were for a kept draft,
 * with `discarded=true` for a dropped one. After release, lines pass straight through (a dropped
 * draft's late cancel report is marked discarded too).
 */
internal class UsageHold(private val sink: (CallUsage) -> Unit) {
    private val held = mutableListOf<CallUsage>()
    private var released: Boolean? = null

    fun add(u: CallUsage) {
        val pass = synchronized(this) {
            when (released) {
                null -> {
                    held.add(u)
                    null
                }
                true -> u.copy(discarded = true)
                false -> u
            }
        }
        pass?.let(sink)
    }

    /** Releases what is held; [discarded] marks every held line. Only the first call counts. */
    fun release(discarded: Boolean) {
        val out = synchronized(this) {
            if (released != null) return
            released = discarded
            held.toList().also { held.clear() }
        }
        out.forEach { sink(if (discarded) it.copy(discarded = true) else it) }
    }
}

/**
 * One draft of the text model's answer: started on a guess ([start]), then either adopted as the
 * turn's answer ([adopt]) or dropped ([discard]). [loop] is a text loop whose usage reaches [usage]
 * (the caller builds it with the hold's [UsageHold.add]).
 */
internal class SpeculativeAnswer(
    private val scope: CoroutineScope,
    private val loop: GeminiTextLoop,
    private val usage: UsageHold,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class State { DRAFTING, ADOPTED, DISCARDED }

    private val state = AtomicReference(State.DRAFTING)
    private val gate = CompletableDeferred<Unit>()
    private val events = mutableListOf<(GeminiTextLoop.Listener) -> Unit>()
    @Volatile private var target: GeminiTextLoop.Listener? = null
    @Volatile private var executor: TextToolExecutor? = null
    @Volatile private var job: Deferred<GeminiTextLoop.Result>? = null

    /** When the draft's first text or call arrived (null: not yet). */
    @Volatile var firstPartAtMs: Long? = null
        private set

    /** Characters of text the draft has written so far (held, unheard). */
    @Volatile var draftChars = 0
        private set

    /** The draft stopped at a tool call and waits for confirmation. */
    @Volatile var heldAtTool = false
        private set

    val isDone: Boolean get() = job?.isCompleted == true

    /**
     * The draft's model call already ended in a failure (or threw). Such a draft is not adopted:
     * the caller drops it and makes the normal call, so the user is never told a failure that a
     * fresh call might not repeat.
     */
    @Volatile var failed = false
        private set
    val current: State get() = state.get()

    /** Why this draft may not be adopted at confirmation, or null: it was settled, or its call failed. */
    val unusableReason: String?
        get() = when {
            current != State.DRAFTING -> "stale"
            failed -> "draft_failed"
            else -> null
        }

    fun start(prefix: CascadePrefix, contents: JSONArray) {
        job = scope.async {
            val r = loop.answer(
                prefix,
                contents,
                TextToolExecutor { name, args, readThrough ->
                    // Only reachable after the hold, i.e. after adopt() set the real executor.
                    executor?.run(name, args, readThrough)
                        ?: JSONObject().put("success", false).put("message", "Not run: the turn was not confirmed.")
                },
                listener,
                hold = {
                    heldAtTool = true
                    gate.await()
                },
            )
            if (r.failure != null) failed = true
            r
        }.also { j ->
            j.invokeOnCompletion { if (it != null && it !is CancellationException) failed = true }
        }
    }

    /**
     * The turn is confirmed with the draft's words: everything held is replayed to [listener] in
     * order, tools may run (through [tools]), the held usage is released as billed, and the draft's
     * result is the turn's. Cancelling the caller cancels the draft.
     */
    suspend fun adopt(tools: TextToolExecutor, listener: GeminiTextLoop.Listener): GeminiTextLoop.Result {
        check(state.compareAndSet(State.DRAFTING, State.ADOPTED)) { "draft already ${state.get()}" }
        val adoptedAt = now()
        executor = tools
        synchronized(events) {
            events.forEach { it(listener) }
            events.clear()
            firstPartAtMs?.let { listener.onFirstPart(1, (it - adoptedAt).coerceAtLeast(0)) }
            target = listener
        }
        usage.release(discarded = false)
        gate.complete(Unit)
        val j = job ?: error("draft never started")
        try {
            return j.await()
        } catch (e: CancellationException) {
            j.cancel()
            throw e
        }
    }

    /** Drops the draft: nothing it wrote is heard, no tool it asked for runs. True if it was live. */
    fun discard(): Boolean {
        if (!state.compareAndSet(State.DRAFTING, State.DISCARDED)) return false
        job?.cancel()
        gate.cancel()
        usage.release(discarded = true)
        return true
    }

    /** Held until adoption; after it, straight through. */
    private val listener = object : GeminiTextLoop.Listener {
        private fun deliver(event: (GeminiTextLoop.Listener) -> Unit) {
            val direct = synchronized(events) {
                target ?: run {
                    events.add(event)
                    null
                }
            }
            direct?.let(event)
        }

        override fun onText(delta: String) {
            draftChars += delta.length
            deliver { it.onText(delta) }
        }

        override fun onToolCalls(names: List<String>) = deliver { it.onToolCalls(names) }

        override fun onToolsStarting(calls: List<Pair<String, JSONObject>>) = deliver { it.onToolsStarting(calls) }

        override fun onToolResult(name: String, args: JSONObject, response: JSONObject, ms: Long, writeDone: Boolean) =
            deliver { it.onToolResult(name, args, response, ms, writeDone) }

        override fun onReadThrough(source: String) = deliver { it.onReadThrough(source) }

        // A write's facts and code's line for it (2026-11-19): only after adoption (tools wait for it).
        override fun onWriteFact(fact: ScriptedLines.WriteFact) = deliver { it.onWriteFact(fact) }

        override fun onCodeLine(text: String, facts: List<ScriptedLines.WriteFact>) =
            deliver { it.onCodeLine(text, facts) }

        override fun onFirstPart(round: Int, ms: Long) {
            val direct = synchronized(events) {
                if (target == null && firstPartAtMs == null) {
                    // Replayed at adoption as "ms after confirmation", the number a turn's audit wants.
                    firstPartAtMs = now()
                    null
                } else {
                    target
                }
            }
            direct?.onFirstPart(round, ms)
        }
    }
}
