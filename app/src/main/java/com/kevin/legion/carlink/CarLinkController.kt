package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.LinkKeyRing
import com.kevin.legion.carlink.protocol.P256
import com.kevin.legion.carlink.protocol.PairingClient
import com.kevin.legion.carlink.protocol.PairingFailure
import com.kevin.legion.carlink.protocol.PairingRunner
import com.kevin.legion.carlink.protocol.PairingStage
import com.kevin.legion.carlink.protocol.PairingTransport
import com.kevin.legion.carlink.protocol.QrPayload
import com.kevin.legion.carlink.protocol.RingEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class LinkState { NOT_PAIRED, PAIRED, BROKEN }

/** Something that happened that the screen should say once, in words. */
enum class CarLinkNote {
    CONFIRMED, REVERTED_QUIET, FORGOT_REVOKE_SENT, FORGOT_NOT_IN_RANGE, FORGET_FAILED, PORT_UNAVAILABLE,
    SETTING_NOT_SAVED,
}

/** Everything the Car link screen reads. Not printed anywhere; [PairingStage.DigitsShown] hides its digits. */
data class CarLinkStatus(
    val link: LinkState = LinkState.NOT_PAIRED,
    val pairing: PairingStage? = null,
    val unitInRange: Boolean = false,
    val sendLocation: Boolean = false,
    val handOverGemini: Boolean = false,
    val note: CarLinkNote? = null,
)

/**
 * The one owner of the car link on the phone: pairing (section 3), the location sender (section 4)
 * and Forget (5.4). The Car link screen calls it; a later voice tool would call the same methods
 * (ADR 0035). Built by hand in [CarLinkGraph] (no Hilt in the build yet); every dependency is a
 * constructor parameter so the JVM test passes fakes.
 */
