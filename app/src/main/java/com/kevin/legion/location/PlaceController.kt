package com.kevin.legion.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.backend.engine.engineRefusalSentence
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.engine.migration.EnginePlacesRetirementCopy

/**
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`) originally moved every read/write in
 * this file onto the engine (ADR 0035) - callers ([FleetScreen]'s saved-places UI,
 * `location/ReminderController.kt`, `service/LiveToolbox.kt`'s `tag_place`/`forget_place`/
 * `show_saved_places`) kept their original signatures throughout and never had to change.
 * **That engine cutover is itself retired as of ticket 15 step 1** - see the class doc's second
 * paragraph below for the current shape, which is `places` for both branches.
 *
 * **Backend-erp Phase 4, aspect 1 of 5** (`.scratch/backend-erp/issues/05-migration-path.md`).
 * DUAL-PATH, per C1: "retirement and deletion are different events". Every function now checks
 * [backend] first:
 * - **Configured**: reads come from the Room [TaggedPlace] replica (cache-first, ticket 01 ruling
 *   9 - and it must work with no network, since [currentLabel] sits on the geofencing/assistant
 *   hot path); writes go straight to the server (ruling 8, no local queue) and the replica is
 *   written **only on a genuine server ACK** - never ahead of it, never on a failure. A failed
 *   remote write is reported as failed in words (the same §7 outcome-verb discipline this file has
 *   always honoured) and leaves Room completely untouched.
 * - **Not configured** (no Supabase project saved): **repointed onto the SAME `places` table as of
 *   ticket 15 step 1** (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`) -
 *   `EngineDataMigrationWave1`'s original engine cutover for this file is retired. Places was the
 *   easiest of the five aspects precisely because `places` already exists, already serves the
 *   configured read above, and was already written on ACK - repointing the unconfigured path here
 *   makes ONE table serve both, with zero new schema. [ensureLegacyReconciled] runs
 *   [EnginePlacesRetirementCopy] once, first, so any place tagged directly through the engine
 *   since wave 1 is not silently lost the moment this read flips. **This file no longer touches
 *   [com.kevin.legion.engine.RecordStore] or `engineRecordDao()` at all** - the engine's Place
 *   records are left exactly where they are (ticket 15: nothing is deleted until every aspect is
 *   repointed and soaked), just no longer read or written from here.
 */
object PlaceController {
    private const val TAG = "PlaceController"

    private const val MATCH_RADIUS_M = 150f

    /** The local pre-validation cap, applied only where no server enforces one - see
     * [normalizeLabel]'s own doc comment. Deliberately the SAME number as
     * `server/api/places.py`'s `PlaceSerializer.LABEL_MAX_LENGTH`: while two copies of a rule
     * exist, a client that refuses at a DIFFERENT length than the server does is worse than either
     * one alone, because the two disagree about the same label. Named rather than inline so the
     * duplication is visible to whoever deletes this guard the day `places` joins
     * `DJANGO_BY_DEFAULT`. */
    private const val MAX_LOCAL_LABEL_LENGTH = 30

    /**
     * Test seam: settable from a unit test so a [PlacesBackend] fake can be injected without a
     * real backend or network. Defaults to null, meaning "resolve normally" - production code
     * never sets this.
     *
     * This comment used to name `SupabaseClientProvider` as the thing a fake stands in for; since
     * [backend] resolves through [com.kevin.legion.backend.engine.EngineBackends], the thing being
     * avoided is now either a Supabase client or an engine token, depending on the transport.
     */
    @Volatile
    internal var backendOverride: PlacesBackend? = null

    /**
     * Resolves the active backend, or null when this aspect's transport is not usable on this
     * device (the signal every function below branches on). Never performs network I/O itself - it
     * only builds a client wrapper; the actual request happens in whichever [PlacesBackend] call
     * the caller makes.
     *
     * **This used to read `SupabaseClientProvider.get(context) ?: return null` followed by
     * `SupabasePlacesBackend(client)`, i.e. Supabase or nothing.** It now asks
     * [EngineBackends] which transport `places` is on and gets that same Supabase backend, or a
     * [com.kevin.legion.backend.engine.DjangoPlacesBackend], or null when neither is configured
     * (django-engine Phase 5). `places` still DEFAULTS to Supabase, so an untouched install
     * behaves exactly as it did; only the debug Setup row changes the answer.
     *
     * **Nothing about this file's durability changes with it.** This controller is pure
     * write-through with no outbox: a failed write is spoken as a failure and nothing is queued,
     * on either transport. See `DjangoPlacesBackend`'s own class doc for why the Django transport
     * deliberately does not add durability the Supabase one does not have.
     */
    private fun backend(context: Context): PlacesBackend? {
        backendOverride?.let { return it }
        return EngineBackends(context).placesBackend()
    }

