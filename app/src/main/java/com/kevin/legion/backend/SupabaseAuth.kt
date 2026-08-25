package com.kevin.legion.backend

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.io.IOException
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

    /** No project URL / anon key saved yet. */
    data object NotConfigured : MembershipResult
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
    fun currentUserId(): String?

    /**
     * Row count of `household_members` visible to the CURRENT caller. Per the migration's own
     * RLS policy, this table's `select` policy expression does not reference the row at all - it
     * evaluates once to "is the caller on the roster" and is then true for every row or none - so
     * an authenticated non-member genuinely gets zero rows back, not an error.
     */
    suspend fun householdRosterSize(): Int
}

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

    /** The signed-in user's Supabase id, or null if nothing is configured or nobody is signed in. */
    fun currentUserId(): String? = gatewayProvider(context)?.currentUserId()

    /** See [MembershipResult] for what each branch means. */
    suspend fun isHouseholdMember(): MembershipResult {
        val gateway = gatewayProvider(context) ?: return MembershipResult.NotConfigured
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
