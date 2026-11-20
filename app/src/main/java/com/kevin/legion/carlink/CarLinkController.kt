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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** LOADING until the stored keys were read, off the main thread. */
/** STUB (red step). */
class FeedMode(val revokeOnly: Boolean, val healOn: PairingNetwork?)

enum class LinkState { LOADING, NOT_PAIRED, PAIRED, BROKEN }

/** Something that happened that the screen should say once, in words. */
enum class CarLinkNote {
    CONFIRMED, REVERTED_QUIET, FORGOT_REVOKE_SENT, FORGOT_NOT_IN_RANGE, FORGET_FAILED, PORT_UNAVAILABLE,
    SETTING_NOT_SAVED, FORGOT_WILL_TELL, NETWORK_NOT_RECORDED, FORGET_TELLING, PORT_TAKEN,
    NETWORK_CHANGED_HEALING, NETWORK_CHANGED_REPAIR,
}

/** Everything the Car link screen reads. Not printed anywhere; [PairingStage.DigitsShown] hides its digits. */
data class CarLinkStatus(
    val link: LinkState = LinkState.LOADING,
    val pairing: PairingStage? = null,
    val unitInRange: Boolean = false,
    val sendLocation: Boolean = false,
    val carKeySet: Boolean = false,
    /** A forgotten pairing whose unit will be told at the next contact (5.4, rev 5). */
    val revokePending: Boolean = false,
    val networkChanged: Boolean = false,
    val note: CarLinkNote? = null,
)

/**
 * The one owner of the car link on the phone: pairing (section 3), the location sender (section 4)
 * and Forget (5.4). The Car link screen calls it; a later voice tool would call the same methods
 * (ADR 0035). Built by hand in [CarLinkGraph] (no Hilt in the build yet); every dependency is a
 * constructor parameter so the JVM test passes fakes.
 */
