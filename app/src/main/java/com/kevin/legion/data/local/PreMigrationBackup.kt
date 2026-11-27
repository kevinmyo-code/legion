package com.kevin.legion.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * A local copy of the database files, taken before Room migrates them (hardening ticket 03).
 *
 * [CopyBeforeOpenFactory] wraps Room's open helper, so the copy runs at Room's first open, the
 * moment before any migration, on the thread making that first query. Room refuses DAO queries on
 * the main thread, and building the database (`CarDatabase.getDatabase`, its LOCK) does no I/O, so
 * no UI caller waits on the copy.
 *
 * Only an upgrade is copied: a stored `user_version` between 1 and the target, or one that cannot
 * be read. The main file and its `-wal`/`-shm`/`-journal` sidecars go into
 * `<timestamp>_v<from>_to_v<to>/`. A pair already kept is never copied again, so a migration that
 * keeps failing, or starts of an older build (a downgrade, which Room refuses), never push out the
 * copy a restore needs. The newest [KEEP] copies are kept, each a different pair.
 *
 * The copy lives in `noBackupFilesDir`, so Android's own device backup never carries it: it is a
 * local copy and nothing else. Restore is manual and written up in `docs/architecture/c3-data.md`.
 * It is not offered in the app, because the running build is the one whose migration a restore
 * would be undoing, and opening the copy with it runs that migration again.
 */
object PreMigrationBackup {
    private const val TAG = "PreMigrationBackup"
    const val DIR_NAME = "pre-migration-backups"
    const val KEEP = 3
    private const val PARTIAL_PREFIX = ".partial-"
    private const val FAILURE_FILE = "last-failure.txt"
    private val SIDECARS = listOf("", "-wal", "-shm", "-journal")
    private val NAME = Regex("""^(\d+)_v(\d+|unknown)_to_v(\d+)$""")

    /** One complete copy. [fromVersion] is null when the stored version could not be read. */
    data class Backup(val takenAtMs: Long, val fromVersion: Int?, val toVersion: Int, val dir: File)

    sealed interface Outcome {
        data object NotNeeded : Outcome
        data class Taken(val backup: Backup) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** What the Drive screen says about the copies on this phone. */
    sealed interface Status {
        data object None : Status
        data class Taken(val latest: Backup) : Status
        /** The newest attempt failed; [latest] is the newest good copy still kept, if any. */
        data class Failed(val atMs: Long, val reason: String, val latest: Backup?) : Status
    }

    fun rootDir(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

    /** Room's open helper with the copy in front of its first open. Pass to `openHelperFactory`. */
    class CopyBeforeOpenFactory(
        private val context: Context,
        private val targetVersion: Int,
        private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val inner = delegate.create(configuration)
            val name = configuration.name ?: return inner
            return object : SupportSQLiteOpenHelper by inner {
                private var checked = false

                private fun copyOnce() = synchronized(this) {
                    if (!checked) {
                        checked = true
                        runBeforeOpen(context, context.getDatabasePath(name), targetVersion)
                    }
                }

                override val writableDatabase get() = copyOnce().let { inner.writableDatabase }
                override val readableDatabase get() = copyOnce().let { inner.readableDatabase }
            }
        }
    }

    /** Never throws: a failed copy is recorded for the Drive screen and the app opens anyway. */
    private fun runBeforeOpen(context: Context, dbFile: File, targetVersion: Int) {
        val root = rootDir(context)
        val now = System.currentTimeMillis()
        val outcome = runCatching { backUpIfSchemaChanges(dbFile, root, targetVersion, now) }
            .getOrElse { e ->
                val reason = e.message ?: e.javaClass.simpleName
                recordFailure(root, now, reason)
                Outcome.Failed(reason)
            }
        when (outcome) {
            is Outcome.Taken -> Log.i(TAG, "copied the database to ${outcome.backup.dir.name}")
            is Outcome.Failed -> Log.w(TAG, "pre-migration copy failed: ${outcome.reason}")
            Outcome.NotNeeded -> Unit
        }
    }

