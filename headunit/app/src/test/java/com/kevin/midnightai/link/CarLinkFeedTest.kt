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
        /** Reads of PENDING that fail before it decrypts (review P2). */
        var pendingFailures = 0
        var reads = 0
        override fun hasRecord() = committed != null || pending != null || broken
        override fun keys(): LinkKeys = when {
            broken -> LinkKeys(null, null, LinkStatus.BROKEN)
            pending != null && pendingFailures > 0 && ++reads <= pendingFailures ->
                LinkKeys(committed?.copyOf(), null, LinkStatus.PENDING, pendingUnreadable = true)
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
        BeaconClient.rereadBaseMs = 5_000L
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

    @Test fun n3_aSlowStartFinishingAfterForgetNeverReinstallsTheOldKey() {
        store.committed = keyA.copyOf()
        val release = java.util.concurrent.CountDownLatch(1)
        var hooked = false
        BeaconClient.beforeInstallForTest = { if (!hooked) { hooked = true; release.await(3, java.util.concurrent.TimeUnit.SECONDS) } }
        try {
            BeaconClient.start(context)
            Thread.sleep(200) // the first start is now stuck inside its startup step, holding key A
            store.forget()
            CarLink.changed(context)
            Thread.sleep(300)
            release.countDown() // the slow first run finishes after the Forget
            Thread.sleep(500)
            assertNull("old key reinstalled after Forget", BeaconClient.feedsForTest())
        } finally {
            BeaconClient.beforeInstallForTest = null
        }
    }

    @Test fun anAcceptedRevokeForgetsThePairingAndStopsTheReceiver() {
        store.committed = keyA.copyOf()
        BeaconClient.start(context)
        waitFor("MNAI2") { BeaconClient.feedsForTest() != null }
        val set = BeaconClient.feedsForTest()!!
        val hello = synchronized(set) { set.committed!!.hello(1000) }
        val heard = PhoneLinkWire.decodeHello(PhoneLinkWire.unitToPhoneKey(keyA), hello, hello.size)!!
        val revoke = PhoneLinkWire.encodeRevoke(PhoneLinkWire.phoneToUnitKey(keyA), ByteArray(16) { 9 }, 1, heard.challenge)
        BeaconClient.handleForTest(context, revoke)
        waitFor("forgotten and off") { store.committed == null && BeaconClient.feedsForTest() == null }
    }

    @Test fun h1_aBrokenPairingListensToNothingNotMnai1EvenInTheHeadUnitRole() {
        DeviceRole.set(context, Role.HEAD_UNIT)
        store.broken = true
        BeaconClient.start(context)
        waitFor("mode NOTHING") { BeaconClient.modeForTest() == FeedMode.NOTHING }
        assertNull(BeaconClient.feedsForTest())
    }

    /**
     * Spec 3.7 rev 4 (recheck c53f1fc3, call d), phone side modelled by a fake: the phone keeps its
     * old key in reserve until its first authenticated HELLO under the new one. The unit end needs
     * no change for this, and this test proves why: while PENDING exists the unit still sends HELLOs
     * under the old key and accepts fixes under it, and after a cancel after `done` (PENDING dropped)
     * it speaks ONLY the old key, which a phone that reverted on the abort answers at once.
     */
    private class ReservePhone(var current: ByteArray, var reserve: ByteArray?) {
        /** First authenticated HELLO under the new key: the reserve is no longer needed. */
        fun heard(hello: ByteArray): Boolean {
            if (PhoneLinkWire.decodeHello(PhoneLinkWire.unitToPhoneKey(current), hello, hello.size) != null) {
                reserve?.fill(0); reserve = null
                return true
            }
            return false
        }
        /** An `abort` after `done` opened (or 60 s of reserve-key-only HELLOs): go back to the old key. */
        fun revert() { reserve?.let { current = it; reserve = null } }
    }

    @Test fun rev4_aCancelAfterDoneLeavesThePhoneAndUnitOnTheOldKey() {
        store.committed = keyA.copyOf()
        store.pending = keyB.copyOf() // written at `done`
        val token = Any()
        CarLink.pairingSessionStarted(token)
        BeaconClient.start(context)
        waitFor("both feeds") { BeaconClient.feedsForTest()?.pending != null }
        val both = BeaconClient.feedsForTest()!!
        val phone = ReservePhone(current = keyB.copyOf(), reserve = keyA.copyOf()) // opened `done`
        // During the window the unit still answers the old key: HELLOs go out under A and B, and an A fix is accepted.
        val hellos = synchronized(both) { both.hellos(1000) }
        assertEquals(2, hellos.size)
        assertTrue(hellos.any { PhoneLinkWire.decodeHello(PhoneLinkWire.unitToPhoneKey(keyA), it, it.size) != null })
        val underA = fixUnder(keyA, both, both.committed!!, keyA)
        val r = synchronized(both) { both.receive(underA, underA.size) }
        assertTrue(r.outcome is PhoneFeed.Outcome.Fix)
        assertTrue("old-key fix dropped PENDING inside the session", !r.dropPending)

        // The driver cancels after `done`: the unit drops PENDING (spec 3.6) and sends `abort`.
        store.dropPending()
        CarLink.pairingSessionEnded(token)
        CarLink.changed(context)
        waitFor("old key only") { BeaconClient.feedsForTest()?.let { it.pending == null && it.committed != null } == true }
        val after = BeaconClient.feedsForTest()!!
        val only = synchronized(after) { after.hellos(1000) }.single()
        assertTrue("the phone must not hear the dropped key", !phone.heard(only))
        phone.revert() // on the abort
        assertTrue("reverted phone does not hear the unit", phone.heard(only))
        val fix = fixUnder(phone.current, after, after.committed!!, keyA)
        assertTrue(synchronized(after) { after.receive(fix, fix.size) }.outcome is PhoneFeed.Outcome.Fix)
        assertEquals(LinkStatus.PAIRED, CarLink.status.value)
    }

    /** Review P2: a PENDING that did not decrypt is read again (bounded backoff), not left until a restart. */
    @Test fun p2_anUnreadablePendingIsReadAgainUntilItOpens() {
        BeaconClient.rereadBaseMs = 50
        store.committed = keyA.copyOf()
        store.pending = keyB.copyOf()
        store.pendingFailures = 2
        BeaconClient.start(context)
        waitFor("the pending feed after re-reads") { BeaconClient.feedsForTest()?.pending != null }
        assertTrue(store.reads >= 3)
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
        // Old-key traffic alone does not drop PENDING at once (review N1: only after a quiet period).
        val underA = fixUnder(keyA, set, set.committed!!, keyA)
        assertTrue(!synchronized(set) { set.receive(underA, underA.size) }.dropPending)
    }
}
