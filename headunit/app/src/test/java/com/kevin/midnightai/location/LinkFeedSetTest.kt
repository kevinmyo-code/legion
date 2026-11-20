package com.kevin.midnightai.location

import com.kevin.midnightai.link.CarLink
import java.util.Random
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COMMITTED + PENDING feeds (spec 3.7), recheck 2026-11-20: N1 (old-key traffic must not drop a
 * pairing while its session is open, nor right after it) and N5 (commit once, not per datagram).
 */
class LinkFeedSetTest {

    private val keyOld = ByteArray(32) { 1 }
    private val keyNew = ByteArray(32) { 2 }
    private var nanos = 1_000_000_000_000L
    private val rng = Random(7)
    private fun feed(key: ByteArray) = PhoneFeed(key, nowNanos = { nanos }, random = { rng.nextBytes(it) })

    private class Phone(val key: ByteArray) {
        val session = ByteArray(16) { (key[0] * 3).toByte() }
        var counter = 0L
        fun fix(hello: ByteArray): ByteArray {
            val heard = PhoneLinkWire.decodeHello(PhoneLinkWire.unitToPhoneKey(key), hello, hello.size)!!
            return PhoneLinkWire.encodeFix(
                PhoneLinkWire.phoneToUnitKey(key), session, ++counter, heard.challenge, PhoneFix(0.5, 0.5, 5f, null, null, null, 50),
            )
        }
    }

    @After fun reset() { CarLink.pairingSessionOpen = false }

    @Test fun n1_oldKeyTrafficWhileThePairingSessionIsOpenNeverDropsPending() {
        val set = LinkFeedSet(feed(keyOld), feed(keyNew))
        CarLink.pairingSessionOpen = true
        val old = Phone(keyOld)
        // Between `done` and `stored` the phone may still send under the old key.
        val r = set.receive(old.fix(set.committed!!.hello(1000)).let { it }, 105)
        assertFalse("dropped PENDING while the session was open", r.dropPending)
    }

    @Test fun n1_afterTheSessionPendingIsDroppedOnlyAfterTheQuietPeriod() {
        var ms = 0L
        val set = LinkFeedSet(feed(keyOld), feed(keyNew), nowMs = { ms }, sessionOpen = { false })
        val old = Phone(keyOld)
        assertFalse(set.receive(old.fix(set.committed!!.hello(1000)), 105).dropPending)
        ms += LinkFeedSet.QUIET_MS - 1
        nanos += 1_000_000_000L
        assertFalse(set.receive(old.fix(set.committed!!.hello(1000)), 105).dropPending)
        ms += 1
        nanos += 1_000_000_000L
        assertTrue(set.receive(old.fix(set.committed!!.hello(1000)), 105).dropPending)
    }

    @Test fun n1_anyNewKeyContactRestartsTheQuietPeriod() {
        var ms = 0L
        val set = LinkFeedSet(feed(keyOld), feed(keyNew), nowMs = { ms }, sessionOpen = { false })
        val old = Phone(keyOld)
        val new = Phone(keyNew)
        set.receive(old.fix(set.committed!!.hello(1000)), 105)
        ms += LinkFeedSet.QUIET_MS - 10
        nanos += 1_000_000_000L
        set.receive(new.fix(set.pending!!.hello(1000)), 105)
        ms += 20
        nanos += 1_000_000_000L
        assertFalse(set.receive(old.fix(set.committed!!.hello(1000)), 105).dropPending)
    }

    @Test fun n5_aFailedCommitIsRetriedLaterNotOnEveryDatagram() {
        var ms = 0L
        val set = LinkFeedSet(feed(keyOld), feed(keyNew), nowMs = { ms }, sessionOpen = { false })
        val new = Phone(keyNew)
        assertTrue(set.receive(new.fix(set.pending!!.hello(1000)), 105).commitPending)
        set.commitFailed()
        nanos += 1_000_000_000L; ms += 1_000
        assertFalse("retried on the next datagram", set.receive(new.fix(set.pending!!.hello(1000)), 105).commitPending)
        nanos += 1_000_000_000L; ms += LinkFeedSet.COMMIT_RETRY_MS
        assertTrue("never retried", set.receive(new.fix(set.pending!!.hello(1000)), 105).commitPending)
    }

    @Test fun n1_theQuietPeriodStartsWhenTheSessionClosesNotDuringIt() {
        var ms = 0L
        var open = true
        val set = LinkFeedSet(feed(keyOld), feed(keyNew), nowMs = { ms }, sessionOpen = { open })
        val old = Phone(keyOld)
        // Old-key traffic for longer than the quiet period while the pairing screen is open.
        repeat(3) {
            assertFalse(set.receive(old.fix(set.committed!!.hello(1000)), 105).dropPending)
            nanos += 1_000_000_000L; ms += LinkFeedSet.QUIET_MS
        }
        open = false
        assertFalse("dropped at once after the session closed", set.receive(old.fix(set.committed!!.hello(1000)), 105).dropPending)
        nanos += 1_000_000_000L; ms += LinkFeedSet.QUIET_MS
        assertTrue(set.receive(old.fix(set.committed!!.hello(1000)), 105).dropPending)
    }

    @Test fun n5_thePendingKeyCommitsOnceNotOnEveryDatagram() {
        val set = LinkFeedSet(feed(keyOld), feed(keyNew))
        val new = Phone(keyNew)
        assertTrue(set.receive(new.fix(set.pending!!.hello(1000)), 105).commitPending)
        nanos += 1_000_000_000L
        assertFalse(set.receive(new.fix(set.pending!!.hello(1000)), 105).commitPending)
    }
}
