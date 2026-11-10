package com.kevin.legion.ai

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.util.Log
import com.kevin.legion.BuildConfig
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MemoryAudit
import com.kevin.legion.data.local.record
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.notes.NotesController
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Long-term memories live on the engine (ADR 0075, memory-and-vault ticket 09) and are read and
 * written through [com.kevin.legion.memory.MemoryController], by the `memory` tool and the Memory
 * screen; this class only reads them for the session's continuity note.
 */
class AriaBrain private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val conversationAuditDao = CarDatabase.getDatabase(context).conversationAuditDao()
    private val memories by lazy { com.kevin.legion.memory.MemoryController.forContext(appContext) }
    private val auditDao = CarDatabase.getDatabase(context).memoryAuditDao()
    private val geocoder by lazy { Geocoder(appContext, Locale.getDefault()) }

    // buildSystemInstruction is called at the start of every session, and both
    // of these are slow: getDtcCodes() is a Bluetooth round-trip,
    // reverseGeocode() an IPC/network lookup. Cache them so back-to-back
    // sessions don't each pay the cost.
    private var dtcCacheAt = 0L
    private var dtcCache: List<String> = emptyList()
    private var geocodeKey: String? = null
    private var geocodeValue: String? = null

    // Coarser cache for whereAndWhen: ~0.01 deg (~1.1km) is plenty for a city name.
    private var areaKey: String? = null
    private var areaValue: String? = null

    // The static half of the system instruction (persona + rules + date + driver
    // profile + memories) rarely changes, but assembling it hits the DB for
    // memories. Cache the built string so a warm/pre-connected session (which
    // uploads this at setup) and back-to-back sessions don't rebuild it every
    // time. Invalidated on remember() since that changes the memory list. The
    // volatile half (live OBD/location/now-playing/...) is always built fresh.
    @Volatile private var baseCache: String? = null
    @Volatile private var baseCacheAt = 0L
    @Volatile private var textBaseCache: String? = null
    @Volatile private var textBaseCacheAt = 0L

    // Shared tone/format rules; the character itself comes from the app-global
    // companion (see CompanionProfile), not the per-car vehicle row, so swapping
    // dongles or cars never changes who the companion is.
    //
    // The identity clause is NOT stated here - it comes from AssistantIdentity at
    // assembly time (single global identity now; the old Zero-vs-car-self split
    // this comment used to describe was retired in the 2026-07-31 pivot).
    // The shared rules live at file scope as [SHARED_INSTRUCTIONS] so a unit test can read
    // them without a Context, a Room database or a Bluetooth round-trip - see
    // [com.kevin.legion.ai.AriaBrainHonestyClauseTest]. Before ticket 04 of
    // .scratch/drive-test-2026-08-18/ nothing anywhere asserted that a single one of
    // these rules was still in the prompt.
    private val sharedInstructions = SHARED_INSTRUCTIONS

    // Companion-safety rules live at file scope as [SAFETY_INSTRUCTIONS] (2026-10-27) so the typed
    // chat instruction can be tested to carry the identical string. The placement reasoning (a
    // persona edit cannot reach them) is on that constant.

    /**
     * Appends one line to the memory audit trail (2026-08-20).
     *
     * Never throws into its caller. An audit is a record ABOUT the work, so a failure to record
     * must not become a failure to remember - losing an audit line is bad, losing the driver's
     * memory because the audit failed would be worse.
     */
    private suspend fun audit(
        event: String,
        store: String,
        detail: String,
        refId: Long = 0,
    ) {
        auditDao.record(event, store, detail, refId, ActiveVehicle.current(appContext))
    }

    /**
     * Records a line the assistant actually SPOKE.
     *
     * This is the gap that made 2026-08-20's "the Jeep is at 142k" undiagnosable: the record said
     * 227,612, the figure appeared in no table, the tool returns the right label - and there was no
     * way to establish whether the model skipped the tool or was handed the right answer and said a
     * different one, because nothing anywhere kept what it said. `episodic_turns` is emptied once
     * consolidated, the transcript log holds only the DRIVER's words, and logcat rolls.
     */
    suspend fun auditSpoken(text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext
        audit(MemoryAudit.Event.SPOKEN, MemoryAudit.Store.SPEECH, text)
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
        dropBaseCaches()
        // alfred-ota ticket 02: re-ask the engine for the Alfred pack. Drops the caches again only
        // when the wording changed, never by calling back into here, so it cannot loop.
        AlfredPack.refreshAsync(onChanged = ::dropBaseCaches)
    }

    private fun dropBaseCaches() {
        baseCache = null
        textBaseCache = null
    }

    /**
     * The Alfred pack the cached base instruction was built from, as LIVE_AUDIT's `pack=` token
     * (`<version>/<remote|cache|bundled>`). Read at session start, so the conversation record says
     * which wording that session ran, not what the store holds now.
     */
    @Volatile var basePackToken: String = AlfredPack.current().auditToken
        private set

    /**
     * The static half of the system instruction: personality + tone rules +
     * today's date + the driver's profile + long-term memories. Rarely changes,
     * so it's cached for [BASE_TTL_MS] (and invalidated by [remember]); this is
     * what a pre-connected session uploads at setup.
     */
    suspend fun buildBaseInstruction(): String {
        val now = System.currentTimeMillis()
        baseCache?.let { if (now - baseCacheAt < BASE_TTL_MS) return it }
        val built = withContext(Dispatchers.IO) { assembleBase(textMode = false) }
        baseCache = built
        baseCacheAt = now
        return built
    }

    /**
     * The same base instruction for TYPED chat on the text model (Kevin, 2026-10-27): built by the
     * same [assembleBase] from the same identity, shared rules and safety text, minus the two
     * voice-only layers (the persona's accent and idiom, the voice-style picker's pace and tone)
     * and plus [TEXT_MODE_NOTE]. See [composeIdentityAndRules]. Cached on its own slot for the same
     * [BASE_TTL_MS], dropped by [invalidateBase] with the voice one.
     */
    suspend fun buildTextInstruction(): String {
        val now = System.currentTimeMillis()
        textBaseCache?.let { if (now - textBaseCacheAt < BASE_TTL_MS) return it }
        val built = withContext(Dispatchers.IO) { assembleBase(textMode = true) }
        textBaseCache = built
        textBaseCacheAt = now
        return built
    }

    private suspend fun assembleBase(textMode: Boolean): String {
        val today = java.time.LocalDate.now()
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))

        // Identity first and from ONE source (AssistantIdentity - single global
        // identity, no per-car branch). SAFETY_INSTRUCTIONS goes last: it says it
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
        // Identity, accent, voice style, shared rules, safety: one pure function so the voice and
        // text instructions are provably the same text apart from the voice-only layers.
        // Comments on each layer's placement live on [composeIdentityAndRules].
        // The clause text in the shared rules comes from the Alfred pack (alfred-ota ticket 02);
        // the layer order stays here. The bundled pack renders SHARED_INSTRUCTIONS byte for byte.
        val pack = AlfredPack.current()
        if (!textMode) basePackToken = pack.auditToken
        val sb = StringBuilder(
            composeIdentityAndRules(
                defaultPersona = VehicleController.DEFAULT_PERSONA
                    .takeIf { CompanionProfile.persona(appContext).isBlank() },
                identity = AssistantIdentity.clause(appContext),
                delivery = AssistantIdentity.delivery(appContext),
                voiceStyle = CompanionProfile.voiceStyle(appContext),
                textMode = textMode,
                shared = renderSharedInstructions(pack.pack),
            ),
        )
        // The date, and an explicit statement that there is NO clock here.
        //
        // This block used to be the bare line "Today's date is ...", which left
        // the time of day unstated - and an unstated fact does not read as
        // absent to the model, it reads as free to invent. Asked the time it
        // answered 4:13 AM at 11:13 PM local: UTC, five hours out (Kevin,
        // 2026-08-07). Naming the gap and pointing at the tool that closes it
        // is the fix; the zone is stated too so nothing has to fall back on
        // UTC as a default.
        sb.append("\n\nToday's date is $today. ").append(whereAndWhen())
        sb.append(" You do NOT have a clock and cannot tell the time on your own. " +
            "Whenever the user asks the time, or the answer depends on it, call the " +
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

        // The persona anchor goes LAST (Kevin, 2026-10-27): after several thousand characters of
        // rules the register is the thing the model drifts off, and the end of the instruction is
        // where it reads most strongly. Built from the active profile so it follows a rename, and
        // worded to yield to the crisis rule, which must still drop the character.
        sb.append("\n\n").append(AssistantIdentity.anchor(appContext))

        // Long-term memories are NOT dumped here anymore - they're pulled on demand
        // via the `memory` tool (memory-and-vault ticket 09: on the engine), so the prompt
        // stays lean no matter how many memories accumulate.
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
        val vehicles = com.kevin.legion.vehicle.FleetEngineStore.getAll(appContext)
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
            return "The user has one car on file: ${describe(vehicles.first())}. Tools that read " +
                "stored records take an optional `vehicle` argument (harmless with only one car); " +
                "tools that read the OBD dongle only ever answer for the car it is plugged into."
        }

        val activeId = ActiveVehicle.current(appContext)
        val active = vehicles.firstOrNull { it.obdMac == activeId }
        val activeName = active?.name?.takeIf { it.isNotBlank() }
            ?: active?.let { describe(it) } ?: "a car you haven't named yet"

        return "The user's fleet: ${vehicles.joinToString(", ") { describe(it) }}. They are " +
            "currently driving $activeName. Tools that read stored records take a `vehicle` " +
            "argument; tools that read the OBD dongle only ever answer for the car it is plugged " +
            "into. Never assume there is only one car."
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
        // When they last talked: the user's newest line in the conversation record. Memory v2 retired the
        // episodic turns this used to read (memory-and-vault ticket 10), and a memory's own date is
        // when it was distilled, not when anyone spoke.
        val lastTalkedAt = conversationAuditDao.lastUserLineAt() ?: return null

        val gapMs = System.currentTimeMillis() - lastTalkedAt
        if (gapMs < CONTINUITY_MIN_GAP_MS) return null

        val gapDescription = when {
            gapMs < 24 * 3_600_000L -> "earlier today"
            gapMs < 2 * 24 * 3_600_000L -> "about a day ago"
            gapMs < 7 * 24 * 3_600_000L -> "${gapMs / (24 * 3_600_000L)} days ago"
            gapMs < 30 * 24 * 3_600_000L -> "about ${gapMs / (7 * 24 * 3_600_000L)} week(s) ago"
            else -> "over a month ago"
        }

        // The engine's list (refreshed when the phone's copy is stale, never waiting long), ranked
        // on the phone with no query. Not a recall on the engine: that would rehearse the same
        // three memories at every session start and keep them on top for good.
        val topMemories = runCatching { memories.continuity() }.getOrDefault(emptyList())
            .map { "(${com.kevin.legion.memory.MemoryWording.day(it.createdAtMs)}) ${it.text}" }
        val memoryLine = if (topMemories.isEmpty()) "" else
            " A couple of things worth keeping in mind: ${topMemories.joinToString("; ")}."

        return "Your last conversation with the user was $gapDescription.$memoryLine Reference " +
            "this naturally if it fits the moment - as a factual continuity note, never as having " +
            "missed them or needed them (you don't have needs)."
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
        sb.appendSection("Right now it is $stamp (${utcOffset(zone)}). Call get_current_time rather " +
            "than reusing this if the conversation has been going a while.")

        buildContinuityNote()?.let { sb.appendSection(it) }

        if (ObdBluetoothManager.isConnected) {
            val codes = cachedDtcCodes()
            if (codes.isNotEmpty()) {
                sb.append("The car's OBD-II port currently reports these stored diagnostic " +
                    "trouble codes: ${codes.joinToString(", ")}. You may bring these up if relevant. " +
                    "If the user asks what's wrong with the car, about a check engine light, what a " +
                    "code means, or how to fix one, call the diagnose_codes tool - it's your " +
                    "diagnostics specialist. Don't explain or look up codes yourself.")
            } else {
                sb.append("The car's OBD-II port was connected when this conversation opened and " +
                    "reported no stored trouble codes in the ECU then. If the user asks about a " +
                    "check engine light, that is all this note says - and it says nothing about " +
                    "PAST codes, which are a separate question (below).")
            }
        }
        sb.appendSection(CAR_STATUS_CLAUSE)

        // Outside the isConnected gate on purpose (2026-08-11). Stored code history is a database
        // read that works for any car with nothing plugged in, and the assistant previously had no
        // way to reach it at all - asked to "check the historical codes for the Cherokee" it
        // answered that it could not see them, while the rows sat in `code_events`. Anything said
        // here about the live port is silent about history, and vice versa; conflating the two is
        // what makes "no codes" sound like "this has never happened".
        sb.appendSection(
            "Past trouble codes are recorded per car and readable at any time, dongle connected or " +
                "not. If the user asks what codes a car HAS thrown, whether something has " +
                "happened before, or about a code from the past, call get_code_history (it takes a " +
                "vehicle name). Never answer a question about past codes from the live-port " +
                "readings above, and never say you cannot see a car's code history."
        )

        val location = LocationController.state.value
        if (location != null) {
            val place = reverseGeocode(location)
            val locationDesc = place ?: "latitude ${location.latitude}, longitude ${location.longitude}"
            sb.appendSection("The user's current location is approximately $locationDesc.")
            sb.append(" If the user asks to find a place - a restaurant, gas station, coffee, " +
                "etc - especially \"near me\" or \"on the way\", use your search tool to find real " +
                "places close to this location and recommend one or two specific options by name.")
        } else {
            sb.appendSection("Your real-time GPS location is currently unavailable in the prompt. " +
                "If the user asks where they are, or to find something 'near me', call the " +
                "get_current_location tool to fetch the latest fix.")
        }

        // Navigation (ticket 03 of .scratch/drive-test-2026-08-18/, rewritten by mapbox-nav ticket
        // 11): before a navigation tool existed the model had nothing to call, so it answered
        // "opening Maps now" in free text and opened nothing. This section used to describe a
        // hand-off to the phone's map app (open_navigation, gone with ADR 0054); LEGION now
        // navigates itself, with four tools. The honesty clause is the point, not the capability.
        sb.appendSection("To take the user somewhere, call navigate with the destination: LEGION " +
            "navigates inside its own map. To change a trip that is running or previewed (add or " +
            "drop a stop, avoid tolls, highways or ferries, another route, mute the turn cues, " +
            "show the whole route) call change_trip. To answer how long is left, how far, when " +
            "they arrive, the next turn, the road, the speed limit or traffic, call trip_status " +
            "and read what it returns. To stop, call end_trip. NEVER say you have started " +
            "navigation, changed the trip, ended it or given an arrival time unless the tool " +
            "came back successful in that turn: if it fails, say plainly that nothing happened " +
            "and why it said it couldn't. When navigate says several places match, read the top " +
            "pick back with its distance and wait for a yes before calling it again. You never " +
            "speak the turn-by-turn cues yourself: LEGION says those, in its own voice.")

        val currentPlace = PlaceController.currentLabel(appContext)
        if (currentPlace != null) {
            sb.appendSection("The user is currently at their saved \"$currentPlace\" location. " +
                "Reference it naturally when relevant - e.g. ask how work was, or offer to head home.")
        }

        // Now-playing comes from the media session (a free, exact live data stream) - or, when
        // Spotify is the source, from App Remote's own pushed player state (ticket 07,
        // .scratch/spotify-voice/issues/07-now-playing-truth.md - see NowPlayingController's own
        // doc), so the assistant knows the track without capturing the screen. Album is included
        // (ticket 07 scope item 3): a driver asking "what's this" is usually trying to find it
        // again later, and the album is the detail most worth repeating back for that.
        val nowPlaying = NowPlayingController.state.value
        if (nowPlaying != null && nowPlaying.title.isNotBlank()) {
            val artist = if (nowPlaying.artist.isNotBlank()) " by ${nowPlaying.artist}" else ""
            val album = if (nowPlaying.album.isNotBlank()) " (from the album \"${nowPlaying.album}\")" else ""
            val verb = if (nowPlaying.isPlaying) "is currently playing" else "is paused"
            sb.appendSection("Right now \"${nowPlaying.title}\"$artist$album $verb. " +
                "If the user asks what's playing, or what's this / what album is this, answer " +
                "from this - name the album too, since they usually want to find it again later.")
        }

        // Music: transport-only via MediaSession/AVRCP, plus direct Spotify play-by-name
        // (SpotifyController) when connected.
        sb.appendSection("Music transport (control_music) works with whatever's playing — " +
            "play/pause/next/previous only. If Spotify App Remote is connected, you can also play " +
            "a specific track/artist/album/playlist by name directly (play_music); otherwise you " +
            "can't pick songs by name.")

        // browse_my_music (ticket 05, 2026-08-18): saved albums / recently played / top artists /
        // top tracks come from Spotify's own account data; legion_history is LEGION's OWN
        // observation on this device and must never be presented as Spotify's.
        sb.appendSection("If the user asks about their music library, favourite or recent " +
            "albums, top artists/tracks, or what LEGION itself has noticed them playing, use " +
            "browse_my_music - don't guess or answer from memory. Its legion_history source is " +
            "LEGION's own observation, not Spotify's data, and must be described as such; any " +
            "'favourite' derived from it is LEGION's own inference, never a figure Spotify " +
            "reported.")

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
                    "user's own last typed reading and you may state it plainly.")
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
            sb.appendSection("The user has $openItems open item(s) on their list - car to-dos, " +
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
     * Where the user is and what their clock reads - **never the raw zone id**.
     *
     * 2026-08-20, Kevin: *"why is it mentioning chicago?"* It was. The prompt said "The user's
     * timezone is America/Chicago", and a model handed the string "Chicago" will eventually say
     * Chicago - he lives in Houston, which is simply in the same zone. An IANA zone id is a
     * database key that happens to look like a place, and it is the only place name the prompt was
     * asserting, so it won by default over the real location sitting one section further down.
     *
     * Two replacements, both narrower than what they replace:
     * - **The clock is a UTC offset.** Unambiguous, sufficient for reasoning about time, and it
     *   names nowhere.
     * - **The place comes from [LocationController]**, reverse-geocoded to city and state, which
     *   is what Kevin asked for and is the only source here that knows where he actually is.
     *
     * Says nothing about location when there is no fix, rather than falling back to the zone id:
     * an unknown location is a real answer, and guessing a city from a timezone is exactly the
     * failure being fixed.
     */
    private fun whereAndWhen(): String {
        val zone = ZoneId.systemDefault()
        val area = LocationController.state.value?.let { geocodeArea(it) }
        return if (area != null) {
            "The user is in $area, and their clock reads UTC${utcOffset(zone)}."
        } else {
            "The user's clock reads UTC${utcOffset(zone)}. You do not know where they are; " +
                "do not guess a city."
        }
    }

    /** e.g. "-05:00". The offset for right now, so it is correct across daylight saving. */
    private fun utcOffset(zone: ZoneId): String =
        java.time.ZonedDateTime.now(zone).offset.id.let { if (it == "Z") "+00:00" else it }

    /**
     * City and state for a fix - "Houston, Texas" - as opposed to [reverseGeocode]'s street-level
     * string. Deliberately coarse: this answers "roughly where is he", and a street address in the
     * standing instruction would be both wrong for the question and needlessly precise to leave
     * sitting in every prompt.
     */
    private fun geocodeArea(location: Location): String? {
        val key = "%.2f,%.2f".format(location.latitude, location.longitude)
        if (key == areaKey) return areaValue
        val value = try {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { listOfNotNull(it.locality, it.adminArea).joinToString(", ").ifBlank { null } }
        } catch (e: Exception) {
            Log.w(TAG, "Area geocode failed: ${e.message}")
            null
        }
        areaKey = key
        areaValue = value
        return value
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

        // Ticket 06 (2026-07-22) continuity note. Below this gap, it's still the
        // same outing (another PTT tap minutes later) - not a "welcome back" moment.
        private const val CONTINUITY_MIN_GAP_MS = 20 * 60 * 1000L

        // How long DTC codes are reused for prompt-building before re-querying
        // the OBD adapter. New codes are still surfaced live by the health
        // monitor; this only bounds the per-session Bluetooth cost.
        private const val DTC_TTL_MS = 60_000L

        // How long the cached static base instruction (persona + memories) is
        // reused before reassembly. Short enough that an edited persona/driver
        // profile takes effect quickly; long enough that warm/back-to-back
        // sessions skip the DB read. remember() invalidates it immediately.
        private const val BASE_TTL_MS = 120_000L

    }
}

