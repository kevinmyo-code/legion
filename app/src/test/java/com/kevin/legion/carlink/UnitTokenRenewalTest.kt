package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.LinkKeyRing
import com.kevin.legion.carlink.protocol.Mnai2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.6: the phone renews the car's own engine sign-in over the live link, and the old credential
 * lives until the unit says it stored the new one.
 */
class UnitTokenRenewalTest {

    private val box = FakeKeystoreStandIn()
    private val file = MemoryRecordFile()
    private val redacted = mutableListOf<String>()
    private val store = CarLinkStore(box, file) { redacted += it }
    private var now = 1_000_000_000L

    /** The engine, as the renewal sees it: one live unit, every call counted. */
    private class FakeEngine(var rows: List<UnitRecord>) : UnitTokenSource, UnitTokenDirectory {
        var lists = 0
        var listFails = false
        val minted = mutableListOf<UnitToken>()
        val revoked = mutableListOf<String>()
        var nextId = 100

        override suspend fun list(): List<UnitRecord>? {
            lists++
            return if (listFails) null else rows
        }

        /** Something that happens while a mint is in flight (a Forget, say). */
        var onMint: (() -> Unit)? = null

        override suspend fun mint(name: String, vehicleId: String?): UnitToken {
            onMint?.invoke()
            val token = UnitToken("id-${nextId++}", "hu1_token-${minted.size}", null)
            minted += token
            rows = rows + UnitRecord(token.id, Long.MAX_VALUE, vehicleId)
            return token
        }

        override suspend fun rotate(id: String): UnitToken? = throw AssertionError("rotate is never used")

        override suspend fun revoke(id: String): Boolean {
            revoked += id
            rows = rows.filterNot { it.id == id }
            return true
        }
    }

    /** The live link: what was handed over, and whether the unit is reachable at all. */
    private class FakeLink(var inRange: Boolean = true) : TokenCarrier {
        var clock = 0L
        val handed = mutableListOf<Pair<String, String?>>()

        override fun nowMs() = clock
        override fun handToken(token: String, vehicleId: String?): Boolean {
            if (!inRange) return false
            handed += token to vehicleId
            return true
        }
    }

