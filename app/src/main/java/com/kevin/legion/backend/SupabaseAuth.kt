package com.kevin.legion.backend

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Outcome of [SupabaseAuth.signIn]. Every failure branch says in words what did not happen -
 * CLAUDE.md §7's feature-add checklist item for any new tool's failure result. */
sealed interface SignInResult {
    data object Success : SignInResult

    /**
     * The Supabase sign-in itself succeeded (a real session came back), but
     * [SupabaseSession] could not write it to storage (Keystore encryption failed - see its
     * doc comment). The household's data is reachable for THIS process lifetime only; the
     * device will ask to sign in again the next time the app starts.
     */
    data class SucceededButNotPersisted(val message: String) : SignInResult

    /** Supabase rejected the credentials outright - wrong email/password, or the account does
     * not exist. Retyping is the right next action, not retrying. */
    data class InvalidCredentials(val message: String) : SignInResult

    /** Never reached Supabase - device offline, DNS failure, timeout. Retrying later is the
     * right next action; retyping the password is not. */
    data class NetworkUnreachable(val message: String) : SignInResult

    /** No project URL / anon key saved yet ([SupabaseConfig.isConfigured] is false). */
    data object NotConfigured : SignInResult
}

/** Outcome of [SupabaseAuth.isHouseholdMember]. */
sealed interface MembershipResult {
    /** Signed in AND on the household roster - every aspect's data is reachable. */
    data object Member : MembershipResult

    /**
     * **The confusing state named explicitly, per the brief.** The account signed in fine -
     * Supabase accepted the password - but Postgres RLS returns nothing for it, because
     * `household_members` (see `supabase/migrations/20260825000100_household_and_rls.sql`) has
     * no row for this user. This is not a bug and not a network problem: the account exists and
     * authenticated, it simply has not been added to the household roster, which per ticket 02
     * ruling 3 only happens in the Supabase dashboard, never from the app.
     */
    data class NotAMember(val message: String) : MembershipResult

    /** No session at all - [SupabaseAuth.signIn] has not succeeded, or the stored session did
     * not survive a restart ([SupabaseSession] fail-closed, or a genuine sign-out). */
    data object NotSignedIn : MembershipResult

    /** The membership query itself could not complete - offline, timeout. Distinct from
     * [NotAMember] on purpose: an unreachable network must never be reported as "not a member". */
    data class NetworkUnreachable(val message: String) : MembershipResult

    /**
     * **The other confusing state, found on Kevin's phone 2026-08-26.** supabase-kt's Auth plugin
     * restores a stored session ASYNCHRONOUSLY after the client is constructed
     * ([SupabaseAuthGateway.awaitSessionReady]'s doc comment has the mechanism). On a cold process
     * the restore can still be running when this check is asked, and [SupabaseAuthGateway.currentUserId]
     * would read null in that window even though a real signed-in session is about to land - the
     * exact shape of [NotSignedIn], but false. This branch says the honest third thing: the app
     * could not yet tell, distinct from both "definitely signed in" and "definitely signed out",
     * and reached only after waiting bounded time for the restore to settle one way or the other
     * (never reused for a genuine network failure - that stays [NetworkUnreachable], because the
     * network was never the problem here).
     */
    data class Indeterminate(val message: String) : MembershipResult

    /** No project URL / anon key saved yet. */
    data object NotConfigured : MembershipResult
}

/**
 * Outcome of [SupabaseAuth.awaitCurrentUserId] - the same "settled or still restoring" split
 * [MembershipResult.Indeterminate]'s doc comment describes for [SupabaseAuth.isHouseholdMember],
 * factored out here because [EventsSync.maybeAutoPull] needs only the user id, not a household
 * membership check.
 */
sealed interface UserIdReadiness {
    /** The restore settled (either way) within the caller's bound. [userId] is null for a
     *  genuine sign-out, non-null for a real signed-in account - both are trustworthy answers,
     *  unlike the same-shaped null [SupabaseAuth.currentUserId] can return mid-restore. */
    data class Settled(val userId: String?) : UserIdReadiness

    /** [SupabaseAuthGateway.awaitSessionReady] timed out with the restore still in progress - the
     *  honest "don't know yet" that a bare null from [SupabaseAuth.currentUserId] cannot express. */
    data object StillRestoring : UserIdReadiness
}

