package com.kevin.legion.openloops

import android.content.Context
import com.kevin.legion.backend.ChecklistsSync
import com.kevin.legion.backend.EventsSync
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.notes.AlarmScheduler

/**
 * After a yes (ADR 0085): bring the task the engine just made onto this phone through the
 * existing sync, and arm it. A dated reminder is an `events` row, so [EventsSync] then
 * [AlarmScheduler.rescheduleAll] (idempotent: one alarm per item); an undated to-do is a checklist
 * line, so [ChecklistsSync]. Without this, an engine-made reminder would wait for the next app
 * start to be armed, which is when `rescheduleAll` otherwise runs.
 */
object OpenLoopsSync {
    suspend fun pull(context: Context, taskKind: String) {
        val app = context.applicationContext
        val backends = EngineBackends(app)
        when (taskKind) {
            "reminder" -> {
                val events = checkNotNull(backends.eventsBackendAfterAuth()) { "no events backend" }
                EventsSync.pull(app, events)
                AlarmScheduler.rescheduleAll(app)
            }
            "todo" -> {
                val lists = checkNotNull(backends.checklistsBackend()) { "no checklists backend" }
                ChecklistsSync.pull(app, lists)
            }
            else -> Unit // "existing": he had already set it himself; nothing new to bring over
        }
    }
}
