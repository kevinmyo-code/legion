package com.kevin.midnightai.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The app's own "now" (car report 2026-10-13: the head unit's clock and zone reset themselves, the
 * companion said Wednesday on a Tuesday, and "what's due on the 16th" came back with the 15th).
 * Every user-facing now/today goes through here, never `System.currentTimeMillis()` or the head
 * unit's `TimeZone.getDefault()`.
 *
 * - **Time:** network UTC held as an offset against `SystemClock.elapsedRealtime` (monotonic,
 *   untouched by wall-clock resets). SNTP to time.google.com first, then the `Date` header of a
 *   HEAD to google.com. Synced at start, whenever a network comes up, and every [REFRESH_MS].
 *   The offset is persisted for the current boot only (see [PersistedSync]); until a sync lands,
 *   [nowMs] is the head unit's wall clock and [isTrusted] is false.
 * - **Zone:** never the head unit's. Phone beacon zone (when "follow phone" is on) > the zone set
 *   in Settings > the LEGION household zone > [DEFAULT_ZONE_ID]. The chosen zone is also pushed
 *   into the process default ([TimeZone.setDefault]) so any `Calendar`/`SimpleDateFormat` that
 *   still reads the default follows it, and re-pushed when the head unit changes its own zone.
 */
object TrustedClock {
    private const val TAG = "TrustedClock"
    private const val PREFS = "trusted_clock"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_SOURCE = "source"
    private const val KEY_AT_ELAPSED = "at_elapsed"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_BOOT_WALL = "boot_wall"
    private const val KEY_MANUAL_ZONE = "manual_zone"
    private const val KEY_FOLLOW_PHONE = "follow_phone"
    private const val KEY_PHONE_ZONE = "phone_zone"
    private const val KEY_ENGINE_ZONE = "engine_zone"

    /**
     * Kevin lives in Houston. Evidence (2026-10-13): LEGION's CLAUDE.md ("made the assistant talk
     * about Chicago to a man in Houston"), the LEGION household-timezone feature and its tests are
     * built around America/Chicago, and LEGION's memory pins a static Houston lat/lng.
     */
    const val DEFAULT_ZONE_ID = "America/Chicago"

    const val REFRESH_MS = 3L * 3_600_000L
    private const val RETRY_MIN_MS = 60_000L
    private const val RETRY_MAX_MS = 15L * 60_000L
    private const val SNTP_TIMEOUT_MS = 3_000
    /** A reply slower than this is too uncertain to trust over the next source. */
    private const val MAX_RTT_MS = 2_000L

    private val core = TrustedClockCore(SystemClock::elapsedRealtime, System::currentTimeMillis)

    /** The head unit's own zone, captured before this object first overrides the default. */
    @Volatile
    private var deviceZoneId: String = TimeZone.getDefault().id

    data class Status(
        val trusted: Boolean,
        val source: ClockSource?,
        val skewMs: Long?,
        val zoneId: String,
        val zoneSource: ZoneSource,
        val deviceZoneId: String,
        val lastSyncFailed: Boolean,
    ) {
        val line: String get() = TimeText.statusLine(trusted, skewMs, source)
    }

    @Volatile
    private var zone: ZoneId = ZoneId.of(DEFAULT_ZONE_ID)
    @Volatile
    private var zoneSource: ZoneSource = ZoneSource.DEFAULT
    @Volatile
    private var lastSyncFailed = false

    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var loop: Job? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // ---- reads ---------------------------------------------------------------------------------

    fun nowMs(): Long = core.nowMs()

    fun isTrusted(): Boolean = core.isTrusted

    /** This device's OWN zone setting (what the OS says), not the one the app uses. On the phone
     * beacon that is the phone's carrier-set zone, which is what it sends the head unit. */
    fun deviceZoneId(): String = deviceZoneId

    fun zone(): ZoneId {
        val z = zone
        // The framework resets the process default on every head-unit zone change; put ours back.
        if (TimeZone.getDefault().id != z.id) TimeZone.setDefault(TimeZone.getTimeZone(z.id))
        return z
    }

    fun now(): ZonedDateTime = Instant.ofEpochMilli(nowMs()).atZone(zone())

