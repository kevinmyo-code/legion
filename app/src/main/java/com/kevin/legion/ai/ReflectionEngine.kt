package com.kevin.legion.ai

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.data.local.MemoryAudit
import com.kevin.legion.data.local.record
import com.kevin.legion.service.ConversationState
import com.kevin.legion.vehicle.ActiveVehicle
import org.json.JSONArray

/**
 * Companion-memory map, ticket 05 (2026-07-22): the layer that turns recording
 * into UNDERSTANDING. Periodically synthesizes higher-order observations from
 * CLUSTERS of [CompanionMemory] rows - "we've circled back to the LS swap
 * three times," not just the three separate facts. Same background
 * Gemini-as-reasoner shape as [MemoryConsolidator], one level up: its input is
 * memories, not a raw transcript.
 *
 * **Feedback-loop guard, by construction, not by depth-tracking:** the input
 * pool is [CompanionMemory.Source.CONSOLIDATED] rows ONLY - reflections never
 * read other reflections. Reflecting-on-reflections cannot happen because the
 * query excludes them, not because of a runtime recursion check.
 *
 * **Trigger, mirroring [MemoryConsolidator]'s own lesson:** gated on
 * [ConversationState.isBusy] being false for the same reason (the head unit
 * loses power on engine-off, so a pure time-idle trigger routinely never
 * fires). On top of that, reflection ALSO waits for enough new material -
 * the paper's own "accumulated importance since last reflection" signal - so
 * it doesn't re-synthesize the same handful of facts every five minutes.
 */
object ReflectionEngine {
    private const val TAG = "ReflectionEngine"

    // Sum of new CONSOLIDATED memories' importance since the last reflection
    // must cross this before reflecting again. ~30 is roughly 4-6 mid-
    // importance memories, or fewer high-importance ones - the paper's own
    // spirit ("enough has happened"), not tuned against real usage data yet.
    private const val REFLECTION_IMPORTANCE_THRESHOLD = 30

    private fun agent() = SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false)

    /**
     * Reflects on the active car's memories if enough new material has
     * accumulated since the last reflection. No-ops entirely while a
     * conversation is live, same correctness gate as [MemoryConsolidator].
     * Scoped to the ACTIVE vehicle only (not every car this device has ever
     * seen) - reflecting on a car that isn't the one being driven right now
     * has no clear payoff and adds cross-car complexity for no benefit yet.
     */
    suspend fun reflectIfDue(context: Context) {
        if (ConversationState.isBusy) return
        val vehicleId = ActiveVehicle.current(context)
        val dao = CarDatabase.getDatabase(context).companionMemoryDao()

        val lastReflectionAt = dao.bySource(vehicleId, CompanionMemory.Source.REFLECTION)
            .firstOrNull()?.createdAt ?: 0L
        val newConsolidated = dao.bySource(vehicleId, CompanionMemory.Source.CONSOLIDATED)
            .filter { it.createdAt > lastReflectionAt }
        if (newConsolidated.isEmpty()) return
        if (newConsolidated.sumOf { it.importance } < REFLECTION_IMPORTANCE_THRESHOLD) return

        val insights = synthesize(newConsolidated) ?: return // failure: try again next pass, nothing lost
        if (insights.isEmpty()) return // model judged nothing worth synthesizing yet

        if (ConversationState.isBusy) return // a conversation started while the model call was in flight
        val now = System.currentTimeMillis()
        val auditDao = CarDatabase.getDatabase(context).memoryAuditDao()
        for (insight in insights) {
            val written = com.kevin.legion.backend.MemoryWriteThrough.addCompanionMemory(
                context,
                CompanionMemory(
                    vehicleId = vehicleId,
                    text = insight.text,
                    category = insight.category,
                    source = CompanionMemory.Source.REFLECTION,
                    importance = insight.importance,
                    createdAt = now,
                    lastAccessedAt = now,
                    updatedAtMs = now,
                ),
            )
            val id = written.id
            // Audit trail (2026-08-20). Reflection is the pass most worth auditing: it writes a
            // memory synthesized from OTHER memories rather than from anything the driver said, so
            // it is the one place a plausible-sounding claim can enter the record with no external
            // anchor behind it at all.
            auditDao.record(
                MemoryAudit.Event.WRITTEN,
                MemoryAudit.Store.COMPANION,
                "[${insight.category}/reflection] ${insight.text}",
                refId = id,
                vehicleId = vehicleId,
            )
        }
    }

    private data class Insight(val text: String, val importance: Int, val category: String)

    private suspend fun synthesize(memories: List<CompanionMemory>): List<Insight>? {
        val listing = memories.joinToString("\n") { m -> "- (${m.category}, importance ${m.importance}) ${m.text}" }
        val result = agent().askTyped(context = listing, question = REFLECT_QUESTION)
        val text = (result as? AgentResult.Success)?.text ?: return null
        return parseInsights(text)
    }

    private fun parseInsights(raw: String): List<Insight>? {
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
                val importance = o.optInt("importance", 6).coerceIn(1, 10)
                Insight(text, importance, category)
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse reflection output: ${e.message}")
            null
        }
    }

    private val VALID_CATEGORIES = setOf(
        CompanionMemory.Category.CAR_ANCHORED,
        CompanionMemory.Category.DRIVER,
        CompanionMemory.Category.RELATIONSHIP,
    )

    private const val REFLECT_QUESTION =
        "Look across these memories for a PATTERN or higher-order insight that isn't obvious from " +
            "any single one alone - something only visible from the cluster. Respond with ONLY a raw " +
            "JSON array (no markdown, no commentary, no code fences) of 0 to 2 objects, each with " +
            "keys \"text\" (a short, spoken-friendly synthesis, e.g. \"Keeps circling back to the LS " +
            "swap - seems serious about it this time\"), \"importance\" (integer 1-10 - a real " +
            "synthesis is usually MORE significant than any single fact it's drawn from, so lean " +
            "high when you find a genuine pattern), and \"category\" (exactly one of \"car_anchored\", " +
            "\"driver\", \"relationship\"). If nothing genuinely connects across these memories, " +
            "return an empty array - do not force a pattern that isn't really there."

    private val SYSTEM_INSTRUCTION = """
        You are looking for patterns across a companion's memories of one user - recurring
        themes, escalating interest, contradictions, or things that only become visible once you see
        several memories together. This is NOT summarizing each memory - it's noticing what the
        COLLECTION reveals that no single memory does.

        Category (exactly one per insight, choose the narrowest honest fit):
        - "car_anchored": a pattern about the CAR - its history, recurring maintenance, mod interest.
        - "driver": a pattern about the USER - preferences, routines, recurring plans - not about
          your relationship to them.
        - "relationship": specifically about the pattern of the bond between you and the user. Use
          this narrowly and honestly - most patterns are about the user's own interests or life, not
          your relationship with them.

        Be conservative: most small clusters of memories have NOTHING genuinely new to say beyond
        what's already in each memory - return an empty array rather than manufacture a pattern.
        Never invent anything not supported by the memories given. Never claim feelings, sentience, or
        a need for the driver on your own part - you are noticing a pattern, not narrating an
        emotional bond.
    """.trimIndent()
}