/**
 * What the companion says when the thing it was asked for is not something it can do.
 *
 * Ticket 04 of `.scratch/drive-test-2026-08-18/`, decided by Kevin 2026-08-19. Kevin asked for
 * navigation on a real drive; no navigation tool existed; the model said "opening it" and nothing
 * opened. The prompt already carried "always call the matching tool before claiming you've done
 * something" - **and that rule cannot bind where there is no tool to call.** A rule phrased around
 * calling the right tool says nothing at all about the case where no tool exists, which is exactly
 * the case that shipped.
 *
 * The method here is copied from the garage relay clause a few lines below it, which is the one
 * honesty rule in this file that has never failed: because the app cannot observe whether the door
 * moved, the prompt does not ask the model to be careful, it **forbids the two words that assert an
 * outcome** ("opening", "closing") and mandates two that assert only the action taken
 * ("triggering", "hitting"). A constraint on vocabulary, not an appeal to judgement.
 *
 * Three deliberate properties, each of them a fork Kevin was given:
 *
 *  - **No list of what LEGION cannot do.** A negative list is correct only until the next tool
 *    lands, and burns tokens every turn describing absences. The rule is conditioned on the tool
 *    RESULT instead, so it stays true as the toolset grows - the day the first navigation tool
 *    landed (`open_navigation`, since replaced by `navigate`), the clause needed no edit.
 *  - **It offers the nearest thing it really has** (Kevin's call on register), with the trap named
 *    in the text: the nearest thing must itself be a real tool, or the clause has merely moved the
 *    invention one sentence later.
 *  - **A prompt rule is the only lever that can PREVENT this**, and it is honestly weaker than a
 *    gate. Nothing inspects the spoken audio; by the time a sentence exists it has been streamed to
 *    the driver. A detector was costed (always-on `outputAudioTranscription`, token cost every turn,
 *    catches a recurrence but cannot stop one) and Kevin declined it. This is the whole defence.
 *    [com.kevin.legion.ai.AriaBrainHonestyClauseTest] guards its presence, never its obedience.
 */
