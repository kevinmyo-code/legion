package com.kevin.legion.vehicle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds a Shelly `Switch.Set` RPC request frame: a big-endian uint32 byte
 * length prefix followed by the JSON-RPC 2.0 request body. `toggle_after: 1`
 * makes the relay auto-return after one second regardless of the device's own
 * configuration, which is what turns a plain "on" call into a momentary
 * button-press pulse - the only action v1 supports (see [GarageOpener]'s
 * KDoc on why there's no direction/status).
 *
 * Deliberately a top-level function with no BLE/Android dependency, so it's
 * unit-testable without a device (see GarageLogicTest) - [ShellyBleOpener]
 * itself only calls it, never reimplements the framing.
 */
fun buildSwitchSetFrame(relayId: Int): ByteArray {
    val body = JSONObject()
        .put("id", 1)
        .put("method", "Switch.Set")
        .put(
            "params",
            JSONObject()
                .put("id", relayId)
                .put("on", true)
                .put("toggle_after", 1),
        )
        .toString()
        .toByteArray(Charsets.UTF_8)
    val length = body.size
    val header = byteArrayOf(
        ((length ushr 24) and 0xFF).toByte(),
        ((length ushr 16) and 0xFF).toByte(),
        ((length ushr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )
    return header + body
}

/**
 * Alternate car-local BLE transport, not wired in v1 (v1 uses
 * [ShellyCloudOpener] - the relay is mounted at the garage on home WiFi, not
 * in the car, so a car-local radio can't reach it). Kept behind the
 * [GarageOpener] seam for a future no-cloud option (an in-car relay wired to
 * something the car itself triggers). [GarageController] does not reference
 * this class.
 *
 * NOTE: [GarageDoorConfig] was reshaped for the cloud transport (see that
 * class's KDoc) - [deviceId] is repurposed below to hold the BLE MAC address
 * for this unwired variant, since v1 only carries one generic string
 * identifier per door rather than a field per transport.
 *
 * [GarageOpener] over Shelly Gen2+'s Bluetooth RPC service - the Mongoose-OS
 * JSON-RPC-over-GATT protocol every Gen2+ Shelly (Plus/Pro/1 Gen4 family)
 * exposes once "RPC over Bluetooth" is turned on in the Shelly app. Verified
 * protocol, do not change without re-checking against a real device:
 *
 *  - Service `5f6d4f53-5f52-5043-5f53-56435f49445f`
 *  - Data char (r/w) `5f6d4f53-5f52-5043-5f64-6174615f5f5f`
 *  - TX control char (write-only) `5f6d4f53-5f52-5043-5f74-785f63746c5f`
 *  - RX control char (read/notify) `5f6d4f53-5f52-5043-5f72-785f63746c5f`
 *
 * To send a request: write the frame's JSON length (big-endian uint32) to TX
 * control, then write the JSON bytes to the data characteristic (chunked to
 * the negotiated MTU). To read the response: read RX control for the length
 * the device wants to send; if nonzero, read the data characteristic
 * repeatedly (each read returns the next chunk) until that many bytes are
 * assembled, then parse as JSON-RPC.
 *
 * RPC over BLE requires the link be bonded (GATT encryption) - v1 requires
 * the door already paired via Setup and throws [GarageException.NotConfigured]
 * otherwise; it does not attempt to bond mid-activation. v1 also assumes RPC
 * auth is OFF (the fresh-device default) - an auth-required response maps to
 * a [GarageException.DeviceError] telling the driver to disable it, rather
 * than implementing Shelly's digest auth.
 *
 * One-shot per activation: connects, pulses, disconnects. No persistent
 * connection is kept between triggers (unlike [ObdBluetoothManager]'s
 * always-on telemetry link) since a garage trigger is a rare, isolated
 * action.
 */
class ShellyBleOpener : GarageOpener {

    override suspend fun activate(context: Context, door: GarageDoorConfig) = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw GarageException.DeviceError("Bluetooth isn't available on this head unit.")
        val device = try {
            adapter.getRemoteDevice(door.deviceId)
        } catch (e: IllegalArgumentException) {
            throw GarageException.DeviceError("The saved address for that door looks invalid - re-add it in Settings.")
        }
        if (!isBonded(device)) {
            throw GarageException.NotConfigured("That garage relay isn't paired yet - set it up in Settings first.")
        }

        val callback = RpcGattCallback()
        val gatt = connectGatt(context, device, callback)
        try {
            awaitConnect(callback)
            val maxWrite = negotiateMtu(gatt, callback)
            discoverServices(gatt, callback)

            val service = gatt.getService(RPC_SERVICE_UUID)
                ?: throw GarageException.DeviceError(
                    "This device doesn't advertise the Shelly RPC service - enable " +
                        "\"RPC over Bluetooth\" for it in the Shelly app."
                )
            val dataChar = service.getCharacteristic(DATA_CHAR_UUID)
                ?: throw GarageException.DeviceError("Missing the Shelly RPC data channel.")
            val txChar = service.getCharacteristic(TX_CONTROL_CHAR_UUID)
                ?: throw GarageException.DeviceError("Missing the Shelly RPC control channel.")
            val rxChar = service.getCharacteristic(RX_CONTROL_CHAR_UUID)
                ?: throw GarageException.DeviceError("Missing the Shelly RPC control channel.")

            enableNotifications(gatt, rxChar, callback)

            val frame = buildSwitchSetFrame(door.relayId)
            val lengthPrefix = frame.copyOfRange(0, 4)
            val body = frame.copyOfRange(4, frame.size)
            writeCharacteristic(gatt, txChar, lengthPrefix, callback)
            writeChunked(gatt, dataChar, body, maxWrite, callback)

            val respLenBytes = readCharacteristic(gatt, rxChar, callback)
            val respLen = beUint32(respLenBytes)
            if (respLen > 0) {
                checkResponse(readAssembled(gatt, dataChar, respLen, callback))
            }
        } finally {
            closeGatt(gatt)
        }
    }

    /** Parses the RPC response and maps a Shelly-side error to [GarageException.DeviceError]. */
    private fun checkResponse(bytes: ByteArray) {
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            ?: return // Unparseable response still means the relay answered; treat as success.
        val error = json.optJSONObject("error") ?: return
        val code = error.optInt("code")
        val msg = error.optString("message", "The garage relay reported an error.")
        if (code == 401 || msg.contains("auth", ignoreCase = true)) {
            throw GarageException.DeviceError(
                "This Shelly needs RPC auth turned off - disable \"RPC auth\" for it in the Shelly app."
            )
        }
        throw GarageException.DeviceError(msg)
    }

    @SuppressLint("MissingPermission")
    private fun isBonded(device: BluetoothDevice): Boolean = try {
        device.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: SecurityException) {
        false
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(context: Context, device: BluetoothDevice, callback: RpcGattCallback): BluetoothGatt {
        return try {
            // TRANSPORT_LE: Shelly's RPC-over-Bluetooth service is BLE-only.
            device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw GarageException.Offline()
        } catch (e: SecurityException) {
            throw GarageException.DeviceError("Missing Bluetooth permission.")
        }
    }

    private fun awaitConnect(callback: RpcGattCallback) {
        if (!callback.connectLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) ||
            callback.lastStatus != BluetoothGatt.GATT_SUCCESS
        ) {
            throw GarageException.Offline()
        }
    }

    /** Best-effort MTU bump; falls back to the 23-byte default (20-byte payload) on failure. */
    @SuppressLint("MissingPermission")
    private fun negotiateMtu(gatt: BluetoothGatt, callback: RpcGattCallback): Int {
        var negotiated = 23
        if (gatt.requestMtu(247) && callback.mtuLatch.await(3000, TimeUnit.MILLISECONDS)) {
            negotiated = callback.negotiatedMtu
        }
        return maxOf(negotiated - 3, 20)
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(gatt: BluetoothGatt, callback: RpcGattCallback) {
        if (!gatt.discoverServices() ||
            !callback.servicesLatch.await(5000, TimeUnit.MILLISECONDS) ||
            callback.lastStatus != BluetoothGatt.GATT_SUCCESS
        ) {
            throw GarageException.Offline("Couldn't read the relay's Bluetooth services - try again.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, callback: RpcGattCallback) {
        gatt.setCharacteristicNotification(char, true)
        val cccd = char.getDescriptor(CCCD_UUID) ?: return
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        if (gatt.writeDescriptor(cccd)) {
            callback.descriptorLatch.await(3000, TimeUnit.MILLISECONDS)
        }
    }

    /** Single write, awaiting the ack if the characteristic supports WRITE (not just WRITE_NO_RESPONSE). */
    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        bytes: ByteArray,
        callback: RpcGattCallback,
    ) {
        val useResponse = char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        @Suppress("DEPRECATION")
        char.value = bytes
        @Suppress("DEPRECATION")
        char.writeType = if (useResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val latch = CountDownLatch(1)
        callback.writeLatchRef.set(latch)
        @Suppress("DEPRECATION")
        val sent = gatt.writeCharacteristic(char)
        if (!sent) throw GarageException.DeviceError("Couldn't write to the garage relay.")
        if (useResponse) {
            latch.await(2000, TimeUnit.MILLISECONDS)
        } else {
            Thread.sleep(10)
        }
    }

    /** Writes [bytes] to [char] in [maxWrite]-sized chunks, in order. */
    private fun writeChunked(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        bytes: ByteArray,
        maxWrite: Int,
        callback: RpcGattCallback,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maxWrite, bytes.size)
            writeCharacteristic(gatt, char, bytes.copyOfRange(offset, end), callback)
            offset = end
        }
    }

    /** Reads [char] once and returns whatever the device answered (possibly empty). */
    @SuppressLint("MissingPermission")
    private fun readCharacteristic(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, callback: RpcGattCallback): ByteArray {
        val latch = CountDownLatch(1)
        callback.readLatchRef.set(latch)
        callback.lastRead = null
        val requested = gatt.readCharacteristic(char)
        if (!requested) throw GarageException.DeviceError("Couldn't read from the garage relay.")
        latch.await(3000, TimeUnit.MILLISECONDS)
        return callback.lastRead ?: ByteArray(0)
    }

    /** Reads [char] repeatedly until [total] bytes are assembled or the device stops answering. */
    private fun readAssembled(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, total: Int, callback: RpcGattCallback): ByteArray {
        val out = ByteArray(total)
        var got = 0
        while (got < total) {
            val chunk = readCharacteristic(gatt, char, callback)
            if (chunk.isEmpty()) break
            val n = minOf(chunk.size, total - got)
            System.arraycopy(chunk, 0, out, got, n)
            got += n
        }
        return if (got == total) out else out.copyOfRange(0, got)
    }

    private fun beUint32(bytes: ByteArray): Int {
        if (bytes.size < 4) return 0
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
            gatt.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing garage relay GATT: ${e.message}")
        }
    }

    /**
     * One callback instance per [activate] call. Mirrors [BleTransport]'s
     * latch-per-phase shape, plus a read latch/buffer since this protocol is
     * request-response rather than a continuous notify stream.
     */
    private class RpcGattCallback : BluetoothGattCallback() {
        @Volatile var lastStatus: Int = BluetoothGatt.GATT_SUCCESS
        @Volatile var negotiatedMtu: Int = 23
        @Volatile var lastRead: ByteArray? = null
        val connectLatch = CountDownLatch(1)
        val mtuLatch = CountDownLatch(1)
        val servicesLatch = CountDownLatch(1)
        val descriptorLatch = CountDownLatch(1)
        val writeLatchRef = AtomicReference(CountDownLatch(0))
        val readLatchRef = AtomicReference(CountDownLatch(0))

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            lastStatus = status
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectLatch.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectLatch.countDown()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            mtuLatch.countDown()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            lastStatus = status
            servicesLatch.countDown()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorLatch.countDown()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeLatchRef.get().countDown()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            lastRead = characteristic.value
            readLatchRef.get().countDown()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // RX control also notifies on change (in addition to being readable);
            // treat it the same as an explicit read so a device that pushes the
            // length instead of waiting to be polled still completes the round trip.
            lastRead = characteristic.value
            readLatchRef.get().countDown()
        }
    }

    companion object {
        private const val TAG = "ShellyBleOpener"
        private const val CONNECT_TIMEOUT_MS = 12_000L

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        val RPC_SERVICE_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f53-56435f49445f")
        val DATA_CHAR_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f64-6174615f5f5f")
        val TX_CONTROL_CHAR_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f74-785f63746c5f")
        val RX_CONTROL_CHAR_UUID: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f72-785f63746c5f")
    }
}
