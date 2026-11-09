package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.memory.LegacyMemoryRow
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.memory.MemoryWording

/**
 * Stated goal-plan constraints ("no gym access"), kept as the member's own private memories on the
 * engine (memory v2, ADR 0075) since 2026-11-09. Until then they were written to the legacy
 * `companion_memories` table through `MemoryWriteThrough`, so a constraint stated after the one-time
 * import never reached member memories, the Memory screen, the web or Claude.
 *
 * A constraint is a memory whose text starts with [GoalPlanAgent.CONSTRAINT_PREFIX]; it shows on the
 * Memory screen like any other and is forgotten the same way.
 *
 * - **Read**: the engine's list through [MemoryController.list], which answers from the phone's
 *   cache when the engine is out of reach. With no cache either, the legacy Room rows written before
 *   the move ([legacyRows]), so a plan generated offline still carries what was said before.
 * - **Write**: [MemoryController.remember], engine only. Offline it is refused in words and nothing
 *   is queued, as for any memory.
 * - **Backfill, once**: the legacy rows go to the engine's import route, keyed by their `syncId`
 *   (the `origin_guid` the legacy sync sent), the same key `manage.py import_legacy_memories` uses,
 *   so the two can run in either order without a duplicate. [isBackfilled] is set only once the
 *   engine says Ok, so a failed upload is retried on the next read.
 */
class GoalPlanConstraints(
    private val controller: MemoryController,
    private val legacyRows: suspend () -> List<CompanionMemory>,
    private val isBackfilled: () -> Boolean,
    private val markBackfilled: () -> Unit,
) {

    /** Every constraint on file, prefix stripped, oldest first (the order they were said). */
    suspend fun read(): List<String> {
        backfillOnce()
        val memories = when (val outcome = controller.list()) {
            is MemoryOutcome.Ok -> outcome.value.memories
                .filter { it.aboutYou && it.text.startsWith(GoalPlanAgent.CONSTRAINT_PREFIX) }
                .sortedBy { it.createdAtMs }
                .map { it.text }
            else -> legacyRows().map { it.text }
        }
        return memories.map { it.removePrefix(GoalPlanAgent.CONSTRAINT_PREFIX).trim() }
    }

    /**
     * Saves [text] as a constraint unless one already says the same (trimmed, case-insensitive).
     * Null when saved or already on file; otherwise the sentence saying why nothing was saved.
     */
    suspend fun add(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank() || read().any { it.equals(trimmed, ignoreCase = true) }) return null
        return when (
            val outcome = controller.remember(
                GoalPlanAgent.CONSTRAINT_PREFIX + trimmed,
                shared = false,
                source = MemoryController.SOURCE_PHONE,
            )
        ) {
            is MemoryOutcome.Ok -> null
            else -> MemoryWording.writeFailed(outcome, "saved")
        }
    }

    private suspend fun backfillOnce() {
        if (isBackfilled()) return
        val rows = legacyRows().map {
            LegacyMemoryRow(
                store = LEGACY_STORE,
                originGuid = it.syncId,
                text = it.text,
                category = it.category,
                source = it.source,
                importance = it.importance,
                loggedAtMs = it.createdAt,
            )
        }
        if (controller.importLegacy(rows) is MemoryOutcome.Ok) markBackfilled()
    }

    companion object {
        private const val LEGACY_STORE = "companion_memories"
        private const val PREFS = "goal_plan_constraints"
        private const val KEY_BACKFILLED = "backfilled_to_member_memory"

        /** The production wiring: [controller], the legacy Room rows, a flag in shared preferences. */
        fun forContext(context: Context, controller: MemoryController): GoalPlanConstraints {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return GoalPlanConstraints(
                controller = controller,
                legacyRows = {
                    CarDatabase.getDatabase(app).companionMemoryDao()
                        .byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)
                },
                isBackfilled = { prefs.getBoolean(KEY_BACKFILLED, false) },
                markBackfilled = { prefs.edit().putBoolean(KEY_BACKFILLED, true).apply() },
            )
        }
    }
}
