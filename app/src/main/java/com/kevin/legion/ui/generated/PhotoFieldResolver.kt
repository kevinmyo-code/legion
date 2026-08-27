package com.kevin.legion.ui.generated

/**
 * Pure derivation of what a [com.kevin.legion.data.local.FieldType.PHOTO] field's stored path
 * means right now - `ui/generated/GeneratedFormScreen.kt`'s photo field used to read a non-null
 * `currentPath` as "PHOTO ON FILE" outright, which was true the day it was written but stops
 * being true the moment the file underneath it disappears. `.scratch/backend-erp/issues/09-
 * backups-do-not-cover-files.md`: [com.kevin.legion.sync.DatabaseSnapshot] restores the database
 * row with its stored path intact while the `files/` directory the path points into was never
 * part of the backup, so a restored record can hold a path to nothing. Same shape as
 * [com.kevin.legion.ui.sync.DriveBackupResolver]/[com.kevin.legion.ui.settings.BackendMigrationResolver]:
 * the caller does the real file-existence check (a plain `java.io.File(path).exists()`, no
 * Android dependency, so this stays a plain JVM unit-test target) and this object only turns the
 * two booleans into a verdict and a worded label.
 *
 * **The two facts this exists to keep apart (CLAUDE.md's "unreadable and empty are different
 * sentences", extended from a permission read to a file read):** a record that never had a photo
 * attached looks identical, in the payload alone, to nothing - both are a null/blank
 * `currentPath`. A record that HAD one and lost it is a non-blank path with no file at the other
 * end. Collapsing those into one message would be the same quiet lie CLAUDE.md's rule 7
 * condition 3 forbids for a reconciliation gate, aimed here at a photo instead of a ledger row.
 */
object PhotoFieldResolver {

    /** The three states a photo field can be in, once the caller has actually checked the disk -
     * never inferred from the path string alone, since a non-blank path proves nothing about
     * whether the file survived. */
    enum class Status {
        /** No path stored at all (null or blank). The record never had a photo - not a loss. */
        NONE,

        /** A path is stored and the file exists at it. The ordinary, healthy state. */
        ON_FILE,

        /** A path is stored but nothing exists there anymore. This is what an uninstall, a
         * restore-without-`files/`, or (per the ticket) a `connectedDebugAndroidTest` run wiping
         * app-private storage leaves behind - the database says a photo exists and it does not. */
        MISSING,
    }

    /**
     * [currentPath] is the raw field value, exactly as read from the record payload -
     * [android.graphics.BitmapFactory]/`java.io.File` never enter this function, so it needs no
     * Robolectric. [fileExistsAt] is the caller's own `File(path).exists()` result (or an
     * equivalent check against wherever the photo store actually keeps the bytes); passed in
     * rather than performed here so this object stays a plain JVM unit-test target with the
     * filesystem call injected, same seam shape as [ScheduledBackup.runIfDue]'s `now`/`backup`
     * parameters.
     */
    fun status(currentPath: String?, fileExistsAt: (String) -> Boolean): Status {
        if (currentPath.isNullOrBlank()) return Status.NONE
        return if (fileExistsAt(currentPath)) Status.ON_FILE else Status.MISSING
    }

    /** The label to render for [status], or null when nothing should be shown (the [Status.NONE]
     * case - a record with no photo draws no photo-status line at all, same as before this
     * ticket). Worded so [Status.MISSING] can never be mistaken for [Status.NONE] or for a
     * transient loading state - CLAUDE.md §7's "worded, never colour-only" rule, and this
     * codebase's habit (see [com.kevin.legion.ui.widgets.EngineWidgets.PhotoWidget]'s own
     * "photo could not be read" branch) of saying which of the two facts happened rather than
     * rendering a blank space either way. */
    fun label(status: Status): String? = when (status) {
        Status.NONE -> null
        Status.ON_FILE -> "PHOTO ON FILE"
        Status.MISSING ->
            "PHOTO MISSING - the file this record pointed to is gone (backups do not cover " +
                "photo files, only the database). Not recoverable here."
    }
}
