package com.kevin.legion.vehicle

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.kevin.legion.MidnightEvents
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.DebugSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A device seen during [ObdBluetoothManager.startDiscovery], tagged with which
 * scanner found it - classic inquiry ([BluetoothDevice.ACTION_FOUND]) or a BLE
 * advertisement scan. BLE serial ELM327 clones never bond, so without this tag
 * the picker has no way to route the selection to [BleTransport] instead of
 * [RfcommTransport].
 *
 * A sighting tags one scanner, NOT the device: the same dongle can and does
 * arrive twice, once per scanner (the V020 does exactly this - it answers
 * classic inquiry with an audio device class it cannot honour, and it
 * advertises over BLE). Merging the two sightings is [foldDiscovered]'s job.
 *
 * [advertisedName] carries the name the scanner itself reported - the BLE scan
 * record's local name, or `EXTRA_NAME` from the classic inquiry. Both are
 * needed because [BluetoothDevice.getName] reads the bond cache and is null
 * for a device that has never bonded, which is every BLE dongle by definition:
 * the V020 broadcasts "V020" in its scan response and still reads back as an
 * unnamed device.
 */
data class DiscoveredDevice(
    val device: BluetoothDevice,
    val isBle: Boolean,
    val advertisedName: String? = null,
)

/**
 * Manages a persistent connection to an OBD-II ELM327 adapter (real hardware
 * over Bluetooth RFCOMM or BLE GATT, or an emulator over TCP - see
 * [ObdTransport]) and exposes simple PID queries (coolant temp, RPM, trouble
 * codes). Singleton so both the foreground service (which owns the connection
 * loop) and the UI (which queries live data for quick local answers) share one
 * connection.
 */
object ObdBluetoothManager {
    private const val TAG = "ObdBluetoothManager"

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * What the adapter and car reported on the last successful connect: its
     * ATI identity string (e.g. "ELM327 v1.5") and the OBD protocol the ECU
     * negotiated. This is the seed data for the adapter-tier/honesty system -
     * captured now, on real hardware, before any tier-assignment rules are
     * written, so the tiers get designed from what adapters actually say
     * rather than guessed. See [ObdDeviceRegistry.setLastKnownInfo] for the
     * per-MAC persisted copy that survives a disconnect.
     */
    data class AdapterInfo(val idString: String, val protocolName: String)
    private val _adapterInfo = MutableStateFlow<AdapterInfo?>(null)
    val adapterInfo: StateFlow<AdapterInfo?> = _adapterInfo.asStateFlow()

    /**
     * The Mode-01 PID numbers this car reported as supported, discovered from
     * the support bitmasks at connect time (see [finishConnect]/
     * [detectSupportedPids]). Drives the Settings gauge picker so it only offers
     * gauges the connected car actually answers (see
     * [com.kevin.legion.vehicle.ObdGauge]). Empty while disconnected or if
     * the adapter never returned a usable bitmask.
     */
    private val _supportedPids = MutableStateFlow<Set<Int>>(emptySet())
    val supportedPids: StateFlow<Set<Int>> = _supportedPids.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val commandMutex = Mutex()

    // @Volatile: written by the connection-loop coroutine (finishConnect) and by
    // disconnect() (which can fire from the main thread via forceReconnect/
    // setActiveDevice), read by isConnected / connectedDeviceAddress from UI,
    // MusicHistoryRecorder, and LiveToolbox on various dispatchers. The command
    // mutex only serializes command exchange, not these reads, so volatile is
    // what guarantees a stale-socket read doesn't linger after a disconnect.
    @Volatile private var transport: ObdTransport? = null

    // Captured from finishConnect's context param so disconnect() (which has no
    // context of its own, and fires from several call sites) can still notify
    // ActiveVehicle's identity-resolution listeners on a real disconnect.
    @Volatile private var appContext: Context? = null

    // Consecutive PID (non-AT) commands that came back blank - i.e. the ECU/K-line
    // side of the link has gone quiet, distinct from the adapter itself (AT
    // commands like ATRV are answered locally and never touch the vehicle bus).
    // See sendCommand()'s reinit trigger: drive-notes ticket 03 ("only voltage
    // remains after a while").
    @Volatile private var consecutivePidSilence = 0
    private const val PID_REINIT_THRESHOLD = 3

    val isConnected: Boolean
        get() = transport != null

    // MAC address of the currently-connected adapter (or the emulator's
    // "host:port" label) - identifies which physical car this is (each car
    // has its own dongle), so VehicleController can switch persona/
    // maintenance schedule per car.
    @Volatile var connectedDeviceAddress: String? = null
        private set

