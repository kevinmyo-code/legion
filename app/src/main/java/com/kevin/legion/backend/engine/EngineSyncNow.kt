package com.kevin.legion.backend.engine

import android.content.Context
import com.kevin.legion.backend.ChecklistsBackfill
import com.kevin.legion.backend.ChecklistsOutboxDrain
import com.kevin.legion.backend.ChecklistsSync
import com.kevin.legion.backend.EventsOutboxDrain
import com.kevin.legion.backend.EventsSync

/**
 * The debug Setup screen's "SYNC NOW" row: runs the engine's drain-then-backfill-then-pull for
 * whichever aspects are on [Transport.DJANGO], right now, ignoring every five-minute throttle, and
 * hands back ONE sentence saying what actually happened.
 *
 * **The sentence is the point.** Every automatic path in this file's neighbourhood reports only to
 * logcat (`MidnightEvents`), which is fine for a background pass and useless when Kevin is stood in
 * front of the phone trying to find out whether the laptop engine is reachable at all. This is the
 * one surface that answers that in words - and it answers it honestly: rows pulled, rows still
 * queued, or the engine's own failure sentence verbatim. **Nothing here says "synced" off anything
 * but a real result** (CLAUDE.md section 7); an aspect that is not on Django, or a device with no
 * token, is reported as exactly that rather than as a quiet success.
 *
 * **No `object` singleton** - a class taking [Context] and its [EngineBackends] as constructor
 * parameters, which is also how a test substitutes fakes.
 */
class EngineSyncNow(
    context: Context,
    private val backends: EngineBackends = EngineBackends(context),
) {
    private val app: Context = context.applicationContext

    suspend fun run(): String {
        val lines = mutableListOf<String>()
        lines += eventsLine()
        lines += checklistsLine()
        return lines.joinToString("\n")
    }

    private suspend fun eventsLine(): String {
        val backend = backends.eventsBackendAfterAuth()
            ?: return "Events: not on the engine (transport is Supabase, or no token on this device)."
        var failed: String? = null
        val line = guardingForeground(onFailure = { failed = it.message ?: "failed, with no message." }) {
            // Drain first, then pull - the same load-bearing ordering EventsOutboxDrain's own
            // class doc argues for, reproduced here rather than skipped because this button is
            // "do the foreground pass now", not a second, differently-ordered mechanism.
            val drained = EventsOutboxDrain.drain(app, backend)
            val pulled = EventsSync.pull(app, backend)
            "Events: pulled ${pulled.inserted} new, ${pulled.updated} updated, " +
                "${pulled.tombstoned} removed; sent ${drained.succeeded}, " +
                "${drained.stillPending} still queued, ${drained.poisoned} stuck."
        }
        return line ?: "Events: $failed"
    }

    private suspend fun checklistsLine(): String {
        val backend = backends.checklistsBackend()
            ?: return "Checklists: not on the engine (flip the checklists transport row, and sign in)."
        var failed: String? = null
        val line = guardingForeground(onFailure = { failed = it.message ?: "failed, with no message." }) {
            val drained = ChecklistsOutboxDrain.drain(app, backend)
            val backfilled = ChecklistsBackfill.run(app, backend)
            val pulled = ChecklistsSync.pull(app, backend)
            // The backfill reports a per-table stop rather than throwing (see its own rule 5), so
            // a partial run has to be said in words here or it would read as a clean pass.
            val failedNote = if (backfilled.failed.isEmpty()) {
                ""
            } else {
                " Backfill stopped: " + backfilled.failed.joinToString("; ")
            }
            "Checklists: pulled ${pulled.inserted} new, ${pulled.updated} updated, " +
                "${pulled.tombstoned} removed; backfilled ${backfilled.pushed}; " +
                "sent ${drained.succeeded}, ${drained.stillPending} still queued, " +
                "${drained.poisoned} stuck.$failedNote"
        }
        return line ?: "Checklists: $failed"
    }
}
