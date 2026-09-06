package com.kevin.legion.ai

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.BackgroundPassState
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
 *
 * **CORRECTED 2026-09-06: that last clause was false, and had been since this class was written.**
 * It said the importance gate stopped a re-synthesis every five minutes; it did not, because the
 * "since last reflection" mark was derived from the newest [CompanionMemory.Source.REFLECTION] row.
 * A model that came back with an EMPTY insight list wrote no row, so the mark never advanced, the
 * same memories were still past it, the importance sum was still over threshold, and the identical
 * set went back to the model on the next pass and every pass after - 288 paid calls a day for an
 * answer already given. The model never had to fail; it only had to judge there was nothing worth
 * saying, which is the ordinary case once a cluster has already been reflected on.
 *
 * The mark now lives in [BackgroundPassState] and advances on any call that ANSWERED, empty
 * included, because an empty answer is a real answer about that exact set. A call that FAILED is
 * retried with a cap and an exponential backoff, then set aside with its reason. The A25's own
 * database is what found it: the newest reflection row is 2026-08-20 20:01:33Z, the active
 * vehicle's importance sum crossed the threshold at 2026-08-30 02:04:26Z, and the pass that pushed
 * it over ran in the same coroutine as a consolidation that DID write - so the loop was demonstrably
 * alive with the precondition true and never wrote anything again.
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
        // No key, no call (2026-09-06). This was NOT already true: synthesize() went straight to
        // SubAgent.askTyped, which builds a URL with an empty `key=` and fires a real HTTP request
        // that comes back 400 API_KEY_INVALID - a network round trip every five minutes, forever,
        // on an install that has never been configured. The gate belongs here rather than only in
        // SubAgent because the whole pass is pointless without a key, not just its last step.
        if (!GeminiKeyProvider.hasKey()) return
        val vehicleId = ActiveVehicle.current(context)
        val db = CarDatabase.getDatabase(context)
        val dao = db.companionMemoryDao()
        val passDao = db.backgroundPassStateDao()
        val passKey = BackgroundPassState.reflectionKey(vehicleId)
        val startedAt = System.currentTimeMillis()
        passDao.ensure(passKey, startedAt)

        val newConsolidated = dueMaterial(dao, passDao, vehicleId, passKey)
        if (newConsolidated.isEmpty()) return
        val effective = gate(passDao, passKey, newConsolidated, startedAt) ?: return

        val outcome = synthesize(newConsolidated)
        if (outcome is ModelPassOutcome.HardFailure) {
            // The key has nothing left to spend, or was rejected. Retrying is not "worth a go",
            // it is a guaranteed identical failure - so the pass stops on the FIRST one of these
            // rather than burning the five a soft failure gets. Kevin, 2026-09-06: "i ran out of
            // credits and im probably not gonna top up for a while."
            passDao.setAside(passKey, outcome.reason, startedAt)
            Log.w(TAG, "reflection set aside for $passKey: ${outcome.reason}")
            return
        }
        val insights = (outcome as? ModelPassOutcome.Answered)?.let { parseInsights(it.text) }
        if (insights == null) {
            // A SOFT failure, or an answer that would not parse: the material is still unreflected
            // and a retry is legitimate - but a bounded one, because an unparseable reply can also
            // repeat forever, and 288 passes a day of that is the shape that burned the credits.
            recordFailure(passDao, passKey, effective, startedAt)
            return
        }

        // ANSWERED. Advance the mark whether or not the answer had content in it: "there is no
        // pattern here worth stating" is a real result about this exact set of memories, and
        // paying for it twice buys nothing. The mark is the newest memory the call actually
        // considered, so material that arrives afterwards is still reflected on.
        passDao.recordSuccess(passKey, newConsolidated.maxOf { it.createdAt }, System.currentTimeMillis())
        if (insights.isEmpty()) return

        writeInsights(context, vehicleId, insights)
    }

    /**
     * The consolidated memories this pass would reflect on, or empty if there is nothing due.
     *
     * **The watermark is the LATER of the newest reflection row and the persisted mark, and the
     * persisted mark is the whole fix (2026-09-06).** Deriving it from written rows alone meant a
     * model that answered with an EMPTY insight list wrote nothing, so the mark never moved, the
     * same consolidated memories were still there, the importance sum was still over threshold, and
     * the identical set was re-synthesized every five minutes - 288 paid calls a day for an answer
     * already given. See [BackgroundPassState] for the phone evidence.
     */
    private suspend fun dueMaterial(
        dao: com.kevin.legion.data.local.CompanionMemoryDao,
        passDao: com.kevin.legion.data.local.BackgroundPassStateDao,
        vehicleId: String,
        passKey: String,
    ): List<CompanionMemory> {
        val lastReflectionAt = dao.bySource(vehicleId, CompanionMemory.Source.REFLECTION)
            .firstOrNull()?.createdAt ?: 0L
        val watermark = maxOf(lastReflectionAt, passDao.byKey(passKey)?.watermark ?: 0L)
        val newConsolidated = dao.bySource(vehicleId, CompanionMemory.Source.CONSOLIDATED)
            .filter { it.createdAt > watermark }
        val enough = newConsolidated.sumOf { it.importance } >= REFLECTION_IMPORTANCE_THRESHOLD
        return if (enough) newConsolidated else emptyList()
    }

    /**
     * Whether this pass may spend a call right now, and the retry state it should count against.
     * Null means no.
     *
     * **Fresh material clears an old verdict, and the order matters.** A pass set aside or backing
     * off against OLDER memories has been judged on an input that is not the one in front of it
     * now, so it earns a clean slate - checked BEFORE the set-aside and backoff gates, or a
     * set-aside pass could never be revived by the new input that might well succeed.
     */
    private suspend fun gate(
        passDao: com.kevin.legion.data.local.BackgroundPassStateDao,
        passKey: String,
        newConsolidated: List<CompanionMemory>,
        startedAt: Long,
    ): BackgroundPassState? {
        // ensure() ran a moment ago, so a null here means the row was deleted between the two
        // calls. A zeroed state is the right answer for that: never set aside, no backoff owed.
        val pass = passDao.byKey(passKey) ?: BackgroundPassState(passKey = passKey)
        val freshSince = newConsolidated.any { it.createdAt > pass.updatedAt }
        val effective = if (freshSince && (pass.isSetAside || pass.attempts > 0)) {
            passDao.clearRetryState(passKey, startedAt)
            passDao.byKey(passKey) ?: pass
        } else {
            pass
        }
        val blocked = effective.isSetAside || startedAt < effective.nextAttemptAt
        return if (blocked) null else effective
    }

    /** Writes the synthesized insights and their audit rows. Split out of [reflectIfDue] purely to
     *  keep that function inside detekt's length and complexity ceilings. */
    private suspend fun writeInsights(context: Context, vehicleId: String, insights: List<Insight>) {
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

    /**
     * Counts one failed call and parks the next attempt, setting the pass aside for good once it
     * has burned [BackgroundPassState.MAX_ATTEMPTS] in a row.
     *
     * The reason is stored in words rather than as a code, because the only reader is a person
     * looking at the Setup screen asking why reflection stopped, and "reflection kept failing" is
     * the answer to that question. Nothing here retries on its own: a set-aside pass wakes up only
     * when new consolidated material arrives past the watermark, which is a genuinely different
     * input and worth another go.
     */
    private suspend fun recordFailure(
        passDao: com.kevin.legion.data.local.BackgroundPassStateDao,
        passKey: String,
        pass: BackgroundPassState?,
        at: Long,
    ) {
        val attempts = (pass?.attempts ?: 0) + 1
        if (attempts >= BackgroundPassState.MAX_ATTEMPTS) {
            passDao.setAside(
                passKey,
                "Reflection failed $attempts times in a row - no new insights are being written " +
                    "for this car until more memories arrive.",
                at,
            )
            Log.w(TAG, "reflection set aside for $passKey after $attempts failures")
            return
        }
        passDao.recordFailure(passKey, at + BackgroundPassState.backoffMs(attempts), at)
    }

    private data class Insight(val text: String, val importance: Int, val category: String)

    /**
     * Test seam: stands in for the model round trip, taking the memory listing and returning the
     * model's RAW text, or null for "the call did not come back with an answer".
     *
     * **Deliberately at the raw-text level, not at the parsed-insight level.** The whole bug this
     * class was fixed for is the difference between a call that ANSWERED with nothing and a call
     * that FAILED, and those two are separated by [parseInsights] - `"[]"` is the first, garbage
     * is the second. A seam above the parser would let a test assert the branch while faking the
     * very thing that decides it. Null here means "use the real model call", which is why the
     * branch below reads the property once instead of using an elvis (a seam that RETURNS null is
     * simulating a failure, and must not fall through to a real network call).
     */
    internal var modelCallForTest: (suspend (String) -> ModelPassOutcome)? = null

    /**
     * The model round trip, classified into the three outcomes the retry policy distinguishes.
     * Parsing stays OUT of here: [reflectIfDue] parses [ModelPassOutcome.Answered]'s text itself,
     * so an empty answer and an unparseable one - which collapse into one value the moment they
     * share a nullable list - stay two different things all the way to the branch that treats
     * them differently.
     */
    private suspend fun synthesize(memories: List<CompanionMemory>): ModelPassOutcome {
        val listing = memories.joinToString("\n") { m -> "- (${m.category}, importance ${m.importance}) ${m.text}" }
        val seam = modelCallForTest
        if (seam != null) return seam(listing)
        return ModelPassOutcome.from(agent().askTyped(context = listing, question = REFLECT_QUESTION))
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