    private fun placeDao(context: Context) = CarDatabase.getDatabase(context).placeDao()

    /**
     * One-time reconcile gate for the unconfigured path (ticket 15 step 1): before EVER reading
     * or writing `places` from an unconfigured branch, make sure any engine-only Place has already
     * landed there. Cheap after the first call - [EnginePlacesRetirementCopy.copyIfNeeded] itself
     * short-circuits on its own completion flag, so this is a SharedPreferences read on every
     * later call, not a repeat scan. Every unconfigured function below calls this first so none of
     * them can read `places` before the copy has run, regardless of call order.
     */
    private suspend fun ensureLegacyReconciled(context: Context) {
        EnginePlacesRetirementCopy.copyIfNeeded(context)
    }

    /**
     * What one [tagPlace] or [forgetPlace] call did. Same shape and same reason as
     * [com.kevin.legion.ai.AriaBrain.RememberOutcome]: both functions returned a bare `String` and
     * `LiveToolbox`'s `tag_place`/`forget_place` dispatches hardcoded `success = true` over it, so
     * every refusal below - no label heard, no GPS lock, the engine saying no in its own words, a
     * Room write that threw, a label that was never saved - reached the model as
     * `{"success": true, "message": "<a failure>"}`. §7's outcome-verb clause is conditioned on the
     * tool RESULT, so a lying flag defeats it outright. Corrected 2026-09-07 alongside the
     * identical hole in `remember`.
     *
     * [message] is what the caller speaks either way; `ui/FleetScreen.kt` renders it unchanged.
     */
    data class WriteOutcome(val success: Boolean, val message: String)

    /**
     * Tags the current GPS location under [rawLabel] (normalized). Returns a spoken ack and
     * whether anything was actually pinned - see [WriteOutcome].
     *
     * Address-based tagging (resolving a spoken address via forward geocoding) is not
     * supported - only "tag where I am right now".
     */
    suspend fun tagPlace(context: Context, rawLabel: String): WriteOutcome {
        // Resolved BEFORE the label is normalized, because which transport this write is going to
        // decides whether the local length cap applies at all - see [serverOwnsTheLabelCap]. This
        // is a pure resolve with no network I/O (see [backend]'s own doc comment), so moving it
        // above the GPS read costs nothing and changes no ordering that matters.
        val backend = backend(context)
        val label = normalizeLabel(rawLabel, capLength = !serverOwnsTheLabelCap(context, backend))
        val loc = LocationController.state.value
        // The two refusals and the two write paths are ONE expression rather than four early
        // returns, purely to stay under detekt's `ReturnCount`. That rule used to be covered for
        // this function by a baseline entry, and the entry is keyed on the old `: String`
        // signature - answering a [WriteOutcome] stales it, so the rule bites again and the
        // honest answer is to satisfy it rather than to re-baseline. Same order, same sentences,
        // same behaviour.
        return when {
            label == null ->
                WriteOutcome(false, "I didn't catch what to call this spot — try something like 'home' or 'work'.")
            loc == null -> WriteOutcome(
                false,
                "I don't have a GPS lock yet, so I can't pin this spot. Give it a sec and try again.",
            )
            backend != null -> tagOverBackend(context, backend, label, loc)
            else -> tagUnconfigured(context, label, loc)
        }
    }

    /** [tagPlace]'s configured branch. Room is written ONLY after a genuine server ACK (ticket 01
     * ruling 9) - never ahead of it, and never on the failure branch. */
    private suspend fun tagOverBackend(
        context: Context,
        backend: PlacesBackend,
        label: String,
        loc: Location,
    ): WriteOutcome {
        val remote = backend.upsert(label, loc.latitude, loc.longitude).getOrElse {
            // A REFUSAL is relayed in the engine's own words; anything else keeps the generic
            // sentence. See [engineRefusal] for why the two are not the same thing.
            return WriteOutcome(
                false,
                engineRefusal(it)
                    ?: "Something went wrong pinning that spot - it didn't save. Try again in a sec.",
            )
        }
        placeDao(context).upsert(
            TaggedPlace(
                label = remote.label,
                latitude = remote.latitude,
                longitude = remote.longitude,
                timestamp = remote.updatedAtMs,
                deleted = remote.deleted,
            )
        )
        return WriteOutcome(true, ackFor(label))
    }

