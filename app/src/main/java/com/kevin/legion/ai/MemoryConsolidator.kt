package com.kevin.legion.ai

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.BackgroundPassState
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.data.local.EpisodicTurn
import com.kevin.legion.data.local.MemoryAudit
import com.kevin.legion.data.local.record
import com.kevin.legion.service.ConversationState
import org.json.JSONArray

/**
 * Companion-memory map, ticket 02 (2026-07-22): distills each finished Live
 * session's raw [EpisodicTurn] transcript into a handful of durable, scored,
 * sec-9.1-categorized [CompanionMemory] rows - "key events, not verbatim."
 * Same shape as the `foresight_notes` nightly-reasoner pattern: a background
 * [SubAgent] one-shot on the driver's own key, off the conversation path.
 *
 * **Trigger, and why it's NOT a time-since-last-turn idle check:** the
 * Cherokee's head unit loses power the moment the engine turns off (the same
 * fact that shapes the wake-word design and the drive-sync loop's own comment
 * in [com.kevin.legion.service.AriaForegroundService] - "he cuts the key
 * on arrival and the unit powers down before any end-of-drive work could
 * run"). A periodic idle-timeout trigger would frequently never get the
 * chance to fire before the process dies. So the real gate is simpler and
 * survives that: [ConversationState.isBusy] is false, meaning nothing can
 * currently be appending turns, so EVERY pending session is safe to
 * consolidate right now, regardless of how old it is. The caller decides
 * WHEN to check that gate - see [runOnStartup]/[runPeriodically]'s doc for
 * the two call sites this needs (startup catch-up + a running loop), because
 * "engine off = instant kill" means a purely-periodic call site would miss
 * whatever was still pending when the car last shut off.
 */
object MemoryConsolidator {
    private const val TAG = "MemoryConsolidator"

