package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAuditDao
import com.kevin.legion.engine.DeviceId
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Install-scoped high-water mark for [ConversationAuditReconcile]'s upload, keyed by [DeviceId] -
 * same cursor shape and same reasoning as [ObdSampleUploadCursor], applied to a much smaller table
 * (197 rows as of 2026-08-29 versus obd_samples' 26,059) where the cost this cursor avoids is
 * smaller in absolute terms but the pattern is identical: a re-post is CORRECT by construction
 * (the `(device_id, local_id)` natural key) but re-scanning the whole table every run is not
 * CHEAP, and this is what makes a routine re-run touch only what is new.
 *
 * Keyed by device id, not a single global key, purely for hygiene against the (never actually
 * expected) case of [DeviceId.current] changing under one install - a wrong device-scoped cursor
 * fails safe by re-uploading a small, already-idempotent table, not by silently losing rows.
 */
internal object ConversationAuditUploadCursor {
    private const val PREFS = "conversation_audit_upload_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * **`_v2` because the watermark's MEANING changed on 2026-09-06, not merely its value.** The
     * old key recorded rows posted under the `(device_id, local_id)` server key; this one records
     * rows the server has CONFIRMED it accepted under
     * [com.kevin.legion.data.local.ConversationAudit.clientUuid]. Those are different claims, and
     * the old key's value is a claim about a key that turned out not to identify anything - on the
     * A25 it read 142 while the server held none of those 142 rows. A new key starts every install
     * at 0 and re-offers the whole table exactly once, which is free: the upload is
     * `on conflict (client_uuid) do nothing`, so a row genuinely already there is a no-op, and a
     * row wrongly believed uploaded finally goes up.
     *
     * Migrating the old value forward would have carried the lie forward with it. Deleting the old
     * key is not worth the code - it is 8 bytes and it is now inert.
     */
    private fun key(deviceId: String) = "uploaded_through_local_id_v2_$deviceId"

    fun lastUploadedId(context: Context, deviceId: String): Long = prefs(context).getLong(key(deviceId), 0L)

    fun advance(context: Context, deviceId: String, id: Long) {
        prefs(context).edit().putLong(key(deviceId), id).apply()
    }
}

/** The upload watermark for THIS device, for callers outside this file that need it but have no
 *  business knowing how it is stored - chiefly
 *  [com.kevin.legion.data.local.ConversationAuditDao.record]'s two call sites, which hand it to
 *  the retention trim so it cannot delete a row the server has not confirmed. */
internal fun conversationAuditUploadedThroughId(context: Context): Long =
    ConversationAuditUploadCursor.lastUploadedId(context, DeviceId.current(context))

/**
 * The upload path for `conversation_audit`
 * (`.scratch/backend-erp/issues/24-do-the-conversation-logs-reach-the-server.md`, RULED
 * 2026-08-29: "same for conversations for audit"). Schema:
 * `supabase/migrations/20260829000100_obd_samples_and_conversation_audit.sql`, UNAPPLIED as of
 * that migration's own header - nothing here has run on device.
 *
 * **Why this is safe to sync at all, restated because it is the reason the ticket allowed this
 * table to exist server-side.** CLAUDE.md section 7 forbids persisting anything other people wrote
 * to Kevin. That rule is satisfied upstream, at WRITE, not here: a tool in
 * [com.kevin.legion.service.LiveToolbox.EPISODIC_EXCLUDED_TOOLS] already has its RESULT replaced
 * with [com.kevin.legion.data.local.READ_THROUGH_REDACTED] before the phone's own
 * `conversation_audit` table ever holds it (confirmed against the LIVE set and its one call site,
 * `LiveSessionController`'s `toolRedacted = GeminiLiveSession.isEpisodicExcludedTool(call.name)`,
 * which reads [com.kevin.legion.service.LiveToolbox.EPISODIC_EXCLUDED_TOOLS] directly rather than a
 * copy - a tool added to that set is redacted automatically, with no second list to keep in sync).
 * This reconcile therefore uploads [com.kevin.legion.data.local.ConversationAudit.content]/[redacted]
 * VERBATIM - there is nothing left to redact a second time, and re-deciding it here would risk
 * drifting from the one true membership test.
 *
 * **`Kind.USER` rows upload unredacted, per the ticket's own 2026-08-29 ruling on that exact
 * question** ("the app is the thing doing the fetching, and the rule stops the app from building
 * a durable store of other people's messages... a person speaking is not the app fetching") - this
 * reconcile does not special-case [com.kevin.legion.data.local.ConversationAudit.Kind.USER] at all,
 * which is the correct shape for a ruling that says nothing further needs to happen to that row.
 *
 * **No vehicle resolution, unlike [ObdSampleReconcile].**
 * [com.kevin.legion.data.local.ConversationAudit.vehicleId] rides along as an unresolved obdMac
 * string, exactly as `conversation_audit.vehicle_id`'s own migration column comment states
 * ("CONTEXT, never a filter, and deliberately not a FK... an audit row must survive a vehicle
 * being deleted") - so unlike obd_samples there is no per-row vehicle lookup and therefore nothing
 * that can be skipped-and-named for an unresolved one.
 *
 * **Batches and resumes for the same reason [ObdSampleReconcile] does, at a much smaller scale.**
 * A table this size does not need [BATCH_SIZE]'s batching to stay within a request's comfortable
 * size, but the identical shape means there is exactly one pattern to review for this class of
 * upload, not two. (This paragraph used to quote "197 rows today", measured 2026-08-29. It was a
 * count in a doc comment, so it rotted; read the table.)
 *
 * **The upsert key is `client_uuid`, and was `(device_id, local_id)` until 2026-09-06.** That is
 * the correction this whole file exists in the shape it does because of: `local_id` is Room's
 * `AUTOINCREMENT` rowid and restarts at 1 whenever the phone's table is emptied, while `device_id`
 * (`ANDROID_ID`) does not - so after a reset this reconcile offered rows 1..142 against 142
 * unrelated August rows already holding those ids, the server dropped all of them under
 * `on conflict do nothing`, returned 200, and [ConversationAuditUploadCursor] advanced over the
 * lot. Three days of the only durable record of what a tool call did, with a 14-day delete timer
 * already running on it. Two changes make that shape unreachable rather than merely fixed:
 * [com.kevin.legion.data.local.ConversationAudit.clientUuid] is an identity that survives a table
 * reset, and [run] now advances the watermark only by rows the server SAYS it accepted.
 */
object ConversationAuditReconcile {
    private const val BATCH_SIZE = 500

    /**
     * @param sourceCount every `conversation_audit` row on this device right now - note this can
     *   SHRINK between runs, unlike every other reconcile's [sourceCount], because
     *   [com.kevin.legion.data.local.ConversationAuditDao.trimUploadedOlderThan] drops rows past
     *   [com.kevin.legion.data.local.CONVERSATION_AUDIT_RETENTION_DAYS] on every write.
     *
     *   **This paragraph used to end differently, and the difference is the fix.** It read: "A row
     *   trimmed locally before this reconcile ever runs is lost, not merely delayed - this is a
     *   real gap this ticket's scope does not close." That gap is now closed, from the other end:
     *   the trim stops at this device's upload watermark, so an un-uploaded row is KEPT past its
     *   window instead of deleted. The table can therefore grow without bound while uploads are
     *   broken, which is the trade taken deliberately - unbounded disk is recoverable, a deleted
     *   audit row is not - and [pendingSummary] is what stops that growth being a secret.
     * @param uploaded rows the SERVER reported accepting this run. Deliberately NOT the batch size
     *   attempted, which is what this counted until 2026-09-06 and what let it report 142 rows
     *   uploaded on three consecutive days that uploaded nothing. Diverges from
     *   [ObdSampleReconcile.Report.uploaded]'s attempted-count convention on purpose.
     * @param serverCountAfter the server's `conversation_audit` row count after this run, via
     *   [ConversationAuditBackend.countConversationAudit]'s HEAD-only request - same "cheap enough
     *   to report, too expensive to diff against" posture [ObdSampleReconcile.Report.serverCountAfter]
     *   states for its own table.
     */
    data class Report(
        val sourceCount: Int,
        val uploaded: Int,
        val serverCountAfter: Long,
        /**
         * Rows this run OFFERED that the server did not report accepting - sent minus accepted,
         * summed over every batch. Almost always 0. A non-zero value means the server already held
         * a row with that [com.kevin.legion.data.local.ConversationAudit.clientUuid], which under a
         * client-minted UUID key genuinely IS the same row (an interrupted earlier run, most
         * likely) - so it is not an error, but it is the number whose silence caused this ticket.
         * Reported separately from [uploaded] rather than folded into it, because the old code
         * added the batch SIZE to [uploaded] and thereby reported 142 rows uploaded on a run that
         * wrote none of them.
         */
        val notAccepted: Int,
    )

    /**
     * The watermark to start from, with the one sanity check that can catch a watermark left over
     * from a previous incarnation of this table.
     *
     * [com.kevin.legion.data.local.ConversationAudit.id] is an `AUTOINCREMENT` rowid, so it
     * restarts at 1 when the table is emptied - but this cursor lives in SharedPreferences, which
     * survives that. The two then disagree in the silent direction:
     * [com.kevin.legion.data.local.ConversationAuditDao.getAfterId] returns nothing and the upload
     * reports a clean, empty, successful run forever. (That is not the 2026-09-03 failure - that
     * one was a key collision, see [com.kevin.legion.data.local.ConversationAudit.clientUuid] -
     * but it is the SAME cursor-outlives-its-table root, and it is what would happen next time.)
     *
     * A cursor above the table's own highest id is impossible unless the sequence reset, so it is
     * safe to treat as proof of one and restart from 0. Re-offering rows costs nothing under the
     * UUID key. An empty table returns 0, the same conclusion by a shorter route.
     */
    internal suspend fun resolveCursor(dao: ConversationAuditDao, stored: Long): Long {
        val maxId = dao.maxId() ?: return 0L
        return if (stored > maxId) 0L else stored
    }

    suspend fun run(context: Context, backend: ConversationAuditBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val dao = db.conversationAuditDao()
        val deviceId = DeviceId.current(context)

        var cursor = resolveCursor(dao, ConversationAuditUploadCursor.lastUploadedId(context, deviceId))
        var uploadedThisRun = 0
        var notAcceptedThisRun = 0

        // Loops while the previous batch came back FULLY accepted. A short batch means the server
        // did not confirm the remainder, so this run stops and the next one re-offers it - and
        // expressing that as the loop condition rather than a third `break` keeps the exit
        // conditions in one place instead of scattered down the body.
        var previousBatchFullyAccepted = true
        while (previousBatchFullyAccepted) {
            val batch = dao.getAfterId(cursor, BATCH_SIZE)
            if (batch.isEmpty()) break

            val uploads = batch.map { row ->
                ConversationAuditUpload(
                    deviceId = deviceId,
                    localId = row.id,
                    clientUuid = row.clientUuid,
                    turnSeq = row.turnSeq,
                    kind = row.kind,
                    toolName = row.toolName,
                    args = row.args,
                    content = row.content,
                    redacted = row.redacted,
                    vehicleId = row.vehicleId,
                    recordedAtMs = row.at,
                )
            }
            val accepted = backend.uploadConversationAuditBatch(uploads).getOrElse { return Result.failure(it) }
            uploadedThisRun += accepted
            notAcceptedThisRun += uploads.size - accepted

            // The cursor moves by ACCEPTED ROWS, never by rows offered. This is the repair: the old
            // code advanced to `batch.last().id` after any non-failing call, so a server that
            // discarded all 500 rows of a batch and returned 200 OK moved the watermark 500 rows
            // forward over evidence nobody held. Advancing `accepted` positions into the batch
            // cannot outrun what the server confirmed, whatever the key turns out to be. Zero
            // accepted moves it not at all.
            if (accepted > 0) {
                cursor = batch[accepted - 1].id
                ConversationAuditUploadCursor.advance(context, deviceId, cursor)
            }
            previousBatchFullyAccepted = accepted == uploads.size
        }

        val serverCountAfter = backend.countConversationAudit().getOrElse { return Result.failure(it) }

        return Result.success(
            Report(
                sourceCount = dao.count(),
                uploaded = uploadedThisRun,
                serverCountAfter = serverCountAfter,
                notAccepted = notAcceptedThisRun,
            ),
        )
    }

    /** [pendingSummary]'s two numbers. [oldestAgeMs] is null exactly when [rows] is 0. */
    data class Pending(val rows: Int, val oldestAgeMs: Long?)

    /**
     * How many rows are waiting to reach the server, and how old the oldest one is - the numbers
     * [com.kevin.legion.ui.KeyScreen] turns into a sentence in front of a person.
     *
     * **This exists because the failure it describes was invisible for three days.** CLAUDE.md
     * section 7's rule is that a tool says in words what did NOT happen; the reason the 2026-09-03
     * collision ran as long as it did is that every surface reported success and the only contrary
     * evidence was a row count in a database nobody was looking at. A count and an age are enough:
     * "0 waiting" and "142 waiting, oldest 3 days" read differently at a glance, and the second is
     * a question a person will actually ask about.
     *
     * Returns null only when the table cannot be read at all, so the caller can say so rather than
     * render an unread state as "nothing waiting" - unreadable and empty are different sentences
     * (CLAUDE.md section 1).
     */
    suspend fun pendingSummary(context: Context, now: Long = System.currentTimeMillis()): Pending? =
        runCatching {
            val dao = CarDatabase.getDatabase(context).conversationAuditDao()
            val cursor = resolveCursor(dao, conversationAuditUploadedThroughId(context))
            Pending(
                rows = dao.countAfterId(cursor),
                oldestAgeMs = dao.oldestAtAfterId(cursor)?.let { (now - it).coerceAtLeast(0L) },
            )
        }.getOrNull()

    private val autoRunScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAutoRunAt = 0L

    /** Same floor and reasoning as [ObdSampleReconcile]'s own `AUTO_RUN_MIN_INTERVAL_MS` - this
     *  table is smaller (197 rows as of 2026-08-29 versus obd_samples' 26,059, per this object's
     *  own class doc) but the resumable-cursor shape and the reason for a floor are identical. */
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Same throttle predicate, same reason it is pulled out separately, as
     *  [ObdSampleReconcile.isThrottled] - see that function's own doc for why [autoRunGate] itself
     *  cannot be driven directly under Robolectric. */
    internal fun isThrottled(now: Long): Boolean = now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS

    /** Test-only escape hatch for [isThrottled]'s own test - same idiom as
     *  [ObdSampleReconcile.setLastAutoRunAtForTest]. */
    internal fun setLastAutoRunAtForTest(atMs: Long) {
        lastAutoRunAt = atMs
    }

    /** The synchronous half of [maybeAutoRun] - same throttle-floor-plus-configuration gate as
     *  [ObdSampleReconcile.autoRunGate], extracted for the identical reason: a directly assertable
     *  return value for [ConversationAuditReconcileTest], with [lastAutoRunAt] reserved here,
     *  before [maybeAutoRun] launches anything async. */
    internal fun autoRunGate(context: Context, now: Long = System.currentTimeMillis()): SupabaseClient? {
        if (isThrottled(now)) return null
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return null
        lastAutoRunAt = now
        return client
    }

    /** The async half of [maybeAutoRun] - same shape and same reason as
     *  [ObdSampleReconcile.runIfSignedIn]: resolves who is signed in via
     *  [SupabaseAuth.resolveSignedInUserId], runs [run] if anyone is, and reports via
     *  [MidnightEvents], extracted so [ConversationAuditReconcileTest] can drive the "signed out"
     *  and "signed in" branches directly against a fake [SupabaseAuth] gatewayProvider. */
    internal suspend fun runIfSignedIn(context: Context, backend: ConversationAuditBackend, auth: SupabaseAuth) {
        try {
            if (auth.resolveSignedInUserId() == null) return
            val report = run(context, backend).getOrThrow()
            MidnightEvents.conversationAuditAutoReconcileSucceeded(
                report.uploaded,
                report.serverCountAfter,
                report.notAccepted,
            )
        } catch (e: Exception) {
            MidnightEvents.conversationAuditAutoReconcileFailed(e)
        }
    }

    /**
     * `MainActivity.onResume`'s hook - `conversation_audit` had 78 rows never uploaded because
     * this reconcile's only production caller before this ticket was a Settings row nobody had
     * wired up to run automatically. No-ops silently, with a logged breadcrumb rather than a
     * dialog or a crash, when Supabase is not configured or nobody is signed in - see
     * [autoRunGate]/[runIfSignedIn] for the two halves this delegates to. Fire-and-forget on
     * [autoRunScope]; never suspends the caller.
     */
    fun maybeAutoRun(context: Context) {
        val client = autoRunGate(context) ?: return
        val app = context.applicationContext
        autoRunScope.launch {
            runIfSignedIn(app, SupabaseConversationAuditBackend(client), SupabaseAuth(app))
        }
    }
}
