package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.data.local.MemoryAudit
import com.kevin.legion.data.local.MemoryEntry
import com.kevin.legion.data.local.OutboxTarget

/**
 * Per-table install-scoped high-water mark for [MemoryBackfill]'s one-time (then perpetually
 * cheap-no-op) upload - same shape as [BodyBackfillCursor]. See that object's own class doc for
 * the full reasoning; unchanged here. `memory_audit` uses the SAME constant name
 * (`"memory_audit"`) as [OutboxTarget] would if it had one, purely as a stable prefs key - see
 * this file's own class doc for why that table has no [OutboxTarget] constant at all.
 */
internal object MemoryBackfillCursor {
    private const val PREFS = "memory_backfill_cursor"
    private const val MEMORY_AUDIT_KEY = "memory_audit"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBackfilledId(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, id: Long) {
        prefs(context).edit().putLong(table, id).apply()
    }

    const val MEMORY_AUDIT_TABLE = MEMORY_AUDIT_KEY
}

/**
 * The one-time backfill for memory: every local row that predates write-through
 * (`MemoryWriteThrough`/`MemoryOutbox.kt`) has no path to the server at all for `memories`/
 * `companion_memories`, because write-through only ever pushes NEW writes going forward - same
 * gap [BodyBackfill]'s own class doc describes for body, same five numbered rules apply
 * (idempotent via `origin_guid`, resumable via [MemoryBackfillCursor], `serverId` trusted only in
 * the "already present" direction, a locally-deleted-and-never-synced row is skipped rather than
 * resurrected, per-table failures do not abort the whole run) - see [BodyBackfill]'s own class doc
 * for the reasoning behind each, verbatim here.
 *
 * **`memory_audit` is the one place this file diverges from the body template, and it is the
 * table's ONLY path to the server, not a catch-up for a separate write-through.** `MemoryOutbox.kt`
 * deliberately has no live write-through for `memory_audit` (see that file's own class doc) -
 * every audit row, old and new, reaches Supabase through this backfill sweep alone. That is sound
 * because [backfillTable] does not distinguish "predates write-through" from "write-through was
 * never wired here": it just sweeps every row with `id > cursor`, in order, forever - cheap once
 * steady state is reached (the cursor advances past every row this run decides, so a routine
 * five-minute re-run touches nothing new until the next audit line is written). The cost is
 * latency, not correctness: a freshly-written audit row waits up to [MemoryBackfill]'s own
 * [MemoryBackfill.AUTO_RUN_MIN_INTERVAL_MS] before it reaches the server, rather than landing
 * immediately the way a write-through push would.
 */
object MemoryBackfill {
    private data class TableResult(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val failure: String?,
    )

    data class Report(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val failed: List<String>,
    )

    private suspend fun <L> backfillTable(
        context: Context,
        table: String,
        rows: List<L>,
        localId: (L) -> Long,
        localGuid: (L) -> String,
        localServerId: (L) -> String?,
        localDeleted: (L) -> Boolean,
        push: suspend (L) -> Result<*>,
    ): TableResult {
        val cursorAtStart = MemoryBackfillCursor.lastBackfilledId(context, table)
        val pending = rows.filter { localId(it) > cursorAtStart }.sortedBy { localId(it) }

        var pushed = 0
        var alreadyPresent = 0
        var skippedLocalOnlyDeleted = 0
        var failure: String? = null

        for (row in pending) {
            when {
                localServerId(row) != null -> {
                    alreadyPresent++
                    MemoryBackfillCursor.advance(context, table, localId(row))
                }
                localDeleted(row) -> {
                    skippedLocalOnlyDeleted++
                    MemoryBackfillCursor.advance(context, table, localId(row))
                }
                else -> {
                    val result = push(row)
                    if (result.isSuccess) {
                        pushed++
                        MemoryBackfillCursor.advance(context, table, localId(row))
                    } else {
                        val message = result.exceptionOrNull()?.message ?: "unknown error"
                        failure = "$table: $message (row id ${localId(row)}, guid ${localGuid(row)})"
                        break
                    }
                }
            }
        }

        return TableResult(pushed, alreadyPresent, skippedLocalOnlyDeleted, failure)
    }