class CarLinkController(
    private val store: CarLinkStore,
    private val transport: PairingTransport,
    private val handover: HandoverPlan,
    private val newSender: (LinkKeyRing, (RingEvent) -> Unit, () -> Boolean) -> CarLinkSender?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val jitterMs: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    private val _status = MutableStateFlow(CarLinkStatus())
    val status: StateFlow<CarLinkStatus> = _status.asStateFlow()

    private val ring: LinkKeyRing
    private var pairingJob: Job? = null
    private var feedJob: Job? = null
    @Volatile private var sender: CarLinkSender? = null

    init {
        val loaded = store.load()
        ring = when (loaded) {
            is StoredLink.Paired -> LinkKeyRing(loaded.current, loaded.reserve, store)
            else -> LinkKeyRing(null, null, store)
        }
        _status.value = CarLinkStatus(
            link = when (loaded) {
                is StoredLink.Paired -> LinkState.PAIRED
                StoredLink.Broken -> LinkState.BROKEN
                StoredLink.NotPaired -> LinkState.NOT_PAIRED
            },
            sendLocation = store.sendLocation(),
            handOverGemini = store.handOverGemini(),
        )
    }

    /** Starts the sender if paired. Safe to call repeatedly (app start, after pairing). */
    @Synchronized fun start() {
        if (ring.current == null || feedJob?.isActive == true) return
        feedJob = scope.launch(io) {
            val s = newSender(ring, ::onRingEvent) { _status.value.sendLocation }
            if (s == null) {
                _status.update { it.copy(note = CarLinkNote.PORT_UNAVAILABLE) }
                return@launch
            }
            sender = s
            try {
                while (isActive && ring.current != null) {
                    s.step()
                    val inRange = s.inRange()
                    if (inRange != _status.value.unitInRange) _status.update { it.copy(unitInRange = inRange) }
                }
            } catch (_: IOException) {
                // The socket closed under a blocked receive (Forget, or the port went away): the loop ends.
            } finally {
                sender = null
                s.close()
                _status.update { it.copy(unitInRange = false) }
            }
        }
    }

    /** The scanner (or the debug paste) produced [qrText]. */
    @Synchronized fun pair(qrText: String) {
        if (pairingJob?.isActive == true) return
        val qr = QrPayload.parse(qrText.trim())
        if (qr == null) {
            _status.update { it.copy(pairing = PairingStage.Failed(PairingFailure.BAD_QR), note = null) }
            return
        }
        ring.sessionOpen = true
        _status.update { it.copy(pairing = PairingStage.AwaitingAck, note = null) }
        pairingJob = scope.launch(io) {
            val client = PairingClient(qr, P256.generate(), ring, handover::body)
            val runner = PairingRunner(transport, jitterMs, sleep) { stage ->
                _status.update { it.copy(pairing = stage) }
                // Mint the unit's token only once the unit proved itself (a verified ack).
                if (stage is PairingStage.DigitsShown) scope.launch(io) { handover.prepare() }
            }
            try {
                runner.run(client, qr.host, qr.port)
            } finally {
                // Also on a cancel: one after `stored` still leaves the new key current (Paired).
                ring.sessionOpen = false
                withContext(NonCancellable) {
                    if (client.stage !is PairingStage.Paired) handover.discard()
                    finish(client.stage)
                }
            }
        }
    }

    private fun finish(end: PairingStage) {
        if (end is PairingStage.Paired) {
            store.setUnitDeviceId(handover.mintedId())
            handover.keep()
        }
        val link = when {
            ring.current != null -> LinkState.PAIRED
            else -> LinkState.NOT_PAIRED
        }
        _status.update { it.copy(link = link, pairing = end) }
        // The sender re-derives its keys from the ring on every call; it only needs starting.
        start()
    }

    /** The person cancelled pairing. */
    fun cancel() {
        pairingJob?.cancel()
    }

    /** Clears a finished pairing's result from the screen. */
    fun dismiss() {
        if (pairingJob?.isActive == true) return
        _status.update { it.copy(pairing = null, note = null) }
    }

    /** "Forget head unit" (5.4): REVOKE if in range, revoke the unit's engine token, delete the keys. */
    suspend fun forget() = withContext(io) {
        // On io: the REVOKE is a socket send, never allowed on the main thread.
        pairingJob?.cancelAndJoin()
        val sentRevoke = sender?.revoke() == true
        store.unitDeviceId()?.let { handover.revoke(it) }
        if (!ring.forget()) {
            _status.update { it.copy(note = CarLinkNote.FORGET_FAILED) }
            return@withContext
        }
        stopFeed()
        _status.update {
            CarLinkStatus(
                link = LinkState.NOT_PAIRED,
                note = if (sentRevoke) CarLinkNote.FORGOT_REVOKE_SENT else CarLinkNote.FORGOT_NOT_IN_RANGE,
            )
        }
    }

    fun setSendLocation(on: Boolean) {
        if (!store.setSendLocation(on)) return _status.update { it.copy(note = CarLinkNote.SETTING_NOT_SAVED) }
        _status.update { it.copy(sendLocation = on) }
    }

    fun setHandOverGemini(on: Boolean) {
        if (!store.setHandOverGemini(on)) return _status.update { it.copy(note = CarLinkNote.SETTING_NOT_SAVED) }
        _status.update { it.copy(handOverGemini = on) }
    }

    private fun onRingEvent(e: RingEvent) {
        val note = when (e) {
            RingEvent.CONFIRMED -> CarLinkNote.CONFIRMED
            RingEvent.REVERTED -> CarLinkNote.REVERTED_QUIET
            RingEvent.NONE -> return
        }
        val link = if (ring.current != null) LinkState.PAIRED else LinkState.NOT_PAIRED
        _status.update { it.copy(note = note, link = link) }
    }

    private fun stopFeed() {
        feedJob?.cancel()
        feedJob = null
        // Unblocks a receive that is waiting on the socket.
        sender?.close()
    }
}
