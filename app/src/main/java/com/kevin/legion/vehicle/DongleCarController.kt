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
 * [VehicleController.switchTo]), the notification's buttons ([DongleCarActionReceiver]) and the
 * Cars screen's question card.
 *
 * On every connect [onConnected] reads the VIN once, decides with [DongleCar.decide], and says so:
 *  - **matched**: the car is known; recording goes there, even over a hand pick (said once).
 *  - **unknown VIN, or no VIN and no earlier answer**: the data is HELD under a pending id, a
 *    notification asks which car (cars on file plus "a new car"), and a spoken ask goes through
 *    [ProactiveBus] (Fleet switch, quiet hours, never mid-conversation).
 *  - **no VIN, earlier answer**: the last car Kevin confirmed for this dongle is ASSUMED, a quiet
 *    notification says which car in words with one-tap alternatives, and the window's samples are
 *    held back from upload until he confirms, corrects, or the next connect closes the window.
 *
 * Per-device state, never synced (the same reasoning as [ActiveVehicle]): SharedPreferences for
 * what must survive a restart (the last car per dongle, the held window), memory for the rest.
 */
object DongleCarController {
    private const val TAG = "DongleCar"
    private const val PREFS = "dongle_car"
    private const val KEY_LAST_CAR = "last_car:"
    private const val KEY_HELD_CAR = "held_car"
    private const val KEY_HELD_FROM = "held_from"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_FROM = "session_from"
    private const val KEY_DISCONNECTED_AT = "disconnected_at"
    private const val KEY_ASKED = "asked"
    private const val KEY_OVERRIDE_SAID = "override_said"
    private const val KEY_SESSION_ANSWERED = "session_answered"

    /** Whole VIN read, retry included: two 5 s command timeouts plus slack. */
    internal const val VIN_READ_BUDGET_MS = 12_000L

    /** A reconnect inside this gap continues the same session (a Bluetooth blip, not a new drive). */
    internal const val SESSION_CONTINUES_MS = 30L * 60 * 1000

    /** A second identical answer inside this window is the explicit "yes, replace the VIN". */
    private const val REPLACE_CONFIRM_MS = 5L * 60 * 1000

    const val RULE_ID = "obd_which_car"
    private const val LISTEN_HOLD_MS = 15_000L

    private val _state = MutableStateFlow<DongleCar?>(null)

    /** This connection's decision, or null when no dongle is connected (or it is still deciding). */
    val state: StateFlow<DongleCar?> = _state.asStateFlow()

    /** The last [answer] that hit a VIN conflict, so the Cars screen can offer "replace". */
    private val _conflict = MutableStateFlow<Conflict?>(null)
    val conflict: StateFlow<Conflict?> = _conflict.asStateFlow()

    data class Conflict(val subjectId: String, val carId: String, val message: String)

    /** The result of an answer, in words; [ok] is false when nothing changed. */
    data class Outcome(val ok: Boolean, val message: String)

    private val mutex = Mutex()

    @Volatile private var sessionFrom = 0L

    // What this process wrote under each id, for re-attribution: trip miles added to a car's
    // odometer, and lifetime gallons/miles. Memory only; a process restart mid-drive loses the
    // tally (said in the report), never the readings themselves.
    private val tripMiles = ConcurrentHashMap<String, Double>()
    private val lifetime = ConcurrentHashMap<String, Pair<Double, Double>>()

    private var lastReplaceAsk: Triple<String, String, Long>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The car Kevin last confirmed (or a VIN proved) for the dongle at [mac], or null. */
    fun lastConfirmed(context: Context, mac: String): String? =
        prefs(context).getString(KEY_LAST_CAR + mac, null)?.takeIf { it.isNotBlank() }

    private fun rememberCar(context: Context, mac: String, carId: String) {
        prefs(context).edit().putString(KEY_LAST_CAR + mac, carId).apply()
        ActiveVehicle.rememberConnected(context, carId)
    }

    // ---------------------------------------------------------------------------- connect

