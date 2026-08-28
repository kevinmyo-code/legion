package com.kevin.legion.backend

/**
 * The Storage half of ticket 01 ruling 10 as amended
 * (`.scratch/backend-erp/issues/09-backups-do-not-cover-files.md`) - receipt photos get a durable,
 * replicated home in the household's own Supabase project instead of relying on the app-private
 * `files/` directory, which neither `DatabaseSnapshot` nor an app uninstall preserves. Mirrors
 * [PlacesBackend]/[PantryBackend]'s own shape exactly: no [io.github.jan.supabase.SupabaseClient]
 * in the interface, every function returns [Result] rather than throwing, so
 * [com.kevin.legion.pantry.PantryController] never has to distinguish "the request failed" from
 * "the request succeeded and returned nothing" (CLAUDE.md section 7's outcome-verb rule needs that
 * distinction to hold).
 *
 * **`objectPath` is always the receipt photo's own `content_sha256`** (the same hash
 * [com.kevin.legion.pantry.PantryController.buildCommitReceiptPayload] already computes over the
 * identical bytes for the commit RPC's idempotency key), not a server-generated id or a random
 * UUID. Two consequences, both deliberate: a retried upload of the same bytes lands on the same
 * path (handled by `upsert` in [SupabasePhotoBackend.uploadReceiptPhoto], never a conflict), and
 * the object path never needs a lookup table of its own - it is derivable from the photo bytes
 * alone, the same way the commit RPC's idempotency already works.
 */
interface PantryPhotoBackend {

    /**
     * Uploads [bytes] to the household's private `receipt-photos` bucket under [objectPath].
     * Returns the same [objectPath] back on success (nothing server-generated to reconcile) so the
     * caller can store it as-is against the committed receipt row's `photo_object_path`.
     *
     * **There is no "expected negative" here the way [PlacesBackend.softDelete]'s
     * `Result.success(false)` has one** - an upload either lands or it does not, so every
     * unsuccessful outcome is a genuine [Result.failure]. **A failed upload must never lose the
     * receipt itself**: [com.kevin.legion.pantry.PantryController.commitReceiptRemote] commits the
     * receipt's figures regardless of this call's outcome, with `photo_object_path` left null and
     * the failure worded into the result message - losing financial data because a photo backup
     * failed would be strictly worse than the durability gap this interface exists to close.
     */
    suspend fun uploadReceiptPhoto(objectPath: String, bytes: ByteArray): Result<String>

    /**
     * Downloads the bytes stored at [objectPath]. `Result.success(null)` is this interface's
     * analogue of [PlacesBackend.softDelete]'s `Result.success(false)` - the EXPECTED negative for
     * a byte-array-returning call: nothing is stored at that path (the receipt predates ticket 09,
     * or the upload that would have written it failed and was worded rather than retried), never
     * reported as a failure. `Result.failure` means the request itself did not complete (offline,
     * rejected, unauthenticated).
     */
    suspend fun downloadReceiptPhoto(objectPath: String): Result<ByteArray?>
}

/** Thrown (wrapped in [Result.failure]) by [SupabasePhotoBackend] for every failure branch - owned
 * by this package, never a raw supabase-kt/Ktor exception, same posture as
 * [PlacesBackendException]/[PantryBackendException]. */
class PantryPhotoBackendException(message: String) : Exception(message)
