package com.kevin.legion.ui.settings

import com.kevin.legion.carlink.CarLinkController
import com.kevin.legion.carlink.CarLinkStore
import com.kevin.legion.carlink.FakeKeystoreStandIn
import com.kevin.legion.carlink.HandoverGap
import com.kevin.legion.carlink.HandoverPlan
import com.kevin.legion.carlink.HandoverSources
import com.kevin.legion.carlink.MemoryRecordFile
import com.kevin.legion.carlink.NoUnitTokens
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
import org.junit.Assert.assertNull
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
        handover = HandoverPlan(HandoverSources({ null }, { null }, { "Test phone" }), NoUnitTokens()) {},
        newSender = { _, _, _, _ -> null },
        scope = scope,
        io = Dispatchers.Unconfined,
        jitterMs = { 300L },
        sleep = {},
    )
    private val vm = CarLinkViewModel(controller, scannerAvailable = true, debugPaste = true, scope = scope)

    private fun state() = vm.state.value

    @Test fun `starts not paired, off by default, with Scan offered`() {
        assertEquals(CarLinkPhase.NOT_PAIRED, state().phase)
        assertFalse(state().sendLocation)
        assertTrue(state().showScan)
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

    @Test fun `while the camera is open the screen says scanning, and a pairing code it reads starts pairing`() {
        vm.scan()
        assertEquals(CarLinkPhase.SCANNING, state().phase)
        assertTrue(state().canCancel)
        vm.onScanned(CarLinkVectors.qrText())
        assertEquals(CarLinkPhase.DIGITS, state().phase)
    }

    @Test fun `a code that is not a pairing code is refused in words and nothing starts`() {
        vm.scan()
        vm.onScanned("https://example.invalid/not-a-pairing-code")
        assertEquals(CarLinkPhase.SCANNING, state().phase)
        assertEquals(CarLinkWords.NOT_A_PAIRING_CODE, state().scanReject)
        assertTrue(conn.sent.isEmpty())
    }

    @Test fun `a refused camera says what to do, and Scan stays available`() {
        vm.onCameraDenied()
        assertEquals(CarLinkPhase.NOT_PAIRED, state().phase)
        assertTrue(state().cameraNote!!.contains("Settings"))
        assertTrue(state().canPair)
    }

    @Test fun `the screen says what pairing hands over, and what this phone lacks`() {
        assertEquals(
            "This phone has no Gemini key and no map token, so the car will get neither.",
            state().handsOver,
        )
    }

    /** 2026-11-23: the Spotify setup is one extra sentence, not a branch per combination. */
    @Test fun `the Spotify setup is named only when this phone has one`() {
        val none = setOf(HandoverGap.NO_GEMINI_KEY, HandoverGap.NO_MAP_TOKEN, HandoverGap.NO_SPOTIFY_CLIENT_ID)
        assertFalse(CarLinkWords.handsOver(none).contains("Spotify"))
        val withSpotify = CarLinkWords.handsOver(setOf(HandoverGap.NO_GEMINI_KEY, HandoverGap.NO_MAP_TOKEN))
        assertTrue(withSpotify, withSpotify.contains("Spotify setup goes too"))
        assertTrue(withSpotify, withSpotify.startsWith("This phone has no Gemini key and no map token"))
        val everything = CarLinkWords.handsOver(emptySet())
        assertTrue(everything, everything.contains("Gemini key and map token") && everything.contains("Spotify"))
    }

    @Test fun `a key this phone cannot read shows Pairing broken and offers Forget`() {
        val file = MemoryRecordFile()
        CarLinkStore(FakeKeystoreStandIn(), file) {}.write(ByteArray(32) { 1 }, null)
        val unreadable = FakeKeystoreStandIn().apply { failOpens = 3 }
        val brokenController = CarLinkController(
            CarLinkStore(unreadable, file) {}, { _, _ -> null },
            HandoverPlan(HandoverSources({ null }, { null }, { null }), NoUnitTokens()) {},
            { _, _, _, _ -> null }, scope, Dispatchers.Unconfined, { 300L }, {},
        )
        val broken = CarLinkViewModel(brokenController, scannerAvailable = true, debugPaste = false, scope = scope)
        assertEquals(CarLinkPhase.BROKEN, broken.state.value.phase)
        assertEquals("Pairing broken: pair again.", broken.state.value.headline)
        assertTrue(broken.state.value.canForget)
    }

    @Test fun `debug paste does nothing in a release build`() {
        val release = CarLinkViewModel(controller, scannerAvailable = true, debugPaste = false, scope = scope)
        release.pasteCode(CarLinkVectors.qrText())
        assertEquals(CarLinkPhase.NOT_PAIRED, release.state.value.phase)
    }

    @Test fun `a phone with a camera shows Scan and no words about scanning later`() {
        val release = CarLinkViewModel(controller, scannerAvailable = true, debugPaste = false, scope = scope)
        assertNull(release.state.value.scanNote)
        assertTrue(release.state.value.showScan)
    }

    @Test fun `a scanner that could not start leaves the pane and says so`() {
        vm.scan()
        vm.onScannerFailed()
        assertEquals(CarLinkPhase.NOT_PAIRED, state().phase)
        assertEquals(CarLinkWords.SCANNER_FAILED, state().scannerFailure)
        vm.scan()
        assertNull(state().scannerFailure)
    }

    @Test fun `the hands-over line and the no-car note wait until the facts have loaded`() {
        val slow = CarLinkController(
            store = store, transport = { _, _ -> null },
            handover = HandoverPlan(HandoverSources({ null }, { null }, { null }), NoUnitTokens()) {},
            newSender = { _, _, _, _ -> null }, scope = scope, io = Dispatchers.Unconfined,
            jitterMs = { 300L }, sleep = {},
            vehicles = { kotlinx.coroutines.awaitCancellation() },
        )
        val waiting = CarLinkViewModel(slow, scannerAvailable = true, debugPaste = false, scope = scope)
        assertEquals("", waiting.state.value.handsOver)
        assertNull(waiting.state.value.vehicleNote)
    }

    @Test fun `Scan and Pair wait until the facts have loaded`() {
        val slow = CarLinkController(
            store = store, transport = { _, _ -> null },
            handover = HandoverPlan(HandoverSources({ null }, { null }, { null }), NoUnitTokens()) {},
            newSender = { _, _, _, _ -> null }, scope = scope, io = Dispatchers.Unconfined,
            jitterMs = { 300L }, sleep = {},
            vehicles = { kotlinx.coroutines.awaitCancellation() },
        )
        val waiting = CarLinkViewModel(slow, scannerAvailable = true, debugPaste = false, scope = scope)
        assertTrue(!waiting.state.value.canPair)
        waiting.scan()
        assertTrue(waiting.state.value.phase != CarLinkPhase.SCANNING)
    }
}