    /**
     * Called once per connect by [ObdBluetoothManager], after the ELM init and before anything is
     * recorded. [readVin] is bounded here as well as per command; any failure is "no VIN", so a
     * bad read can never keep the connection from coming up.
     */
    @Suppress("TooGenericExceptionCaught") // a failed read is "no VIN", never a failed connect
    suspend fun onConnected(context: Context, mac: String, readVin: suspend () -> VinResponse.Result): DongleCar {
        val vinResult = try {
            withTimeoutOrNull(VIN_READ_BUDGET_MS) { readVin() } ?: VinResponse.Result.NoResponse
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "VIN read failed: ${e.message}")
            VinResponse.Result.NoResponse
        }
        val decision = mutex.withLock { decideAndApply(context, mac, vinResult) }
        // Outside the lock: a spoken ask can be answered at once, and the answer takes the lock.
        say(context, decision)
        return decision
    }

    private suspend fun decideAndApply(context: Context, mac: String, vinResult: VinResponse.Result): DongleCar {
        val cars = carVins(context)
        var decision = DongleCar.decide(mac, vinResult, cars, lastConfirmed(context, mac), ActiveVehicle.selected(context))
        val now = System.currentTimeMillis()
        val p = prefs(context)
        val continues = sessionContinues(
            previousId = p.getString(KEY_SESSION_ID, null),
            newId = decision.recordingId,
            disconnectedAt = p.getLong(KEY_DISCONNECTED_AT, 0L),
            now = now,
        )
        // A Bluetooth blip after Kevin answered is the same answer, not a fresh assumption.
        if (continues && p.getBoolean(KEY_SESSION_ANSWERED, false) && decision is OnCar && decision.basis == Basis.ASSUMED) {
            decision = decision.copy(basis = Basis.ANSWER)
        }
        sessionFrom = if (continues) p.getLong(KEY_SESSION_FROM, now) else now
        val edit = p.edit().putString(KEY_SESSION_ID, decision.recordingId).putLong(KEY_SESSION_FROM, sessionFrom)
        // A new session closes the previous assumed window: its samples may upload now.
        if (!continues) edit.remove(KEY_HELD_CAR).remove(KEY_HELD_FROM).putBoolean(KEY_SESSION_ANSWERED, false)
        if (decision is OnCar && decision.basis == Basis.ASSUMED && (!continues || p.getString(KEY_HELD_CAR, null) == null)) {
            edit.putString(KEY_HELD_CAR, decision.carId).putLong(KEY_HELD_FROM, sessionFrom)
        }
        edit.apply()
        if (decision is OnCar) {
            if (decision.basis == Basis.VIN) rememberCar(context, mac, decision.carId)
            else ActiveVehicle.rememberConnected(context, decision.carId)
        }
        _state.value = decision
        _conflict.value = null
        MidnightEvents.obdAttributed(mac, decision.recordingId, describe(vinResult, decision))
        return decision
    }

    /** True when a reconnect is the same session: same recording id, and a short gap. */
    internal fun sessionContinues(previousId: String?, newId: String, disconnectedAt: Long, now: Long): Boolean =
        previousId == newId && disconnectedAt > 0L && now - disconnectedAt in 0..SESSION_CONTINUES_MS

    /** Called by [ObdBluetoothManager] when the link drops. Keeps every held window. */
    fun onDisconnected(context: Context, at: Long = System.currentTimeMillis()) {
        if (_state.value == null) return
        _state.value = null
        prefs(context).edit().putLong(KEY_DISCONNECTED_AT, at).apply()
    }

    private fun describe(vinResult: VinResponse.Result, decision: DongleCar): String {
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
        return "$read; $how"
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
                "The OBD dongle read ${DongleCar.vinTail(decision.vin.orEmpty())}, which is on file for more than one car. $held"
            HeldReason.NO_VIN ->
                "The OBD dongle could not read this car's VIN, and you have not said which car it is in before. $held"
        } + if (labels.isEmpty()) "" else " Cars on file: ${labels.values.joinToString(", ")}."
    }

    private suspend fun speakAsk(context: Context, decision: Held, labels: Map<String, String>) {
        val p = prefs(context)
        // One spoken ask per held id; a reconnect does not ask again. The notification stays up.
        if (p.getString(KEY_ASKED, null) == decision.recordingId) return
        p.edit().putString(KEY_ASKED, decision.recordingId).apply()
        val facts = question(decision, labels)
        val choices = (labels.values + "a new car").joinToString(", ")
        ProactiveBus.speakIfAllowed(
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
    }

    // ---------------------------------------------------------------------------- the answer

    /**
     * Pending ids still holding samples from connections nobody answered for. The live connection's
     * own held id is included while it holds data.
     */
    suspend fun openQuestions(context: Context): List<String> =
        CarDatabase.getDatabase(context).odbSampleDao().pendingVehicleIds()

    /** True when a "this is the X" should answer the dongle question rather than just pick a car. */
    fun hasLiveQuestion(): Boolean {
        val s = _state.value ?: return false
        return s is Held || (s is OnCar && s.basis != Basis.VIN)
    }

    /**
     * Kevin says the dongle's data belongs to [carId]. [subjectId] null means the live connection;
     * otherwise it is a leftover pending id. [replaceVin] is his explicit yes to replacing a
     * different VIN already on file for that car.
     */
    suspend fun answer(context: Context, carId: String, subjectId: String? = null, replaceVin: Boolean = false): Outcome =
        mutex.withLock { answerLocked(context, carId, subjectId, replaceVin) }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // one branch per plan
    private suspend fun answerLocked(context: Context, carId: String, subjectId: String?, replaceVinArg: Boolean): Outcome {
        val live = _state.value
        val subject: DongleCar = when {
            subjectId == null || subjectId == live?.recordingId -> live
                ?: return Outcome(false, "The OBD dongle isn't connected, so there is no car to set for it.")
            DongleCar.isPending(subjectId) -> Held(
                mac = subjectId.removePrefix("pending:obd:"),
                vin = DongleCar.vinOfPending(subjectId),
                reason = if (DongleCar.vinOfPending(subjectId) != null) HeldReason.UNKNOWN_VIN else HeldReason.NO_VIN,
            )
            else -> return Outcome(false, "That is not a dongle connection waiting for a car.")
        }
        val isLive = live != null && subject.recordingId == live.recordingId
        val cars = carVins(context)
        val labels = labels(context)
        val name = labels[carId] ?: "that car"
        val now = System.currentTimeMillis()
        val replaceVin = replaceVinArg || lastReplaceAsk?.let { (s, c, at) ->
            s == subject.recordingId && c == carId && now - at < REPLACE_CONFIRM_MS
        } == true

        return when (val plan = DongleCar.planAnswer(subject, carId, cars, replaceVin)) {
            is AnswerPlan.NotOnFile -> Outcome(false, "That car isn't on file (or is archived), so nothing changed.")
            is AnswerPlan.VinSaysOtherCar -> {
                val other = labels[plan.vinCarId] ?: "another car"
                Outcome(
                    false,
                    "The dongle read the $other's VIN, so live readings stay on the $other. " +
                        "If that VIN is on the wrong car, fix it on that car's Specs.",
                )
            }
            is AnswerPlan.VinConflict -> {
                lastReplaceAsk = Triple(subject.recordingId, carId, now)
                val message = "The $name already has ${DongleCar.vinTail(plan.onFile)} on file, and this car " +
                    "reads ${DongleCar.vinTail(plan.read)}. Nothing changed. If the $name's VIN on file is " +
                    "wrong, say it is the $name again (or tap Replace) and I'll replace it."
                _conflict.value = Conflict(subject.recordingId, carId, message)
                Outcome(false, message)
            }
            is AnswerPlan.Keep -> {
                if (isLive) {
                    rememberCar(context, subject.mac, carId)
                    prefs(context).edit().remove(KEY_HELD_CAR).remove(KEY_HELD_FROM)
                        .putBoolean(KEY_SESSION_ANSWERED, true).apply()
                    val vin = (subject as? OnCar)?.vin
                    if (plan.settles) _state.value = OnCar(subject.mac, carId, vin, Basis.ANSWER)
                    ObdBluetoothManager.persistCapabilitiesFor(context, carId)
                }
                DongleCarNotifier.clear(context)
                Outcome(true, "Noted: the dongle is in the $name.")
            }
            is AnswerPlan.Move -> {
                plan.writeVin?.let { FleetEngineStore.setVin(context, carId, it) }
                plan.clearVinFrom.forEach { FleetEngineStore.setVin(context, it, "") }
                val fromMs = if (plan.asCorrection) heldFrom(context, plan.fromId) ?: sessionFrom else 0L
                val moved = FleetEngineStore.reattributeObdData(context, plan.fromId, carId, fromMs, now, plan.asCorrection)
                moveTallies(context, plan.fromId, carId, plan.asCorrection)
                TelemetryRecorder.reattributeDriveInProgress(plan.fromId, carId)
                if (subject.mac.isNotBlank() && !subject.mac.startsWith("pending:")) rememberCar(context, subject.mac, carId)
                if (isLive) {
                    _state.value = OnCar(subject.mac, carId, (subject as? Held)?.vin ?: (subject as? OnCar)?.vin, Basis.ANSWER)
                    prefs(context).edit()
                        .remove(KEY_HELD_CAR).remove(KEY_HELD_FROM)
                        .putString(KEY_SESSION_ID, carId)
                        .putBoolean(KEY_SESSION_ANSWERED, true)
                        .apply()
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

    private fun movedWords(name: String, moved: FleetEngineStore.Reattributed, plan: AnswerPlan.Move): String {
        val parts = buildList {
            add("${moved.samples} reading${if (moved.samples == 1) "" else "s"}")
            if (moved.drives > 0) add("${moved.drives} drive${if (moved.drives == 1) "" else "s"}")
            if (moved.codeEvents > 0) add("${moved.codeEvents} trouble-code record${if (moved.codeEvents == 1) "" else "s"}")
        }
        val vin = plan.writeVin?.let { " Saved its ${DongleCar.vinTail(it)} so I'll know it next time." }.orEmpty()
        val cleared = if (plan.clearVinFrom.isNotEmpty()) {
            " Cleared that VIN from ${plan.clearVinFrom.size} other car${if (plan.clearVinFrom.size == 1) "" else "s"} that also had it."
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
    suspend fun answerNewCar(context: Context, subjectId: String? = null): Outcome {
        val subject = if (subjectId == null) _state.value else null
        val vin = (subject as? Held)?.vin ?: subjectId?.let { DongleCar.vinOfPending(it) }
            ?: return Outcome(
                false,
                "I can't look a car up without its VIN. Add it with its year, make and model (Cars, Add car, " +
                    "or say add my car), then tell me it's that one.",
            )
        val decoded = VinDecoder.decode(vin)
        if (decoded == null || decoded.year < MIN_YEAR || decoded.make.isBlank() || decoded.model.isBlank()) {
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
        return answer(context, id, subjectId)
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

    private suspend fun moveTallies(context: Context, fromId: String, toId: String, asCorrection: Boolean) {
        val now = System.currentTimeMillis()
        tripMiles.remove(fromId)?.let { miles ->
            // A pending id has no vehicles row, so its odometer add was a no-op; only the target gains.
            if (asCorrection) FleetEngineStore.addTripMiles(context, fromId, -miles, now)
            FleetEngineStore.addTripMiles(context, toId, miles, now)
            noteTripMiles(toId, miles)
        }
        val tally = lifetime.remove(fromId)
        TelemetryRecorder.moveLifetime(context, fromId, toId, tally, moveAll = !asCorrection)
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

    /** Test seam: forget this process's state. */
    internal fun resetForTest() {
        _state.value = null
        _conflict.value = null
        tripMiles.clear()
        lifetime.clear()
        lastReplaceAsk = null
        sessionFrom = 0L
    }

    /** Test seam: put a decision in place without a dongle. */
    internal fun setStateForTest(state: DongleCar?) {
        _state.value = state
    }
}
