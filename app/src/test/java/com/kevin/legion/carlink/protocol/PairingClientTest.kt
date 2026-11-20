package com.kevin.legion.carlink.protocol

import com.kevin.legion.carlink.protocol.CarLinkVectors.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's pairing state machine against every failure the document names (3.4, 3.6, 3.7). */
class PairingClientTest {
    private val p = CarLinkVectors.pairing
    private val oldKey = ByteArray(32) { 0x55 }
    private val ack = """{"t":"ack","v":1,"kc":"${LinkCrypto.b64(p.hex("ack_kc"))}"}"""
    private val codeOk = CarLinkVectors.sealedJson("code_ok", "code_ok")
    private val done = CarLinkVectors.sealedJson("done", "done")

    private fun client(store: MemoryKeys = MemoryKeys(current = oldKey.copyOf())): Pair<PairingClient, LinkKeyRing> {
        val ring = store.ring()
        return PairingClient(QrPayload.parse(CarLinkVectors.qrText())!!, CarLinkVectors.phoneKey(), ring) {
            CarLinkVectors.vectorHandover()
        } to ring
    }

    private fun sent(effects: List<PairingEffect>) = effects.filterIsInstance<PairingEffect.Send>().map { it.json }

    private fun failure(c: PairingClient) = (c.stage as PairingStage.Failed).why

    @Test fun `a tampered kc aborts with bad_message and never shows digits`() {
        val (c, _) = client()
        val kc = p.hex("ack_kc").also { it[0] = (it[0].toInt() xor 1).toByte() }
        val out = c.onMessage("""{"t":"ack","v":1,"kc":"${LinkCrypto.b64(kc)}"}""")
        assertEquals(listOf("""{"t":"abort","why":"bad_message"}"""), sent(out))
        assertEquals(PairingFailure.UNIT_NOT_PROVEN, failure(c))
    }

    @Test fun `an ack with another version aborts with bad_version`() {
        val (c, _) = client()
        val out = c.onMessage("""{"t":"ack","v":2,"kc":"${LinkCrypto.b64(p.hex("ack_kc"))}"}""")
        assertEquals(listOf("""{"t":"abort","why":"bad_version"}"""), sent(out))
    }

    @Test fun `a second ack is an abort`() {
        val (c, _) = client()
        c.onMessage(ack)
        assertEquals(listOf("""{"t":"abort","why":"bad_message"}"""), sent(c.onMessage(ack)))
    }

    @Test fun `a tampered code_ok aborts and sends no hand-over`() {
        val (c, ring) = client()
        c.onMessage(ack)
        val bad = p.hex("code_ok").also { it[3] = (it[3].toInt() xor 0x40).toByte() }
        val out = c.onMessage(PairingWire.sealedMessage("code_ok", bad))
        assertEquals(listOf("""{"t":"abort","why":"bad_message"}"""), sent(out))
        assertArrayEquals(oldKey, ring.sendKey())
    }

    @Test fun `three wrong digit entries on the unit end the session and the old pairing stays`() {
        val (c, ring) = client()
        c.onMessage(ack)
        val out = c.onMessage("""{"t":"abort","why":"code_wrong"}""")
        assertTrue(sent(out).isEmpty())
        assertEquals(PairingFailure.UNIT_CODE_WRONG, failure(c))
        assertArrayEquals(oldKey, ring.current)
        assertNull(ring.reserve)
    }

    @Test fun `every unit why maps to its own failure`() {
        val map = mapOf(
            "bad_version" to PairingFailure.UNIT_BAD_VERSION, "bad_message" to PairingFailure.UNIT_BAD_MESSAGE,
            "timeout" to PairingFailure.UNIT_TIMEOUT, "code_wrong" to PairingFailure.UNIT_CODE_WRONG,
            "cancelled" to PairingFailure.UNIT_CANCELLED, "store_failed" to PairingFailure.UNIT_STORE_FAILED,
            "something_new" to PairingFailure.UNIT_STOPPED,
        )
        for ((why, expected) in map) {
            val (c, _) = client()
            c.onMessage("""{"t":"abort","why":"$why"}""")
            assertEquals(why, expected, failure(c))
        }
    }

