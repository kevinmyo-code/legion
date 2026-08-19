package com.kevin.legion.vehicle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A connected byte-stream link to a BLE ("for iPhone") ELM327 clone - the
 * HM-10-style GATT serial modules that never bond (no PIN, no SDP record), so
 * [RfcommTransport] can never see them. This is [ObdTransport]'s third
 * implementation: it terminates in the same inputStream/outputStream seam, so
 * everything above it (Elm327Io's exchange/drain/readUntilPrompt,
 * ObdBluetoothManager's handshake) is unaware which radio it's talking to.
 *
 * BLE serial dongles don't standardize a service/characteristic layout the way
 * RFCOMM's Serial Port Profile does, so [connect] walks a candidate-profile
 * ladder (HM-10, Viecar/Vgate, Nordic UART) before falling back to a generic
 * "first NOTIFY char in, first WRITE char out" scan. Every discovered
 * service/characteristic is logged at connect time (`Log.d`) so an
 * unrecognized dongle is diagnosable from Crashlytics breadcrumbs - ADB
 * logcat is blocked on the head unit (see CLAUDE.md sec 14).
 */
class BleTransport private constructor(
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    private val gattInput: GattInputStream,
    private val callback: ObdGattCallback,
    maxWrite: Int,
    override val label: String,
) : ObdTransport {
    override val inputStream: InputStream = gattInput
    override val outputStream: OutputStream = GattOutputStream(gatt, writeChar, callback, maxWrite)

    /**
     * Immediate teardown - we don't await the disconnect callback, matching
     * [RfcommTransport.close]'s "best effort, don't hang the caller" shape.
     */
    @SuppressLint("MissingPermission")
    override fun close() {
        try {
            gattInput.shutdown()
            gatt.disconnect()
            gatt.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing BLE GATT: ${e.message}")
        }
    }

    /**
     * Thread-safe byte buffer fed from [BluetoothGattCallback.onCharacteristicChanged].
     * [Elm327Io] only ever reads after `available() > 0` and never blocks, so
     * this needs no wait()/notify() machinery - just a locked deque.
     */
    private class GattInputStream : InputStream() {
        private val lock = Object()
        private val buffer = ArrayDeque<Byte>()
        @Volatile private var closed = false

        fun feed(bytes: ByteArray) {
            synchronized(lock) {
                for (b in bytes) buffer.addLast(b)
            }
        }

        /** Marks the stream ended (e.g. on an unexpected GATT disconnect). */
        fun shutdown() {
            closed = true
        }

        // WIRED 2026-08-16. `closed` was written by shutdown() and read by NOTHING, so after an
        // unexpected GATT disconnect this stream reported "no bytes right now" - indistinguishable
        // from a car that simply had not answered yet. That is the BLE half of the quiet-link defect
        // android-auto ticket 13 describes on the RFCOMM side, and the field existing unread was
        // evidence of an intended check rather than dead code, so it is used rather than removed.
        //
        // Draining first is deliberate: bytes already buffered when the link dropped are real and
        // are still handed over. Only once the buffer is empty does a closed stream report EOF.
        override fun available(): Int = synchronized(lock) { buffer.size }

        override fun read(b: ByteArray): Int = synchronized(lock) {
            if (buffer.isEmpty()) return@synchronized if (closed) -1 else 0
            val n = minOf(b.size, buffer.size)
            for (i in 0 until n) b[i] = buffer.removeFirst()
            n
        }

        override fun read(): Int = synchronized(lock) {
            if (buffer.isEmpty()) -1 else buffer.removeFirst().toInt() and 0xFF
        }
    }

    /**
     * Writes chunked to the negotiated MTU. ELM327 commands are tiny
     * ("0100\r", "ATRV\r") so this is almost always a single chunk; chunking
     * exists only for correctness if a longer write ever happens. A
     * write-capable characteristic (PROPERTY_WRITE) is acked via
     * onCharacteristicWrite and awaited before the next chunk (ordered,
     * reliable); a WRITE_NO_RESPONSE-only characteristic gets no ack, so we
     * just pace with a short sleep between chunks.
     */
    private class GattOutputStream(
        private val gatt: BluetoothGatt,
        private val writeChar: BluetoothGattCharacteristic,
        private val callback: ObdGattCallback,
        private val maxWrite: Int,
    ) : OutputStream() {
        @SuppressLint("MissingPermission")
        override fun write(bytes: ByteArray) {
            val useResponse = writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + maxWrite, bytes.size)
                val chunk = bytes.copyOfRange(offset, end)
                @Suppress("DEPRECATION")
                writeChar.value = chunk
                @Suppress("DEPRECATION")
                writeChar.writeType = if (useResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                val latch = CountDownLatch(1)
                callback.writeLatchRef.set(latch)
                @Suppress("DEPRECATION")
                val sent = gatt.writeCharacteristic(writeChar)
                if (!sent) throw IOException("writeCharacteristic() returned false")
                if (useResponse) {
                    latch.await(2000, TimeUnit.MILLISECONDS)
                } else {
                    Thread.sleep(10)
                }
                offset = end
            }
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun flush() {
            // No-op: write() already pushes each chunk to the radio
            // synchronously (awaiting the ack for WRITE_TYPE_DEFAULT chunks).
        }
    }

    /**
     * Single callback instance for one [connect] attempt. Each connect phase
     * (link up, MTU, service discovery, CCCD write) gets its own one-shot
     * latch; [writeLatchRef] is swapped per outbound chunk since a transport
     * instance sends many commands over its lifetime, while the others are
     * only ever awaited once during [connect].
     */
    private class ObdGattCallback(private val gattInput: GattInputStream) : BluetoothGattCallback() {
        @Volatile var lastStatus: Int = BluetoothGatt.GATT_SUCCESS
        @Volatile var negotiatedMtu: Int = 23
        val connectLatch = CountDownLatch(1)
        val mtuLatch = CountDownLatch(1)
        val servicesLatch = CountDownLatch(1)
        val descriptorLatch = CountDownLatch(1)
        val writeLatchRef = AtomicReference(CountDownLatch(0))

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            lastStatus = status
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectLatch.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Unblock a stuck connect wait, and end the input stream so any
                // in-flight sendCommand sees end-of-stream rather than hanging
                // forever. ObdBluetoothManager's sendCommand catches the
                // resulting IOException/empty-response and tears the
                // connection down through its own path - we never call back
                // into it directly.
                connectLatch.countDown()
                gattInput.shutdown()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            mtuLatch.countDown()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            lastStatus = status
            Log.d(TAG, "onServicesDiscovered status=$status")
            servicesLatch.countDown()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite status=$status")
            descriptorLatch.countDown()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeLatchRef.get().countDown()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            gattInput.feed(value)
        }
    }

    companion object {
        private const val TAG = "BleTransport"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** A serial profile candidate: one GATT service plus its notify/write characteristic UUIDs. */
        private data class CandidateProfile(val service: UUID, val notify: UUID, val write: UUID)

        // Checked in order; the first candidate whose service AND both
        // characteristics are present wins. See connect()'s KDoc.
        private val CANDIDATE_PROFILES = listOf(
            // HM-10 (dominant on cheap ELM327 clones): one characteristic
            // serves as both notify and write.
            CandidateProfile(
                service = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
                notify = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
                write = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
            ),
            // Viecar/Vgate style: separate notify and write characteristics.
            CandidateProfile(
                service = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"),
                notify = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB"),
                write = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB"),
            ),
            // Nordic UART Service (NUS).
            CandidateProfile(
                service = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                notify = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
                write = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            ),
        )

        /**
         * Connects to a BLE serial ELM327 clone over GATT. Runs on the
         * caller's Dispatchers.IO thread and blocks on latches through each
         * connect phase (link up, MTU, discovery, CCCD) rather than a
         * suspend/callback bridge, matching [RfcommTransport.connect]'s
         * "throws IOException on failure" contract.
         */
        @SuppressLint("MissingPermission")
        fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long = 12_000): BleTransport {
            val gattInput = GattInputStream()
            val callback = ObdGattCallback(gattInput)
            // TRANSPORT_LE is required - a dual-mode device otherwise may
            // pick BR/EDR (classic) and defeat the entire point of this class.
            val gatt = device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("connectGatt returned null for ${device.address}")

            try {
                if (!callback.connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS) ||
                    callback.lastStatus != BluetoothGatt.GATT_SUCCESS
                ) {
                    throw IOException("BLE connect failed/timed out for ${device.address} (status=${callback.lastStatus})")
                }

                // Best-effort MTU bump so multi-frame responses (VIN, mode-02
                // freeze frames) chunk less. Not every clone honors this -
                // fall back to the default 23-byte MTU (20-byte payload) on
                // failure or timeout.
                var negotiatedMtu = 23
                if (gatt.requestMtu(517) && callback.mtuLatch.await(3000, TimeUnit.MILLISECONDS)) {
                    negotiatedMtu = callback.negotiatedMtu
                }
                val maxWrite = maxOf(negotiatedMtu - 3, 20)

                if (!gatt.discoverServices()) {
                    throw IOException("discoverServices() returned false for ${device.address}")
                }
                if (!callback.servicesLatch.await(5000, TimeUnit.MILLISECONDS) ||
                    callback.lastStatus != BluetoothGatt.GATT_SUCCESS
                ) {
                    throw IOException("Service discovery failed/timed out for ${device.address}")
                }

                logDiscoveredGatt(gatt, device.address)
                val resolved = resolveSerialChars(gatt)
                    ?: throw IOException("No serial GATT characteristic found on ${device.address}")
                val (notifyChar, writeChar) = resolved
                Log.d(TAG, "Using notify=${notifyChar.uuid} write=${writeChar.uuid} for ${device.address}")

                gatt.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val enableValue = if (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    }
                    @Suppress("DEPRECATION")
                    cccd.value = enableValue
                    @Suppress("DEPRECATION")
                    val wroteDescriptor = gatt.writeDescriptor(cccd)
                    if (wroteDescriptor) {
                        if (!callback.descriptorLatch.await(3000, TimeUnit.MILLISECONDS)) {
                            Log.w(TAG, "CCCD write timed out for ${device.address}; some clones notify without honoring it")
                        }
                    } else {
                        Log.w(TAG, "writeDescriptor() returned false for ${device.address}")
                    }
                } else {
                    Log.w(TAG, "No CCCD descriptor on ${notifyChar.uuid} for ${device.address}; relying on setCharacteristicNotification alone")
                }

                return BleTransport(gatt, writeChar, gattInput, callback, maxWrite, device.address)
            } catch (e: Exception) {
                try {
                    gatt.close()
                } catch (ignored: Exception) {
                }
                gattInput.shutdown()
                throw if (e is IOException) e else IOException("BLE connect failed for ${device.address}: ${e.message}", e)
            }
        }

        /** Logs every discovered service/characteristic UUID + properties for diagnosing an unrecognized dongle. */
        @SuppressLint("MissingPermission")
        private fun logDiscoveredGatt(gatt: BluetoothGatt, address: String) {
            for (service in gatt.services) {
                Log.d(TAG, "[$address] service ${service.uuid}")
                for (c in service.characteristics) {
                    Log.d(TAG, "[$address]   characteristic ${c.uuid} properties=${c.properties}")
                }
            }
        }

        /**
         * Walks [CANDIDATE_PROFILES] in order, then falls back to a generic
         * scan (first NOTIFY characteristic as input, first WRITE/
         * WRITE_NO_RESPONSE characteristic as output - possibly the same
         * characteristic) across every discovered service. Returns null if
         * nothing usable is found.
         */
        private fun resolveSerialChars(gatt: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            for (profile in CANDIDATE_PROFILES) {
                val service = gatt.getService(profile.service) ?: continue
                val notifyChar = service.getCharacteristic(profile.notify)?.takeIf { hasNotify(it) }
                val writeChar = service.getCharacteristic(profile.write)?.takeIf { hasWrite(it) }
                if (notifyChar != null && writeChar != null) return notifyChar to writeChar
            }

            var notifyChar: BluetoothGattCharacteristic? = null
            var writeChar: BluetoothGattCharacteristic? = null
            for (service in gatt.services) {
                for (c in service.characteristics) {
                    if (notifyChar == null && hasNotify(c)) notifyChar = c
                    if (writeChar == null && hasWrite(c)) writeChar = c
                }
            }
            return if (notifyChar != null && writeChar != null) notifyChar to writeChar else null
        }

        private fun hasNotify(c: BluetoothGattCharacteristic): Boolean =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        private fun hasWrite(c: BluetoothGattCharacteristic): Boolean =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }
}