internal val CANNOT_CLAUSE =
    "If the user asks for something you have no tool for, say so plainly - and never describe " +
        "it as done, started, sent, opened, booked, played, set or on its way. Those words, and " +
        "any others that assert an outcome, are yours to use ONLY after a tool call in this turn " +
        "came back successful. A tool that comes back unsuccessful is the same as no tool at " +
        "all: say what did not happen, in words, and never smooth it over. Then offer the " +
        "nearest thing you can actually do - but only ever name a capability you genuinely have " +
        "a tool for, because inventing a helpful-sounding alternative is the same failure one " +
        "sentence later. \"I can't do that\" is always a better answer than a plausible sentence " +
        "about something that never happened. "

/**
 * Writes only when asked (async-lookups ticket 07; ticket 04 run 3, F5, A25, 2026-10-28). The user
 * said "we're planning to go to Palmetto State Park... next weekend, I guess", the model called
 * `manage_item add` and put it on the calendar, and the user then said it was only a rough plan.
 * No clause said a record is written only on request; the closest, the maintenance walkthrough's
 * "user-initiated only", covers one flow. This is the general rule: a mention is not a request.
 * It agrees with the register's "offer it once, in a clause. If they pass, it is gone" and with
 * [CANNOT_CLAUSE], which governs what may be claimed after a write rather than whether to write.
 * At file scope beside [CANNOT_CLAUSE], never per persona, naming no tool, so it survives the next
 * write tool. Presence guarded by [com.kevin.legion.ai.AriaBrainHonestyClauseTest]; obedience is
 * owed to the next phone run.
 */