    @Test fun `nothing goes out under the old key from handover until done opens`() {
        val (c, ring) = client()
        c.onMessage(ack)
        c.onMessage(codeOk)
        assertEquals(PairingStage.AwaitingDone, c.stage)
        assertNull(ring.sendKey())
        c.onMessage(done)
        assertArrayEquals(p.hex("link_key"), ring.sendKey())
    }

    @Test fun `a failure before done gives the old key its voice back`() {
        val (c, ring) = client()
        c.onMessage(ack)
        c.onMessage(codeOk)
        c.onDisconnected()
        assertEquals(PairingFailure.CONNECTION_LOST, failure(c))
        assertArrayEquals(oldKey, ring.sendKey())
    }

    @Test fun `done keeps the old key in reserve, persisted together before stored`() {
        val store = MemoryKeys(current = oldKey.copyOf())
        val (c, ring) = client(store)
        c.onMessage(ack)
        c.onMessage(codeOk)
        val writesBefore = store.writes
        c.onMessage(done)
        assertEquals(writesBefore + 1, store.writes)
        assertArrayEquals(p.hex("link_key"), store.current)
        assertArrayEquals(oldKey, store.reserve)
        assertArrayEquals(oldKey, ring.reserve)
    }

    @Test fun `a phone that cannot save the new key aborts with store_failed and stays on the old one`() {
        val store = MemoryKeys(current = oldKey.copyOf())
        val (c, ring) = client(store)
        c.onMessage(ack)
        c.onMessage(codeOk)
        store.fail = true
        assertEquals(listOf("""{"t":"abort","why":"store_failed"}"""), sent(c.onMessage(done)))
        assertEquals(PairingFailure.PHONE_STORE_FAILED, failure(c))
        assertArrayEquals(oldKey, ring.sendKey())
    }

    @Test fun `an abort after done reverts to the earlier key and says the pairing did not complete`() {
        val store = MemoryKeys(current = oldKey.copyOf())
        val (c, ring) = client(store)
        c.onMessage(ack); c.onMessage(codeOk); c.onMessage(done)
        c.onMessage("""{"t":"abort","why":"cancelled"}""")
        val failed = c.stage as PairingStage.Failed
        assertEquals(PairingFailure.NOT_COMPLETED, failed.why)
        assertTrue(failed.hadEarlierPairing)
        assertArrayEquals(oldKey, ring.current)
        assertArrayEquals(oldKey, store.current)
        assertNull(store.reserve)
    }

    @Test fun `an abort after done in a first pairing ends unpaired`() {
        val store = MemoryKeys()
        val (c, ring) = client(store)
        c.onMessage(ack); c.onMessage(codeOk); c.onMessage(done)
        c.onMessage("""{"t":"abort","why":"cancelled"}""")
        assertFalse((c.stage as PairingStage.Failed).hadEarlierPairing)
        assertNull(ring.current)
        assertNull(store.current)
    }

    @Test fun `a cancel after stored only closes, so the unit's commit is never stranded`() {
        val (c, ring) = client()
        c.onMessage(ack); c.onMessage(codeOk); c.onMessage(done)
        assertTrue(sent(c.cancel()).isEmpty())
        assertEquals(PairingStage.Paired, c.stage)
        assertArrayEquals(p.hex("link_key"), ring.current)
    }

    @Test fun `a cancel before done sends abort cancelled`() {
        val (c, _) = client()
        c.onMessage(ack)
        assertEquals(listOf("""{"t":"abort","why":"cancelled"}"""), sent(c.cancel()))
        assertEquals(PairingFailure.CANCELLED_HERE, failure(c))
    }

    @Test fun `a tampered done aborts and keeps the old key`() {
        val (c, ring) = client()
        c.onMessage(ack); c.onMessage(codeOk)
        val bad = p.hex("done").also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val out = c.onMessage(PairingWire.sealedMessage("done", bad))
        assertEquals(listOf("""{"t":"abort","why":"bad_message"}"""), sent(out))
        assertArrayEquals(oldKey, ring.sendKey())
    }

    @Test fun `junk before ack is bad_message`() {
        val (c, _) = client()
        assertEquals(listOf("""{"t":"abort","why":"bad_message"}"""), sent(c.onMessage("not json")))
    }
}
