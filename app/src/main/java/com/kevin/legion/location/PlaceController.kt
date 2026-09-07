package com.kevin.legion.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
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
     * Tags the current GPS location under [rawLabel] (normalized). Returns a spoken ack.
     *
     * Address-based tagging (resolving a spoken address via forward geocoding) is not
     * supported - only "tag where I am right now".
     */
    suspend fun tagPlace(context: Context, rawLabel: String): String {
        val label = normalizeLabel(rawLabel)
            ?: return "I didn't catch what to call this spot — try something like 'home' or 'work'."

        val loc = LocationController.state.value
            ?: return "I don't have a GPS lock yet, so I can't pin this spot. Give it a sec and try again."

        val backend = backend(context)
        if (backend != null) {
            val remote = backend.upsert(label, loc.latitude, loc.longitude).getOrElse {
                // A REFUSAL is relayed in the engine's own words; anything else keeps the generic
                // sentence. See [engineRefusal] for why the two are not the same thing.
                return engineRefusal(it)
                    ?: "Something went wrong pinning that spot - it didn't save. Try again in a sec."
            }
            // Room is written ONLY here, after a genuine server ACK (ticket 01 ruling 9) - never
            // ahead of it, and never on the failure branch above.
            placeDao(context).upsert(
                TaggedPlace(
                    label = remote.label,
                    latitude = remote.latitude,
                    longitude = remote.longitude,
                    timestamp = remote.updatedAtMs,
                    deleted = remote.deleted,
                )
            )
            return ackFor(label)
        }

        // Unconfigured (ticket 15 step 1): `places` is now the single store for this branch too,
        // so tagging is a plain upsert on its `@PrimaryKey` label - the exact re-tag-overwrites
        // semantics [TaggedPlace]'s own doc comment describes, reproduced here instead of by hand
        // against the engine the way the retired code did.
        //
        // **The failure is WORDED, not thrown, and that was corrected rather than assumed.** Step 1
        // originally let a Room failure propagate, on the reasoning that a suspend insert either
        // completes or throws so there is nothing to check. That is only safe for a function
        // reachable solely through a voice tool, because `LiveSessionController.dispatch` wraps
        // every tool call in a catch-all. `tagPlace` is NOT only that: `ui/FleetScreen.kt` calls it
        // from a bare `scope.launch` with no handler, so a throw there is an unhandled coroutine
        // exception rather than anything the user can read. Section 7 wants a failure result that
        // says in words what did not happen, and a crash says nothing at all. Found while tracing
        // the identical question for `PantryController.writeReceipt` in step 2.
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
            ackFor(label)
        } catch (e: Exception) {
            Log.w(TAG, "unconfigured tagPlace write failed for $label: ${e.message}")
            "Something went wrong pinning that spot - it didn't save. Try again in a sec."
        }
    }

    /** Deletes the saved place matching [rawLabel]. Returns a spoken ack, or an error if not found
     * or if the delete itself did not actually land (same §7 fix as [tagPlace]). */
    suspend fun forgetPlace(context: Context, rawLabel: String): String {
        val label = normalizeLabel(rawLabel) ?: return "I'm not sure which place you mean."

        val backend = backend(context)
        if (backend != null) {
            val didDelete = backend.softDelete(label).getOrElse {
                return "I found \"$label\" but couldn't remove it just now - nothing was deleted."
            }
            if (!didDelete) return "I don't have a saved place called \"$label\"."
            placeDao(context).delete(label)
            return forgetAck(label)
        }

        // Unconfigured (ticket 15 step 1): existence has to be checked against `places` directly
        // now (there is no server ACK to report a real/fake delete) - a label with no active row
        // is reported as never-found rather than issuing a soft-delete UPDATE that would match zero
        // rows and still speak a false "gone."
        ensureLegacyReconciled(context)
        placeDao(context).getAll().firstOrNull { it.label == label }
            ?: return "I don't have a saved place called \"$label\"."
        placeDao(context).delete(label)
        return forgetAck(label)
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
     * Cleans a spoken label down to a name, or null when there is no usable name in it.
     *
     * **The 30-character cap now ALSO lives in the engine** (django-engine ticket 14):
     * `server/api/places.py`'s `PlaceSerializer.LABEL_MAX_LENGTH` refuses the same write with a
     * sentence [tagPlace] relays verbatim, and `supabase/migrations/20260907000200_places_label_length.sql`
     * is the matching CHECK (UNAPPLIED as of 2026-09-07). The server is the authority.
     *
     * **The Kotlin copy is still here, and deleting it today would lose the rule rather than move
     * it.** Ticket 14 asks for the deletion, on the reasoning that [tagPlace] is a synchronous
     * write-through with no outbox so the refusal reaches the user before anything is stored. That
     * is true of the DJANGO transport. It is not true of the other two, and `places` is on neither
     * of them by choice: [com.kevin.legion.backend.engine.EngineTransport.DJANGO_BY_DEFAULT] is
     * `{"events", "checklists"}`, so an untouched install runs this aspect on Supabase - where
     * `public.places` has `check (length(trim(label)) > 0)` and no length bound at all - or, with
     * no project saved, straight into Room with no server in the path whatsoever. On both, deleting
     * this line means a misheard sentence is simply stored as a place name.
     *
     * **So the deletion is owed the day `places` joins `DJANGO_BY_DEFAULT`, and not before.** Until
     * then this is the pre-validation ADR 0042 explicitly permits ("a client may pre-validate for a
     * faster error message, never as the only check") - it is no longer the only check, which is
     * the half of that sentence this ticket actually fixed.
     */
    private fun normalizeLabel(raw: String): String? {
        var s = raw.lowercase()
            .replace(Regex("\\bby the way\\b"), " ")
            .replace(Regex("\\b(location|place|spot|address)\\b"), " ")
            .replace(Regex("[.!?,]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.removePrefix("my ").removePrefix("the ").removePrefix("a ").trim()
        if (s.isBlank() || s.length > 30) return null

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