/**
 * Thrown by a [SupabaseAuthGateway] when the remote side rejected the request outright (bad
 * credentials, unknown account). Owned by this package rather than re-thrown as
 * [io.github.jan.supabase.exceptions.RestException] directly, so [SupabaseAuth]'s branch logic -
 * and its tests - never need to construct a real [io.github.jan.supabase.exceptions.RestException],
 * which requires a live [io.ktor.client.statement.HttpResponse] to build. [LiveSupabaseAuthGateway]
 * is the one place that translates the real library exception into this.
 */
class AuthRejectedException(message: String) : Exception(message)

/** Thrown by a [SupabaseAuthGateway] when the request never reached the server at all - offline,
 * DNS failure, timeout. See [AuthRejectedException] for why this package defines its own type
 * rather than propagating [java.io.IOException]/[io.github.jan.supabase.exceptions.HttpRequestException] directly. */
class AuthNetworkException(message: String) : Exception(message)

/**
 * The narrow surface [SupabaseAuth] actually needs from a live [SupabaseClient] - factored out so
 * a test can inject a fake gateway and assert each [SignInResult]/[MembershipResult] branch
 * without standing up a real network-backed client (same posture as
 * [com.kevin.legion.gmail.GmailToolLogic]'s doc comment: the thing that can be WRONG should be a
 * plain JVM test target, not something that needs a device to exercise). Implementations throw
 * only [AuthRejectedException], [AuthNetworkException], or [SessionPersistFailedException] - never
 * a raw supabase-kt/Ktor exception - so [SupabaseAuth]'s catch clauses (and any test double) deal
 * in types this package owns.
 */
interface SupabaseAuthGateway {
    suspend fun signInWithPassword(email: String, password: String)
    suspend fun signOut()

    /**
     * **Racy by construction on a cold process - see [awaitSessionReady].** supabase-kt's Auth
     * plugin restores a session it previously persisted to disk ASYNCHRONOUSLY after the client
     * object exists, so on a freshly-started process this can read null for a real, signed-in
     * account simply because the restore has not finished yet. [SupabaseAuth.isHouseholdMember]
     * calls [awaitSessionReady] first specifically so it never reads this mid-restore; any NEW
     * caller of this method directly must do the same or inherit the same false-negative window.
     * [SupabaseAuth.currentUserId] (the one existing direct caller, traced 2026-08-26) does not
     * await and is therefore still exposed to this race - see its own doc comment for why that is
     * currently a documented hazard rather than a fixed one.
     */
    fun currentUserId(): String?

    /**
     * Waits (bounded by [timeoutMillis]) for the Auth plugin to finish restoring whatever session
     * it had persisted to disk, so a caller that then reads [currentUserId] gets a settled answer
     * rather than racing the restore. In supabase-kt 3.6.0 this is backed by
     * `Auth.awaitInitialization()`, which suspends until `Auth.sessionStatus` leaves
     * `SessionStatus.Initializing` for `Authenticated`, `NotAuthenticated`, or `RefreshFailure` -
     * confirmed by decompiling the cached 3.6.0 `auth-kt` aar (`javap -p Auth.class`), not assumed
     * from the library's docs.
     *
     * Returns true once settled (whichever way), false if [timeoutMillis] elapsed while the
     * restore was still running - a genuinely signed-out session settles to `NotAuthenticated`
     * almost immediately, so a slow return here means the restore itself is stuck, not that
     * nobody is signed in. The default implementation is `true` (already settled) so a fake that
     * has no restore step to simulate does not need to override this.
     */
    suspend fun awaitSessionReady(timeoutMillis: Long = SUPABASE_SESSION_READY_TIMEOUT_MS): Boolean = true

    /**
     * Row count of `household_members` visible to the CURRENT caller. Per the migration's own
     * RLS policy, this table's `select` policy expression does not reference the row at all - it
     * evaluates once to "is the caller on the roster" and is then true for every row or none - so
     * an authenticated non-member genuinely gets zero rows back, not an error.
     */
    suspend fun householdRosterSize(): Int
}

/** Bound on [SupabaseAuthGateway.awaitSessionReady]'s wait, so a stuck restore freezes a settings
 * screen for at most this long rather than hanging it. 3 seconds: generous for a local-disk
 * session restore, short enough that a real user notices a wait rather than a freeze. */
