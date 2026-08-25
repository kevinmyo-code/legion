package com.kevin.legion.sync

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns [DatabaseSnapshot] into an actual, unattended backup instead of a button someone has
 * to remember to press. `.scratch/backend-erp/issues/05-migration-path.md` Phase 0 item 1:
 * [DatabaseSnapshot.backupNow] was manual-only - its only three callers were the buttons on
 * `ui/DriveSyncScreen.kt` - which meant a driver who never opened that screen never got a
 * backup at all. That is not a backup, that is a button.
 *
 * **App-lifecycle daily check, not WorkManager (Kevin's call, this ticket's brief).** The app
 * has no WorkManager dependency and this alone does not earn one. Existing periodic work is
 * either `AlarmManager` + `BootReceiver` re-arm, or an app-foreground hook -
 * [com.kevin.legion.calendar.CalendarImportController] is the precedent this follows exactly:
 * one plain `suspend fun` a caller runs from `onResume`, gated by its own "was it recently
 * enough" check, no scheduler service of its own. [checkNow] is the fire-and-forget entry
 * point `ui/MainActivity.kt` calls; [runIfDue] is the pure suspend body a test can drive
 * directly with an injected clock.
 *
 * **This can only ever be as fresh as the app is opened.** [MIN_INTERVAL_MS] is a FLOOR on
 * how often an automatic backup is attempted, never a promise that one happens every 24
 * hours - a driver who does not open the app for a week gets no automatic backup for that
 * week, full stop. Framing it as anything stronger than a floor would be exactly the kind of
 * silently-overstated safety CLAUDE.md sec 4 rule 6/7 refuse for data, applied here to backup
 * cadence instead of extraction confidence.
 *
 * **Never passes `overrideGuard = true`.** [DatabaseSnapshotGuard] exists to make a driver
 * explicitly confirm a wipe-shaped backup; an unattended background pass has nobody to ask,
 * so the only honest behaviour on a guard refusal is to record it and try again next time -
 * never to answer the confirmation on the driver's behalf.
 *
 * **State is install-scoped SharedPreferences**, same pattern as
 * [com.kevin.legion.ai.CompanionProfile]'s flags (`KEY_SYNC_ENABLED` and friends): never
 * synced, never profile-scoped - a scheduler's own bookkeeping about ITS OWN device is not
 * data the driver authored.
 */
object ScheduledBackup {
    private const val PREFS = "scheduled_backup"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val KEY_LAST_FAILURE_REASON = "last_failure_reason"

    /**
     * Minimum time between automatic backup ATTEMPTS, counted from the last SUCCESSFUL one.
     * This is a floor on frequency, not a promise of freshness - see this object's class doc
     * comment. It exists only so every single foreground return does not re-run a network
     * backup pass; it does not and cannot guarantee a backup actually happened in the last
     * 24 hours, since the app might not have been opened at all.
     */
    const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every branch expressible in words - CLAUDE.md sec 7's "failure result says in words
     * what did NOT happen" applied to a background pass with no UI to render it in directly. */
    sealed interface Outcome {
        /** [SyncCapability.syncAvailable] was false - no Play Services, or Drive not connected.
         * Nothing was attempted, nothing recorded. */
        data object NotAvailable : Outcome

        /** The last successful backup is still within [MIN_INTERVAL_MS]. Nothing was
         * attempted, nothing recorded - the existing last-success/last-failure state stands. */
        data object NotDue : Outcome

