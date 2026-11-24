package com.kevin.midnightai.location

import com.kevin.midnightai.location.PhoneFeed.Outcome
import com.kevin.midnightai.location.PhoneFeed.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * HU-1 T4: the unit's end of the phone location feed against a fake phone that speaks MNAI2
 * exactly as the spec says. Every case the plan names: dropouts, out-of-order fixes, clock skew
 * between phone and unit, a stale burst after reconnect, plus replay, tampering and a wrong key.
 */
class PhoneFeedTest {

    private val linkKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private var unitNanos = 5_000_000_000_000L
    private val rng = Random(42)
    private val feed = PhoneFeed(linkKey, nowNanos = { unitNanos }, random = { rng.nextBytes(it) })

    /** The phone end, written from the spec only (its own clock, its own session, its own counter). */
    private inner class FakePhone(key: ByteArray = linkKey, var clockNanos: Long = 77L) {
        private val p2u = PhoneLinkWire.phoneToUnitKey(key)
        private val u2p = PhoneLinkWire.unitToPhoneKey(key)
        val session = ByteArray(16).also { rng.nextBytes(it) }
        var counter = 0L
        var challenge: ByteArray? = null

        fun hear(hello: ByteArray) {
            challenge = PhoneLinkWire.decodeHello(u2p, hello, hello.size)!!.challenge
        }

        fun fix(lat: Double, ageMs: Long = 200L, counterOverride: Long? = null): ByteArray {
            val c = counterOverride ?: ++counter
            return PhoneLinkWire.encodeFix(
                p2u, session, c, challenge!!,
                PhoneFix(lat, 0.25, 5f, 90f, 20f, 15.0, ageMs, 2f, 0.5f),
            )
        }
    }

    private fun fixOf(o: Outcome): Outcome.Fix = o as? Outcome.Fix ?: throw AssertionError("not a fix: $o")

