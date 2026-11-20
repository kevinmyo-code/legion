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
/**
 * How a sender runs: [revokeOnly] for a forgotten pairing (REVOKE only), [healOn] while it checks
 * whether the car is on the hotspot's new subnet (sends nothing, R2).
 */
class FeedMode(val revokeOnly: Boolean, val healOn: PairingNetwork?)

/** Starts and stops the foreground service that keeps the sender alive with the screen off. */
interface LocationServiceHost {
    /** False = Android refused to start it (said in words). */
    fun start(): Boolean
    fun stop()

    companion object {
        val NONE = object : LocationServiceHost {
            override fun start() = true
            override fun stop() = Unit
        }
    }
}

enum class LinkState { LOADING, NOT_PAIRED, PAIRED, BROKEN }

/** Something that happened that the screen should say once, in words. */
enum class CarLinkNote {
    CONFIRMED, REVERTED_QUIET, FORGOT_REVOKE_SENT, FORGOT_NOT_IN_RANGE, FORGET_FAILED, PORT_UNAVAILABLE,
    SETTING_NOT_SAVED, FORGOT_WILL_TELL, NETWORK_NOT_RECORDED, FORGET_TELLING, PORT_TAKEN,
    NETWORK_CHANGED_HEALING, NETWORK_CHANGED_REPAIR, SERVICE_NOT_STARTED, SERVICE_NO_LOCATION_PERMISSION,
}

