package com.kevin.legion.vehicle.diag

import android.content.Context
import android.util.Log
import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one entry point for a module scan or clear, shared by voice (`get_codes` with
 * `all_modules`, the `car` tool's `clear_codes` with `modules`) and the Faults screen (ADR 0035:
 * one controller, two paths). A scan is a job owned here, not by the caller: it outlives a voice
 * tool's timeout, and its result lands in Room and in [state] either way.
 */
object DiagScanController {
    private const val TAG = "DiagScan"

    sealed interface State {
        data object Idle : State

        data class Running(val carId: String, val full: Boolean, val progress: ModuleScanner.Progress?) : State

        data class Done(val carId: String, val scanId: Long, val report: ScanReport) : State

        data class Failed(val carId: String?, val words: String) : State
    }

    enum class Start { STARTED, ALREADY_RUNNING, NOT_CONNECTED }

    private const val OTHER_CAR =
        "That scan was of another car than the one the dongle is in now. Scan again first."

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    const val NOT_CONNECTED =
        "The OBD dongle is not connected, so no module can be read. Plug it in, key at ON, and try again."

    /** Starts a scan of the car the dongle is in, unless one is running. Never blocks. */
    fun start(context: Context, full: Boolean): Start = when {
        !ObdBluetoothManager.isConnected -> Start.NOT_CONNECTED
        _state.value is State.Running -> Start.ALREADY_RUNNING
        else -> {
            val app = context.applicationContext
            val carId = ActiveVehicle.recordingId(app)
            _state.value = State.Running(carId, full, null)
            scope.launch { run(app, carId, full) }
            Start.STARTED
        }
    }

    private suspend fun run(context: Context, carId: String, full: Boolean) {
        val outcome = runCatching {
            gate.withLock {
                val make = DiagScanStore.makeFor(context, carId)
                val options = ModuleScanner.Options(
                    make = make,
                    full = full,
                    budgetMs = if (full) ModuleScanner.FULL_BUDGET_MS else ModuleScanner.QUICK_BUDGET_MS,
                    packs = DiagPacks.forMake(DiagScanStore.packs(context), make),
                )
                ObdBluetoothManager.withDiagnosticLink { link ->
                    ModuleScanner(link, System::currentTimeMillis, { delay(it) }) { p ->
                        _state.value = State.Running(carId, full, p)
                    }.scan(options)
                }
            }
        }
        val report = outcome.getOrNull()
        _state.value = when {
            outcome.isFailure -> State.Failed(carId, "The scan failed: ${outcome.exceptionOrNull()?.message}")
            report == null -> State.Failed(carId, NOT_CONNECTED)
            else -> State.Done(carId, DiagScanStore.save(context, carId, report), report)
        }
        outcome.exceptionOrNull()?.let { Log.w(TAG, "scan failed", it) }
        report?.let {
            CarProbeLog.log(TAG, "car=$carId bus=${it.bus} modules=${it.modules.size} complete=${it.complete}")
        }
    }

    /** Waits up to [timeoutMs] for the running scan to finish; the state at that moment. */
    suspend fun await(timeoutMs: Long): State =
        withTimeoutOrNull(timeoutMs) { state.first { it !is State.Running } } ?: _state.value

    /**
     * Sends the clears for [addresses] (null = every target the plan offers) from stored scan
     * [scanId]; each target is re-read inside the clear. Null when the dongle is gone. The plan is
     * recomputed here, so no caller can clear what the planner excluded (the airbag module, any
     * module outside 7E0-7E7). Refused in words for a scan of another car, a scan older than
     * [MAX_SCAN_AGE_MS], or while the dongle's car is not answered yet. The engine step is recorded
     * as a code-clear event exactly like the engine-only clear; the whole run is kept on the scan row.
     */
    suspend fun clear(context: Context, scanId: Long, addresses: Set<String>?): ClearRun? {
        val stored = DiagScanStore.get(context, scanId)
        val carId = ActiveVehicle.recordingId(context)
        val refusal = when {
            stored == null -> "That scan is no longer on file."
            DongleCar.isPending(carId) -> PENDING_CAR
            // Only recognised by its print, or not decided yet: named or VIN-proven cars only (2026-11-17).
            com.kevin.legion.vehicle.AdapterBinding.clearRefusalNow() != null ->
                com.kevin.legion.vehicle.AdapterBinding.clearRefusalNow()
            stored.row.vehicleId != carId -> OTHER_CAR
            System.currentTimeMillis() - stored.report.finishedAtMs > MAX_SCAN_AGE_MS -> TOO_OLD
            else -> null
        }
        return if (stored == null || refusal != null) {
            ClearRun(emptyList(), refusal)
        } else {
            send(context, stored, addresses)
        }
    }

    private suspend fun send(context: Context, stored: StoredScan, addresses: Set<String>?): ClearRun? {
        val chosen = ClearPlanner.plan(stored.report).targets.filter { addresses == null || it.address in addresses }
        val run = gate.withLock {
            ObdBluetoothManager.withDiagnosticLink { link ->
                ModuleClearer(link, stored.report.bus).clear(chosen, stored.report)
            }
        }
        run?.let { r ->
            r.engineTransaction?.let { tx ->
                val car = VehicleController.vehicleFor(context, stored.row.vehicleId)
                DtcClearController.recordOutcome(context, car, tx)
            }
            DiagScanStore.saveClear(context, stored.row.id, r)
            val outcomes = r.results.joinToString { "${it.target.address}=${it.outcome}" }
            CarProbeLog.log(TAG, "clear car=${stored.row.vehicleId} $outcomes")
            // A scan from before the clear no longer says what is stored; voice must not reuse it.
            if (_state.value is State.Done) _state.value = State.Idle
        }
        return run
    }

    /** Longest a scan may be old and still drive a clear. */
    const val MAX_SCAN_AGE_MS = 30 * 60_000L
    private const val TOO_OLD = "That scan is more than 30 minutes old. Scan again first, then clear."
    private const val PENDING_CAR = "Which car the dongle is in has not been answered yet. Answer that first, " +
        "then scan and clear."
}