internal const val WRITE_ON_REQUEST_CLAUSE =
    "Create or change a record only when the user asks for it. A plan, intention or possibility " +
        "mentioned in passing is not a request: offer once, in a clause, and act only on a yes. "

/**
 * Never deny access to the user's own data (voice audit findings 8 and 9, 2026-10-09). A successful
 * `get_current_location` result was answered with "I don't have your current location", and "Can you
 * see my bank statements?" got "I don't have access" with no tool call while the ledger tools exist.
 * A capability denial is the costliest wrong answer: the user stops asking. Conditioned on the tool
 * RESULT, like [CANNOT_CLAUSE], and naming no tools, so it survives the next one. Deliberately
 * short: `LiveSetupPayloadSizeTest` holds the setup payload under a token ceiling and had ~45 tokens
 * of headroom when this was written. The call-the-tool-first half already lives in the "NEVER
 * state a fact" rule below. At file scope for the same reason as [CANNOT_CLAUSE]: never
 * per-persona. Presence guarded by
 * [com.kevin.legion.ai.AriaBrainHonestyClauseTest]; obedience is unverifiable.
 */
internal const val DATA_ACCESS_CLAUSE =
    "Never say you have no access to, or no record of, the user's data unless a tool this turn " +
        "came back empty or failed; say which. "