    @Test fun aGoodFixIsAcceptedAndStampedOnTheUnitClockMinusItsAge() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val out = fixOf(feed.receive(phone.fix(0.76, ageMs = 400), 105))
        assertEquals(0.76, out.fix.lat, 0.0)
        assertEquals(unitNanos - 400_000_000L, out.fixNanos)
        assertEquals(2f, out.fix.bearingAccuracyDeg)
    }

    @Test fun clockSkewBetweenPhoneAndUnitDoesNotMatter() {
        // The phone's monotonic clock is from another boot entirely; only the age travels.
        val phone = FakePhone(clockNanos = Long.MAX_VALUE / 2)
        phone.hear(feed.hello(1000))
        val out = fixOf(feed.receive(phone.fix(0.0, ageMs = 0).let { it }, 105))
        assertEquals(unitNanos, out.fixNanos)
    }

    @Test fun aNegativeAgeIsClampedSoNoFixIsStampedInTheFuture() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        // Encoders clamp, but a buggy phone could still send a huge u32 that reads negative.
        val out = fixOf(feed.receive(phone.fix(0.0, ageMs = -50), 105))
        assertTrue(out.fixNanos <= unitNanos)
    }

    @Test fun outOfOrderAndReplayedDatagramsAreDropped() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val first = phone.fix(0.0)
        val second = phone.fix(0.1)
        unitNanos += 1_000_000_000L
        fixOf(feed.receive(second, second.size))
        assertEquals(Outcome.Rejected(Refusal.REPLAY_OR_OUT_OF_ORDER), feed.receive(first, first.size))
        assertEquals(Outcome.Rejected(Refusal.REPLAY_OR_OUT_OF_ORDER), feed.receive(second, second.size))
    }

    @Test fun aDropoutThenFreshFixesResumeNormally() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        fixOf(feed.receive(phone.fix(0.0), 105))
        unitNanos += 45_000_000_000L // 45 s of nothing (hotspot gone)
        phone.hear(feed.hello(1000))
        phone.hear(feed.hello(1000))
        val out = fixOf(feed.receive(phone.fix(0.5), 105))
        assertEquals(0.5, out.fix.lat, 0.0)
    }

    @Test fun aStaleBurstAfterReconnectNeverMovesThePositionBackwards() {
        val phone = FakePhone()
        unitNanos += 10_000_000_000L
        phone.hear(feed.hello(1000))
        fixOf(feed.receive(phone.fix(0.9, ageMs = 100), 105))
        // The phone flushes what it queued while the link was down: older fixes, newer counters.
        assertEquals(Outcome.Rejected(Refusal.OLDER_THAN_SHOWN), feed.receive(phone.fix(0.1, ageMs = 4_000), 105))
        assertEquals(Outcome.Rejected(Refusal.STALE), feed.receive(phone.fix(0.2, ageMs = 30_000), 105))
        unitNanos += 1_000_000_000L
        phone.hear(feed.hello(1000))
        fixOf(feed.receive(phone.fix(0.9, ageMs = 100), 105))
    }

    @Test fun aRecordingFromAnEarlierDriveIsRefusedByTheChallenge() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val recorded = phone.fix(0.0)
        // More than 10 s later the recorded challenge is no longer live.
        unitNanos += PhoneFeed.MAX_AGE_MS * 1_000_000L + 1
        feed.hello(1000)
        assertEquals(Outcome.Rejected(Refusal.UNKNOWN_CHALLENGE), feed.receive(recorded, recorded.size))
    }

    @Test fun aRestartedPhoneWithANewSessionIsAcceptedFromCounterOne() {
        val before = FakePhone()
        before.hear(feed.hello(1000))
        repeat(5) { before.hear(feed.hello(1000)); fixOf(feed.receive(before.fix(0.1 * it), 105)); unitNanos += 1_000_000_000L }
        val after = FakePhone()
        after.hear(feed.hello(1000))
        assertNotNull(fixOf(feed.receive(after.fix(0.31), 105)))
    }

    @Test fun tamperingAWrongKeyAndStrangersAreDropped() {
        val phone = FakePhone()
        val hello = feed.hello(1000)
        phone.hear(hello)
        val good = phone.fix(0.0)
        val flipped = good.copyOf().also { it[60] = (it[60].toInt() xor 1).toByte() }
        assertEquals(Outcome.Rejected(Refusal.AUTH_FAILED), feed.receive(flipped, flipped.size))
        val headerFlipped = good.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertEquals(Outcome.Rejected(Refusal.AUTH_FAILED), feed.receive(headerFlipped, headerFlipped.size))

        val stranger = FakePhone(key = ByteArray(32) { 9 })
        stranger.challenge = phone.challenge
        val forged = stranger.fix(0.0)
        assertEquals(Outcome.Rejected(Refusal.AUTH_FAILED), feed.receive(forged, forged.size))

        val legacy = "MNAI1|F|0.5|0.5|||||".toByteArray()
        assertEquals(Outcome.Rejected(Refusal.NOT_MNAI2), feed.receive(legacy, legacy.size))
    }

    @Test fun theZoneTravelsAuthenticated() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val z = PhoneLinkWire.encodeZone(
            PhoneLinkWire.phoneToUnitKey(linkKey), phone.session, ++phone.counter, phone.challenge!!, "Etc/UTC",
        )
        assertEquals(Outcome.Zone("Etc/UTC"), feed.receive(z, z.size))
    }

    // --- Security review 2026-11-20 -----------------------------------------------------------

    @Test fun m2_aFixHeldBackInTransitIsAgedByTheChallengeItEchoes() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val sentAt = unitNanos
        unitNanos += 8_000_000_000L // an attacker held the datagram for 8 s
        val out = fixOf(feed.receive(phone.fix(0.5, ageMs = 100), 105))
        assertTrue("stamped ${(unitNanos - out.fixNanos) / 1_000_000} ms old", out.fixNanos <= sentAt)
    }

    @Test fun m3_anOldSessionCannotInterleaveWithANewOne() {
        val a = FakePhone()
        a.hear(feed.hello(1000))
        val recordedA = a.fix(0.1)
        fixOf(feed.receive(a.fix(0.2), 105))
        unitNanos += 1_000_000_000L
        val b = FakePhone()
        b.hear(feed.hello(1000))
        fixOf(feed.receive(b.fix(0.3), 105))
        unitNanos += 1_000_000_000L
        // A recording from session A, echoing A's still-live challenge, must not come back.
        assertTrue(feed.receive(recordedA, recordedA.size) is Outcome.Rejected)
    }

    @Test fun l7_absurdAuthenticatedValuesAreTreatedAsAbsent() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val bytes = PhoneLinkWire.encodeFix(
            PhoneLinkWire.phoneToUnitKey(linkKey), phone.session, ++phone.counter, phone.challenge!!,
            PhoneFix(0.5, 0.5, 1e9f, 90f, 1e9f, 1e9, 100),
        )
        val out = fixOf(feed.receive(bytes, bytes.size))
        assertEquals(null, out.fix.speedMps)
        assertEquals(null, out.fix.accuracyM)
        assertEquals(null, out.fix.altitudeM)
    }

    @Test fun aRevokeIsAcceptedOnlyWithALiveChallenge() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val r = PhoneLinkWire.encodeRevoke(PhoneLinkWire.phoneToUnitKey(linkKey), phone.session, ++phone.counter, phone.challenge!!)
        assertEquals(Outcome.Revoke, feed.receive(r, r.size))
        assertTrue(feed.receive(r, r.size) is Outcome.Rejected)
    }

    @Test fun m4_anAgeAbove2To31IsStaleNotNegativeAndFresh() {
        // Written byte by byte here (the Kotlin encoder cannot produce it); CarLinkVectorsTest has
        // the same case from the independent implementation.
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        val key = PhoneLinkWire.sessionKey(PhoneLinkWire.phoneToUnitKey(linkKey), phone.session)
        val counter = ++phone.counter
        val header = java.nio.ByteBuffer.allocate(31).put("MNAI2".toByteArray()).put(1).put('F'.code.toByte())
            .put(phone.session).putLong(counter).array()
        val body = java.nio.ByteBuffer.allocate(58).put(phone.challenge!!).putShort(0).putDouble(0.1).putDouble(0.1)
            .putFloat(0f).putFloat(0f).putFloat(0f).putDouble(0.0).putInt(-1).putFloat(0f).putFloat(0f).array()
        val bytes = header + com.kevin.midnightai.link.LinkCrypto.seal(
            key, com.kevin.midnightai.link.LinkCrypto.nonce("P2U ".toByteArray(), counter), header, body,
        )
        assertEquals(Outcome.Rejected(Refusal.STALE), feed.receive(bytes, bytes.size))
    }

    @Test fun datagramsFitTheBuffer() {
        val phone = FakePhone()
        phone.hear(feed.hello(1000))
        assertEquals(105, phone.fix(0.0).size)
        assertTrue(phone.fix(0.0).size <= PhoneLinkWire.MAX_PACKET_BYTES)
    }
}
