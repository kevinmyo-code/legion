package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.io.IOException

/** The bucket `20260827000400_receipt_photos_bucket.sql` provisions - private, JPEG-only,
 * household-scoped by that migration's `storage.objects` policies. */
private const val BUCKET = "receipt-photos"

/**
 * [PantryPhotoBackend]'s real implementation over supabase-kt's Storage plugin
 * (`storage-kt`, installed by [SupabaseClientProvider]). This is the one deliberately untested
 * seam in the pantry-photo cutover - exercising it for real needs a live project - same posture as
 * [SupabasePlacesBackend]/`LiveSupabaseAuthGateway`. [PantryPhotoBackend] is the fake-friendly
 * interface; every branch here does nothing but translate exceptions.
 */
class SupabasePhotoBackend(private val client: SupabaseClient) : PantryPhotoBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(PantryPhotoBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(PantryPhotoBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(PantryPhotoBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    override suspend fun uploadReceiptPhoto(objectPath: String, bytes: ByteArray): Result<String> =
        translating("back up that receipt photo") {
            client.storage.from(BUCKET).upload(objectPath, bytes) {
                contentType = ContentType.Image.JPEG
                // objectPath is content_sha256-derived (see PantryPhotoBackend's own doc comment),
                // so a retried upload of the SAME bytes lands on the SAME path - upsert makes that
                // a safe no-op instead of a conflict, matching the commit RPC's own idempotency
                // posture for the row itself.
                upsert = true
            }
            objectPath
        }

    // Not routed through translating() - downloadReceiptPhoto's "nothing at this path" case
    // (NotFoundRestException) is this interface's OWN "expected negative"
    // (Result.success(null), per PantryPhotoBackend's own doc comment) and must be caught BEFORE
    // the generic RestException branch below, since NotFoundRestException is itself a RestException
    // subtype and translating()'s single catch order has no way to special-case it.
    override suspend fun downloadReceiptPhoto(objectPath: String): Result<ByteArray?> = try {
        Result.success(client.storage.from(BUCKET).downloadAuthenticated(objectPath))
    } catch (e: NotFoundRestException) {
        Result.success(null)
    } catch (e: RestException) {
        Result.failure(PantryPhotoBackendException("Supabase rejected the request to fetch that receipt photo: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(PantryPhotoBackendException("Couldn't reach the server to fetch that receipt photo."))
    } catch (e: Exception) {
        Result.failure(PantryPhotoBackendException("Couldn't fetch that receipt photo: ${e.message ?: "unknown error"}"))
    }
}
