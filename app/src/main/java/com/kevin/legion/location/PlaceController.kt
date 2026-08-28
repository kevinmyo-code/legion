package com.kevin.legion.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabasePlacesBackend
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
     * real [SupabaseClientProvider] / network. Defaults to null, meaning "resolve normally" -
     * production code never sets this.
     */
    @Volatile
    internal var backendOverride: PlacesBackend? = null

    /** Resolves the active backend, or null when Supabase is not configured (the signal every
     * function below branches on). Never performs network I/O itself - it only builds a client
     * wrapper; the actual request happens in whichever [PlacesBackend] call the caller makes. */
    private fun backend(context: Context): PlacesBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabasePlacesBackend(client)
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
                return "Something went wrong pinning that spot - it didn't save. Try again in a sec."
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

    private fun distanceTo(from: Location, place: TaggedPlace): Float {
        val out = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, place.latitude, place.longitude, out)
        return out[0]
    }

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
