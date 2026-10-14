package com.kevin.midnightai.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which of three worlds this head unit's Bluetooth lives in - see the ticket that commissioned
 * this file. Decided at runtime from what actually works, never assumed from a device list.
 */
enum class PhoneLinkCapability {
    /** Android's own stack owns the phones; hidden connect/disconnect are reachable and don't throw. */
    FULL,
    /** Phones are visible (bonded / profile proxies) but control is unreachable or denied. */
    VIEW_ONLY,
    /** Android's adapter sees no phones at all; a vendor BT app owns the link instead. */
    VENDOR,
    /** No [BluetoothAdapter], or permission never granted - nothing to show. */
    NO_BLUETOOTH,
}

enum class LinkState { CONNECTED, CONNECTING, DISCONNECTING, DISCONNECTED, UNKNOWN }

data class PhoneLinkInfo(
    val address: String,
    val name: String,
    val audioState: LinkState,
    val callsState: LinkState,
    val preferred: Boolean,
)

data class PhoneLinkState(
    val capability: PhoneLinkCapability = PhoneLinkCapability.NO_BLUETOOTH,
    val phones: List<PhoneLinkInfo> = emptyList(),
    /** Address of the phone actually carrying audio right now, or null if none is. */
    val activeAddress: String? = null,
    /** First installed package that looks like a vendor BT app (see [BluetoothProbe]'s pattern). */
    val vendorPackage: String? = null,
    /** Plain-English result of the last action taken, for the UI's one-line status. */
    val lastResult: String? = null,
)

/** Outcome of an action that dispatches real Bluetooth calls (switch / disconnect). */
sealed interface PhoneLinkResult {
    data object Success : PhoneLinkResult
    data class PartiallyFailed(val reason: String) : PhoneLinkResult
    data class NotSupported(val reason: String) : PhoneLinkResult
}

/**
 * Process-singleton phone-link controller. Owns the live [state] the manager screen and the
 * home-screen chip both read, and is the only place that actually dials the hidden
 * connect/disconnect/setPriority Bluetooth APIs.
 *
 * Nothing here can crash the app: every reflective or platform Bluetooth call is wrapped, a
 * [SecurityException] permanently downgrades [PhoneLinkCapability] to VIEW_ONLY for the rest of
 * the process (recomputed capability guesses are optimistic; a real throw is ground truth and
 * wins), and every public suspend function returns a result instead of propagating.
 *
 * Threading (white-screen fix): everything that talks to the Bluetooth stack or PackageManager
 * for STATE runs on one dedicated worker thread, never main. On a cold start the receiver fires
 * a burst (ACL / bond / adapter / A2DP / HFP) and each refresh used to do bondedDevices, per-device
 * binder calls and a full `getInstalledPackages` on the main thread, in the window before the
 * first frame. [refresh] now only schedules, and bursts coalesce into one pass. Consequence for
 * callers: [state] updates a moment (~REFRESH_DEBOUNCE_MS) after [refresh], not synchronously.
 */
object PhoneLinkManager {

    private val _state = MutableStateFlow(PhoneLinkState())
    val state: StateFlow<PhoneLinkState> = _state.asStateFlow()

    @Volatile private var appCtx: Context? = null
    // Set on the worker (the adapter lookup is itself a binder call), read from IO threads too.
    @Volatile private var adapter: BluetoothAdapter? = null
    private var initialized = false
    private var receiverRegistered = false

    /** Once a hidden call throws SecurityException, stop pretending we have FULL control.
     *  Written from IO threads (actions) and read on the worker, hence volatile. */
    @Volatile private var forcedDowngrade = false

    /** The one thread all state refreshes run on; started by [init], never on main. */
    @Volatile private var worker: Handler? = null

    /** True while a refresh is queued but has not started; further requests fold into it. */
    private val refreshPending = AtomicBoolean(false)

