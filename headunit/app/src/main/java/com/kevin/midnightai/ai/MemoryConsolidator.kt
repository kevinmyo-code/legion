package com.kevin.midnightai.ai

import android.content.Context
import android.util.Log
import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.data.local.CompanionMemory
import com.kevin.midnightai.data.local.EpisodicTurn
import com.kevin.midnightai.service.ConversationState

/**
 * Companion-memory map, ticket 02 (2026-07-22): distills each finished Live
 * session's raw [EpisodicTurn] transcript into a handful of durable, scored,
 * sec-9.1-categorized [CompanionMemory] rows - "key events, not verbatim."
 * Same shape as the `foresight_notes` nightly-reasoner pattern: a background
 * [SubAgent] one-shot on the driver's own key, off the conversation path.
 *
 * **Trigger, and why it's NOT a time-since-last-turn idle check:** the
 * Cherokee's head unit loses power the moment the engine turns off (the same
 * fact behind the drive-sync loop's own comment
 * in [com.kevin.midnightai.service.AriaForegroundService] - "he cuts the key
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
        val db = CarDatabase.getDatabase(context)
        val turnDao = db.episodicTurnDao()
        val memoryDao = db.companionMemoryDao()

        for (sessionId in turnDao.pendingSessionIds()) {
            if (ConversationState.isBusy) return // a new conversation started mid-sweep
            val turns = turnDao.forSession(sessionId)
            if (turns.isEmpty()) continue // already cleared by a concurrent pass

            // A crisis conversation is dropped whole, never distilled (ticket #458,
            // [CrisisMemoryGate]). The session's write gate already keeps one out of this buffer;
            // this is what reaches rows a unit recorded before that gate existed, and what catches
            // one that raced the teardown. The sweep has already bailed if a conversation is live,
            // so this never runs under one; what it drops is the re-check the ordinary delete below
            // makes, which holds turns back for a conversation that started mid-sweep. These are
            // exactly the turns nothing may be given again, so they go either way.
            if (CrisisMemoryGate.forgetsSession(turns)) {
                Log.w(TAG, "crisis session $sessionId dropped, not consolidated")
                turnDao.deleteSession(sessionId)
                continue
            }

            val distilled = distill(turns)
            if (distilled == null) {
                // Model/network failure: leave the turns in place, try again next pass.
                // Not distinguishing failure reasons here (rate limit vs offline) - this
                // is an unattended background pass with no one to report to; it just retries.
                Log.w(TAG, "consolidation failed for session $sessionId, leaving turns for retry")
                continue
            }

            val vehicleId = turns.first().vehicleId
            val now = com.kevin.midnightai.time.TrustedClock.nowMs()
            for (m in distilled.memories) {
                memoryDao.insert(CompanionMemory(
                    vehicleId = vehicleId,
                    text = m.text,
                    category = m.category,
                    source = CompanionMemory.Source.CONSOLIDATED,
                    importance = m.importance,
                    createdAt = now,
                    lastAccessedAt = now,
                ))
            }
            if (ConversationState.isBusy) continue // don't delete turns a live convo might still need
            // Issue #452: what the next chat is told about this one, kept before its raw turns go.
            RecentChatStore.forContext(context).record(
                RecentChat(
                    vehicleId = vehicleId,
                    endedAtMs = turns.maxOf { it.timestamp },
                    summary = distilled.summary?.takeUnless(CrisisDetector::detect),
                    driverLines = RecentChatsNote.driverLines(turns),
                ),
            )
            turnDao.deleteSession(sessionId)
        }
    }

    /** One SubAgent one-shot per session; returns null on any failure (retry next pass). */
    private suspend fun distill(turns: List<EpisodicTurn>): ConsolidationReply.Parsed? {
        val transcript = turns.joinToString("\n") { t ->
            val speaker = if (t.role == EpisodicTurn.Role.DRIVER) "Driver" else "You"
            "$speaker: ${t.text}"
        }
        val result = agent().askTyped(context = transcript, question = DISTILL_QUESTION)
        val text = (result as? AgentResult.Success)?.text ?: return null
        return ConsolidationReply.parse(text)
    }

    // Issue #452: the same one-shot now also writes the chat's summary, which the next chat is
    // told (RecentChatsNote). A reply in the old bare-array shape still parses, with no summary.
    private const val DISTILL_QUESTION =
        "Distill this conversation. Respond with ONLY a raw JSON object (no markdown, no " +
            "commentary, no code fences) with two keys. \"summary\": two to four plain sentences, " +
            "in the third person, on what was talked about, what was decided and anything the " +
            "driver or you promised to do; one short sentence if nothing of substance was said. " +
            "\"memories\": an array of 0 to 5 objects, each with keys \"text\" (a short, " +
            "spoken-friendly memory in third person about the driver, e.g. \"Mentioned wanting an " +
            "LS swap eventually\"), \"importance\" (integer 1-10), and \"category\" (exactly one of " +
            "\"car_anchored\", \"driver\", \"relationship\")."

    // The rubric + category definitions. "You" in the transcript is this
    // companion's own turns - kept as "You" (not a name) since the identity
    // clause isn't relevant to distillation, only the content is.
    private val SYSTEM_INSTRUCTION = """
        You are distilling a conversation between a driver and their car companion into a small
        number of durable memories - key events worth remembering, not a transcript. Most ordinary
        chit-chat, small talk, and filler has NOTHING worth keeping - return an empty array for those.
        Only extract something a person would actually remember days or weeks later. The summary
        is different: it always says, briefly, what the conversation was about, using only what
        was said.

        Importance scale (1-10), for a CAR companion, be honest and use the full range:
        - 9-10: a named future plan or project (a specific mod, a planned trip), an explicit
          "remember this" request, or a significant life event mentioned.
        - 6-8: a real preference, a recurring theme, or a notable one-off fact worth resurfacing.
        - 3-5: a casual opinion or comment with some substance (a music take, a passing mood).
        - 1-2: near-filler; usually not worth a row at all - prefer omitting these entirely.

        Category (exactly one per memory, choose the narrowest honest fit):
        - "car_anchored": a fact about the CAR itself - its history, a mod discussed, service, a quirk.
        - "driver": a fact about the DRIVER - their preferences, routines, plans, taste - not about
          your relationship to them.
        - "relationship": specifically about the bond between you and the driver (a running joke
          between you two, something they said about trusting or relying on you). Use this narrowly -
          most things are "driver", not "relationship".

        Never invent anything not actually said. Never claim feelings, sentience, or a need for the
        driver on your own part - you are recording what happened, not narrating an emotional bond.
    """.trimIndent()
}
