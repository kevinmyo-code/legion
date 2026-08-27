package com.kevin.legion.ui.sync

import com.kevin.legion.sync.DatabaseSnapshot

/**
 * Pure UI-state derivation for the whole-database backup/restore panel on
 * `ui/DriveSyncScreen.kt` (Phase 0 of the sync overhaul, Kevin 2026-08-12 - see
 * [DatabaseSnapshot]'s class doc comment for the incident this exists for). Same
 * shape/pairing as [GoogleGrantResolver]: the screen owns [DatabaseSnapshot],
 * [com.kevin.legion.sync.DriveAuth], and coroutine plumbing; this object only
 * turns already-fetched values into display strings and enable/disable state,
 * so it is a plain JVM unit-test target with no Robolectric.
 *
 * [DatabaseSnapshot.Generation] is itself an Android-free plain data class, so
 * depending on it directly here costs this file nothing - contrast
 * [GoogleGrantResolver], which specifically avoids
 * [com.kevin.legion.sync.DriveAuth.Outcome] because THAT type's `NeedsConsent`
 * arm carries a real `android.app.PendingIntent`.
 */
object DriveBackupResolver {

    /**
     * `.scratch/backend-erp/issues/09-backups-do-not-cover-files.md`'s ruling: [DatabaseSnapshot]
     * stays database-only on purpose (its generation guard, pre-restore aside and single-`.db.gz`
     * naming are load-bearing and this is not the ticket that reopens them), which means a
     * restored database can hold rows whose photo path points at a file the backup never carried.
     * Shared verbatim across the panel description and both restore confirm dialogs so the caveat
     * cannot drift into three slightly different wordings that a driver could read as three
     * different facts.
     */
    const val PHOTO_COVERAGE_CAVEAT =
        "This does not include photos. Receipt and record images live in the app's own storage, " +
            "not the database, so backing up or restoring never touches them."

    /** One row the "generations available" list draws. */
    data class GenerationRow(
        val generation: DatabaseSnapshot.Generation,
        val timeLabel: String,
        val rowCountLabel: String,
        val isNewest: Boolean,
        val canRestore: Boolean,
        /** Worded, never colour-only (CLAUDE.md §7) - shown next to a disabled restore action. */
        val disabledReason: String?,
    )

    /**
     * [generations] newest-first - the same order
     * [com.kevin.legion.sync.DatabaseSnapshot.listGenerations] already returns.
     * [runningSchemaVersion] is the live app's own schema version. This mirrors
     * the same refusal [com.kevin.legion.sync.DatabaseSnapshot.restore] enforces
     * for real (a newer-schema generation can't be restored into an older
     * build) so the greyed-out state here and the actual gate never disagree
     * about which generations are eligible - but [DatabaseSnapshot.restore] is
     * still the one that actually enforces it; this function only decides what
     * the button looks like.
     */
    fun generationRows(
        generations: List<DatabaseSnapshot.Generation>,
        runningSchemaVersion: Int,
        formatTime: (Long) -> String,
    ): List<GenerationRow> =
        generations.mapIndexed { index, gen ->
            val newerSchema = gen.schemaVersion > runningSchemaVersion
            GenerationRow(
                generation = gen,
                timeLabel = formatTime(gen.timestampMs),
                rowCountLabel = pluralRows(gen.rowCount),
                isNewest = index == 0,
                canRestore = !newerSchema,
                disabledReason = if (newerSchema) {
                    "From a newer app version (schema v${gen.schemaVersion}) than this one " +
                        "(v$runningSchemaVersion) - update the app before restoring it."
                } else {
                    null
                },
            )
        }

    /** The "last backup: ..." summary line, or a plain no-backups-yet state. Deliberately
     * takes the caller's own [generations] rather than re-deriving "newest" differently
     * from [generationRows] - both read the same max-by-timestamp. */
    fun lastBackupSummary(generations: List<DatabaseSnapshot.Generation>, formatTime: (Long) -> String): String {
        val newest = generations.maxByOrNull { it.timestampMs } ?: return "No backups yet."
        return "Last backup: ${formatTime(newest.timestampMs)} - ${pluralRows(newest.rowCount)}."
    }

    /** The worded confirm-dialog body naming exactly what a restore overwrites (CLAUDE.md
     * §7: worded, never colour-only - there is no colour-only signal anywhere in this
     * flow at all). */
    fun confirmRestoreMessage(generation: DatabaseSnapshot.Generation, formatTime: (Long) -> String): String =
        "This replaces everything on this device - ledger, pantry, workouts, lists, garage, " +
            "all of it - with the backup from ${formatTime(generation.timestampMs)} " +
            "(${pluralRows(generation.rowCount)}). Your current data is saved locally first, so " +
            "this can be undone, but the app must restart to finish. Nothing does this " +
            "automatically or as part of a sync - you have to choose it here. $PHOTO_COVERAGE_CAVEAT " +
            "If a restored record's photo is gone, its field will say so."

    /** One row the "Recover locally" list draws - a [DatabaseSnapshot.LocalRecovery] plus
     * display text. Never needs Drive/adb: these files never left the device (Ravi's
     * review, ALSO-FIX 7). */
    data class LocalRecoveryRow(val recovery: DatabaseSnapshot.LocalRecovery, val timeLabel: String, val kindLabel: String)

    /** [recoveries] in whatever order [com.kevin.legion.sync.DatabaseSnapshot.listLocalRecoveries]
     * returned (newest first). */
    fun localRecoveryRows(recoveries: List<DatabaseSnapshot.LocalRecovery>, formatTime: (Long) -> String): List<LocalRecoveryRow> =
        recoveries.map { r ->
            LocalRecoveryRow(
                recovery = r,
                timeLabel = formatTime(r.timestampMs),
                kindLabel = when (r.kind) {
                    DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY -> "Safety copy"
                    DatabaseSnapshot.LocalRecoveryKind.INTERRUPTED_ORIGINAL -> "Interrupted restore - your original database"
                },
            )
        }

    /** The worded confirm-dialog body for recovering a local (non-Drive) copy. */
    fun confirmLocalRecoveryMessage(row: LocalRecoveryRow, formatTime: (Long) -> String): String =
        "This replaces the current database on this device with the ${row.kindLabel.lowercase()} " +
            "from ${row.timeLabel}. This does not touch Drive. The app must restart to finish. " +
            PHOTO_COVERAGE_CAVEAT

    /**
     * The worded confirm-dialog body for overriding [com.kevin.legion.sync.DatabaseSnapshotGuard]'s
     * refusal (Ravi's review, ALSO-FIX 5) - a distinct, deliberate action, never the routine
     * BACK UP NOW path. [refusalReason] is the exact reason the routine backup was just
     * refused, quoted back so the driver is confirming the SPECIFIC numbers, not a generic
     * "are you sure?".
     */
    fun confirmOverrideGuardMessage(refusalReason: String): String =
        "$refusalReason\n\nOnly do this if you deliberately deleted a lot of data yourself. " +
            "This will overwrite the last good backup on Drive with this smaller one."

    private fun pluralRows(count: Long): String = if (count == 1L) "1 row" else "$count rows"
}
