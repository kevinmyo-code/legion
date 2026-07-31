package com.kevin.legion.location

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Phone end of the GPS beacon link: answers the head unit's HELLOs with this
 * device's live position. See [com.kevin.legion.service.DeviceRole] for why.
 *
 * Nothing here has to find the head unit - it replies to whatever address the HELLO
 * arrived from, which is how the link needs no configuration on either side.
 *
 * **Sampling is driven by the head unit, not by us.** The requested interval rides
 * in each HELLO, and a fast request switches this device's own
 * [LocationController] into nav mode, so the phone only burns 1 Hz GPS while the
 * car is actually navigating. When HELLOs stop for [PEER_TIMEOUT_MS] the peer is
 * dropped and nav mode is released - which means cutting the ignition, walking out
 * of range, or force-stopping the head unit all stop the phone sampling, without
 * any of them needing to deliver a shutdown message first.
 */
object BeaconServer {
    private const val TAG = "BeaconServer"

    /** No HELLO for this long and the head unit is gone. ~3 missed announcements. */
    private const val PEER_TIMEOUT_MS = 16_000L

    /** A request at or under this rate means "navigating" - worth the fast GPS. */
    private const val FAST_REQUEST_MS = 5_000L

    private const val RECEIVE_TIMEOUT_MS = 10_000

    private var job: Job? = null
    @Volatile private var socket: DatagramSocket? = null

    @Volatile private var peerAddress: InetAddress? = null
    @Volatile private var peerPort: Int = 0
    @Volatile private var peerLastHelloMs = 0L
    @Volatile private var requestedIntervalMs = 30_000L

    /** Whether we currently hold one of [LocationController]'s nav-mode references. */
    @Volatile private var holdsNavMode = false

    /** Interface enumeration is throttled to this; see the call site. */
    private const val ADDRESS_REFRESH_MS = 15_000L
    @Volatile private var lastAddressReadMs = 0L

    private val _serving = MutableStateFlow(false)

    /** True while a head unit is actively asking for fixes - shown on the Setup screen. */
    val serving: StateFlow<Boolean> = _serving.asStateFlow()

    private val _localAddress = MutableStateFlow<String?>(null)

    /**
     * This phone's address on the hotspot, shown on its own Setup screen.
     *
     * Purely so the driver can read it and type it into the head unit if broadcast
     * discovery does not survive the access point (see [BeaconClient]'s doc on
     * client isolation). Without this there is no way to find it out - the head unit
     * has no shell and ADB is blocked (CLAUDE.md §14).
     */
    val localAddress: StateFlow<String?> = _localAddress.asStateFlow()

    /**
     * Picks this device's IPv4 address on the WiFi interface by walking the network
     * interfaces rather than reading `WifiManager`: on a device joined to someone
     * else's hotspot, the interface list is the thing that is actually true, and it
     * does not need the deprecated DHCP surface.
     *
     * **WiFi interfaces are preferred explicitly, not taken in enumeration order.**
     * A beacon phone can easily have mobile data, a VPN or USB tethering up
     * alongside the hotspot connection (`rmnet*`, `tun*`, `usb*`), and enumeration
     * order is the OS's business, not ours. Showing a carrier-NAT or VPN address
     * here would be worse than showing nothing: the driver would type it into the
     * head unit and it could never work, with no way to find out why. Non-WiFi is a
     * last resort only.
     */
    private fun readLocalAddress(): String? = runCatching {
        val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
        val ipv4Of = { iface: java.net.NetworkInterface ->
            iface.inetAddresses.toList().firstOrNull {
                !it.isLoopbackAddress && !it.isLinkLocalAddress && it is java.net.Inet4Address
            }?.hostAddress
        }
        candidates.filter { it.name.startsWith("wlan") }.firstNotNullOfOrNull(ipv4Of)
            ?: candidates.firstNotNullOfOrNull(ipv4Of)
    }.getOrNull()

    /**
     * Binds synchronously before launching, for the reason spelled out on
     * [BeaconClient.start] - and it matters more on this end, because this socket
     * holds a FIXED port. A leaked receive loop here keeps 17311 bound forever, so
     * the next `start()` fails with a BindException that is swallowed into a log
     * line nobody can read (ADB is blocked, CLAUDE.md §14) and the Setup screen sits
     * on "Ready" while serving nothing until the process is killed.
     *
     * SO_REUSEADDR covers the other half: a socket closed cleanly can still hold the
     * port in TIME_WAIT briefly, which is exactly the window a driver toggling the
     * role off and back on to test it lands in.
     */
    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        LocationController.init(appContext)