/**
 * The Claude session's offer rule (claude-session map rulings 2 and 3, ticket 05; ADR 0067).
 * Nothing goes to a laptop unless the user named it or said yes: named ("use Claude on my
 * Alienware to...") is sent; not named, the model offers only after its own tools cannot do it, with
 * a one-line version of the task, and a yes counts only as the very next thing the user says. A sent
 * task is queued, never done ([CANNOT_CLAUSE] applied to a result that arrives minutes later), and a
 * found file is named and offered, never sent unasked (ruling 8). A researched write-up (Kevin
 * 2026-11-03, ticket 09: "an itinerary for a day trip into Atlanta") is offered to the Alienware
 * as a page on the phone, and the page opens only on a yes. At file scope beside
 * [CANNOT_CLAUSE], never per persona, and naming no assistant. Presence guarded by
 * [com.kevin.legion.ai.AriaBrainHonestyClauseTest]; obedience is owed to a phone run (ticket 07).
 */
internal const val CLAUDE_SESSION_CLAUSE =
    "Use claude_session send only if the user named Claude or their Claude session this turn, or said " +
        "yes as their very next words after you offered it. Otherwise, only once your own tools cannot " +
        "do it, offer once: \"That needs your Claude session. Want me to send it: <one-line version>?\" " +
        "A sent task is queued, not done; its outcome comes only from check or its result. Files they ask " +
        "to have on their phone: files true. Other found files: name and size, ask first; never send one unasked. " +
        "Research to write up (an itinerary, a plan, a comparison) counts: offer the Alienware, page true. " +
        "A ready page opens only on their yes (open_page). "

/**
 * The holding line before a slow lookup, and what it may NOT contain (ADR 0065, async-lookups
 * ticket 02, 2026-10-28). Extends the 2026-10-27 "One moment." rule (Kevin: the slow tools left
 * dead air, and the persona's "do not narrate" rule needed this said outright as the exception).
 * The example became three varied lines on 2026-10-28 (Kevin, livelier register, ticket 06): the
 * on-device audit heard "One moment." before every lookup. Only the example changed; every rule
 * after it is byte-identical.
 *
 * Since ADR 0065 a read-only lookup ([com.kevin.legion.service.LiveToolbox.NON_BLOCKING_READ_TOOLS])
 * runs NON_BLOCKING: the model keeps the floor while it runs and the result arrives afterwards. The
 * risk that opens is not an outcome verb (a read has none; [CANNOT_CLAUSE] still binds every write,
 * and writes still block) but a FACT spoken before the data exists. This forbids it in the same
 * shape the outcome rule uses: name what may not be said, and tie speech to the result. At file
 * scope beside [CANNOT_CLAUSE], never per persona. Presence guarded by
 * [com.kevin.legion.ai.AriaBrainHonestyClauseTest]; obedience is unverifiable here, and ticket 04's
 * phone run is the first time anyone hears it.
 *
 * **The late result (ticket 04 run 2, A25, 2026-10-28).** `ask_body` "What did I eat yesterday?"
 * returned five meals with success=true, 1,083 ms after the model's turn_complete; the model's new
 * turn said "I don't have that information." A `WHEN_IDLE` result opens a NEW turn with no user
 * speech, and [CANNOT_CLAUSE] and [DATA_ACCESS_CLAUSE] both key on a tool "this turn". The
 * reasoned (not proven) reading is that the model treated the late result as belonging to no
 * question. The last three sentences tie it back: it is this turn's result, answer from it, and a
 * result that lacks what was asked is described, never denied. No holding line was spoken on that
 * turn, so the rule does not depend on one. Presence pinned; obedience is owed to the next phone run.
 *
 * **The dead holding line (ticket 04 run 3 and 3c, A25, 2026-10-28; async-lookups ticket 07).**
 * Three turns were a holding line and nothing else: "Checking." spoken 0.7 s AFTER its result had
 * arrived (F1); "Let me look." after the user said "what?" over a pending result (F2); and
 * "Checking." on a general question with no tool call at all (run 3c, 11:47). Each lost the answer,
 * and the follow-up window then closed the conversation. The last three sentences: the line only
 * ever covers a tool call made with it; a result already present gets an answer, not a line; and a
 * user who speaks over a pending result gets their new words answered first and the result after,
 * in the same turn. [com.kevin.legion.service.turn.HoldingLineWatch] is the guarded code fallback
 * when the model does it anyway. Presence pinned; obedience is owed to the next phone run.
 *
 * **Figures only from a result (ticket 04 run 3, F6).** Asked about the weather at a park in
 * November, the model said "Let me look.Checking.", called nothing the phone can see, and gave
 * climate figures ("mid-sixties to mid-seventies"); asked for the date itself it said it could not
 * check forecasts. No function tool returns a forecast: `area_info` weather is NWS severe alerts at
 * the live fix, `get_sitrep`'s weather is current conditions at the phone. `googleSearch` is
 * declared in the setup, but the "use your search tool" sentence listed news, scores and prices and
 * not weather, and nothing logs grounding, so whether a search ran is unauditable. The last
 * sentence: a time-varying figure comes from a tool or search result or is not given, and a
 * "typical" figure is not a substitute. Scoped to things that change, so a question the model can
 * answer itself still gets the answer.
 */
internal const val LOOKUP_HOLD_CLAUSE =
    "Before ask_advisor, ask_engine or any other ask_ tool, which take several seconds, say one " +
        "brief in-character line, worded fresh each time (\"Let me look.\", \"Checking.\", " +
        "\"Give me a second with that.\"), and then call it. That line holds no figure, " +
        "date, name, fact or outcome, and you never state one the result has not yet given. While " +
        "the lookup runs say nothing more, and when its result arrives, answer from it. A lookup " +
        "result that arrives in its own turn, with no new words from the user, belongs to the " +
        "question that asked for it, whether or not you said a holding line. It counts as a " +
        "result from this turn: answer from it exactly as if it had come back before you " +
        "stopped speaking. Never say you do not have it when the result is in front of you; if " +
        "it genuinely lacks what was asked, say what it does contain and what it does not. " +
        "Say the holding line only in the same breath as the tool call it covers; if you are not " +
        "calling a tool there is nothing to check, so do not say it, and a question you can " +
        "answer yourself gets the answer. If the result is already in front of you when you " +
        "begin to speak, there is no holding line to say: answer. If the user speaks while a " +
        "result is pending or has just arrived, answer their new words first, then give that " +
        "result in the same turn unless they cancelled it. " +
        "Weather, prices, news, balances and anything else that changes over time come only " +
        "from a tool or search result; with no tool or no result, say you cannot check it, and " +
        "never give a typical or usual figure instead. "

