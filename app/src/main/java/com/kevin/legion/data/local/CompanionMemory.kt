package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A durable, distilled companion memory (companion-memory map, ticket 01,
 * 2026-07-22) - "key events, not verbatim." Written by the consolidation pass
 * (ticket 02, from a drive's [EpisodicTurn]s) and the reflection pass (ticket
 * 05, from clusters of these rows), never directly from raw transcript.
 *
 * Separate from the older, still-live `memories` table ([MemoryEntry]), which
 * stays wired to the explicit "remember X" tool/command. This table is the
 * consolidated/reflected side of memory; unifying the two recall paths is a
 * later ticket's call, not this one's.
 *
 * **[category] is THE load-bearing field.** It is what makes re-guardrailing
 * before public release (CLAUDE.md sec 9.1) a `WHERE category = ...` instead of
 * a rewrite - every writer of this table must set it honestly, even while the
 * experimental phase (2026-07-22, Kevin's explicit call) filters nothing on it.
 * See [Category].
 */
@Entity(tableName = "companion_memories")
data class CompanionMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    /** The distilled memory content, spoken-friendly. */
    val text: String,
    /** The sec 9.1 axis - see [Category]. Non-optional: every writer must decide. */
    val category: String,
    /** How this row was produced - see [Source]. */
    val source: String,
    /**
     * 1-10, how significant this memory is (Generative-Agents-style importance
     * score). Drives retrieval ranking + decay (ticket 03). Defaults to a
     * neutral midpoint; ticket 02's consolidation prompt is expected to set
     * this deliberately rather than leave the default.
     */
    @ColumnInfo(defaultValue = "5") val importance: Int = 5,
    val createdAt: Long,
    /** Bumped whenever this memory is actually recalled (ticket 03's decay input). */
    @ColumnInfo(defaultValue = "0") val lastAccessedAt: Long = 0,
    /**
     * Serialized embedding vector (format TBD by the layer-5 wiring ticket -
     * comma-joined floats or JSON), null until semantic recall is wired.
     * [embeddingModel] MUST be set alongside this - per the embeddings-
     * feasibility research (ticket 04), stored and query vectors must share
     * one model + dimensionality, so a bare vector with no model tag is
     * useless (and dangerous to compare against a future different model).
     *
     * **memory-supabase ticket: deliberately does NOT travel to Supabase.** CLAUDE.md's regenerate
     * test (`.scratch/live-sync/map.md`, "can this be recreated from something that survives?") says
     * yes here - an embedding is a pure function of [text], which DOES travel, so a phone that pulls
     * [text] back can always re-embed it locally once semantic recall is wired. Uploading it too
     * would ship a large, model-pinned blob for something the [text] column alone can reproduce, the
     * same reasoning the map gives for `daily_drive_logs` (a derived narrative, source survives).
     * [com.kevin.legion.backend.CompanionMemoryFields] carries no embedding field at all.
     */
    val embeddingVector: String? = null,
    /** Which model/dimensionality produced [embeddingVector] - see that field's own doc comment for
     * why this does not travel to Supabase either. */
    val embeddingModel: String? = null,
    // Portable cross-device identity (same shape as MemoryEntry.syncId) - cheap
    // to add now vs a migration later, even though cross-device sync of this
    // table is still fog (see the map's "Not yet specified").
    //
    // memory-supabase ticket (v60 -> v61): "still fog" resolved - this IS the natural key
    // [com.kevin.legion.backend.MemoryBackend] upserts by (`origin_guid`), same reuse-not-duplicate
    // reasoning as [MemoryEntry.syncId]'s own v61 doc comment.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** This row's server uuid once round-tripped - see [MemoryEntry.serverId]'s own doc comment. */
    val serverId: String? = null,
    /** LWW clock for [com.kevin.legion.backend.MemorySync.pull] - see [MemoryEntry.updatedAtMs]'s
     * own doc comment for why this is a separate column from [createdAt]/[lastAccessedAt] rather
     * than reusing either. */
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    /** Soft-delete tombstone - see [MemoryEntry.deleted]'s own doc comment. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) {
    /** The CLAUDE.md sec 9.1 axis every memory here is tagged with. */
    object Category {
        /** A fact about the CAR - service, quirks, history. Always safe to keep post-guardrail. */
        const val CAR_ANCHORED = "car_anchored"
        /** A fact about the DRIVER (preferences, routines) not about the relationship itself. */
        const val DRIVER = "driver"
        /** About the Moose-driver relationship/bond - the class sec 9.1 is most cautious about. */
        const val RELATIONSHIP = "relationship"
    }

    object Source {
        /** Distilled from a drive's raw transcript (ticket 02). */
        const val CONSOLIDATED = "consolidated"
        /** Synthesized from clusters of other memories (ticket 05). */
        const val REFLECTION = "reflection"
        /**
         * Written directly by a tool call, from something the user said in THAT SAME turn - never
         * distilled from a transcript later, never something the model concluded on its own
         * (goal-plans ticket 03). No schema change: [Source] is a TEXT column with no CHECK
         * constraint, so a new constant here is the same "widening an enum stored as TEXT is not a
         * migration" case CLAUDE.md sec 5 already documents for `IngestMethod`. The only current
         * writer is `generate_goal_plan`'s stated-constraint persistence
         * (`service/LiveToolbox.kt`) - a fitness constraint the user states out loud ("no gym
         * access") is exactly the kind of externally falsifiable fact CLAUDE.md sec 7 requires
         * memory to stay anchored to, and this tag is what lets a reader of the memory screen or
         * the audit trail tell "the app inferred this" from "the user said this," which
         * [CONSOLIDATED] and [REFLECTION] cannot - both describe an unattended pass reading a
         * transcript, not a tool call acting on the current turn.
         */
        const val STATED = "stated"
    }
}