// The car link's one owner (pairing, the feed, Forget, its settings): splitting it would spread one
// state across classes that must change together.
@Suppress("TooManyFunctions")
class CarLinkController(
    private val store: CarLinkStore,
    private val transport: PairingTransport,
    private val handover: HandoverPlan,
    private val newSender: (LinkKeyRing, (RingEvent) -> Unit, () -> Boolean, FeedMode) -> CarLinkSender?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val jitterMs: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    /** The network the phone paired on is up now (4.3, rev 5). Nothing listens while it is not. */
    private val network: () -> NetworkLook = { NetworkLook.Present },
    /** Records the network that holds [String] (the unit's address) at pairing; false = none found. */
    private val recordNetwork: (String) -> Boolean = { true },
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** R1: a revoke-only record is wiped after this long with no HELLO under its key. */
    private val revokeSilenceMs: Long = 30_000L,
) {
    private val _status = MutableStateFlow(CarLinkStatus())
    val status: StateFlow<CarLinkStatus> = _status.asStateFlow()

    // Empty until [ensureLoaded] read the store; nothing touches the Keystore on the caller's thread.
    @Volatile private var ring = LinkKeyRing(null, null, store)
    private val loadLock = Mutex()
    @Volatile private var loaded = false
    private var pairingJob: Job? = null
    private var feedJob: Job? = null
    @Volatile private var sender: CarLinkSender? = null
    // The forgotten pairing's key while a revoke-only record waits for the unit (5.4, rev 5).
    @Volatile private var revokeKey: ByteArray? = null

    /**
     * Reads the stored keys once, on [io] (Keystore decrypts with retries can be slow). A store that
     * throws is "Pairing broken", never a crash: app start must not fail because of the car link.
     */
    @Suppress("TooGenericExceptionCaught") // a failing Keystore must not take app start down
    private suspend fun ensureLoaded() = loadLock.withLock {
        if (loaded) return@withLock
        loaded = true
        val result = try {
            store.load()
        } catch (_: RuntimeException) {
            StoredLink.Broken
        }
        if (result is StoredLink.Paired) ring = LinkKeyRing(result.current, result.reserve, store)
        if (result is StoredLink.NotPaired) loadRevokeOnly()
        val sendLocation = try {
            store.sendLocation()
        } catch (_: RuntimeException) {
            false
        }
        _status.update {
            it.copy(
                link = when (result) {
                    is StoredLink.Paired -> LinkState.PAIRED
                    StoredLink.Broken -> LinkState.BROKEN
                    StoredLink.NotPaired -> LinkState.NOT_PAIRED
                },
                sendLocation = sendLocation,
                carKeySet = runCatching { store.carGeminiKey() != null }.getOrDefault(false),
            )
        }
    }

    /**
     * Loads (once, off the caller's thread) and starts the sender if paired. Returns at once; safe to
     * call repeatedly (app start, the Car link screen, after pairing).
     */
    fun start() {
        scope.launch(io) {
            ensureLoaded()
            startFeed()
        }
    }

    /** A revoke-only record younger than 90 days is kept for one REVOKE; an older one expires unused. */
    private fun loadRevokeOnly() {
        if (!store.revokeOnlyPending()) return
        val since = store.revokeOnlySince()
        if (since == null || wallClock() - since > REVOKE_ONLY_MAX_MS) {
            store.clearRevokeOnly()
            return
        }
        revokeKey = store.revokeOnlyKey()
        _status.update { it.copy(revokePending = true) }
    }

    /**
     * Runs the sender: the full feed when paired, or, for a revoke-only record, a listener that can
     * do one thing: recognise that unit's HELLO and send it REVOKE (no fixes, no zone, no hand-over).
     */
    @Synchronized private fun startFeed() {
        if (feedJob?.isActive == true) return
        val revokeOnly = ring.current == null
        val feedRing = if (!revokeOnly) {
            ring
        } else {
            revokeKey?.let { LinkKeyRing(it.copyOf(), null) { _, _ -> false } } ?: return
        }
        feedJob = scope.launch(io) {
            var keepGoing = true
            while (keepGoing && isActive && feedRing.current != null) {
                // 4.3 rev 5: no socket at all while the network the phone paired on is absent.
                val present = network() is NetworkLook.Present
                keepGoing = !present || runSender(feedRing, revokeOnly)
                if (keepGoing) delay(if (present) RESTART_DELAY_MS else NETWORK_POLL_MS)
            }
        }
    }

    /** One socket's life. False = stop for good (no port, or the REVOKE went); true = the network left. */
    private suspend fun runSender(feedRing: LinkKeyRing, revokeOnly: Boolean): Boolean {
        val s = newSender(feedRing, ::onRingEvent, { !revokeOnly && _status.value.sendLocation }, FeedMode(revokeOnly, null))
        if (s == null) {
            // A revoke-only listener keeps Forget's own words on screen.
            if (!revokeOnly) _status.update { it.copy(note = CarLinkNote.PORT_UNAVAILABLE) }
            return false
        }
        sender = s
        var delivered = false
        try {
            while (!delivered && keepListening(feedRing)) {
                if (!stepContained(s)) break
                val inRange = s.inRange()
                if (revokeOnly) {
                    delivered = inRange && s.revoke()
                    if (delivered) revokeDelivered()
                } else if (inRange != _status.value.unitInRange) {
                    _status.update { it.copy(unitInRange = inRange) }
                }
            }
        } finally {
            if (sender === s) sender = null
            s.close()
            _status.update { it.copy(unitInRange = false) }
        }
        return !delivered
    }

    private suspend fun keepListening(feedRing: LinkKeyRing): Boolean =
        currentCoroutineContext().isActive && feedRing.current != null && network() is NetworkLook.Present

    /**
     * One loop step that can never take the app down: false when the socket closed (Forget, or the
     * port went away) and the loop should end; anything else unexpected is counted and backed off.
     */
    @Suppress("TooGenericExceptionCaught") // a crash here would be the whole app's, from LAN bytes
    private suspend fun stepContained(s: CarLinkSender): Boolean = try {
        s.step()
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        loopFaults++
        delay(LOOP_BACKOFF_MS)
        true
    }

    /** Unexpected failures the sender loop survived. */
    @Volatile var loopFaults = 0
        private set

    /** The scanner (or the debug paste) produced [qrText]. */
    @Synchronized fun pair(qrText: String) {
        if (pairingJob?.isActive == true) return
        val qr = QrPayload.parse(qrText.trim())
        if (qr == null) {
            _status.update { it.copy(pairing = PairingStage.Failed(PairingFailure.BAD_QR), note = null) }
            return
        }
        _status.update { it.copy(pairing = PairingStage.AwaitingAck, note = null) }
        pairingJob = scope.launch(io) {
            ensureLoaded()
            val ring = ring
            ring.sessionOpen = true
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
                    finish(client.stage, qr.host)
                }
            }
        }
    }

    private fun revokeDelivered() {
        store.clearRevokeOnly()
        revokeKey?.fill(0)
        revokeKey = null
        _status.update { it.copy(revokePending = false, note = CarLinkNote.FORGOT_REVOKE_SENT) }
    }

    private fun finish(end: PairingStage, unitHost: String) {
        if (end is PairingStage.Paired) {
            store.setUnitDeviceId(handover.mintedId())
            handover.keep()
            if (!recordNetwork(unitHost)) _status.update { it.copy(note = CarLinkNote.NETWORK_NOT_RECORDED) }
            // A new pairing replaces any revoke-only record (its key is no longer the unit's current one).
            if (revokeKey != null) {
                stopFeed()
                store.clearRevokeOnly()
                revokeKey = null
                _status.update { it.copy(revokePending = false) }
            }
        }
        val link = when {
            ring.current != null -> LinkState.PAIRED
            else -> LinkState.NOT_PAIRED
        }
        _status.update { it.copy(link = link, pairing = end) }
        // The sender re-derives its keys from the ring on every call; it only needs starting.
        startFeed()
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
        ensureLoaded()
        val sentRevoke = sender?.revoke() == true
        store.unitDeviceId()?.let { handover.revoke(it) }
        val key = ring.current?.copyOf()
        // Out of range with a key: keep a REVOKE-only record so the unit is told at the next contact.
        val keepForRevoke = !sentRevoke && key != null
        val forgotten = if (keepForRevoke) {
            store.writeRevokeOnly(key!!, wallClock()).also { if (it) ring.dropFromMemory() }
        } else {
            ring.forget()
        }
        if (!forgotten) {
            _status.update { it.copy(note = CarLinkNote.FORGET_FAILED) }
            return@withContext
        }
        stopFeed()
        revokeKey = if (keepForRevoke) key else null
        _status.update {
            CarLinkStatus(
                link = LinkState.NOT_PAIRED,
                revokePending = keepForRevoke,
                note = when {
                    sentRevoke -> CarLinkNote.FORGOT_REVOKE_SENT
                    keepForRevoke -> CarLinkNote.FORGOT_WILL_TELL
                    else -> CarLinkNote.FORGOT_NOT_IN_RANGE
                },
            )
        }
        if (keepForRevoke) startFeed()
    }

    fun setSendLocation(on: Boolean) {
        if (!store.setSendLocation(on)) return _status.update { it.copy(note = CarLinkNote.SETTING_NOT_SAVED) }
        _status.update { it.copy(sendLocation = on) }
    }

    /** The car's own Gemini key field (blank clears it). Sealed on [io]: the Keystore is slow. */
    fun setCarGeminiKey(key: String?) {
        scope.launch(io) {
            val saved = store.setCarGeminiKey(key)
            _status.update {
                if (saved) it.copy(carKeySet = !key.isNullOrBlank()) else it.copy(note = CarLinkNote.SETTING_NOT_SAVED)
            }
        }
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

    private companion object {
        const val LOOP_BACKOFF_MS = 1_000L
        const val NETWORK_POLL_MS = 10_000L
        const val RESTART_DELAY_MS = 1_000L

        /** 5.4 rev 5: a revoke-only record waits about 90 days, then expires unused. */
        const val REVOKE_ONLY_MAX_MS = 90L * 24 * 60 * 60 * 1000
    }
}