    /** Unconfined on both sides: every launched engine call finishes before [step] returns. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun renewal(engine: FakeEngine) = UnitTokenRenewal(
        store = store,
        tokens = engine,
        directory = engine,
        scope = scope,
        io = Dispatchers.Unconfined,
        wallClock = { now },
    )

    private val vehicle = "veh-1"
    private fun dueSoon() = listOf(UnitRecord("id-old", now + 3L * 24 * 60 * 60 * 1000, vehicle))

    @Test fun `a sign-in near its end is replaced and handed over, and the old one stays live`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val link = FakeLink()
        val r = renewal(engine)
        r.session()
        r.step(link) // asks the engine, mints
        r.step(link) // hands the minted token over
        assertEquals(1, engine.minted.size)
        assertEquals(engine.minted.single().token to vehicle, link.handed.single())
        assertTrue("nothing is retired before the unit answers", engine.revoked.isEmpty())
        assertEquals("id-old", store.unitDeviceId())
        assertEquals(engine.minted.single().id, store.pendingUnitToken()?.id)
        assertTrue(redacted.contains(engine.minted.single().token))
    }

    @Test fun `the unit's answer retires the old credential and makes the new one the car's`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val link = FakeLink()
        val r = renewal(engine)
        r.session()
        r.step(link)
        r.step(link)
        val fresh = engine.minted.single()
        r.onTokenStored(Mnai2.fingerprint(fresh.token))
        assertEquals(fresh.id, store.unitDeviceId())
        assertEquals(listOf("id-old"), engine.revoked)
        assertNull(store.pendingUnitToken())
    }

    @Test fun `a lost answer keeps the same token and never mints or retires a second one`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val r = renewal(engine)
        val first = FakeLink()
        r.session()
        r.step(first)
        r.step(first)
        // The socket died with no answer. A new one, and even a new process, hands over the SAME one.
        val second = FakeLink()
        val later = renewal(engine)
        later.session()
        later.step(second)
        assertEquals(1, engine.minted.size)
        assertEquals(engine.minted.single().token, second.handed.single().first)
        assertTrue(engine.revoked.isEmpty())
        assertEquals("id-old", store.unitDeviceId())
    }

    @Test fun `an answer about another token changes nothing`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val link = FakeLink()
        val r = renewal(engine)
        r.session()
        r.step(link)
        r.step(link)
        r.onTokenStored(Mnai2.fingerprint("hu1_some-other-token"))
        assertEquals("id-old", store.unitDeviceId())
        assertTrue(engine.revoked.isEmpty())
        assertNotNull(store.pendingUnitToken())
    }

    @Test fun `a sign-in with weeks left is left alone`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(listOf(UnitRecord("id-old", now + 25L * 24 * 60 * 60 * 1000, vehicle)))
        val r = renewal(engine)
        r.session()
        r.step(FakeLink())
        assertTrue(engine.minted.isEmpty())
        assertNull(store.pendingUnitToken())
    }

    @Test fun `an engine that cannot be reached, or an expiry this build cannot read, mints nothing`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        engine.listFails = true
        val r = renewal(engine)
        r.session()
        r.step(FakeLink())
        engine.listFails = false
        engine.rows = listOf(UnitRecord("id-old", null, vehicle))
        now += UnitTokenRenewal.ASK_EVERY_MS
        r.step(FakeLink())
        assertTrue(engine.minted.isEmpty())
    }

    @Test fun `the engine is asked at most once in six hours`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(listOf(UnitRecord("id-old", now + 25L * 24 * 60 * 60 * 1000, vehicle)))
        val r = renewal(engine)
        r.session()
        repeat(5) { r.step(FakeLink()) }
        assertEquals(1, engine.lists)
        now += UnitTokenRenewal.ASK_EVERY_MS
        r.step(FakeLink())
        assertEquals(2, engine.lists)
    }

    @Test fun `hand-overs are spaced and bounded per socket`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val link = FakeLink()
        val r = renewal(engine)
        r.session()
        r.step(link)
        repeat(UnitTokenRenewal.MAX_SENDS * 3) {
            link.clock += UnitTokenRenewal.SEND_EVERY_MS
            r.step(link)
        }
        assertEquals(UnitTokenRenewal.MAX_SENDS, link.handed.size)
        // Too soon after the last one: nothing goes out.
        val fresh = FakeLink()
        r.session()
        r.step(fresh)
        r.step(fresh)
        assertEquals(1, fresh.handed.size)
    }

    @Test fun `a pending credential the engine no longer lists is dropped so renewal can go on`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val link = FakeLink()
        val r = renewal(engine)
        r.session()
        r.step(link)
        r.step(link)
        val abandoned = engine.minted.single()
        // It expired or was revoked with "forget every unit": nobody can be using it.
        engine.rows = dueSoon()
        now += UnitTokenRenewal.ASK_EVERY_MS
        r.step(link)
        assertNull(store.pendingUnitToken())
        assertTrue(engine.revoked.isEmpty())
        now += UnitTokenRenewal.ASK_EVERY_MS
        r.step(link)
        assertEquals(2, engine.minted.size)
        assertTrue(engine.minted.last().token != abandoned.token)
    }

    @Test fun `a mint the store cannot keep is revoked at once, because nobody holds it`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        box.failSealAfter = 0
        val r = renewal(engine)
        r.session()
        r.step(FakeLink())
        assertEquals(1, engine.minted.size)
        assertEquals(listOf(engine.minted.single().id), engine.revoked)
        assertNull(store.pendingUnitToken())
    }

    @Test fun `a pending credential is in the store where Forget can find and revoke it`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val r = renewal(engine)
        r.session()
        r.step(FakeLink())
        val waiting = store.pendingUnitToken()!!
        assertEquals(engine.minted.single().id, waiting.id)
        assertEquals(engine.minted.single().token, waiting.token)
        assertEquals(vehicle, waiting.vehicleId)
    }

    @Test fun `no renewal at all until a pairing has a unit id`() {
        val engine = FakeEngine(dueSoon())
        val r = renewal(engine)
        r.session()
        r.step(FakeLink())
        assertEquals(0, engine.lists)
        assertTrue(engine.minted.isEmpty())
    }

    @Test fun `an unreachable unit is no reason to retire anything`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val link = FakeLink(inRange = false)
        val r = renewal(engine)
        r.session()
        r.step(link)
        r.step(link)
        assertTrue(link.handed.isEmpty())
        assertEquals("id-old", store.unitDeviceId())
        assertTrue(engine.revoked.isEmpty())
    }

    /**
     * Blocker 2 (RED review, 2026-11-25). Forget clears the store's pending record without going
     * through this object, so a record held only in memory outlived the pairing it belonged to: the
     * next pairing's first in-range pass handed the NEW unit the OLD, revoked token, and the unit's
     * answer then made the dead credential the car's and revoked the fresh one. The just-made
     * pairing was dead and only another re-pair got out of it, which is worse than the base.
     */
    @Test fun `Forget then a re-pair in the same process never hands the new unit the old token`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val r = renewal(engine)
        r.session()
        r.step(FakeLink()) // asks the engine and mints: a pending record now exists
        val stale = engine.minted.single()
        assertEquals(stale.id, store.pendingUnitToken()?.id)

        // Forget: the engine revokes the pending credential and the record goes with the pairing.
        runBlocking { engine.revoke(stale.id) }
        store.writeRevokeOnly(ByteArray(32) { 3 }, now)
        assertNull(store.pendingUnitToken())

        // A re-pair in the same process: a new unit, a new credential, a new socket.
        store.setUnitDeviceId("id-new")
        val next = FakeLink()
        r.session()
        r.step(next)
        assertTrue("the old, revoked token must never reach the new unit", next.handed.isEmpty())