    /**
     * [tagPlace]'s unconfigured branch (ticket 15 step 1): `places` is now the single store for
     * this branch too, so tagging is a plain upsert on its `@PrimaryKey` label - the exact
     * re-tag-overwrites semantics [TaggedPlace]'s own doc comment describes, reproduced here
     * instead of by hand against the engine the way the retired code did.
     *
     * **The failure is WORDED, not thrown, and that was corrected rather than assumed.** Step 1
     * originally let a Room failure propagate, on the reasoning that a suspend insert either
     * completes or throws so there is nothing to check. That is only safe for a function
     * reachable solely through a voice tool, because `LiveSessionController.dispatch` wraps
     * every tool call in a catch-all. `tagPlace` is NOT only that: `ui/FleetScreen.kt` calls it
     * from a bare `scope.launch` with no handler, so a throw there is an unhandled coroutine
     * exception rather than anything the user can read. Section 7 wants a failure result that
     * says in words what did not happen, and a crash says nothing at all. Found while tracing
     * the identical question for `PantryController.writeReceipt` in step 2.
     */
    private suspend fun tagUnconfigured(context: Context, label: String, loc: Location): WriteOutcome {
        ensureLegacyReconciled(context)
        return try {
            placeDao(context).upsert(
                TaggedPlace(
                    label = label,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    timestamp = System.currentTimeMillis(),
                    deleted = false,
                )
            )
            WriteOutcome(true, ackFor(label))
        } catch (e: Exception) {
            Log.w(TAG, "unconfigured tagPlace write failed for $label: ${e.message}")
            WriteOutcome(false, "Something went wrong pinning that spot - it didn't save. Try again in a sec.")
        }
    }

    /** Deletes the saved place matching [rawLabel]. Returns a spoken ack, or an error if not found
     * or if the delete itself did not actually land (same §7 fix as [tagPlace]).
     *
     * **"No such saved place" is [WriteOutcome.success] = false**, not a quiet success: nothing was
     * deleted, so nothing may be spoken with an outcome verb over it. Same reading
     * [com.kevin.legion.backend.PlacesBackend.softDelete]'s own `Result.success(false)` already
     * carries ("must never be reported as a delete having happened"). */
    suspend fun forgetPlace(context: Context, rawLabel: String): WriteOutcome {
        // Same ordering and the same reason as [tagPlace] - and the cap has to be lifted on BOTH
        // or the pair disagrees: a label the engine accepted for a tag must still be nameable when
        // the user asks to forget it, or the app would answer "I'm not sure which place you mean"
        // about a place it is currently showing them.
        val backend = backend(context)
        val label = normalizeLabel(rawLabel, capLength = !serverOwnsTheLabelCap(context, backend))
        // One expression rather than three early returns, for exactly the reason [tagPlace]'s own
        // comment gives: the baseline entry covering `ReturnCount` here was keyed on the old
        // `: String` signature and no longer matches.
        return when {
            label == null -> WriteOutcome(false, "I'm not sure which place you mean.")
            backend != null -> forgetOverBackend(context, backend, label)
            else -> forgetUnconfigured(context, label)
        }
    }

    /** `Result.success(false)` from the backend means "no active row matched" and is reported as a
     * delete that did NOT happen - [com.kevin.legion.backend.PlacesBackend.softDelete]'s own
     * contract. A `Result.failure` is the request itself not completing, which is a different
     * sentence. One `when` rather than three returns, for detekt's `ReturnCount`. */
    private suspend fun forgetOverBackend(context: Context, backend: PlacesBackend, label: String): WriteOutcome {
        val result = backend.softDelete(label)
        return when (result.getOrNull()) {
            null -> WriteOutcome(false, "I found \"$label\" but couldn't remove it just now - nothing was deleted.")
            false -> WriteOutcome(false, "I don't have a saved place called \"$label\".")
            else -> {
                placeDao(context).delete(label)
                WriteOutcome(true, forgetAck(label))
            }
        }
    }

    /** Unconfigured (ticket 15 step 1): existence has to be checked against `places` directly now
     * (there is no server ACK to report a real/fake delete) - a label with no active row is
     * reported as never-found rather than issuing a soft-delete UPDATE that would match zero rows
     * and still speak a false "gone." */
    private suspend fun forgetUnconfigured(context: Context, label: String): WriteOutcome {
        ensureLegacyReconciled(context)
        val present = placeDao(context).getAll().any { it.label == label }
        if (!present) return WriteOutcome(false, "I don't have a saved place called \"$label\".")
        placeDao(context).delete(label)
        return WriteOutcome(true, forgetAck(label))
    }

