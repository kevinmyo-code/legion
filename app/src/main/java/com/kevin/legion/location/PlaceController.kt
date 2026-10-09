package com.kevin.legion.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.kevin.legion.backend.EventsSync
import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.PlacesBackendException
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.backend.engine.engineRefusalSentence
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.engine.migration.EnginePlacesRetirementCopy
import com.kevin.legion.notes.NotesController

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

    private const val PIN_FAILED = "Something went wrong pinning that spot - it didn't save. Try again in a sec."

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
     * What one [forgetPlace] or [renamePlace] call did. Same shape and same reason as
     * [com.kevin.legion.ai.AriaBrain.RememberOutcome]: these functions returned a bare `String` and
     * `LiveToolbox`'s dispatches hardcoded `success = true` over it, so every refusal - no label
     * heard, no GPS lock, the engine saying no in its own words, a Room write that threw, a label
     * that was never saved - reached the model as `{"success": true, "message": "<a failure>"}`.
     * §7's outcome-verb clause is conditioned on the tool RESULT, so a lying flag defeats it
     * outright. Corrected 2026-09-07 alongside the identical hole in `remember`.
     *
     * [message] is what the caller speaks either way; the Saved places screen renders it unchanged.
     */
    data class WriteOutcome(val success: Boolean, val message: String)

    /** Where a save pins: the coordinates always, the human address when one is known. */
    data class Spot(val latitude: Double, val longitude: Double, val address: String?)

    /**
     * What one save did (2026-10-09). Richer than [WriteOutcome] because two of its refusals are
     * questions the caller has to put to the user - which address, and whether to replace - and the
     * Saved places screen answers them with a picker and a dialog rather than a sentence.
     * [success] is true only for [Saved]; every other branch wrote nothing.
     */
    sealed interface SaveOutcome {
        val message: String
        val success: Boolean get() = this is Saved

        data class Saved(override val message: String, val place: TaggedPlace) : SaveOutcome

        /** The label already names a live place somewhere else. Nothing written; [spot] is what a
         * confirmed save will store, so the screen can confirm without a second lookup. */
        data class NeedsConfirm(
            override val message: String,
            val label: String,
            val existing: TaggedPlace,
            val spot: Spot,
        ) : SaveOutcome

        /** The address matched several places. Nothing written. */
        data class Choose(
            override val message: String,
            val label: String,
            val candidates: List<GeocodedAddress>,
        ) : SaveOutcome

        data class Refused(override val message: String) : SaveOutcome
    }

    /**
     * Test seam for the address lookups, same posture as [backendOverride]. Null means the real
     * [AndroidPlaceGeocoder].
     */
    @Volatile
    internal var geocoderOverride: PlaceGeocoder? = null

    private fun geocoder(context: Context): PlaceGeocoder = geocoderOverride ?: AndroidPlaceGeocoder(context)

    /**
     * Saves a place under [rawLabel] (normalized) - the one function behind `tag_place` and the
     * Saved places screen's two save buttons (ADR 0035).
     *
     * - [rawAddress] given: it is looked up ([PlaceGeocoder.forward]) and the RESOLVED address and
     *   its point are stored, so what is read back is what was saved. Several plausible matches or
     *   none: nothing is saved and the result says so ([SaveOutcome.Choose] / [SaveOutcome.Refused]).
     *   No lookup service or no connection: refused in words, never a guessed address.
     * - [rawAddress] null or blank: the current GPS fix, as `tag_place` always did, then reverse
     *   looked up for its address. A failed reverse lookup still saves by coordinates and says the
     *   address is unknown.
     *
     * **No silent replacement** (voice audit 2026-10-09, finding 2): when [rawLabel] already names a
     * live place more than [MATCH_RADIUS_M] away from the new spot, nothing is written unless
     * [confirmed] - the result says what would be lost, the `clear_codes` shape.
     */
    suspend fun savePlace(context: Context, rawLabel: String, rawAddress: String?, confirmed: Boolean): SaveOutcome {
        // Resolved BEFORE the label is normalized, because which transport this write is going to
        // decides whether the local length cap applies at all - see [serverOwnsTheLabelCap]. A pure
        // resolve with no network I/O (see [backend]'s own doc comment).
        val backend = backend(context)
        val label = normalizeLabel(rawLabel, capLength = !serverOwnsTheLabelCap(context, backend))
            ?: return SaveOutcome.Refused(PlaceSentences.NO_LABEL)
        val address = rawAddress?.trim()?.takeIf { it.isNotEmpty() }
        return when (val resolved = if (address != null) lookUp(context, label, address) else here(context)) {
            is Resolved.Ready -> commit(context, backend, label, resolved.pinned, confirmed)
            is Resolved.Stop -> resolved.outcome
        }
    }

    /**
     * Saves [spot] as it is, with no lookup - the screen's path after the user picked one of a
     * [SaveOutcome.Choose]'s candidates or confirmed a [SaveOutcome.NeedsConfirm]. The same commit,
     * confirm gate and sentences as [savePlace].
     */
    suspend fun savePlaceAt(context: Context, rawLabel: String, spot: Spot, confirmed: Boolean): SaveOutcome {
        val backend = backend(context)
        val label = normalizeLabel(rawLabel, capLength = !serverOwnsTheLabelCap(context, backend))
            ?: return SaveOutcome.Refused(PlaceSentences.NO_LABEL)
        return commit(context, backend, label, Pinned(spot, here = false), confirmed)
    }

    /** Where a save will pin and how it was found: [here] for the current fix, [lookupFailure] when
     * its reverse lookup could not answer (said in words, never guessed). */
    private data class Pinned(val spot: Spot, val here: Boolean, val lookupFailure: String? = null)

    /** Where a save will pin, or the outcome that stops it before anything is written. */
    private sealed interface Resolved {
        data class Ready(val pinned: Pinned) : Resolved

        data class Stop(val outcome: SaveOutcome) : Resolved
    }

    private suspend fun lookUp(context: Context, label: String, address: String): Resolved =
        when (val found = geocoder(context).forward(address)) {
            is ForwardLookup.Unavailable ->
                Resolved.Stop(SaveOutcome.Refused(PlaceSentences.lookupUnavailable(found.why)))
            ForwardLookup.NotFound -> Resolved.Stop(SaveOutcome.Refused(PlaceSentences.lookupNotFound(address)))
            is ForwardLookup.Found -> when (val choice = AddressChoice.decide(address, found.results)) {
                is AddressChoice.Decision.One -> choice.address.let {
                    Resolved.Ready(Pinned(Spot(it.latitude, it.longitude, it.address), here = false))
                }
                is AddressChoice.Decision.Several -> Resolved.Stop(
                    SaveOutcome.Choose(PlaceSentences.severalMatches(choice.candidates), label, choice.candidates),
                )
            }
        }

    /** The current fix, reverse looked up. A failed lookup is not a failed save: the coordinates
     * are still the place, and the sentence says the address is unknown. */
    private suspend fun here(context: Context): Resolved {
        val loc = LocationController.state.value
            ?: return Resolved.Stop(SaveOutcome.Refused(PlaceSentences.NO_FIX))
        val pinned = when (val r = geocoder(context).reverse(loc.latitude, loc.longitude)) {
            is ReverseLookup.Found -> Pinned(Spot(loc.latitude, loc.longitude, r.address), here = true)
            ReverseLookup.NotFound -> Pinned(Spot(loc.latitude, loc.longitude, null), here = true)
            is ReverseLookup.Unavailable -> Pinned(Spot(loc.latitude, loc.longitude, null), here = true, r.why)
        }
        return Resolved.Ready(pinned)
    }

    /**
     * The confirm gate, then the write. The existing place is read from the replica (cache-first,
     * ticket 01 ruling 9), the same read the screen's list shows. Moving a label less than
     * [MATCH_RADIUS_M] is not replacing a location, so it needs no confirm.
     */
    private suspend fun commit(
        context: Context,
        backend: PlacesBackend?,
        label: String,
        pinned: Pinned,
        confirmed: Boolean,
    ): SaveOutcome {
        val spot = pinned.spot
        val replaced = all(context).firstOrNull { it.label == label }
            ?.takeIf { distanceM(it.latitude, it.longitude, spot) > MATCH_RADIUS_M }
        if (replaced != null && !confirmed) {
            return SaveOutcome.NeedsConfirm(
                PlaceSentences.confirmReplace(label, replaced, spot.address),
                label,
                replaced,
                spot,
            )
        }
        val written = if (backend != null) {
            writeOverBackend(context, backend, label, spot)
        } else {
            writeUnconfigured(context, label, spot)
        }
        return written.fold(
            onSuccess = { place ->
                // A spot with no address is always the user's own fix (a lookup result always has
                // one) - including one the screen re-sends after a replace confirm - so it gets the
                // "where you are now, address unknown" sentence, never "at ." over nothing.
                val address = spot.address
                val sentence = if (pinned.here || address == null) {
                    PlaceSentences.savedHere(label, place.address ?: address, pinned.lookupFailure, replaced)
                } else {
                    PlaceSentences.savedAtAddress(label, address, replaced)
                }
                SaveOutcome.Saved(sentence, place)
            },
            onFailure = { SaveOutcome.Refused(it.message ?: PIN_FAILED) },
        )
    }

    /** The configured write. Room is written ONLY after a genuine server ACK (ticket 01 ruling 9) -
     * never ahead of it, and never on the failure branch. A REFUSAL is relayed in the engine's own
     * words; anything else keeps the generic sentence (see [engineRefusal]). */
    private suspend fun writeOverBackend(
        context: Context,
        backend: PlacesBackend,
        label: String,
        spot: Spot,
    ): Result<TaggedPlace> {
        val remote = backend.upsert(label, spot.latitude, spot.longitude, spot.address).getOrElse {
            return Result.failure(IllegalStateException(engineRefusal(it) ?: PIN_FAILED))
        }
        val place = TaggedPlace(
            label = remote.label,
            latitude = remote.latitude,
            longitude = remote.longitude,
            timestamp = remote.updatedAtMs,
            deleted = remote.deleted,
            // The transport's own answer, not what was sent: Supabase has no address column and
            // answers null, and the replica must agree with the server it mirrors.
            address = remote.address,
        )
        placeDao(context).upsert(place)
        return Result.success(place)
    }

    /**
     * The unconfigured write (ticket 15 step 1): `places` is the single store for this branch, so
     * saving is a plain upsert on its `@PrimaryKey` label.
     *
     * **The failure is WORDED, not thrown**: the Saved places screen calls this from a bare
     * `scope.launch` with no handler, so a throw there is an unhandled coroutine exception rather
     * than anything the user can read. Section 7 wants a failure result that says in words what did
     * not happen, and a crash says nothing at all.
     */
    private suspend fun writeUnconfigured(context: Context, label: String, spot: Spot): Result<TaggedPlace> {
        ensureLegacyReconciled(context)
        val place = TaggedPlace(
            label = label,
            latitude = spot.latitude,
            longitude = spot.longitude,
            timestamp = System.currentTimeMillis(),
            deleted = false,
            address = spot.address,
        )
        return try {
            placeDao(context).upsert(place)
            Result.success(place)
        } catch (e: Exception) {
            Log.w(TAG, "unconfigured save failed for $label: ${e.message}")
            Result.failure(IllegalStateException(PIN_FAILED))
        }
    }

    /**
     * Deletes the saved place matching [rawLabel] - only when [confirmed]. Unconfirmed, nothing is
     * deleted and the result names what would be lost (label and address), the `clear_codes` shape
     * (voice audit 2026-10-09: a rename was carried out as an unconfirmed forget + tag).
     *
     * **"No such saved place" is [WriteOutcome.success] = false**, not a quiet success: nothing was
     * deleted, so nothing may be spoken with an outcome verb over it. Same reading
     * [com.kevin.legion.backend.PlacesBackend.softDelete]'s own `Result.success(false)` carries.
     */
    suspend fun forgetPlace(context: Context, rawLabel: String, confirmed: Boolean): WriteOutcome {
        // Same ordering and the same reason as [savePlace] - and the cap has to be lifted on BOTH
        // or the pair disagrees: a label the engine accepted for a save must still be nameable when
        // the user asks to forget it.
        val backend = backend(context)
        val label = normalizeLabel(rawLabel, capLength = !serverOwnsTheLabelCap(context, backend))
        return when {
            label == null -> WriteOutcome(false, "I'm not sure which place you mean.")
            !confirmed -> all(context).firstOrNull { it.label == label }
                ?.let { WriteOutcome(false, PlaceSentences.confirmForget(it)) }
                ?: WriteOutcome(false, PlaceSentences.noSuchPlace(label))
            backend != null -> forgetOverBackend(context, backend, label)
            else -> forgetUnconfigured(context, label)
        }
    }

    /** `Result.success(false)` from the backend means "no active row matched" and is reported as a
     * delete that did NOT happen - [com.kevin.legion.backend.PlacesBackend.softDelete]'s own
     * contract. A `Result.failure` is the request itself not completing, which is a different
     * sentence. */
    private suspend fun forgetOverBackend(context: Context, backend: PlacesBackend, label: String): WriteOutcome {
        val result = backend.softDelete(label)
        return when (result.getOrNull()) {
            null -> WriteOutcome(false, "I found \"$label\" but couldn't remove it just now - nothing was deleted.")
            false -> WriteOutcome(false, PlaceSentences.noSuchPlace(label))
            else -> {
                placeDao(context).delete(label)
                WriteOutcome(true, PlaceSentences.forgot(label))
            }
        }
    }

    /** Unconfigured (ticket 15 step 1): existence is checked against `places` directly (there is no
     * server ACK to report a real/fake delete). */
    private suspend fun forgetUnconfigured(context: Context, label: String): WriteOutcome {
        ensureLegacyReconciled(context)
        val present = placeDao(context).getAll().any { it.label == label }
        if (!present) return WriteOutcome(false, PlaceSentences.noSuchPlace(label))
        placeDao(context).delete(label)
        return WriteOutcome(true, PlaceSentences.forgot(label))
    }

    /**
     * A new name for a saved place, keeping its coordinates and address (voice audit 2026-10-09,
     * finding 2: "rename Home to Katie House" ran forget then tag, which silently re-pinned home
     * wherever the phone was). Refuses in words when [rawTo] already names a live place.
     *
     * Configured: one engine write (`POST /api/places/<from>/rename/`), which also moves live
     * place-triggered reminders in the same transaction; the replica follows on ACK. Unconfigured,
     * or a transport that does not move reminders: the reminders are moved here, through
     * [NotesController.setPlaceTrigger], the same write `set_reminder` makes.
     */
    suspend fun renamePlace(context: Context, rawFrom: String, rawTo: String): WriteOutcome {
        val backend = backend(context)
        val cap = !serverOwnsTheLabelCap(context, backend)
        val from = normalizeLabel(rawFrom, cap)
        val to = normalizeLabel(rawTo, cap)
        return when {
            from == null -> WriteOutcome(false, PlaceSentences.RENAME_NO_FROM)
            to == null -> WriteOutcome(false, PlaceSentences.RENAME_NO_TO)
            from == to -> WriteOutcome(false, PlaceSentences.renameSame(from))
            backend != null -> renameOverBackend(context, backend, from, to)
            else -> renameUnconfigured(context, from, to)
        }
    }

    private suspend fun renameOverBackend(
        context: Context,
        backend: PlacesBackend,
        from: String,
        to: String,
    ): WriteOutcome {
        val renamed = backend.rename(from, to).getOrElse {
            val said = engineRefusal(it)
                ?: (it as? PlacesBackendException)?.message
                ?: PlaceSentences.RENAME_FAILED
            return WriteOutcome(false, said)
        }
        val remote = renamed.place
        val place = TaggedPlace(
            label = remote.label,
            latitude = remote.latitude,
            longitude = remote.longitude,
            timestamp = remote.updatedAtMs,
            deleted = remote.deleted,
            address = remote.address,
        )
        placeDao(context).upsert(place)
        placeDao(context).delete(from)
        val moved = renamed.remindersMoved
        return if (moved != null) {
            // The engine moved them; the events replica learns on its next pull.
            if (moved > 0) EventsSync.maybeAutoPull(context)
            WriteOutcome(true, PlaceSentences.renamed(from, place, moved, remindersFailed = 0))
        } else {
            val (ok, failed) = moveReminders(context, from, to)
            WriteOutcome(true, PlaceSentences.renamed(from, place, ok, failed))
        }
    }

    private suspend fun renameUnconfigured(context: Context, from: String, to: String): WriteOutcome {
        ensureLegacyReconciled(context)
        val places = placeDao(context).getAll()
        val source = places.firstOrNull { it.label == from }
        return when {
            source == null -> WriteOutcome(false, PlaceSentences.noSuchPlace(from))
            places.any { it.label == to } -> WriteOutcome(false, PlaceSentences.renameTaken(to))
            else -> try {
                val place = source.copy(label = to, timestamp = System.currentTimeMillis(), deleted = false)
                placeDao(context).upsert(place)
                placeDao(context).delete(from)
                val (ok, failed) = moveReminders(context, from, to)
                WriteOutcome(true, PlaceSentences.renamed(from, place, ok, failed))
            } catch (e: Exception) {
                Log.w(TAG, "unconfigured rename failed for $from: ${e.message}")
                WriteOutcome(false, PlaceSentences.RENAME_FAILED)
            }
        }
    }

    /** Re-points every live reminder on [from] at [to]. (moved, failed). */
    private suspend fun moveReminders(context: Context, from: String, to: String): Pair<Int, Int> {
        val items = ReminderController.activeFor(context, from)
        val moved = items.count { NotesController.setPlaceTrigger(context, it, to) != null }
        return moved to (items.size - moved)
    }

    private fun distanceM(lat: Double, lng: Double, spot: Spot): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lng, spot.latitude, spot.longitude, out)
        return out[0]
    }

    /** Deletes a saved place by label (used by the UI list). Returns true only on a confirmed
     * delete - false for "no such label" and for a write that did not actually land, same §7 fix as
     * [savePlace]/[forgetPlace]. */
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
     * sentence [savePlace] relays verbatim, and `supabase/migrations/20260907000200_places_label_length.sql`
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
     * That ticket asks for the Kotlin copy to be deleted outright, on the reasoning that [savePlace]
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
}
