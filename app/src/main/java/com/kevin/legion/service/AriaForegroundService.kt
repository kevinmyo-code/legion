package com.kevin.legion.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.BuildConfig
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.firstGreetingOpener
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.MemoryConsolidator
import com.kevin.legion.ai.ReflectionEngine
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.location.ReminderController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.ai.OnboardingState
import com.kevin.legion.notes.NotesController
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleSpecController
import com.kevin.legion.sync.SyncEngine
import com.kevin.legion.weather.WeatherController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "Soul" of the app - a persistent foreground service, and the home of
 * the whole experience.
 *
 * This service owns the Gemini Live session via [LiveSessionController] (it used
 * to live in the UI, so voice only worked with the app in front). The driver
 * talks by tapping the companion on the Cruise screen (the old floating overlay
 * button was removed - Midnight AI is the head unit's home screen, so it's always a
 * tap away). It also runs the always-on background work - vehicle telemetry
 * loops and the proactive engine, which voices openers/alerts through the same
 * session via [ProactiveBus.requestSpeak]. There's no wake word (the old offline
 * "Hey Moose" engine shipped arm64-only native libs that blocked the head-unit
 * install).
 */
class AriaForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Owns the single Gemini Live session + conversation state machine. Lives in
    // the service (not a composable) so voice works while another app is in front.
    private lateinit var sessionController: LiveSessionController

    // The ledger folder-scan pipeline used to be constructed HERE (ticket 05
    // resolution §1) on the reasoning that this service already declares
    // dataSync/connectedDevice/microphone, so hosting it costs no new
    // dependency and no manifest change. Ticket 08 Part 6 (wiring the ledger
    // UI to an actual scan) found that reasoning incomplete: onCreate() below
    // unconditionally boots the ENTIRE assistant - mic prewarm, the spoken
    // opener, the OBD Bluetooth loop, GPS, wake word, ambient listening - the
    // instant this service is created for ANY reason, bind or start, with no
    // internal gate on AssistantIgnition.isEnabled(). AssistantIgnition's own
    // doc comment promises "ledger/pantry/fleet are unaffected" by that
    // toggle, which is OFF by default (ticket 07 resolution §1, "a fresh
    // install asks for nothing") - so a driver opening the Ledger tab on a
    // fresh install must never cause Zero to start talking and the OBD radio
    // to spin up. IngestScanner now lives in its own
    // [com.kevin.legion.service.LedgerIngestService] instead, a small
    // `dataSync`-only foreground service with no dependency on this one -
    // see that class's doc comment for the full reasoning. Nothing under this
    // comment references IngestScanner anymore; this note stays so the next
    // person doesn't reintroduce it here for the same reason ticket 05 first
    // reached for it.

    // Highest 10k-mile milestone already celebrated, so the proactive check fires
    // only on new crossings (not retroactively). -1 = not yet seeded. Process-life.
    private var lastMilestoneAnnounced = -1

    // The startup-opener coroutine. Kept so ACTION_GREET can tell "opener already
    // queued this startup" (skip - avoid a double greeting that clobbers the
    // first-run self-intro) from "service long-running" (greet again).
    private var openerJob: kotlinx.coroutines.Job? = null

    // Open-recall check runs at most once per process launch.
    @Volatile private var recallChecked = false

    // Debug-only: lets a turn be driven by typed text over adb instead of the
    // mic. The emulator's virtual mic is unreliable (replays host audio, can't do
    // full-duplex), so this is how you test the brain + voice output there. Null
    // (and never registered) in release builds.
    private var debugTextReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        createNotificationChannel()
        startForegroundCompat()

        // Load the active Gemini key (user-entered, else BuildConfig) into the
        // process cache before anything builds a session that needs it.
        GeminiKeyProvider.init(this)
        ProactivePreferences.init(this)

        // Own the Live session (driven by the Cruise screen's tap-to-talk).
        sessionController = LiveSessionController(this)

        // IngestScanner construction used to happen here (ticket 05) - moved
        // to LedgerIngestService. See the doc comment above where
        // `ingestScanner` used to be declared for the full reasoning.

        // Pre-open a warm Gemini Live socket so the driver's first tap doesn't pay
        // the connect + setup handshake. Only once onboarding is done, so we don't
        // open a cloud connection while the companion is still being set up.
        if (OnboardingState.isComplete(this)) sessionController.prewarm()

        // Live data sources Zero reads from (used to be inited by MainActivity):
        // the media session for now-playing, and GPS for location/arrival. Both
        // are safe to call repeatedly and quietly wait until their grant is given.
        NowPlayingController.init(this)
        LocationController.init(this)
        // Watch phone-call state (phone paired over BT HFP) so the assistant can
        // announce incoming calls and stay quiet during one. No-op without
        // READ_PHONE_STATE.
        TelephonyController.init(this)
        // Keep current weather warm (flavors the startup greeting). Retries
        // quickly until the first GPS fix/fetch lands, then refreshes slowly, so a
        // session never blocks on the network for it.
        serviceScope.launch {
            while (isActive) {
                val weather = WeatherController.refresh()
                delay(if (weather == null) WEATHER_RETRY_MS else WEATHER_REFRESH_MS)
            }
        }

        // Cross-device sync while the engine is running (2026-07-16, Kevin's field
        // report). Sync used to fire ONLY from MainActivity.onResume, which on a
        // head unit means "when you start the car" - so a drive's telemetry, all of
        // it recorded after that resume, never left the device until the NEXT time
        // the car started. "Drive, get home, check the phone" could not work.
        //
        // Deliberately NOT hooked to drive-end: that needs the drive monitor, which
        // needs GPS (Kevin's XJ has no antenna wired), and even with it he cuts the
        // key on arrival and the unit powers down before any end-of-drive work could
        // run. A periodic push while the engine turns is the only trigger that
        // survives both. Worst case he loses the last few minutes, not the drive.
        //
        // Gated on engine-on so a parked car doesn't sync all day on the driver's
        // hotspot data. maybeAutoSync is itself a no-op unless Drive is connected
        // and its own throttle has elapsed, so this loop is cheap when idle.
        //
        // NOT the only trigger any more (ticket 10, 2026-08-02): this service only
        // ever starts from the Settings assistant toggle, which defaults OFF, and
        // ticket 07 §1 rules ledger/pantry/fleet all work regardless of that
        // toggle. Left in place because it is not wrong - a live drive is still the
        // right moment to push OBD telemetry - just insufficient on its own for
        // ledger/pantry, which now also sync from MainActivity.onResume
        // (foreground-lifecycle trigger, independent of the assistant).
        serviceScope.launch {
            while (isActive) {
                delay(DRIVE_SYNC_INTERVAL_MS)
                if (com.kevin.legion.vehicle.TelemetryRecorder.isEngineRunning) {
                    SyncEngine.maybeAutoSync(this@AriaForegroundService, serviceScope)
                }
            }
        }

        // Companion-memory map, tickets 02 + 05 (2026-07-22): distill whatever's
        // left pending into durable memories, then look for patterns across
        // them. TWO call sites, deliberately, for the same reason the drive-
        // sync loop above is periodic-not-drive-end: the Cherokee's head unit
        // loses power the instant the engine turns off, so a purely-periodic
        // trigger would frequently die before it ever gets to run. A startup
        // catch-up sweep picks up whatever was pending when the engine last cut
        // out; the periodic loop catches sessions that finished earlier in the
        // SAME still-running drive. See MemoryConsolidator's own doc for why the
        // correctness gate is "nothing is live right now," not a staleness
        // timestamp - either call site is safe at any cadence. Reflection always
        // runs AFTER consolidation in the same pass so its input pool is fresh.
        serviceScope.launch {
            MemoryConsolidator.consolidatePending(this@AriaForegroundService)
            ReflectionEngine.reflectIfDue(this@AriaForegroundService)
        }
        serviceScope.launch {
            while (isActive) {
                delay(MEMORY_CONSOLIDATION_INTERVAL_MS)
                MemoryConsolidator.consolidatePending(this@AriaForegroundService)
                ReflectionEngine.reflectIfDue(this@AriaForegroundService)
            }
        }

        // Wire the proactive engine to the one session (the Cruise screen drives
        // listening directly). Used to be collected in AriaLiveScreen.
        serviceScope.launch { ProactiveBus.requestSpeak.collect { sessionController.requestSpeak(it) } }

        // Greet the driver proactively (the car has just started). Gemini Live
        // does the speaking, so there's no TTS engine to warm up first. The job is
        // kept so an ACTION_GREET arriving in the same startup doesn't fire a
        // SECOND opener - on a fresh install the duplicate took the generic-
        // greeting branch (the first had already marked first-session-done) and
        // overwrote the pending self-introduction before the socket connected.
        openerJob = serviceScope.launch { speakOpener() }

        // Optional: a one-time open-recall check at startup (off by default).
        serviceScope.launch { checkRecallsOnce() }

        // Start vehicle connection loop — restart if it ever throws unexpectedly.
        serviceScope.launch {
            while (isActive) {
                try {
                    ObdBluetoothManager.startConnectionLoop(this@AriaForegroundService)
                    break // normal exit: loop stopped itself
                } catch (e: Exception) {
                    android.util.Log.e("AriaFGS", "OBD connection loop crashed, restarting in 5s", e)
                    MidnightEvents.recordError("obd_loop", e)
                    kotlinx.coroutines.delay(5_000)
                }
            }
        }

        // The compounding-history pipeline: 30s obd_samples telemetry + MPG
        // accumulation + cold-start bursts, while the engine runs. Also the
        // single odometer writer now (GPS-or-OBD per-tick miles) - the old
        // separate trackTripMileage loop was consolidated into this one.
        serviceScope.launch {
            com.kevin.legion.vehicle.TelemetryRecorder.run(this@AriaForegroundService)
        }

        // One-time online lookup of maintenance intervals for any vehicle
        // that hasn't been onboarded yet (e.g. the default Zero profile).
        serviceScope.launch {
            VehicleController.onboardPendingVehicles(this@AriaForegroundService)
        }

        startHealthMonitor()
        startArrivalMonitor()
        startDriveMonitor()
        startRecapMonitor()

        registerDebugTextInput()

        // Custom wake word ("hey <name>") - no-op unless the driver has opted in via
        // the Setup toggle. Re-armed here on every service (re)launch so a toggle left
        // on from a prior session resumes without revisiting Setup.
        WakeWordEngine.start(this)
        // Ambient cabin listening (2026-07-22) - no-op unless opted in AND not
        // muted (the mute button is a hard LISTENING gate for this feature, not
        // just a speaking gate - see AmbientListener's own doc). Mutually
        // exclusive with the wake word above (see its guard clause).
        AmbientListener.start(this)
        // The mute toggle must stop LISTENING in real time, not just at the next
        // launch - a driver flipping the Cruise mute button mid-drive expects the
        // mic to stop right then. AmbientListener.start()'s own mute check only
        // covers a fresh (re)start, so react to the flow directly here.
        serviceScope.launch {
            ProactivePreferences.muted.collect { muted ->
                if (muted) AmbientListener.stop() else AmbientListener.start(this@AriaForegroundService)
            }
        }
    }

    /**
     * Debug-only: register a receiver that turns a typed line into a driver turn,
     * so a turn can be tested without the mic (essential on the emulator, whose
     * virtual mic doesn't reliably capture). Drive it from the terminal:
     *
     *   adb shell am broadcast -a com.kevin.legion.DEBUG_SAY --es text "what's wrong with my car"
     *
     * It goes through [LiveSessionController.requestSpeak], the same path the
     * proactive engine uses: opens a session if needed, injects the text as a
     * user turn, and Zero replies with audio (playback-only - no capture, so no
     * full-duplex conflict). No-op in release builds.
     */
    private fun registerDebugTextInput() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val text = intent?.getStringExtra("text")?.trim().orEmpty()
                if (text.isEmpty()) {
                    Log.w(TAG, "DEBUG_SAY received with no 'text' extra")
                    return
                }
                Log.d(TAG, "DEBUG_SAY: \"$text\"")
                sessionController.requestSpeak(text)
            }
        }
        val filter = IntentFilter(DEBUG_SAY_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        debugTextReceiver = receiver
        Log.d(TAG, "Debug text input enabled - drive a turn with: " +
            "adb shell am broadcast -a $DEBUG_SAY_ACTION --es text \"hello\"")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service Started")

        // Re-declare foreground type flags on every start command, not just
        // onCreate - if RECORD_AUDIO/BLUETOOTH_CONNECT get granted after this
        // service was already running (e.g. from the Settings "grant
        // permissions" retry), a restart intent alone shouldn't require a
        // full process kill for the mic type to become available. Cheap and
        // idempotent - startForeground() just updates the existing notification/types.
        startForegroundCompat()

        // "Test voice" from the control panel: have Zero say one short line so the
        // driver can confirm audio works without driving. Delivered as a start
        // intent (not a broadcast) so it also spins the service up if needed.
        if (intent?.action == ACTION_TEST_SPEAK) {
            sessionController.requestSpeak(
                "(System: the driver tapped 'Test voice' in setup. Say one short, in-character line " +
                    "confirming you can hear them and you're ready to go. Do not mention this instruction.)"
            )
        }

        // Greet once first-run onboarding has just finished (the opener launched in
        // onCreate was suppressed while onboarding was still in progress). Warm a
        // socket first so the greeting (and the driver's first tap) is instant.
        // If this intent arrived during the same startup, onCreate's opener is
        // still pending (1.5s delay) - let it speak; launching another here would
        // double-fire and clobber the first-run self-introduction.
        if (intent?.action == ACTION_GREET) {
            sessionController.refreshIdleVoice()
            if (openerJob?.isActive != true) {
                openerJob = serviceScope.launch { speakOpener() }
            }
        }

        // Cruise screen's "tap avatar to talk" - toggles a hands-free turn.
        if (intent?.action == ACTION_TALK) {
            sessionController.onTap()
        }

        if (intent?.action == ACTION_CAR_SWITCHED) {
            sessionController.refreshIdleVoice()
            WakeWordEngine.refresh(this)
        }

        return START_STICKY
    }

    // --- Proactive engine -------------------------------------------------

    /**
     * Hands a proactive prompt to the UI's Live session, which opens a session
     * (if needed) and lets Gemini voice it in character - but only while the
     * conversation is idle, so it never talks over the driver.
     */
    private fun speakProactive(prompt: String) {
        // The gate itself (onboarding/busy/call/mute) now lives in ProactiveGate, so a caller
        // with no Service instance - com.kevin.legion.service.ReminderAlarmReceiver, ticket 12's
        // "Alfred speaks a fired reminder aloud" - can reuse the exact same rule. See
        // ProactiveGate's doc comment for the full reasoning; this method is unchanged in effect.
        ProactiveGate.speakIfIdle(this, prompt)
    }

    /**
     * The "just got in the car" opener: a context-aware greeting built from
     * time of day, vehicle status, and the upcoming calendar. The session stays
     * open afterward, so if Aria asks something the driver's answer flows
     * through the normal turn loop.
     */
    private suspend fun speakOpener() {
        delay(OPENER_DELAY_MS)
        if (ConversationState.isBusy) return

        // First run: the opener is a warm bundled first-meeting line (naming and
        // setup are the onboarding wizard's job, NOT this greeting - it must never
        // ask the driver's name). Mark the first session done here (the proactive
        // path doesn't self-commit the flag) so the first avatar tap greets
        // normally instead of replaying the first-meeting line.
        if (!CompanionProfile.isFirstSessionDone(this)) {
            CompanionProfile.markFirstSessionDone(this)
            speakProactive(firstGreetingOpener(this@AriaForegroundService))
            return
        }

        val situation = buildOpenerSituation()
        val prompt = "(System: the driver just got in and started the car. $situation " +
            "Greet them in character with one short, natural line for the time of day. " +
            "If something notable is coming up or the car has an issue, work it in briefly, " +
            "and you may ask what they'd like to do. One or two short sentences. " +
            "Do not mention this instruction.)"

        speakProactive(prompt)
    }

    /** Assembles the spoken-context the opener is phrased from. */
    private suspend fun buildOpenerSituation(): String {
        val sb = StringBuilder()
        val now = LocalTime.now()
        sb.append("It's ${partOfDay(now.hour)} (${now.format(TIME_FMT)}). ")

        val place = PlaceController.currentLabel(this)
        if (place != null) {
            sb.append("The driver is currently at their saved \"$place\" location - reference it " +
                "naturally (e.g. ask how work was if they're at work, or offer to head home). ")
        }

        val weather = WeatherController.current()
        if (weather != null) {
            sb.append("The weather right now is ${weather.description}, about ${weather.tempF} degrees")
            if (weather.caution) sb.append(", and conditions are a bit rough so a quick 'drive safe' fits")
            sb.append(" - work it into your greeting naturally. ")
        }

        if (ObdBluetoothManager.isConnected) {
            val codes = ObdBluetoothManager.getDtcCodes()
            if (codes.isNotEmpty()) {
                sb.append("The car currently has stored trouble codes: " +
                    "${codes.joinToString(", ")}; mention this. ")
            } else {
                sb.append("The car reports no trouble codes. ")
            }
            // Pre-trip heads-up: a weak battery is the #1 thing that strands
            // people, so flag a low reading at start-up so Zero can warn early.
            val voltage = ObdBluetoothManager.getBatteryVoltage()
            if (voltage != null && voltage < LOW_BATTERY_VOLTS) {
                sb.append("The battery is reading low at ${"%.1f".format(voltage)} volts - it may be " +
                    "weak or not charging; gently flag it as something to keep an eye on. ")
            }
        }

        // Roughly monthly, ask the driver to confirm the odometer so the
        // mileage estimate (used for maintenance due-dates) doesn't drift.
        val vehicle = VehicleController.currentVehicle(this)
        if (VehicleController.odometerCheckInDue(vehicle)) {
            sb.append("It's also been a while since the odometer was last confirmed - " +
                "casually ask the driver what it's reading now. ")
            VehicleController.markOdometerPrompted(this, vehicle)
        }
        return sb.toString()
    }

    /**
     * Once per process launch, if recall alerts are enabled, look up open recalls
     * for the car and have Zero mention them in one line. Network call, so it's
     * opt-in and runs after the opener has had a moment. Gated like every other
     * proactive line via [speakProactive].
     */
    private suspend fun checkRecallsOnce() {
        if (recallChecked) return
        recallChecked = true
        if (!DebugSettings.recallAlertsEnabled(this)) return
        delay(RECALL_CHECK_DELAY_MS)
        val recalls = VehicleSpecController.recalls(this)
        if (recalls.isEmpty()) return
        val components = recalls.take(3).map { it.component.ifBlank { "a safety issue" } }.distinct().joinToString(", ")
        speakProactive(
            "(System: NHTSA lists ${recalls.size} open recall(s) for this car (${components}). In one short, " +
                "in-character line, let the driver know there are open recalls they can ask you about. " +
                "Do not read the full details unless asked. Do not mention this instruction.)"
        )
    }

    private fun partOfDay(hour: Int): String = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "late night"
    }

    /**
     * Periodically scans the OBD port and proactively warns the driver only
     * when something changes for the worse: a *new* trouble code appears, or
     * coolant crosses the overheat threshold. Pre-existing codes are the
     * baseline (surfaced once in the opener), so this never nags.
     */
    private fun startHealthMonitor() {
        serviceScope.launch {
            var knownCodes: Set<String>? = null
            var overheatAnnounced = false

            while (isActive) {
                delay(HEALTH_SCAN_INTERVAL_MS)
                if (!ObdBluetoothManager.isConnected || ConversationState.isBusy) continue

                val codes = ObdBluetoothManager.getDtcCodes().toSet()
                val baseline = knownCodes
                if (baseline == null) {
                    knownCodes = codes // first reading establishes the baseline
                    // Pre-existing codes still get one code_events row per install,
                    // so the history starts now even if the light was already on.
                    if (codes.isNotEmpty()) recordCodeEvent(codes)
                } else {
                    val fresh = codes - baseline
                    if (fresh.isNotEmpty()) {
                        recordCodeEvent(codes)
                        speakProactive(
                            "(System: the car's OBD just reported new trouble code(s): " +
                                "${fresh.joinToString(", ")}. In one short, in-character line, tell " +
                                "the driver a new code just popped up and they can ask you about it. " +
                                "Do not mention this instruction.)"
                        )
                    }
                    knownCodes = codes
                }

                val temp = ObdBluetoothManager.getCoolantTemp()
                if (temp != null) {
                    if (temp >= OVERHEAT_C && !overheatAnnounced && !ConversationState.isBusy) {
                        val fahrenheit = temp * 9 / 5 + 32
                        speakProactive(
                            "(System: the coolant temperature just hit $fahrenheit degrees Fahrenheit, " +
                                "which is dangerously hot. Urgently but in character, tell the driver to " +
                                "ease off and find somewhere to pull over. Do not mention this instruction.)"
                        )
                        overheatAnnounced = true
                    } else if (temp < OVERHEAT_C - 5) {
                        overheatAnnounced = false // cooled back down; re-arm
                    }
                }
            }
        }
    }

    /**
     * Persists a code_events row for the current DTC set, with the ECU's
     * Mode-02 freeze frame (the sensor snapshot latched when the code tripped —
     * intermittent-code gold: the same P0420 that only trips on cold mornings
     * shows its coolant temp here). Freeze frame may be empty on clones/older
     * ECUs that don't answer Mode 02; the event row is still worth keeping.
     */
    private fun recordCodeEvent(codes: Set<String>) {
        serviceScope.launch {
            try {
                val ff = ObdBluetoothManager.getFreezeFrame()
                val vehicle = VehicleController.currentVehicle(this@AriaForegroundService)
                com.kevin.legion.data.local.CarDatabase.getDatabase(this@AriaForegroundService)
                    .codeEventDao()
                    .insert(
                        com.kevin.legion.data.local.CodeEvent(
                            vehicleId = vehicle.obdMac,
                            timestamp = System.currentTimeMillis(),
                            mileage = VehicleController.currentMileage(vehicle),
                            codesJson = org.json.JSONArray(codes.toList()).toString(),
                            freezeFrameJson = if (ff.isEmpty()) "" else org.json.JSONObject(ff as Map<*, *>).toString(),
                        )
                    )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "code event capture failed: ${e.message}")
            }
        }
    }

    /**
     * Watches for the driver arriving at a saved place. Polls the nearest saved
     * place and, on the enter transition (away/elsewhere -> here), has Zero read
     * out any pending reminders for it. The place the driver starts at is taken
     * as the baseline so it doesn't announce on launch.
     */
    private fun startArrivalMonitor() {
        serviceScope.launch {
            var lastPlace: String? = null
            var initialized = false

            while (isActive) {
                delay(ARRIVAL_SCAN_INTERVAL_MS)
                val place = PlaceController.currentLabel(this@AriaForegroundService)

                if (!initialized) {
                    initialized = true        // baseline: don't announce where we started
                } else if (place != null && place != lastPlace) {
                    onArrived(place)
                }
                lastPlace = place
            }
        }
    }

    private suspend fun onArrived(place: String) {
        val reminders = ReminderController.activeFor(this, place)
        if (reminders.isEmpty()) return

        // If the driver is mid-conversation, wait up to 30s for it to finish so the
        // reminder isn't silently dropped - it's a routine proactive, not an urgent one.
        val deadline = System.currentTimeMillis() + 30_000L
        while (ConversationState.isBusy && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(2_000)
        }
        if (ConversationState.isBusy) return // still busy after 30s — skip rather than interrupt

        val list = reminders.joinToString("; ") { it.text }
        speakProactive(
            "(System: the driver just arrived at their \"$place\". They left reminders for here: " +
                "$list. In one short, in-character line, surface what they wanted to remember. " +
                "Do not mention this instruction.)"
        )
    }

    /**
     * One loop covering the drive-aware proactive moments (tuned to be occasional,
     * and never talking over the driver via [speakProactive]): a rest-stop nudge on
     * a long continuous drive, an odometer-milestone celebration, and the odd
     * in-character musing on a long quiet stretch. Movement is inferred from GPS
     * deltas; a sustained stop ends the current drive.
     */
    private fun startDriveMonitor() {
        serviceScope.launch {
            var lastLocation: Location? = null
            var driveStartedAt = 0L     // 0 = not currently driving
            var lastMovedAt = 0L
            var breakAnnounced = false
            var quietSince = System.currentTimeMillis() // start of the current quiet stretch
            var lastChatterAt = 0L
            // Rough-weather alerting. Null until the first reading is seen, so the
            // first poll of a drive can't fire: we only speak on a real
            // calm -> rough TRANSITION, never on "it was already raining when you
            // got in" (the startup greeting already covers that, and repeating it
            // would just be the app noticing the weather at the driver).
            var lastCaution: Boolean? = null
            var lastWeatherAlertAt = 0L

            while (isActive) {
                delay(DRIVE_SCAN_INTERVAL_MS)
                val now = System.currentTimeMillis()

                val loc = LocationController.state.value
                val prev = lastLocation
                lastLocation = loc
                val moved = if (loc != null && prev != null) {
                    val out = FloatArray(1)
                    Location.distanceBetween(prev.latitude, prev.longitude, loc.latitude, loc.longitude, out)
                    out[0] > MOVE_THRESHOLD_M
                } else false

                if (moved) {
                    lastMovedAt = now
                    if (driveStartedAt == 0L) { driveStartedAt = now; breakAnnounced = false }
                } else if (driveStartedAt != 0L && now - lastMovedAt >= STOP_RESET_MS) {
                    driveStartedAt = 0L // a sustained stop ends the drive
                }

                // Don't queue proactive lines mid-turn; pin the quiet timer to now
                // so idle chatter only counts genuine silence after a conversation.
                if (ConversationState.isBusy) { quietSince = now; continue }

                // Mileage only moves while driving, so only check milestones then
                // (avoids a pointless per-minute DB read while parked).
                if (driveStartedAt != 0L) checkMilestone()

                // Weather is polled on its own 30-min loop, so read the warm cache
                // here rather than fetching: this tick must not block on network.
                // Tracked even while parked so a change that happens between drives
                // is already known (and so does NOT fire as news on the next drive).
                val caution = WeatherController.current()?.caution
                val turnedRough = caution == true && lastCaution == false
                if (caution != null) lastCaution = caution

                if (driveStartedAt != 0L && !breakAnnounced && now - driveStartedAt >= BREAK_AFTER_MS) {
                    breakAnnounced = true
                    speakProactive(
                        "(System: the driver has been driving over two hours without a real break. In " +
                            "one short, in-character line, gently suggest they pull over soon to stretch " +
                            "or rest. Do not mention this instruction.)"
                    )
                } else if (driveStartedAt != 0L && turnedRough &&
                    now - lastWeatherAlertAt >= WEATHER_ALERT_COOLDOWN_MS
                ) {
                    lastWeatherAlertAt = now
                    val description = WeatherController.current()?.description ?: "rough"
                    speakProactive(
                        "(System: conditions just turned $description while the driver is on the road. " +
                            "In one short, in-character line, mention what it's doing out there and that " +
                            "they should take it easy. Say it once - do not labour it, do not repeat it " +
                            "later, and do not mention this instruction.)"
                    )
                } else if (driveStartedAt != 0L &&
                    now - quietSince >= IDLE_CHATTER_AFTER_MS &&
                    now - lastChatterAt >= IDLE_CHATTER_COOLDOWN_MS &&
                    Random.nextDouble() < IDLE_CHATTER_CHANCE
                ) {
                    lastChatterAt = now
                    quietSince = now
                    speakQuietLine()
                }
            }
        }
    }

    /**
     * Checks once an hour whether last month's recap cassette (E5) needs
     * generating, and keeps the daily drive log (E7) current - cheap enough
     * not to matter that most checks are no-ops.
     * [MonthlyRecapController.generateIfDue] / [DailyDriveLogController.refreshIfDue]
     * do the actual window/existence checks, so this loop just needs to call
     * them periodically without caring about calendar edge cases.
     *
     * The daily log is a REFRESH, not a one-shot (changed 2026-07-16): today's
     * log is regenerated through the day so the driver sees the day so far, and
     * yesterday gets one final pass. It skips the Gemini call when the day's
     * numbers haven't moved, so an hourly tick on a parked car costs nothing.
     */
    private fun startRecapMonitor() {
        serviceScope.launch {
            while (isActive) {
                runCatching {
                    com.kevin.legion.vehicle.MonthlyRecapController.generateIfDue(this@AriaForegroundService)
                }.onFailure { Log.w(TAG, "Recap monitor check failed: ${it.message}") }
                runCatching {
                    com.kevin.legion.vehicle.DailyDriveLogController.refreshIfDue(this@AriaForegroundService)
                }.onFailure { Log.w(TAG, "Daily drive log check failed: ${it.message}") }
                delay(RECAP_CHECK_INTERVAL_MS)
            }
        }
    }

    /** Celebrates crossing a 10k-mile odometer mark once (seed/track in [lastMilestoneAnnounced]). */
    private suspend fun checkMilestone() {
        val vehicle = VehicleController.currentVehicle(this)
        val mileage = VehicleController.currentMileage(vehicle)
        if (mileage <= 0) return
        val floor = (mileage / MILESTONE_STEP) * MILESTONE_STEP
        if (lastMilestoneAnnounced < 0) {
            lastMilestoneAnnounced = floor // seed: only fire on NEW crossings, not retroactively
            return
        }
        if (floor > lastMilestoneAnnounced && floor >= MILESTONE_STEP) {
            lastMilestoneAnnounced = floor
            // floor stays computed off the raw Int (arithmetic, ticket 10 leaves this alone) - but
            // the sentence Zero actually SPEAKS asserts a number, so it needs the same caveat every
            // other spoken/rendered surface carries. mileageCaveat is null exactly when `mileage`
            // IS the driver's own last typed reading, so a confirmed crossing still reads plainly.
            val caveat = VehicleController.mileageCaveat(vehicle)
            val caveatNote = if (caveat != null) " (that reading is $caveat - don't state it as an exact figure)" else ""
            speakProactive(
                "(System: the car's odometer just rolled past ${"%,d".format(floor)} miles$caveatNote. In one short, " +
                    "in-character line, mark the milestone with some old-car pride or grumbling. Do not " +
                    "mention this instruction.)"
            )
        }
    }

    /**
     * A quiet-stretch proactive line: when there's anything on the car to-do /
     * wishlist, occasionally offer to run through it (the "ask if they want to hear
     * the list" behavior); otherwise a brief in-character musing.
     */
    private suspend fun speakQuietLine() {
        // One list now, counted whole (2026-08-11: "dissolve the car list, merge everything into
        // one list model") - there is no "Car" sub-list left to count. See NotesController.theList.
        val open = NotesController.openItemCount(this)
        if (open > 0 && Random.nextDouble() < TODO_OFFER_SHARE) {
            speakProactive(
                "(System: the driver has $open open item(s) on their list. In one short, " +
                    "in-character line, offer to run through the list with them if they'd like. Do not " +
                    "mention this instruction.)"
            )
        } else {
            speakProactive(
                "(System: it's been quiet for a while on this drive. Offer one brief, in-character " +
                    "remark to fill the silence - a small observation, some grumbling, or something you " +
                    "remember about the driver. Keep it short and natural, and don't ask a question " +
                    "unless it feels natural. Do not mention this instruction.)"
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")
        debugTextReceiver?.let { runCatching { unregisterReceiver(it) } }
        WakeWordEngine.stop()
        AmbientListener.stop()
        TelephonyController.destroy()
        if (this::sessionController.isInitialized) sessionController.destroy()
        serviceScope.cancel()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 14+ hard-crashes (SecurityException) a foreground service that
     * declares a "special use" type - microphone, connectedDevice - without
     * actually holding that type's permission at the moment startForeground()
     * runs. This used to declare FOREGROUND_SERVICE_TYPE_MICROPHONE
     * unconditionally on API 30+ regardless of whether RECORD_AUDIO was
     * granted - MainActivity's permission flow starts this service even on a
     * denial (by design, so a mic denial doesn't block the rest of the app),
     * which made that denial a guaranteed startup crash loop on Android 14
     * devices, worse once a "don't ask again" denial meant no dialog ever
     * showed again on retry. Each type flag is now gated on actually holding
     * its permission right now, so a missing grant just means that specific
     * capability is unavailable rather than crashing the whole service.
     */
    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // NOT MEDIA_PLAYBACK. It was requested unconditionally here while the
            // manifest only declares connectedDevice|dataSync|microphone, so
            // startForeground threw
            //   IllegalArgumentException: foregroundServiceType 0x00000083 is not
            //   a subset of foregroundServiceType attribute 0x00000091
            // and killed the process on the FIRST LINE of the assistant's own
            // onCreate. The assistant could never start, on any build, since the
            // port - found on 2026-08-02 the first time anything ever tapped
            // tap-to-talk.
            //
            // Removed rather than declared: this app does not play media. Media3
            // was dropped in the 2026-07-31 pivot and `media/MusicController`
            // drives Spotify's OWN MediaSession rather than owning playback, so
            // claiming the type would assert a capability that no longer exists.
            // The flag is a leftover from Midnight AI, when the app did own music.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val bluetoothOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                granted(Manifest.permission.BLUETOOTH_CONNECT)
            } else true // pre-S Bluetooth permissions are install-time, not runtime
            if (bluetoothOk) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && granted(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ARIA Co-Pilot Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIA Co-Pilot")
            .setContentText("Persistent AI co-pilot is active.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AriaService"
        private const val CHANNEL_ID = "aria_channel"
        private const val NOTIFICATION_ID = 1

        // Debug-only broadcast action to drive a turn from typed text (see
        // registerDebugTextInput). Has no effect in release builds.
        private const val DEBUG_SAY_ACTION = "com.kevin.legion.DEBUG_SAY"

        // Start-intent action: the control panel's "Test voice" button (any build).
        const val ACTION_TEST_SPEAK = "com.kevin.legion.TEST_SPEAK"

        // Start-intent action: greet right after first-run onboarding completes.
        const val ACTION_GREET = "com.kevin.legion.GREET"

        // Start-intent action: Cruise screen's tap-avatar-to-talk.
        const val ACTION_TALK = "com.kevin.legion.TALK"

        // Start-intent actions: show / hide the floating companion badge. Show
        // is fired right after LiveToolbox opens nav/music fullscreen; hide is
        // fired by MainActivity.onResume() once the driver returns to Midnight
        // AI (e.g. via Home). Show is gated on the SYSTEM_ALERT_WINDOW grant.
        /**
         * The driver switched cars (car manager, 2026-07-16). Voice is per-car now,
         * so a warm socket is holding the PREVIOUS car's voice - the same shape as
         * the "default voice after onboarding" field bug. Re-prewarm to pick up the
         * new car's voice.
         */
        const val ACTION_CAR_SWITCHED = "com.kevin.legion.CAR_SWITCHED"

        // Settle time after start before the opener, so it doesn't talk over
        // the engine cranking / the driver getting situated.
        private const val OPENER_DELAY_MS = 1500L

        // Wait past the opener before the (opt-in) recall heads-up, so the two
        // proactive lines don't collide at startup.
        private const val RECALL_CHECK_DELAY_MS = 12_000L

        // How often the proactive health monitor scans the OBD port.
        private const val HEALTH_SCAN_INTERVAL_MS = 5 * 60 * 1000L

        // How often the running-loop side of memory consolidation sweeps for
        // pending sessions (the startup sweep handles the other case - see the
        // launch site's comment). Same cadence class as the health monitor;
        // MemoryConsolidator itself no-ops instantly whenever it's not safe to run.
        private const val MEMORY_CONSOLIDATION_INTERVAL_MS = 5 * 60 * 1000L

        // How often the arrival monitor checks whether the driver reached a
        // saved place. Frequent enough to catch arrivals promptly, cheap since
        // it's just a GPS distance check against saved places.
        private const val ARRIVAL_SCAN_INTERVAL_MS = 20 * 1000L

        // Coolant temp (Celsius) at/above which Aria proactively warns.
        private const val OVERHEAT_C = 110

        // Battery voltage below which the start-up opener flags a weak/uncharged
        // battery. Set low to avoid false alarms (a healthy resting battery is
        // ~12.4-12.7V; running/charging is ~14V).
        private const val LOW_BATTERY_VOLTS = 12.0

        // Weather cache: retry fast until the first fetch lands, then refresh slowly.
        // How often to push a snapshot to Drive while the engine is running. Short
        // enough that cutting the key on arrival costs only the last few minutes;
        // long enough not to hammer the driver's hotspot. maybeAutoSync's own
        // throttle is the real floor.
        private const val DRIVE_SYNC_INTERVAL_MS = 5 * 60 * 1000L

        private const val WEATHER_RETRY_MS = 60 * 1000L
        private const val WEATHER_REFRESH_MS = 30 * 60 * 1000L

        // Floor between rough-weather alerts. Generous on purpose: weather that
        // flickers around the caution threshold (drizzle the WMO code can't make
        // its mind up about) must not turn Zero into a weather nag, which is the
        // sec 9.1 failure mode where noticing becomes chatter.
        private const val WEATHER_ALERT_COOLDOWN_MS = 90 * 60 * 1000L

        // Drive-aware proactive monitor (occasional cadence).
        private const val DRIVE_SCAN_INTERVAL_MS = 60 * 1000L         // poll once a minute
        private const val MOVE_THRESHOLD_M = 50f                      // >50 m in a minute = driving
        private const val STOP_RESET_MS = 5 * 60 * 1000L             // 5 min stationary ends a drive
        private const val BREAK_AFTER_MS = 2 * 60 * 60 * 1000L       // suggest a break after ~2 h
        private const val MILESTONE_STEP = 10_000                    // celebrate every 10k miles
        private const val IDLE_CHATTER_AFTER_MS = 45 * 60 * 1000L    // quiet this long before musing
        private const val IDLE_CHATTER_COOLDOWN_MS = 60 * 60 * 1000L // at most ~once an hour
        private const val IDLE_CHATTER_CHANCE = 0.3                  // randomize so it isn't clockwork
        // When there are open to-do items, share of quiet lines that become an
        // "want to hear your list?" offer instead of a generic musing.
        private const val TODO_OFFER_SHARE = 0.5

        // Monthly recap cassette (E5): only fires within generateIfDue's grace
        // window and once per month, so an hourly check costs nothing.
        private const val RECAP_CHECK_INTERVAL_MS = 60 * 60 * 1000L

        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    }
}
