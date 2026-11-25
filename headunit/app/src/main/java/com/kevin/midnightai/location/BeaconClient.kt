package com.kevin.midnightai.location

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Head-unit end of the GPS beacon link: asks the phone for fixes and feeds them to
 * [LocationController]. See [com.kevin.midnightai.service.DeviceRole] for why the
 * link exists at all.
 *
 * **Finding the phone (revised 2026-07-25).** This originally assumed the beacon
 * phone was the hotspot's access point, so the head unit's default gateway WAS the
 * beacon and no discovery was needed. That is still true for an Android phone
 * sharing its own data, and it is still tried first - but it is not Kevin's rig.
 * There the eSIM lives in an iPhone, which supplies the hotspot and cannot run this
 * app, while the beacon is a second Android phone with no cellular data joined to
 * that same hotspot. Beacon and head unit are peer clients; the gateway is a device
 * that will never answer.
 *
 * So a HELLO goes to every plausible address at once - a manually typed one if the
 * driver set it, else the gateway, the subnet broadcast, and the limited broadcast.
 * Whichever reaches the beacon gets a reply, and the reply's source address is then
 * remembered and unicast to from then on. Three extra datagrams every 5 seconds is
 * not a cost worth optimising against an environment we cannot verify from code:
 * client isolation (an access point refusing to pass traffic between its clients) is
 * a real setting, iOS Personal Hotspot's behaviour is undocumented for our purposes,
 * and if broadcast turns out to be dropped, [BeaconPreferences]'s typed address is
 * the escape hatch that still works.
 *
 * Pull-gated push: this end sends a HELLO every [HELLO_INTERVAL_MS] naming the rate
 * it wants, and the phone transmits only while those HELLOs keep arriving. Stop the
 * head unit (or drive out of range, or cut the ignition) and the phone stops
 * sampling GPS on its own, with no shutdown message needing to survive the failure
 * that caused it.
 *
 * The requested rate is read live from [LocationController.desiredIntervalMs], so
 * the existing `startNavMode`/`stopNavMode` calls on the Cruise and nav screens
 * already drive it. No consumer of location knows this class exists.
 */
object BeaconClient {
    private const val TAG = "BeaconClient"

    /** How often we re-announce ourselves. Must stay well under the phone's peer timeout. */
    private const val HELLO_INTERVAL_MS = 5_000L

    /**
     * Blocking-receive timeout. Only exists so a cancelled scope actually unwinds
     * instead of parking forever inside `receive()`.
     */
    private const val RECEIVE_TIMEOUT_MS = 10_000

    /**
     * Grace on top of the rate we asked for, before the link is reported down.
     *
     * It has to be RELATIVE to the requested interval, not a constant. A fixed 15s
     * looked fine against nav mode's 1 Hz but was shorter than the 30s background
     * cadence, so in ordinary background operation the link went "down" for 10 of
     * every 30 seconds while the phone was answering perfectly on schedule - and the
     * Setup screen dutifully flapped between "Receiving location from your phone" and
     * "Waiting for a phone" every cycle. A status line that lies is worse than none.
     */
    private const val LINK_GRACE_MS = 10_000L
    private const val LINK_STALE_FACTOR = 2