        data class BackedUp(val generation: DatabaseSnapshot.Generation) : Outcome
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Runs one check-and-maybe-backup pass. [DatabaseSnapshot.backupNow] itself returns
     * [DatabaseSnapshot.BackupResult.Failed] rather than throwing, but this function is NOT
     * throw-free: [isAvailable] reaches Play Services and the SharedPreferences writes can
     * throw too. [checkNow] therefore catches and RECORDS rather than merely swallowing - see
     * its doc comment for why a silent throw here would be the worst outcome of the three.
     *
     * @param now injectable clock, defaulting to the real one - the only way to test the 24h
     * boundary without sleeping (this ticket's own test brief).
     * @param isAvailable injectable in place of [SyncCapability.syncAvailable] - the real
     * function needs a live Play Services check, which Robolectric cannot provide without
     * heavy additional mocking (same class of gap [DatabaseSnapshotRestoreTest]'s own doc
     * comment already accepts for `restore()`), so tests drive this seam directly instead.
     * @param backup injectable in place of [DatabaseSnapshot.backupNow] - real Drive network
     * IO is out of scope for a JVM test (see this ticket's own report, "no device attached").
     * Never called with `overrideGuard = true` - see this object's class doc comment.
     */
    suspend fun runIfDue(
        context: Context,
        now: Long = System.currentTimeMillis(),
        isAvailable: (Context) -> Boolean = SyncCapability::syncAvailable,
        backup: suspend (Context) -> DatabaseSnapshot.BackupResult = { ctx -> DatabaseSnapshot.backupNow(ctx, overrideGuard = false) },
    ): Outcome {
        if (!isAvailable(context)) return Outcome.NotAvailable

        val lastSuccess = lastSuccessAt(context)
        if (lastSuccess != null && now - lastSuccess < MIN_INTERVAL_MS) return Outcome.NotDue

        prefs(context).edit().putLong(KEY_LAST_ATTEMPT_AT, now).apply()

        return when (val result = backup(context)) {
            is DatabaseSnapshot.BackupResult.Ok -> {
                prefs(context).edit()
                    .putLong(KEY_LAST_SUCCESS_AT, now)
                    .remove(KEY_LAST_FAILURE_REASON)
                    .apply()
                Outcome.BackedUp(result.generation)
            }
            is DatabaseSnapshot.BackupResult.Refused -> {
                recordFailure(context, result.reason)
                Outcome.Refused(result.reason)
            }
            is DatabaseSnapshot.BackupResult.Failed -> {
                recordFailure(context, result.reason)
                Outcome.Failed(result.reason)
            }
        }
    }

    /** Refused/Failed both land here: the reason is recorded, but [KEY_LAST_SUCCESS_AT] is
     * left untouched - a failed or refused attempt must never advance the "last successful
     * backup" clock the 24h gate and the settings screen both read. */
    private fun recordFailure(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_LAST_FAILURE_REASON, reason).apply()
    }

    /** Process-lifetime scope owned by this object, same shape and same reasoning as
     * [SyncEngine]'s own `engineScope` - see that field's doc comment for the exact
     * build-a-scope-in-the-Activity anti-pattern this avoids. [checkNow] is the
     * fire-and-forget entry point `ui/MainActivity.kt`'s `onResume` calls. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Launches [runIfDue] on this object's own scope and returns immediately - never blocks
     * the caller, which matters on the UI thread `onResume` runs on.
     *
     * **The `runCatching` is load-bearing, not defensive habit.** [runIfDue] reaches
     * [SyncCapability.syncAvailable] (which calls into Play Services) and writes
     * SharedPreferences, either of which can throw; inside a `launch` on a [SupervisorJob]
     * scope a throw would be swallowed with nothing logged and nothing shown, leaving the
     * settings screen reporting the last SUCCESS as if no attempt had been made since. That is
     * exactly the failure ticket 04 named: a background data-protection job that fails silently
     * is worse than one that does not exist, because it reports safety it is not providing. So
     * an unexpected throw is recorded as a failure reason the same way a [Outcome.Failed] is,
     * and surfaces on `ui/DriveSyncScreen.kt` in words. */
    fun checkNow(context: Context) {
        scope.launch {
            runCatching { runIfDue(context) }.onFailure { t ->
                runCatching {
                    recordFailure(context, t.message ?: t::class.java.simpleName)
                }
                com.kevin.legion.MidnightEvents.appStartWorkFailed("scheduled_backup", t)
            }
        }
    }

    // ------------------------------------------------------------ status, for DriveSyncScreen

    /** Epoch millis of the last SUCCESSFUL scheduled backup, or null if there has never been
     * one on this install. */
    fun lastSuccessAt(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_SUCCESS_AT, 0L).takeIf { it != 0L }

    /** Epoch millis of the last scheduled backup ATTEMPT (successful or not), or null if none
     * has ever run on this install. */
    fun lastAttemptAt(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_ATTEMPT_AT, 0L).takeIf { it != 0L }

    /** The reason the most recent attempt was refused or failed, or null if the most recent
     * attempt succeeded (or there has never been an attempt). Cleared the moment a backup
     * succeeds - a stale failure reason must never outlive the success that superseded it. */
    fun lastFailureReason(context: Context): String? =
        prefs(context).getString(KEY_LAST_FAILURE_REASON, null)
}
