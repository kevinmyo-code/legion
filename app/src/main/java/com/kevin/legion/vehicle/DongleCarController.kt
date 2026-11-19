package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.vehicle.DongleCar.AnswerPlan
import com.kevin.legion.vehicle.DongleCar.Basis
import com.kevin.legion.vehicle.DongleCar.Held
import com.kevin.legion.vehicle.DongleCar.HeldReason
import com.kevin.legion.vehicle.DongleCar.OnCar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The one controller for "which car is the OBD dongle in" (OBD VIN attribution, Kevin 2026-11-15).
 * Voice and hands both call [answer] / [answerNewCar]: the `manage_vehicle` switch action (through
 * [VehicleController.switchToOutcome]), the notification's buttons ([DongleCarActionReceiver]) and
 * the Cars screen's question card.
 *
 * On every connect [onConnected] reads the VIN once, decides with [DongleCar.decide], and says so:
 *  - **matched**: the car is known; recording goes there, even over a hand pick (said once).
 *  - **unknown VIN, or no VIN and no single learned print**: the data is HELD under a pending id, a
 *    notification asks which car (cars on file plus "a new car"), and a spoken ask goes through
 *    [ProactiveBus] (Fleet switch, quiet hours, never mid-conversation).
 *  - **no VIN, this connect's print learned for exactly one car** ([CarFingerprint]): that car is
 *    ASSUMED, a quiet notification says which car in words with one-tap alternatives, and the
 *    window's samples are held back from upload until he confirms, corrects, or the next connect
 *    closes the window.
 * The binding is the CONNECTION's, never the adapter's: one adapter moves between cars (Kevin,
 * 2026-11-17). The pre-1b per-adapter `last_car:<mac>` values are left in prefs and never read.
 *
 * A connect read often comes too early (key on, engine off: the ECU may not answer Mode 09 yet),
 * so a connection that got no VIN tries once more when the engine starts ([retryVinIfMissing]),
 * and a reconnect inside the same drive that can read it carries the drive's data to the car the
 * VIN names. Either way the VIN, a fact, replaces the assumption.
 *
 * Per-device state, never synced (the same reasoning as [ActiveVehicle]): SharedPreferences for
 * what must survive a restart (the session, the held window; prints in [CarFingerprint]), memory for the rest.
 */
@Suppress("TooManyFunctions", "LargeClass") // one controller: connect, ask, answer, hold
object DongleCarController {
    private const val TAG = "DongleCar"
    private const val PREFS = "dongle_car"
    private const val KEY_HELD_CAR = "held_car"
    private const val KEY_HELD_FROM = "held_from"
    private const val KEY_HELD_MAC = "held_mac"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_FROM = "session_from"
    private const val KEY_DISCONNECTED_AT = "disconnected_at"
    private const val KEY_ASKED = "asked"
    private const val KEY_OVERRIDE_SAID = "override_said"
    private const val KEY_SESSION_ANSWERED = "session_answered"
    private const val KEY_SESSION_FP = "session_fp"

    /** Whole VIN read, retry included: two 5 s command timeouts plus slack. */
    internal const val VIN_READ_BUDGET_MS = 12_000L

    /** A reconnect inside this gap continues the same session (a Bluetooth blip, not a new drive). */
    internal const val SESSION_CONTINUES_MS = 30L * 60 * 1000

    /**
     * A second identical answer counts as the explicit "yes, replace the VIN" only inside this
     * window and only after [REPLACE_MIN_GAP_MS], so a model retrying a failed call at once does not
     * count as his yes.
     */
    private const val REPLACE_CONFIRM_MS = 5L * 60 * 1000
    internal const val REPLACE_MIN_GAP_MS = 10_000L

    const val RULE_ID = "obd_which_car"
    private const val LISTEN_HOLD_MS = 15_000L

    private val _state = MutableStateFlow<DongleCar?>(null)

    /** This connection's decision, or null when no dongle is connected (or it is still deciding). */
    val state: StateFlow<DongleCar?> = _state.asStateFlow()

    /** The last [answer] that needs an explicit "replace", so the Cars screen can offer it. */
    private val _conflict = MutableStateFlow<Conflict?>(null)
    val conflict: StateFlow<Conflict?> = _conflict.asStateFlow()

    data class Conflict(val subjectId: String, val carId: String, val message: String)

    /** The result of an answer, in words; [ok] is false when nothing changed. */
    data class Outcome(val ok: Boolean, val message: String)