    private fun agent() = SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false)

    /**
     * Consolidates every currently-pending session, oldest first. No-ops
     * entirely (skips the whole sweep) if a conversation is live - see the
     * class doc for why that's the correctness gate, not a staleness
     * timestamp. Re-checks the gate before each session's destructive delete
     * too (a conversation could start mid-sweep); a session caught mid-sweep
     * this way is just picked up again next pass; nothing is lost, since
     * turns are only deleted after a successful distill+write.
     */
    suspend fun consolidatePending(context: Context) {
        if (ConversationState.isBusy) return
        // No key, no call (2026-09-06). This was NOT already true: distill() went straight to
        // SubAgent.askTyped, which builds a URL with an empty `key=` and fires a real HTTP request
        // per pending session, every five minutes, forever, on an install that has never been
        // configured. Checked once for the whole sweep rather than per session.
        if (!GeminiKeyProvider.hasKey()) return
        val db = CarDatabase.getDatabase(context)

        for (sessionId in db.episodicTurnDao().pendingSessionIds()) {
            // A new conversation started mid-sweep, or a hard failure every remaining session
            // would repeat identically. `break` rather than `return` so there is one exit.
            if (ConversationState.isBusy) break
            if (consolidateSession(context, db, sessionId) == SweepStep.STOP) break
        }
    }

    /** Whether the sweep should carry on to the next pending session, or stop entirely. */
    private enum class SweepStep { CONTINUE, STOP }

    /**
     * One pending session's worth of work. Split out of [consolidatePending] to keep that function
     * inside detekt's complexity ceiling, and because "what happens to one session" is the unit
     * the retry policy actually reasons about.
     *
     * Returns [SweepStep.STOP] only for a hard failure - a key with no quota or a rejected key -
     * because every remaining session in the sweep would fail identically. That is the difference
     * between one wasted call and one per conversation the phone has ever had, every five minutes.
     */
    private suspend fun consolidateSession(
        context: Context,
        db: CarDatabase,
        sessionId: String,
    ): SweepStep {
        val passDao = db.backgroundPassStateDao()
        val turns = db.episodicTurnDao().forSession(sessionId)
        val passKey = BackgroundPassState.consolidationKey(sessionId)
        val startedAt = System.currentTimeMillis()
        // Empty turns means a concurrent pass already cleared this session; a false mayAttempt
        // means it is set aside or still inside its backoff window. Either way, nothing to spend.
        if (turns.isEmpty() || !mayAttempt(passDao, passKey, startedAt)) return SweepStep.CONTINUE
        return attemptSession(context, db, sessionId, turns, passKey, startedAt)
    }

    /**
     * Whether this session may cost a call right now. Creates the retry row if it is missing, so
     * the caller can rely on one existing.
     */
    private suspend fun mayAttempt(
        passDao: com.kevin.legion.data.local.BackgroundPassStateDao,
        passKey: String,
        startedAt: Long,
    ): Boolean {
        passDao.ensure(passKey, startedAt)
        val pass = passDao.byKey(passKey) ?: return true
        return !pass.isSetAside && startedAt >= pass.nextAttemptAt
    }

    /** The part of [consolidateSession] that actually spends a call. */
    private suspend fun attemptSession(
        context: Context,
        db: CarDatabase,
        sessionId: String,
        turns: List<EpisodicTurn>,
        passKey: String,
        startedAt: Long,
    ): SweepStep {
        val passDao = db.backgroundPassStateDao()
        val outcome = distill(turns)
        if (outcome is ModelPassOutcome.HardFailure) {
            passDao.setAside(passKey, outcome.reason, startedAt)
            Log.w(TAG, "consolidation sweep stopped: ${outcome.reason}")
            return SweepStep.STOP
        }
        val distilled = (outcome as? ModelPassOutcome.Answered)?.let { parseMemories(it.text) }
        if (distilled == null) {
            // A SOFT failure, or an answer that would not parse - and NOT the same thing as a
            // model that answered with nothing: an empty distill is a real answer and takes the
            // else branch, writing no memories and then deleting the turns, because a transcript
            // the model has read and found nothing durable in is finished with.
            Log.w(TAG, "consolidation failed for session $sessionId, leaving turns for retry")
            recordFailure(passDao, passKey, passDao.byKey(passKey), sessionId, startedAt)
        } else {
            writeMemories(context, db, turns.first().vehicleId, distilled)
            // Don't delete turns a conversation that started mid-call might still need; this
            // session is simply picked up again next pass.
            if (!ConversationState.isBusy) {
                db.episodicTurnDao().deleteSession(sessionId)
                // The unit of work is done and its turns are gone, so its retry row is dead
                // weight. Without this the table would grow by one row per conversation, forever.
                passDao.forget(passKey)
            }
        }
        return SweepStep.CONTINUE
    }

    /** Writes one session's distilled memories and their audit rows. */
    private suspend fun writeMemories(
        context: Context,
        db: CarDatabase,
        vehicleId: String,
        distilled: List<DistilledMemory>,
    ) {
        val auditDao = db.memoryAuditDao()
        val now = System.currentTimeMillis()
        for (m in distilled) {
            val written = com.kevin.legion.backend.MemoryWriteThrough.addCompanionMemory(
                context,
                CompanionMemory(
                    vehicleId = vehicleId,
                    text = m.text,
                    category = m.category,
                    source = CompanionMemory.Source.CONSOLIDATED,
                    importance = m.importance,
                    createdAt = now,
                    lastAccessedAt = now,
                    updatedAtMs = now,
                ),
            )
            // Audit trail (2026-08-20): this pass runs unattended and writes durable memories from
            // a transcript it then DELETES, so without a line here a wrong memory has no
            // recoverable provenance at all.
            auditDao.record(
                MemoryAudit.Event.WRITTEN,
                MemoryAudit.Store.COMPANION,
                "[${m.category}/consolidated] ${m.text}",
                refId = written.id,
                vehicleId = vehicleId,
            )
        }
    }

    /**
     * Counts one failed distill and parks the next attempt, setting the session aside for good
     * once it has burned [BackgroundPassState.MAX_ATTEMPTS] in a row.
     *
     * **Set aside, not deleted.** The turns stay in `episodic_turns` and the reason is recorded
     * beside them, the same skip-and-record shape
     * [com.kevin.legion.backend.ChecklistsBackfill] uses for a row the engine refuses: a
     * transcript that cannot be distilled is still the only record of that conversation, and
     * throwing it away to tidy up the loop would destroy the evidence of why the loop stopped.
     * The cost of keeping it is a few rows; the cost of the old behaviour was a paid model call
     * every five minutes forever.
     */
    private suspend fun recordFailure(
        passDao: com.kevin.legion.data.local.BackgroundPassStateDao,
        passKey: String,
        pass: BackgroundPassState?,
        sessionId: String,
        at: Long,
    ) {
        val attempts = (pass?.attempts ?: 0) + 1
        if (attempts >= BackgroundPassState.MAX_ATTEMPTS) {
            passDao.setAside(
                passKey,
                "A conversation transcript could not be summarised after $attempts tries. Its " +
                    "turns are kept, not deleted, and nothing more is spent on it.",
                at,
            )
            Log.w(TAG, "consolidation set aside for session $sessionId after $attempts failures")
            return
        }
        passDao.recordFailure(passKey, at + BackgroundPassState.backoffMs(attempts), at)
    }

    private data class DistilledMemory(val text: String, val importance: Int, val category: String)

    /**
     * Test seam: stands in for the model round trip, taking the assembled transcript and returning
     * the model's RAW text, or null for "the call did not come back with an answer". Same
     * raw-text-not-parsed-result reasoning as [ReflectionEngine.modelCallForTest] - see that
     * property's doc, since the answered-with-nothing versus failed distinction this class now
     * depends on is drawn by [parseMemories], below the seam.
     */
    internal var modelCallForTest: (suspend (String) -> ModelPassOutcome)? = null

    /**
     * One SubAgent one-shot per session, classified into the three outcomes the retry policy
     * distinguishes. Parsing stays OUT of here for the same reason it does in
     * [ReflectionEngine.synthesize] - see that function's doc.
     */
    private suspend fun distill(turns: List<EpisodicTurn>): ModelPassOutcome {
        val transcript = turns.joinToString("\n") { t ->
            val speaker = if (t.role == EpisodicTurn.Role.DRIVER) "User" else "You"
            "$speaker: ${t.text}"
        }
        val seam = modelCallForTest
        if (seam != null) return seam(transcript)
        return ModelPassOutcome.from(agent().askTyped(context = transcript, question = DISTILL_QUESTION))
    }

    private fun parseMemories(raw: String): List<DistilledMemory>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return null
        return try {
            val arr = JSONArray(raw.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").trim()
                if (text.isBlank()) return@mapNotNull null
                val category = o.optString("category").trim().lowercase()
                if (category !in VALID_CATEGORIES) return@mapNotNull null
                val importance = o.optInt("importance", 5).coerceIn(1, 10)
                DistilledMemory(text, importance, category)
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse consolidation output: ${e.message}")
            null
        }
    }

    private val VALID_CATEGORIES = setOf(
        CompanionMemory.Category.CAR_ANCHORED,
        CompanionMemory.Category.DRIVER,
        CompanionMemory.Category.RELATIONSHIP,
    )

    private const val DISTILL_QUESTION =
        "Distill this conversation into durable memories. Respond with ONLY a raw JSON array (no " +
            "markdown, no commentary, no code fences) of 0 to 5 objects, each with keys \"text\" " +
            "(a short, spoken-friendly memory in third person about the user, e.g. \"Mentioned " +
            "wanting an LS swap eventually\"), \"importance\" (integer 1-10), and \"category\" " +
            "(exactly one of \"car_anchored\", \"driver\", \"relationship\")."

    // The rubric + category definitions. "You" in the transcript is this
    // companion's own turns - kept as "You" (not a name) since the identity
    // clause isn't relevant to distillation, only the content is.
    private val SYSTEM_INSTRUCTION = """
        You are distilling a conversation between a driver and their car companion into a small
        number of durable memories - key events worth remembering, not a transcript. Most ordinary
        chit-chat, small talk, and filler has NOTHING worth keeping - return an empty array for those.
        Only extract something a person would actually remember days or weeks later.

        Importance scale (1-10), for a CAR companion, be honest and use the full range:
        - 9-10: a named future plan or project (a specific mod, a planned trip), an explicit
          "remember this" request, or a significant life event mentioned.
        - 6-8: a real preference, a recurring theme, or a notable one-off fact worth resurfacing.
        - 3-5: a casual opinion or comment with some substance (a music take, a passing mood).
        - 1-2: near-filler; usually not worth a row at all - prefer omitting these entirely.

        Category (exactly one per memory, choose the narrowest honest fit):
        - "car_anchored": a fact about the CAR itself - its history, a mod discussed, service, a quirk.
        - "driver": a fact about the USER - their preferences, routines, plans, taste - not about
          your relationship to them.
        - "relationship": specifically about the bond between you and the user (a running joke
          between you two, something they said about trusting or relying on you). Use this narrowly -
          most things are "driver", not "relationship".

        Never invent anything not actually said. Never claim feelings, sentience, or a need for the
        user on your own part - you are recording what happened, not narrating an emotional bond.
    """.trimIndent()
}
