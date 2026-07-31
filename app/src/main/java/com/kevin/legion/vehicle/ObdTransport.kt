package com.kevin.legion.vehicle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * A connected byte-stream link to an OBD-II adapter, abstracting over the
 * physical transport - a real Bluetooth RFCOMM link ([RfcommTransport]), or a
 * TCP socket to an ELM327 emulator for development without a car
 * ([TcpTransport]). [ObdBluetoothManager]'s command-send/response-parse logic
 * (sendCommand/drainInput/readUntilPrompt) only ever touches
 * inputStream/outputStream, so it doesn't know or care which one it's talking
 * to - this seam is what makes the emulator harness possible without
 * duplicating any protocol logic.
 */
interface ObdTransport {
    val inputStream: InputStream
    val outputStream: OutputStream

    /** Human-readable identity for logs/registry - a MAC for Bluetooth, host:port for TCP. */
    val label: String

    fun close()
}

/**
 * Real adapter over Bluetooth RFCOMM (Serial Port Profile). Cheap ELM327 clones
 * are wildly inconsistent about which socket type they accept: some only answer
 * the standard SDP lookup ([BluetoothDevice.createRfcommSocketToServiceRecord]),
 * many reject the *secure* variant outright (they never negotiate encryption)
 * and only take an *insecure* socket, and some don't implement SDP at all and
 * need the well-known "channel 1" socket obtained by reflection. So instead of
 * a single attempt we walk a ladder of strategies and return the first that
 * connects - this is the difference between "dongle shows in Bluetooth settings
 * but the app won't connect" and a working link on clone hardware.
 */
class RfcommTransport private constructor(
    private val socket: BluetoothSocket,
    override val label: String,
) : ObdTransport {
    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream

    override fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing RFCOMM socket: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RfcommTransport"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Connects using the first strategy that works. [adapter] is used only to
         * cancel any in-flight discovery first: discovery is a heavyweight radio
         * operation that will slow or outright fail an RFCOMM connect (Android's
         * own docs say to always cancel it before connecting), and on a head unit
         * the system Bluetooth settings' own scan can easily be running.
         */
        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice, adapter: BluetoothAdapter?): RfcommTransport {
            if (adapter?.isDiscovering == true) {
                Log.d(TAG, "Cancelling in-flight discovery before connect")
                adapter.cancelDiscovery()
            }

            // Order matters: try the standard secure socket first (correct for
            // genuine adapters), then insecure (the fix for most clones), then
            // the reflected channel-1 sockets for adapters with broken SDP.
            val strategies: List<Pair<String, () -> BluetoothSocket>> = listOf(
                "secure SDP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                "insecure SDP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                "insecure ch1" to { reflectChannelSocket(device, "createInsecureRfcommSocket") },
                "secure ch1" to { reflectChannelSocket(device, "createRfcommSocket") },
            )

            var lastError: Exception? = null
            for ((name, makeSocket) in strategies) {
                val socket = try {
                    makeSocket()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not create $name socket: ${e.message}")
                    lastError = e
                    continue
                }
                try {
                    socket.connect()
                    Log.d(TAG, "Connected to ${device.address} via $name")
                    return RfcommTransport(socket, device.address)
                } catch (e: IOException) {
                    Log.w(TAG, "$name connect failed: ${e.message}")
                    lastError = e
                    try {
                        socket.close()
                    } catch (ignored: IOException) {
                    }
                }
            }
            throw lastError as? IOException
                ?: IOException("All RFCOMM strategies failed: ${lastError?.message}")
        }

        /** Reflected `create[Insecure]RfcommSocket(1)` for adapters with broken SDP. */
        private fun reflectChannelSocket(device: BluetoothDevice, methodName: String): BluetoothSocket {
            return device.javaClass
                .getMethod(methodName, Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
        }
    }
}

/**
 * A TCP connection to an ELM327 emulator (e.g. Ircama's Python ELM327-emulator
 * on GitHub) - for testing OBD parsing/PID/tool-call code without a car.
 * `10.0.2.2` is the Android emulator's alias for the host machine's loopback,
 * so this reaches an emulator process running on the dev machine while the
 * app runs in an AVD. Debug-gated (see [com.kevin.legion.service.DebugSettings]
 * .obdEmulatorEnabled) so it's never reachable in a release build.
 */
class TcpTransport private constructor(
    private val socket: Socket,
    override val label: String,
) : ObdTransport {
    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()

    override fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing TCP socket: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TcpTransport"
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 35000

        fun connect(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT, timeoutMs: Int = 5000): TcpTransport {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return TcpTransport(socket, "$host:$port")
        }
    }
}
