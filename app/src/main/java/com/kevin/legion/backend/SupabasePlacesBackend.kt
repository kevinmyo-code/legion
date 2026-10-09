package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val TABLE = "places"

/**
 * The wire shape sent on [SupabasePlacesBackend.upsert]. **`deletedAt` is deliberately a required
 * (non-defaulted) property, not `val deletedAt: String? = null`.** kotlinx-serialization's default
 * `encodeDefaults = false` omits any property whose value equals its declared default, and a
 * `null` default is still a default - so `= null` here would silently vanish from the outgoing
 * JSON, `ON CONFLICT (label) DO UPDATE` would leave the existing row's `deleted_at` untouched, and
 * re-tagging a label that was previously forgotten would stay invisible forever. Requiring the
 * caller to pass `deletedAt = null` explicitly forces it onto the wire every time, reproducing
 * [com.kevin.legion.data.local.TaggedPlace]'s old `OnConflictStrategy.REPLACE` upsert semantics
 * (whole-row replace) against a server that otherwise only patches named columns.
 */
@Serializable
private data class PlaceUpsertDto(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("deleted_at") val deletedAt: String?,
)

/** The wire shape for [SupabasePlacesBackend.softDelete] - a genuine partial PATCH, touching only
 * the tombstone column, unlike [PlaceUpsertDto]'s deliberate whole-row shape. */
@Serializable
private data class PlaceDeleteDto(
    @SerialName("deleted_at") val deletedAt: String,
)

/** The wire shape read back off `public.places` for every operation. */
@Serializable
private data class PlaceRowDto(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemotePlace() = RemotePlace(
        label = label,
        latitude = latitude,
        longitude = longitude,
        // OffsetDateTime, not java.time.Instant.parse - Supabase's timestamptz comes back with an
        // explicit numeric offset ("+00:00"), which Instant.parse's strict ISO_INSTANT rejects
        // (it wants a literal "Z"). Same choice as `location/AreaInfo.kt`'s OFFSET_CLOCK parsing.
        updatedAtMs = OffsetDateTime.parse(updatedAt).toInstant().toEpochMilli(),
        deleted = deletedAt != null,
    )
}

/**
 * [PlacesBackend]'s real implementation over Postgrest, against `public.places`
 * (`supabase/migrations/20260825000500_aspect_places_fleet.sql`). This is the one deliberately
 * untested seam in the places cutover - exercising it for real needs a live project - same
 * posture as `SupabaseAuth.kt`'s `LiveSupabaseAuthGateway`. [PlacesBackend] is the fake-friendly
 * interface; every branch here does nothing but translate exceptions and decode DTOs.
 */
class SupabasePlacesBackend(private val client: SupabaseClient) : PlacesBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(PlacesBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(PlacesBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(PlacesBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    override suspend fun fetchActive(): Result<List<RemotePlace>> = translating("load your saved places") {
        client.postgrest.from(TABLE)
            .select {
                filter { filter("deleted_at", FilterOperator.IS, "null") }
            }
            .decodeList<PlaceRowDto>()
            .map { it.toRemotePlace() }
    }

    /** [address] is not sent: `public.places` on Supabase has no `address` column (it was added
     * on the engine only, 2026-10-09, and this transport is retiring), so sending it would be
     * refused. The row comes back with no address, and the replica stores what came back. */
    override suspend fun upsert(
        label: String,
        latitude: Double,
        longitude: Double,
        address: String?,
    ): Result<RemotePlace> =
        translating("save that place") {
            client.postgrest.from(TABLE)
                .upsert(PlaceUpsertDto(label = label, latitude = latitude, longitude = longitude, deletedAt = null)) {
                    onConflict = "label"
                    select()
                }
                .decodeSingle<PlaceRowDto>()
                .toRemotePlace()
        }

    /**
     * Two requests, not one transaction - Postgrest has no rename route here, and this transport
     * is retiring (ADR 0044), so it gets the honest minimum rather than a new SQL function: [to]
     * is refused if it is already live, then upserted with [from]'s coordinates, then [from] is
     * tombstoned. Reminders are NOT moved on this transport; `remindersMoved` is null and the
     * controller moves them itself. If the second write fails the place exists under both names, which is
     * reported as a failure and loses nothing.
     */
    override suspend fun rename(from: String, to: String): Result<RemoteRename> = try {
        Result.success(renameOrThrow(from, to))
    } catch (e: PlacesBackendException) {
        // Every failure below is one: [translating] wraps each request's in this type.
        Result.failure(e)
    }

    private suspend fun renameOrThrow(from: String, to: String): RemoteRename {
        val active = fetchActive().getOrThrow()
        val source = active.firstOrNull { it.label == from }
            ?: throw PlacesBackendException("Nothing was renamed. There is no saved place called '$from'.")
        if (active.any { it.label == to }) {
            throw PlacesBackendException("Nothing was renamed. There is already a saved place called '$to'.")
        }
        val renamed = upsert(to, source.latitude, source.longitude, source.address).getOrThrow()
        softDelete(from).getOrThrow()
        return RemoteRename(renamed, remindersMoved = null)
    }

    override suspend fun softDelete(label: String): Result<Boolean> = translating("remove that place") {
        client.postgrest.from(TABLE)
            .update(PlaceDeleteDto(deletedAt = OffsetDateTime.now().toString())) {
                select()
                filter {
                    eq("label", label)
                    filter("deleted_at", FilterOperator.IS, "null")
                }
            }
            .decodeList<PlaceRowDto>()
            .isNotEmpty()
    }
}
