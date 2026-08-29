package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.Vehicle
import org.json.JSONArray

/**
 * Fleet's first WRITE to the car (`.scratch/hands-and-senses/issues/01-clear-dtc.md`, resolved
 * 2026-08-16) - clearing stored trouble codes via OBD Mode 04. Owns the WHOLE transaction and the
 * confirm gate, the way [GarageController] owns `activate_garage`'s: [clear] is pure and
 * Context-free (unit-tested against a fake transport in `DtcClearControllerTest`, no Context
 * needed there), and [dispatchAndRecord] is the one Context-aware wrapper BOTH
 * `LiveToolbox.clearCodes` (voice) and `FleetScreen`'s STORED CODES dialog (UI) call - D5's "one
 * gate function" - so persistence/logging can never drift between the two surfaces.
 *
 * **D1: clearing is a TRANSACTION, not a send.** snapshot -> send -> re-read -> report what the
 * re-read returned. "Cleared" may NEVER be spoken off the send: `ObdBluetoothManager.sendCommand`
 * returns `""` on failure and a quiet link answers exactly like a successful Mode 04 ack at that
 * seam, so only [ClearOutcome.CLEARED] - assigned strictly off a POST-SEND re-read that came back
 * empty - may ever say "cleared". The `44` ack ([ClearResult.ackRaw]) is diagnostic only and never
 * upgrades a sentence (D2).
 */
object DtcClearController {

    /**
     * D2's five states, exhaustive. `Command sent?`: NOTHING_TO_CLEAR/REFUSED = no,
     * CLEARED/RETURNED/UNVERIFIED = yes - see [recordOutcome] for why only the latter three ever
     * earn a [CodeClearEvent] row.
     */
    enum class ClearOutcome { NOTHING_TO_CLEAR, CLEARED, RETURNED, UNVERIFIED, REFUSED }

    /**
     * What [clear] hands back to either surface - the one shared shape, so voice and the UI
     * dialog can never say something different about the same transaction (D5/D9).
     *
     * @param outcome `null` ONLY on the confirm-prompt turn (call 1 found real codes and is
     *   asking, D4.1) - not yet one of [ClearOutcome]'s five real states, since nothing happened.
     * @param codesBefore the snapshot this result's [message] is grounded in - the call-1 read for
     *   the prompt, or the call-2 re-read (D4.2) for a real outcome.
     * @param codesAfter `null` means "never actually re-read" (REFUSED/NOTHING_TO_CLEAR/UNVERIFIED
     *   never captured a trustworthy post-send set); an EMPTY list is a real re-read that came
     *   back clean (CLEARED). This distinction is why it is `List<String>?`, not `List<String>`.
     * @param message the ONE line to speak or show - D9's register, generated here so neither
     *   caller writes its own copy.
     */
    data class ClearResult(
        val outcome: ClearOutcome?,
        val codesBefore: List<String>,
        val codesAfter: List<String>?,
        val freezeFrameJson: String,
        val ackRaw: String,
        val message: String,
    )