    /** Every function below reuses [MemoryWriteThrough]'s own payload-mapping classes rather than
     * re-deriving `Local -> Fields`, same "one mapping, never two" reasoning [BodyBackfill]'s own
     * class doc gives. `memory_audit` has no such payload class (no write-through counterpart), so
     * its push builds [MemoryAuditFields] directly. */
    suspend fun run(context: Context, backend: MemoryBackend): Report {
        val db = CarDatabase.getDatabase(context)

        val memories = backfillTable(
            context, OutboxTarget.MEMORY_MEMORIES, db.memoryDao().getAll(),
            localId = { it.id }, localGuid = { it.syncId }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: MemoryEntry -> backend.upsertMemoryEntry(row.syncId, MemoryWriteThrough.MemoryEntryPayload.from(row).toFields()) },
        )
        val companionMemories = backfillTable(
            context, OutboxTarget.MEMORY_COMPANION_MEMORIES, db.companionMemoryDao().getAll(),
            localId = { it.id }, localGuid = { it.syncId }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: CompanionMemory -> backend.upsertCompanionMemory(row.syncId, MemoryWriteThrough.CompanionMemoryPayload.from(row).toFields()) },
        )
        val memoryAudit = backfillTable(
            context, MemoryBackfillCursor.MEMORY_AUDIT_TABLE, db.memoryAuditDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: MemoryAudit ->
                backend.upsertMemoryAudit(
                    row.guid,
                    MemoryAuditFields(row.event, row.store, row.detail, row.refId, row.vehicleId, row.at),
                )
            },
        )

        val results = listOf(memories, companionMemories, memoryAudit)

        return Report(
            pushed = results.sumOf { it.pushed },
            alreadyPresent = results.sumOf { it.alreadyPresent },
            skippedLocalOnlyDeleted = results.sumOf { it.skippedLocalOnlyDeleted },
            failed = results.mapNotNull { it.failure },
        )
    }

    // --- Foreground auto-trigger ------------------------------------------------------------------

    @Volatile private var lastAutoRunAt = 0L

    /** Same floor as [MemorySync]'s own auto-pull interval - see [BodyBackfill.AUTO_RUN_MIN_INTERVAL_MS]'s
     * own doc comment for why. Also the effective push latency for `memory_audit` rows - see this
     * file's own class doc. */
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** `MainActivity.onResume`'s hook - runs BEFORE [MemorySync.maybeAutoPull], same reasoning as
     * [BodyBackfill.maybeAutoRun]'s own class doc gives for body: an unsent local row must reach
     * the server before the pull weighs last-write-wins against a server copy that does not know
     * about it yet. `suspend`, run INLINE for the identical reason [BodyBackfill.maybeAutoRun]'s
     * own doc comment explains. No-ops silently when Supabase is not configured or nobody is
     * signed in. */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoRunAt = now
        try {
            val report = runIfSignedIn(app, SupabaseAuth(app), SupabaseMemoryBackend(client)) ?: return
            MidnightEvents.memoryBackfillSucceeded(report.pushed, report.alreadyPresent, report.skippedLocalOnlyDeleted, report.failed)
        } catch (e: Exception) {
            MidnightEvents.memoryBackfillFailed(e)
        }
    }

    /** [maybeAutoRun]'s guard-then-[run], factored out so a test can drive the "still restoring,
     * then succeeds" retry directly - same shape as [BodyBackfill.runIfSignedIn]. */
    internal suspend fun runIfSignedIn(context: Context, auth: SupabaseAuth, backend: MemoryBackend): Report? {
        if (auth.resolveSignedInUserId() == null) return null
        return run(context, backend)
    }
}