    /** Vendor BT app lookup is a full installed-package scan; the answer does not change within a
     *  process in any way that matters here, so compute it once (on the worker) and reuse. */
    private var vendorPackageResolved = false
    private var vendorPackageCache: String? = null

    private val proxies = mutableMapOf<Int, BluetoothProfile>()

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            synchronized(proxies) { proxies[profile] = proxy }
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            synchronized(proxies) { proxies.remove(profile) }
            refresh()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    /** Safe to call repeatedly - re-binds proxies/receiver only once per process. Cheap on the
     *  calling thread: it only spins up the worker and queues the real setup there. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appCtx = app

        val thread = HandlerThread("phonelink").apply { start() }
        val handler = Handler(thread.looper)
        worker = handler

        handler.post {
          // An uncaught throw on a HandlerThread kills the process, so nothing escapes this lambda.
          try {
            adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

            adapter?.let { a ->
                listOf(PhoneLinkProfiles.A2DP_SINK, PhoneLinkProfiles.AVRCP_CONTROLLER, PhoneLinkProfiles.HEADSET_CLIENT)
                    .forEach { id -> runCatching { a.getProfileProxy(app, proxyListener, id) } }
            }

            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            }
            // Handler = worker: onReceive runs there, so a power-up burst never lands on main.
            runCatching {
                // EXPORTED on purpose: every action here is a SYSTEM broadcast (sent by the
                // bluetooth uid). On API < 33 androidx.core implements RECEIVER_NOT_EXPORTED by
                // registering under a signature-level "<pkg>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                // which a system sender does not hold, so the broadcasts would be dropped on this
                // AOSP 8-10 unit. Receiving only protected system actions, so exported is safe.
                ContextCompat.registerReceiver(app, receiver, filter, null, handler, ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
            }

            refreshNow()
          } catch (t: Throwable) {
            android.util.Log.w("PhoneLinkManager", "init failed: ${t.message}")
          }
        }
    }

    /**
     * Asks for a state recompute. Returns immediately; the work happens on the worker after a short
     * debounce, and any requests arriving before it starts fold into that one pass (the receiver
     * fires several broadcasts per connect). A no-op before [init].
     */
    fun refresh() {
        val h = worker ?: return
        if (refreshPending.compareAndSet(false, true)) {
            h.postDelayed({
                refreshPending.set(false)
                refreshNow()
            }, REFRESH_DEBOUNCE_MS)
        }
    }

    /** Recomputes the whole [state] from current adapter/proxy/package-manager reality.
     *  Worker thread only. Publishes with `update` (not read-modify-write) so it cannot clobber a
     *  `lastResult` an action wrote from an IO thread in between. */
    private fun refreshNow() {
        val t0 = SystemClock.elapsedRealtime()
        try {
            refreshNowInner()
        } catch (t: Throwable) {
            // Same promise as the class doc: nothing here may take the app down.
            android.util.Log.w("PhoneLinkManager", "refresh failed: ${t.message}")
        } finally {
            com.kevin.midnightai.StartupTrace.phoneLinkRefreshed(SystemClock.elapsedRealtime() - t0)
        }
    }