    /**
     * The pure transaction, injected with the three raw operations a real car link performs -
     * this is the seam `DtcClearControllerTest` substitutes with canned strings, playing the role
     * a fake `ObdTransport` would at the byte-stream layer one level down (see that test's own
     * doc comment for why the fake sits at THIS seam rather than `ObdTransport`'s literal
     * inputStream/outputStream shape: the five-outcome logic below is about interpreting
     * [ObdResponseParser]'s vocabulary, not about the socket underneath it, and injecting raw
     * response strings tests exactly that logic without reimplementing Elm327Io's framing).
     *
     * @param confirmed false on the first call (D4.1: snapshot-and-maybe-ask); true only on the
     *   very next turn after the driver says yes (D4).
     * @param engineRunning RPM > 0 - D4.5's warn-not-gate clause on the confirm prompt.
     * @param readCodesRaw the RAW Mode 03 response ([ObdBluetoothManager.getDtcCodesRaw]) - called
     *   once for the prompt (`!confirmed`) or twice for a real attempt (D4.2's fresh pre-send
     *   re-read, then the post-send re-read that decides CLEARED/RETURNED/UNVERIFIED).
     * @param readFreezeFrame Mode 02 ([ObdBluetoothManager.getFreezeFrame]) - only read once the
     *   gate has actually fired, since the confirm prompt never mentions it.
     * @param sendClearRaw the RAW Mode 04 response ([ObdBluetoothManager.clearDtcCodes]) - D1/D2:
     *   never inspected for anything but [ClearResult.ackRaw]'s diagnostic value.
     */
    suspend fun clear(
        confirmed: Boolean,
        engineRunning: Boolean,
        readCodesRaw: suspend () -> String,
        readFreezeFrame: suspend () -> Map<String, Double>,
        sendClearRaw: suspend () -> String,
    ): ClearResult {
        val snapshotRaw = readCodesRaw()
        if (ObdResponseParser.isFailureResponse(snapshotRaw)) {
            return refused()
        }
        val codesBefore = ObdResponseParser.dtcCodes(snapshotRaw)
        if (codesBefore.isEmpty()) {
            return nothingToClear()
        }
        if (!confirmed) {
            // D4.1: call 1 performs the snapshot read and may end the operation without ever
            // asking (both branches above already did, for the two states that never ask). Real
            // codes exist and nothing is confirmed yet - recite the warning, ask, and stop.
            return ClearResult(
                outcome = null,
                codesBefore = codesBefore,
                codesAfter = null,
                freezeFrameJson = "",
                ackRaw = "",
                message = confirmMessage(codesBefore, engineRunning),
            )
        }

        // D4.2: the gate has fired - re-read FRESH immediately before sending. Turns can be a
        // minute apart; codesBeforeJson must say what was ACTUALLY erased, not what call 1 saw.
        val freshRaw = readCodesRaw()
        if (ObdResponseParser.isFailureResponse(freshRaw)) {
            return refused()
        }
        val freshBefore = ObdResponseParser.dtcCodes(freshRaw)
        if (freshBefore.isEmpty()) {
            return nothingToClear()
        }

        val freezeFrame = readFreezeFrame()
        val freezeFrameJson = if (freezeFrame.isEmpty()) "" else org.json.JSONObject(freezeFrame as Map<*, *>).toString()
        val ackRaw = sendClearRaw()

        // D1's whole point: this re-read, never the ack above, is what may ever say "cleared".
        val rereadRaw = readCodesRaw()
        if (ObdResponseParser.isFailureResponse(rereadRaw)) {
            return ClearResult(
                outcome = ClearOutcome.UNVERIFIED,
                codesBefore = freshBefore,
                codesAfter = null,
                freezeFrameJson = freezeFrameJson,
                ackRaw = ackRaw,
                message = "I sent the clear, but the car stopped answering, so I do not know whether it took.",
            )
        }
        val after = ObdResponseParser.dtcCodes(rereadRaw)
        return if (after.isEmpty()) {
            ClearResult(
                outcome = ClearOutcome.CLEARED,
                codesBefore = freshBefore,
                codesAfter = after,
                freezeFrameJson = freezeFrameJson,
                ackRaw = ackRaw,
                // D1's anti-overclaim sentence - not optional (D9).
                message = "Cleared. Nothing stored now. That means the erase took, not that the fault is " +
                    "gone - a live fault will come back after a drive cycle.",
            )
        } else {
            ClearResult(
                outcome = ClearOutcome.RETURNED,
                codesBefore = freshBefore,
                codesAfter = after,
                freezeFrameJson = freezeFrameJson,
                ackRaw = ackRaw,
                message = "Sent the clear. ${englishList(after)} came straight back. " +
                    (if (after.size == 1) "That fault is active, not stored." else "Those faults are active, not stored."),
            )
        }
    }

    private fun refused() = ClearResult(
        outcome = ClearOutcome.REFUSED,
        codesBefore = emptyList(),
        codesAfter = null,
        freezeFrameJson = "",
        ackRaw = "",
        message = "The car is not answering. I have not sent anything.",
    )

    private fun nothingToClear() = ClearResult(
        outcome = ClearOutcome.NOTHING_TO_CLEAR,
        codesBefore = emptyList(),
        codesAfter = null,
        freezeFrameJson = "",
        ackRaw = "",
        message = "Nothing stored. Nothing to clear.",
    )

    /**
     * D9's confirm-prompt register, recited every time (D4.3 - never first-use-only, since the
     * codes differ each time). [engineRunning] adds D4.5's warn-not-gate clause; an ECU that
     * genuinely refuses while running still surfaces as [ClearOutcome.REFUSED] on the next call,
     * so this is a heads-up, never a block.
     */
    private fun confirmMessage(codes: List<String>, engineRunning: Boolean): String {
        val pronoun = when (codes.size) {
            1 -> "it"
            2 -> "both"
            else -> "them"
        }
        val engineClause = if (engineRunning) {
            " Engine is running - some ECUs refuse a clear while running."
        } else {
            ""
        }
        return "${countWord(codes.size)} stored: ${englishList(codes)}. Clearing wipes $pronoun, wipes the " +
            "freeze frame, and resets the readiness monitors, so the car will fail an emissions test " +
            "until it has driven enough to reset them.$engineClause Do you want me to clear?"
    }