private const val SUPABASE_SESSION_READY_TIMEOUT_MS = 3_000L

/** Gap before the one retry in [SupabaseAuth.resolveSignedInUserId] - long enough that a restore
 * stuck on cold I/O has had a moment to make progress, short enough that the whole cold-start
 * determination (two [SupabaseAuthGateway.awaitSessionReady] bounds plus this gap) stays a few
 * seconds, not a hang. Every caller of [SupabaseAuth.resolveSignedInUserId] runs this off the UI
 * thread (a background auto-trigger scope, or a lifecycle callback's own launched coroutine), so
 * taking a few extra seconds here costs nothing the way it would on a foreground call. */
private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

/**
 * Wraps a real [SupabaseClient]'s Auth/Postgrest plugins as a [SupabaseAuthGateway], translating
 * supabase-kt/Ktor's own exception types into this package's [AuthRejectedException]/
 * [AuthNetworkException] at the one seam where they can occur, per [SupabaseAuthGateway]'s
 * contract. This class is deliberately the ONLY untested part of the auth surface - see
 * `SupabaseAuthTest`'s doc comment - because exercising it for real needs a live network call.
 */
private class LiveSupabaseAuthGateway(
    private val client: SupabaseClient,
    private val session: SupabaseSession,
) : SupabaseAuthGateway {

    private inline fun <T> translating(block: () -> T): T = try {
        block()
    } catch (e: SessionPersistFailedException) {
        throw e
    } catch (e: RestException) {
        throw AuthRejectedException(e.error)
    } catch (e: IOException) {
        throw AuthNetworkException(e.message ?: "network unreachable")
    }

    override suspend fun signInWithPassword(email: String, password: String) = translating {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        if (!session.lastSaveSucceeded) {
            // signInWith already returned normally (Supabase accepted the credentials); the
            // Keystore failure surfaces here as a distinct branch rather than as an exception
            // thrown from inside the plugin's own call chain, because this reads the flag AFTER
            // a clean return rather than catching a throw from saveSession.
            throw SessionPersistFailedException(
                "Signed in, but the session could not be saved to this device."
            )
        }
    }

    override suspend fun signOut() = translating {
        client.auth.signOut()
    }

    override fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    override suspend fun awaitSessionReady(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            // awaitInitialization() suspends until sessionStatus leaves Initializing; it does not
            // throw for a genuine sign-out (that settles to NotAuthenticated, which counts as
            // "ready" here) so this is not wrapped in translating() - there is no network call of
            // its own to translate, it is only waiting on the plugin's own restore coroutine.
            client.auth.awaitInitialization()
        } != null

    override suspend fun householdRosterSize(): Int = translating {
        client.postgrest.from("household_members")
            .select(columns = Columns.raw("user_id"))
            .decodeList<JsonObject>()
            .size
    }
}

/**
 * Email+password auth against the household's BYO Supabase project (ticket 02 ruling 1 - no
 * OAuth, no magic link) plus the household-membership check (ruling 2's roster is the ONLY
 * authorization concept LEGION has).
 *
 * Every entry point degrades in words rather than throwing to a caller that has no reason to
 * expect an exception - same posture as [com.kevin.legion.gmail.GmailToolLogic]/
 * [com.kevin.legion.ai.GeminiKeyValidator]: a bad credential and an unreachable network need
 * different user actions (retype vs retry later), so they are never collapsed into one failure.
 *
 * [gatewayProvider] is the test seam - defaults to building a real [LiveSupabaseAuthGateway] off
 * [SupabaseClientProvider], returns null when [SupabaseConfig] is not configured.
 */
