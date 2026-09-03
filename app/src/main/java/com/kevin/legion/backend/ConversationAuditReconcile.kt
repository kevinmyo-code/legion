package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
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

    private fun key(deviceId: String) = "last_uploaded_local_id_$deviceId"

    fun lastUploadedId(context: Context, deviceId: String): Long = prefs(context).getLong(key(deviceId), 0L)

    fun advance(context: Context, deviceId: String, id: Long) {
        prefs(context).edit().putLong(key(deviceId), id).apply()
    }
}

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
 * 197 rows today does not need [BATCH_SIZE]'s batching to stay within a request's comfortable
 * size, but a busy fortnight is not measured yet either, and the identical shape means there is
 * exactly one pattern to review for this class of upload, not two.
 */
object ConversationAuditReconcile {
    private const val BATCH_SIZE = 500

    /**
     * @param sourceCount every `conversation_audit` row on this device right now - note this can
     *   SHRINK between runs, unlike every other reconcile's [sourceCount]: `ConversationAuditDao.trimOlderThan`
     *   deletes rows older than [com.kevin.legion.data.local.CONVERSATION_AUDIT_RETENTION_DAYS] on
     *   every write, independent of whether this reconcile has ever uploaded them. **A row trimmed
     *   locally before this reconcile ever runs is lost, not merely delayed** - this is a real gap
     *   this ticket's scope does not close (uploading, not scheduling), named here rather than
     *   left implicit: a reconcile that never runs for two weeks loses whatever aged out in that
     *   window.
     * @param uploaded rows this RUN sent, counted by batch size attempted - same convention as
     *   [ObdSampleReconcile.Report.uploaded].
     * @param serverCountAfter the server's `conversation_audit` row count after this run, via
     *   [ConversationAuditBackend.countConversationAudit]'s HEAD-only request - same "cheap enough
     *   to report, too expensive to diff against" posture [ObdSampleReconcile.Report.serverCountAfter]
     *   states for its own table.
     */
    data class Report(
        val sourceCount: Int,
        val uploaded: Int,
        val serverCountAfter: Long,
    )

    suspend fun run(context: Context, backend: ConversationAuditBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val deviceId = DeviceId.current(context)

        var cursor = ConversationAuditUploadCursor.lastUploadedId(context, deviceId)
        var uploadedThisRun = 0

        while (true) {
            val batch = db.conversationAuditDao().getAfterId(cursor, BATCH_SIZE)
            if (batch.isEmpty()) break

            val uploads = batch.map { row ->
                ConversationAuditUpload(
                    deviceId = deviceId,
                    localId = row.id,
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
            backend.uploadConversationAuditBatch(uploads).getOrElse { return Result.failure(it) }
            uploadedThisRun += uploads.size
            cursor = batch.last().id
            ConversationAuditUploadCursor.advance(context, deviceId, cursor)
        }

        val serverCountAfter = backend.countConversationAudit().getOrElse { return Result.failure(it) }

        return Result.success(
            Report(
                sourceCount = db.conversationAuditDao().count(),
                uploaded = uploadedThisRun,
                serverCountAfter = serverCountAfter,
            ),
        )
    }

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
            MidnightEvents.conversationAuditAutoReconcileSucceeded(report.uploaded, report.serverCountAfter)
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
