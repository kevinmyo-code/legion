package com.kevin.legion.ai

import android.content.Context
import android.util.Log
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
        val db = CarDatabase.getDatabase(context)
        val turnDao = db.episodicTurnDao()
        val memoryDao = db.companionMemoryDao()
        val auditDao = db.memoryAuditDao()

        for (sessionId in turnDao.pendingSessionIds()) {
            if (ConversationState.isBusy) return // a new conversation started mid-sweep
            val turns = turnDao.forSession(sessionId)
            if (turns.isEmpty()) continue // already cleared by a concurrent pass

            val distilled = distill(turns)
            if (distilled == null) {
                // Model/network failure: leave the turns in place, try again next pass.
                // Not distinguishing failure reasons here (rate limit vs offline) - this
                // is an unattended background pass with no one to report to; it just retries.
                Log.w(TAG, "consolidation failed for session $sessionId, leaving turns for retry")
                continue
            }

            val vehicleId = turns.first().vehicleId
            val now = System.currentTimeMillis()
            for (m in distilled) {
                val id = memoryDao.insert(CompanionMemory(
                    vehicleId = vehicleId,
                    text = m.text,
                    category = m.category,
                    source = CompanionMemory.Source.CONSOLIDATED,
                    importance = m.importance,
                    createdAt = now,
                    lastAccessedAt = now,
                ))
                // Audit trail (2026-08-20): this pass runs unattended and writes durable memories
                // from a transcript it then DELETES, so without a line here a wrong memory has no
                // recoverable provenance at all.
                auditDao.record(
                    MemoryAudit.Event.WRITTEN,
                    MemoryAudit.Store.COMPANION,
                    "[${m.category}/consolidated] ${m.text}",
                    refId = id,
                    vehicleId = vehicleId,
                )
            }
            if (ConversationState.isBusy) continue // don't delete turns a live convo might still need
            turnDao.deleteSession(sessionId)
        }
    }

    private data class DistilledMemory(val text: String, val importance: Int, val category: String)

    /** One SubAgent one-shot per session; returns null on any failure (retry next pass). */
    private suspend fun distill(turns: List<EpisodicTurn>): List<DistilledMemory>? {
        val transcript = turns.joinToString("\n") { t ->
            val speaker = if (t.role == EpisodicTurn.Role.DRIVER) "User" else "You"
            "$speaker: ${t.text}"
        }
        val result = agent().askTyped(context = transcript, question = DISTILL_QUESTION)
        val text = (result as? AgentResult.Success)?.text ?: return null
        return parseMemories(text)
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
