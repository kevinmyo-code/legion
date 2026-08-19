package com.kevin.legion.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.io.IOException
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
 *
 * **Known, tracked, unresolved limitation (CLAUDE.md 2, "still threaten plans"):**
 * Google identifies this app purely by package name + signing SHA-1 registered
 * against an Android OAuth client in the Google Cloud console. A stranger who
 * clones this repo and sideloads their own build - a different signing cert -
 * gets no matching client and every [authorize]/[tokenFromConsent] call fails
 * with an [ApiException] whose [ApiException.getStatusCode] is
 * [CommonStatusCodes.DEVELOPER_ERROR] (10). That is a direct threat to the
 * clone-and-run requirement and this file cannot fix it - registering a client
 * is console configuration, not code. What this file CAN do, and what it was
 * failing to do until 2026-08-03 (first real device run: consent completed,
 * the screen said "wasn't connected", with no way to tell a DEVELOPER_ERROR
 * apart from Kevin having simply tapped cancel), is surface that status code
 * legibly instead of discarding it - see [ConsentResult.Failed] and
 * [com.kevin.legion.ui.sync.GoogleGrantResolver.diagnose].
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

    /** Outcome of feeding the consent Activity's result Intent back through [tokenFromConsent]. */
    sealed interface ConsentResult {
        /** Consent was granted; a fresh access token is in hand. */
        data class Token(val accessToken: String) : ConsentResult

        /** The user backed out of the consent screen. Not a bug - no diagnostic needed. */
        data object Cancelled : ConsentResult

        /** Consent did not complete, and it was not a plain cancel - the [error] is real and worth showing. */
        data class Failed(val error: Throwable) : ConsentResult
    }

    /**
     * Pulls the access token out of the Intent the consent activity returns, distinguishing
     * a real token, the user cancelling, and a genuine failure carrying the [Throwable].
     *
     * This used to be `runCatching { ... }.getOrNull()`, collapsing all three into a single
     * null - which is exactly the defect Kevin hit on the first real device run: an
     * unregistered signing cert ([ApiException] with [CommonStatusCodes.DEVELOPER_ERROR])
     * read identically to him tapping cancel, and there was no way to tell them apart, let
     * alone show which one happened. See this class's doc comment for the underlying blocker.
     */
    fun tokenFromConsent(context: Context, data: Intent?): ConsentResult =
        try {
            val token = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
                .accessToken
            if (token != null) {
                ConsentResult.Token(token)
            } else {
                ConsentResult.Failed(IllegalStateException("Consent completed but returned no access token"))
            }
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.CANCELED) ConsentResult.Cancelled else ConsentResult.Failed(e)
        } catch (t: Throwable) {
            ConsentResult.Failed(t)
        }

    /**
     * The three things a caller can do with an authorize() attempt, spelled out instead of
     * collapsed to a nullable String - the ticket-12 fix for the defect ticket 06 point 4
     * surfaced: [accessTokenOrNull] used to fold [Outcome.NeedsConsent] (a grant that lapsed
     * or was revoked) into the exact same `null` as [Outcome.Failed] (a real error), so
     * [SyncEngine] and [com.kevin.legion.sync.DatabaseSnapshot] could never tell "you were
     * never connected" apart from "you WERE connected and it stopped" - both read as a
     * generic "couldn't reach your Google Drive" every time, including on a genuine
     * revocation days into using the app. [NeedsConsent] here carries no [PendingIntent]
     * (unlike [Outcome.NeedsConsent]): the callers this exists for (a background sync pass,
     * a backup/restore call) cannot launch an interactive consent Activity mid-flight, they
     * can only report that one is needed and point the driver at Setup.
     */
    sealed interface TokenResult {
        data class Token(val accessToken: String) : TokenResult
        /** A grant is needed (first connect, lapse, or revocation) and this caller has no
         * Activity context to resolve it interactively - the driver must go re-authorise
         * from Setup. */
        data object NeedsConsent : TokenResult
        data class Failed(val error: Throwable) : TokenResult
    }

    /**
     * A fresh Drive access token, or the specific reason one isn't available - see
     * [TokenResult]. This is [authorize]'s [Outcome] flattened into the three-way shape
     * background callers actually need, distinguishing "needs (re-)authorisation" from
     * "a real error occurred" rather than folding both into one signal the way
     * [accessTokenOrNull] still deliberately does for its own, unchanged, callers.
     */
    suspend fun tokenOrReason(context: Context): TokenResult =
        when (val outcome = authorize(context)) {
            is Outcome.Authorized -> TokenResult.Token(outcome.accessToken)
            is Outcome.NeedsConsent -> TokenResult.NeedsConsent
            is Outcome.Failed -> TokenResult.Failed(outcome.error)
        }

    /**
     * A fresh Drive access token for a REST call, or null if not yet authorized
     * (needs interactive consent) or on error. [SyncEngine] uses this: a null
     * simply means "can't sync right now", handled gracefully like any offline
     * state - never a crash. Deliberately still nullable and unchanged in shape:
     * [SyncEngine]'s graceful null-means-cannot-sync path is exactly right and
     * this fix does not touch it - see [authorize]'s own [Outcome.Failed] (which
     * DOES carry the [Throwable]) for the diagnosable path instead. Now implemented
     * in terms of [tokenOrReason]; the reason a call sees a null here is exactly the
     * thing [tokenOrReason] would have named, for any caller that has since been
     * upgraded to want it.
     */
    suspend fun accessTokenOrNull(context: Context): String? =
        (tokenOrReason(context) as? TokenResult.Token)?.accessToken

    /**
     * The Play Services status code behind [error] ([ApiException.getStatusCode]),
     * or null if [error] wasn't an [ApiException] at all (a plain I/O failure, an
     * unexpected runtime exception, etc). Exposed so
     * [com.kevin.legion.ui.sync.GoogleGrantResolver.diagnose] can turn a failure
     * into a specific, actionable message without that class depending on any
     * GMS/Android type - see its own doc comment for why it stays a plain JVM unit.
     */
    fun statusCodeOf(error: Throwable): Int? = (error as? ApiException)?.statusCode

    /** Whether [error] looks like a plain network failure (offline, DNS, timeout) that never reached Play Services at all. */
    fun looksLikeNetworkFailure(error: Throwable): Boolean = error is IOException

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
