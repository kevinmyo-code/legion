package com.kevin.legion.location

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
 * [LocationController]. See [com.kevin.legion.service.DeviceRole] for why the
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

        job = scope.launch {
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
        job?.cancel()
        job = null
        // Closing is what actually stops the loops; cancel alone cannot reach a
        // thread parked in a blocking receive.
        runCatching { socket?.close() }
        socket = null
        lastFixAtMs = 0L
        resolvedPeer = null
        _peerLabel.value = null
        _linkUp.value = false
    }

    private suspend fun CoroutineScope.helloLoop(context: Context, sock: DatagramSocket) {
        while (isActive) {
            val payload = BeaconProtocol.encodeHello(LocationController.desiredIntervalMs)
            for (target in helloTargets(context)) {
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
            delay(HELLO_INTERVAL_MS)
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

    private fun receiveLoop(context: Context, sock: DatagramSocket) {
        val buffer = ByteArray(BeaconProtocol.MAX_PACKET_BYTES)
        while (!sock.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!sock.isClosed) Log.w(TAG, "Beacon receive failed", e)
                return
            }
            // A hostile or malformed datagram from anyone else on the hotspot decodes
            // to null and is dropped here, before it can reach location state.
            val fix = BeaconProtocol.decodeFix(packet.data, packet.length) ?: continue

            // A typed address PINS the beacon: anything else on the hotspot is
            // ignored outright. Without this, any device running PHONE role - a
            // second household phone, an old test handset left in a drawer and
            // powered on - can answer a broadcast HELLO and take over the head
            // unit's position feed, and the driver's only clue is an IP address on a
            // settings screen. When no address is typed we accept the first
            // answerer, which is the whole point of auto-discovery.
            val pinned = manualPeerAddress(context)
            if (pinned != null && packet.address != pinned) continue

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
    }

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
