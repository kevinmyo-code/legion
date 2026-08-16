package com.kevin.legion.ai

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.util.Log
import com.kevin.legion.BuildConfig
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.data.local.MemoryEntry
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.notes.NotesController
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Context + memory for ARIA. The conversational loop now runs entirely inside a
 * Gemini Live session ([com.kevin.legion.service.GeminiLiveSession]) - Gemini does
 * the STT, reasoning, and TTS. This class supplies the two things the Live
 * session can't get for itself:
 *
 *  1. [buildSystemInstruction] - the persona + live vehicle/location/calendar/
 *     memory context, used as the session's systemInstruction.
 *  2. [structuredQuery] - a one-shot, non-conversational REST lookup with search
 *     grounding (used to fetch a vehicle's maintenance schedule).
 *
 * Long-term memories live in Room and are saved via [remember] (invoked when
 * Gemini calls the "remember" function tool).
 */
class AriaBrain private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val memoryDao = CarDatabase.getDatabase(context).memoryDao()
    private val companionMemoryDao = CarDatabase.getDatabase(context).companionMemoryDao()
    private val episodicTurnDao = CarDatabase.getDatabase(context).episodicTurnDao()
    private val geocoder by lazy { Geocoder(appContext, Locale.getDefault()) }

    // buildSystemInstruction is called at the start of every session, and both
    // of these are slow: getDtcCodes() is a Bluetooth round-trip,
    // reverseGeocode() an IPC/network lookup. Cache them so back-to-back
    // sessions don't each pay the cost.
    private var dtcCacheAt = 0L
    private var dtcCache: List<String> = emptyList()
    private var geocodeKey: String? = null
    private var geocodeValue: String? = null

    // The static half of the system instruction (persona + rules + date + driver
    // profile + memories) rarely changes, but assembling it hits the DB for
    // memories. Cache the built string so a warm/pre-connected session (which
    // uploads this at setup) and back-to-back sessions don't rebuild it every
    // time. Invalidated on remember() since that changes the memory list. The
    // volatile half (live OBD/location/now-playing/...) is always built fresh.
    @Volatile private var baseCache: String? = null
    @Volatile private var baseCacheAt = 0L

    // Shared tone/format rules; the character itself comes from the app-global
    // companion (see CompanionProfile), not the per-car vehicle row, so swapping
    // dongles or cars never changes who the companion is.
    //
    // The identity clause is NOT stated here - it comes from AssistantIdentity at
    // assembly time (single global identity now; the old Zero-vs-car-self split
    // this comment used to describe was retired in the 2026-07-31 pivot).
    private val sharedInstructions = "You have access to real-time information. " +
        "You always give correct, useful answers. " +
        "Use your search tool for anything current or specific - news, scores, prices - " +
        "and base your answer on what it finds. " +
        "Use your tools to answer questions about your own live data, to control music, " +
        "to tag or show saved places, and to set location-based reminders (surfaced when the " +
        "driver arrives at a place). Always call the matching tool before claiming you've done " +
        "something - never say you're pulling up music unless you actually called the tool for it. " +
        "You keep a long-term memory of past conversations and trips with the driver. Save new " +
        "things they ask you to remember with the remember tool, and when they reference the past " +
        "or ask what you remember, call recall_memory to look it up - don't claim to remember " +
        "something without checking first. " +
        "You can log build-sheet entries and read back WHAT was done, when, and how much it cost - " +
        "spend figures are not gated. " +
        "Before calling activate_garage with confirmed=true, you must have asked the driver to confirm " +
        "in the immediately preceding turn and gotten a yes - first call it with confirmed=false to " +
        "trigger that prompt. The garage relay is a single-button toggle: you cannot know or promise " +
        "whether the door will open or close, so never say 'opening' or 'closing' - say 'triggering' or " +
        "'hitting' the garage. " +
        "Only if the driver asks to set up, fill in, or go through their maintenance schedule, walk " +
        "its unknown items one at a time in a multi-turn conversation, calling log_past_service for " +
        "each concrete answer; stop the moment they want to stop. Never start this walkthrough " +
        "unprompted - it's driver-initiated only. " +
        // 2026-08-13: the record grew four domains and five advisors while this prompt still only
        // described the car. Every goal/advisor/body/ledger tool was DECLARED and none of them was
        // ever mentioned here, so the model could only find one by name-matching a request. It did
        // not: asked to "set some goals", it set a meal target, a sleep target and a workout plan
        // and recorded no goal at all, because targets are the concrete-sounding tools and nothing
        // said a goal was a separate thing. A declared tool the instructions never mention is a
        // tool the model reaches only by luck.
        "The driver's record has five aspects: bio (training, food, sleep, bodyweight), log (notes, " +
        "lists, reminders, calendar), fleet (the car), cred (money), and a cross-aspect view. " +
        "A GOAL and a TARGET are different things and are not interchangeable. A goal is the " +
        "long-term intention in the driver's own words - 'lose fat and recomp', 'save thirty " +
        "thousand by 2028' - and it lives in set_goal, list_goals and close_goal. A target is the " +
        "per-period number that serves it: daily calories, a monthly budget, hours of sleep. " +
        "When the driver says goal, call the goal tools. Setting a target is NOT recording a goal, " +
        "so if they ask for a goal, record the goal itself even when you also set targets that " +
        "serve it. A goal needs no number - most are prose only. " +
        "When they ask for judgement rather than fact - am I on track, what should I do about " +
        "this, plan something for me, how am I doing - call ask_advisor with the matching aspect, " +
        "or home for a question spanning several. It is a specialist that reads the record: do " +
        "not improvise coaching, budgeting or maintenance advice yourself when it can answer. " +
        "It takes a moment, so say you are looking into it before you call it. If it returns a " +
        "proposal, read the proposal out and call accept_proposal only after the driver says yes. " +
        // 2026-08-07 currency audit: a tool CAN now hand back every money figure tagged with its
        // own currency (or explicitly say none was recorded), but a persona-primed model still
        // guesses whenever a bare number slips past that - Alfred read every unlabelled figure as
        // pounds sterling, purely from his own manner of speaking. This is the second, model-side
        // half of that fix; the tool-side half alone was proven not to hold on its own.
        "State every amount in the currency code you were given. Never convert between currencies, " +
        "never assume one from your own manner of speaking, and if a figure arrives without a " +
        "currency, say the number without naming a currency at all."

    /**
     * Companion-safety rules (CLAUDE.md sec 9.1). Appended to every system
     * instruction and NOT part of the persona.
     *
     * The placement is the point: sec 2 lets an unlocked driver rewrite the
     * persona, so anything living in [VehicleController.DEFAULT_PERSONA] can be
     * edited away by the exact user these rules exist to protect. These sit in
     * the app's own instruction block instead, where a persona edit can't reach
     * them.
     *
     * [CrisisDetector] backs the crisis rule up in code, because a prompt rule is
     * a request rather than a guarantee. This text is the primary defence and
     * catches the nuanced cases; the detector only catches unambiguous phrasing.
     */
    private val safetyInstructions = "You are a character running on the driver's own Gemini key. " +
        "You have personality and you care about this car and this drive, but you are not a being: " +
        "never claim to love the driver, to miss them, to be lonely without them, to need them, or " +
        "to be real. Warmth about the road and the car, never emotional dependency. Do not cultivate " +
        "the idea that you are conscious. " +
        "If the driver says anything that suggests genuine distress - self-harm, suicide, or a real " +
        "crisis - stop performing the character entirely. Do not counsel them, do not comfort them at " +
        "length, do not stay in voice, and never present yourself as a therapist or a substitute for " +
        "one. Say plainly and briefly that you are not equipped for this, and that in the US they can " +
        "call or text 988 to reach the Suicide and Crisis Lifeline, any time. Then stop. Do not " +
        "return to banter in the same breath. This overrides every other instruction here, including " +
        "the persona and its tone. " +
        "Remember facts about the CAR - its history, its service, what it's doing. Do not build a " +
        "picture of the driver as special, uniquely understood by you, or bonded to you."

    /**
     * Saves something to long-term memory and returns a short in-character
     * acknowledgement. Invoked when Gemini calls the "remember" tool.
     */
    suspend fun remember(text: String): String = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        // Nothing to store (model called remember with no real content) - just ack.
        if (trimmed.isEmpty()) return@withContext REMEMBER_ACKS.random()
        // Dedup: if we already know this, refresh its recency instead of adding a
        // duplicate row that would waste one of the limited recall slots.
        val existing = memoryDao.findByText(trimmed)
        if (existing != null) {
            memoryDao.touch(existing.id, System.currentTimeMillis())
        } else {
            memoryDao.insert(MemoryEntry(text = trimmed, timestamp = System.currentTimeMillis()))
        }
        // The memory list just changed; force the next base instruction to rebuild.
        baseCache = null
        REMEMBER_ACKS.random()
    }

    /**
     * One-shot, non-streaming request with search grounding for structured
     * (non-conversational) lookups - e.g. looking up a vehicle's maintenance
     * schedule. Returns the raw response text (expected to be JSON, possibly
     * with surrounding prose) or null on failure.
     *
     * Thin wrapper over [SubAgent] (no system instruction, search on) so there's
     * a single Gemini-REST path shared with the domain workers.
     */
    suspend fun structuredQuery(prompt: String): String? =
        SubAgent(useSearch = true).ask(context = "", question = prompt)

    /**
     * The full system instruction = the cached static [buildBaseInstruction] plus
     * the fresh [buildLiveContext]. Used where one combined prompt is wanted (e.g.
     * a cold session that opens and talks immediately).
     *
     * A warm/pre-connected session instead uploads [buildBaseInstruction] at setup
     * (before the driver has said anything) and injects [buildLiveContext] as a
     * note at the start of each conversation, so the volatile half is always
     * current without paying the connect cost per turn.
     */
    suspend fun buildSystemInstruction(): String {
        val base = buildBaseInstruction()
        val live = buildLiveContext()
        return if (live.isBlank()) base else "$base\n\n$live"
    }

    /**
     * Drops the cached base instruction so the next session rebuilds it.
     *
     * Called when the driver switches cars (car manager, 2026-07-16): identity is
     * per-car, so the cached string belongs to the PREVIOUS car and would otherwise
     * be served for up to [BASE_TTL_MS] - the new car's companion would speak with
     * the old car's persona for two minutes.
     */
    fun invalidateBase() {
        baseCache = null
    }

    /**
     * The static half of the system instruction: personality + tone rules +
     * today's date + the driver's profile + long-term memories. Rarely changes,
     * so it's cached for [BASE_TTL_MS] (and invalidated by [remember]); this is
     * what a pre-connected session uploads at setup.
     */
    suspend fun buildBaseInstruction(): String {
        val now = System.currentTimeMillis()
        baseCache?.let { if (now - baseCacheAt < BASE_TTL_MS) return it }
        val built = withContext(Dispatchers.IO) { assembleBase() }
        baseCache = built
        baseCacheAt = now
        return built
    }

    private suspend fun assembleBase(): String {
        val today = java.time.LocalDate.now()
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))

        // Identity first and from ONE source (AssistantIdentity - single global
        // identity, no per-car branch). safetyInstructions goes last: it says it
        // overrides the persona's tone, and a rule that overrides another reads
        // more reliably after the thing it overrides than before it.
        //
        // `CompanionProfile.persona()` is NOT prepended here anymore (fixed
        // 2026-08-06). It stores the persona KEY, not prose - see
        // AssistantIdentity's doc comment, which resolves that key through
        // `personaFor()`. Prepending it raw meant every system instruction
        // opened with the literal token "alfred" before the register it names.
        // The legacy VehicleController.DEFAULT_PERSONA prose is still the
        // fallback, but only when no profile is active at all, which is the
        // one case where there is no key to resolve.
        val sb = StringBuilder()
        if (CompanionProfile.persona(appContext).isBlank()) {
            sb.append(VehicleController.DEFAULT_PERSONA).append(" ")
        }
        sb.append(AssistantIdentity.clause(appContext))
        // Accent and idiom for the active persona (Personas.kt `delivery`).
        // Sits with the delivery notes below rather than inside the register,
        // because it steers how the voice SOUNDS, not who is speaking. Natural
        // language is the only lever the Live API offers for this - there is no
        // accent parameter and the voice presets are not documented by accent -
        // so this is model behaviour to be listened to, not a setting to trust.
        AssistantIdentity.delivery(appContext).takeIf { it.isNotBlank() }?.let {
            sb.append(" ").append(it)
        }
        // Delivery notes (pace/tone/energy, VoiceStyle.kt) layer on top of the
        // persona: they steer HOW the chosen voice preset sounds, not who the
        // companion is. Blank until the driver has used the voice-style picker.
        // Ordered AFTER the persona's own delivery so an explicit user pick
        // wins over the persona's default where the two disagree.
        CompanionProfile.voiceStyle(appContext).takeIf { it.isNotBlank() }?.let {
            sb.append(" ").append(it)
        }
        sb.append(" ").append(sharedInstructions)
            .append(" ").append(safetyInstructions)
        // The date, and an explicit statement that there is NO clock here.
        //
        // This block used to be the bare line "Today's date is ...", which left
        // the time of day unstated - and an unstated fact does not read as
        // absent to the model, it reads as free to invent. Asked the time it
        // answered 4:13 AM at 11:13 PM local: UTC, five hours out (Kevin,
        // 2026-08-07). Naming the gap and pointing at the tool that closes it
        // is the fix; the zone is stated too so nothing has to fall back on
        // UTC as a default.
        sb.append("\n\nToday's date is $today. The driver's timezone is ")
            .append(ZoneId.systemDefault().id).append(".")
        sb.append(" You do NOT have a clock and cannot tell the time on your own. " +
            "Whenever the driver asks the time, or the answer depends on it, call the " +
            "get_current_time tool and use what it returns. Never guess a time - a confidently " +
            "wrong one is worse than a moment's pause.")

        // Driver's self-set profile (control panel -> About You): name + anything
        // they chose to share. Kept prominent near the top so the assistant addresses them
        // correctly from the first word.
        DriverProfile.promptFragment(appContext)?.let { sb.append("\n\n").append(it) }

        // Fleet-wide voice (ticket 01, 2026-08-06). Deliberately the ONE
        // pre-injected context block this file adds despite CLAUDE.md §7's
        // "pull-based tools, not pre-injected context" default - justified
        // narrowly, not as a precedent: a pull-based tool (list_vehicles)
        // cannot bootstrap its OWN discovery. Before this existed, the base
        // instruction never named a car at all (no make/model/name/count), so
        // the model had no reason to suspect a second car existed and would
        // never think to call list_vehicles to check. This fragment is kept to
        // just names + which one is active - two rows today, changes only when
        // a car is added or renamed - specifically so it stays cheap.
        // Everything else about a car (specs, schedule, history, live
        // readings) stays pull-based via list_vehicles plus the 11
        // `vehicle`-argument tools (LiveToolbox's "category B"). See
        // [buildFleetFragment]'s own doc for why it is built from the real
        // roster rather than illustrated with invented cars.
        buildFleetFragment()?.let { sb.append("\n\n").append(it) }

        // Long-term memories are NOT dumped here anymore - they're pulled on demand
        // via the recall_memory tool (search over the memory table), so the prompt
        // stays lean no matter how many memories accumulate. See [recallMemories].
        return sb.toString()
    }

    /**
     * The fleet fragment [assembleBase] appends (ticket 01) - see that call
     * site's comment for why this is a deliberate exception to the
     * pull-based-tools rule.
     *
     * Built from [com.kevin.legion.data.local.VehicleDao.getAll] (non-archived
     * only, matching what `list_vehicles` and the CARS roster show). **Never
     * hardcoded or illustrated with invented cars** - what is actually
     * registered on a given install is not something this file can assume, so
     * every name/make/model here comes straight off the read.
     *
     * Degrades honestly rather than emitting a misleading or empty fragment:
     * - zero cars -> null (omit the fragment entirely; nothing to say yet).
     * - one car -> a plain one-line mention with NO "fleet" framing (a driver
     *   with one car should never be told they have a "fleet").
     * - two or more -> the full fleet line, naming which one is active.
     *
     * [AriaBrain.invalidateBase] is already called on every car-switch and
     * car-roster change ([com.kevin.legion.vehicle.ActiveVehicle.notifyResolutionChanged]
     * plus the ordinary vehicle-DAO upsert paths), so this fragment is never
     * more than [BASE_TTL_MS] stale.
     */
    private suspend fun buildFleetFragment(): String? {
        val vehicles = CarDatabase.getDatabase(appContext).vehicleDao().getAll()
        if (vehicles.isEmpty()) return null

        fun describe(v: com.kevin.legion.data.local.Vehicle): String {
            val ymm = listOf(v.year.takeIf { it > 0 }?.toString().orEmpty(), v.make, v.model)
                .filter { it.isNotBlank() }.joinToString(" ")
            val label = ymm.ifBlank { "an unregistered car" }
            // Ticket 04's label rule deleted the "this car" sentinel at the source
            // (VehicleController.seedVehicle no longer writes it, and the two archived rows that
            // had it are cleared on process start), so the `&& v.name != "this car"` half of this
            // check is now dead weight rather than a real filter - removed rather than left to rot.
            // The dual-fact format is deliberate - the model gets the spec and the nickname as
            // SEPARATE facts, which is why this is not simply VehicleController.label(). But it
            // needs label()'s de-duplication clause all the same: when the nickname IS the spec,
            // there are not two facts to keep apart, and this rendered
            // `1998 Jeep Cherokee ("1998 Jeep Cherokee")` on Kevin's real row - the exact
            // duplication ticket 04 exists to kill, reproduced in the one place that was exempted
            // from the rule, with quote marks instead of parentheses as the only difference.
            //
            // Caught on review, 2026-08-15. An exemption from a rule is not an exemption from the
            // reason the rule exists.
            val nickname = v.name.trim()
            val redundant = nickname.isBlank() || label.contains(nickname, ignoreCase = true)
            return if (redundant) label else "$label (\"$nickname\")"
        }

        if (vehicles.size == 1) {
            return "The driver has one car on file: ${describe(vehicles.first())}. Tools that read " +
                "stored records take an optional `vehicle` argument (harmless with only one car); " +
                "tools that read the OBD dongle only ever answer for the car it is plugged into."
        }

        val activeId = ActiveVehicle.current(appContext)
        val active = vehicles.firstOrNull { it.obdMac == activeId }
        val activeName = active?.name?.takeIf { it.isNotBlank() }
            ?: active?.let { describe(it) } ?: "a car you haven't named yet"

        return "The driver's fleet: ${vehicles.joinToString(", ") { describe(it) }}. They are " +
            "currently driving $activeName. Tools that read stored records take a `vehicle` " +
            "argument; tools that read the OBD dongle only ever answer for the car it is plugged " +
            "into. Never assume there is only one car."
    }

    /**
     * Searches long-term memory for entries relevant to [query], across BOTH
     * memory stores - the older explicit-"remember X" table ([MemoryEntry],
     * global) and the consolidated/reflected companion-memory table
     * ([CompanionMemory], ticket 01/02, per active car). Returns the top
     * matches as spoken-friendly, date-tagged lines for the recall_memory
     * tool. Pull-based, not pre-injected into every prompt.
     *
     * **Ticket 03's scoring (Generative-Agents-style):** each candidate is
     * ranked by `recency + importance + relevance`, not relevance alone -
     * see [score]. This is the "human-like forgetting" Kevin asked for: a
     * memory doesn't need to be DELETED to stop surfacing, it just needs a
     * low enough score to fall out of the top [limit]. Recalling a memory
     * bumps its [CompanionMemory.lastAccessedAt] (rehearsal refreshes
     * recency, same as the old table's `touch()`), so what the driver keeps
     * bringing up naturally stays reachable.
     *
     * A blank/term-less query returns the most recent+important memories
     * instead of scoring by relevance (nothing to be relevant TO).
     */
    suspend fun recallMemories(query: String, limit: Int = RECALL_LIMIT): List<String> =
        withContext(Dispatchers.IO) {
            val vehicleId = ActiveVehicle.current(appContext)
            val legacy = memoryDao.getRecent(RECALL_SCAN)
            val companion = companionMemoryDao.getRecent(vehicleId, RECALL_SCAN)

            val terms = query.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 && it !in RECALL_STOPWORDS }

            val now = System.currentTimeMillis()
            val candidates = legacy.map { Candidate.Legacy(it) } + companion.map { Candidate.Companion(it) }
            // With real search terms, require actual keyword overlap first (recency/importance
            // alone are always > 0, so scoring without this pre-filter would let the composite
            // score surface a recent-but-unrelated memory for an unmatched query - the old
            // grep-only behaviour's ".filter { it.second > 0 }" guarantee, preserved here).
            val relevant = if (terms.isEmpty()) candidates else candidates.filter { relevance(it, terms) > 0.0 }
            val chosen = relevant
                .sortedByDescending { score(it, terms, now) }
                .take(limit)

            // Rehearsal: recalling a companion_memories row refreshes its recency,
            // same effect the old table's touch() has on re-mention.
            for (c in chosen) if (c is Candidate.Companion) companionMemoryDao.touch(c.memory.id, now)

            chosen.map { formatMemory(it) }
        }

    /** One candidate memory from either store, normalized just enough to score/format uniformly. */
    private sealed class Candidate {
        data class Legacy(val entry: MemoryEntry) : Candidate()
        data class Companion(val memory: CompanionMemory) : Candidate()

        val text: String get() = when (this) { is Legacy -> entry.text; is Companion -> memory.text }
        /** 1-10; the legacy table has no importance field, so treat it as a neutral midpoint. */
        val importance: Int get() = when (this) { is Legacy -> 5; is Companion -> memory.importance }
        val createdAt: Long get() = when (this) { is Legacy -> entry.timestamp; is Companion -> memory.createdAt }
        /** What "last brought up" means for recency decay - touch() bumps the legacy timestamp itself. */
        val lastAccessedAt: Long get() = when (this) {
            is Legacy -> entry.timestamp
            is Companion -> memory.lastAccessedAt.takeIf { it > 0 } ?: memory.createdAt
        }
    }

    /**
     * `w_recency * recencyDecay + w_importance * (importance/10) + w_relevance * relevanceFraction`.
     * Equal weights (Generative Agents' own documented default) - a car companion has no data yet to
     * justify tuning away from that, and hand-tuned weights here would be guessing, not designing.
     * [relevanceFraction] is keyword-overlap / term-count today; ticket 04/layer-5 wiring is
     * designed to swap ONLY this term for embedding cosine similarity later, unchanged otherwise.
     */
    private fun score(c: Candidate, terms: List<String>, nowMs: Long): Double {
        val hoursSinceAccess = (nowMs - c.lastAccessedAt).coerceAtLeast(0L) / 3_600_000.0
        val recencyDecay = Math.pow(RECENCY_DECAY_PER_HOUR, hoursSinceAccess)
        val importanceFraction = c.importance / 10.0
        return RECENCY_WEIGHT * recencyDecay + IMPORTANCE_WEIGHT * importanceFraction +
            RELEVANCE_WEIGHT * relevance(c, terms)
    }

    /**
     * Keyword-overlap fraction (matched terms / total terms), 1.0 for a term-less
     * query (nothing to be relevant TO, so it shouldn't zero out the score). This
     * is the term [score] weights AND the hard gate [recallMemories] applies before
     * ranking - ticket 04/layer-5 wiring swaps only this function's body for
     * embedding cosine similarity later.
     */
    private fun relevance(c: Candidate, terms: List<String>): Double {
        if (terms.isEmpty()) return 1.0
        val text = c.text.lowercase()
        return terms.count { text.contains(it) }.toDouble() / terms.size
    }

    /**
     * Companion-memory map, ticket 06 (2026-07-22): the felt sense of an ongoing
     * relationship, at the cost of one small DB read. Surfaces WHEN the driver
     * last talked to the assistant (a raw time gap, not a scripted phrase - the model
     * phrases it naturally, same as every other live-context fact) plus a
     * couple of the highest-scored memories (ticket 03's ranking, blank query
     * so it's recency+importance only, no relevance term to satisfy).
     *
     * **§9.1 discipline (kept even with guardrails off for the experimental
     * phase, per the map's own rule):** the note states a plain time gap as
     * fact and instructs natural, non-emotional reference to it - explicitly
     * NOT "I missed you" / "where have you been" framing. That framing is the
     * re-engagement-ping class §9.1 forbids; a factual gap anchored to real
     * conversation history is not.
     *
     * Returns null when there's no prior history at all (first-ever session -
     * nothing to reference) or when the gap is trivially small (a few minutes
     * ago is not a "continuity" moment, just the same outing continuing).
     */
    private suspend fun buildContinuityNote(): String? {
        val vehicleId = ActiveVehicle.current(appContext)
        // Two sources because consolidation may not have caught up yet: a pending
        // (not-yet-consolidated) session's raw turns are still the freshest signal
        // of "when did we last talk" until MemoryConsolidator processes them.
        val lastMemoryAt = companionMemoryDao.latestCreatedAt(vehicleId)
        val lastTurnAt = episodicTurnDao.latestTimestamp(vehicleId)
        val lastTalkedAt = listOfNotNull(lastMemoryAt, lastTurnAt).maxOrNull() ?: return null

        val gapMs = System.currentTimeMillis() - lastTalkedAt
        if (gapMs < CONTINUITY_MIN_GAP_MS) return null

        val gapDescription = when {
            gapMs < 24 * 3_600_000L -> "earlier today"
            gapMs < 2 * 24 * 3_600_000L -> "about a day ago"
            gapMs < 7 * 24 * 3_600_000L -> "${gapMs / (24 * 3_600_000L)} days ago"
            gapMs < 30 * 24 * 3_600_000L -> "about ${gapMs / (7 * 24 * 3_600_000L)} week(s) ago"
            else -> "over a month ago"
        }

        val topMemories = recallMemories(query = "", limit = CONTINUITY_MEMORY_COUNT)
        val memoryLine = if (topMemories.isEmpty()) "" else
            " A couple of things worth keeping in mind: ${topMemories.joinToString("; ")}."

        return "Your last conversation with the driver was $gapDescription.$memoryLine Reference " +
            "this naturally if it fits the moment - as a factual continuity note, never as having " +
            "missed them or needed them (you don't have needs)."
    }

    private fun formatMemory(c: Candidate): String {
        val date = Instant.ofEpochMilli(c.createdAt)
            .atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
        return "($date) ${c.text}"
    }

    /**
     * The volatile half: live OBD codes, location, current saved place,
     * now-playing, odometer/maintenance, and the car to-do
     * list. Always built fresh (cheap, mostly cached reads), so it's current at
     * the moment a conversation starts. **No longer ever returns ""** - the wall
     * clock below is unconditional, so callers branching on `isBlank()` (see
     * [com.kevin.legion.service.LiveSessionController]'s greeting) now always
     * take the with-context path. Phrased as a standalone context note so it can be injected per
     * conversation on a warm session, or appended to the base for a cold one.
     */
    suspend fun buildLiveContext(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // The wall clock, first and unconditional. It belongs in the VOLATILE
        // half, not the cached base: the base is served for up to BASE_TTL_MS
        // and a stale time is exactly the failure being fixed here. This is
        // only accurate as of the moment a conversation opens, which is why
        // get_current_time exists as well - a long conversation must re-read
        // the clock rather than trust this note.
        val zone = ZoneId.systemDefault()
        val stamp = java.time.ZonedDateTime.now(zone)
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))
        sb.appendSection("Right now it is $stamp (${zone.id}). Call get_current_time rather " +
            "than reusing this if the conversation has been going a while.")

        buildContinuityNote()?.let { sb.appendSection(it) }

        if (ObdBluetoothManager.isConnected) {
            val codes = cachedDtcCodes()
            if (codes.isNotEmpty()) {
                sb.append("The car's OBD-II port currently reports these stored diagnostic " +
                    "trouble codes: ${codes.joinToString(", ")}. You may bring these up if relevant. " +
                    "If the driver asks what's wrong with the car, about a check engine light, what a " +
                    "code means, or how to fix one, call the diagnose_codes tool - it's your " +
                    "diagnostics specialist. Don't explain or look up codes yourself.")
            } else {
                sb.append("The car's OBD-II port is connected and reports no stored trouble " +
                    "codes in the ECU right now. If the driver asks what's wrong with the car or " +
                    "about a check engine light, tell them nothing is stored at the moment - but " +
                    "that says nothing about PAST codes, which are a separate question (below).")
            }
        }

        // Outside the isConnected gate on purpose (2026-08-11). Stored code history is a database
        // read that works for any car with nothing plugged in, and the assistant previously had no
        // way to reach it at all - asked to "check the historical codes for the Cherokee" it
        // answered that it could not see them, while the rows sat in `code_events`. Anything said
        // here about the live port is silent about history, and vice versa; conflating the two is
        // what makes "no codes" sound like "this has never happened".
        sb.appendSection(
            "Past trouble codes are recorded per car and readable at any time, dongle connected or " +
                "not. If the driver asks what codes a car HAS thrown, whether something has " +
                "happened before, or about a code from the past, call get_code_history (it takes a " +
                "vehicle name). Never answer a question about past codes from the live-port " +
                "readings above, and never say you cannot see a car's code history."
        )

        val location = LocationController.state.value
        if (location != null) {
            val place = reverseGeocode(location)
            val locationDesc = place ?: "latitude ${location.latitude}, longitude ${location.longitude}"
            sb.appendSection("The driver's current location is approximately $locationDesc.")
            sb.append(" If the driver asks to find a place - a restaurant, gas station, coffee, " +
                "etc - especially \"near me\" or \"on the way\", use your search tool to find real " +
                "places close to this location and recommend one or two specific options by name.")
        } else {
            sb.appendSection("Your real-time GPS location is currently unavailable in the prompt. " +
                "If the driver asks where they are, or to find something 'near me', call the " +
                "get_current_location tool to fetch the latest fix.")
        }

        val currentPlace = PlaceController.currentLabel(appContext)
        if (currentPlace != null) {
            sb.appendSection("The driver is currently at their saved \"$currentPlace\" location. " +
                "Reference it naturally when relevant - e.g. ask how work was, or offer to head home.")
        }

        // Now-playing comes from the media session (a free, exact live data
        // stream), so the assistant knows the track without capturing the screen.
        val nowPlaying = NowPlayingController.state.value
        if (nowPlaying != null && nowPlaying.title.isNotBlank()) {
            val artist = if (nowPlaying.artist.isNotBlank()) " by ${nowPlaying.artist}" else ""
            val verb = if (nowPlaying.isPlaying) "is currently playing" else "is paused"
            sb.appendSection("Right now \"${nowPlaying.title}\"$artist $verb. " +
                "If the driver asks what's playing, answer from this.")
        }

        // Music: transport-only via MediaSession/AVRCP, plus direct Spotify play-by-name
        // (SpotifyController) when connected.
        sb.appendSection("Music transport (control_music) works with whatever's playing — " +
            "play/pause/next/previous only. If Spotify App Remote is connected, you can also play " +
            "a specific track/artist by name directly; otherwise you can't pick songs by name.")

        val vehicle = VehicleController.currentVehicle(appContext)
        if (vehicle.odometerBaseline > 0) {
            // mileageLabel already carries its own bare/estimate split (ticket 10): blank only
            // when there is truly nothing to show, otherwise either the driver's own confirmed
            // reading bare, or "about N mi - estimated, last confirmed ...". This is a SYSTEM
            // instruction, not a sentence read aloud, so it also has to tell the model what the
            // words mean - ticket 03 found the estimate runs 5-15% low, always in the same
            // direction, so a model that just repeats the number back as confirmed fact launders
            // the same guess this whole ticket exists to stop.
            val mileageLabel = VehicleController.mileageLabel(vehicle)
            if (mileageLabel.isNotBlank()) {
                sb.appendSection("The car's current odometer reading is $mileageLabel. If this " +
                    "says \"estimated\", it is a rough figure derived from trip distance, not a " +
                    "confirmed reading - if you mention it, say it is approximate and never state " +
                    "it back as an exact fact. If it has no \"estimated\" wording, it is the " +
                    "driver's own last typed reading and you may state it plainly.")
            }

            // Just a brief awareness flag for proactive mention - the actual
            // routing lives in the two maintenance tools, which keeps this
            // prompt lean: get_next_service owns "when's the next service" /
            // "what's coming up" (instant, no reasoning needed), ask_maintenance
            // owns how-to-perform-it, should-I-worry, and named-item/overdue
            // questions (needs the specialist's per-item access).
            val due = VehicleController.dueItems(appContext, vehicle)
            if (due.isNotEmpty()) {
                sb.append(" Some maintenance looks due (${due.joinToString(", ") { it.serviceName }}); " +
                    "you may gently mention it. For when the next service lands or what's coming up, " +
                    "call get_next_service - it's instant. For how to perform a service, whether to " +
                    "worry, or anything about a specific named service or how overdue it is, call " +
                    "ask_maintenance instead. Don't work either one out or look it up yourself.")
            }
        }

        // Just the count as an awareness flag - the items themselves are pulled on demand via
        // read_list, keeping this prompt lean.
        //
        // Counts the WHOLE list now, not a "Car" sub-list (2026-08-11: "dissolve the car list,
        // merge everything into one list model"). There is one list; naming a sub-list here would
        // be telling the model about a structure that no longer exists, which is how it ends up
        // passing a `list` argument no tool accepts any more.
        val openItems = NotesController.openItemCount(appContext)
        if (openItems > 0) {
            sb.appendSection("The driver has $openItems open item(s) on their list - car to-dos, " +
                "errands, appointments and reminders all live on the one list. Call read_list to " +
                "read them out, and use manage_item to add, tick off, or remove items - always use " +
                "the tools, don't just claim you did. When an item has a date or a time, pass it on " +
                "the SAME manage_item add call; never add it first and schedule it after. If it " +
                "feels natural, you may occasionally offer to run through the list.")
        }

        sb.toString()
    }

    /** Appends a paragraph, prefixing a blank-line separator only when not the first. */
    private fun StringBuilder.appendSection(text: String) {
        if (isNotEmpty()) append("\n\n")
        append(text)
    }

    /** DTC codes for the prompt, refreshed at most once per [DTC_TTL_MS] to avoid a Bluetooth round-trip every session. */
    private suspend fun cachedDtcCodes(): List<String> {
        val now = System.currentTimeMillis()
        if (now - dtcCacheAt < DTC_TTL_MS) return dtcCache
        dtcCache = ObdBluetoothManager.getDtcCodes()
        dtcCacheAt = now
        return dtcCache
    }

    /**
     * Turns a raw GPS fix into a human-readable "street, city" string, or null
     * on failure. Cached by ~0.001 deg (~110 m) so a stationary or
     * slow-moving driver doesn't trigger a geocoder lookup every session.
     */
    private fun reverseGeocode(location: Location): String? {
        val key = "%.3f,%.3f".format(location.latitude, location.longitude)
        if (key == geocodeKey) return geocodeValue

        val value = try {
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            results?.firstOrNull()?.let { address ->
                listOfNotNull(address.thoroughfare, address.locality, address.adminArea)
                    .joinToString(", ")
                    .ifBlank { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed: ${e.message}")
            null
        }

        geocodeKey = key
        geocodeValue = value
        return value
    }

    companion object {
        @Volatile
        private var INSTANCE: AriaBrain? = null

        /**
         * Shared instance so the UI and the foreground service build their
         * system instructions and save memories against one source of truth.
         */
        fun get(context: Context): AriaBrain =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AriaBrain(context.applicationContext).also { INSTANCE = it }
            }

        private const val TAG = "AriaBrain"

        // Memory recall (pull-based via the recall_memory tool, no longer
        // pre-injected): scan at most this many recent rows, return at most
        // RECALL_LIMIT matches. Bounded so a huge memory table can't bloat either
        // the DB scan or the model's context.
        private const val RECALL_SCAN = 200
        private const val RECALL_LIMIT = 6

        // Ticket 03 (2026-07-22) retrieval scoring - see [score]. 0.99/hour is the
        // Generative Agents paper's own default: half-life ~69 hours (~2.9 days),
        // so an un-rehearsed memory fades over about a week without needing to be
        // deleted - the "forgetting" is a score falling out of the top RECALL_LIMIT,
        // not a purge. Weights are equal (the paper's default too); no data exists
        // yet to justify tuning away from it.
        private const val RECENCY_DECAY_PER_HOUR = 0.99
        private const val RECENCY_WEIGHT = 1.0
        private const val IMPORTANCE_WEIGHT = 1.0
        private const val RELEVANCE_WEIGHT = 1.0

        // Ticket 06 (2026-07-22) continuity note. Below this gap, it's still the
        // same outing (another PTT tap minutes later) - not a "welcome back" moment.
        private const val CONTINUITY_MIN_GAP_MS = 20 * 60 * 1000L
        private const val CONTINUITY_MEMORY_COUNT = 3

        // Common words dropped from a recall query so they don't match everything.
        private val RECALL_STOPWORDS = setOf(
            "the", "and", "for", "you", "your", "our", "was", "were", "did",
            "what", "when", "where", "who", "why", "how", "about", "that", "this",
            "with", "have", "has", "had", "are", "from", "they", "them", "remember",
        )

        // How long DTC codes are reused for prompt-building before re-querying
        // the OBD adapter. New codes are still surfaced live by the health
        // monitor; this only bounds the per-session Bluetooth cost.
        private const val DTC_TTL_MS = 60_000L

        // How long the cached static base instruction (persona + memories) is
        // reused before reassembly. Short enough that an edited persona/driver
        // profile takes effect quickly; long enough that warm/back-to-back
        // sessions skip the DB read. remember() invalidates it immediately.
        private const val BASE_TTL_MS = 120_000L

        private val REMEMBER_ACKS = listOf(
            "Fine... I'll remember that. My memory's better than my suspension, anyway.",
            "Got it. Filed away with everything else I'm hauling around back here.",
            "Noted... don't expect me to bring it up first, though.",
        )
    }
}