    /**
     * Infinite loop maintaining the OBD-II connection, reconnecting as needed.
     * When [DebugSettings.obdEmulatorEnabled] is on, connects to the ELM327
     * emulator over TCP instead of scanning for a real Bluetooth dongle - see
     * [ObdTransport] for why the rest of this class doesn't need to know which.
     */
    suspend fun startConnectionLoop(context: Context) = withContext(Dispatchers.IO) {
        while (true) {
            if (!isConnected) {
                if (DebugSettings.obdEmulatorEnabled(context)) {
                    connectEmulator(context)
                } else {
                    val activeMac = ObdDeviceRegistry.getActiveMac(context)
                    val device = try {
                        if (activeMac != null) bluetoothAdapter?.getRemoteDevice(activeMac)
                        else findPairedObdiiDevice()
                    } catch (e: IllegalArgumentException) {
                        // getRemoteDevice throws on a malformed MAC. It normally
                        // comes straight from a real device.address so this
                        // shouldn't happen, but a corrupt stored value would
                        // otherwise throw out of the whole loop every 5s forever.
                        // Clear it so we fall back to auto-detect instead.
                        Log.w(TAG, "Bad stored OBD MAC '$activeMac', clearing: ${e.message}")
                        ObdDeviceRegistry.clearActive(context)
                        null
                    }

                    if (device == null) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        delay(10000)
                        continue
                    }
                    connect(context, device)
                }
            }
            delay(5000)
        }
    }

    /**
     * All bonded devices, with the ones that look like OBD dongles sorted to the
     * top. We deliberately do NOT hide non-matching devices: cheap ELM327 clones
     * advertise under wildly inconsistent names (bare MACs, "SPP-CA", or "V-LINK"
     * with punctuation the old contains-filter missed), so hiding the driver's
     * actual dongle was far worse than showing a few extra rows. [looksLikeObd]
     * drives ordering/highlighting now, not visibility.
     */
    @SuppressLint("MissingPermission")
    fun listBondedObd(): List<BluetoothDevice> = try {
        // getBondedDevices throws SecurityException on Android 12+ until
        // BLUETOOTH_CONNECT is granted - and the OBD screen builds this list at
        // composition time, which can be BEFORE its permission launcher fires.
        bluetoothAdapter?.bondedDevices
            ?.sortedByDescending { looksLikeObd(it) }
            ?: emptyList()
    } catch (e: SecurityException) {
        Log.w(TAG, "listBondedObd without BLUETOOTH_CONNECT: ${e.message}")
        emptyList()
    }

    /** Heuristic: does this device's name look like an ELM327 OBD dongle? Ordering only. */
    @SuppressLint("MissingPermission")
    fun looksLikeObd(device: BluetoothDevice): Boolean =
        looksLikeObd(try { device.name } catch (e: SecurityException) { null })

    /**
     * Name-only form of the heuristic, for a name that came off a scan record
     * rather than off a [BluetoothDevice] - a never-bonded BLE dongle has no
     * cached `device.name` at all, so the device overload above always says
     * false for exactly the adapters this screen exists to find.
     */
    fun looksLikeObd(name: String?): Boolean {
        val normalized = (name ?: return false).uppercase().filter { it.isLetterOrDigit() }
        return DEVICE_NAME_PATTERNS.any { normalized.contains(it) }
    }

    /**
     * Scans for nearby unpaired dongles - both classic inquiry (bondable
     * RFCOMM dongles) and a BLE advertisement scan (HM-10-style GATT dongles,
     * which never bond) merged into one flow. BLUETOOTH_SCAN (neverForLocation)
     * already covers the BLE scan; no separate manifest permission is needed.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(context: Context): Flow<DiscoveredDevice> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        // Emit every discovered device - do NOT name-filter here.
                        // The dongle's name is usually still null on the first
                        // ACTION_FOUND (it resolves a beat later), and clones use
                        // inconsistent names, so filtering by name silently dropped
                        // the very dongle the driver is trying to pair. The UI sorts
                        // likely-OBD devices first via looksLikeObd instead.
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null) {
                            trySend(
                                DiscoveredDevice(
                                    device = device,
                                    isBle = false,
                                    advertisedName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME),
                                ),
                            )
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // Classic inquiry stopped on its own (~12s cycle). The
                        // BLE scan below is independent and has no natural end
                        // of its own - don't close the flow here, let the 30s
                        // safety net below own the overall scan lifetime.
                        Log.d(TAG, "Classic discovery finished")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)
        // startDiscovery returns false when the radio refuses (BT off/toggling,
        // permission edge).
        val classicStarted = try {
            bluetoothAdapter?.startDiscovery() == true
        } catch (e: SecurityException) {
            Log.w(TAG, "startDiscovery without permission: ${e.message}")
            false
        }

        // BLE serial clones ("for iPhone" ELM327 dongles) never bond and never
        // answer classic inquiry - they advertise, they don't do SDP/inquiry -
        // so without this they could never be found at all.
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // scanRecord.deviceName, not device.name: an unbonded BLE
                // device has no cached name, so device.name is null and the
                // row renders as UNNAMED DEVICE forever. The name is in the
                // advertisement (often the scan response, which is why it can
                // take a couple of sightings to arrive).
                trySend(
                    DiscoveredDevice(
                        device = result.device,
                        isBle = true,
                        advertisedName = result.scanRecord?.deviceName,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: errorCode=$errorCode")
            }
        }
        val bleStarted = if (scanner != null) {
            try {
                scanner.startScan(
                    /* filters = */ null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCallback,
                )
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "BLE startScan without permission: ${e.message}")
                false
            }
        } else {
            false
        }

        if (!classicStarted && !bleStarted) {
            // Neither scanner came up - no broadcast/callback will ever
            // arrive, so close now or the caller's spinner runs forever.
            close()
        } else {
            // Safety net: a normal classic cycle is ~12s and ends with
            // ACTION_DISCOVERY_FINISHED, but flaky head-unit BT stacks have
            // been seen to drop that broadcast, and the BLE scan has no
            // natural end at all. Run both for up to 30s and own the overall
            // lifetime here rather than tracking classic/BLE completion
            // separately.
            launch {
                delay(30_000)
                close()
            }
        }

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Exception) {}
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (ignored: SecurityException) {}
            try {
                scanner?.stopScan(scanCallback)
            } catch (ignored: SecurityException) {}
        }
    }

    /** Initiates pairing, auto-replying the ELM327 near-universal default PIN "1234". */
    @SuppressLint("MissingPermission")
    suspend fun bondDevice(context: Context, device: BluetoothDevice, timeoutMs: Long = 30000): Boolean = withContext(Dispatchers.IO) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    if (variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                        // One pairing request accepts exactly one PIN reply - a second
                        // setPin just overwrites the first, so the old
                        // "setPin(1234); setPin(0000)" silently only ever tried 0000.
                        // "1234" is the near-universal ELM327 clone default. A dongle
                        // that uses a different PIN can still be paired from the head
                        // unit's own Bluetooth settings, after which it appears in the
                        // PAIRED list here (which no longer name-filters).
                        device.setPin("1234".toByteArray())
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST))
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return@withContext true
            // Bonding while a scan is in flight frequently fails - the SCAN
            // button that surfaced this device may still be discovering.
            if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter.cancelDiscovery()
            val success = device.createBond()
            if (!success) return@withContext false
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (device.bondState == BluetoothDevice.BOND_BONDED) return@withContext true
                delay(500)
            }
            false
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Exception) {}
        }
    }

    /** Removes bond via reflected removeBond(). */
    fun unpair(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Unpair failed", e)
            false
        }
    }

    /**
     * Writes to registry and disconnects if switching. [isBle] persists which
     * scanner found the device (see [ObdDeviceRegistry.isBle]'s KDoc) so
     * [connect] knows to use [BleTransport] instead of [RfcommTransport] even
     * after a reboot. Defaults to false - the classic PAIRED-list path (bonded
     * devices are always classic) never needs to pass it.
     */
    fun setActiveDevice(context: Context, mac: String?, isBle: Boolean = false) {
        if (mac != null) ObdDeviceRegistry.setBle(context, mac, isBle)
        val current = ObdDeviceRegistry.getActiveMac(context)
        if (current != mac) {
            ObdDeviceRegistry.setActiveMac(context, mac)
            if (isConnected) disconnect()
        }
    }

    fun getActiveDeviceMac(context: Context): String? {
        return ObdDeviceRegistry.getActiveMac(context)
    }

    /**
     * Drops the current connection (if any) so [startConnectionLoop] picks a
     * fresh one on its next cycle - used when flipping
     * [DebugSettings.obdEmulatorEnabled] so the switch between real dongle and
     * emulator takes effect immediately instead of waiting on whatever the
     * old transport's next failed command happens to be.
     */
    fun forceReconnect() {
        if (isConnected) disconnect()
    }

    /** Coolant temperature in Celsius, or null if unavailable. */
    suspend fun getCoolantTemp(): Int? = ObdResponseParser.coolantTempC(sendCommand("0105"))

    /** Engine speed in RPM, or null if unavailable. */
    suspend fun getRpm(): Int? = ObdResponseParser.rpm(sendCommand("010C"))

    /** Calculated engine load %, or null if unavailable. */
    suspend fun getEngineLoad(): Double? = ObdResponseParser.engineLoadPct(sendCommand("0104"))

    /** Short-term fuel trim % (bank 1), or null if unavailable. */
    suspend fun getShortFuelTrim(): Double? = ObdResponseParser.shortFuelTrimPct(sendCommand("0106"))

    /** Long-term fuel trim % (bank 1), or null if unavailable. */
    suspend fun getLongFuelTrim(): Double? = ObdResponseParser.longFuelTrimPct(sendCommand("0107"))

    /** Vehicle speed in km/h, or null if unavailable. */
    suspend fun getSpeedKmh(): Int? = ObdResponseParser.speedKmh(sendCommand("010D"))

    /** Intake air temperature in Celsius, or null if unavailable. */
    suspend fun getIntakeAirTemp(): Int? = ObdResponseParser.intakeAirTempC(sendCommand("010F"))

    /** Mass air flow in grams/second, or null if unavailable (feeds the MPG math). */
    suspend fun getMaf(): Double? = ObdResponseParser.mafGramsPerSec(sendCommand("0110"))

    /** Fuel tank level %, or null if unavailable. */
    suspend fun getFuelLevel(): Double? = ObdResponseParser.fuelLevelPct(sendCommand("012F"))

    /** Emissions readiness (MIL, DTC count, monitor states), or null if unavailable. */
    suspend fun getReadiness(): ReadinessStatus? = ObdResponseParser.readiness(sendCommand("0101"))

    /**
     * Mode-02 freeze-frame snapshot: the PID values the ECU latched when the
     * current trouble code set. Returns whatever the ECU answered (possibly
     * empty - many clones and pre-2001 cars don't support mode 02 at all).
     * Keys are human labels, ready to JSON-encode onto a code_events row.
     */
    suspend fun getFreezeFrame(): Map<String, Double> {
        data class FfPid(val pid: String, val len: Int, val label: String, val decode: (List<Int>) -> Double)
        val pids = listOf(
            FfPid("0C", 2, "rpm") { b -> ((b[0] * 256) + b[1]) / 4.0 },
            FfPid("05", 1, "coolant_c") { b -> b[0] - 40.0 },
            FfPid("0D", 1, "speed_kmh") { b -> b[0].toDouble() },
            FfPid("04", 1, "load_pct") { b -> b[0] * 100.0 / 255.0 },
            FfPid("10", 2, "maf_gs") { b -> ((b[0] * 256) + b[1]) / 100.0 },
            FfPid("06", 1, "stft_pct") { b -> (b[0] - 128) * 100.0 / 128.0 },
            FfPid("07", 1, "ltft_pct") { b -> (b[0] - 128) * 100.0 / 128.0 },
            FfPid("0F", 1, "iat_c") { b -> b[0] - 40.0 },
        )
        val out = mutableMapOf<String, Double>()
        for (p in pids) {
            val bytes = ObdResponseParser.freezeFrameBytes(sendCommand("02${p.pid}00"), p.pid, p.len)
                ?: continue
            out[p.label] = p.decode(bytes)
        }
        return out
    }

    /** Stored diagnostic trouble codes (e.g. "P0301"), empty if none/unavailable. */
    suspend fun getDtcCodes(): List<String> = ObdResponseParser.dtcCodes(sendCommand("03"))

    /**
     * Battery/system voltage in volts via the ELM327 "ATRV" command, or null if
     * unavailable. Engine off this is the resting battery voltage (~12.4-12.7 =
     * healthy); engine running it reflects the alternator (~13.7-14.7).
     */
    suspend fun getBatteryVoltage(): Double? = ObdResponseParser.batteryVoltage(sendCommand("ATRV"))

    /**
     * The 17-character VIN via mode-09 PID 02, or null if unavailable (older
     * pre-CAN cars and some clones don't support it). Multi-frame reply, so it's
     * given a longer timeout than the single-frame PID reads.
     */
    suspend fun getVin(): String? = ObdResponseParser.vin(sendCommand("0902", timeoutMs = 5000))

    // Common ELM327 dongle name patterns ("OBDII", "OBD-II", "Vlinker MC",
    // "Veepeak", "Vgate iCar", "OBDLink"...). Most contain "OBD" or "ELM"
    // already; these cover the popular brands that don't.
    // Ordering only, never visibility (see listBondedObd's KDoc). "V020" is the
    // HM-10-style BLE clone Kevin actually owns - it matched none of the
    // generic patterns, so it sorted below every random beacon in the room.
    private val DEVICE_NAME_PATTERNS =
        listOf("OBD", "ELM", "VLINK", "VGATE", "VEEPEAK", "ICAR", "V020")

    @SuppressLint("MissingPermission")
    private fun findPairedObdiiDevice(): BluetoothDevice? = try {
        // Auto-connect fallback when no active device was ever selected. Uses
        // the same normalized name heuristic as the UI ordering ("V-LINK" and
        // friends match), and swallows the missing-BLUETOOTH_CONNECT
        // SecurityException so the service's connection loop survives a boot
        // where the grant hasn't happened yet.
        bluetoothAdapter?.bondedDevices?.find { looksLikeObd(it) }
    } catch (e: SecurityException) {
        Log.w(TAG, "findPairedObdiiDevice without BLUETOOTH_CONNECT: ${e.message}")
        null
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(context: Context, device: BluetoothDevice) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting to connect to ${device.name} (${device.address})")
            // BLE serial clones ("for iPhone" HM-10-style dongles) never bond,
            // so they can't go through RFCOMM. The persisted registry flag is
            // the source of truth (set by the picker at scan time, see
            // ObdDeviceRegistry.isBle's KDoc); device.type is a secondary
            // hint for a device that was never routed through the picker.
            val useBle = ObdDeviceRegistry.isBle(context, device.address) ||
                device.type == BluetoothDevice.DEVICE_TYPE_LE
            val t = if (useBle) BleTransport.connect(context, device) else RfcommTransport.connect(device, bluetoothAdapter)
            finishConnect(context, t, device.address)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            disconnect()
        }
    }

    /**
     * Connects to the Ircama ELM327 emulator over TCP instead of a real
     * dongle (see [ObdTransport.DEFAULT_HOST]/[TcpTransport]) - lets OBD
     * parsing/PID/tool-call code be exercised end-to-end, including scripted
     * failure injection the emulator supports (truncation, timeouts, missing
     * AT responses), without a car. Only reached when
     * [DebugSettings.obdEmulatorEnabled] is on.
     */
    private suspend fun connectEmulator(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting to connect to OBD emulator")
            val t = TcpTransport.connect()
            finishConnect(context, t, t.label)
        } catch (e: Exception) {
            Log.e(TAG, "Emulator connection failed: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            disconnect()
        }
    }

    /**
     * Shared post-connect handshake for both [connect] and [connectEmulator]:
     * reset the adapter, capture its identity/protocol, mark us connected.
     * [address] is a Bluetooth MAC or the emulator's "host:port" label.
     */
    private suspend fun finishConnect(context: Context, t: ObdTransport, address: String) {
        _adapterInfo.value = null // clear any previous device's info before we know this one's
        _connectionState.value = ConnectionState.CONNECTING
        transport = t
        appContext = context.applicationContext
        // ActiveVehicle.current() falls back to this address when the driver hasn't
        // explicitly picked a car (CLAUDE.md sec 5/9), so a cold-start connect can
        // flip the resolved vehicle id from VehicleController.DEFAULT_VEHICLE_ID to
        // this dongle's real MAC AFTER Cruise's avatar/persona/voice already composed
        // against the default - the old avatar/voice flashing on startup, drive-notes
        // ticket 02. Nothing previously told the cached base instruction, a prewarmed
        // voice socket, or the UI that the resolved identity changed once it did.
        val wasDisconnected = connectedDeviceAddress == null
        connectedDeviceAddress = address
        if (wasDisconnected) ActiveVehicle.notifyResolutionChanged(context.applicationContext)

        // Some adapters need a moment after the link comes up before they're
        // ready to accept AT commands.
        delay(500)

        // Reset adapter, disable echo/linefeeds, auto-detect protocol.
        sendCommand("ATZ", timeoutMs = 5000)
        sendCommand("ATE0")
        sendCommand("ATL0")
        sendCommand("ATSP0")
        // ATI reports the adapter's own firmware identity (e.g. "ELM327
        // v1.5") - genuine adapters and cheap clones both answer this, but
        // the string itself is a signal (clones often report bogus/odd
        // version strings). ATDPN only reports a real protocol AFTER the
        // adapter has actually talked to the ECU, so a PID request (mode 01
        // PID 00, "supported PIDs") is sent first to force that handshake.
        val idString = sendCommand("ATI").trim().ifBlank { "(no response)" }
        val supported0100 = sendCommand("0100")
        val protocolCode = sendCommand("ATDPN").trim()
        val protocolName = describeProtocol(protocolCode)
        // A command failing mid-handshake calls disconnect() (nulling transport),
        // so re-check before declaring success - otherwise we'd report CONNECTED
        // with a dead socket and every read would silently return null.
        if (transport == null) {
            Log.w(TAG, "Handshake lost the transport; aborting connect")
            _adapterInfo.value = null
            _connectionState.value = ConnectionState.ERROR
            return
        }
        val info = AdapterInfo(idString, protocolName)
        _adapterInfo.value = info
        ObdDeviceRegistry.setLastKnownInfo(context, address, "$idString · $protocolName")
        Log.d(TAG, "OBD-II connected and initialized: $idString, protocol=$protocolName")
        _connectionState.value = ConnectionState.CONNECTED
        detectSupportedPids(supported0100)
        MidnightEvents.obdConnected(address)
        announceActiveCar(context)
    }

    /**
     * Flashes "RECORDING AS <car>" once, on every OBD connect (car manager,
     * 2026-07-16).
     *
     * **Every connect, not just when the car changed.** A change-detecting notice
     * would be silent in the exact case it exists for: move the dongle to another
     * car and forget to switch, and the resolved vehicleId is unchanged - so it
     * would say nothing precisely when the driver is about to record onto the wrong
     * car.
     *
     * Reuses [CompanionPhase.showNotice]'s existing 4s flash rather than adding a
     * surface: on a head unit bolted into one car this is a status confirmation
     * once per ignition, the weight of a dashboard light, not a nag. It never
     * blocks - recording is never gated on a UI state, because a mislabeled drive
     * is correctable (OBD - HISTORY - MOVE TO) and a missing one is gone forever.
     *
     * Honest limit: a flash the driver doesn't look at prevents nothing. This is the
     * cheap first line; the corrector is the real answer.
     */
    private suspend fun announceActiveCar(context: Context) {
        runCatching {
            // Ticket 04's label rule: the one rule, every surface - see VehicleController.label's
            // doc. Only the fixed "RECORDING AS " chrome is uppercase; the label itself is data
            // (a driver-typed nickname) and must never be transformed - the old
            // `.uppercase()` over the WHOLE string is how this notice used to shout a renamed car's
            // own name back at the driver.
            val label = VehicleController.label(VehicleController.currentVehicle(context))
            CompanionPhase.showNotice("RECORDING AS $label")
        }
    }

    /**
     * Builds the supported-PID set from the Mode-01 support bitmasks: the
     * [firstWindow] response ("0100", already sent during the handshake), plus
     * each further window the car advertises (a window's bitmask sets the
     * marker bit for the next one - PID 0x20 means "0120 has data", 0x40 means
     * "0140 has data", 0x60 means "0160 has data"). Best-effort: a missing or
     * garbled window just leaves those PIDs out rather than failing the connect.
     *
     * **The 0x60 window was missing until 2026-08-12.** The loop stopped at 0x40,
     * so every PID from 0x61 to 0x80 was invisible - the whole turbo and torque
     * range (boost control, wastegate, turbo RPM, charge-air-cooler temp, EGT).
     * The app could not even report whether a vehicle answered them, because it
     * never asked. Driven off [SUPPORT_PROBES] now rather than an unrolled
     * if-chain, so adding the next window is a row in that list.
     */
    private suspend fun detectSupportedPids(firstWindow: String) {
        val supported = sortedSetOf<Int>()
        ObdResponseParser.supportedPids(firstWindow, 0x00)?.let { supported += it }
        // Skip the first probe: its response is already in hand as [firstWindow].
        for ((command, base) in SUPPORT_PROBES.drop(1)) {
            if (base !in supported || transport == null) break
            ObdResponseParser.supportedPids(sendCommand(command), base)?.let { supported += it }
        }
        _supportedPids.value = supported
        Log.d(TAG, "Supported Mode-01 PIDs: ${supported.joinToString { "%02X".format(it) }}")
        persistCapabilities(supported)
    }

    /**
     * Writes the freshly-scanned profile against the connected car so it can be answered while
     * parked and unplugged (see [com.kevin.legion.data.local.VehicleCapability]).
     *
     * Best-effort and deliberately swallowing: a failed capability write must never break an
     * otherwise good OBD connection. The DAO itself refuses to clear a stored profile on an empty
     * scan, so a bad handshake cannot erase what we already knew about the car.
     */
    private suspend fun persistCapabilities(supported: Set<Int>) {
        val context = appContext ?: return
        // ActiveVehicle.current, NOT connectedDeviceAddress (bug found on-device 2026-08-12).
        //
        // `Vehicle.obdMac` is the primary key but it is only a String: a car added by hand gets a
        // SYNTHETIC id (`car:<uuid>`, see ActiveVehicle.mintId) and never a MAC. Kevin's F-150 is
        // exactly that. Keying capabilities off the dongle's Bluetooth address would have filed
        // them under an id no Vehicle row has, and `read_vehicle_sensor` - which looks up
        // vehicle.obdMac - would never have found them. Silent, and invisible until someone asked
        // the assistant a question it should have been able to answer.
        //
        // Every other per-vehicle writer (TelemetryRecorder, code events) already resolves this way.
        val vehicleId = ActiveVehicle.current(context)
        runCatching {
            CarDatabase.getDatabase(context).vehicleCapabilityDao()
                .replaceForVehicle(vehicleId, supported, System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "capability persist failed", it) }
    }

    /**
     * Reads any PID in [PID_REGISTRY] generically - the one call that replaces writing a new
     * `getWhatever()` for every reading (see [PidSpec]'s doc comment).
     *
     * Returns null when the adapter or the car did not answer usefully, when the response was
     * short, or when [spec] has no decoder yet. Never throws and never guesses: a PID the car
     * declines to answer reads as absent, not as zero.
     */
    suspend fun readPid(spec: PidSpec): Double? {
        val decode = spec.decode ?: return null
        val response = sendCommand(spec.command)
        if (ObdResponseParser.isFailureResponse(response)) return null
        val bytes = ObdResponseParser.dataBytes(response, spec.responsePrefix) ?: return null
        if (bytes.size < spec.bytes) return null
        return runCatching { decode(bytes) }.getOrNull()
    }

    /**
     * What the currently connected car can do - [capabilitiesFor] applied to the live bitmask.
     * Empty-but-valid when nothing is connected, so callers never need a null check.
     */
    fun capabilities(): VehicleCapabilities = capabilitiesFor(_supportedPids.value)

    /**
     * Maps an ATDPN response to a human-readable protocol name. ATDPN prefixes
     * an "A" when the protocol was auto-selected (the normal case, since we
     * always connect with ATSP0) - e.g. "A3" means "auto-selected, ISO 9141-2".
     * Codes per the ELM327 datasheet.
     */
    private fun describeProtocol(code: String): String {
        val auto = code.startsWith("A", ignoreCase = true)
        val digit = code.removePrefix("A").removePrefix("a").trim()
        val name = when (digit) {
            "0" -> "unknown (not yet detected)"
            "1" -> "SAE J1850 PWM"
            "2" -> "SAE J1850 VPW"
            "3" -> "ISO 9141-2"
            "4" -> "ISO 14230-4 KWP (5-baud init)"
            "5" -> "ISO 14230-4 KWP (fast init)"
            "6" -> "ISO 15765-4 CAN (11-bit, 500k)"
            "7" -> "ISO 15765-4 CAN (29-bit, 500k)"
            "8" -> "ISO 15765-4 CAN (11-bit, 250k)"
            "9" -> "ISO 15765-4 CAN (29-bit, 250k)"
            "A" -> "SAE J1939 CAN"
            else -> "unrecognized ($code)"
        }
        return if (auto) "$name (auto)" else name
    }

    private fun disconnect() {
        MidnightEvents.obdDisconnected(connectedDeviceAddress ?: "unknown")
        transport?.close()
        transport = null
        val wasConnected = connectedDeviceAddress != null
        connectedDeviceAddress = null
        // Symmetric with finishConnect: losing the dongle can flip
        // ActiveVehicle.current() back to the default vehicle id too.
        if (wasConnected) appContext?.let { ActiveVehicle.notifyResolutionChanged(it) }
        _supportedPids.value = emptySet()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Sends an AT/PID command and returns the raw response, or "" on failure.
     * Default timeout is 5000ms, not the ELM327's typical ~200ms round-trip -
     * the 1998 XJ (and pre-2001 cars generally) negotiate ISO 9141-2 "slow
     * init", whose per-PID ECU round-trip routinely blew past the old 3000ms
     * budget under bus contention, silently truncating every gauge but ATRV
     * (adapter-answered, no ECU round-trip) to "unavailable".
     *
     * That timeout bump alone wasn't the whole fix (drive-notes ticket 03: gauges
     * still went silent for the rest of a drive, ATRV excepted). A blank PID
     * response can mean the ECU's K-line session itself has gone idle - a real
     * ISO 9141-2 behavior after a lull in bus traffic, needing a fresh protocol
     * handshake to wake back up - and nothing after the initial [finishConnect]
     * ever re-ran that handshake. [PID_REINIT_THRESHOLD] consecutive silent PID
     * reads (AT commands don't count; they're answered locally) triggers
     * [reinitProtocolLocked] to recover the link instead of staying dead.
     *
     * "Silent" means [ObdResponseParser.isFailureResponse], not [String.isBlank]
     * (drive-notes-2 ticket 02: the first version of this fix only checked
     * `isBlank()`, but the real 1998 XJ's ELM327 answers a dead K-line with
     * "NO DATA" - non-blank text - so the counter never incremented and
     * [reinitProtocolLocked] never fired in practice; the drive that exposed
     * this still dropped to voltage-only for the rest of the drive).
     */
    private suspend fun sendCommand(cmd: String, timeoutMs: Long = 5000): String = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            val response = exchangeLocked(cmd, timeoutMs)
            if (!cmd.startsWith("AT", ignoreCase = true)) {
                if (ObdResponseParser.isFailureResponse(response) && transport != null) {
                    consecutivePidSilence++
                    // Breadcrumb the EXACT failing response (ADB logcat is blocked on
                    // the head unit): a dead K-line's real answer string is the thing
                    // we can't see from a JVM test, and it's what tells us whether the
                    // isFailureResponse vocabulary above is even catching it.
                    MidnightEvents.obdPidSilence(consecutivePidSilence, response)
                    if (consecutivePidSilence >= PID_REINIT_THRESHOLD) {
                        consecutivePidSilence = 0
                        Log.w(TAG, "$PID_REINIT_THRESHOLD consecutive silent PID reads - re-running protocol init")
                        val recovered = reinitProtocolLocked()
                        // Non-fatal (retrievable on its own) so ONE drive tells us
                        // definitively whether reinit fires AND whether it recovered.
                        MidnightEvents.obdReinit(response, recovered)
                    }
                } else {
                    consecutivePidSilence = 0
                }
            }
            response
        }
    }

    /** The actual byte exchange, assuming [commandMutex] is already held. */
    private suspend fun exchangeLocked(cmd: String, timeoutMs: Long): String {
        val t = transport ?: return ""
        return try {
            Elm327Io(t.inputStream, t.outputStream).exchange(cmd, timeoutMs)
        } catch (e: IOException) {
            Log.e(TAG, "Command '$cmd' failed: ${e.message}")
            disconnect()
            ""
        }
    }

    /**
     * Forces a fresh ECU handshake to recover a K-line session that has gone idle.
     * Called from inside [sendCommand]'s own lock, so it must use [exchangeLocked]
     * directly - calling back into [sendCommand] would deadlock on [commandMutex].
     *
     * Sequence (strengthened 2026-07-19, drive-notes-2 ticket 02 second pass): the
     * previous version was just ATSP0 + 0100, and on real hardware that left the
     * gauges dead for the rest of a drive. ATSP0 only sets protocol selection to
     * auto - it does NOT force the adapter to tear down and re-establish a K-line
     * session it still believes is open. [ATPC] (protocol close) explicitly drops
     * that session, so the following [0100] triggers a genuine fresh 5-baud init
     * rather than reusing the dead one. This is the documented ELM327 way to wake a
     * dormant slow-init link. ATPC on a cheap clone that doesn't support it just
     * returns "?" and is ignored, so it can't make things worse.
     *
     * @return true if the post-reinit 0100 came back looking like a real
     *   supported-PIDs reply (i.e. the link recovered), false if it's still failing.
     */
    private suspend fun reinitProtocolLocked(): Boolean {
        exchangeLocked("ATPC", timeoutMs = 5000)   // close the dead protocol session
        exchangeLocked("ATSP0", timeoutMs = 5000)  // auto protocol
        val probe = exchangeLocked("0100", timeoutMs = 5000)  // force fresh init + handshake
        return !ObdResponseParser.isFailureResponse(probe) && "41 00" in probe.uppercase()
    }
}