    /** Deletes a saved place by label (used by the UI list). Returns true only on a confirmed
     * delete - false for "no such label" and for a write that did not actually land, same §7 fix as
     * [tagPlace]/[forgetPlace]. */
    suspend fun forget(context: Context, label: String): Boolean {
        val backend = backend(context)
        if (backend != null) {
            val didDelete = backend.softDelete(label).getOrElse { return false }
            if (didDelete) placeDao(context).delete(label)
            return didDelete
        }

        ensureLegacyReconciled(context)
        if (placeDao(context).getAll().none { it.label == label }) return false
        placeDao(context).delete(label)
        return true
    }

    /** All saved places (used by the UI list). Configured: reads the Room replica, never the
     * network - cache-first (ticket 01 ruling 9). Unconfigured: `places` too, as of ticket 15 step
     * 1 - reconciled against the engine first (see [ensureLegacyReconciled]) so nothing tagged
     * while engine-backed is silently dropped by the repoint. */
    suspend fun all(context: Context): List<TaggedPlace> {
        if (backend(context) == null) ensureLegacyReconciled(context)
        return placeDao(context).getAll()
    }

    /**
     * The label of the saved place the driver is currently within
     * [MATCH_RADIUS_M] of (nearest wins), or null. Reads whatever [all] returns - never performs
     * network I/O itself, configured or not, since this sits on the geofencing/assistant hot path.
     */
    suspend fun currentLabel(context: Context): String? {
        val loc = LocationController.state.value ?: return null
        return all(context)
            .map { it to distanceTo(loc, it) }
            .filter { it.second <= MATCH_RADIUS_M }
            .minByOrNull { it.second }
            ?.first?.label
    }

    /**
     * The engine's own sentence when it REFUSED a write, or null for every other failure shape.
     *
     * **Why the engine's words rather than this file's own** (django-engine ticket 14): a rule the
     * server holds and the phone does not can only be explained by the server. `PlaceSerializer`
     * refuses an over-long label with a sentence written to be read by a person - it names the
     * length, the limit and what a name that long usually is - and paraphrasing it here would put
     * a second, drifting copy of the rule in Kotlin, which is the thing that ticket exists to
     * remove. [engineRefusalSentence] only unwraps DRF's `{"field": ["..."]}` envelope; it does not
     * reword.
     *
     * **4xx only, deliberately.** [com.kevin.legion.backend.engine.EngineHttp.classify] files 5xx
     * under [EngineFailure.Refused] too, on the sound reasoning that the request DID reach the
     * engine and must not be treated as never-sent - but "The engine failed on its side (HTTP 500)"
     * is a fault, not a refusal, and reading it out as though the user had asked for something
     * disallowed would be the wrong sentence. Those keep the generic message. 401/403 never arrive
     * here at all; `classify` files them under [EngineFailure.Unauthorized].
     *
     * Returns null on the Supabase transport for everything, since that backend throws its own
     * exception type - which is correct rather than a gap: `public.places` has no length CHECK, so
     * there is no refusal on that path to relay (see the label cap in [normalizeLabel]).
     */
    private fun engineRefusal(t: Throwable): String? =
        ((t as? EngineHttpException)?.failure as? EngineFailure.Refused)
            ?.takeIf { it.status in HTTP_CLIENT_ERROR_RANGE }
            ?.let { engineRefusalSentence(it.body).takeIf(String::isNotBlank) }

    /** DRF's `status.HTTP_400_BAD_REQUEST` and the top of the 4xx block - the range [engineRefusal]
     * relays, being refusals the caller could act on rather than 5xx faults. */
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_LAST_CLIENT_ERROR = 499
    private val HTTP_CLIENT_ERROR_RANGE = HTTP_BAD_REQUEST..HTTP_LAST_CLIENT_ERROR

