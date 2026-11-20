package com.kevin.legion.ui.settings

import com.kevin.legion.carlink.CarLinkController
import com.kevin.legion.carlink.CarLinkStore
import com.kevin.legion.carlink.FakeKeystoreStandIn
import com.kevin.legion.carlink.HandoverPlan
import com.kevin.legion.carlink.MemoryRecordFile
import com.kevin.legion.carlink.UnitTokenNotYetAvailable
import com.kevin.legion.carlink.protocol.CarLinkVectors
import com.kevin.legion.carlink.protocol.CarLinkVectors.hex
import com.kevin.legion.carlink.protocol.Frames
import com.kevin.legion.carlink.protocol.LinkCrypto
import com.kevin.legion.carlink.protocol.P256
import com.kevin.legion.carlink.protocol.PairingConnection
import com.kevin.legion.carlink.protocol.PairingKeys
import com.kevin.legion.carlink.protocol.PairingWire
import com.kevin.legion.carlink.protocol.Received
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Car link screen's ViewModel over the real controller, with a scripted head unit on a fake connection. */
class CarLinkViewModelTest {
    private val p = CarLinkVectors.pairing
    private val unitPk = p.hex("unit_pk")
    private val secret = p.hex("secret")

    /** The unit's side of 3.4, driven by the test: answers the hello with a valid ack, then waits. */
    private inner class FakeUnitConnection : PairingConnection {
        val inbox = Channel<Received>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        private val reader = Frames.FrameReader()
        lateinit var keys: PairingKeys

        override suspend fun send(frame: ByteArray) {
            for (msg in reader.feed(frame)) {
                sent += msg
                val obj = Json.parseToJsonElement(msg).jsonObject
                if (obj["t"]!!.jsonPrimitive.content == "hello") {
                    val phonePk = LinkCrypto.unb64(obj["pk"]!!.jsonPrimitive.content)!!
                    val unit = P256.fromScalar(p["unit_private_scalar"]!!.jsonPrimitive.content, unitPk)
                    keys = PairingKeys.derive(secret, unitPk, phonePk, P256.agree(unit, P256.parsePublic(phonePk)!!))
                    inbox.send(Received.Message("""{"t":"ack","v":1,"kc":"${LinkCrypto.b64(keys.expectedKc())}"}"""))
                }
            }
        }

        fun sealed(type: String, dir: String, label: String) {
            val ok = """{"ok":true}""".toByteArray()
            val c = LinkCrypto.seal(keys.handKey, LinkCrypto.nonce(dir, 1), LinkCrypto.ascii(label), ok)
            inbox.trySend(Received.Message(PairingWire.sealedMessage(type, c)))
        }

        override suspend fun receive(timeoutMs: Long): Received = inbox.receive()
        override fun close() = Unit
    }

    private val conn = FakeUnitConnection()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val store = CarLinkStore(FakeKeystoreStandIn(), MemoryRecordFile()) {}
    private val controller = CarLinkController(
        store = store,
        transport = { _, _ -> conn },
        handover = HandoverPlan({ null }, UnitTokenNotYetAvailable(), { "Test phone" }, {}),
        newSender = { _, _, _, _ -> null },
        scope = scope,
        io = Dispatchers.Unconfined,
        jitterMs = { 300L },
        sleep = {},
    )
    private val vm = CarLinkViewModel(controller, NoQrScannerChosen(), debugPaste = true, scope = scope)

    private fun state() = vm.state.value

    @Test fun `starts not paired, off by default, with no scanner chosen`() {
        assertEquals(CarLinkPhase.NOT_PAIRED, state().phase)
        assertFalse(state().sendLocation)
        assertFalse(state().carKeySet)
        assertFalse(state().scannerAvailable)
        assertTrue(state().canPair)
    }

    @Test fun `a pasted code that is not a pairing code fails in words`() {
        vm.pasteCode("hello there")
        assertEquals(CarLinkPhase.FAILED, state().phase)
        assertEquals("That is not a head unit pairing code. Nothing was sent.", state().headline)
    }

    @Test fun `the whole pairing walks waiting, digits, waiting, paired`() = runBlocking {
        vm.pasteCode(CarLinkVectors.qrText())
        assertEquals(CarLinkPhase.DIGITS, state().phase)
        assertEquals(conn.keys.check, state().digits)
        assertFalse(state().toString().contains(conn.keys.check))
        conn.sealed("code_ok", "CDOK", "MNAI-PAIR code ok v1")
        assertEquals(CarLinkPhase.WAITING_FOR_UNIT, state().phase)
        conn.sealed("done", "DONE", "MNAI-PAIR done v1")
        conn.inbox.send(Received.Closed)
        assertEquals(CarLinkPhase.PAIRED, state().phase)
        assertTrue(state().canForget)
        assertNotNull(store.load() as? com.kevin.legion.carlink.StoredLink.Paired)
    }

