package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One record the user silenced forever - CLAUDE.md sec 7's compulsion test, clause (d):
 * "silenceable forever in one instruction" (aspect-engine ticket 19, the Dates aspect build).
 *
 * Deliberately its own tiny table rather than a column on [EngineRecord]. Two reasons, both
 * load-bearing:
 *
 *  - A mute has to apply to ANY record carrying a [EngineRecord.dueAt], across ANY aspect -
 *    [com.kevin.legion.engine.dates.DatesAgenda] merges the Dates aspect with every OTHER record
 *    type's own due date (ticket 05 answer point 4: "agenda is a query... one fact, one place"),
 *    so a mute cannot live as a Dates-aspect-only field without breaking for a fleet service
 *    reminder or a future aspect's own dueAt.
 *  - [EngineRecord] is written only through [com.kevin.legion.engine.RecordStore]'s single door
 *    (ticket 03 answer point 3), and a mute is deliberately NOT a record edit - it must survive
 *    the record being updated, restored from trash, or re-imported by Google without needing to
 *    round-trip through that door, or without a Google re-import silently clobbering it the way an
 *    ordinary field would if [com.kevin.legion.calendar.CalendarImportController] overwrote it on
 *    every sync.
 *
 * [recordId] is the primary key, not a surrogate one, deliberately - "muted" is a fact about a
 * SPECIFIC record id, never about more than one, and a plain existence check
 * ([MutedReminderDao.isMuted]) is exactly what this shape gives for free.
 */
@Entity(tableName = "muted_reminders")
data class MutedReminder(
    @PrimaryKey val recordId: Long,
    val mutedAt: Long,
)
