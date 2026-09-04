package com.kevin.legion

import android.util.Log

/**
 * Structured breadcrumb logging. Backed by [Log.d] for now, NOT Crashlytics -
 * Firebase is deliberately not wired up in this repo yet (no `google-services.json`,
 * no Firebase dependency; see README.md). Every call keeps the same public
 * signature it had when this wrapped FirebaseCrashlytics in Midnight AI, so a
 * later Firebase setup only needs to change the bodies here, not any call site.
 *
 * Call [setCompanionName] / [setHasGeminiKey] once after the profile loads so
 * subsequent log lines carry that context (currently just logged, not tagged
 * onto anything persistent).
 */
object MidnightEvents {
    private const val TAG = "MidnightEvents"

    /**
     * Observability must never crash the app it's observing. Every call routes
     * through here so a bad state silently no-ops instead of throwing into a
     * hot path.
     */
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    // --- Session identity keys -----------------------------------------------

    fun setBuildContext(buildType: String, deviceModel: String, isEmulator: Boolean) = safe {
        Log.d(TAG, "build_context: type=$buildType device=$deviceModel emulator=$isEmulator")
    }

    /** Tag context with the assistant's configured name. */
    fun setCompanionName(name: String) = safe { Log.d(TAG, "companion_name: $name") }

    /** Tag whether the user supplied their own Gemini key (vs. the BuildConfig key). */
    fun setHasGeminiKey(hasByo: Boolean) = safe { Log.d(TAG, "has_byo_key: $hasByo") }

    /** Tag the device's raw `reqGlEsVersion` (e.g. "0x30000" for ES 3.0). */
    fun setGlEsVersion(hex: String) = safe { Log.d(TAG, "gl_es_version: $hex") }

    // --- Breadcrumb lifecycle events ------------------------------------------

    /** Gemini Live session socket opened and setup sent. */
    fun sessionStart() = safe { Log.d(TAG, "session_start") }

    /** Gemini Live session closed. [reason] is the close string from the socket. */
    fun sessionEnd(reason: String) = safe { Log.d(TAG, "session_end: $reason") }

    /** ELM327 OBD adapter connected at this Bluetooth MAC. */
    fun obdConnected(mac: String) = safe { Log.d(TAG, "obd_connected: $mac") }

    /** OBD adapter disconnected (annotates any crash that follows a disconnect). */
    fun obdDisconnected(reason: String) = safe { Log.d(TAG, "obd_disconnected: $reason") }

    /**
     * A PID read came back as a failure while the socket was still up. [rawResponse]
     * is the exact adapter reply that tripped
     * [com.kevin.legion.vehicle.ObdResponseParser.isFailureResponse].
     */
    fun obdPidSilence(count: Int, rawResponse: String) = safe {
        Log.d(TAG, "obd_pid_silence[$count]: '${rawResponse.take(60).replace('\n', ' ')}'")
    }

    /** The K-line reinit fired (3 consecutive silent PID reads). [trigger] is the raw
     * response that tripped it; [recovered] is whether the post-reinit 0100 looked valid. */
    fun obdReinit(trigger: String, recovered: Boolean) = safe {
        Log.w(TAG, "obd_reinit recovered=$recovered trigger='${trigger.take(60).replace('\n', ' ')}'")
    }

    /**
     * A `clear_codes` transaction reached one of D2's five outcomes
     * (`vehicle/DtcClearController.kt`, `.scratch/hands-and-senses/issues/01-clear-dtc.md`).
     * [before]/[after] are the DTC lists at each end of the transaction - `after` empty means the
     * post-send re-read genuinely came back clean, distinct from NOTHING_TO_CLEAR/REFUSED/
     * UNVERIFIED, none of which ever captured a trustworthy after-read (the caller passes an empty
     * list for those too, since this is a `Log.d` breadcrumb, not the durable row - see
     * [com.kevin.legion.data.local.CodeClearEvent] for the nullable distinction that DOES matter).
     */
    fun dtcCleared(outcome: String, before: List<String>, after: List<String>) = safe {
        Log.d(TAG, "dtc_cleared[$outcome]: before=$before after=$after")
    }