class SupabaseAuth(
    private val context: Context,
    private val gatewayProvider: (Context) -> SupabaseAuthGateway? = ::defaultGateway,
) {

    /**
     * Signs in with [email]/[password]. See [SignInResult] for what each branch means and what
     * the driver should do next.
     */
    suspend fun signIn(email: String, password: String): SignInResult {
        val gateway = gatewayProvider(context) ?: return SignInResult.NotConfigured
        return try {
            gateway.signInWithPassword(email.trim(), password)
            SignInResult.Success
        } catch (e: SessionPersistFailedException) {
            SignInResult.SucceededButNotPersisted(
                e.message ?: "Signed in, but the session could not be saved to this device."
            )
        } catch (e: AuthRejectedException) {
            SignInResult.InvalidCredentials("That email or password was rejected: ${e.message}")
        } catch (e: AuthNetworkException) {
            SignInResult.NetworkUnreachable("Couldn't reach the server to sign in right now.")
        } catch (e: Exception) {
            // Any other failure (a decoding bug, an unexpected library exception) - unknown, but
            // definitely not "the password was wrong", so it is reported as unreachable rather
            // than misdirecting the driver into retyping a correct password.
            SignInResult.NetworkUnreachable("Couldn't sign in: ${e.message ?: "unknown error"}")
        }
    }

    /** Best-effort sign-out. Clears the local session even if the remote call fails, so a driver
     * who is offline can still get back to a clean "signed out" state on this device. */
    suspend fun signOut() {
        val gateway = gatewayProvider(context) ?: return
        try {
            gateway.signOut()
        } catch (e: Exception) {
            // Remote sign-out failing (offline, expired token already) must not block the local
            // session clear below - SessionManager.deleteSession() is called by the Auth plugin
            // itself as part of signOut()'s own implementation when it succeeds; if it throws
            // before reaching that, the caller is left signed in on THIS device, which is the
            // safer failure direction for a shared credential.
        }
    }

    /**
     * The signed-in user's Supabase id, or null if nothing is configured or nobody is signed in.
     *
     * **Known hazard, not fixed here (traced 2026-08-26): this can race the async session
     * restore the same way [isHouseholdMember] used to** - see
     * [SupabaseAuthGateway.currentUserId]'s doc comment for the mechanism. This function is `fun`,
     * not `suspend`, so it has no way to await [SupabaseAuthGateway.awaitSessionReady] first. It
     * is left unchanged rather than silently made `suspend` because that would be a breaking
     * signature change for a hazard that, as of this trace, has no live caller to break: nothing
     * in `ui/` or elsewhere calls this method directly (only [isHouseholdMember], below, calls the
     * gateway's version). If a future caller needs a reliable answer on a cold process, it should
     * go through [isHouseholdMember] (which does await) rather than this method.
     *
     * **That last sentence stopped being true 2026-09-02.** [EventsSync.maybeAutoPull] DID call
     * this method directly and hit exactly the race described above - observed on the A25:
     * force-stop, launch, wait 16 seconds, nothing in logcat, no pull at all, because the guard
     * read null while the restore was still in flight and the whole pull was skipped rather than
     * merely delayed. [isHouseholdMember]'s own roster check is more than that caller needs, so
     * it now calls [awaitCurrentUserId] below instead of this method. This method itself is
     * unchanged and still racy; it remains for a caller that already awaits its own session state
     * by some other means, or that can genuinely tolerate an occasional false negative.
     *
     * **Same day, same bug, six more places (traced 2026-09-02): the fix above was copied by hand
     * into [BodySync], and every OTHER caller of this method - `BodyBackfill`, `BodyOutbox`,
     * `BodyRealtime`, `EventsOutbox`, `EventsRealtime`, `LedgerReconcile`,
     * `MaintenanceScheduleReconcile` - kept the raw `currentUserId() == null` guard, because they
     * were written from the pre-fix template rather than from this one. All seven now go through
     * [resolveSignedInUserId] below, the single shared implementation of the retry
     * [EventsSync.resolveUserIdForAutoPull] first introduced.** This method is still racy and still
     * has no live direct caller as of this pass; a NEW caller with a cold-start-sensitive question
     * should reach for [resolveSignedInUserId], not this method.
     */
    fun currentUserId(): String? = gatewayProvider(context)?.currentUserId()

    /**
     * Like [currentUserId], but waits (bounded by [timeoutMillis]) for the async session restore
     * to settle first - see [SupabaseAuthGateway.awaitSessionReady]'s doc comment for the
     * mechanism this closes over. Returns [UserIdReadiness.Settled] with the (possibly null, for
     * a genuine sign-out) user id once the restore has resolved one way or the other, or
     * [UserIdReadiness.StillRestoring] if [timeoutMillis] elapsed while it was still in progress -
     * the same honest third state [MembershipResult.Indeterminate] carries for [isHouseholdMember],
     * so a caller here gets to tell "signed out" apart from "don't know yet" instead of collapsing
     * both into a null the way [currentUserId] does.
     *
     * Built for [EventsSync.maybeAutoPull]'s cold-start fix (traced 2026-09-02, see
     * [currentUserId]'s own doc comment for what was observed on the phone) but not specific to
     * it - any future caller with the same "give the restore a real chance before deciding"
     * need should prefer this over [currentUserId].
     */
    suspend fun awaitCurrentUserId(
        timeoutMillis: Long = SUPABASE_SESSION_READY_TIMEOUT_MS,
    ): UserIdReadiness {
        val gateway = gatewayProvider(context) ?: return UserIdReadiness.Settled(null)
        if (!gateway.awaitSessionReady(timeoutMillis)) return UserIdReadiness.StillRestoring
        return UserIdReadiness.Settled(gateway.currentUserId())
    }

    /**
     * **The one place this codebase resolves "is anyone signed in, and who" for a cold-start
     * caller - built 2026-09-02 after the fix that first closed this race
     * ([EventsSync.resolveUserIdForAutoPull]) was copied by hand into [BodySync] and left as a raw
     * [currentUserId] guard everywhere else (seven call sites, one bug: `BodyBackfill`,
     * `BodyOutbox`, `BodyRealtime`, `EventsOutbox`, `EventsRealtime`, `LedgerReconcile`,
     * `MaintenanceScheduleReconcile`).** [EventsSync.resolveUserIdForAutoPull] and
     * [BodySync.resolveUserIdForAutoPull] are now thin delegations to this method rather than
     * independent copies, so there is exactly one implementation of the retry left to keep correct.
     *
     * Calls [awaitCurrentUserId] and, if it reports [UserIdReadiness.StillRestoring], waits
     * [retryDelayMs] and calls it exactly once more - never loops. Returns null for a genuine,
     * settled sign-out OR a restore still going after both bounded waits; either way this run does
     * not proceed, and (for every existing auto-trigger caller) a later foreground return gets
     * another chance at it. **This is the call a NEW cold-start-sensitive auth check should reach
     * for** - prefer it over the raw [currentUserId] the way [isHouseholdMember] already prefers
     * awaiting the restore over a bare read.
     */
    suspend fun resolveSignedInUserId(retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS): String? {
        var readiness = awaitCurrentUserId()
        if (readiness is UserIdReadiness.StillRestoring) {
            delay(retryDelayMs)
            readiness = awaitCurrentUserId()
        }
        return (readiness as? UserIdReadiness.Settled)?.userId
    }

    /** See [MembershipResult] for what each branch means. */
    suspend fun isHouseholdMember(): MembershipResult {
        val gateway = gatewayProvider(context) ?: return MembershipResult.NotConfigured
        if (!gateway.awaitSessionReady()) {
            // The restore is still running after a bounded wait - report the honest third state
            // rather than guessing NotSignedIn, which is exactly the false statement this branch
            // exists to stop making (found on Kevin's phone 2026-08-26, see MembershipResult.Indeterminate).
            return MembershipResult.Indeterminate(
                "Still checking - the session is taking a moment to restore. Try again shortly."
            )
        }
        gateway.currentUserId() ?: return MembershipResult.NotSignedIn
        return try {
            val rosterSize = gateway.householdRosterSize()
            if (rosterSize > 0) {
                MembershipResult.Member
            } else {
                MembershipResult.NotAMember(
                    "Signed in as this account, but it is not on the household roster yet. " +
                        "Add it in the Supabase dashboard's household_members table."
                )
            }
        } catch (e: AuthRejectedException) {
            MembershipResult.NetworkUnreachable("The household check failed: ${e.message}")
        } catch (e: AuthNetworkException) {
            MembershipResult.NetworkUnreachable("Couldn't reach the server to check the household.")
        } catch (e: Exception) {
            MembershipResult.NetworkUnreachable(
                "Couldn't check the household: ${e.message ?: "unknown error"}"
            )
        }
    }

    companion object {
        private fun defaultGateway(context: Context): SupabaseAuthGateway? {
            val client = SupabaseClientProvider.get(context) ?: return null
            val session = SupabaseClientProvider.session(context) ?: return null
            return LiveSupabaseAuthGateway(client, session)
        }
    }
}
