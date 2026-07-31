package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One driver-or-companion turn from a Gemini Live conversation, captured
 * verbatim as it completes (companion-memory map, ticket 01, 2026-07-22).
 *
 * This is the RAW buffer, not durable memory: a background consolidation pass
 * (ticket 02) reads a session's turns, distills them into scored
 * [CompanionMemory] rows, then clears the turns that fed it. A row that sits
 * here un-consolidated is just unprocessed input, not something Moose "knows"
 * yet - nothing reads this table for conversation.
 *
 * No `syncId`: transient by design, cleared after consolidation, never meant
 * to cross devices - the durable artifact ([CompanionMemory]) is what syncs.
 */
@Entity(tableName = "episodic_turns")
data class EpisodicTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Groups turns from one Live connection (minted at connect, held for the session's life). */
    val sessionId: String,
    val vehicleId: String,
    /** "driver" or "companion" - see [Role]. */
    val role: String,
    val text: String,
    val timestamp: Long,
) {
    object Role {
        const val DRIVER = "driver"
        const val COMPANION = "companion"
    }
}