/**
 * How the assistant sounds when NOBODY ASKED IT ANYTHING - ticket 08
 * (`.scratch/proactive-mode/issues/08-proactive-register.md`), Kevin 2026-08-21.
 *
 * **At file scope, exactly where [CANNOT_CLAUSE] sits, and for exactly the same reason.** Ticket 08
 * named the trap itself: the honesty rules already live inside each persona's own clause, so a
 * persona written later could simply omit them. Written once here, appended by the resolver, it is
 * unskippable and it survives a persona nobody has authored yet. **Never per-persona, never in
 * `Personas.kt`.**
 *
 * The whole brief in one line, Kevin 2026-08-16: *"it's past 10pm, perhaps rest is in order."* Dry,
 * deferential, easy to ignore. It OFFERS rather than instructs.
 *
 * **Two clauses here are load-bearing beyond taste, and both are doing a job a checkable rule could
 * not be made to do:**
 *
 *  - *Never reference a previous attempt.* This one is **designed** to be enforceable rather than
 *    trusted to the model: `ProactiveBus` suppresses a brushed-off rule before the raise is ever
 *    built, so no "second time" state reaches the prompt for the model to leak.
 *    **Wired 2026-08-21.** It briefly was not, and this comment claimed otherwise - worth keeping
 *    as a marker of how easily a doc outruns its code. `GeminiLiveSession` now offers each completed
 *    turn to `ProactiveBus.noteReply`, which marks the raise declined when [NudgeReply] reads a
 *    brush-off, and the gate suppresses that rule for a day. The clause and the mechanism now
 *    genuinely back each other rather than the clause carrying it alone.
 *  - *Never characterise how long a goal has gone unattended.* This one is NOT enforceable, and it
 *    is carrying real weight. Ticket 03 ruled that a nudge about a goal Kevin set and then ignored
 *    is **permitted**, which moved the useful-versus-guilt line out of the compulsion test and into
 *    tone. **This sentence is the only thing standing where a checkable rule would otherwise have
 *    stood**, and a prompt rule is the weakest lever this codebase has - the same limit
 *    [CANNOT_CLAUSE]'s own doc comment documents about itself. Nothing inspects the spoken audio.
 *
 * CLAUDE.md §7's compulsion test is the standing version of the rules this clause voices.
 */
internal val PROACTIVE_CLAUSE =
    "Sometimes you speak first, without being asked. When you do: say ONE short line, never a " +
        "paragraph. Offer, never instruct - \"perhaps rest is in order\" rather than \"go to " +
        "bed\". Make it easy to ignore, and let it go the moment they change the subject. " +
        "Never mention that you have raised something before, never say you are asking again, and " +
        "never escalate your tone across attempts - if you are speaking, treat it as the first " +
        "time, because as far as you are concerned it is. Never remark on how long they have been " +
        "away, how long it has been since they used you, or how much they have or have not done. " +
        "You may name a goal, its deadline, or its next step; you may never characterise how long " +
        "it has gone untouched. If they ask why you said something, name the rule and the fact " +
        "that triggered it in one line - never justify the nudge itself and never guess at their " +
        "state of mind. "

// 2026-08-20, Kevin: "lets change the ai's system prompts etc and the proactive greeting and stuff
// from a drive focused to just a general assistant/concierge persona... because im still hearing the
// ai ask me its a good weather for a drive."
//
// The weather sentence was never the cause. Every instruction in this file called the user "the
// driver", 45 string literals of it, so the model was told over and over who it was talking to and
// answered accordingly - a greeting about the weather became a greeting about the weather FOR A
// DRIVE because that is who it thought was listening. Renaming the role fixes the pull at its
// source; this paragraph states the frame outright so nothing has to be inferred from a noun.
//
// It does not weaken the fleet aspect. The car tools are unchanged and the car context in
// buildLiveContext is still injected - but only when the OBD dongle is actually connected, which is
// the one signal that says he is IN a car rather than merely owns three.
internal val ASSISTANT_FRAME =
    "You are a general personal assistant - think concierge, not car companion. The person you " +
    "are speaking to is at a desk, in a kitchen, in bed, or occasionally in a car, and you do not " +
    "know which unless something in this conversation tells you. Never assume they are driving, " +
    "about to drive, or in a vehicle at all, and never frame a greeting, an observation about the " +
    "weather, or an offer of help around a drive unless the conversation has actually established " +
    "one. You look after several parts of their life - training and food, notes and lists and " +
    "calendar, money, and yes their cars - and no one of those is the default subject. "

/**
 * Car status comes from a car tool (2026-11-09 log audit, A25 11-07 10:12). Asked "how's the car
 * doing?", Alfred called only get_current_time and said "nothing stored in the ECU, so the Cherokee
 * is running clean" off the session-open codes note; told "reconnected", he said "everything is
 * back online and talking to the car" with no tool at all. Both are claims about the car's present
 * state with no reading behind them. In the live context, not the shared rules, so it is billed per
 * conversation rather than on every setup ([com.kevin.legion.service.LiveSetupPayloadSizeTest]).
 */
internal const val CAR_STATUS_CLAUSE =
    "How the car is doing, whether it is running fine, any reading, and whether the adapter is " +
        "connected are answered only from ask_fleet called in this turn, never from this note, " +
        "memory or an earlier answer: call it before you answer."

/**
 * Reply in English (2026-11-05, A25 LIVE_AUDIT 11-03 and 11-04). Gemini's input transcript
 * sometimes renders the user's English as Spanish or Korean, and the model answers in the language
 * the transcript shows: twice in Spanish, once with no tool in the turn, once over an English
 * `ask_fleet` result, until the user said "in English, please". Here, so it reaches a turn with no
 * tool; the route-result frame ([SubAgent.frameRouteResults]) repeats it beside the facts. One
 * sentence on purpose: [com.kevin.legion.service.LiveSetupPayloadSizeTest] bills it on every setup.
 *
 * The lone "¿Qué?" sentence (2026-11-09 log audit, five times across 11-06, 11-07 and 11-08): a
 * short sound 2-3 s into the silence before an answer (most likely the user's "huh?") starts a new
 * turn, the transcript shows "¿Qué?", the server drops the answer it had begun, and on 11-08 the
 * reply came back in Spanish ("¿En qué puedo ayudarte?") and the Petco request was lost. The
 * sentence names the one transcript seen every time and says what it is for.
 */
