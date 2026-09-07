package com.kevin.legion.backend.engine

import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.RemotePlace
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val PLACES_PATH = "/api/places/"

private fun parsePlaceTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

/**
 * One `public.places` row exactly as `server/api/places.py`'s `PlaceSerializer` renders it -
 * **measured against the live engine on 2026-09-07**, not inferred from the Python:
 * `{"id": "b021c3f8-...", "label": "fancy walmart", "latitude": 29.9114766666667,
 * "longitude": -95.7280833333333, "provenance": "USER", "created_at": "2026-08-26T21:11:36.342899Z",
 * "updated_at": "2026-08-26T21:11:36.342899Z", "deleted_at": null}`.
 *
 * `id`, `provenance` and `created_at` are on the wire and dropped here by
 * [engineSyncedJson]'s `ignoreUnknownKeys` - [RemotePlace] carries none of the three, because
 * `places` is keyed by [label] on both sides and has no `origin_guid` column at all (confirmed
 * against the live schema by `api/places.py`'s own module doc, and by
 * `20260825000500_aspect_places_fleet.sql`).
 */
@Serializable
private data class DjangoPlaceRow(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemote() = RemotePlace(
        label = label,
        latitude = latitude,
        longitude = longitude,
        updatedAtMs = parsePlaceTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/**
 * The `PUT /api/places/<label>/` body. Three fields, because three is every writable column
 * `PlaceSerializer` declares - `id`, `provenance`, `created_at`, `updated_at` and `deleted_at` are
 * all in its `read_only_fields`, and `SyncedSerializer.to_internal_value` **refuses an unknown
 * field with a 400 rather than dropping it**, so this class is the exact set and not a superset.
 *
 * **`label` rides in the body as well as the URL, and the URL is what counts.**
 * `SyncedModelViewSet.upsert` does `data[self.identity_field] = identity` before validating, so
 * the path segment is the authority on identity ("the same precedence
 * `ChecklistItemListCreateView.post` gives its `checklist_id`", in its own words). It is sent
 * anyway because a body that states its own key reads correctly in a log, and because a
 * percent-encoding bug would then show up as a disagreement rather than as a silently wrong row.
 */
@Serializable
private data class DjangoPlaceWrite(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * [PlacesBackend] over the household Django engine (`server/api/places.py` on
 * `server/api/synced.py`'s generic shape). Interchangeable with
 * [com.kevin.legion.backend.SupabasePlacesBackend] by construction: same interface, same
 * [RemotePlace] out, so [com.kevin.legion.location.PlaceController] cannot tell which one it holds.
 *
 * **This aspect is the only one whose PUT revives a tombstone, and that is deliberate on both
 * sides.** `PlaceViewSet.put_revives_tombstone = True` mirrors
 * `SupabasePlacesBackend.PlaceUpsertDto`'s explicit `deleted_at: null` - re-tagging a label that
 * was previously forgotten brings the row back, reproducing
 * [com.kevin.legion.data.local.TaggedPlace]'s old `OnConflictStrategy.REPLACE` semantics. Body and
 * memory do NOT do this; their upserts leave an existing tombstone alone. Nothing in this class
 * has to ask for the revival - it is a property of the route.
 *
 * **No outbox, and that is a behaviour this class deliberately does not add.**
 * [com.kevin.legion.location.PlaceController] is pure write-through: a failed write is spoken as a
 * failure and nothing is queued. Handing it a transport with durability the Supabase transport
 * does not have would make "did that save?" depend on which row the debug Setup screen has flipped,
 * which is the kind of divergence the whole two-transport period exists to avoid.
 */
class DjangoPlacesBackend(http: EngineHttp) : PlacesBackend {

    private val table = EngineSyncedTable(
        http = http,
        path = PLACES_PATH,
        rowSerializer = DjangoPlaceRow.serializer(),
        idOf = { it.id },
    )

    override suspend fun fetchActive(): Result<List<RemotePlace>> =
        translatingEngineCall("load your saved places") {
            // `?active=1`, narrowed server-side - see EngineSyncedTable.fetchActive. The
            // interface's contract is "every active (not soft-deleted) place row", and this is
            // the route that answers exactly that question.
            table.fetchActive().map { it.toRemote() }
        }

    override suspend fun upsert(label: String, latitude: Double, longitude: Double): Result<RemotePlace> =
        translatingEngineCall("save that place") {
            val body = engineSyncedJson.encodeToString(
                DjangoPlaceWrite.serializer(),
                DjangoPlaceWrite(label = label, latitude = latitude, longitude = longitude),
            )
            table.put(label, body).toRemote()
        }

    /**
     * `DELETE /api/places/<label>/`.
     *
     * **`Result.success(false)` means only "no row with that label at all" (404), never "the row
     * was already tombstoned"** - `SyncedModelViewSet.destroy` is deliberately idempotent and
     * answers 204 for a fresh delete and for a repeat alike, so this transport genuinely cannot
     * tell the two apart. That is a narrower `false` than
     * [com.kevin.legion.backend.SupabasePlacesBackend.softDelete]'s (which sees the affected-row
     * count), and it is narrow in the safe direction: `true` here always means the label is
     * tombstoned server-side now, which is the only thing [com.kevin.legion.location.PlaceController]
     * acts on. Identical to the narrowing [DjangoEventsBackend.softDelete]'s own doc comment
     * records.
     *
     * The consequence worth stating: forgetting a place twice speaks the "gone" ack twice on this
     * transport, where Supabase would say "I don't have a saved place called ..." the second time.
     */
    override suspend fun softDelete(label: String): Result<Boolean> =
        deletingEngineRow("remove that place") { table.deleteRow(label) }
}