    @Test fun `three wrong digit entries show their own words and keep the phone unpaired`() = runBlocking {
        vm.pasteCode(CarLinkVectors.qrText())
        conn.inbox.send(Received.Message("""{"t":"abort","why":"code_wrong"}"""))
        assertEquals(CarLinkPhase.FAILED, state().phase)
        assertEquals("The digits were typed wrong three times. Show a new code and scan again.", state().headline)
        assertEquals("This phone is not paired with a head unit.", state().detail)
    }

    @Test fun `cancel while the digits show sends abort cancelled`() = runBlocking {
        vm.pasteCode(CarLinkVectors.qrText())
        vm.cancel()
        assertEquals("""{"t":"abort","why":"cancelled"}""", conn.sent.last())
        assertEquals(CarLinkPhase.FAILED, state().phase)
    }

    @Test fun `forget clears the pairing and says the unit was not in range`() = runBlocking {
        vm.pasteCode(CarLinkVectors.qrText())
        conn.sealed("code_ok", "CDOK", "MNAI-PAIR code ok v1")
        conn.sealed("done", "DONE", "MNAI-PAIR done v1")
        conn.inbox.send(Received.Closed)
        vm.forget()
        assertEquals(CarLinkPhase.NOT_PAIRED, state().phase)
        assertTrue(state().note!!.startsWith("The car has not heard yet"))
        assertEquals(com.kevin.legion.carlink.StoredLink.NotPaired, store.load())
    }

    @Test fun `the location switch is saved`() {
        vm.setSendLocation(true)
        assertTrue(state().sendLocation)
        assertTrue(store.sendLocation())
    }

    @Test fun `scan with no scanner chosen returns to the resting state`() {
        vm.scan()
        assertEquals(CarLinkPhase.NOT_PAIRED, state().phase)
    }

    @Test fun `while the scanner is open the screen says scanning, and its result pairs`() {
        var pending: ((String?) -> Unit)? = null
        val scanner = object : QrScanner {
            override val available = true
            override fun scan(onResult: (String?) -> Unit) { pending = onResult }
        }
        val withScanner = CarLinkViewModel(controller, scanner, debugPaste = false, scope = scope)
        withScanner.scan()
        assertEquals(CarLinkPhase.SCANNING, withScanner.state.value.phase)
        assertTrue(withScanner.state.value.canCancel)
        pending!!.invoke(CarLinkVectors.qrText())
        assertEquals(CarLinkPhase.DIGITS, withScanner.state.value.phase)
    }

    @Test fun `a key this phone cannot read shows Pairing broken and offers Forget`() {
        val file = MemoryRecordFile()
        CarLinkStore(FakeKeystoreStandIn(), file) {}.write(ByteArray(32) { 1 }, null)
        val unreadable = FakeKeystoreStandIn().apply { failOpens = 3 }
        val brokenController = CarLinkController(
            CarLinkStore(unreadable, file) {}, { _, _ -> null },
            HandoverPlan({ null }, UnitTokenNotYetAvailable(), { null }, {}),
            { _, _, _, _ -> null }, scope, Dispatchers.Unconfined, { 300L }, {},
        )
        val broken = CarLinkViewModel(brokenController, NoQrScannerChosen(), debugPaste = false, scope = scope)
        assertEquals(CarLinkPhase.BROKEN, broken.state.value.phase)
        assertEquals("Pairing broken: pair again.", broken.state.value.headline)
        assertTrue(broken.state.value.canForget)
    }

    @Test fun `debug paste does nothing in a release build`() {
        val release = CarLinkViewModel(controller, NoQrScannerChosen(), debugPaste = false, scope = scope)
        release.pasteCode(CarLinkVectors.qrText())
        assertEquals(CarLinkPhase.NOT_PAIRED, release.state.value.phase)
    }

    @Test fun `a build with no scanner and no paste field says scanning comes later, with no dead button`() {
        val release = CarLinkViewModel(controller, NoQrScannerChosen(), debugPaste = false, scope = scope)
        assertEquals("Scanning arrives in a later update.", release.state.value.scanNote)
        assertFalse(release.state.value.showScan)
    }
}