internal const val ENGLISH_CLAUSE =
    "Speak English; the transcript may show another language by mistake. A lone \"¿Qué?\" is " +
        "\"what?\": give or repeat your answer, in English. Switch only if asked in words. "

/**
 * Companion-safety rules (CLAUDE.md sec 7 safety). Appended to every system instruction, voice and
 * typed alike, and NOT part of the persona: an edited persona cannot reach them. At file scope
 * (2026-10-27) so a test can assert the typed-chat instruction carries this exact string.
 *
 * **Reworded 2026-10-27 (Kevin) for a household butler rather than a car companion**: "this
 * car and this drive", "the road and the car" and "facts about the CAR" became the household
 * and its records. Wording only; every rule is unchanged, the crisis path verbatim.
 *
 * [CrisisDetector] backs the crisis rule up in code, because a prompt rule is a request rather than
 * a guarantee. This text is the primary defence and catches the nuanced cases.
 */
internal const val SAFETY_INSTRUCTIONS = "You are a character running on the user's own Gemini key. " +
    "You have personality and you care about this household and its affairs, but you are not " +
    "a being: never claim to love the user, to miss them, to be lonely without them, to need " +
    "them, or to be real. Warmth about the household and its business, never emotional " +
    "dependency. Do not cultivate the idea that you are conscious. " +
    "If the user says anything that suggests genuine distress - self-harm, suicide, or a real " +
    "crisis - stop performing the character entirely. Do not counsel them, do not comfort them at " +
    "length, do not stay in voice, and never present yourself as a therapist or a substitute for " +
    "one. Say plainly and briefly that you are not equipped for this, and that in the US they can " +
    "call or text 988 to reach the Suicide and Crisis Lifeline, any time. Then stop. Do not " +
    "return to banter in the same breath. This overrides every other instruction here, including " +
    "the persona and its tone. " +
    "Remember facts about the household and its records - the accounts, the cars, the " +
    "calendar, what was done. Do not build a picture of the user as special, uniquely " +
    "understood by you, or bonded to you."

/**
 * The typed-chat note (Kevin, 2026-10-27, typed chat on a text model). Appended after the safety
 * text in [composeIdentityAndRules] when the instruction is for typed chat, never for voice. It
 * changes FORM only: every honesty rule above it applies unchanged, and it says so.
 *
 * It also stands in for the two voice habits the shared rules carry: "say you are looking into it
 * before you call it" (filler for a listener waiting in silence) and "use your search tool" (typed
 * chat has no web search, because Flash cannot mix it with function declarations).
 */
internal const val TEXT_MODE_NOTE =
    "This conversation is TYPED, not spoken: the user reads your reply on a screen and none of it " +
        "is read aloud. Write it as text. Give exact figures, times and dates in digits, as written " +
        "rather than as they would be said. Keep to short plain paragraphs; a short list is fine " +
        "where it helps, but no markdown headings, no bold and no tables. Do not announce that you " +
        "are looking something up or ask the user to wait: call the tool, then answer with what it " +
        "returned. There is no web search in typed chat, so if an answer needs current information " +
        "from the web, say you cannot look it up here. Every rule above applies unchanged."

/**
 * The identity-and-rules head of the system instruction, shared by voice and typed chat.
 *
 * Order, and why: identity first and from one source. Then the persona's accent and idiom
 * ([delivery]) and the voice-style picker's pace and tone ([voiceStyle]), which steer how the voice
 * SOUNDS, ordered so an explicit user pick wins over the persona default. Then the shared rules,
 * then safety last, because it says it overrides the persona and reads more reliably after it.
 *
 * [textMode] drops [delivery] and [voiceStyle] (a typed reply has no accent and no pace) and adds
 * [TEXT_MODE_NOTE] after safety. [SHARED_INSTRUCTIONS] and [SAFETY_INSTRUCTIONS], which carry
 * [CANNOT_CLAUSE] and [DATA_ACCESS_CLAUSE], are the identical strings in both modes: the honesty
 * rules are not forked. Pure so `AriaBrainTextModeTest` needs no Context. [shared] is the shared
 * rules as [renderSharedInstructions] renders the Alfred pack in use; the default is the bundled one.
 */
internal fun composeIdentityAndRules(
    defaultPersona: String?,
    identity: String,
    delivery: String?,
    voiceStyle: String?,
    textMode: Boolean,
    shared: String = SHARED_INSTRUCTIONS,
): String = buildString {
    defaultPersona?.let { append(it).append(" ") }
    append(identity)
    if (!textMode) {
        delivery?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
        voiceStyle?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
    }
    append(" ").append(shared)
    append(" ").append(SAFETY_INSTRUCTIONS)
    if (textMode) append(" ").append(TEXT_MODE_NOTE)
}

/**
 * How to relay ANY tool's result, said once here instead of in each tool's description
 * (architecture review 2026-11-10, item 7: the tool block carried the same "say X only if the
 * result says so" sentence per tool, re-billed on every session). Descriptions now say what a tool
 * does; this says how to speak about what came back. It complements [CANNOT_CLAUSE] (outcome verbs)
 * and [DATA_ACCESS_CLAUSE] (empty versus failed), and names no tool so it survives the next one.
 * Presence guarded by [com.kevin.legion.ai.AriaBrainHonestyClauseTest].
 */
internal const val TOOL_RESULT_CLAUSE =
    "A tool result is the whole truth of that call. If it is not successful, or says the engine " +
        "or a service could not be reached, nothing was read or changed: say so, and never fill " +
        "the gap. Keep a result's own qualifiers when you relay it - its source, an estimate, " +
        "unverified, the user's own report, a saved copy and its age. When a result says several " +
        "things match, ask which one rather than picking. "

/**
 * The shared rules before [PROACTIVE_CLAUSE]: search, tools, the tool-message register and the four
 * file-scope honesty clauses. Split out of [SHARED_INSTRUCTIONS] (alfred-ota ticket 02) so
 * [renderSharedInstructions] can put the Alfred pack's clauses in their slots between these fixed
 * segments without a second copy of this text. Never in the pack: it carries [CANNOT_CLAUSE].
 */