    private fun refreshNowInner() {
        val app = appCtx ?: return
        val a = adapter

        if (a == null) {
            val vendor = firstVendorPackage(app)
            _state.update {
                it.copy(
                    capability = if (vendor != null) PhoneLinkCapability.VENDOR else PhoneLinkCapability.NO_BLUETOOTH,
                    phones = emptyList(),
                    activeAddress = null,
                    vendorPackage = vendor,
                )
            }
            return
        }
        if (!hasConnectPermission(app)) {
            _state.update {
                it.copy(
                    capability = PhoneLinkCapability.NO_BLUETOOTH,
                    phones = emptyList(),
                    activeAddress = null,
                )
            }
            return
        }

        val vendorPkg = firstVendorPackage(app)
        val bonded = runCatching { a.bondedDevices.toList() }.getOrDefault(emptyList())
        val bondedPhones = bonded.filter { isPhoneClass(it) }

        val proxySnapshot = synchronized(proxies) { proxies.toMap() }
        val a2dp = proxySnapshot[PhoneLinkProfiles.A2DP_SINK]
        val hfp = proxySnapshot[PhoneLinkProfiles.HEADSET_CLIENT]

        val phones = bondedPhones.map { d ->
            PhoneLinkInfo(
                address = d.address,
                name = label(d),
                audioState = connectionState(a2dp, d),
                callsState = connectionState(hfp, d),
                preferred = d.address == preferredAddress(app),
            )
        }

        val capability = when {
            forcedDowngrade -> PhoneLinkCapability.VIEW_ONLY
            bondedPhones.isEmpty() && vendorPkg != null -> PhoneLinkCapability.VENDOR
            bondedPhones.isEmpty() -> PhoneLinkCapability.VIEW_ONLY
            else -> {
                // Optimistic guess from reflection lookup only (mirrors BluetoothProbe): a
                // reachable method means FULL is worth attempting. A real SecurityException
                // during an actual attempt is what permanently sets forcedDowngrade above.
                val reachable = a2dp?.let { hasMethod(it, "connect") } == true
                if (reachable) PhoneLinkCapability.FULL else PhoneLinkCapability.VIEW_ONLY
            }
        }

        val active = phones.firstOrNull { it.audioState == LinkState.CONNECTED }?.address
        _state.update {
            it.copy(
                capability = capability,
                phones = phones,
                activeAddress = active,
                vendorPackage = vendorPkg,
            )
        }
    }

    /** Disconnect every other bonded phone, de-prioritize them, then bring [address] up. */
    suspend fun switchTo(address: String): PhoneLinkResult = withContext(Dispatchers.IO) {
        val snapshot = _state.value
        if (snapshot.capability != PhoneLinkCapability.FULL) {
            return@withContext PhoneLinkResult.NotSupported(
                "This head unit doesn't expose direct phone switching - use system Bluetooth settings.",
            )
        }
        val actions = planSwitch(snapshot.phones.map { it.address }, address)
        if (actions.isEmpty()) return@withContext PhoneLinkResult.NotSupported("Unknown phone.")

        var anyFailed = false
        actions.forEach { action ->
            val ok = runAction(action)
            if (!ok) anyFailed = true
            delay(ACTION_GAP_MS)
        }
        appCtx?.let { setPreferred(it, address) }
        refresh()
        val result = if (anyFailed) {
            PhoneLinkResult.PartiallyFailed("Switch mostly worked but one or more Bluetooth calls failed.")
        } else {
            PhoneLinkResult.Success
        }
        recordResult(result, "Switched to phone")
        result
    }

    /** Disconnects one phone on every sink profile, without touching anyone else. */
    suspend fun disconnect(address: String): PhoneLinkResult = withContext(Dispatchers.IO) {
        val snapshot = _state.value
        if (snapshot.capability != PhoneLinkCapability.FULL) {
            return@withContext PhoneLinkResult.NotSupported(
                "This head unit doesn't expose direct phone control - use system Bluetooth settings.",
            )
        }
        val a = adapter ?: return@withContext PhoneLinkResult.NotSupported("No adapter.")
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext PhoneLinkResult.NotSupported("Unknown phone.")

        var anyFailed = false
        listOf(PhoneLinkProfiles.A2DP_SINK, PhoneLinkProfiles.AVRCP_CONTROLLER, PhoneLinkProfiles.HEADSET_CLIENT)
            .forEach { id ->
                val proxy = synchronized(proxies) { proxies[id] } ?: return@forEach
                if (!invokeDeviceMethod(proxy, "disconnect", device)) anyFailed = true
                delay(ACTION_GAP_MS)
            }
        refresh()
        val result = if (anyFailed) {
            PhoneLinkResult.PartiallyFailed("Some profiles didn't disconnect cleanly.")
        } else {
            PhoneLinkResult.Success
        }
        recordResult(result, "Disconnected phone")
        result
    }

