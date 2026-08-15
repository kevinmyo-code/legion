package com.kevin.legion.gmail

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
 * On-device authorization to the driver's OWN Gmail, read-only - the second Google grant,
 * added by ticket `.scratch/google-account-integration/issues/15-gmail-tools.md`.
 *
 * **Deliberately its own object, not a generalized [com.kevin.legion.sync.DriveAuth].** Ticket
 * 06's Answer settled incremental, independent consent per grant: asking for Gmail must not
 * re-prompt or disturb the Drive grant already in place. The Google Identity Authorization API
 * scopes each [AuthorizationRequest] to exactly the [Scope]s it lists, so a second, separate
 * request naming only [GMAIL_READONLY_SCOPE] already IS the incremental behaviour - no shared
 * state with Drive to coordinate. A "GoogleAuth" abstracting over a list of scopes was
 * considered and rejected: [com.kevin.legion.sync.DriveAuth]'s `Outcome`/`TokenResult` shapes are
 * load-bearing for `SyncEngine` and the Setup Drive probe (ticket 12 fixed a real swallowed-
 * `NeedsConsent` defect there), and touching that file for an unrelated scope risked regressing a
 * grant that has been live since 2026-08-03. [com.kevin.legion.ui.sync.GoogleGrantResolver] is
 * the one place both grants' failures are diagnosed identically, without either auth class
 * knowing the other exists - see its own doc comment.
 *
 * The requested scope is [GMAIL_READONLY_SCOPE]: read Gmail, search it, fetch one message's full
 * text. Never send, reply, delete, or modify - `gmail.readonly` cannot do any of those even if
 * asked. This is a Google-classified RESTRICTED scope (ticket 09's console finding); whether
 * Google actually grants it to Kevin as the project owner without a verification submission is
 * the open empirical question this ticket exists to answer on-device, not by reading more docs.
 *
 * Shape mirrors [com.kevin.legion.sync.DriveAuth] on purpose (same [Outcome]/[ConsentResult]/
 * [TokenResult] three-way split, same [statusCodeOf]/[looksLikeNetworkFailure] diagnostics) so
 * [com.kevin.legion.ui.sync.GoogleGrantResolver.diagnose] and the Setup screen can treat both
 * grants identically without a shared base class.
 */
object GmailAuth {
    /** Read Gmail messages and search them. Cannot send, reply, delete, or modify anything. */
    const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

    sealed interface Outcome {
        data class Authorized(val accessToken: String) : Outcome
        data class NeedsConsent(val pendingIntent: PendingIntent) : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
            .build()

    /**
     * Attempts authorization. Returns [Outcome.Authorized] with a fresh access token if the
     * grant already exists, [Outcome.NeedsConsent] if the driver must approve first (first ever
     * ask, a lapsed Testing-status grant, or a revocation), or [Outcome.Failed] on error (offline,
     * Play Services missing, unregistered signing cert, or - the live test this ticket runs - a
     * restricted-scope refusal from Google).
     */
    suspend fun authorize(context: Context): Outcome =
        try {
            Identity.getAuthorizationClient(context).authorize(request()).await().toOutcome()
        } catch (t: Throwable) {
            Outcome.Failed(t)
        }

    /** Outcome of feeding the consent Activity's result Intent back through [tokenFromConsent]. */
    sealed interface ConsentResult {
        data class Token(val accessToken: String) : ConsentResult
        /** The user backed out of the consent screen. Not a bug - no diagnostic needed. */
        data object Cancelled : ConsentResult
        /** Consent did not complete, and it was not a plain cancel - the [error] is real and worth showing. */
        data class Failed(val error: Throwable) : ConsentResult
    }

    /** Pulls the access token out of the Intent the consent activity returns - see [com.kevin.legion.sync.DriveAuth.tokenFromConsent]. */
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

    /** The three things a caller can do with an [authorize] attempt - see [com.kevin.legion.sync.DriveAuth.TokenResult]. */
    sealed interface TokenResult {
        data class Token(val accessToken: String) : TokenResult
        /** A grant is needed (first ask, lapse, or revocation) and this caller has no Activity
         * context to resolve it interactively - the tool call reports it and points at Setup. */
        data object NeedsConsent : TokenResult
        data class Failed(val error: Throwable) : TokenResult
    }

    /**
     * A fresh Gmail access token, or the specific reason one isn't available. This is what
     * [com.kevin.legion.service.LiveToolbox]'s mail tools call from inside a live tool
     * dispatch - they have a plain [Context], never an Activity, so an [Outcome.NeedsConsent]
     * here can only be reported in words ("you haven't given me access to Gmail yet" / "Gmail
     * needs re-authorising"), never resolved interactively mid-conversation. Interactive consent
     * only ever happens from [com.kevin.legion.ui.sync.GoogleAccessScreen]'s Gmail row.
     */
    suspend fun tokenOrReason(context: Context): TokenResult =
        when (val outcome = authorize(context)) {
            is Outcome.Authorized -> TokenResult.Token(outcome.accessToken)
            is Outcome.NeedsConsent -> TokenResult.NeedsConsent
            is Outcome.Failed -> TokenResult.Failed(outcome.error)
        }

    /**
     * The Play Services status code behind [error] ([ApiException.getStatusCode]), or null if
     * [error] wasn't an [ApiException] at all. Exposed so
     * [com.kevin.legion.ui.sync.GoogleGrantResolver.diagnose] can turn a failure into a
     * specific, actionable message without depending on any GMS/Android type.
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
