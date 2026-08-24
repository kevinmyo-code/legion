package com.kevin.legion.location

import android.content.Context
import android.location.Location
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.places.PlacesAspectSeeder
import org.json.JSONObject

/**
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`). Every function keeps its original
 * signature and return type - callers ([FleetScreen]'s saved-places UI, `location/ReminderController.kt`,
 * `service/LiveToolbox.kt`'s `tag_place`/`forget_place`/`show_saved_places`) flip onto the engine
 * unchanged (ADR 0035). Internally, every read/write now goes through [RecordStore] against the
 * Places aspect's `Place` record type (`docs/architecture/wave1-carve-2026-08-23.md`'s field
 * mapping, reused verbatim), and every [TaggedPlace] this file returns is an in-memory value object
 * built from an [EngineRecord] payload - **`places` gains zero writers from this file after
 * cutover** (see the cutover doc's reader/writer table).
 *
 * **Closes a known v1 gap the wave 1 carve doc flagged rather than fixed** ("`PlaceController`'s
 * own re-tag-overwrites-by-label behaviour is NOT reproduced by the engine copy alone... not
 * load-bearing for THIS wave since the old table remained the live path"). It is load-bearing now:
 * [tagPlace] looks up any existing active record with the same label and [RecordStore.update]s it
 * in place rather than always creating a second row, reproducing [TaggedPlace.label]'s old
 * `@PrimaryKey`/`OnConflictStrategy.REPLACE` upsert semantics by hand, since the engine has no
 * column-level uniqueness mechanism (`RecordStore`'s own class doc).
 */
object PlaceController {
    private const val MATCH_RADIUS_M = 150f

    private fun store(context: Context): RecordStore {
        val db = CarDatabase.getDatabase(context)
        return RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
    }

    private suspend fun schema(context: Context) = PlacesAspectSeeder.ensureSeeded(context)

    private fun toTaggedPlace(record: EngineRecord, fieldIds: Map<String, Long>): TaggedPlace {
        val payload = JSONObject(record.payload)
        return TaggedPlace(
            label = PayloadCodec.readString(payload, fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL)).orEmpty(),
            latitude = PayloadCodec.readDouble(payload, fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE)) ?: 0.0,
            longitude = PayloadCodec.readDouble(payload, fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE)) ?: 0.0,
            timestamp = record.updatedAt,
            deleted = record.deletedAt != null,
        )
    }

    private suspend fun activeRecords(context: Context): List<EngineRecord> {
        val db = CarDatabase.getDatabase(context)
        val sch = schema(context)
        return db.engineRecordDao().activeByRecordType(sch.recordTypeId)
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

        val sch = schema(context)
        val fieldValues = mapOf(
            sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL) to label,
            sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE) to loc.latitude,
            sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE) to loc.longitude,
        )
        val existing = activeRecords(context).firstOrNull {
            PayloadCodec.readString(JSONObject(it.payload), sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL)) == label
        }
        val store = store(context)
        // Senior review, 2026-08-24 (should-fix 3): a §7 outcome-verb violation - tagPlace used to
        // always return ackFor(label) regardless of whether the engine write actually landed, so a
        // failed RecordStore.create/update still spoke a confident "got it, pinned it." Both branches
        // now check the real WriteResult and only speak the ack on a genuine Success.
        val ok = if (existing != null) {
            store.update(existing.id, fieldValues) is RecordStore.WriteResult.Success
        } else {
            store.create(sch.recordTypeId, fieldValues, RecordProvenance.USER) is RecordStore.WriteResult.Success
        }
        return if (ok) ackFor(label) else "Something went wrong pinning that spot - it didn't save. Try again in a sec."
    }

    /** Deletes the saved place matching [rawLabel]. Returns a spoken ack, or an error if not found
     * or if the delete itself did not actually land (same §7 fix as [tagPlace]). */
    suspend fun forgetPlace(context: Context, rawLabel: String): String {
        val label = normalizeLabel(rawLabel) ?: return "I'm not sure which place you mean."
        val sch = schema(context)
        val existing = activeRecords(context).firstOrNull {
            PayloadCodec.readString(JSONObject(it.payload), sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL)) == label
        } ?: return "I don't have a saved place called \"$label\"."
        val trashed = store(context).delete(existing.id) is RecordStore.DeleteResult.Trashed
        return if (trashed) forgetAck(label) else "I found \"$label\" but couldn't remove it just now - nothing was deleted."
    }

    /** Deletes a saved place by label (used by the UI list). Returns true only on a confirmed
     * delete - false for "no such label" and for a write that did not actually land, same §7 fix as
     * [tagPlace]/[forgetPlace]. */
    suspend fun forget(context: Context, label: String): Boolean {
        val sch = schema(context)
        val existing = activeRecords(context).firstOrNull {
            PayloadCodec.readString(JSONObject(it.payload), sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL)) == label
        } ?: return false
        return store(context).delete(existing.id) is RecordStore.DeleteResult.Trashed
    }

    /** All saved places (used by the UI list). */
    suspend fun all(context: Context): List<TaggedPlace> {
        val sch = schema(context)
        return activeRecords(context).map { toTaggedPlace(it, sch.fieldIds) }
    }

    /**
     * The label of the saved place the driver is currently within
     * [MATCH_RADIUS_M] of (nearest wins), or null.
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