/** Everything the Car link screen reads. Not printed anywhere; [PairingStage.DigitsShown] hides its digits. */
data class CarLinkStatus(
    val link: LinkState = LinkState.LOADING,
    val pairing: PairingStage? = null,
    val unitInRange: Boolean = false,
    val sendLocation: Boolean = false,
    /** What the hand-over will not carry because this phone lacks it (said in words). */
    val gaps: Set<HandoverGap> = emptySet(),
    /** The household's cars, and the one this head unit is in (the engine binds one). */
    val vehicles: List<VehicleOption> = emptyList(),
    val vehicleId: String? = null,
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
    /** Whether an interface is the phone's own hotspot NOW; asked again at heal time (C3). */
    private val ownHotspotNow: (String) -> Boolean = { false },
    /** The household's cars, for the pairing's vehicle choice. */
    private val vehicles: suspend () -> List<VehicleOption> = { emptyList() },
    /** Keeps the process sending with the screen off (a foreground service of type location). */
    private val locationService: LocationServiceHost = LocationServiceHost.NONE,
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
    // A pairing session is running: the revoke-only listener pauses (C1). Its REVOKE under the old
    // key would make a unit holding the new pairing as PENDING forget that too.
    @Volatile private var pairingOpen = false
    // The foreground service's wanted state, and whether the pairing network is up right now.
    @Volatile private var serviceOn = false
    @Volatile private var networkPresent = false

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
            refreshForScreen()
            startFeed()
        }
    }

    /** What the screen shows before a pairing: what the hand-over would lack. */
    @Suppress("TooGenericExceptionCaught") // a failing source must not stop the car link starting
    private suspend fun refreshForScreen() {
        val gaps = try {
            handover.gaps()
        } catch (_: RuntimeException) {
            emptySet()
        }
        val cars = try {
            vehicles()
        } catch (_: RuntimeException) {
            emptyList()
        }
        _status.update { s ->
            // One car: it is the one. A choice no longer on the list is dropped.
            val chosen = s.vehicleId?.takeIf { id -> cars.any { it.id == id } } ?: cars.singleOrNull()?.id
            s.copy(gaps = gaps, vehicles = cars, vehicleId = chosen)
        }
    }

    /** A revoke-only record younger than 90 days is kept for one REVOKE; an older one expires unused. */
    private fun loadRevokeOnly() {
        if (!store.revokeOnlyPending()) return
        if (revokeExpired()) {
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
        val feedRing = if (!revokeOnly) ring else revokeRing() ?: return
        feedJob = scope.launch(io) {
            var keepGoing = true
            while (keepGoing && isActive && feedRing.current != null) {
                // 4.3: no socket at all while the network the phone paired on is absent (rev 5); a
                // changed subnet is followed only on the phone's own hotspot (rev 6, R2).
                val look = network()
                noteNetwork(look)
                val heal = healTarget(look)
                keepGoing = when {
                    look is NetworkLook.Present -> runSender(feedRing, FeedMode(revokeOnly, null))
                    heal != null -> runSender(feedRing, FeedMode(revokeOnly, heal))
                    else -> true
                }
                if (keepGoing) delay(if (look is NetworkLook.Present) RESTART_DELAY_MS else NETWORK_POLL_MS)
            }
        }
    }

    /** One socket's life. False = stop for good (no port, or the REVOKE went); true = the network left. */
    /**
     * R2, the heal rule. The recorded interface's name is up with a different subnet:
     * - on the phone's OWN hotspot, listen there (sending nothing) for the car to confirm it;
     * - on any other network, never follow: say so and ask for a re-pair.
     * Why the hotspot case is safe: that interface exists only while this phone is the access point,
     * so only devices that joined with the hotspot's passphrase are on it. A captured HELLO replayed
     * from any other network (P-D) never reaches this path, because that network's interface is not the
     * recorded hotspot interface. Inside the hotspot the heal needs 2 HELLOs that open under the
     * current link key, from a session this listener has never seen, rising counters (4.3 replay rule).
     * The phone still cannot prove a HELLO is live (4.3 rev 5), which is why the heal is limited to it.
     */
    private fun healTarget(look: NetworkLook): PairingNetwork? {
        if (look !is NetworkLook.Changed) {
            if (look is NetworkLook.Present && _status.value.networkChanged) {
                _status.update { it.copy(networkChanged = false, note = null) }
            }
            return null
        }
        // Recorded at pairing AND true now: where hotspot and Wi-Fi client share an interface name,
        // a cafe Wi-Fi on that name must never look like a changed hotspot (C3).
        val own = store.pairingNetwork()?.ownHotspot == true && ownHotspotNow(look.iface.name)
        val note = if (own) CarLinkNote.NETWORK_CHANGED_HEALING else CarLinkNote.NETWORK_CHANGED_REPAIR
        if (_status.value.note != note) _status.update { it.copy(networkChanged = true, note = note) }
        return if (own) PairingNetwork.of(look.iface, ownHotspot = true) else null
    }

    private suspend fun runSender(feedRing: LinkKeyRing, mode: FeedMode): Boolean {
        val revokeOnly = mode.revokeOnly
        val s = openSender(feedRing, mode) ?: return false
        sender = s
        var delivered = false
        val tell = RevokeTelling(sentAny = false, heardAt = s.nowMs())
        try {
            while (!delivered && keepListening(feedRing, mode)) {
                if (!stepContained(s)) break
                val inRange = s.inRange()
                if (mode.healOn != null) {
                    if (s.acceptedHellos() >= HEAL_HELLOS) healed(mode.healOn)
                } else if (revokeOnly) {
                    delivered = revokeStep(s, tell)
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

    /** A sender, or null with the reason said in words (a taken port is never worked around). */
    private fun openSender(feedRing: LinkKeyRing, mode: FeedMode): CarLinkSender? {
        val revokeOnly = mode.revokeOnly
        val note = try {
            val fixesOn = { mode.healOn == null && !revokeOnly && _status.value.sendLocation }
            newSender(feedRing, ::onRingEvent, fixesOn, mode)
                ?.let { return it }
            CarLinkNote.PORT_UNAVAILABLE
        } catch (_: java.net.BindException) {
            CarLinkNote.PORT_TAKEN
        } catch (_: IOException) {
            CarLinkNote.PORT_UNAVAILABLE
        }
        // A revoke-only listener keeps Forget's own words on screen unless the port is taken.
        if (!revokeOnly || note == CarLinkNote.PORT_TAKEN) _status.update { it.copy(note = note) }
        return null
    }

    private suspend fun keepListening(feedRing: LinkKeyRing, mode: FeedMode): Boolean {
        val look = network()
        noteNetwork(look)
        return currentCoroutineContext().isActive && feedRing.current != null && stillOn(look, mode)
    }

    private fun noteNetwork(look: NetworkLook) {
        networkPresent = look is NetworkLook.Present
        syncService()
    }

    /**
     * The foreground service runs only while paired AND "Send location" is on AND the pairing
     * network is present; it stops the moment any of them ends. A start Android refuses is said in
     * words and not retried until the conditions change.
     */
    @Synchronized private fun syncService() {
        val want = ring.current != null && _status.value.sendLocation && networkPresent
        if (want == serviceOn) return
        serviceOn = want
        if (!want) return locationService.stop()
        if (!locationService.start()) _status.update { it.copy(note = CarLinkNote.SERVICE_NOT_STARTED) }
    }

    /** The sender stays up only while the network still looks the way it did when it started. */
    private fun stillOn(look: NetworkLook, mode: FeedMode): Boolean {
        val heal = mode.healOn ?: return look is NetworkLook.Present
        return look is NetworkLook.Changed && PairingNetwork.of(look.iface, ownHotspot = true).encode() == heal.encode()
    }

    /** Two authenticated HELLOs on the hotspot's new subnet: record it and listen there as normal. */
    private fun healed(net: PairingNetwork) {
        if (store.setPairingNetwork(net)) _status.update { it.copy(networkChanged = false, note = null) }
    }

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
            pairingOpen = true
            if (ring.current == null) stopFeed()
            val client = PairingClient(qr, P256.generate(), ring, handover::body)
            val runner = PairingRunner(transport, jitterMs, sleep) { stage ->
                _status.update { it.copy(pairing = stage) }
                // Mint the unit's token only once the unit proved itself (a verified ack).
                if (stage is PairingStage.DigitsShown) scope.launch(io) { handover.prepare(_status.value.vehicleId) }
            }
            try {
                runner.run(client, qr.host, qr.port)
            } finally {
                // Also on a cancel: one after `stored` still leaves the new key current (Paired).
                ring.sessionOpen = false
                pairingOpen = false
                withContext(NonCancellable) {
                    if (client.stage !is PairingStage.Paired) handover.discard()
                    finish(client.stage, qr.host)
                }
            }
        }
    }

    /** Where a revoke-only listener is: whether it told the car yet, and when it last heard it. */
    private class RevokeTelling(var sentAny: Boolean, var heardAt: Long, var hellos: Long = 0)

    /**
     * R1: REVOKE again on every accepted HELLO under the forgotten key (a UDP send that did not throw
     * is not delivery); the record goes only after [revokeSilenceMs] with no HELLO under that key,
     * which means the car forgot. True = done.
     */
    private fun revokeStep(s: CarLinkSender, tell: RevokeTelling): Boolean {
        // The 90 days are checked where the record is used, not only at app start.
        if (revokeExpired()) {
            expireRevoke()
            return true
        }
        val n = s.acceptedHellos()
        if (n > tell.hellos) {
            tell.hellos = n
            tell.heardAt = s.nowMs()
            if (s.revoke()) {
                tell.sentAny = true
                _status.update { it.copy(note = CarLinkNote.FORGET_TELLING) }
            }
        }
        // C2: silence proves the car forgot only when it begins right after a REVOKE this listener sent in
        // reply to a HELLO it heard. A REVOKE sent at Forget and then nothing may just mean the phone walked
        // away (its hotspot stays up), so then the record waits and the words say so.
        val silent = s.nowMs() - tell.heardAt >= revokeSilenceMs
        if (silent && !tell.sentAny && _status.value.note == CarLinkNote.FORGET_TELLING) {
            _status.update { it.copy(note = CarLinkNote.FORGOT_WILL_TELL) }
        }
        val quiet = silent && tell.sentAny
        if (quiet) revokeDelivered()
        return quiet
    }

    /** A ring holding only the forgotten key (writes refused), or null when there is none or it expired. */
    private fun revokeRing(): LinkKeyRing? {
        // Paused while a pairing session is open (C1).
        val key = revokeKey?.takeIf { !pairingOpen } ?: return null
        return if (revokeExpired()) {
            expireRevoke()
            null
        } else {
            LinkKeyRing(key.copyOf(), null) { _, _ -> false }
        }
    }

    /** Wall clock, so the 90 days count across reboots; a record with no readable date is expired. */
    private fun revokeExpired(): Boolean {
        val since = store.revokeOnlySince() ?: return true
        return wallClock() - since > REVOKE_ONLY_MAX_MS
    }

    private fun expireRevoke() {
        store.clearRevokeOnly()
        revokeKey?.fill(0)
        revokeKey = null
        _status.update { it.copy(revokePending = false) }
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
        // In range or not, a key becomes a REVOKE-only record: REVOKE goes again on every HELLO under it
        // until the car goes quiet (R1), so one lost datagram never leaves the car paired.
        val keepForRevoke = key != null
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
                    sentRevoke -> CarLinkNote.FORGET_TELLING
                    keepForRevoke -> CarLinkNote.FORGOT_WILL_TELL
                    else -> CarLinkNote.FORGOT_NOT_IN_RANGE
                },
            )
        }
        syncService()
        if (keepForRevoke) startFeed()
    }

    fun setSendLocation(on: Boolean) {
        if (!store.setSendLocation(on)) return _status.update { it.copy(note = CarLinkNote.SETTING_NOT_SAVED) }
        _status.update { it.copy(sendLocation = on) }
        syncService()
    }

    /** The service could not start for lack of location permission: said in words, not retried. */
    fun onServiceLostLocationPermission() {
        _status.update { it.copy(note = CarLinkNote.SERVICE_NO_LOCATION_PERMISSION) }
    }

    /** Which of the household's cars this head unit is in (the engine binds its sign-in to one). */
    fun chooseVehicle(id: String?) {
        _status.update { s -> s.copy(vehicleId = id?.takeIf { v -> s.vehicles.any { it.id == v } }) }
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
        const val HEAL_HELLOS = 2L

        /** 5.4 rev 5: a revoke-only record waits about 90 days, then expires unused. */
        const val REVOKE_ONLY_MAX_MS = 90L * 24 * 60 * 60 * 1000
    }
}