    /** REFRESH SCHEDULE in the logbook's DUE tab threw. */
    fun maintenanceRefreshFailed(e: Throwable) = safe {
        Log.w(TAG, "maintenance_refresh ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /**
     * One of [com.kevin.legion.MidnightApplication]'s process-start jobs threw
     * (audit fix, 2026-08-07). [stage] names which one.
     *
     * These run on a `SupervisorJob` scope with no `CoroutineExceptionHandler`,
     * so before they were wrapped an exception here killed the process at cold
     * start with nothing recorded. They are also gated off under Robolectric, so
     * **no test can catch a regression in this code** - which makes a log line
     * the only evidence that will ever exist when one of them fails on a real
     * device.
     */
    fun appStartWorkFailed(stage: String, e: Throwable) = safe {
        Log.w(TAG, "app_start_failed stage=$stage ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /**
     * A foreground [com.kevin.legion.backend.EventsSync.maybeAutoPull] pass completed - the only
     * evidence that will ever exist on a real device that the automatic events pull ran and what
     * it did, since it has no UI of its own (it is not another Settings button; see that object's
     * own class doc for why).
     *
     * [skippedTombstoneNoLocalMatch] defaults to 0 rather than being added as a required parameter
     * so [com.kevin.legion.backend.EventsRealtime]'s own call to the sibling
     * [eventsRealtimePullSucceeded] (a realtime-triggered pull, out of scope for this change) did
     * not need touching - traced 2026-09-02, the same [PullReport][com.kevin.legion.backend.EventsSync.PullReport]
     * field this log line now surfaces for the auto-pull path is documented on that data class.
     */
    fun eventsAutoPullSucceeded(
        inserted: Int,
        updated: Int,
        skippedLocalNewer: Int,
        tombstoned: Int,
        unrecognizedKinds: List<String>,
        skippedTombstoneNoLocalMatch: Int = 0,
    ) = safe {
        Log.d(
            TAG,
            "events_auto_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned skippedTombstoneNoLocalMatch=$skippedTombstoneNoLocalMatch " +
                "unrecognizedKinds=$unrecognizedKinds",
        )
    }

    /** [com.kevin.legion.backend.EventsSync.maybeAutoPull] failed - degraded to this log line
     * rather than a crash or a dialog, per that function's own doc comment. */
    fun eventsAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "events_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.EventsOutboxDrain.maybeDrain] pass completed - same
     * "only evidence this ran" role as [eventsAutoPullSucceeded] plays for the pull it runs
     * immediately before (see that drain's own class doc for why the ordering is load-bearing). */
    fun eventsOutboxDrainSucceeded(succeeded: Int, stillPending: Int, poisoned: Int) = safe {
        Log.d(TAG, "events_outbox_drain succeeded=$succeeded stillPending=$stillPending poisoned=$poisoned")
    }

    /** [com.kevin.legion.backend.EventsOutboxDrain.maybeDrain] failed - degraded to this log line,
     * same posture as [eventsAutoPullFailed]. */
    fun eventsOutboxDrainFailed(e: Throwable) = safe {
        Log.w(TAG, "events_outbox_drain_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.EventsRealtime] could not open or maintain its
     * `postgres_changes` subscription - degraded to this log line, per that object's own class
     * doc: the foreground pull stays as the fallback, so a socket failure must never surface as a
     * dialog or a crash, only as reduced immediacy. */
    fun eventsRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "events_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.EventsRealtime]-triggered [com.kevin.legion.backend.EventsSync.pull]
     * completed - same "only evidence this ran" role as [eventsAutoPullSucceeded], for the pull a
     * realtime `postgres_changes` event triggered instead of a foreground resume. */
    fun eventsRealtimePullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int, unrecognizedKinds: List<String>) = safe {
        Log.d(
            TAG,
            "events_realtime_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned unrecognizedKinds=$unrecognizedKinds",
        )
    }

    /** A realtime-triggered pull failed - degraded to this log line, same posture as
     * [eventsAutoPullFailed]. */
    fun eventsRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "events_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LedgerReconcile.maybeAutoRun] pass completed - the
     * only evidence that will ever exist on a real device that ledger's UNRECONCILED-only upload
     * ran automatically, since (like [eventsAutoPullSucceeded]'s pull) it has no UI of its own. */
    fun ledgerAutoReconcileSucceeded(uploaded: Int, skipped: Int, serverCountAfter: Int) = safe {
        Log.d(TAG, "ledger_auto_reconcile uploaded=$uploaded skipped=$skipped serverCountAfter=$serverCountAfter")
    }

    /** [com.kevin.legion.backend.LedgerReconcile.maybeAutoRun] failed - degraded to this log line,
     * same posture as [eventsAutoPullFailed]. */
    fun ledgerAutoReconcileFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_auto_reconcile_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.MaintenanceScheduleReconcile.maybeAutoRun] pass
     * completed - same "only evidence this ran" role as [ledgerAutoReconcileSucceeded]. */
    fun maintenanceScheduleAutoReconcileSucceeded(uploaded: Int, skipped: Int, serverCountAfter: Int) = safe {
        Log.d(TAG, "maintenance_schedule_auto_reconcile uploaded=$uploaded skipped=$skipped serverCountAfter=$serverCountAfter")
    }

    /** [com.kevin.legion.backend.MaintenanceScheduleReconcile.maybeAutoRun] failed - degraded to
     * this log line, same posture as [eventsAutoPullFailed]. */
    fun maintenanceScheduleAutoReconcileFailed(e: Throwable) = safe {
        Log.w(TAG, "maintenance_schedule_auto_reconcile_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.ObdSampleReconcile.maybeAutoRun] pass completed -
     * same "only evidence this ran" role as [ledgerAutoReconcileSucceeded], for the 5,263-row
     * backlog `.scratch/backend-erp/issues/30-*.md` found sitting behind a Settings row nobody
     * had wired up. [serverCountAfter] is a HEAD-only exact count, not a full fetch - see
     * [com.kevin.legion.backend.ObdSampleReconcile.Report.serverCountAfter]'s own doc. */
    fun obdSampleAutoReconcileSucceeded(uploaded: Int, skipped: Int, serverCountAfter: Long) = safe {
        Log.d(TAG, "obd_sample_auto_reconcile uploaded=$uploaded skipped=$skipped serverCountAfter=$serverCountAfter")
    }

    /** [com.kevin.legion.backend.ObdSampleReconcile.maybeAutoRun] failed - degraded to this log
     * line, same posture as [eventsAutoPullFailed]. */
    fun obdSampleAutoReconcileFailed(e: Throwable) = safe {
        Log.w(TAG, "obd_sample_auto_reconcile_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.ConversationAuditReconcile.maybeAutoRun] pass
     * completed - same "only evidence this ran" role as [ledgerAutoReconcileSucceeded]. No
     * `skipped` parameter - unlike [ledgerAutoReconcileSucceeded]'s ledger wave,
     * [com.kevin.legion.backend.ConversationAuditReconcile] never skips a row, only batches and
     * resumes (see that object's own class doc). */
    fun conversationAuditAutoReconcileSucceeded(uploaded: Int, serverCountAfter: Long) = safe {
        Log.d(TAG, "conversation_audit_auto_reconcile uploaded=$uploaded serverCountAfter=$serverCountAfter")
    }

    /** [com.kevin.legion.backend.ConversationAuditReconcile.maybeAutoRun] failed - degraded to
     * this log line, same posture as [eventsAutoPullFailed]. */
    fun conversationAuditAutoReconcileFailed(e: Throwable) = safe {
        Log.w(TAG, "conversation_audit_auto_reconcile_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.PlacesReconcile.maybeAutoRun] pass completed - same
     * "only evidence this ran" role as [ledgerAutoReconcileSucceeded]. Places' only backfill
     * mechanism (`ui/settings/BackendMigrationScreen.kt`'s retirement, live-sync ticket 05). */
    fun placesAutoReconcileSucceeded(uploaded: Int, serverCountAfter: Int) = safe {
        Log.d(TAG, "places_auto_reconcile uploaded=$uploaded serverCountAfter=$serverCountAfter")
    }

    /** [com.kevin.legion.backend.PlacesReconcile.maybeAutoRun] failed - degraded to this log line,
     * same posture as [eventsAutoPullFailed]. */
    fun placesAutoReconcileFailed(e: Throwable) = safe {
        Log.w(TAG, "places_auto_reconcile_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.PantryReconcile.maybeAutoRun] pass completed - same
     * "only evidence this ran" role as [ledgerAutoReconcileSucceeded]. Pantry's only backfill
     * mechanism (`ui/settings/BackendMigrationScreen.kt`'s retirement, live-sync ticket 05). */
    fun pantryAutoReconcileSucceeded(uploaded: Int, uploadedUnreconciled: Int, rejectedOveraccounted: Int, serverCountAfter: Int) = safe {
        Log.d(
            TAG,
            "pantry_auto_reconcile uploaded=$uploaded uploadedUnreconciled=$uploadedUnreconciled " +
                "rejectedOveraccounted=$rejectedOveraccounted serverCountAfter=$serverCountAfter",
        )
    }

    /** [com.kevin.legion.backend.PantryReconcile.maybeAutoRun] failed - degraded to this log line,
     * same posture as [eventsAutoPullFailed]. */
    fun pantryAutoReconcileFailed(e: Throwable) = safe {
        Log.w(TAG, "pantry_auto_reconcile_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.FleetReconcile.maybeAutoRun] pass completed - same
     * "only evidence this ran" role as [ledgerAutoReconcileSucceeded]. Fleet's only route to the
     * server at all (it has no configured write path - see that object's own class doc), and its
     * only backfill mechanism (`ui/settings/BackendMigrationScreen.kt`'s retirement, live-sync
     * ticket 05). [uploaded] sums all ten sub-reports' own `uploaded` counts - see
     * [com.kevin.legion.backend.FleetReconcile.runIfSignedIn]'s own doc for why this is a rough
     * breadcrumb, not the per-table breakdown. [serverCountAfter] is `vehicles`' own count only. */
    fun fleetAutoReconcileSucceeded(uploaded: Int, serverCountAfter: Int) = safe {
        Log.d(TAG, "fleet_auto_reconcile uploaded=$uploaded serverCountAfter=$serverCountAfter")
    }

    /** [com.kevin.legion.backend.FleetReconcile.maybeAutoRun] failed - degraded to this log line,
     * same posture as [eventsAutoPullFailed]. */
    fun fleetAutoReconcileFailed(e: Throwable) = safe {
        Log.w(TAG, "fleet_auto_reconcile_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.BodySync.maybeAutoPull] pass completed across all
     * eight body tables - same "only evidence this ran" role as [eventsAutoPullSucceeded] plays
     * for events. Body-supabase ticket. */
    fun bodyAutoPullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int, skippedTombstoneNoLocalMatch: Int) = safe {
        Log.d(
            TAG,
            "body_auto_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned skippedTombstoneNoLocalMatch=$skippedTombstoneNoLocalMatch",
        )
    }

    /** [com.kevin.legion.backend.BodySync.maybeAutoPull] failed - degraded to this log line, same
     * posture as [eventsAutoPullFailed]. */
    fun bodyAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "body_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.BodyOutboxDrain.maybeDrain] pass completed - same
     * role as [eventsOutboxDrainSucceeded] plays for events. Body-supabase ticket. */
    fun bodyOutboxDrainSucceeded(succeeded: Int, stillPending: Int, poisoned: Int) = safe {
        Log.d(TAG, "body_outbox_drain succeeded=$succeeded stillPending=$stillPending poisoned=$poisoned")
    }

    /** [com.kevin.legion.backend.BodyOutboxDrain.maybeDrain] failed - degraded to this log line,
     * same posture as [eventsOutboxDrainFailed]. */
    fun bodyOutboxDrainFailed(e: Throwable) = safe {
        Log.w(TAG, "body_outbox_drain_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.BodyRealtime] could not open or maintain its `postgres_changes`
     * subscription - degraded to this log line, same posture as [eventsRealtimeSubscribeFailed]:
     * the foreground pull stays as the fallback. */
    fun bodyRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "body_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.BodyRealtime]-triggered [com.kevin.legion.backend.BodySync.pull]
     * completed - same role as [eventsRealtimePullSucceeded]. */
    fun bodyRealtimePullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int) = safe {
        Log.d(
            TAG,
            "body_realtime_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned",
        )
    }

    /** A realtime-triggered body pull failed - degraded to this log line, same posture as
     * [eventsRealtimePullFailed]. */
    fun bodyRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "body_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.BodyBackfill.maybeAutoRun] pass completed across all
     * eight body tables - the one-time (then perpetually-cheap-no-op) upload of rows that predate
     * write-through, distinct from [bodyAutoPullSucceeded] which only ever pulls. [failedTables]
     * names which of the eight halted on a push failure this run, empty on the healthy/steady-state
     * case - see [com.kevin.legion.backend.BodyBackfill]'s own class doc for why a per-table halt
     * beats an all-or-nothing abort. */
    fun bodyBackfillSucceeded(pushed: Int, alreadyPresent: Int, skippedLocalOnlyDeleted: Int, failedTables: List<String>) = safe {
        Log.d(
            TAG,
            "body_backfill pushed=$pushed alreadyPresent=$alreadyPresent " +
                "skippedLocalOnlyDeleted=$skippedLocalOnlyDeleted failed=${failedTables.joinToString("; ")}",
        )
    }

    /** [com.kevin.legion.backend.BodyBackfill.maybeAutoRun] threw outright (not the per-table halt
     * [bodyBackfillSucceeded] already reports in words) - degraded to this log line, same posture
     * as [bodyAutoPullFailed]. */
    fun bodyBackfillFailed(e: Throwable) = safe {
        Log.w(TAG, "body_backfill_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.MemorySync.maybeAutoPull] pass completed across all
     * three memory tables - same role as [bodyAutoPullSucceeded] plays for body. Memory-supabase
     * ticket. */
    fun memoryAutoPullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int, skippedTombstoneNoLocalMatch: Int) = safe {
        Log.d(
            TAG,
            "memory_auto_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned skippedTombstoneNoLocalMatch=$skippedTombstoneNoLocalMatch",
        )
    }

    /** [com.kevin.legion.backend.MemorySync.maybeAutoPull] failed - degraded to this log line, same
     * posture as [bodyAutoPullFailed]. */
    fun memoryAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "memory_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.MemoryOutboxDrain.maybeDrain] pass completed - same
     * role as [bodyOutboxDrainSucceeded] plays for body. Memory-supabase ticket. */
    fun memoryOutboxDrainSucceeded(succeeded: Int, stillPending: Int, poisoned: Int) = safe {
        Log.d(TAG, "memory_outbox_drain succeeded=$succeeded stillPending=$stillPending poisoned=$poisoned")
    }

    /** [com.kevin.legion.backend.MemoryOutboxDrain.maybeDrain] failed - degraded to this log line,
     * same posture as [bodyOutboxDrainFailed]. */
    fun memoryOutboxDrainFailed(e: Throwable) = safe {
        Log.w(TAG, "memory_outbox_drain_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.MemoryRealtime] could not open or maintain its `postgres_changes`
     * subscription - degraded to this log line, same posture as [bodyRealtimeSubscribeFailed]. */
    fun memoryRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "memory_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.MemoryRealtime]-triggered [com.kevin.legion.backend.MemorySync.pull]
     * completed - same role as [bodyRealtimePullSucceeded]. */
    fun memoryRealtimePullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int) = safe {
        Log.d(
            TAG,
            "memory_realtime_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned",
        )
    }

    /** A realtime-triggered memory pull failed - degraded to this log line, same posture as
     * [bodyRealtimePullFailed]. */
    fun memoryRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "memory_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.MemoryBackfill.maybeAutoRun] pass completed across
     * all three memory tables - same role as [bodyBackfillSucceeded] plays for body, and (for
     * `memory_audit`) the ONLY path that table has to the server - see [com.kevin.legion.backend.MemoryBackfill]'s
     * own class doc. */
    fun memoryBackfillSucceeded(pushed: Int, alreadyPresent: Int, skippedLocalOnlyDeleted: Int, failedTables: List<String>) = safe {
        Log.d(
            TAG,
            "memory_backfill pushed=$pushed alreadyPresent=$alreadyPresent " +
                "skippedLocalOnlyDeleted=$skippedLocalOnlyDeleted failed=${failedTables.joinToString("; ")}",
        )
    }

    /** [com.kevin.legion.backend.MemoryBackfill.maybeAutoRun] threw outright - degraded to this log
     * line, same posture as [bodyBackfillFailed]. */
    fun memoryBackfillFailed(e: Throwable) = safe {
        Log.w(TAG, "memory_backfill_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LedgerConfigSync.maybeAutoPull] pass completed across
     * all three ledger config tables (`categories`/`category_rules`/`budget_targets`) - same role
     * as [memoryAutoPullSucceeded] plays for memory. Ledger-config-supabase ticket. */
    fun ledgerConfigAutoPullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int, skippedTombstoneNoLocalMatch: Int) = safe {
        Log.d(
            TAG,
            "ledger_config_auto_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned skippedTombstoneNoLocalMatch=$skippedTombstoneNoLocalMatch",
        )
    }

    /** [com.kevin.legion.backend.LedgerConfigSync.maybeAutoPull] failed - degraded to this log line,
     * same posture as [memoryAutoPullFailed]. */
    fun ledgerConfigAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_config_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LedgerConfigOutboxDrain.maybeDrain] pass completed -
     * same role as [memoryOutboxDrainSucceeded] plays for memory. */
    fun ledgerConfigOutboxDrainSucceeded(succeeded: Int, stillPending: Int, poisoned: Int) = safe {
        Log.d(TAG, "ledger_config_outbox_drain succeeded=$succeeded stillPending=$stillPending poisoned=$poisoned")
    }

    /** [com.kevin.legion.backend.LedgerConfigOutboxDrain.maybeDrain] failed - degraded to this log
     * line, same posture as [memoryOutboxDrainFailed]. */
    fun ledgerConfigOutboxDrainFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_config_outbox_drain_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.LedgerConfigRealtime] could not open or maintain its
     * `postgres_changes` subscription - degraded to this log line, same posture as
     * [memoryRealtimeSubscribeFailed]. */
    fun ledgerConfigRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_config_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.LedgerConfigRealtime]-triggered
     * [com.kevin.legion.backend.LedgerConfigSync.pull] completed - same role as
     * [memoryRealtimePullSucceeded]. */
    fun ledgerConfigRealtimePullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int) = safe {
        Log.d(
            TAG,
            "ledger_config_realtime_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned",
        )
    }

    /** A realtime-triggered ledger config pull failed - degraded to this log line, same posture as
     * [memoryRealtimePullFailed]. */
    fun ledgerConfigRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_config_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LedgerConfigBackfill.maybeAutoRun] pass completed
     * across all three ledger config tables - same role as [memoryBackfillSucceeded] plays for
     * memory. */
    fun ledgerConfigBackfillSucceeded(pushed: Int, alreadyPresent: Int, skippedLocalOnlyDeleted: Int, failedTables: List<String>) = safe {
        Log.d(
            TAG,
            "ledger_config_backfill pushed=$pushed alreadyPresent=$alreadyPresent " +
                "skippedLocalOnlyDeleted=$skippedLocalOnlyDeleted failed=${failedTables.joinToString("; ")}",
        )
    }

    /** [com.kevin.legion.backend.LedgerConfigBackfill.maybeAutoRun] threw outright - degraded to
     * this log line, same posture as [memoryBackfillFailed]. */
    fun ledgerConfigBackfillFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_config_backfill_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LastAspectsSync.maybeAutoPull] pass completed across
     * all four last-aspect-slice tables (`goals`/`grocery_staples`/`item_lists`/`list_items`) -
     * same role as [ledgerConfigAutoPullSucceeded] plays for ledger config. */
    fun lastAspectsAutoPullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int, skippedTombstoneNoLocalMatch: Int) = safe {
        Log.d(
            TAG,
            "last_aspects_auto_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned skippedTombstoneNoLocalMatch=$skippedTombstoneNoLocalMatch",
        )
    }

    /** [com.kevin.legion.backend.LastAspectsSync.maybeAutoPull] failed - degraded to this log
     * line, same posture as [ledgerConfigAutoPullFailed]. */
    fun lastAspectsAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "last_aspects_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LastAspectsOutboxDrain.maybeDrain] pass completed -
     * same role as [ledgerConfigOutboxDrainSucceeded] plays for ledger config. */
    fun lastAspectsOutboxDrainSucceeded(succeeded: Int, stillPending: Int, poisoned: Int) = safe {
        Log.d(TAG, "last_aspects_outbox_drain succeeded=$succeeded stillPending=$stillPending poisoned=$poisoned")
    }

    /** [com.kevin.legion.backend.LastAspectsOutboxDrain.maybeDrain] failed - degraded to this log
     * line, same posture as [ledgerConfigOutboxDrainFailed]. */
    fun lastAspectsOutboxDrainFailed(e: Throwable) = safe {
        Log.w(TAG, "last_aspects_outbox_drain_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.LastAspectsRealtime] could not open or maintain its
     * `postgres_changes` subscription - degraded to this log line, same posture as
     * [ledgerConfigRealtimeSubscribeFailed]. */
    fun lastAspectsRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "last_aspects_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.LastAspectsRealtime]-triggered
     * [com.kevin.legion.backend.LastAspectsSync.pull] completed - same role as
     * [ledgerConfigRealtimePullSucceeded]. */
    fun lastAspectsRealtimePullSucceeded(inserted: Int, updated: Int, skippedLocalNewer: Int, tombstoned: Int) = safe {
        Log.d(
            TAG,
            "last_aspects_realtime_pull inserted=$inserted updated=$updated skippedLocalNewer=$skippedLocalNewer " +
                "tombstoned=$tombstoned",
        )
    }

    /** A realtime-triggered last-aspects pull failed - degraded to this log line, same posture as
     * [ledgerConfigRealtimePullFailed]. */
    fun lastAspectsRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "last_aspects_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LastAspectsBackfill.maybeAutoRun] pass completed
     * across all four last-aspect-slice tables - same role as [ledgerConfigBackfillSucceeded]
     * plays for ledger config. */
    fun lastAspectsBackfillSucceeded(pushed: Int, alreadyPresent: Int, skippedLocalOnlyDeleted: Int, failedTables: List<String>) = safe {
        Log.d(
            TAG,
            "last_aspects_backfill pushed=$pushed alreadyPresent=$alreadyPresent " +
                "skippedLocalOnlyDeleted=$skippedLocalOnlyDeleted failed=${failedTables.joinToString("; ")}",
        )
    }

    /** [com.kevin.legion.backend.LastAspectsBackfill.maybeAutoRun] threw outright - degraded to
     * this log line, same posture as [ledgerConfigBackfillFailed]. */
    fun lastAspectsBackfillFailed(e: Throwable) = safe {
        Log.w(TAG, "last_aspects_backfill_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.FleetSync.maybeAutoPull] pass completed across every
     * fleet table this pull merges (vehicles/service_history/drives/code_events/code_clear_events/
     * oil_analyses/vehicle_specs/build_entries/drive_reassignments/maintenance_schedules), plus the
     * windowed `obd_samples` pull - same role as [ledgerConfigAutoPullSucceeded]. [reconstructed] is
     * this pull's own addition: how many vehicles this run rebuilt a legacy row and sidecar entry
     * for (a mac-hint match or a synthetic id), the count the "on a wiped phone" fix exists to make
     * non-zero. [obdSamplesPulled] is the count actually downloaded THIS run inside the window;
     * [obdSamplesServerTotal] is the server's own whole-table count ([FleetBackend.countObdSamples],
     * unwindowed) - logged side by side deliberately, so the gap between them stays visible rather
     * than letting the phone's windowed copy read as the whole history. */
    fun fleetAutoPullSucceeded(
        reconstructed: Int,
        inserted: Int,
        updated: Int,
        tombstoned: Int,
        skippedLocalNewer: Int,
        skippedTombstoneNoLocalMatch: Int,
        obdSamplesPulled: Int,
        obdSamplesServerTotal: Long,
    ) = safe {
        Log.d(
            TAG,
            "fleet_auto_pull reconstructed=$reconstructed inserted=$inserted updated=$updated " +
                "tombstoned=$tombstoned skippedLocalNewer=$skippedLocalNewer " +
                "skippedTombstoneNoLocalMatch=$skippedTombstoneNoLocalMatch " +
                "obdSamplesPulled=$obdSamplesPulled obdSamplesServerTotal=$obdSamplesServerTotal",
        )
    }

    /** [com.kevin.legion.backend.FleetSync.maybeAutoPull] threw outright - degraded to this log
     * line, same posture as [ledgerConfigAutoPullFailed]. */
    fun fleetAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "fleet_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.FleetRealtime]'s channel subscribe attempt failed - same posture as
     * [ledgerConfigRealtimeSubscribeFailed]. */
    fun fleetRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "fleet_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.FleetRealtime]-triggered [com.kevin.legion.backend.FleetSync.pull]
     * completed - same role as [ledgerConfigRealtimePullSucceeded]. */
    fun fleetRealtimePullSucceeded(inserted: Int, updated: Int, tombstoned: Int) = safe {
        Log.d(TAG, "fleet_realtime_pull inserted=$inserted updated=$updated tombstoned=$tombstoned")
    }

    /** A realtime-triggered fleet pull failed - degraded to this log line, same posture as
     * [ledgerConfigRealtimePullFailed]. */
    fun fleetRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "fleet_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.LedgerTransactionsSync.maybeAutoPull] pass completed -
     * same role as [fleetAutoPullSucceeded]. `inserted`/`alreadyPresent` mirror the insert-if-absent
     * shape [LedgerTransactionsSync]'s own class doc explains (no LWW, no tombstone branch);
     * [unrecognizedProvenance] is a count of rows this pull refused to insert because their
     * `provenance` string did not match a known [com.kevin.legion.data.local.IngestMethod] - should
     * be zero on every real pull, and non-zero here is a signal worth looking at directly, not just
     * logging past. */
    fun ledgerTransactionsAutoPullSucceeded(inserted: Int, alreadyPresent: Int, unrecognizedProvenance: Int) = safe {
        Log.d(
            TAG,
            "ledger_transactions_auto_pull inserted=$inserted alreadyPresent=$alreadyPresent " +
                "unrecognizedProvenance=$unrecognizedProvenance",
        )
    }

    /** [com.kevin.legion.backend.LedgerTransactionsSync.maybeAutoPull] threw outright - degraded to
     * this log line, same posture as [fleetAutoPullFailed]. */
    fun ledgerTransactionsAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_transactions_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.LedgerTransactionsRealtime]'s channel subscribe attempt failed -
     * same posture as [fleetRealtimeSubscribeFailed]. */
    fun ledgerTransactionsRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_transactions_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.LedgerTransactionsRealtime]-triggered
     * [com.kevin.legion.backend.LedgerTransactionsSync.pull] completed - same role as
     * [fleetRealtimePullSucceeded]. */
    fun ledgerTransactionsRealtimePullSucceeded(inserted: Int, alreadyPresent: Int) = safe {
        Log.d(TAG, "ledger_transactions_realtime_pull inserted=$inserted alreadyPresent=$alreadyPresent")
    }

    /** A realtime-triggered ledger transactions pull failed - degraded to this log line, same
     * posture as [fleetRealtimePullFailed]. */
    fun ledgerTransactionsRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "ledger_transactions_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A foreground [com.kevin.legion.backend.PantryReceiptsSync.maybeAutoPull] pass completed -
     * same role as [ledgerTransactionsAutoPullSucceeded]. [linesInserted] is this pull's own
     * addition: a receipt-level count alone would hide whether the lines that ride along with a
     * newly-inserted receipt actually landed. */
    fun pantryReceiptsAutoPullSucceeded(inserted: Int, alreadyPresent: Int, unrecognizedProvenance: Int, linesInserted: Int) = safe {
        Log.d(
            TAG,
            "pantry_receipts_auto_pull inserted=$inserted alreadyPresent=$alreadyPresent " +
                "unrecognizedProvenance=$unrecognizedProvenance linesInserted=$linesInserted",
        )
    }

    /** [com.kevin.legion.backend.PantryReceiptsSync.maybeAutoPull] threw outright - degraded to this
     * log line, same posture as [ledgerTransactionsAutoPullFailed]. */
    fun pantryReceiptsAutoPullFailed(e: Throwable) = safe {
        Log.w(TAG, "pantry_receipts_auto_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** [com.kevin.legion.backend.PantryReceiptsRealtime]'s channel subscribe attempt failed - same
     * posture as [ledgerTransactionsRealtimeSubscribeFailed]. */
    fun pantryReceiptsRealtimeSubscribeFailed(e: Throwable) = safe {
        Log.w(TAG, "pantry_receipts_realtime_subscribe_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A [com.kevin.legion.backend.PantryReceiptsRealtime]-triggered
     * [com.kevin.legion.backend.PantryReceiptsSync.pull] completed - same role as
     * [ledgerTransactionsRealtimePullSucceeded]. */
    fun pantryReceiptsRealtimePullSucceeded(inserted: Int, alreadyPresent: Int) = safe {
        Log.d(TAG, "pantry_receipts_realtime_pull inserted=$inserted alreadyPresent=$alreadyPresent")
    }

    /** A realtime-triggered pantry receipts pull failed - degraded to this log line, same posture as
     * [ledgerTransactionsRealtimePullFailed]. */
    fun pantryReceiptsRealtimePullFailed(e: Throwable) = safe {
        Log.w(TAG, "pantry_receipts_realtime_pull_failed ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A voice tool was dispatched. Useful for tracing what tool ran before a crash. */
    fun toolDispatched(toolName: String) = safe { Log.d(TAG, "tool_dispatched: $toolName") }

    /**
     * A navigation launch attempt and what actually came of it. [outcome] is the
     * [com.kevin.legion.location.NavigationController.Outcome] that was returned, so a launch
     * that never happened is distinguishable in the log from one that did - the whole point of
     * the ticket this was built for.
     */
    fun navigationLaunch(mode: String, outcome: String) = safe {
        Log.d(TAG, "navigation_launch: mode=$mode outcome=$outcome")
    }

    /**
     * A completed voice turn: what Gemini heard, and how much mic audio we actually
     * forwarded. `forwardedBytes` near zero on a turn the driver spoke into IS the
     * diagnosis: it means the mic was closed or muted, not that Gemini misheard.
     * The transcript is the driver's own speech - debug-only, do not ship in release
     * without deciding that deliberately.
     */
    fun voiceTurn(transcript: String, forwardedBytes: Long) = safe {
        Log.d(TAG, "voice_turn: bytes=$forwardedBytes heard=\"${transcript.take(80)}\"")
    }

    /** A voice turn forwarded suspiciously few bytes - the "shows LISTENING but hears
     * nothing" symptom. */
    fun silentMicTurn(transcript: String, forwardedBytes: Long) = safe {
        Log.w(TAG, "silent_mic_turn: bytes=$forwardedBytes heard=\"${transcript.take(80)}\"")
    }

    /** Which branch the turn-complete state machine took, and the flags it decided from. */
    fun voiceTurnDecision(detail: String) = safe { Log.d(TAG, "voice_turn_decision: $detail") }

    /** The mic was opened for the driver, or closed. */
    fun micState(open: Boolean, why: String) = safe { Log.d(TAG, "mic_${if (open) "open" else "closed"}: $why") }

    /**
     * Ticket 15 (`.scratch/wake-word/issues/15-see-a-deaf-mic.md`): the mic has been open,
     * actively capturing a turn, for [heldMs] with a peak signal level of [peak] that never
     * rose above [com.kevin.legion.service.MicSignal.SILENCE_PEAK_THRESHOLD] - digital silence,
     * not a quiet room. This is a WARN-level forced report (not a debug breadcrumb) because the
     * whole point of this ticket is a failure that otherwise leaves no trace at all: it opens
     * cleanly, throws nothing, and the server-VAD turn simply never completes. [recordingState]
     * is `AudioRecord.recordingState` at the moment this fired - logged alongside the fault
     * because a STOPPED recorder and a genuinely DEAF one look identical from every other signal
     * available here, and only this field can tell them apart afterward.
     */
    fun deafMicWatchdog(heldMs: Long, peak: Int, recordingState: Int) = safe {
        Log.w(TAG, "deaf_mic_watchdog: heldMs=$heldMs peak=$peak recordingState=$recordingState")
    }

    /**
     * A synced or imported row carried columns this device's schema does not have,
     * and they were dropped so the rest of the row could still be written. Warn,
     * not debug: this is data arriving that we chose not to store, and the only
     * signal that a payload and the local schema have drifted apart. Reported once
     * per table+column, not per row.
     */
    fun syncColumnsDropped(table: String, columns: List<String>) = safe {
        Log.w(TAG, "sync_columns_dropped[$table]: ${columns.joinToString(",")}")
    }

    /**
     * The Midnight AI import moved rows off the shared `"default"` vehicle id onto
     * a portable one. Warn: this rewrites rows already on disk and should happen
     * exactly once per device, so a second sighting means something is wrong.
     */
    fun importRekeyed(rows: Int, remap: Map<String, String>) = safe {
        Log.w(TAG, "import_rekeyed: $rows row(s), $remap")
    }

    /**
     * Cutover 1's `EngineDataMigrationWave1.rekeySkipsToEngineIds` (`docs/architecture/cutover1-2026-08-24.md`)
     * could not find a live target for one `ListItemSkip` row and left it un-rekeyed - its
     * occurrence-skip is silently lost (the same accepted cost that function's own doc comment
     * already names). Logged, not silently dropped, so the owed on-device run can actually observe
     * whether this ever fires for real data rather than only in a unit test fixture.
     */
    fun skipRekeyOrphaned(skipId: Long, legacyItemId: Long, reason: String) = safe {
        Log.w(TAG, "skip_rekey_orphaned: skip #$skipId (legacy itemId $legacyItemId) - $reason")
    }

    // --- Handled errors -------------------------------------------------------

    /** Records a handled (non-fatal) error under [tag]. */
    fun recordError(tag: String, e: Throwable) = safe {
        Log.w(TAG, "[$tag] ${e.message}", e)
    }

    /** Debug-only verification helper. Wired to a Setup dev-tools button. */
    fun testPing() = safe {
        Log.d(TAG, "test_ping")
    }
}