    fun today(): LocalDate = now().toLocalDate()

    /** A Calendar at trusted now in the trusted zone, for code still on java.util.Calendar. */
    fun calendar(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone().id)).apply { timeInMillis = nowMs() }

    /** The system 12/24 h clock format, in the trusted zone. */
    fun formatClock(context: Context, ms: Long = nowMs()): String {
        val fmt = DateFormat.getTimeFormat(context)
        fmt.timeZone = TimeZone.getTimeZone(zone().id)
        return fmt.format(Date(ms))
    }

    /** The "now" line for the companion's prompt. */
    fun promptLine(): String = TimeText.promptLine(now(), core.isTrusted)

    /** The "now" paragraph for the engine sub-agent (carries the UTC-window rule). */
    fun engineNowText(): String = TimeText.engineLine(now(), core.isTrusted)

    // ---- zone settings -------------------------------------------------------------------------

    fun manualZoneId(context: Context): String? = prefs(context).getString(KEY_MANUAL_ZONE, null)

    /** Saves (or with blank, clears) the Settings zone. False when [id] is not a zone id. */
    fun setManualZone(context: Context, id: String?): Boolean {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isNotEmpty() && ZoneResolver.parse(trimmed) == null) return false
        prefs(context).edit().apply {
            if (trimmed.isEmpty()) remove(KEY_MANUAL_ZONE) else putString(KEY_MANUAL_ZONE, trimmed)
        }.apply()
        resolveZone(context)
        return true
    }

    fun followPhone(context: Context): Boolean = prefs(context).getBoolean(KEY_FOLLOW_PHONE, true)

    fun setFollowPhone(context: Context, follow: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOLLOW_PHONE, follow).apply()
        resolveZone(context)
    }

    fun phoneZoneId(context: Context): String? = prefs(context).getString(KEY_PHONE_ZONE, null)

    fun engineZoneId(context: Context): String? = prefs(context).getString(KEY_ENGINE_ZONE, null)

    /** The zone the phone beacon reported (its own carrier-set zone). */
    fun acceptPhoneZone(id: String) {
        val ctx = appContext ?: return
        if (ZoneResolver.parse(id) == null || phoneZoneId(ctx) == id) return
        prefs(ctx).edit().putString(KEY_PHONE_ZONE, id).apply()
        resolveZone(ctx)
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        restore(app)
        resolveZone(app)
        registerZoneReceiver(app)
        registerNetworkCallback(app)
        loop = scope.launch { refreshLoop(app) }
    }

    /** Fire-and-forget re-sync (e.g. from Settings). */
    fun requestSync() {
        val app = appContext ?: return
        scope.launch { syncOnce(app) }
    }

    private suspend fun refreshLoop(app: Context) {
        var retry = RETRY_MIN_MS
        while (true) {
            val ok = syncOnce(app)
            if (ok) {
                retry = RETRY_MIN_MS
                delay(REFRESH_MS)
            } else {
                delay(retry)
                retry = (retry * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    /** One sync attempt: SNTP, then the HTTP Date header. Also refreshes the engine zone. */
    suspend fun syncOnce(app: Context): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val sync = trySntp() ?: tryHttpDate()
            if (sync != null) {
                core.accept(sync)
                lastSyncFailed = false
                persist(app, sync)
                Log.i(TAG, "synced via ${sync.source}, skew ${core.skewMs()} ms")
            } else {
                lastSyncFailed = true
                Log.w(TAG, "sync failed (SNTP and HTTP Date)")
            }
            refreshEngineZone(app)
            publish()
            sync != null
        }
    }

    private fun trySntp(): ClockSync? = try {
        val r = Sntp.query(Sntp.DEFAULT_HOST, SNTP_TIMEOUT_MS, SystemClock::elapsedRealtime, System.currentTimeMillis())
        if (r.roundTripMs > MAX_RTT_MS) {
            Log.w(TAG, "SNTP rtt ${r.roundTripMs} ms, too slow to trust")
            null
        } else {
            ClockSync(r.offsetMs, ClockSource.SNTP, SystemClock.elapsedRealtime())
        }
    } catch (e: Exception) {
        Log.w(TAG, "SNTP failed: ${e.message}")
        null
    }

    private fun tryHttpDate(): ClockSync? = try {
        val request = Request.Builder().url("https://www.google.com/").head().build()
        val sent = SystemClock.elapsedRealtime()
        http.newCall(request).execute().use { response ->
            val received = SystemClock.elapsedRealtime()
            val dateMs = HttpDate.parseMs(response.header("Date"))
            if (dateMs == null || received - sent > MAX_RTT_MS * 2) {
                null
            } else {
                ClockSync(HttpDate.offsetMs(dateMs, sent, received), ClockSource.HTTP_DATE, received)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "HTTP Date failed: ${e.message}")
        null
    }

    /** The LEGION household's own IANA zone (`GET /api/households/me`), when signed in. */
    private suspend fun refreshEngineZone(app: Context) {
        val http = EngineHttp(EngineConfig(app))
        if (!http.isUsable()) return
        val body = http.get("/api/households/me").getOrNull()?.body ?: return
        val tz = runCatching { JSONObject(body).optString("timezone") }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" } ?: return
        if (ZoneResolver.parse(tz) == null || engineZoneId(app) == tz) return
        prefs(app).edit().putString(KEY_ENGINE_ZONE, tz).apply()
        resolveZone(app)
    }

    // ---- internals -----------------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1)

    private fun persist(context: Context, sync: ClockSync) {
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putLong(KEY_OFFSET, sync.offsetMs)
            .putString(KEY_SOURCE, sync.source.name)
            .putLong(KEY_AT_ELAPSED, sync.atElapsedMs)
            .putInt(KEY_BOOT_COUNT, bootCount(context))
            .putLong(KEY_BOOT_WALL, System.currentTimeMillis() - nowElapsed)
            .apply()
    }

    private fun restore(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_OFFSET)) return
        val saved = PersistedSync(
            offsetMs = p.getLong(KEY_OFFSET, 0L),
            source = runCatching { ClockSource.valueOf(p.getString(KEY_SOURCE, "") ?: "") }
                .getOrDefault(ClockSource.SNTP),
            atElapsedMs = p.getLong(KEY_AT_ELAPSED, 0L),
            bootCount = p.getInt(KEY_BOOT_COUNT, -1),
            bootWallMs = p.getLong(KEY_BOOT_WALL, 0L),
        )
        val ok = PersistedSync.restorable(
            saved, bootCount(context), SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        )
        if (ok) core.accept(saved.toSync())
        publish()
    }

    private fun resolveZone(context: Context) {
        val (z, src) = ZoneResolver.resolve(
            phone = phoneZoneId(context),
            followPhone = followPhone(context),
            manual = manualZoneId(context),
            engine = engineZoneId(context),
            default = DEFAULT_ZONE_ID,
        )
        zone = z
        zoneSource = src
        zone()
        publish()
    }

    private fun registerZoneReceiver(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                intent.getStringExtra("time-zone")?.let { deviceZoneId = it }
                zone() // re-assert ours over the head unit's
                publish()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                app, receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                },
                // System broadcasts; EXPORTED for the same API < 33 reason PhoneLinkManager documents.
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "zone receiver not registered: ${it.message}") }
    }

    private fun registerNetworkCallback(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { syncOnce(app) }
                }
            })
        }.onFailure { Log.w(TAG, "network callback not registered: ${it.message}") }
    }

    private fun snapshot() = Status(
        trusted = core.isTrusted,
        source = core.sync?.source,
        skewMs = core.skewMs(),
        zoneId = zone.id,
        zoneSource = zoneSource,
        deviceZoneId = deviceZoneId,
        lastSyncFailed = lastSyncFailed,
    )

    private fun publish() {
        val s = snapshot()
        _status.value = s
        runCatching {
            val c = FirebaseCrashlytics.getInstance()
            c.setCustomKey("clock_trusted", s.trusted)
            c.setCustomKey("clock_skew_ms", s.skewMs ?: 0L) // 0 while untrusted; read with clock_trusted
            c.setCustomKey("tz_used", "${s.zoneId} (${s.zoneSource.name.lowercase()})")
            c.setCustomKey("device_tz", s.deviceZoneId)
        }
    }
}
