package com.kevin.legion.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [SupabaseAuth]'s failure branches - each one worded distinctly, per the brief and CLAUDE.md §7's
 * "failure result says in words what did not happen" checklist item. Exercised entirely through
 * the injected [SupabaseAuthGateway] seam and this package's own [AuthRejectedException]/
 * [AuthNetworkException] types, never a real network-backed [io.github.jan.supabase.SupabaseClient]
 * or a real [io.github.jan.supabase.exceptions.RestException] (which needs a live
 * [io.ktor.client.statement.HttpResponse] to construct) - same posture as
 * [com.kevin.legion.gmail.GmailToolLogic]: the thing that can be wrong here is plain logic, not
 * the network. [LiveSupabaseAuthGateway] (private, real-client-backed) is the one untested seam,
 * by design - see its own doc comment. Robolectric only because [SupabaseAuth]'s constructor
 * takes a real [android.content.Context] (every gatewayProvider lambda in this suite ignores it) -
 * no SharedPreferences or Keystore path is exercised here.
 */
@RunWith(RobolectricTestRunner::class)
class SupabaseAuthTest {

    private val fakeContext = RuntimeEnvironment.getApplication()

    private class FakeGateway(
        var signInThrows: Throwable? = null,
        var userId: String? = null,
        var rosterSize: Int = 0,
        var rosterThrows: Throwable? = null,
    ) : SupabaseAuthGateway {
        var signOutCalled = false

        override suspend fun signInWithPassword(email: String, password: String) {
            signInThrows?.let { throw it }
            userId = "user-1"
        }

        override suspend fun signOut() {
            signOutCalled = true
        }

        override fun currentUserId(): String? = userId

        override suspend fun householdRosterSize(): Int {
            rosterThrows?.let { throw it }
            return rosterSize
        }
    }

    private fun authWith(gateway: SupabaseAuthGateway?) =
        SupabaseAuth(context = fakeContext, gatewayProvider = { gateway })

    @Test
    fun `signIn with no config reports NotConfigured`() = runBlocking {
        val result = authWith(null).signIn("a@b.com", "pw")

        assertEquals(SignInResult.NotConfigured, result)
    }

    @Test
    fun `signIn success reports Success`() = runBlocking {
        val result = authWith(FakeGateway()).signIn("a@b.com", "pw")

        assertEquals(SignInResult.Success, result)
    }

    @Test
    fun `signIn with bad credentials reports InvalidCredentials distinctly from network failure`() = runBlocking {
        val gateway = FakeGateway(signInThrows = AuthRejectedException("invalid_grant"))

        val result = authWith(gateway).signIn("a@b.com", "wrong-password")

        assertTrue(result is SignInResult.InvalidCredentials)
        assertTrue((result as SignInResult.InvalidCredentials).message.contains("rejected"))
    }

    @Test
    fun `signIn with no network reports NetworkUnreachable distinctly from bad credentials`() = runBlocking {
        val gateway = FakeGateway(signInThrows = AuthNetworkException("connection refused"))

        val result = authWith(gateway).signIn("a@b.com", "pw")

        assertTrue(result is SignInResult.NetworkUnreachable)
    }

    @Test
    fun `signIn whose session cannot be persisted reports SucceededButNotPersisted`() = runBlocking {
        val gateway = FakeGateway(signInThrows = SessionPersistFailedException("Keystore broken"))

        val result = authWith(gateway).signIn("a@b.com", "pw")

        assertTrue(result is SignInResult.SucceededButNotPersisted)
    }

    @Test
    fun `isHouseholdMember with no config reports NotConfigured`() = runBlocking {
        assertEquals(MembershipResult.NotConfigured, authWith(null).isHouseholdMember())
    }

    @Test
    fun `isHouseholdMember with nobody signed in reports NotSignedIn`() = runBlocking {
        val gateway = FakeGateway(userId = null)

        assertEquals(MembershipResult.NotSignedIn, authWith(gateway).isHouseholdMember())
    }

    @Test
    fun `isHouseholdMember signed in and on the roster reports Member`() = runBlocking {
        val gateway = FakeGateway(userId = "user-1", rosterSize = 2)

        assertEquals(MembershipResult.Member, authWith(gateway).isHouseholdMember())
    }

    @Test
    fun `isHouseholdMember signed in but not on the roster is worded distinctly - the confusing state`() = runBlocking {
        val gateway = FakeGateway(userId = "user-1", rosterSize = 0)

        val result = authWith(gateway).isHouseholdMember()

        assertTrue(result is MembershipResult.NotAMember)
        val message = (result as MembershipResult.NotAMember).message
        assertTrue(message.contains("not on the household roster"))
    }

    @Test
    fun `isHouseholdMember network failure is never reported as NotAMember`() = runBlocking {
        val gateway = FakeGateway(userId = "user-1", rosterThrows = AuthNetworkException("timeout"))

        val result = authWith(gateway).isHouseholdMember()

        assertTrue(result is MembershipResult.NetworkUnreachable)
    }

    @Test
    fun `signOut clears local state even when the remote call is unreachable`() = runBlocking {
        val gateway = object : SupabaseAuthGateway {
            override suspend fun signInWithPassword(email: String, password: String) = Unit
            override suspend fun signOut(): Unit = throw AuthNetworkException("offline")
            override fun currentUserId(): String? = null
            override suspend fun householdRosterSize(): Int = 0
        }

        // Must not throw - signOut is best-effort per its own doc comment.
        authWith(gateway).signOut()
    }

    @Test
    fun `currentUserId with no config is null`() {
        assertNull(authWith(null).currentUserId())
    }
}
