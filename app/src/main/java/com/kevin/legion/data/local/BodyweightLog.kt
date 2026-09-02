package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kevin.legion.plan.TrustTier

/**
 * A single bodyweight measurement (`.scratch/legion-shape/issues/08-workouts-domain.md` D23):
 * "Bodyweight is its OWN thing, a plain reported measurement with its own target later. Not a
 * field on the workout log." Kevin named workouts and weight together in the same request; they
 * are recorded separately here because one is an activity (a set performed) and the other is a
 * measurement (a state of the body) - conflating them onto one row would make "how much weight
 * did you lift" and "how much do you weigh" the same column, which they are not.
 *
 * [trustTier] is always [TrustTier.REPORTED] for a voice-logged reading, same reasoning as
 * [WorkoutSetLog.trustTier] - stored explicitly rather than assumed, per ticket 05 D4.
 *
 * No target table exists for bodyweight yet ("its own target later" - D23's own words defer it);
 * this entity is deliberately just the measurement.
 *
 * **[guid]/[serverId]/[updatedAtMs]/[deleted] joined v59 -> v60 (body-supabase ticket), mirroring
 * [Event]'s own four sync columns field for field** - see that entity's own doc comment for the
 * full reasoning. [guid] is minted once at row creation and carried forward unchanged; it is what
 * [com.kevin.legion.backend.SupabaseBodyBackend] upserts ON (`origin_guid` server-side), so unlike
 * [Event] there is no create/update fork on this table at all - every write is a genuine upsert
 * keyed on an identity this row has had since the moment it was written, never a client-minted
 * placeholder that later needs replacing. [serverId] is filled in for real by the next
 * [com.kevin.legion.backend.BodySync.pull] after a push, same as [Event.serverId]'s own v59 doc
 * comment describes, but nothing on the write path ever needs to read it back to address a future
 * write - [guid] already does that job.
 */
@Entity(tableName = "bodyweight_logs", indices = [Index(value = ["guid"], unique = true)])
data class BodyweightLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weightValue: Double,
    /** "lbs" or "kg". */
    val weightUnit: String,
    val loggedAt: Long,
    val trustTier: TrustTier,
    @ColumnInfo(defaultValue = "''") val guid: String = "",
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
