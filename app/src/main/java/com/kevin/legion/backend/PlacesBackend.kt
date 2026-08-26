package com.kevin.legion.backend

/**
 * A `public.places` row as Postgres reports it (`supabase/migrations/20260825000500_aspect_places_fleet.sql`) -
 * the shape [SupabasePlacesBackend] hands back after every write, and the shape
 * [PlacesReconcile] copies into the Room replica ([com.kevin.legion.data.local.PlaceDao]).
 *
 * `updatedAtMs` is the server's own `updated_at`, converted to epoch millis - this is the "as of"
 * clock for the cache-first read path (ticket 01 ruling 9), not a device-local timestamp.
 */
data class RemotePlace(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/**
 * The seam Phase 4 exists to prove (`.scratch/backend-erp/issues/05-migration-path.md`, Phase 3
 * of the arc's status notes: "Phase 4 is where that bill comes due"). Places is the FIRST aspect
 * to get an injectable backend interface at all - Phase 3's load effects had no such seam and
 * were traced rather than tested for exactly that reason. [PlacesBackend] is deliberately narrow
 * (three functions, no `SupabaseClient` in its signature) so a test's fake implementation is a
 * few lines of in-memory `MutableMap`, never a real network-backed client.
 *
 * Every function returns [Result] rather than throwing, and rather than returning a nullable -
 * CLAUDE.md §7's outcome-verb rule needs a caller to be able to tell "the write failed" from "the
 * write succeeded and returned nothing", and a null return conflates those. [com.kevin.legion.location.PlaceController]
 * is the one production caller and never touches Room ahead of a [Result.success] from here
 * (ticket 01 ruling 9: "Room is written on server ACK, never ahead of it").
 */
interface PlacesBackend {
    /** Every active (not soft-deleted) place row, server-side. Used to refresh the Room replica -
     * never called from [com.kevin.legion.location.PlaceController.currentLabel] or any other
     * composition/hot-path read, which read the replica instead. */
    suspend fun fetchActive(): Result<List<RemotePlace>>

    /**
     * Upserts by [label] (the server's natural key - `places.label_unique`). Reactivates a
     * previously soft-deleted row under the same label, matching [com.kevin.legion.data.local.TaggedPlace]'s
     * old `@PrimaryKey`/`OnConflictStrategy.REPLACE` semantics (see [SupabasePlacesBackend]'s own
     * doc comment for why that needs an explicit `deleted_at: null` in the upload, not an implicit
     * one).
     */
    suspend fun upsert(label: String, latitude: Double, longitude: Double): Result<RemotePlace>

    /**
     * Soft-deletes the active row for [label]. `Result.success(false)` means "no active row
     * matched" - a normal, expected outcome (the label was never saved, or was already deleted) -
     * and must never be reported as a delete having happened. `Result.failure` means the request
     * itself did not complete (offline, rejected, etc).
     */
    suspend fun softDelete(label: String): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabasePlacesBackend] for every failure branch.
 * Owned by this package, never a raw supabase-kt/Ktor exception, so a fake implementation and a
 * test never need to construct one of those - same posture as [AuthRejectedException]/
 * [AuthNetworkException] in `SupabaseAuth.kt`. */
class PlacesBackendException(message: String) : Exception(message)