        // An answer about the stale token moves nothing either, so a unit still holding it from
        // before the Forget cannot take the fresh pairing down with it.
        r.onTokenStored(Mnai2.fingerprint(stale.token))
        assertEquals("the new pairing's credential stays the car's", "id-new", store.unitDeviceId())
        assertTrue("the fresh credential must not be revoked", "id-new" !in engine.revoked)
        // `askedAt` is still within its 6 hours, so no renewal is minted for the new pairing in this
        // pass. That cannot hurt it: a pairing's own credential has 30 days and the window to renew
        // one opens at 10 days left, so a 6-hour wait is never the thing in the way.
        assertEquals(1, engine.minted.size)
    }

    /**
     * Blocker, RED review round 3. "Pair again" is a first-class button on the car-link screen and
     * does NOT go through Forget, so the pending record used to survive it: the next pass handed
     * the NEW unit the OLD pairing's token, which rebound the car to the old pairing's car and
     * revoked the credential the new pairing had just minted. The record now goes with the link key
     * it was minted under, which is the one write every pairing makes.
     */
    @Test fun `Pair again ends the old pairing's renewal and never hands its token on`() {
        val oldKey = ByteArray(32) { 7 }
        assertTrue(store.write(oldKey, null))
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val r = renewal(engine)
        r.session()
        r.step(FakeLink())
        val stale = engine.minted.single()
        assertEquals(stale.id, store.pendingUnitToken()?.id)

        // "Pair again": the pairing's own key write, through the ring the controller pairs with.
        assertTrue(LinkKeyRing(oldKey, null, store).commit(ByteArray(32) { 9 }))
        store.setUnitDeviceId("id-new")
        assertNull("the record goes with the pairing that minted it", store.pendingUnitToken())

        val next = FakeLink()
        r.session()
        r.step(next)
        assertTrue("the old pairing's token must never reach the new unit", next.handed.isEmpty())
        assertTrue("and that credential is revoked, not left live", stale.id in engine.revoked)

        // A unit still holding it from before cannot take the fresh pairing down with it either.
        r.onTokenStored(Mnai2.fingerprint(stale.token))
        assertEquals("id-new", store.unitDeviceId())
        assertTrue("the fresh credential must not be revoked", "id-new" !in engine.revoked)
    }

    /** The same, on a socket that outlived the re-pair, so nothing re-read the store for it. */
    @Test fun `Pair again hands nothing on even while the socket that mints it is still up`() {
        val oldKey = ByteArray(32) { 7 }
        assertTrue(store.write(oldKey, null))
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val link = FakeLink()
        val r = renewal(engine)
        r.session()
        r.step(link)
        val stale = engine.minted.single()
        val handedBefore = link.handed.size

        assertTrue(LinkKeyRing(oldKey, null, store).commit(ByteArray(32) { 9 }))
        store.setUnitDeviceId("id-new")

        link.clock += UnitTokenRenewal.SEND_EVERY_MS
        r.step(link)
        assertEquals("nothing more goes over this socket", handedBefore, link.handed.size)
        assertTrue(stale.id in engine.revoked)
    }

    /**
     * A pending token is handed over only while the engine has lately listed it. A record outlives
     * the process on purpose, and in the meantime it can expire or be revoked by hand from the
     * member's device list; a unit that stored a dead one 401s on its next upload and the screen
     * asks for the re-pair this exists to remove.
     */
    @Test fun `an expired pending token is dropped instead of handed to the unit`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val first = renewal(engine)
        first.session()
        first.step(FakeLink())
        assertNotNull(store.pendingUnitToken())
        // It ran out its own 30 days while the phone and the car were apart: the engine lists only
        // live credentials, so it is gone from the list.
        engine.rows = dueSoon()

        val link = FakeLink()
        val later = renewal(engine)
        later.session()
        later.step(link)
        assertTrue("a dead token must never reach the unit", link.handed.isEmpty())
        assertNull(store.pendingUnitToken())
    }

    @Test fun `nothing is handed over until the engine has answered about it`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        val first = renewal(engine)
        first.session()
        first.step(FakeLink())
        val waiting = engine.minted.single()

        // A new process, and no engine to ask: the record is good, but nothing says so yet.
        engine.listFails = true
        val link = FakeLink()
        val later = renewal(engine)
        later.session()
        later.step(link)
        assertTrue(link.handed.isEmpty())

        // The engine comes back minutes later, not hours, and the hand-over goes.
        engine.listFails = false
        now += UnitTokenRenewal.ASK_RETRY_MS
        later.step(link)
        assertEquals(waiting.token, link.handed.single().first)
    }

    @Test fun `a mint that lands after Forget revokes itself instead of arming the next pairing`() {
        store.setUnitDeviceId("id-old")
        val engine = FakeEngine(dueSoon())
        // The Forget lands while the mint is in flight, which is the one ordering the renewal cannot
        // see: without the check the credential would be written into a store the pairing has left.
        engine.onMint = { store.writeRevokeOnly(ByteArray(32) { 4 }, now) }
        val r = renewal(engine)
        r.session()
        r.step(FakeLink())
        assertEquals(1, engine.minted.size)
        assertNull("nothing is left armed for the next pairing", store.pendingUnitToken())
        assertEquals(
            "a credential nobody can reach goes now, not in 30 days",
            engine.minted.single().id,
            engine.revoked.singleOrNull(),
        )
    }
}