    private val NUMBER_WORDS = mapOf(
        1 to "One", 2 to "Two", 3 to "Three", 4 to "Four", 5 to "Five",
        6 to "Six", 7 to "Seven", 8 to "Eight", 9 to "Nine",
    )

    private fun countWord(n: Int): String = NUMBER_WORDS[n] ?: n.toString()

    /** "P0420" / "P0420 and P0128" / "P0420, P0128, and P0301" - Oxford comma, matching D9's example. */
    private fun englishList(codes: List<String>): String = when (codes.size) {
        0 -> ""
        1 -> codes[0]
        2 -> "${codes[0]} and ${codes[1]}"
        else -> codes.dropLast(1).joinToString(", ") + ", and ${codes.last()}"
    }

    // ------------------------------------------------------------------ Context-aware wrapper

    /**
     * The ONE gate function both `LiveToolbox.clearCodes` and the FleetScreen STORED CODES dialog
     * call (D5) - wires [clear] to the real [ObdBluetoothManager] operations, then [recordOutcome]
     * persists/logs. Neither caller reimplements a step.
     */
    suspend fun dispatchAndRecord(context: Context, vehicle: Vehicle, confirmed: Boolean): ClearResult {
        val engineRunning = (ObdBluetoothManager.getRpm() ?: 0) > 0
        val result = clear(
            confirmed = confirmed,
            engineRunning = engineRunning,
            readCodesRaw = { ObdBluetoothManager.getDtcCodesRaw() },
            readFreezeFrame = { ObdBluetoothManager.getFreezeFrame() },
            sendClearRaw = { ObdBluetoothManager.clearDtcCodes() },
        )
        recordOutcome(context, vehicle, result)
        return result
    }

    /**
     * D8's three observability channels for a REAL outcome (the confirm-prompt turn, `outcome ==
     * null`, logs nothing - nothing happened yet). [MidnightEvents.dtcCleared] and [CarProbeLog]
     * fire for all five outcomes; the durable [CodeClearEvent] row is written ONLY for
     * CLEARED/RETURNED/UNVERIFIED (D2's "Command sent?" column) - see [CodeClearEvent]'s own doc
     * comment. D6: nothing here ever touches `service_records` or `maintenance_items` - a clear is
     * a diagnostic act, not work performed on the car.
     *
     * `internal`, not `private` - [dispatchAndRecord] calls this normally, but reaching it through
     * [dispatchAndRecord] in a test also means going through the real [ObdBluetoothManager]
     * singleton (a static object wrapping a physical Bluetooth link, no fake/injection seam of its
     * own). `internal` lets `DtcClearRecordingTest` call this directly with a hand-built
     * [ClearResult], exercising the real persistence code (Room, MidnightEvents, CarProbeLog)
     * without needing a real OBD transport.
     */
    internal suspend fun recordOutcome(context: Context, vehicle: Vehicle, result: ClearResult) {
        val outcome = result.outcome ?: return
        MidnightEvents.dtcCleared(outcome.name, result.codesBefore, result.codesAfter ?: emptyList())
        CarProbeLog.log(
            "DtcClear",
            "outcome=${outcome.name} before=${result.codesBefore} after=${result.codesAfter ?: "unread"} " +
                "ack='${result.ackRaw.take(60).replace('\n', ' ')}'",
        )

        val sent = outcome == ClearOutcome.CLEARED || outcome == ClearOutcome.RETURNED || outcome == ClearOutcome.UNVERIFIED
        if (!sent) return

        FleetEngineStore.recordCodeClearEvent(
            context = context,
            mac = vehicle.obdMac,
            timestamp = System.currentTimeMillis(),
            mileage = VehicleController.currentMileage(vehicle),
            codesBeforeJson = JSONArray(result.codesBefore).toString(),
            freezeFrameJson = result.freezeFrameJson,
            codesAfterJson = result.codesAfter?.let { JSONArray(it).toString() } ?: "",
            outcome = outcome.name,
            ackRaw = result.ackRaw,
        )
    }
}