    /** Built once - it is a constant, and resolving it per cycle would be silly. */
    private val LIMITED_BROADCAST: InetAddress? =
        runCatching { InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)) }.getOrNull()

    /**
     * Owned rather than injected so the Setup screen can flip the role on and off
     * live without a service restart. The link is process-scoped by nature - one
     * head unit, one phone - so there is no lifecycle for a caller to hand us that
     * is more correct than this.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var job: Job? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var lastFixAtMs = 0L

    /**
     * Where the last fix actually came from. Once the beacon has answered we know
     * its real address and can stop broadcasting at the whole subnet - and it is
     * also the only way the driver can find out what to type into
     * [BeaconPreferences] if discovery is flaky, since ADB is blocked on this unit
     * (CLAUDE.md §14) and there is nowhere else to read it from.
     */
    @Volatile private var resolvedPeer: InetAddress? = null

    /**
     * How this run listens ([com.kevin.midnightai.link.CarLinkRules.feedMode], review H1/H2) and,
     * in MNAI2 mode, the feeds under the COMMITTED and PENDING link keys. A unit with a pairing
     * record NEVER speaks or accepts MNAI1; one whose key cannot be read listens to nothing.
     * Rebuilt by [restart] on every pairing change, so a forgotten or replaced key is dead at once.
     * [feeds] is guarded by itself.
     */
    @Volatile private var mode: com.kevin.midnightai.link.FeedMode = com.kevin.midnightai.link.FeedMode.OFF

    /**
     * Which run may install [mode] and [feeds] (review N3). Every start and stop moves it on, and a
     * run installs only if it still holds the current number, so a slow earlier start that finishes
     * after a Forget or a re-pair can never put an old key back.
     */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var feeds: LinkFeedSet? = null

    private val committing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Tests only: the feeds the running receiver holds, and its mode. */
    internal fun feedsForTest(): LinkFeedSet? = feeds
    internal fun modeForTest(): com.kevin.midnightai.link.FeedMode = mode

    /** A candidate peer address and how many accepted datagrams in a row came from it (review L8). */
    @Volatile private var candidatePeer: InetAddress? = null
    @Volatile private var candidateCount = 0

    private val _linkUp = MutableStateFlow(false)

    /** True while fixes are actually arriving - drives the Setup screen's status line. */
    val linkUp: StateFlow<Boolean> = _linkUp.asStateFlow()

    private val _peerLabel = MutableStateFlow<String?>(null)

    /** The beacon's address once it has answered, for the Setup screen. Null until then. */
    val peerLabel: StateFlow<String?> = _peerLabel.asStateFlow()

    /**
     * The socket is opened HERE, synchronously, before the coroutine is launched -
     * not inside it. The loops below spend their lives blocked in `receive()`, which
     * is not a suspension point, so `job.cancel()` cannot interrupt them; only
     * closing the socket can. Opening it inside the coroutine left a window where
     * `stop()` ran before the field was assigned, closed nothing, and the loop then
     * installed a socket that nothing could ever reach again - an immortal receive
     * loop still sending HELLOs after the driver switched the role off. Assigning
     * the field before anything can block closes that window entirely.
     */
    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        val sock = runCatching { DatagramSocket() }.getOrElse {
            Log.w(TAG, "Could not open beacon socket", it)
            return
        }
        sock.soTimeout = RECEIVE_TIMEOUT_MS
        // Required before a datagram may go to a broadcast address; without it the
        // send throws and discovery silently only ever tries the gateway.
        runCatching { sock.broadcast = true }
        socket = sock
        val gen = generation.incrementAndGet()

        job = scope.launch {
            // The keys are decrypted here, off the caller's (often the main) thread.
            if (!resolveMode(appContext, gen)) {
                runCatching { sock.close() }
                if (socket === sock) socket = null
                return@launch
            }
            // Announce on a timer; receive on this coroutine. Two jobs, one socket -
            // DatagramSocket permits a concurrent send during a blocking receive,
            // which is what lets the receive loop stay a simple blocking read.
            val hello = launch { helloLoop(appContext, sock) }
            try {
                receiveLoop(appContext, sock)
            } finally {
                hello.cancel()
                runCatching { sock.close() }
                if (socket === sock) socket = null
                _linkUp.value = false
            }
        }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        // Closing is what actually stops the loops; cancel alone cannot reach a
        // thread parked in a blocking receive.
        runCatching { socket?.close() }
        socket = null
        lastFixAtMs = 0L
        feeds = null
        mode = com.kevin.midnightai.link.FeedMode.OFF
        resolvedPeer = null
        candidatePeer = null
        candidateCount = 0
        _peerLabel.value = null
        _linkUp.value = false
    }

    /** Stops and starts again with the current pairing (CarLink listener; review H2). Serialised (N3). */
    @Synchronized
    fun restart(context: Context) {
        stop()
        start(context)
    }

    /**
     * Decides the mode for this run from the device role and the pairing (keys decrypted here).
     * Returns false when this run should not listen at all (OFF, or NOTHING for a broken pairing).
     */
    private fun resolveMode(context: Context, gen: Int): Boolean {
        val keys = com.kevin.midnightai.link.CarLink.refresh()
        val role = com.kevin.midnightai.service.DeviceRole.current(context)
        val decided = com.kevin.midnightai.link.CarLinkRules.feedMode(role, keys.status)
        if (decided != com.kevin.midnightai.link.FeedMode.MNAI2) {
            keys.committed?.fill(0)
            keys.pending?.fill(0)
            return install(gen, decided, null) && decided == com.kevin.midnightai.link.FeedMode.MNAI1
        }
        val random = java.security.SecureRandom()
        fun feed(key: ByteArray?) = key?.let {
            PhoneFeed(it, nowNanos = SystemClock::elapsedRealtimeNanos, random = { b -> random.nextBytes(b) }).also { _ -> it.fill(0) }
        }
        val set = LinkFeedSet(committed = feed(keys.committed), pending = feed(keys.pending))
        beforeInstallForTest?.invoke()
        val installed = install(gen, decided, set)
        if (installed) scheduleReread(context, gen, keys.pendingUnreadable)
        return installed
    }

    private val rereads = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Review P2: a PENDING that did not decrypt is read again after 5, 10, 20, 40, 80, 160 s (then
     * left for the next restart), each time through CarLink.changed so the screen and the feed
     * both follow. A readable read resets the count.
     */
    private fun scheduleReread(context: Context, gen: Int, unreadable: Boolean) {
        if (!unreadable) {
            rereads.set(0)
            return
        }
        val n = rereads.getAndIncrement()
        if (n >= MAX_REREADS) return
        val wait = rereadBaseMs shl n
        scope.launch {
            delay(wait)
            if (generation.get() == gen) com.kevin.midnightai.link.CarLink.changed(context)
        }
    }

    /** Installs this run's decision only if no newer start or stop happened meanwhile (N3). */
    @Synchronized
    private fun install(gen: Int, decided: com.kevin.midnightai.link.FeedMode, set: LinkFeedSet?): Boolean {
        if (gen != generation.get()) return false
        mode = decided
        feeds = set
        return true
    }

    private suspend fun CoroutineScope.helloLoop(context: Context, sock: DatagramSocket) {
        while (isActive) {
            val set = feeds
            val payloads = if (set != null) {
                synchronized(set) { set.hellos(LocationController.desiredIntervalMs) }
            } else {
                listOf(BeaconProtocol.encodeHello(LocationController.desiredIntervalMs))
            }
            for (payload in payloads) for (target in helloTargets(context)) {
                runCatching {
                    sock.send(DatagramPacket(payload, payload.size, target, BeaconProtocol.PORT))
                }.onFailure { Log.v(TAG, "HELLO to $target failed: ${it.message}") }
            }
            // Not joined to the hotspot yet, or no phone answering: both are normal
            // and self-correcting, so this stays a quiet retry rather than an error.
            val staleAfter = LocationController.desiredIntervalMs * LINK_STALE_FACTOR + LINK_GRACE_MS
            if (SystemClock.elapsedRealtime() - lastFixAtMs > staleAfter) {
                _linkUp.value = false
                // Fall back to searching again. A phone that changed IP on a DHCP
                // renew, or a different phone taking over the role, would otherwise
                // leave us unicasting forever at an address nobody answers.
                resolvedPeer = null
                _peerLabel.value = null
            }
            // MNAI2: a HELLO at the fix rate (spec 4.3), so a fix's conservative age stays small.
            delay(if (set != null) PhoneFeed.helloIntervalMs(LocationController.desiredIntervalMs) else HELLO_INTERVAL_MS)
        }
    }

    /**
     * Addresses worth sending this HELLO to, best first.
     *
     * Once the beacon has replied we know exactly where it is and send only there.
     * Before that, we shotgun: a typed address wins outright if set, otherwise the
     * gateway (right when the beacon phone is itself the hotspot) plus both
     * broadcast forms (right when it is a peer client, as on the iPhone-hotspot rig).
     */
    private fun helloTargets(context: Context): List<InetAddress> {
        resolvedPeer?.let { return listOf(it) }

        // The typed address is tried FIRST but never INSTEAD. Returning only it left
        // the link permanently stuck the moment it went wrong - a changed DHCP lease,
        // a swapped beacon phone, or a typo that happens to be a valid address - with
        // no recovery except the driver noticing "Waiting for a phone" never clears
        // and blanking the field by hand. Automatic discovery still runs alongside it,
        // so a wrong entry costs one wasted datagram per cycle instead of the feature.
        val manual = manualPeerAddress(context)

        return (listOfNotNull(manual) + listOfNotNull(
            gatewayAddress(context),
            subnetBroadcast(context),
            LIMITED_BROADCAST,
        )).distinct()
    }

    /**
     * Parses the typed address as a literal IPv4 dotted quad ONLY.
     *
     * Deliberately not `InetAddress.getByName`, which falls through to a DNS lookup
     * for anything that is not already a literal - and this runs inside the 5-second
     * HELLO loop. The Setup field persists on every keystroke, so a driver typing
     * "172.20.10.5" leaves "1", "172", "172.20" in preferences on the way, and a
     * hotspot with no reachable resolver would have blocked the loop on each of them
     * until the OS resolver timed out. A literal-only parse makes every one of those
     * partial values a free, instant null.
     */
    private fun manualPeerAddress(context: Context): InetAddress? {
        val typed = BeaconPreferences.manualPeer(context) ?: return null
        val parts = typed.split('.')
        if (parts.size != 4) return null
        val octets = ByteArray(4)
        for (i in 0..3) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value.toByte()
        }
        return runCatching { InetAddress.getByAddress(octets) }.getOrNull()
    }

    /**
     * The directed broadcast for this device's own subnet (e.g. 172.20.10.255 on an
     * iPhone hotspot). Some access points pass this while dropping the limited
     * 255.255.255.255 form, and some do the reverse, so both are tried.
     */
    @Suppress("DEPRECATION")
    private fun subnetBroadcast(context: Context): InetAddress? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcp = runCatching { wifi.dhcpInfo }.getOrNull() ?: return null
        if (dhcp.ipAddress == 0 || dhcp.netmask == 0) return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        return runCatching { InetAddress.getByAddress(littleEndianToBytes(broadcast)) }.getOrNull()
    }

    private suspend fun receiveLoop(context: Context, sock: DatagramSocket) {
        val buffer = ByteArray(maxOf(BeaconProtocol.MAX_PACKET_BYTES, PhoneLinkWire.MAX_PACKET_BYTES))
        while (!sock.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (sock.isClosed) return
                // Review L10: a receive error is not the end of the link; back off and go on.
                Log.w(TAG, "Beacon receive failed", e)
                delay(1_000)
                continue
            }
            // One bad datagram must never end the loop (review L10).
            val reply = runCatching { handle(context, packet) }
                .onFailure { Log.w(TAG, "Beacon datagram dropped", it) }.getOrNull()
            // Rev 9: the only datagram this unit sends in reply to one. Sent from here, not from
            // inside handle(), so the send path stays in one place beside the HELLO loop's.
            if (reply != null) {
                runCatching {
                    sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                }.onFailure { Log.v(TAG, "TOKEN_OK send failed: ${it.message}") }
            }
        }
    }

    /** Tests only: a hook inside the startup step (to make it slow), and a datagram handed in directly. */
    @Volatile internal var beforeInstallForTest: (() -> Unit)? = null

    /** First wait before re-reading an unreadable PENDING (review P2); doubled per try. */
    @Volatile internal var rereadBaseMs = 5_000L
    private const val MAX_REREADS = 6

    internal fun handleForTest(context: Context, data: ByteArray): ByteArray? =
        handle(context, DatagramPacket(data, data.size, java.net.InetAddress.getLoopbackAddress(), BeaconProtocol.PORT))

    /** What to send straight back, if anything (rev 9's TOKEN_OK and nothing else). */
    private fun handle(context: Context, packet: DatagramPacket): ByteArray? {
        var reply: ByteArray? = null
        run {
            val set = feeds
            if (set != null) {
                // Paired: authenticated MNAI2 only. A refusal is dropped silently (anyone on the
                // hotspot can send this port anything; nothing about it is logged).
                val result = synchronized(set) { set.receive(packet.data, packet.length) }
                when (val out = result.outcome) {
                    is PhoneFeed.Outcome.Fix -> {
                        notePeer(packet.address)
                        LocationController.acceptPhone(out.fix, out.fixNanos)
                    }
                    is PhoneFeed.Outcome.Zone -> {
                        notePeer(packet.address)
                        com.kevin.midnightai.time.TrustedClock.acceptPhoneZone(out.zoneId)
                    }
                    PhoneFeed.Outcome.Revoke -> scope.launch {
                        com.kevin.midnightai.link.CarLink.store.forget()
                        com.kevin.midnightai.link.CarLink.changed(context)
                    }
                    is PhoneFeed.Outcome.Credential -> {
                        notePeer(packet.address)
                        // A credential that arrived under the PENDING key is not acted on: the
                        // commit below would overwrite the very token an answer would be about.
                        if (!result.underPending) {
                            reply = keepHandedToken(context, set, out)
                        }
                    }
                    is PhoneFeed.Outcome.Rejected -> Unit
                }
                // Once per feed set (LinkFeedSet), single-flight, and the receiver is rebuilt only when
                // the commit actually happened (review N5): a failing Keystore does not restart the
                // receiver on every fix.
                if (result.commitPending && committing.compareAndSet(false, true)) scope.launch {
                    try {
                        val error = runCatching { com.kevin.midnightai.link.CarLink.store.commitPending() }.getOrElse { "commit threw" }
                        if (error == null) {
                            com.kevin.midnightai.link.CarLink.changed(context)
                        } else {
                            // Not committed: no receiver restart, and a later datagram retries.
                            synchronized(set) { set.commitFailed() }
                        }
                    } finally {
                        committing.set(false)
                    }
                }
                if (result.dropPending) scope.launch {
                    com.kevin.midnightai.link.CarLink.store.dropPending()
                    com.kevin.midnightai.link.CarLink.changed(context)
                }
                return reply
            }
            if (mode != com.kevin.midnightai.link.FeedMode.MNAI1) return null
            // The phone's zone (trusted clock, 2026-10-13): same pinning rule as a fix, and
            // only from the beacon this unit already follows.
            BeaconProtocol.decodeZone(packet.data, packet.length)?.let { zoneId ->
                val pinnedPeer = manualPeerAddress(context)
                val fromBeacon = if (pinnedPeer != null) packet.address == pinnedPeer else packet.address == resolvedPeer
                if (fromBeacon) com.kevin.midnightai.time.TrustedClock.acceptPhoneZone(zoneId)
                return null
            }

            // A hostile or malformed datagram from anyone else on the hotspot decodes
            // to null and is dropped here, before it can reach location state.
            val fix = BeaconProtocol.decodeFix(packet.data, packet.length) ?: return null

            // A typed address PINS the beacon: anything else on the hotspot is
            // ignored outright. Without this, any device running PHONE role - a
            // second household phone, an old test handset left in a drawer and
            // powered on - can answer a broadcast HELLO and take over the head
            // unit's position feed, and the driver's only clue is an IP address on a
            // settings screen. When no address is typed we accept the first
            // answerer, which is the whole point of auto-discovery.
            val pinned = manualPeerAddress(context)
            if (pinned != null && packet.address != pinned) return null

            // Learn where the beacon actually is from its first valid reply, and
            // stop broadcasting at the whole subnet from here on.
            if (resolvedPeer != packet.address) {
                resolvedPeer = packet.address
                _peerLabel.value = packet.address?.hostAddress
            }
            lastFixAtMs = SystemClock.elapsedRealtime()
            _linkUp.value = true
            LocationController.acceptExternal(fix)
        }
        return reply
    }

    /**
     * Stores a handed-over engine sign-in (rev 9) and answers with the fingerprint of what was
     * stored. Null (no answer) when the write did not happen, so the phone keeps the credential the
     * car is using and hands this one over again at the next contact. On the IO thread the receive
     * loop runs on: the write decrypts and commits.
     */
    private fun keepHandedToken(context: Context, set: LinkFeedSet, out: PhoneFeed.Outcome.Credential): ByteArray? {
        val stored = com.kevin.midnightai.link.CarLink.store.acceptHandedToken(out.token, out.vehicleId)
        if (!stored) return null
        com.kevin.midnightai.link.CarLink.changed(context)
        return synchronized(set) { set.tokenOk(PhoneLinkWire.fingerprint(out.token)) }
    }

    /**
     * An authenticated datagram arrived from [address]: the link is up. The unicast target moves
     * only after [PEER_CONFIRMATIONS] accepted datagrams in a row from the same new address, so an
     * in-path attacker re-sending one captured datagram from its own address cannot steer the
     * HELLOs away from the phone (review L8).
     */
    private fun notePeer(address: InetAddress?) {
        if (address != null && resolvedPeer != address) {
            if (candidatePeer == address) candidateCount++ else { candidatePeer = address; candidateCount = 1 }
            if (resolvedPeer == null || candidateCount >= PEER_CONFIRMATIONS) {
                resolvedPeer = address
                _peerLabel.value = address.hostAddress
                candidatePeer = null
                candidateCount = 0
            }
        } else {
            candidatePeer = null
            candidateCount = 0
        }
        lastFixAtMs = SystemClock.elapsedRealtime()
        _linkUp.value = true
    }

    private const val PEER_CONFIRMATIONS = 2

    /**
     * The phone's address on the hotspot: this device's default gateway.
     *
     * `dhcpInfo` is deprecated on modern Android but is the one call that answers
     * this in a single line, and the target here is an AOSP 8-10 head unit where it
     * is fully supported. [linkPropertiesGateway] covers newer devices (and the
     * phone-as-head-unit case) where it can come back empty.
     */
    private fun gatewayAddress(context: Context): InetAddress? =
        dhcpGateway(context) ?: linkPropertiesGateway(context)

    @Suppress("DEPRECATION")
    private fun dhcpGateway(context: Context): InetAddress? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val gw = runCatching { wifi.dhcpInfo?.gateway }.getOrNull() ?: return null
        if (gw == 0) return null
        return runCatching { InetAddress.getByAddress(littleEndianToBytes(gw)) }.getOrNull()
    }

    /**
     * `DhcpInfo` packs addresses little-endian, the same order
     * `android.text.format.Formatter.formatIpAddress` unpacks them in.
     */
    private fun littleEndianToBytes(address: Int): ByteArray = byteArrayOf(
        (address and 0xff).toByte(),
        (address shr 8 and 0xff).toByte(),
        (address shr 16 and 0xff).toByte(),
        (address shr 24 and 0xff).toByte(),
    )

    private fun linkPropertiesGateway(context: Context): InetAddress? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(network) ?: return null
        return props.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway
    }
}