    /** Data to carry to a new recording id: every row of [fromId], or its rows since [fromMs]. */
    private data class Carry(val fromId: String, val fromMs: Long?)

    private val mutex = Mutex()

    @Volatile private var sessionFrom = 0L
    @Volatile private var vinRetried = false

    // What this session wrote under each id, for re-attribution: trip miles added to a car's
    // odometer, and lifetime gallons/miles. Memory only and cleared when a new session starts, so a
    // correction moves this drive's miles and never an earlier drive's. A process restart mid-drive
    // loses the tally (said in the report), never the readings themselves.
    private val tripMiles = ConcurrentHashMap<String, Double>()
    private val lifetime = ConcurrentHashMap<String, Pair<Double, Double>>()

    private var lastReplaceAsk: Triple<String, String, Long>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * This connection's car fingerprint ([CarFingerprint]), null when the car gave none. Nothing is
     * remembered about the adapter itself: one adapter moves between cars (Kevin, 2026-11-17).
     */
    @Volatile private var connectionPrint: String? = null

    /** Counts connects, so a question asked on one connection is never answered on the next. */
    @Volatile private var connectionSeq = 0

    /**
     * This connection and the car it is recording under, once decided; null while nothing is connected
     * or the VIN read is still running. A clear-codes yes and the adapter ask are keyed by it.
     */
    fun connectionKey(): String? = _state.value?.let { "c$connectionSeq|${it.recordingId}" }

    /** The car on file whose learned fingerprint is exactly [fp], or null (none, several, or no print). */
    fun recognised(context: Context, fp: String?): String? {
        val prints = CarFingerprint.all(context)
        return CarFingerprint.match(prints, fp, prints.keys)
    }

    /**
     * [carId] is the car of the connection that gave [print] (a VIN proved it or he said so): the
     * print is learned for it, so the next connect recognises it. The old per-adapter "last car"
     * (`last_car:<mac>`) is no longer written or read.
     */
    private fun rememberCar(context: Context, carId: String, print: String?) {
        CarFingerprint.save(context, carId, print)
        ActiveVehicle.rememberConnected(context, carId)
    }

    /**
     * The print of the connection [subject] belongs to: the live one's, or the last session's when
     * [subject] is that session (answered after a disconnect, nothing newer since); otherwise none.
     * An answer about older held readings never teaches the print of another connection.
     */
    private fun printOf(context: Context, subject: DongleCar, isLive: Boolean): String? = when {
        isLive -> connectionPrint
        subject.recordingId == prefs(context).getString(KEY_SESSION_ID, null) ->
            prefs(context).getString(KEY_SESSION_FP, null)
        else -> null
    }

    // ---------------------------------------------------------------------------- connect

    /**
     * Called once per connect by [ObdBluetoothManager], after the ELM init and before anything is
     * recorded. [readVin] is bounded here as well as per command; any failure is "no VIN", so a
     * bad read can never keep the connection from coming up.
     */
    suspend fun onConnected(
        context: Context,
        mac: String,
        /** What the car answered at the handshake ([CarFingerprint.of]); null: none. */
        fingerprint: String? = null,
        readVin: suspend () -> VinResponse.Result,
    ): DongleCar {
        val vinResult = boundedRead(readVin)
        vinRetried = false
        val decision = mutex.withLock {
            // Set with the decision, under the lock: an answer for an earlier connection never sees it.
            connectionSeq++
            connectionPrint = fingerprint
            decideAndApply(context, mac, vinResult)
        }
        // Outside the lock: a spoken ask can be answered at once, and the answer takes the lock.
        say(context, decision)
        return decision
    }