    /** Forgets the phone on the HEAD UNIT side only (hidden `BluetoothDevice.removeBond()`). */
    suspend fun forget(address: String): Boolean = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext false
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return@withContext false
        val ok = runCatching {
            val m = device.javaClass.getMethod("removeBond")
            (m.invoke(device) as? Boolean) ?: true
        }.onFailure { e -> if (isSecurity(e)) forcedDowngrade = true }.getOrDefault(false)
        refresh()
        _state.update {
            it.copy(lastResult = if (ok) "Forgot phone on this head unit." else "Couldn't forget that phone.")
        }
        ok
    }

    /** Disconnect everything, cycle the adapter off then on. Best-effort, bounded by timeouts. */
    suspend fun resetBluetooth(): Boolean = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext false
        // A reset is the way back to normal, so it also undoes every switch's
        // auto-connect OFF. Otherwise a phone that lost a switch never
        // reconnects on its own again, and system settings can't show why.
        _state.value.phones.forEach { p ->
            runCatching { invokePriority(a.getRemoteDevice(p.address), on = true) }
            disconnect(p.address)
        }

        val off = runCatching {
            if (a.isEnabled) a.disable()
            waitForAdapterState(a, BluetoothAdapter.STATE_OFF)
        }.getOrDefault(false)

        val on = runCatching {
            a.enable()
            waitForAdapterState(a, BluetoothAdapter.STATE_ON)
        }.getOrDefault(false)

        refresh()
        val ok = off && on
        _state.update {
            it.copy(
                lastResult = if (ok) "Bluetooth reset." else "Bluetooth reset didn't fully complete - check system settings.",
            )
        }
        ok
    }

    fun pairNewPhoneIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)

    fun systemBluetoothSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    fun vendorAppLaunchIntent(context: Context): Intent? {
        val pkg = _state.value.vendorPackage ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)
    }

    fun preferredAddress(context: Context): String? =
        prefs(context).getString(KEY_PREFERRED, null)

    fun setPreferred(context: Context, address: String) {
        prefs(context).edit().putString(KEY_PREFERRED, address).apply()
    }

    // --- internals ----------------------------------------------------------------------

    private fun runAction(action: PhoneLinkAction): Boolean {
        val a = adapter ?: return false
        val device = runCatching { a.getRemoteDevice(action.address) }.getOrNull() ?: return false
        return when (action) {
            is PhoneLinkAction.SetAutoConnectOff -> invokePriority(device, on = false)
            is PhoneLinkAction.SetAutoConnectOn -> invokePriority(device, on = true)
            is PhoneLinkAction.Disconnect -> {
                val proxy = synchronized(proxies) { proxies[action.profile] } ?: return true // profile not bound = nothing to do
                invokeDeviceMethod(proxy, "disconnect", device)
            }
            is PhoneLinkAction.Connect -> {
                val proxy = synchronized(proxies) { proxies[action.profile] } ?: return false
                invokeDeviceMethod(proxy, "connect", device)
            }
        }
    }

    /** Hidden `setPriority(BluetoothDevice,Int)` (pre-API30) or `setConnectionPolicy` (30+).
     *  Both use 0=off, 100=on for the values this feature needs (reasoned from field naming;
     *  unverified against AOSP source - see the brief). Tried against whichever sink proxy is
     *  bound; auto-reconnect priority is a per-profile setting so we set it on both. */
    private fun invokePriority(device: BluetoothDevice, on: Boolean): Boolean {
        val methodName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "setConnectionPolicy" else "setPriority"
        val value = if (on) 100 else 0
        val snapshot = synchronized(proxies) { proxies.toMap() }
        var anyOk = false
        listOf(PhoneLinkProfiles.A2DP_SINK, PhoneLinkProfiles.HEADSET_CLIENT).forEach { id ->
            val proxy = snapshot[id] ?: return@forEach
            val ok = runCatching {
                val m = proxy.javaClass.getMethod(methodName, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
                (m.invoke(proxy, device, value) as? Boolean) ?: true
            }.onFailure { e -> if (isSecurity(e)) forcedDowngrade = true }.isSuccess
            if (ok) anyOk = true
        }
        return anyOk
    }

    private fun invokeDeviceMethod(proxy: BluetoothProfile, name: String, device: BluetoothDevice): Boolean =
        runCatching {
            val m = proxy.javaClass.getMethod(name, BluetoothDevice::class.java)
            (m.invoke(proxy, device) as? Boolean) ?: true
        }.onFailure { e -> if (isSecurity(e)) forcedDowngrade = true }.isSuccess

    private fun hasMethod(proxy: BluetoothProfile, name: String): Boolean =
        runCatching { proxy.javaClass.getMethod(name, BluetoothDevice::class.java) }.isSuccess

    private fun isSecurity(e: Throwable): Boolean =
        e is SecurityException || e.cause is SecurityException

    @SuppressLint("MissingPermission")
    private fun connectionState(proxy: BluetoothProfile?, device: BluetoothDevice): LinkState {
        if (proxy == null) return LinkState.UNKNOWN
        return runCatching { proxy.getConnectionState(device) }.getOrNull()?.let {
            when (it) {
                BluetoothProfile.STATE_CONNECTED -> LinkState.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> LinkState.CONNECTING
                BluetoothProfile.STATE_DISCONNECTING -> LinkState.DISCONNECTING
                BluetoothProfile.STATE_DISCONNECTED -> LinkState.DISCONNECTED
                else -> LinkState.UNKNOWN
            }
        } ?: LinkState.UNKNOWN
    }

    private suspend fun waitForAdapterState(a: BluetoothAdapter, target: Int): Boolean {
        var waited = 0L
        while (waited < RESET_TIMEOUT_MS) {
            if (runCatching { a.state }.getOrNull() == target) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return runCatching { a.state }.getOrNull() == target
    }

    @SuppressLint("MissingPermission")
    private fun isPhoneClass(d: BluetoothDevice): Boolean =
        runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull() == BluetoothClass.Device.Major.PHONE

    @SuppressLint("MissingPermission")
    private fun label(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull() ?: d.address

    private fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** First installed package that looks like a vendor Bluetooth app (mirrors
     *  [BluetoothProbe]'s heuristic), excluding this app and the AOSP Bluetooth stack itself. */
    private fun firstVendorPackage(context: Context): String? {
        // Cached: this is a whole-device package scan and used to run on every refresh. Only ever
        // called from the worker, so the plain fields need no lock.
        if (vendorPackageResolved) return vendorPackageCache
        val found = scanVendorPackage(context)
        vendorPackageCache = found
        vendorPackageResolved = true
        return found
    }

    private fun scanVendorPackage(context: Context): String? {
        val pkgs = runCatching {
            context.packageManager.getInstalledPackages(0).map { it.packageName }
        }.getOrDefault(emptyList())
        return pkgs.filter { it != context.packageName && it != "com.android.bluetooth" }
            .firstOrNull { looksBluetooth(it) }
    }

    private fun looksBluetooth(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "bluetooth" in p || BT_TOKEN.containsMatchIn(p)
    }

    private fun recordResult(result: PhoneLinkResult, verb: String) {
        val message = when (result) {
            PhoneLinkResult.Success -> "$verb."
            is PhoneLinkResult.PartiallyFailed -> "$verb - ${result.reason}"
            is PhoneLinkResult.NotSupported -> result.reason
        }
        _state.update { it.copy(lastResult = message) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private const val PREFS_FILE = "phone_link"
    private const val KEY_PREFERRED = "preferred_address"
    private const val ACTION_GAP_MS = 350L
    private const val REFRESH_DEBOUNCE_MS = 200L
    private const val POLL_MS = 200L
    private const val RESET_TIMEOUT_MS = 6000L
    private val BT_TOKEN = Regex("""(^|[._])bt([._]|$)""")
}
