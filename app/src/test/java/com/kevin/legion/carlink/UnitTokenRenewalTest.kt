package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.Mnai2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

        override suspend fun mint(name: String, vehicleId: String?): UnitToken {
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
}