        val sock = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(BeaconProtocol.PORT))
            }
        }.getOrElse {
            Log.w(TAG, "Could not bind beacon port ${BeaconProtocol.PORT}", it)
            return
        }
        sock.soTimeout = RECEIVE_TIMEOUT_MS
        socket = sock

        job = scope.launch(Dispatchers.IO) {
            val sender = launch { sendLoop(appContext, sock) }
            try {
                receiveLoop(sock)
            } finally {
                sender.cancel()
                runCatching { sock.close() }
                if (socket === sock) socket = null
                clearPeer(appContext)
            }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        clearPeer(context.applicationContext)
    }

    private fun receiveLoop(sock: DatagramSocket) {
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
            val interval = BeaconProtocol.decodeHello(packet.data, packet.length) ?: continue
            peerAddress = packet.address
            peerPort = packet.port
            requestedIntervalMs = interval
            peerLastHelloMs = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun CoroutineScope.sendLoop(context: Context, sock: DatagramSocket) {
        while (isActive) {
            // Refreshed periodically, not once at start: on a phone that boots before
            // the hotspot is up, the interface has no address yet when the service
            // starts and would otherwise display blank forever. Throttled rather than
            // run every pass, since the send loop ticks once a second in nav mode and
            // a native interface enumeration is not free on a Helio G35 - an address
            // that changes is a DHCP event, not a per-second one.
            val now = SystemClock.elapsedRealtime()
            if (now - lastAddressReadMs > ADDRESS_REFRESH_MS) {
                lastAddressReadMs = now
                _localAddress.value = readLocalAddress()
            }

            val address = peerAddress
            val fresh = SystemClock.elapsedRealtime() - peerLastHelloMs < PEER_TIMEOUT_MS
            if (address == null || !fresh) {
                clearPeer(context)
                // Idle poll: nothing to send, so wait a beat rather than spin. Latency
                // to first fix after a HELLO is bounded by this, not by the interval.
                delay(1_000L)
                continue
            }

            _serving.value = true
            applySamplingRate(context, requestedIntervalMs)

            LocationController.state.value?.let { location ->
                val payload = BeaconProtocol.encodeFix(location.toBeaconFix())
                runCatching {
                    sock.send(DatagramPacket(payload, payload.size, address, peerPort))
                }.onFailure { Log.v(TAG, "FIX send failed: ${it.message}") }
            }
            delay(requestedIntervalMs)
        }
    }

    /**
     * Holds AT MOST ONE nav-mode request. [LocationController.startNavMode] is
     * reference-counted, and this runs once per send-loop iteration - once a second
     * in nav mode - so calling it unconditionally would rack up thousands of holds
     * that a single [clearPeer] could never release, pinning the phone's GPS at 1 Hz
     * for the rest of the process's life.
     */
    private fun applySamplingRate(context: Context, intervalMs: Long) {
        val wantFast = intervalMs <= FAST_REQUEST_MS
        if (wantFast == holdsNavMode) return
        if (wantFast) LocationController.startNavMode(context) else LocationController.stopNavMode()
        holdsNavMode = wantFast
    }

    private fun clearPeer(context: Context) {
        peerAddress = null
        peerPort = 0
        _serving.value = false
        // Release the phone's fast GPS the moment nobody is listening - but only if
        // we actually took a reference, or we would decrement someone else's.
        if (holdsNavMode) {
            LocationController.stopNavMode()
            holdsNavMode = false
        }
    }

    /**
     * Optional accuracy/bearing/speed/altitude are sent only when the platform
     * actually has them - `Location`'s getters return 0 rather than null for absent
     * values, so reading them unconditionally would transmit a confident "0 m/s,
     * heading due north" that the head unit could not tell from a real reading.
     */
    private fun Location.toBeaconFix(): BeaconFix = BeaconFix(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null,
        bearingDeg = if (hasBearing()) bearing else null,
        speedMps = if (hasSpeed()) speed else null,
        altitudeM = if (hasAltitude()) altitude else null,
    )
}
