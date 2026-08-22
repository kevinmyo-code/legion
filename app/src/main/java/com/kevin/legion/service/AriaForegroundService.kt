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
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.MemoryConsolidator
import com.kevin.legion.ai.ReflectionEngine
import com.kevin.legion.location.ArrivalController
import com.kevin.legion.location.GeofenceManager
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.ai.OnboardingState
import com.kevin.legion.calendar.CalendarProvider
import com.kevin.legion.calendar.OpenerCalendarBriefing
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.RecallCheckResult
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleSpecController
import com.kevin.legion.sync.SyncEngine
import com.kevin.legion.util.Temp
import com.kevin.legion.weather.WeatherController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // The foreground-service type bitmask this service actually holds right now, as last
    // applied via startForeground(). -1 means "never started foreground yet" (cold start,
    // before onCreate's first startForegroundCompat() call). Tracked explicitly rather than
    // re-derived, because the platform gives no query for "what type set is this service
    // CURRENTLY running under" - only startForeground() itself sets it, so we have to
    // remember what we last asked for. This is the fix for the 2026-08-17 regression: a
    // process-importance check (isInForegroundEligibleState) is a fine GATE for ACQUIRING a
    // restricted type the service does not yet hold, but it is not stable - MainActivity
    // going off-screen after a live conversation flips importance from FOREGROUND to
    // FOREGROUND_SERVICE (125) with the socket still open and the mic still capturing. If
    // startForegroundCompat() re-evaluated the gate on every call and rebuilt the type set
    // from scratch, that ordinary backgrounding would silently strip the microphone type off
    // an ALREADY-RUNNING, ALREADY-GRANTED capture and kill it stone dead on API 34 - with no
    // exception, no log signal, nothing but a mic that stops working. See
    // startForegroundCompat's doc comment for the acquire-vs-retain distinction this field
    // exists to enforce.
    @Volatile private var currentForegroundTypes: Int = -1

    // Debug-only: lets a turn be driven by typed text over adb instead of the
    // mic. The emulator's virtual mic is unreliable (replays host audio, can't do
    // full-duplex), so this is how you test the brain + voice output there. Null
    // (and never registered) in release builds.
    private var debugTextReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
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
        // Hold the App Remote connection here, in the foreground service, rather than only
        // ever connecting per command (ticket 02, map decision 5, Kevin 2026-08-19).
        //
        // THIS DELIBERATELY VIOLATES Spotify's own documented lifecycle guidance: "connect in
        // onStart, disconnect in onStop... otherwise Spotify will not be able to shutdown."
        // Do not "fix" this by moving the connect to an Activity lifecycle callback and adding
        // a matching disconnect() somewhere. The violation is intentional: a per-command connect
        // costs a real round trip of latency on every single utterance, and this is a car
        // assistant where that latency is the whole cold-start experience the ticket exists to
        // fix ("kill Spotify, then ask for music - it plays"). This service never calls
        // SpotifyController.disconnect() anywhere in its own lifecycle for the same reason -
        // see onDestroy below. connectSilently no-ops instantly when no client ID is saved, so a
        // driver who never set Spotify up pays nothing here; it also joins whatever connect
        // attempt MainActivity.onResume may already have started rather than racing a second one
        // (see SpotifyController.startConnect's own doc).
        SpotifyController.connectSilently(this)
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
        serviceScope.launch {
            ProactiveBus.requestSpeak.collect {
                sessionController.requestSpeak(it.prompt, it.listensForReply, it.carriesReadThroughContent)
            }
        }
        // The other half of a ring-listening window (2026-08-21): TelephonyController is a platform
        // callback with no session reference, so it signals through the bus and this collector
        // closes the microphone when the phone stops ringing.
        serviceScope.launch { ProactiveBus.stopListening.collect { sessionController.stopListening() } }

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

        // The automatic maintenance-interval seed that used to run here (onboardPendingVehicles) was
        // DELETED (ticket 14, `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`)
        // - it fired on every service start and silently seeded 54 rows / 49 empty anchors across
        // Kevin's roster without him ever asking for one. A car's schedule now starts empty; the
        // only way it gets populated is a deliberate, driver-triggered diff-and-confirm.

        startHealthMonitor()
        startArrivalMonitor()
        startDriveMonitor()
        startRecapMonitor()

        registerDebugTextInput()

        // Custom wake word ("hey <name>") - no-op unless the driver has opted in via
        // the Setup toggle. Re-armed here on every service (re)launch so a toggle left
        // on from a prior session resumes without revisiting Setup.
        WakeWordEngine.start(this)
        // Ambient cabin listening was started here (2026-07-22) and is RETIRED
        // (2026-08-21, Kevin, `.scratch/proactive-mode/issues/12-retire-ambient-listening.md`).
        // It was the one raise that let a sub-agent write the spoken line itself, which is the
        // shape ticket 10's contract exists to forbid - and its opt-in had no UI, so it could
        // never actually run. Its real-time mute collector went with it: nothing else here holds
        // the microphone open, so there is no listening left for a mute flip to stop.
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
                "(System: the user tapped 'Test voice' in setup. Say one short, in-character line " +
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
            sessionController.onTap(fromWakeWord = intent.getBooleanExtra(EXTRA_FROM_WAKE_WORD, false))
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
    private fun speakProactive(raise: ProactiveRaise) {
        // The gate itself (onboarding/busy/call/mute) now lives in ProactiveGate, so a caller
        // with no Service instance - com.kevin.legion.service.ReminderAlarmReceiver, ticket 12's
        // "Alfred speaks a fired reminder aloud" - can reuse the exact same rule. See
        // ProactiveGate's doc comment for the full reasoning; this method is unchanged in effect.
        ProactiveGate.speakIfIdle(this, raise)
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

        // First run: mark the first session done here (the proactive path doesn't
        // self-commit the flag) so the first avatar tap greets normally instead of
        // replaying the first-meeting line - [LiveSessionController.beginConversation]
        // reads [firstGreetingOpener] itself off this same flag on that first tap.
        // The raise that used to happen HERE too - speaking the first-meeting line
        // unprompted, on ignition, before the driver ever touched the avatar - was
        // retired 2026-08-18 (Kevin, `.scratch/proactive-mode/issues/
        // 01-one-gate-not-three.md` section 4): it's unsolicited speech with nothing
        // due, the same shape as the retired idle-chatter lines below. The flag write
        // stays; only the speech goes.
        if (!CompanionProfile.isFirstSessionDone(this)) {
            CompanionProfile.markFirstSessionDone(this)
            return
        }

        // 2026-08-20, Kevin: "the ai proactive greeting is based on car and driving, we dont need
        // that anymore. since we are not a car app anymore." LEGION is a phone assistant with a
        // fleet ASPECT, and this line was asserting a whole situation - that he had got in, that he
        // had started an engine, that a drive was beginning - none of which it had checked. The
        // opener fires when the app comes up, which happens at a desk far more often than it
        // happens in a driveway.
        //
        // Same posture as CLAUDE.md sec 7's outcome-verb rule, pointed at context rather than at
        // actions: do not narrate a situation you have not established. buildOpenerSituation now
        // supplies the car framing itself, and only when the dongle says he is actually in the car.
        // 2026-08-21, Kevin, on-device: the opener said he had "lunch with Sam". There is no Sam.
        // The old line here asked the model to work in anything "notable coming up" while
        // buildOpenerSituation handed it no schedule at all, so the one instruction with no data
        // behind it was the one it answered - see [OpenerCalendarBriefing]'s doc comment. The
        // situation block is now the model's whole world for this turn, and the prompt says so.
        val situation = buildOpenerSituation()
        val prompt = "(System: the user just opened the app. $situation " +
            "Greet them in character with one short, natural line for the time of day. " +
            "Everything you know about their situation is in this instruction; you may work one " +
            "detail of it in briefly if it genuinely matters, and you may ask what they'd like " +
            "to do. Do NOT mention any appointment, meeting, plan, task, message or person that " +
            "is not stated above - if it was not given to you here, it does not exist, and a " +
            "made-up one is the worst thing you can say. One or two short sentences. " +
            "Do not assume where they are or what they are about to do. " +
            "Do not mention this instruction.)"

        speakProactive(
            ProactiveRaise(
                ruleId = "startup_opener",
                category = ProactiveCategory.TIMING,
                reason = "the app was opened",
                facts = situation,
                prompt = prompt,
            )
        )
    }

    /** Assembles the spoken-context the opener is phrased from. */
    private suspend fun buildOpenerSituation(): String {
        val sb = StringBuilder()
        val now = LocalTime.now()
        sb.append("It's ${partOfDay(now.hour)} (${now.format(TIME_FMT)}). ")

        val place = PlaceController.currentLabel(this)
        if (place != null) {
            sb.append("The user is currently at their saved \"$place\" location - reference it " +
                "naturally if it fits (e.g. ask how work was if they're at work). ")
        }

        val weather = WeatherController.current()
        if (weather != null) {
            sb.append("The weather right now is ${weather.description}, about ${weather.tempF} degrees")
            // Was an unconditional "a quick 'drive safe' fits". Rough weather is worth a word
            // wherever he is; telling him to drive safely at a desk is the car assumption again.
            if (weather.caution) sb.append(", and conditions are a bit rough so a word about it fits")
            sb.append(" - work it into your greeting naturally. ")
        }

        // The real schedule, read straight off the device, so the model never has to fill the gap
        // itself. Query on IO - this whole builder runs on the service's Main-dispatcher scope and
        // a ContentResolver query is disk work.
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val hasCalendar = CalendarProvider.hasReadPermission(this)
        val events = if (hasCalendar) {
            withContext(Dispatchers.IO) {
                CalendarProvider.eventsInWindow(
                    this@AriaForegroundService,
                    nowMs,
                    nowMs + OpenerCalendarBriefing.WINDOW_HOURS * 60L * 60L * 1000L,
                )
            }
        } else {
            emptyList()
        }
        sb.append(OpenerCalendarBriefing.forOpener(events, nowMs, zone, hasCalendar))

        // Everything below this line is car context, and it is gated on the dongle actually being
        // connected - the one signal that says he is IN the car rather than merely owning one.
        if (ObdBluetoothManager.isConnected) {
            sb.append("The user is in the car right now (the OBD dongle is connected), so car " +
                "context is fair game and a word about the drive fits. ")
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

            // Roughly monthly, ask the driver to confirm the odometer so the mileage estimate
            // (used for maintenance due-dates) doesn't drift. Moved INSIDE the connected branch
            // 2026-08-20: asking someone to read their odometer while they are sitting at a desk
            // is unanswerable, and it also consumed the monthly prompt slot via
            // markOdometerPrompted, so a nudge he could not act on suppressed the next one he
            // could. The wrong-place ask was not merely noise; it was self-defeating.
            val vehicle = VehicleController.currentVehicle(this)
            if (VehicleController.odometerCheckInDue(vehicle)) {
                sb.append("It's also been a while since the odometer was last confirmed - " +
                    "casually ask the user what it's reading now. ")
                VehicleController.markOdometerPrompted(this, vehicle)
            }
        }
        return sb.toString()
    }

    /**
     * Once per process launch, if recall alerts are enabled, look up open recalls
     * for the car and have Zero mention them in one line. Network call, so it's
     * opt-in and runs after the opener has had a moment. Gated like every other
     * proactive line via [speakProactive].
     *
     * Uses the same [VehicleSpecController.recalls] gate the voice tool `check_recalls` does
     * (identity-present, not [com.kevin.legion.data.local.Vehicle.confirmed] - ticket 12,
     * `.scratch/fleet-maintenance/issues/12-a-recall-button.md`), so the two can no longer
     * disagree about whether this car is fit to look up. A missing identity or a failed lookup
     * both mean "nothing to proactively say" here - unlike the button or the voice tool, this
     * path has no UI to say the refusal in, so it stays silent rather than half-announcing.
     */
    private suspend fun checkRecallsOnce() {
        if (recallChecked) return
        recallChecked = true
        if (!DebugSettings.recallAlertsEnabled(this)) return
        delay(RECALL_CHECK_DELAY_MS)
        val outcome = VehicleSpecController.recalls(this)
        // Staying silent is right for this channel (see the doc above), but silence with NO TRACE
        // is a different thing. If NHTSA is persistently unreachable from a network, or the car's
        // identity is never set, this path would otherwise no-op every session forever with
        // nothing anywhere to show it had even tried - undiagnosable by construction. Logged on
        // review, 2026-08-15, as the cheap half of "doing nothing is acceptable, doing nothing
        // silently is not".
        when (outcome) {
            is RecallCheckResult.IdentityMissing ->
                Log.d(TAG, "recall check skipped: identity incomplete (${outcome.missing.joinToString()})")
            is RecallCheckResult.LookupFailed ->
                Log.d(TAG, "recall check failed: NHTSA lookup did not return a usable result")
            is RecallCheckResult.Checked ->
                Log.d(TAG, "recall check ok: ${outcome.recalls.size} open recall(s)")
        }
        val recalls = (outcome as? RecallCheckResult.Checked)?.recalls.orEmpty()
        if (recalls.isEmpty()) return
        val components = recalls.take(3).map { it.component.ifBlank { "a safety issue" } }.distinct().joinToString(", ")
        speakProactive(
            ProactiveRaise(
                ruleId = "open_recalls",
                category = ProactiveCategory.FLEET,
                reason = "NHTSA lists ${recalls.size} open recall(s)",
                facts = "open recalls: $components",
                prompt = "(System: NHTSA lists ${recalls.size} open recall(s) for this car (${components}). In one short, " +
                    "in-character line, let the user know there are open recalls they can ask you about. " +
                    "Do not read the full details unless asked. Do not mention this instruction.)"
            )
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
                            ProactiveRaise(
                                ruleId = "new_trouble_code",
                                category = ProactiveCategory.SAFETY,
                                reason = "new OBD code(s) ${fresh.joinToString(", ")}",
                                facts = "stored trouble codes: ${codes.joinToString(", ")}",
                                prompt = "(System: the car's OBD just reported new trouble code(s): " +
                                    "${fresh.joinToString(", ")}. In one short, in-character line, tell " +
                                    "the user a new code just popped up and they can ask you about it. " +
                                    "Do not mention this instruction.)"
                            )
                        )
                    }
                    knownCodes = codes
                }

                val temp = ObdBluetoothManager.getCoolantTemp()
                if (temp != null) {
                    if (temp >= OVERHEAT_C && !overheatAnnounced && !ConversationState.isBusy) {
                        val spokenTemp = Temp.spoken(this@AriaForegroundService, temp.toDouble())
                        speakProactive(
                            ProactiveRaise(
                                ruleId = "coolant_overheat",
                                category = ProactiveCategory.SAFETY,
                                reason = "coolant reached $spokenTemp, over the overheat threshold",
                                facts = "coolant temperature $spokenTemp",
                                prompt = "(System: the coolant temperature just hit $spokenTemp, " +
                                    "which is dangerously hot. Urgently but in character, tell the user to " +
                                    "ease off and find somewhere to pull over. Do not mention this instruction.)"
                            )
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
     *
     * **This is now the FALLBACK path, not the only one (ticket 05).** [GeofenceManager] registers
     * real OS geofences for the nearest-N saved places and fires event-driven via
     * [com.kevin.legion.location.GeofenceBroadcastReceiver], including with this Service not
     * alive - this loop's 20s poll only matters for a driver who declined background location
     * (`BackgroundLocationAccess.current` != Granted), where geofences silently never fire and
     * this is all he has. Both paths converge on the same [ArrivalController.onArrived] so an
     * arrival is never announced twice by coincidence of both firing near-simultaneously... except
     * that it CAN be, in principle (nothing here dedupes across the two signals) - accepted for
     * now since the geofence radius and this poll's match radius are the same 150m, so a
     * double-fire would require both signals to land in the same ~20s window, which ticket 05
     * left as a phone-verification item rather than a guarantee.
     *
     * Also drives [GeofenceManager]'s "nearest-N re-registered as he moves" requirement (decision
     * 9) - piggybacking on this loop's existing cadence rather than standing up a second timer,
     * since [GeofenceManager.registerNearest] is itself cheap and idempotent (it diffs against
     * what's currently registered and only touches the delta).
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
                    ArrivalController.onArrived(this@AriaForegroundService, place)
                }
                lastPlace = place

                runCatching { GeofenceManager.registerNearest(this@AriaForegroundService) }
                    .onFailure { Log.w(TAG, "geofence re-registration failed: ${it.message}") }
            }
        }
    }

    /**
     * One loop covering the drive-aware proactive moments (tuned to be occasional,
     * and never talking over the driver via [speakProactive]): a rest-stop nudge on
     * a long continuous drive and an odometer-milestone celebration. (The third thing
     * this loop used to do on a long quiet stretch - offer to run through the list, or
     * muse to fill silence - was retired 2026-08-18: `speakQuietLine` and the idle-
     * chatter timer that drove it fired on the ABSENCE of conversation rather than on
     * anything being due, which CLAUDE.md sec 7 names directly as the shape of a
     * mechanism engineered to produce engagement. See `.scratch/proactive-mode/
     * issues/01-one-gate-not-three.md` section 4.) Movement is inferred from GPS
     * deltas; a sustained stop ends the current drive.
     */
    private fun startDriveMonitor() {
        serviceScope.launch {
            var lastLocation: Location? = null
            var driveStartedAt = 0L     // 0 = not currently driving
            var lastMovedAt = 0L
            var breakAnnounced = false
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

                // Don't queue proactive lines mid-turn.
                if (ConversationState.isBusy) continue

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
                        ProactiveRaise(
                            ruleId = "long_drive_break",
                            category = ProactiveCategory.FLEET,
                            reason = "over two hours of continuous driving",
                            facts = "continuous driving time over two hours",
                            prompt = "(System: the user has been driving over two hours without a real break. In " +
                                "one short, in-character line, gently suggest they pull over soon to stretch " +
                                "or rest. Do not mention this instruction.)"
                        )
                    )
                } else if (driveStartedAt != 0L && turnedRough &&
                    now - lastWeatherAlertAt >= WEATHER_ALERT_COOLDOWN_MS
                ) {
                    lastWeatherAlertAt = now
                    val description = WeatherController.current()?.description ?: "rough"
                    speakProactive(
                        ProactiveRaise(
                            ruleId = "rough_weather_driving",
                            category = ProactiveCategory.SAFETY,
                            reason = "conditions turned $description while driving",
                            facts = "current conditions: $description",
                            prompt = "(System: conditions just turned $description while the user is on the road. " +
                                "In one short, in-character line, mention what it's doing out there and that " +
                                "they should take it easy. Say it once - do not labour it, do not repeat it " +
                                "later, and do not mention this instruction.)"
                        )
                    )
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
                ProactiveRaise(
                    ruleId = "odometer_milestone",
                    category = ProactiveCategory.FLEET,
                    reason = "odometer crossed ${"%,d".format(floor)} miles",
                    facts = "odometer ${"%,d".format(floor)} miles$caveatNote",
                    prompt = "(System: the car's odometer just rolled past ${"%,d".format(floor)} miles$caveatNote. In one short, " +
                        "in-character line, mark the milestone with some old-car pride or grumbling. Do not " +
                        "mention this instruction.)"
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "Service Destroyed")
        debugTextReceiver?.let { runCatching { unregisterReceiver(it) } }
        WakeWordEngine.stop()
        TelephonyController.destroy()
        if (this::sessionController.isInitialized) sessionController.destroy()
        serviceScope.cancel()
        // Deliberately NO SpotifyController.disconnect() here. See the comment on
        // SpotifyController.connectSilently's call in onCreate above: the held connection is a
        // conscious violation of Spotify's own "disconnect in onStop" guidance, made for
        // latency, and this is the other half of that same choice - if this service is destroyed
        // and recreated (process death, a restart), the next onCreate reconnects on its own; we
        // do not spend the drive tearing the connection down first only to rebuild it seconds
        // later.
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * True only while this process is actually foreground from the platform's point of view -
     * the documented signal for "an FGS start claiming a while-in-use-restricted type will be
     * allowed here." `RunningAppProcessInfo.importance` is read off THIS app's own package name
     * from [android.app.ActivityManager.getRunningAppProcesses] - there is no cheaper documented
     * API for a service to ask "is my own process foreground right now" than walking that list
     * and matching its own pid, which is exactly what this does.
     *
     * A driver-launched start (`MidnightApplication.onCreate`, or any Settings-toggle start)
     * always reads `IMPORTANCE_FOREGROUND` here because the Activity that triggered it is on
     * screen. A `BootReceiver`-triggered start never does - there is no Activity, no visible UI,
     * nothing above background importance - so this returns false there without [BootReceiver]
     * or [com.kevin.legion.service.AssistantIgnition] needing to say so explicitly.
     *
     * ACQUIRE-ONLY, as of 2026-08-17: [startForegroundCompat] consults this ONLY when deciding
     * whether to newly claim `FOREGROUND_SERVICE_TYPE_MICROPHONE`, never to decide whether to
     * keep a mic type the service already holds (see that function's doc comment for why - an
     * off-screen conversation reads FOREGROUND_SERVICE importance, 125, not FOREGROUND, and a
     * naive re-check on every start would strip the mic off a live capture). Do not widen this
     * function's use to cover retention; add a new, differently-named check if a genuine
     * revocation signal is ever needed.
     */
    private fun isInForegroundEligibleState(): Boolean {
        val am = getSystemService(android.app.ActivityManager::class.java) ?: return false
        val myPid = android.os.Process.myPid()
        val myImportance = am.runningAppProcesses?.firstOrNull { it.pid == myPid }?.importance
            ?: return false
        return myImportance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

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
     *
     * ACQUIRE vs RETAIN (2026-08-17, fixing the regression the eligibility gate below
     * introduced the same day): [isInForegroundEligibleState] answers "is a FRESH claim on
     * the microphone type allowed right now" - it is a gate on ACQUIRING a type the service
     * does not currently hold, mirroring the real platform rule (a BOOT_COMPLETED-triggered
     * start can never claim `microphone`, since that type is on the documented
     * BOOT_COMPLETED-prohibited list at this target SDK). It must NEVER be re-consulted to
     * decide whether to KEEP a type the service already holds - `onStartCommand` calls this
     * function UNCONDITIONALLY on every start intent (wake-word hit, car-switch broadcast,
     * widget tap, retry-after-grant), and every one of those can arrive while MainActivity is
     * off-screen, at which point process importance reads FOREGROUND_SERVICE (125), not
     * FOREGROUND (100) - `isInForegroundEligibleState` legitimately returns false there even
     * though the mic is mid-capture. Re-deriving the type set from that check on every call
     * would silently downgrade a healthy, already-granted microphone FGS the instant the app
     * left the screen. So: once `FOREGROUND_SERVICE_TYPE_MICROPHONE` is in
     * [currentForegroundTypes], it stays there on every subsequent call regardless of the
     * eligibility check - the OS never asks a running service to re-justify a type it already
     * granted, and neither does this function.
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
            // The microphone type is RETAINED unconditionally if we already hold it - see the
            // ACQUIRE vs RETAIN note in this function's doc comment above. Only when we do NOT
            // already hold it does the eligibility gate apply, i.e. this is a one-way ratchet:
            // once granted, mic type survives every subsequent startForegroundCompat() call for
            // the life of the process, even if importance later drops below FOREGROUND. It can
            // still be lost the honest way - RECORD_AUDIO revoked mid-run (Settings > Permissions)
            // - which the `granted()` check below still catches on every call.
            val alreadyHasMic = currentForegroundTypes != -1 &&
                (currentForegroundTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
            val micOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                granted(Manifest.permission.RECORD_AUDIO) &&
                (alreadyHasMic || isInForegroundEligibleState())
            if (micOk) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, types)
            currentForegroundTypes = types
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

        // Ticket 10 (.scratch/wake-word/issues/10-acknowledge-the-wake.md): the SAME action
        // carries every door into a turn - the strip, the Android Auto play button, and the wake
        // word - but a voice-opened turn is the one with no screen to look at, so silence there
        // is indistinguishable from not having heard. This extra tells the two apart. Absent
        // means a tap, which keeps every existing sender correct without touching it.
        const val EXTRA_FROM_WAKE_WORD = "com.kevin.legion.FROM_WAKE_WORD"

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
        /**
         * Whether this service is actually alive right now - **not** whether
         * [com.kevin.legion.service.AssistantIgnition.isEnabled] says it should be.
         *
         * Those two came apart on the A25 on 2026-08-21 and nothing noticed: the process restarted
         * in the background, `startForegroundService` was refused with
         * `ForegroundServiceStartNotAllowedException`, and every surface kept reading the persisted
         * flag and saying "On" while nothing was running. A call came in and there was no
         * announcement, because there was no service to announce it.
         *
         * Process-scoped by construction, which is correct here: if the process died, the service
         * died with it, and a fresh process starts this at false.
         */
        @Volatile var isRunning: Boolean = false
            private set

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

        // Monthly recap cassette (E5): only fires within generateIfDue's grace
        // window and once per month, so an hourly check costs nothing.
        private const val RECAP_CHECK_INTERVAL_MS = 60 * 60 * 1000L

        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    }
}
