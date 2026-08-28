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
 *
 * **A THIRD fact joined the first two once ticket 09's Storage half landed
 * (`SupabasePhotoBackend`/`PantryController.commitReceiptRemote`):** a receipt whose local staging
 * file is gone is no longer automatically "lost" - [com.kevin.legion.pantry.PantryReceipt]'s own
 * class doc notes the local file is deleted by design the moment a receipt is gated, on EVERY
 * successfully-committed receipt, not just ones the ticket 09 incident touched. Reporting every one
 * of those as [Status.MISSING] would itself be a quiet lie in the other direction - claiming a
 * photo is unrecoverable when it is sitting safely in the household's Storage bucket. [status] now
 * takes a caller-supplied `hasRemoteCopy` bit (does this row's `photo_object_path` column carry a
 * value) so [Status.ON_SERVER] can say the true thing: not on this device, but not gone either.
 */
object PhotoFieldResolver {

    /** The four states a photo field can be in, once the caller has actually checked the disk (and,
     * since ticket 09, whether a server-side object path is on record) - never inferred from the
     * path string alone, since a non-blank path proves nothing about whether the file survived. */
    enum class Status {
        /** No path stored at all (null or blank) AND no remote copy on record. The record never
         * had a photo - not a loss. */
        NONE,

        /** A path is stored and the file exists at it. The ordinary, healthy state. */
        ON_FILE,

        /** No local file (or none stored), but a `photo_object_path` is on record - the photo's
         * durable copy lives in the household's Supabase Storage bucket. Distinct from
         * [Status.MISSING] on purpose: this is the expected, healthy shape of a committed receipt
         * once its local staging copy is cleaned up, not a loss. */
        ON_SERVER,

        /** A path is stored, nothing exists there anymore, AND there is no remote copy on record.
         * This is what an uninstall, a restore-without-`files/`, or (per the ticket) a
         * `connectedDebugAndroidTest` run wiping app-private storage leaves behind for a receipt
         * that predates ticket 09 (or whose upload failed) - the database says a photo existed and
         * it is genuinely gone from every place this app knows to look. */
        MISSING,
    }

    /**
     * [currentPath] is the raw field value, exactly as read from the record payload -
     * [android.graphics.BitmapFactory]/`java.io.File` never enter this function, so it needs no
     * Robolectric. [fileExistsAt] is the caller's own `File(path).exists()` result (or an
     * equivalent check against wherever the photo store actually keeps the bytes); passed in
     * rather than performed here so this object stays a plain JVM unit-test target with the
     * filesystem call injected, same seam shape as [ScheduledBackup.runIfDue]'s `now`/`backup`
     * parameters. [hasRemoteCopy] defaults to `false` so every pre-ticket-09 caller (anything not
     * reading `pantry_receipts.photoObjectPath`) keeps its exact old NONE/ON_FILE/MISSING behaviour
     * with no source change required.
     */
    fun status(currentPath: String?, hasRemoteCopy: Boolean = false, fileExistsAt: (String) -> Boolean): Status {
        if (!currentPath.isNullOrBlank() && fileExistsAt(currentPath)) return Status.ON_FILE
        if (hasRemoteCopy) return Status.ON_SERVER
        return if (currentPath.isNullOrBlank()) Status.NONE else Status.MISSING
    }

    /** The label to render for [status], or null when nothing should be shown (the [Status.NONE]
     * case - a record with no photo draws no photo-status line at all, same as before this
     * ticket). Worded so [Status.MISSING] can never be mistaken for [Status.NONE], [Status.ON_SERVER],
     * or a transient loading state - CLAUDE.md §7's "worded, never colour-only" rule, and this
     * codebase's habit (see [com.kevin.legion.ui.widgets.EngineWidgets.PhotoWidget]'s own
     * "photo could not be read" branch) of saying which of the facts happened rather than
     * rendering a blank space either way. */
    fun label(status: Status): String? = when (status) {
        Status.NONE -> null
        Status.ON_FILE -> "PHOTO ON FILE"
        Status.ON_SERVER -> "PHOTO BACKED UP - not on this device, but saved to your Supabase project."
        Status.MISSING ->
            "PHOTO MISSING - the file this record pointed to is gone (backups do not cover " +
                "photo files, only the database). Not recoverable here."
    }
}