    /** Writes the failure the Drive screen shows. Best effort: an unwritable root cannot record it. */
    internal fun recordFailure(root: File, nowMs: Long, reason: String) {
        runCatching {
            root.mkdirs()
            File(root, FAILURE_FILE).writeText("$nowMs\n$reason")
        }
    }

    internal fun backUpIfSchemaChanges(
        dbFile: File,
        root: File,
        targetVersion: Int,
        nowMs: Long,
        copyFile: (File, File) -> Unit = { from, to -> from.copyTo(to, overwrite = true) },
    ): Outcome {
        if (!dbFile.exists()) return Outcome.NotNeeded
        val stored = storedVersion(dbFile)
        val upgrade = stored == null || stored in 1 until targetVersion
        val alreadyKept = list(root).any { it.fromVersion == stored && it.toVersion == targetVersion }
        return if (upgrade && !alreadyKept) {
            copyFiles(dbFile, root, stored, targetVersion, nowMs, copyFile)
        } else {
            Outcome.NotNeeded
        }
    }

    private fun copyFiles(
        dbFile: File,
        root: File,
        stored: Int?,
        targetVersion: Int,
        nowMs: Long,
        copyFile: (File, File) -> Unit,
    ): Outcome {
        root.mkdirs()
        root.listFiles()?.filter { it.name.startsWith(PARTIAL_PREFIX) }?.forEach { it.deleteRecursively() }
        val partial = File(root, "$PARTIAL_PREFIX$nowMs")
        return runCatching<Outcome> {
            check(partial.mkdirs()) { "could not create ${partial.name}" }
            for (suffix in SIDECARS) {
                val source = File(dbFile.path + suffix)
                if (source.exists()) copyFile(source, File(partial, source.name))
            }
            val final = File(root, "${nowMs}_v${stored ?: "unknown"}_to_v$targetVersion")
            check(partial.renameTo(final)) { "could not finish ${final.name}" }
            File(root, FAILURE_FILE).delete()
            list(root).drop(KEEP).forEach { it.dir.deleteRecursively() }
            Outcome.Taken(Backup(nowMs, stored, targetVersion, final))
        }.getOrElse { e ->
            partial.deleteRecursively()
            val reason = e.message ?: e.javaClass.simpleName
            recordFailure(root, nowMs, reason)
            Outcome.Failed(reason)
        }
    }

    /**
     * The stored `user_version`, read through SQLite so a version still in the `-wal` counts. Null
     * when SQLite cannot read the file. The no-op error handler matters: the platform default
     * deletes a file it finds corrupt, which is the one file this copy exists to keep.
     */
    internal fun storedVersion(dbFile: File): Int? = runCatching {
        SQLiteDatabase.openDatabase(
            dbFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ) { }.use { it.version }
    }.getOrNull()

    /** Complete copies, newest first. A `.partial-` directory is never one. */
    fun list(root: File): List<Backup> =
        root.listFiles().orEmpty().mapNotNull { dir ->
            val m = NAME.matchEntire(dir.name) ?: return@mapNotNull null
            if (!dir.isDirectory) return@mapNotNull null
            val (takenAt, from, to) = m.destructured
            Backup(takenAt.toLong(), from.toIntOrNull(), to.toInt(), dir)
        }.sortedByDescending { it.takenAtMs }

    fun status(root: File): Status {
        val latest = list(root).firstOrNull()
        val failure = File(root, FAILURE_FILE).takeIf { it.isFile }?.readLines()
        val failedAt = failure?.firstOrNull()?.toLongOrNull()
        if (failedAt != null && (latest == null || failedAt > latest.takenAtMs)) {
            return Status.Failed(failedAt, failure.drop(1).joinToString(" "), latest)
        }
        return latest?.let { Status.Taken(it) } ?: Status.None
    }
}