    @Suppress("TooGenericExceptionCaught") // a failed read is "no VIN", never a failed connect
    private suspend fun boundedRead(readVin: suspend () -> VinResponse.Result): VinResponse.Result = try {
        withTimeoutOrNull(VIN_READ_BUDGET_MS) { readVin() } ?: VinResponse.Result.NoResponse
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "VIN read failed: ${e.message}")
        VinResponse.Result.NoResponse
    }

    @Suppress("CyclomaticComplexMethod") // blip, same car, answered, carried: each a rule of its own
    private suspend fun decideAndApply(context: Context, mac: String, vinResult: VinResponse.Result): DongleCar {
        val cars = carVins(context)
        val decision = decideFor(context, mac, vinResult, cars)
        val now = System.currentTimeMillis()
        val p = prefs(context)
        val previousId = p.getString(KEY_SESSION_ID, null)
        val blip = recentlyDisconnected(p.getLong(KEY_DISCONNECTED_AT, 0L), now)
        // A reconnect is the same drive only when both connects gave a print and it is the same one:
        // two print-less cars must never pool under one held id (2026-11-17 review).
        val previousPrint = p.getString(KEY_SESSION_FP, null)
        val samePrint = previousPrint != null && previousPrint == connectionPrint
        val continues = blip && samePrint && previousId != null &&
            DongleCar.sessionKey(previousId) == DongleCar.sessionKey(decision.recordingId)
        // A print match stays a recognition, never his answer, even after a blip: a twin car with the
        // same print must not inherit the right to clear codes (2026-11-17 review).
        // A blip inside one drive whose first connect read no VIN and whose reconnect reads one:
        // the drive's data so far goes where the VIN says.
        val readVin = vinResult is VinResponse.Result.Vin
        val carry = if (blip && !continues && readVin) previousId?.let { carryFrom(context, it) } else null
        sessionFrom = when {
            continues || carry != null -> p.getLong(KEY_SESSION_FROM, now)
            else -> now
        }
        return commit(context, decision, newSession = !continues && carry == null, carry = carry, vinResult = vinResult)
    }

    /** What the previous session left to carry: a no-VIN hold (all of it) or an assumed window. */
    private fun carryFrom(context: Context, previousId: String): Carry? = when {
        DongleCar.macOfPending(previousId) != null -> Carry(previousId, null)
        heldWindow(context)?.first == previousId -> Carry(previousId, heldWindow(context)?.second)
        else -> null
    }

    /**
     * Puts [decision0] in place: prefs, the held window, the state (FIRST, so the recorder writes
     * under the new id from here on), then any [carry].
     */
    private suspend fun commit(
        context: Context,
        decision0: DongleCar,
        newSession: Boolean,
        carry: Carry?,
        vinResult: VinResponse.Result,
    ): DongleCar {
        var decision = decision0
        if (decision is Held && decision.vin == null) decision = decision.copy(since = sessionFrom)
        val p = prefs(context)
        val edit = p.edit().putString(KEY_SESSION_ID, decision.recordingId).putLong(KEY_SESSION_FROM, sessionFrom)
            .putString(KEY_SESSION_FP, connectionPrint)
        if (newSession) {
            // A new session closes the previous assumed window: its samples may upload now.
            edit.remove(KEY_HELD_CAR).remove(KEY_HELD_FROM).remove(KEY_HELD_MAC).putBoolean(KEY_SESSION_ANSWERED, false)
            tripMiles.clear()
            lifetime.clear()
        }
        if (decision is OnCar && decision.basis == Basis.VIN) {
            // A VIN proves the car: nothing is assumed any more.
            edit.remove(KEY_HELD_CAR).remove(KEY_HELD_FROM).remove(KEY_HELD_MAC)
        }
        val opensWindow = newSession || p.getString(KEY_HELD_CAR, null) == null
        if (decision is OnCar && decision.basis == Basis.ASSUMED && opensWindow) {
            edit.putString(KEY_HELD_CAR, decision.carId).putLong(KEY_HELD_FROM, sessionFrom)
                .putString(KEY_HELD_MAC, decision.mac)
        }
        edit.apply()
        if (decision is OnCar && decision.basis == Basis.VIN) {
            learnFromVin(context, decision.carId)
        } else if (decision is OnCar) {
            ActiveVehicle.rememberConnected(context, decision.carId)
        }
        _state.value = decision
        _conflict.value = null
        carry?.let { moveData(context, it.fromId, decision.recordingId, it.fromMs, System.currentTimeMillis()) }
        MidnightEvents.obdAttributed(decision.mac, decision.recordingId, describe(vinResult, decision, carry))
        return decision
    }

    /**
     * A VIN proved this connection is [carId]: the VIN is a fact and wins over a print learned for
     * another car. That car keeps the print too, so the print alone is ambiguous from now on (asked,
     * never guessed). Logged, not said.
     */
    private fun learnFromVin(context: Context, carId: String) {
        val printCar = recognised(context, connectionPrint)
        if (printCar != null && printCar != carId) {
            Log.i(TAG, "VIN names $carId; this print was learned for $printCar; learned for both")
        }
        rememberCar(context, carId, connectionPrint)
    }

    internal fun recentlyDisconnected(disconnectedAt: Long, now: Long): Boolean =
        disconnectedAt > 0L && now - disconnectedAt in 0..SESSION_CONTINUES_MS

    /** True when a reconnect is the same session: the same held session or car, and a short gap. */
    internal fun sessionContinues(previousId: String?, newId: String, disconnectedAt: Long, now: Long): Boolean =
        previousId != null && DongleCar.sessionKey(previousId) == DongleCar.sessionKey(newId) &&
            recentlyDisconnected(disconnectedAt, now)

    /**
     * Called by [TelemetryRecorder] when the engine starts. A connection that read no VIN at connect
     * (key on, engine off, the ECU not yet answering Mode 09) reads it once more; a VIN replaces the
     * assumption and carries this session's data to the car it names. Once per connection.
     */
    @Suppress("ReturnCount") // guard clauses: nothing connected, already tried, a VIN already read
    suspend fun retryVinIfMissing(context: Context, readVin: suspend () -> VinResponse.Result) {
        val before = _state.value ?: return
        if (vinRetried) return
        val noVin = (before is OnCar && before.basis == Basis.ASSUMED) || (before is Held && before.vin == null)
        if (!noVin) return
        vinRetried = true
        val vinResult = boundedRead(readVin)
        if (vinResult !is VinResponse.Result.Vin) return
        val decision = mutex.withLock {
            val current = _state.value
            if (current == null || current.recordingId != before.recordingId) return@withLock null
            val fresh = decideFor(context, current.mac, vinResult, carVins(context))
            val carry = if (fresh.recordingId != current.recordingId) {
                Carry(current.recordingId, (current as? OnCar)?.let { heldFrom(context, it.carId) ?: sessionFrom })
            } else {
                null
            }
            commit(context, fresh, newSession = false, carry = carry, vinResult = vinResult)
        } ?: return
        ActiveVehicle.notifyResolutionChanged(context)
        say(context, decision)
    }

    /** Called by [ObdBluetoothManager] when the link drops. Keeps every held window. */
    fun onDisconnected(context: Context, at: Long = System.currentTimeMillis()) {
        connectionPrint = null
        if (_state.value == null) return
        _state.value = null
        prefs(context).edit().putLong(KEY_DISCONNECTED_AT, at).apply()
    }

    private fun describe(vinResult: VinResponse.Result, decision: DongleCar, carry: Carry?): String {
        val read = when (vinResult) {
            is VinResponse.Result.Vin -> DongleCar.vinTail(vinResult.vin)
            VinResponse.Result.NoData -> "VIN: no data"
            VinResponse.Result.NoResponse -> "VIN: no response"
            VinResponse.Result.Unsupported -> "VIN: adapter said ?"
            is VinResponse.Result.Unreadable -> "VIN unreadable (${vinResult.why})"
        }
        val how = when (decision) {
            is OnCar -> decision.basis.name.lowercase()
            is Held -> "held, ${decision.reason.name.lowercase()}"
        }
        return "$read; $how" + (carry?.let { "; carried from ${it.fromId}" } ?: "")
    }

    // ---------------------------------------------------------------------------- the ask

    @Suppress("TooGenericExceptionCaught") // telling him must never break the connect
    private suspend fun say(context: Context, decision: DongleCar) {
        try {
            sayOrThrow(context, decision)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not say which car: ${e.message}")
        }
    }

    private suspend fun sayOrThrow(context: Context, decision: DongleCar) {
        val labels = labels(context)
        when (decision) {
            is Held -> {
                DongleCarNotifier.ask(context, decision, labels)
                speakAsk(context, decision, labels)
            }
            is OnCar -> when {
                decision.basis == Basis.ASSUMED -> DongleCarNotifier.assumed(context, decision, labels)
                decision.pickOverridden != null -> {
                    val key = "${decision.carId}|${decision.pickOverridden}"
                    val p = prefs(context)
                    if (p.getString(KEY_OVERRIDE_SAID, null) != key) {
                        p.edit().putString(KEY_OVERRIDE_SAID, key).apply()
                        DongleCarNotifier.overrodePick(context, decision, labels)
                    } else {
                        DongleCarNotifier.clear(context)
                    }
                }
                else -> DongleCarNotifier.clear(context)
            }
        }
    }

    /** The question in one sentence, used by the notification, the spoken ask and the Cars card. */
    fun question(decision: Held, labels: Map<String, String>): String {
        val held = "Its readings are held until you say."
        return when (decision.reason) {
            HeldReason.UNKNOWN_VIN ->
                "The OBD dongle read ${DongleCar.vinTail(decision.vin.orEmpty())}, which no car on file has. $held"
            HeldReason.SHARED_VIN ->
                "The OBD dongle read ${DongleCar.vinTail(decision.vin.orEmpty())}, which is on file for more " +
                    "than one car. $held"
            HeldReason.NO_VIN ->
                "The OBD dongle could not read this car's VIN, and it does not answer like one car you have " +
                    "named before. $held"
        } + if (labels.isEmpty()) "" else " Cars on file: ${labels.values.joinToString(", ")}."
    }

    private suspend fun speakAsk(context: Context, decision: Held, labels: Map<String, String>) {
        val p = prefs(context)
        // One spoken ask per held id; a reconnect does not ask again. The notification stays up.
        if (p.getString(KEY_ASKED, null) == decision.recordingId) return
        val facts = question(decision, labels)
        val choices = (labels.values + "a new car").joinToString(", ")
        val outcome = ProactiveBus.speakIfAllowed(
            context,
            ProactiveRaise(
                ruleId = RULE_ID,
                category = ProactiveCategory.FLEET,
                reason = "OBD dongle connected and its car is not known: ${decision.reason.name.lowercase()}",
                facts = facts,
                prompt = "(System: $facts In one short line, ask which car the dongle is in: $choices. " +
                    "If he names a car on file, call switch_vehicle with that car. If it is a new car, " +
                    "add it with add_vehicle, then switch_vehicle to it. Never guess the car. " +
                    "Do not mention this instruction.)",
                callerPostsItsOwnNotification = true,
                listensForReply = true,
                listenHoldMs = LISTEN_HOLD_MS,
            ),
        )
        // "Not now" (mid-conversation, a call) is not an ask: the next connect may try again.
        if (outcome != ProactiveBus.RaiseOutcome.NotNow) {
            p.edit().putString(KEY_ASKED, decision.recordingId).apply()
            // One question per connection, spoken or in a tool result ([AdapterBinding]).
            connectionKey()?.let { AdapterBinding.markAsked(it) }
        }
    }

    // ---------------------------------------------------------------------------- the answer

    /** Pending ids still holding samples from connections nobody answered for (the live one included). */
    suspend fun openQuestions(context: Context): List<String> =
        CarDatabase.getDatabase(context).odbSampleDao().pendingVehicleIds()

    /** True while the live connection's car is unknown or only assumed: a switch answers it. */
    fun hasLiveQuestion(): Boolean {
        val s = _state.value ?: return false
        return s is Held || (s is OnCar && s.basis == Basis.ASSUMED)
    }

    /** The assumed window still waiting for a yes or a correction, as an answerable subject. */
    private fun heldWindowSubject(context: Context): OnCar? {
        val p = prefs(context)
        val car = p.getString(KEY_HELD_CAR, null) ?: return null
        return OnCar(p.getString(KEY_HELD_MAC, null).orEmpty(), car, null, Basis.ASSUMED)
    }

    /**
     * Kevin says the dongle's data belongs to [carId]. [subjectId] null means the live connection, or
     * after it dropped, the assumed window still waiting; otherwise it is a pending id or the assumed
     * car's id (both work after the dongle disconnects, which is when a tap usually comes).
     * [replaceVin] is his explicit yes to replacing a different VIN on file.
     */
    suspend fun answer(
        context: Context,
        carId: String,
        subjectId: String? = null,
        replaceVin: Boolean = false,
    ): Outcome = mutex.withLock { answerLocked(context, carId, subjectId, replaceVin) }

    private fun resolveSubject(context: Context, subjectId: String?): DongleCar? {
        val live = _state.value
        val window = heldWindowSubject(context)
        return when {
            subjectId == null -> live ?: window
            subjectId == live?.recordingId -> live
            DongleCar.isPending(subjectId) -> Held(
                mac = DongleCar.macOfPending(subjectId).orEmpty(),
                vin = DongleCar.vinOfPending(subjectId),
                reason = if (DongleCar.vinOfPending(subjectId) != null) HeldReason.UNKNOWN_VIN else HeldReason.NO_VIN,
                since = DongleCar.sinceOfPending(subjectId),
            )
            subjectId == window?.carId -> window
            else -> null
        }
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // one branch per plan
    private suspend fun answerLocked(
        context: Context,
        carId: String,
        subjectId: String?,
        replaceVinArg: Boolean,
    ): Outcome {
        val subject = resolveSubject(context, subjectId)
            ?: return Outcome(false, NOTHING_WAITING)
        val live = _state.value
        val isLive = live != null && subject.recordingId == live.recordingId
        // A pending id with nothing left under it was already answered (a second tap, a stale
        // card). Answering it again must not write or clear a VIN on the strength of zero rows.
        if (!isLive && subject is Held && subject.recordingId !in openQuestions(context)) {
            return Outcome(false, "Those readings were already filed under a car, so nothing changed.")
        }
        val window = heldWindow(context)
        val settlesWindow = subject is OnCar && window?.first == subject.carId
        val cars = carVins(context)
        val labels = labels(context)
        val name = labels[carId] ?: "that car"
        val now = System.currentTimeMillis()
        val replaceVin = replaceVinArg || lastReplaceAsk?.let { (s, c, at) ->
            s == subject.recordingId && c == carId && now - at in REPLACE_MIN_GAP_MS..REPLACE_CONFIRM_MS
        } == true

        return when (val plan = DongleCar.planAnswer(subject, carId, cars, replaceVin)) {
            is AnswerPlan.NotOnFile -> Outcome(false, "That car isn't on file (or is archived), so nothing changed.")
            is AnswerPlan.VinSaysOtherCar -> {
                if (lastReplaceAsk?.let { it.first == subject.recordingId && it.second == carId } != true) {
                    lastReplaceAsk = Triple(subject.recordingId, carId, now)
                }
                val other = labels[plan.vinCarId] ?: "another car"
                val message = "The dongle read the $other's VIN, so live readings stay on the $other. Nothing " +
                    "changed. If that VIN is on the wrong car, tap Replace (Cars, OBD dongle), or say it is the " +
                    "$name again in a moment, and I'll move the VIN and this drive to the $name."
                _conflict.value = Conflict(subject.recordingId, carId, message)
                Outcome(false, message)
            }
            is AnswerPlan.VinConflict -> {
                if (lastReplaceAsk?.let { it.first == subject.recordingId && it.second == carId } != true) {
                    lastReplaceAsk = Triple(subject.recordingId, carId, now)
                }
                val message = "The $name already has ${DongleCar.vinTail(plan.onFile)} on file, and this car " +
                    "reads ${DongleCar.vinTail(plan.read)}. Nothing changed. If the $name's VIN on file is " +
                    "wrong, tap Replace (Cars, OBD dongle), or say it is the $name again in a moment, and " +
                    "I'll replace it."
                _conflict.value = Conflict(subject.recordingId, carId, message)
                Outcome(false, message)
            }
            is AnswerPlan.Keep -> {
                if (isLive && plan.settles) {
                    _state.value = OnCar(subject.mac, carId, (subject as? OnCar)?.vin, Basis.ANSWER)
                }
                // Learned only: an answer about older held readings does not make that car the connected one.
                CarFingerprint.save(context, carId, printOf(context, subject, isLive))
                if (isLive || settlesWindow) {
                    ActiveVehicle.rememberConnected(context, carId)
                    prefs(context).edit().remove(KEY_HELD_CAR).remove(KEY_HELD_FROM).remove(KEY_HELD_MAC)
                        .putBoolean(KEY_SESSION_ANSWERED, true).apply()
                }
                if (isLive) ObdBluetoothManager.persistCapabilitiesFor(context, carId)
                DongleCarNotifier.clear(context)
                Outcome(true, "Noted: the dongle is in the $name.")
            }
            is AnswerPlan.Move -> {
                if (isLive) {
                    // The state first: from this instant the recorder writes under the named car, so
                    // nothing lands under the old id after the move below.
                    val vin = (subject as? Held)?.vin ?: (subject as? OnCar)?.vin
                    _state.value = OnCar(subject.mac, carId, vin, Basis.ANSWER)
                    prefs(context).edit().putString(KEY_SESSION_ID, carId)
                        .putBoolean(KEY_SESSION_ANSWERED, true).apply()
                }
                plan.writeVin?.let { FleetEngineStore.setVin(context, carId, it) }
                plan.clearVinFrom.forEach { FleetEngineStore.setVin(context, it, "") }
                val windowStart = if (settlesWindow) window?.second else null
                val fromMs = if (plan.asCorrection) windowStart ?: sessionFrom else null
                val moved = moveData(context, plan.fromId, carId, fromMs, System.currentTimeMillis())
                // Only the print of the connection these readings came from: an answer about an earlier,
                // held connection must not teach the print of whatever car the adapter is in now.
                rememberCar(context, carId, printOf(context, subject, isLive))
                if (isLive || settlesWindow) {
                    prefs(context).edit().remove(KEY_HELD_CAR).remove(KEY_HELD_FROM).remove(KEY_HELD_MAC).apply()
                }
                if (isLive) {
                    ObdBluetoothManager.persistCapabilitiesFor(context, carId)
                    ActiveVehicle.notifyResolutionChanged(context)
                }
                prefs(context).edit().remove(KEY_ASKED).apply()
                _conflict.value = null
                lastReplaceAsk = null
                DongleCarNotifier.clear(context)
                Outcome(true, movedWords(name, moved, plan))
            }
        }
    }

    /**
     * Moves [fromId]'s data to [toId]: every row ([fromMs] null, a pending id) or the rows since
     * [fromMs] (a car's window). A correction rule is recorded only between two cars; a pending id
     * never left the phone.
     */
    private suspend fun moveData(
        context: Context,
        fromId: String,
        toId: String,
        fromMs: Long?,
        toMs: Long,
    ): FleetEngineStore.Reattributed {
        val windowed = fromMs != null
        val moved = FleetEngineStore.reattributeObdData(
            context,
            FleetEngineStore.ObdDataMove(
                fromId, toId, fromMs ?: 0L, toMs,
                windowed = windowed,
                rule = windowed && !DongleCar.isPending(fromId) && !DongleCar.isPending(toId),
            ),
        )
        moveTallies(context, fromId, toId)
        TelemetryRecorder.reattributeDriveInProgress(fromId, toId)
        return moved
    }

    private fun movedWords(name: String, moved: FleetEngineStore.Reattributed, plan: AnswerPlan.Move): String {
        val parts = buildList {
            add(count(moved.samples, "reading"))
            if (moved.drives > 0) add(count(moved.drives, "drive"))
            if (moved.codeEvents > 0) add(count(moved.codeEvents, "trouble-code record"))
        }
        val vin = plan.writeVin?.let { " Saved its ${DongleCar.vinTail(it)} so I'll know it next time." }.orEmpty()
        val cleared = if (plan.clearVinFrom.isNotEmpty()) {
            " Cleared that VIN from ${count(plan.clearVinFrom.size, "other car")} that had it."
        } else {
            ""
        }
        return "Got it: the dongle is in the $name. Moved ${parts.joinToString(", ")} to it.$vin$cleared"
    }

    /**
     * "A new car": looks the VIN up and adds the car it decodes to, then answers with it. Without a
     * VIN, or when the lookup fails, says what to do instead and changes nothing. A decode that
     * names a car already on file is not added twice: it asks whether it is that one.
     */
    @Suppress("ReturnCount") // guard clauses: no VIN, no lookup, already on file
    suspend fun answerNewCar(context: Context, subjectId: String? = null): Outcome {
        val held = mutex.withLock { resolveSubject(context, subjectId) } as? Held
        val vin = held?.vin
        if (held == null || vin == null) {
            return Outcome(
                false,
                "I can't look a car up without its VIN. Add it with its year, make and model (Cars, Add car, " +
                    "or say add my car), then tell me it's that one.",
            )
        }
        val decoded = VinDecoder.decode(vin)
        if (decoded == null || !usable(decoded)) {
            return Outcome(
                false,
                "I couldn't look up ${DongleCar.vinTail(vin)}. Add the car with its year, make and model, " +
                    "then tell me it's that one.",
            )
        }
        val existing = FleetEngineStore.getAll(context).firstOrNull {
            it.make.equals(decoded.make, true) && it.model.equals(decoded.model, true) && it.year == decoded.year
        }
        if (existing != null) {
            return Outcome(
                false,
                "That VIN is a ${decoded.year} ${decoded.make} ${decoded.model}, and you already have " +
                    "${VehicleController.label(existing)} on file. If it's that one, pick it.",
            )
        }
        val id = ActiveVehicle.newVehicleId()
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = id,
                name = decoded.model,
                make = decoded.make,
                model = decoded.model,
                year = decoded.year,
                trim = decoded.trim,
                personaPrompt = "",
                // A decode fills facts the driver did not state: never marked driver-confirmed
                // (VehicleController.applyDecodedIdentity's policy).
                confirmed = false,
            ),
        )
        return answer(context, id, held.recordingId)
    }

    // ---------------------------------------------------------------------------- uploads

    /** The assumed window whose samples wait for an answer before upload: (car, from), or null. */
    fun heldWindow(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val car = p.getString(KEY_HELD_CAR, null) ?: return null
        return car to p.getLong(KEY_HELD_FROM, 0L)
    }

    private fun heldFrom(context: Context, carId: String): Long? =
        heldWindow(context)?.takeIf { it.first == carId }?.second

    /**
     * True when a sample may not upload yet: it is under a pending id, or inside the assumed
     * window. `ObdSampleReconcile` halts there, so the cursor never passes it before the answer.
     */
    fun uploadHeld(vehicleId: String, timestamp: Long, window: Pair<String, Long>?): Boolean =
        DongleCar.isPending(vehicleId) || (window != null && vehicleId == window.first && timestamp >= window.second)

    // ---------------------------------------------------------------------------- tallies

    /** [TelemetryRecorder] reports odometer miles it added under [id]. */
    fun noteTripMiles(id: String, miles: Double) {
        tripMiles.merge(id, miles) { a, b -> a + b }
    }

    /** [TelemetryRecorder] reports a finished drive's lifetime gallons and miles under [id]. */
    fun noteLifetime(id: String, gallons: Double, miles: Double) {
        lifetime.merge(id, gallons to miles) { a, b -> (a.first + b.first) to (a.second + b.second) }
    }

    private suspend fun moveTallies(context: Context, fromId: String, toId: String) {
        val now = System.currentTimeMillis()
        val fromIsCar = !DongleCar.isPending(fromId)
        tripMiles.remove(fromId)?.let { miles ->
            // A pending id has no vehicles row, so its odometer add was a no-op; only the target gains.
            if (fromIsCar) FleetEngineStore.addTripMiles(context, fromId, -miles, now)
            FleetEngineStore.addTripMiles(context, toId, miles, now)
            noteTripMiles(toId, miles)
        }
        val tally = lifetime.remove(fromId)
        TelemetryRecorder.moveLifetime(context, fromId, toId, tally, moveAll = !fromIsCar)
        tally?.let { noteLifetime(toId, it.first, it.second) }
    }

    // ---------------------------------------------------------------------------- helpers

    private suspend fun carVins(context: Context): List<DongleCar.CarVin> {
        val specs = CarDatabase.getDatabase(context).vehicleSpecDao().getAll().associate { it.vehicleId to it.vin }
        return FleetEngineStore.getAll(context).map { DongleCar.CarVin(it.obdMac, specs[it.obdMac].orEmpty()) }
    }

    /** Car id to its label, for the cars on file that are not archived. */
    suspend fun labels(context: Context): Map<String, String> =
        FleetEngineStore.getAll(context).associate { it.obdMac to VehicleController.label(it) }

    private const val MIN_YEAR = 1900
    private const val NOTHING_WAITING =
        "Nothing is waiting for a car: the dongle isn't connected and no readings are held."

    private suspend fun decideFor(
        context: Context,
        mac: String,
        vinResult: VinResponse.Result,
        cars: List<DongleCar.CarVin>,
    ): DongleCar {
        val prints = CarFingerprint.all(context)
        val byPrint = CarFingerprint.match(prints, connectionPrint, cars.map { it.carId }.toSet())
        return DongleCar.decide(mac, vinResult, cars, byPrint, ActiveVehicle.selected(context))
    }

    private fun usable(decoded: VinDecoder.DecodedVin): Boolean =
        decoded.year >= MIN_YEAR && decoded.make.isNotBlank() && decoded.model.isNotBlank()

    private fun count(n: Int, noun: String): String = "$n $noun${if (n == 1) "" else "s"}"

    /** Test seam: forget this process's state. */
    internal fun resetForTest() {
        connectionPrint = null
        connectionSeq = 0
        _state.value = null
        _conflict.value = null
        tripMiles.clear()
        lifetime.clear()
        lastReplaceAsk = null
        sessionFrom = 0L
        vinRetried = false
    }

    /** Test seam: age the last replace ask, as if he said it again a moment later. */
    internal fun ageReplaceAskForTest(byMs: Long) {
        lastReplaceAsk = lastReplaceAsk?.let { Triple(it.first, it.second, it.third - byMs) }
    }
}
