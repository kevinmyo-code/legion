package com.kevin.midnightai.link

import android.content.Context
import android.os.Build
import com.kevin.midnightai.location.BeaconClient
import com.kevin.midnightai.location.PhoneFeed
import com.kevin.midnightai.location.PhoneFix
import com.kevin.midnightai.location.PhoneLinkWire
import com.kevin.midnightai.service.DeviceRole
import com.kevin.midnightai.service.DeviceRole.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Review H1/H2 (2026-11-20): one decision for how the receiver listens, and every pairing change
 * reaching the RUNNING receiver at once (a forgotten or replaced key is dead immediately).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class CarLinkFeedTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun theFeedModeTruthTable() {
        val cases = mapOf(
            (Role.OFF to LinkStatus.NOT_PAIRED) to FeedMode.OFF,
            (Role.HEAD_UNIT to LinkStatus.NOT_PAIRED) to FeedMode.MNAI1,
            (Role.GPS_BEACON to LinkStatus.NOT_PAIRED) to FeedMode.OFF,
            (Role.OFF to LinkStatus.PAIRED) to FeedMode.MNAI2,
            (Role.HEAD_UNIT to LinkStatus.PAIRED) to FeedMode.MNAI2,
            (Role.GPS_BEACON to LinkStatus.PAIRED) to FeedMode.OFF,
            (Role.OFF to LinkStatus.PENDING) to FeedMode.MNAI2,
            (Role.HEAD_UNIT to LinkStatus.PENDING) to FeedMode.MNAI2,
            (Role.OFF to LinkStatus.BROKEN) to FeedMode.NOTHING,
            (Role.HEAD_UNIT to LinkStatus.BROKEN) to FeedMode.NOTHING,
            (Role.GPS_BEACON to LinkStatus.BROKEN) to FeedMode.OFF,
        )
        cases.forEach { (input, want) -> assertEquals("$input", want, CarLinkRules.feedMode(input.first, input.second)) }
    }

    @Test fun neverPairedWhenBrokenOrPending() {
        assertEquals("Pairing broken: pair again", CarLinkRules.statusLine(LinkStatus.BROKEN, "Test phone"))
        assertEquals("Paired, waiting for the phone", CarLinkRules.statusLine(LinkStatus.PENDING, "Test phone"))
    }

    // --- The running receiver --------------------------------------------------------------

    private class FakeStore : LinkKeySource {
        var committed: ByteArray? = null
        var pending: ByteArray? = null
        var broken = false
        override fun hasRecord() = committed != null || pending != null || broken
        override fun keys(): LinkKeys = when {
            broken -> LinkKeys(null, null, LinkStatus.BROKEN)
            pending != null -> LinkKeys(committed?.copyOf(), pending?.copyOf(), LinkStatus.PENDING)
            committed != null -> LinkKeys(committed?.copyOf(), null, LinkStatus.PAIRED)
            else -> LinkKeys(null, null, LinkStatus.NOT_PAIRED)
        }
        override fun commitPending(): String? { committed = pending; pending = null; return null }
        override fun dropPending() { pending = null }
        override fun forget() { committed = null; pending = null; broken = false }
        override fun phoneName(): String? = null
    }

    private val store = FakeStore()
    private val keyA = ByteArray(32) { 1 }
    private val keyB = ByteArray(32) { 2 }

    @Before fun install() {
        CarLink.resetForTest()
        CarLink.store = store
        CarLink.addListener { BeaconClient.restart(it) }
        DeviceRole.set(context, Role.OFF)
    }

    @After fun stop() {
        BeaconClient.stop()
        CarLink.resetForTest()
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** A fix sealed under [sealKey], echoing a challenge from [helloFrom] (whose key is [helloKey]). */
    private fun fixUnder(sealKey: ByteArray, lock: Any, helloFrom: PhoneFeed, helloKey: ByteArray): ByteArray {
        val hello = synchronized(lock) { helloFrom.hello(1000) }
        val heard = PhoneLinkWire.decodeHello(PhoneLinkWire.unitToPhoneKey(helloKey), hello, hello.size)!!
        return PhoneLinkWire.encodeFix(
            PhoneLinkWire.phoneToUnitKey(sealKey), ByteArray(16) { 7 }, 1, heard.challenge, PhoneFix(0.5, 0.5, 5f, null, null, null, 100),
        )
    }

    @Test fun h2_forgetKillsTheOldKeyAtOnceAndAnewPairingTakesOver() {
        store.committed = keyA.copyOf()
        BeaconClient.start(context)
        waitFor("MNAI2 under key A") { BeaconClient.modeForTest() == FeedMode.MNAI2 && BeaconClient.feedsForTest() != null }
        val setA = BeaconClient.feedsForTest()!!
        val underA = fixUnder(keyA, setA, setA.committed!!, keyA)
        assertTrue(synchronized(setA) { setA.receive(underA, underA.size) }.outcome is PhoneFeed.Outcome.Fix)

        // Forget: the running receiver must drop the key immediately.
        store.forget()
        CarLink.changed(context)
        waitFor("receiver off") { BeaconClient.modeForTest() == FeedMode.OFF && BeaconClient.feedsForTest() == null }

        // Re-pair with key B: the receiver now holds B only; a fix sealed under A opens nowhere.
        store.committed = keyB.copyOf()
        CarLink.changed(context)
        waitFor("MNAI2 under key B") { BeaconClient.feedsForTest() != null }
        val setB = BeaconClient.feedsForTest()!!
        val stillUnderA = fixUnder(keyA, setB, setB.committed!!, keyB)
        val out = synchronized(setB) { setB.receive(stillUnderA, stillUnderA.size) }.outcome
        assertEquals(PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.AUTH_FAILED), out)
    }

    @Test fun h1_aBrokenPairingListensToNothingNotMnai1EvenInTheHeadUnitRole() {
        DeviceRole.set(context, Role.HEAD_UNIT)
        store.broken = true
        BeaconClient.start(context)
        waitFor("mode NOTHING") { BeaconClient.modeForTest() == FeedMode.NOTHING }
        assertNull(BeaconClient.feedsForTest())
    }

    @Test fun h3_aPendingPairingIsCommittedByTheFirstDatagramUnderItsKey() {
        store.committed = keyA.copyOf()
        store.pending = keyB.copyOf()
        BeaconClient.start(context)
        waitFor("both feeds") { BeaconClient.feedsForTest()?.pending != null }
        val set = BeaconClient.feedsForTest()!!
        assertNotNull(set.committed)
        val underB = fixUnder(keyB, set, set.pending!!, keyB)
        val result = synchronized(set) { set.receive(underB, underB.size) }
        assertTrue(result.commitPending)
        val underA = fixUnder(keyA, set, set.committed!!, keyA)
        assertTrue(synchronized(set) { set.receive(underA, underA.size) }.dropPending)
    }
}
