package com.kevin.legion.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device authorization to the driver's OWN Google Drive `appDataFolder`, for
 * cross-device BYO-cloud sync (S1). Wraps the Google Identity Authorization API
 * (`com.google.android.gms.auth.api.identity`), which mints and refreshes a
 * Drive access token client-side - there is no Kevin-hosted server in the loop,
 * so this holds no secret and stores no token; it asks Google for a fresh one
 * when [SyncEngine] needs it.
 *
 * The requested scope is the NARROW `drive.appdata`: the app can only see, create,
 * and delete files in its own hidden application-data folder, never the driver's
 * real Drive files. The app is identified to Google purely by package name +
 * signing SHA-1 (the Android OAuth client registered in the Firebase project), so
 * no client-id string is embedded here.
 *
 * The first authorization (or one after the driver revokes access in their Google
 * account) needs an interactive consent: [authorize] then returns
 * [Outcome.NeedsConsent] with a [PendingIntent] the UI must launch from an
 * ActivityResult launcher; feed the returned Intent back through [tokenFromConsent].
 * After consent, subsequent calls return [Outcome.Authorized] silently.
 */
object DriveAuth {
    /** See, create, and delete ONLY the app's own hidden appDataFolder - not the user's real files. */
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    sealed interface Outcome {
        data class Authorized(val accessToken: String) : Outcome
        data class NeedsConsent(val pendingIntent: PendingIntent) : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

    /**
     * Attempts authorization. Returns [Outcome.Authorized] with a fresh access
     * token if the grant already exists, [Outcome.NeedsConsent] if the driver
     * must approve first, or [Outcome.Failed] on error (offline, Play Services
     * missing, unregistered signing cert).
     */
    suspend fun authorize(context: Context): Outcome =
        try {
            Identity.getAuthorizationClient(context).authorize(request()).await().toOutcome()
        } catch (t: Throwable) {
            Outcome.Failed(t)
        }

    /** Pulls the access token out of the Intent the consent activity returns; null on cancel/error. */
    fun tokenFromConsent(context: Context, data: Intent?): String? =
        runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
                .accessToken
        }.getOrNull()

    /**
     * A fresh Drive access token for a REST call, or null if not yet authorized
     * (needs interactive consent) or on error. [SyncEngine] uses this: a null
     * simply means "can't sync right now", handled gracefully like any offline
     * state - never a crash.
     */
    suspend fun accessTokenOrNull(context: Context): String? =
        (authorize(context) as? Outcome.Authorized)?.accessToken

    private fun AuthorizationResult.toOutcome(): Outcome =
        if (hasResolution()) {
            pendingIntent?.let { Outcome.NeedsConsent(it) }
                ?: Outcome.Failed(IllegalStateException("Authorization needs consent but returned no PendingIntent"))
        } else {
            accessToken?.let { Outcome.Authorized(it) }
                ?: Outcome.Failed(IllegalStateException("Authorization succeeded but returned no access token"))
        }

    /** Minimal Task -> suspend bridge so we don't pull in kotlinx-coroutines-play-services. */
    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resumeWithException(it) }
        }
}