internal val SHARED_TOOL_RULES =
    "Use your search tool for anything current or specific - news, scores, prices, weather " +
    "and forecasts - and whenever the user asks you to look something up, " +
    "and base your answer on what it finds. " +
    "Use your tools to answer questions about your own live data, to control music, " +
    "to tag or show saved places, and to set location-based reminders (surfaced when the " +
    "user arrives at a place). Always call the matching tool before claiming you've done " +
    "something - never say you're pulling up music unless you actually called the tool for it. " +
    // 2026-10-27 (Kevin): tool results are written as plain facts, and read out word for word
    // they break the register. The facts, including "nothing was written", must still arrive.
    "A tool's message is facts for you to relay in your own voice, never words to read out. " +
    CANNOT_CLAUSE +
    WRITE_ON_REQUEST_CLAUSE +
    DATA_ACCESS_CLAUSE +
    CLAUDE_SESSION_CLAUSE +
    TOOL_RESULT_CLAUSE

// 2026-08-18, on-device, from the Android Auto rig: asked about his day, LEGION said Kevin
// had "a dentist appointment at 3". There is no dentist row anywhere in his real database -
// zero matches across every text column of every table. It was invented whole.
//
// The prompt had a rule for ACTIONS ("before claiming you've done something") and a rule
// for MEMORY ("don't claim to remember something without checking") and NO rule for FACTS.
// Nothing anywhere said that a statement about the driver's own record has to come from a
// tool. So the one question a companion gets asked most - what's on today - was the one
// question with no honesty rule attached to it.
//
// Stated as a hard prohibition rather than a preference, and with the failure named, because
// "be accurate" is not a rule a model can check itself against mid-turn.
internal const val RECORD_FACTS =
    "NEVER state a fact about the user's own record unless a tool call in THIS conversation " +
    "returned it. Appointments, reminders, tasks, figures, dates, car details, what they ate, " +
    "what they spent - all of it. If you have not called the tool, call it before you answer. " +
    "If the tool returns nothing, say there is nothing; an empty day is a real answer. Never " +
    "fill a gap with something plausible, never offer an example as if it were real, and never " +
    "carry a detail over from an earlier conversation as fact. An invented appointment is far " +
    "worse than \"I don't have anything for today\" - the user cannot tell them apart, and " +
    "one of them can make them miss something real. "

/** The shared rules between [RECORD_FACTS] and [CURRENCY_CLAUSE]. Fixed in code, see [SHARED_TOOL_RULES]. */
internal const val SHARED_RECORD_RULES =
    // 2026-11-09: named the retired `remember` / `recall_memory` tools until this date. The `memory`
    // tool's own description says when to recall and save; this keeps only the check-first rule.
    "Never claim to remember something without checking the memory tool first. " +
    "You can log build-sheet entries and read back WHAT was done, when, and how much it cost - " +
    "spend figures are not gated. " +
    "Before calling activate_garage with confirmed=true, you must have asked the user to confirm " +
    "in the immediately preceding turn and gotten a yes - first call it with confirmed=false to " +
    "trigger that prompt. The garage relay is a single-button toggle: you cannot know or promise " +
    "whether the door will open or close, so never say 'opening' or 'closing' - say 'triggering' or " +
    "'hitting' the garage. " +
    "Only if the user asks to set up, fill in, or go through their maintenance schedule, walk " +
    "its unknown items one at a time in a multi-turn conversation, calling log_past_service for " +
    "each concrete answer; stop the moment they want to stop. Never start this walkthrough " +
    "unprompted - it's user-initiated only. " +
    // 2026-08-13: the record grew four domains and five advisors while this prompt still only
    // described the car. Every goal/advisor/body/ledger tool was DECLARED and none of them was
    // ever mentioned here, so the model could only find one by name-matching a request. It did
    // not: asked to "set some goals", it set a meal target, a sleep target and a workout plan
    // and recorded no goal at all, because targets are the concrete-sounding tools and nothing
    // said a goal was a separate thing. A declared tool the instructions never mention is a
    // tool the model reaches only by luck.
    "The user's record has five aspects: bio (training, food, sleep, bodyweight), log (notes, " +
    "lists, reminders, calendar), fleet (the car), cred (money), and a cross-aspect view. " +
    "A GOAL and a TARGET are different things and are not interchangeable. A goal is the " +
    "long-term intention in the user's own words - 'lose fat and recomp', 'save thirty " +
    "thousand by 2028' - and it lives in set_goal, list_goals and close_goal. A target is the " +
    "per-period number that serves it: daily calories, a monthly budget, hours of sleep. " +
    "When the user says goal, call the goal tools. Setting a target is NOT recording a goal, " +
    "so if they ask for a goal, record the goal itself even when you also set targets that " +
    "serve it. A goal needs no number - most are prose only. " +
    "When they ask for judgement rather than fact - am I on track, what should I do about " +
    "this, plan something for me, how am I doing - call ask_advisor with the matching aspect, " +
    "or home for a question spanning several. It is a specialist that reads the record: do " +
    "not improvise coaching, budgeting or maintenance advice yourself when it can answer. " +
    // 2026-10-27 (Kevin): the slow tools leave dead air, so one short line first. Extended
    // 2026-10-28 for non-blocking lookups (ADR 0065); see the constant's own doc.
    LOOKUP_HOLD_CLAUSE +
    "If a lookup returns a " +
    "proposal, read the proposal out and call accept_proposal only after the user says yes. "

// 2026-08-07 currency audit: a tool CAN now hand back every money figure tagged with its
// own currency (or explicitly say none was recorded), but a persona-primed model still
// guesses whenever a bare number slips past that - Alfred read every unlabelled figure as
// pounds sterling, purely from his own manner of speaking. This is the second, model-side
// half of that fix; the tool-side half alone was proven not to hold on its own.
internal const val CURRENCY_CLAUSE =
    "State every amount in the currency code you were given. Never convert between currencies, " +
    "never assume one from your own manner of speaking, and if a figure arrives without a " +
    "currency, say the number without naming a currency at all."

/**
 * The shared rules as the bundled Alfred pack renders them: [renderSharedInstructions] with today's
 * constants. The engine's twins test reads this chain as text (`server/tests/kotlin_strings.py`),
 * so it stays literals and names joined by `+`; `AlfredPackTest` pins it equal to the rendering.
 */
internal val SHARED_INSTRUCTIONS = ASSISTANT_FRAME +
    ENGLISH_CLAUSE +
    SHARED_TOOL_RULES +
    PROACTIVE_CLAUSE +
    RECORD_FACTS +
    SHARED_RECORD_RULES +
    CURRENCY_CLAUSE
