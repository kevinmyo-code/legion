// Guard clauses: the document says "refuse otherwise" at each check, and one early return per
// refusal reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink

import android.app.Application
import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.kevin.legion.carlink.protocol.FixSample
import com.kevin.legion.carlink.protocol.Frames
import com.kevin.legion.carlink.protocol.Mnai2
import com.kevin.legion.carlink.protocol.Mnai2Feed
import com.kevin.legion.carlink.protocol.PairingConnection
import com.kevin.legion.carlink.protocol.PairingRunner
import com.kevin.legion.carlink.protocol.PairingTransport
import com.kevin.legion.carlink.protocol.PhoneFix
import com.kevin.legion.carlink.protocol.Received
import com.kevin.legion.location.LocationController
import com.kevin.legion.service.phonelog.LogcatRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.TimeZone
import kotlin.coroutines.coroutineContext

/**
 * The car link's object graph, built by hand because Hilt is not in the build yet (the same shape as
 * [com.kevin.legion.dumb.DumbModeGraph]); `MidnightApplication.carLink` is the injection point.
 */
class CarLinkGraph(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val store = CarLinkStore(KeystoreSecretBox(), PrefsRecordFile(app), LogcatRedactor::addLiteral)

    val controller: CarLinkController by lazy {
        CarLinkController(
            store = store,
            transport = TcpPairingTransport(),
            handover = HandoverPlan.forCar(
                store = store,
                tokens = UnitTokenNotYetAvailable(),
                phoneName = { Build.MODEL },
                redact = LogcatRedactor::addLiteral,
            ),
            newSender = { ring, onEvent, sendFixes, mode ->
                val revokeOnly = mode.revokeOnly
                // Only HELLOs from inside the network the phone paired on count (4.3, rev 5).
                val net = mode.healOn ?: store.pairingNetwork()
                val own = PairingNetwork.systemInterfaces()
                UdpPort.open().let { port ->
                    CarLinkSender(
                        feed = Mnai2Feed(
                            ring = ring,
                            newSession = ::newSession,
                            onLan = { net?.acceptsHelloFrom(it, own) == true },
                            // A revoke-only listener sends nothing but REVOKE: no zone.
                            // A revoke-only or healing listener sends no zone.
                            zoneId = { if (revokeOnly || mode.healOn != null) null else TimeZone.getDefault().id },
                            onRingEvent = onEvent,
                        ),
                        port = port,
                        clock = SystemClock::elapsedRealtime,
                        fixes = LocationFixSource(app),
                        sendFixes = sendFixes,
                    )
                }
            },
            scope = scope,
            io = Dispatchers.IO,
            jitterMs = { PairingRunner.jitter(kotlin.random.Random.Default) },
            sleep = { delay(it) },
            network = { store.pairingNetwork()?.look(PairingNetwork.systemInterfaces()) ?: NetworkLook.Absent },
            ownHotspotNow = ::isTethered,
            recordNetwork = { host ->
                val net = PairingNetwork.recordFor(host, PairingNetwork.systemInterfaces(), ::isTethered)
                store.setPairingNetwork(net) && net != null
            },
        )
    }

    private val random = SecureRandom()

    /** This phone's own hotspot: an up interface that no Android Network carries (tethering). */
    @Suppress("DEPRECATION") // allNetworks: the one call that lists every network on API 24+
    private fun isTethered(name: String): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.none { cm.getLinkProperties(it)?.interfaceName == name }
    }

    private fun newSession(): ByteArray = ByteArray(Mnai2.SESSION_LEN).also(random::nextBytes)
}

/**
 * UDP on [Mnai2.PORT]. Bound to all local addresses on purpose: the unit's first HELLOs are broadcasts,
 * and on Linux a socket bound to one unicast address never receives a broadcast. It exists only
 * while the pairing network is present, and the feed drops every source outside that network's
 * subnet (rev 5, 4.3). Null when the port cannot be bound.
 */
class UdpPort private constructor(private val socket: DatagramSocket) : DatagramPort {
    override fun receive(buf: ByteArray, timeoutMs: Int): Incoming? = try {
        socket.soTimeout = timeoutMs
        val packet = DatagramPacket(buf, buf.size)
        socket.receive(packet)
        Incoming(packet.length, packet.address, packet.port)
    } catch (_: SocketTimeoutException) {
        null
    }

    override fun send(bytes: ByteArray, to: InetAddress, port: Int) {
        socket.send(DatagramPacket(bytes, bytes.size, to, port))
    }

    override fun close() = socket.close()

    companion object {
        /**
         * Never shared: SO_REUSEADDR stays off, so another app cannot co-bind 17311 and take the HELLOs.
         * A taken port throws [java.net.BindException]; there is no fallback port.
         */
        fun open(port: Int = Mnai2.PORT): UdpPort {
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(port))
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return UdpPort(socket)
        }
    }
}

/** The phone's fixes from [LocationController], timed on the elapsed-realtime clock (never wall time). */
class LocationFixSource(private val app: Application) : FixSource {
    override fun latest(): FixSample? {
        val l: Location = LocationController.state.value ?: return null
        return FixSample(l.elapsedRealtimeNanos / NANOS_PER_MS, toFix(l))
    }

    override fun holdFast(on: Boolean) {
        if (on) {
            LocationController.init(app)
            LocationController.startFastMode(app)
        } else {
            LocationController.stopFastMode()
        }
    }

    private fun toFix(l: Location): PhoneFix {
        val newer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        return PhoneFix(
            lat = l.latitude,
            lng = l.longitude,
            ageMs = 0,
            accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            bearingDeg = if (l.hasBearing()) l.bearing else null,
            speedMps = if (l.hasSpeed()) l.speed else null,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            bearingAccDeg = if (newer && l.hasBearingAccuracy()) l.bearingAccuracyDegrees else null,
            speedAccMps = if (newer && l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
        )
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** TCP to the unit's pairing listener (3.2). Reads in short slices so a cancel takes effect quickly. */
class TcpPairingTransport : PairingTransport {
    override suspend fun connect(host: String, port: Int): PairingConnection? = try {
        TcpConnection(Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) })
    } catch (_: IOException) {
        null
    }

    private class TcpConnection(private val socket: Socket) : PairingConnection {
        private val reader = Frames.FrameReader()
        private val pending = ArrayDeque<String>()
        private val buf = ByteArray(READ_BUF)

        override suspend fun send(frame: ByteArray) {
            socket.getOutputStream().apply { write(frame); flush() }
        }

        override suspend fun receive(timeoutMs: Long): Received {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (pending.isEmpty()) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) return Received.TimedOut
                socket.soTimeout = SLICE_MS
                val n = try {
                    socket.getInputStream().read(buf)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (n < 0) return Received.Closed
                pending.addAll(reader.feed(buf, n))
            }
            return Received.Message(pending.removeFirst())
        }

        override fun close() = socket.close()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val SLICE_MS = 250
        const val READ_BUF = 4096
    }
}