    private fun distanceTo(from: Location, place: TaggedPlace): Float {
        val out = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, place.latitude, place.longitude, out)
        return out[0]
    }

    /**
     * True when the write this call is about to make will genuinely reach an engine that enforces
     * the label length ITSELF - the only condition under which [normalizeLabel]'s local cap may
     * stand down.
     *
     * **Both halves are load-bearing, and dropping either one reopens ticket 14's trap.** The
     * transport must be [Transport.DJANGO] (Supabase's `public.places` has
     * `check (length(trim(label)) > 0)` and no length bound at all), AND a backend must actually
     * have resolved - a device set to Django with no engine address or token resolves to `null`
     * here and falls through to the unconfigured branch, which writes STRAIGHT INTO ROOM with no
     * server anywhere in the path. Testing only the transport would let a misheard sentence be
     * stored as a place name on exactly that device.
     *
     * Reading [EngineTransport] rather than the CLASS of [backend] is deliberate, and matches the
     * note in `BodyWriteThrough.write`: a [backendOverride] supplies a fake and says nothing about
     * which transport is live, so a test wanting this path flips the transport row.
     */
    private fun serverOwnsTheLabelCap(context: Context, backend: PlacesBackend?): Boolean =
        backend != null &&
            EngineTransport(context).transportFor(EngineBackends.ASPECT_PLACES) == Transport.DJANGO

    /**
     * Cleans a spoken label down to a name, or null when there is no usable name in it.
     *
     * **The 30-character cap now ALSO lives in the engine** (django-engine ticket 14):
     * `server/api/places.py`'s `PlaceSerializer.LABEL_MAX_LENGTH` refuses the same write with a
     * sentence [tagPlace] relays verbatim, and `supabase/migrations/20260907000200_places_label_length.sql`
     * is the matching CHECK (UNAPPLIED as of 2026-09-07). The server is the authority.
     *
     * **[capLength] is how that authority is honoured without losing the rule where the server has
     * none.** Found on the A25 on 2026-09-07: a 31-character label was refused HERE, with "I didn't
     * catch what to call this spot", and never reached the engine at all - so the sentence
     * [engineRefusal] exists to relay was structurally unreachable, and the server's careful
     * wording (it names the length, the limit, and what a name that long usually is) could never be
     * read by anybody. That is a rule the server owns being pre-empted by a client copy, which is
     * the exact drift ticket 14 was opened to remove.
     *
     * **The cap is lifted ONLY on the Django path, and that narrowness IS ticket 14's own trap.**
     * That ticket asks for the Kotlin copy to be deleted outright, on the reasoning that [tagPlace]
     * is a synchronous write-through with no outbox so the refusal reaches the user before anything
     * is stored. That is true of the DJANGO transport and of no other:
     * [com.kevin.legion.backend.engine.EngineTransport.DJANGO_BY_DEFAULT] is
     * `{"events", "checklists"}`, so an untouched install runs this aspect on Supabase - where
     * `public.places` has `check (length(trim(label)) > 0)` and no length bound at all - or, with
     * no project saved, straight into Room with no server in the path whatsoever. On both, deleting
     * this line means a misheard sentence is simply stored as a place name, which is the "let Room
     * accept a row the server would refuse" failure ticket 14 spells out for the eight guards it
     * protects. So the guard stays, and steps aside only for the one caller with a real server
     * behind it - [serverOwnsTheLabelCap] is how that is decided.
     *
     * **The deletion ticket 14 asks for is owed the day `places` joins `DJANGO_BY_DEFAULT`, and not
     * before.** Until then this is the pre-validation ADR 0042 explicitly permits ("a client may
     * pre-validate for a faster error message, never as the only check") - and on the transport
     * where it is no longer the only check, it is no longer applied either.
     *
     * **The blank-label refusal is NOT conditional and never becomes so.** It is one of ticket 14's
     * eight duplicates that must not be deleted, and unlike the length cap it costs nothing: a
     * blank label has no server sentence worth reaching, because there is nothing to name.
     */
    private fun normalizeLabel(raw: String, capLength: Boolean): String? {
        var s = raw.lowercase()
            .replace(Regex("\\bby the way\\b"), " ")
            .replace(Regex("\\b(location|place|spot|address)\\b"), " ")
            .replace(Regex("[.!?,]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.removePrefix("my ").removePrefix("the ").removePrefix("a ").trim()
        // Two distinct rules, named rather than run together, because only ONE of them is
        // conditional: the blank guard always applies (ticket 14's own list of what must not be
        // deleted), the length cap only where no server enforces one. They share a single `return`
        // to stay under detekt's ReturnCount, not because they are the same rule.
        val noNameInIt = s.isBlank()
        val tooLongForThisTransport = capLength && s.length > MAX_LOCAL_LABEL_LENGTH
        if (noNameInIt || tooLongForThisTransport) return null

        return when (s) {
            "work", "office", "job", "where i work" -> "work"
            "home", "house", "where i live", "live" -> "home"
            else -> s
        }
    }

    private fun ackFor(label: String): String {
        val where = if (label == "home" || label == "work") label else "\"$label\""
        return listOf(
            "Got it. This is $where now. Filed away with the rest of my baggage.",
            "Noted... $where, right here. I'll remember, don't you worry.",
            "Fine, $where it is. Pinned it.",
        ).random()
    }

    private fun forgetAck(label: String): String {
        val where = if (label == "home" || label == "work") label else "\"$label\""
        return listOf(
            "Done. Wiped $where off my map. One less thing rattling around back here.",
            "Forgotten. $where? Never heard of it.",
            "Gone. $where's off the books.",
        ).random()
    }
}
