package com.kevin.midnightai.location

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Rev 9 at the feed: a handed-over engine sign-in goes through every rule a fix does (it must open,
 * echo a live challenge and move the counter forward), and this unit's answer rides the same HELLO
 * session and counter.
 */
class PhoneFeedCredentialTest {

    private val linkKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private var unitNanos = 5_000_000_000_000L
    private val rng = Random(11)
    private val feed = PhoneFeed(linkKey, nowNanos = { unitNanos }, random = { rng.nextBytes(it) })
    private val p2u = PhoneLinkWire.phoneToUnitKey(linkKey)
    private val u2p = PhoneLinkWire.unitToPhoneKey(linkKey)
    private val session = ByteArray(16).also { rng.nextBytes(it) }
    private val token = "hu1_a-renewed-sign-in"
    private val vehicle = "veh-7"
    private var counter = 0L

    private fun challenge(hello: ByteArray) = PhoneLinkWire.decodeHello(u2p, hello, hello.size)!!.challenge

    private fun credential(challenge: ByteArray, counterOverride: Long? = null, key: ByteArray = p2u) =
        PhoneLinkWire.encodeToken(key, session, counterOverride ?: ++counter, challenge, token, vehicle)

    private fun received(bytes: ByteArray) = feed.receive(bytes, bytes.size)

    @Test fun aHandedOverSignInIsReportedWithItsCar() {
        val c = challenge(feed.hello(1000))
        val out = received(credential(c))
        val credential = out as PhoneFeed.Outcome.Credential
        assertEquals(token, credential.token)
        assertEquals(vehicle, credential.vehicleId)
        assertFalse("never printed", credential.toString().contains(token))
    }

    @Test fun aReplayedOrStaleHandOverIsRefused() {
        val c = challenge(feed.hello(1000))
        val bytes = credential(c)
        assertTrue(received(bytes) is PhoneFeed.Outcome.Credential)
        assertEquals(
            PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.REPLAY_OR_OUT_OF_ORDER),
            received(bytes),
        )
        // A challenge this unit never sent, and one it sent too long ago, are both refused.
        assertEquals(
            PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.UNKNOWN_CHALLENGE),
            received(credential(ByteArray(8) { 0x5A })),
        )
        val old = challenge(feed.hello(1000))
        unitNanos += (PhoneFeed.MAX_AGE_MS + 1) * 1_000_000L
        assertEquals(
            PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.UNKNOWN_CHALLENGE),
            received(credential(old)),
        )
    }

    @Test fun aHandOverUnderAnotherKeyIsRefused() {
        val c = challenge(feed.hello(1000))
        val stranger = PhoneLinkWire.phoneToUnitKey(ByteArray(32) { 0x44 })
        assertEquals(
            PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.AUTH_FAILED),
            received(credential(c, key = stranger)),
        )
    }

    @Test fun theAnswerContinuesTheHelloCounterSoNoNonceIsEverReused() {
        val first = feed.hello(1000)
        val answer = feed.tokenOk(PhoneLinkWire.fingerprint(token))
        val next = feed.hello(1000)
        val counters = listOf(first, answer, next).map { bytes ->
            java.nio.ByteBuffer.wrap(bytes, 23, 8).long
        }
        assertEquals(listOf(1L, 2L, 3L), counters)
        assertArrayEquals(PhoneLinkWire.fingerprint(token), PhoneLinkWire.decodeTokenOk(u2p, answer, answer.size))
        // Same session for all three: one counter ladder, which is what keeps the nonce unique.
        assertArrayEquals(first.copyOfRange(7, 23), answer.copyOfRange(7, 23))
    }

    @Test fun aCredentialUnderThePendingKeyIsReportedAsSuch() {
        val pendingKey = ByteArray(32) { 0x33 }
        val pending = PhoneFeed(pendingKey, nowNanos = { unitNanos }, random = { rng.nextBytes(it) })
        val set = LinkFeedSet(committed = feed, pending = pending, nowMs = { 0 }, sessionOpen = { false })
        val hellos = set.hellos(1000)
        val pendingChallenge = PhoneLinkWire.decodeHello(
            PhoneLinkWire.unitToPhoneKey(pendingKey), hellos.first(), hellos.first().size,
        )!!.challenge
        val bytes = PhoneLinkWire.encodeToken(
            PhoneLinkWire.phoneToUnitKey(pendingKey), session, 1, pendingChallenge, token, vehicle,
        )
        val result = set.receive(bytes, bytes.size)
        assertTrue(result.outcome is PhoneFeed.Outcome.Credential)
        assertTrue("the pairing it came with is not committed yet", result.underPending)
        // The committed feed is the only one that ever answers.
        assertNull(LinkFeedSet(committed = null, pending = pending).tokenOk(ByteArray(8)))
    }
}
