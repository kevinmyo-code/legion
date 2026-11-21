package com.kevin.legion.ui.settings

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.BuildConfig
import com.kevin.legion.MidnightApplication
import com.kevin.legion.carlink.CarLinkController
import com.kevin.legion.carlink.CarLinkNote
import com.kevin.legion.carlink.CarLinkStatus
import com.kevin.legion.carlink.HandoverGap
import com.kevin.legion.carlink.LinkState
import com.kevin.legion.carlink.VehicleOption
import com.kevin.legion.carlink.protocol.PairingFailure
import com.kevin.legion.carlink.protocol.PairingStage
import com.kevin.legion.carlink.protocol.QrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class CarLinkPhase { NOT_PAIRED, SCANNING, DIGITS, WAITING_FOR_UNIT, PAIRED, FAILED, BROKEN }

/** The Car link screen's one state. Every line is a sentence; nothing is said by colour alone. */
data class CarLinkUiState(
    val phase: CarLinkPhase = CarLinkPhase.NOT_PAIRED,
    val headline: String = "Not paired with a head unit.",
    val detail: String? = null,
    /** The six digits to type on the unit. Only in [CarLinkPhase.DIGITS]. */
    val digits: String? = null,
    val note: String? = null,
    val sendLocation: Boolean = false,
    /** What pairing hands the car, in words (the phone's own Gemini key and map token, or their absence). */
    val handsOver: String = "",
    /** The household's cars and the one this head unit is in. */
    val vehicles: List<VehicleOption> = emptyList(),
    val vehicleId: String? = null,
    val vehicleNote: String? = null,
    val canPair: Boolean = true,
    val canCancel: Boolean = false,
    val canForget: Boolean = false,
    val showScan: Boolean = true,
    val debugPaste: Boolean = false,
    /** Why there is no scan button (no camera on this phone), when there is none. */
    val scanNote: String? = null,
    /** The camera was refused: what to do. */
    val cameraNote: String? = null,
    /** The scanner itself could not start (ML Kit failed). */
    val scannerFailure: String? = null,
    /** The last code the camera read was not a pairing code. */
    val scanReject: String? = null,
) {
    // A data class's toString would print the digits; nothing may log them.
    override fun toString() = "CarLinkUiState($phase)"
}

/** The scanner's own bit of state, kept apart from the controller's. */
private data class ScanUi(
    val scanning: Boolean = false,
    val cameraNote: String? = null,
    val reject: String? = null,
    val scannerFailure: String? = null,
)

/**
 * The Car link screen's ViewModel (Settings, Car link): one [StateFlow] of [CarLinkUiState] over the
 * process's one [CarLinkController]. [Factory] is the injection point (no Hilt in the build yet).
 * The QR comes from the camera pane (CameraX + ML Kit, bundled model) through [onScanned], which
 * hands nothing anywhere but the pairing parser.
 */
class CarLinkViewModel(
    private val controller: CarLinkController,
    private val scannerAvailable: Boolean,
    private val debugPaste: Boolean,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val scanUi = MutableStateFlow(ScanUi())
    private val _state = MutableStateFlow(CarLinkUiState())
    val state: StateFlow<CarLinkUiState> = _state.asStateFlow()

    init {
        this.scope.launch { controller.status.combine(scanUi) { s, u -> render(s, u) }.collect { _state.value = it } }
        // Reads the stored pairing (off the main thread) and what the screen shows before a pairing.
        controller.showScreen()
    }

    /** Scan tapped: the screen asks for the camera (only now) and shows the camera pane. */
    fun scan() {
        controller.dismiss()
        scanUi.value = ScanUi(scanning = true)
    }

    /**
     * The camera read a QR. Its text goes to nothing but the pairing parser: anything that is not a
     * head unit pairing code (a web address included) is refused in words and scanning goes on.
     */
    fun onScanned(raw: String) {
        if (!scanUi.value.scanning) return
        val text = raw.trim()
        if (QrPayload.parse(text) == null) {
            scanUi.value = scanUi.value.copy(reject = CarLinkWords.NOT_A_PAIRING_CODE)
            return
        }
        scanUi.value = ScanUi()
        controller.pair(text)
    }

    /** ML Kit could not start: leave the pane (its camera is released) and say so; Scan tries again. */
    fun onScannerFailed() {
        scanUi.value = ScanUi(scannerFailure = CarLinkWords.SCANNER_FAILED)
    }

    /** The camera was refused: say what to do; Scan stays there to try again. */
    fun onCameraDenied() {
        scanUi.value = ScanUi(cameraNote = CarLinkWords.CAMERA_REFUSED)
    }

    /** Debug builds only: the pasted `mnai-pair:` text stands in for a scan. */
    fun pasteCode(text: String) {
        if (!debugPaste) return
        controller.dismiss()
        controller.pair(text)
    }

    fun cancel() {
        scanUi.value = ScanUi()
        controller.cancel()
    }

    fun dismiss() = controller.dismiss()

    fun forget() {
        scope.launch { controller.forget() }
    }

    fun setSendLocation(on: Boolean) = controller.setSendLocation(on)

    fun chooseVehicle(id: String?) = controller.chooseVehicle(id)

    private fun render(s: CarLinkStatus, u: ScanUi): CarLinkUiState {
        val base = CarLinkUiState(
            sendLocation = s.sendLocation,
            // Neither line is true before the facts are read: say nothing until then.
            handsOver = if (s.screenFactsLoaded) CarLinkWords.handsOver(s.gaps) else "",
            vehicles = s.vehicles,
            vehicleId = s.vehicleId,
            vehicleNote = if (s.screenFactsLoaded && s.vehicles.isEmpty()) CarLinkWords.NO_VEHICLE else null,
            // No dead button: with no camera the scan button is not drawn, and the words say why.
            showScan = scannerAvailable,
            scanNote = scanNote(scannerAvailable, debugPaste),
            cameraNote = u.cameraNote,
            scanReject = u.reject,
            scannerFailure = u.scannerFailure,
            debugPaste = debugPaste,
            note = s.note?.let(CarLinkWords::note),
            canForget = s.link == LinkState.PAIRED || s.link == LinkState.BROKEN,
        )
        val scanningNow = u.scanning
        val p = s.pairing
        return when {
            scanningNow -> base.copy(
                phase = CarLinkPhase.SCANNING,
                headline = "Point the camera at the code on the head unit.",
                canPair = false,
                canCancel = true,
            )
            p is PairingStage.DigitsShown -> base.copy(
                phase = CarLinkPhase.DIGITS,
                headline = "Type these six digits on the head unit.",
                detail = "The head unit never shows them. Three wrong tries end this pairing.",
                digits = p.digits,
                canPair = false,
                canCancel = true,
            )
            p == PairingStage.AwaitingAck -> base.copy(
                phase = CarLinkPhase.WAITING_FOR_UNIT,
                headline = "Reaching the head unit.",
                canPair = false,
                canCancel = true,
            )
            p == PairingStage.AwaitingDone || p == PairingStage.StoredSent -> base.copy(
                phase = CarLinkPhase.WAITING_FOR_UNIT,
                headline = "Waiting for the head unit to save the pairing.",
                canPair = false,
                canCancel = true,
            )
            p is PairingStage.Failed -> base.copy(
                phase = CarLinkPhase.FAILED,
                headline = CarLinkWords.failure(p),
                detail = CarLinkWords.linkLine(s),
            )
            else -> resting(s, base)
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CarLinkViewModel(
                controller = (app as MidnightApplication).carLink.controller,
                scannerAvailable = app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                debugPaste = BuildConfig.DEBUG,
            ) as T
    }
}

/** No pairing on screen: what the stored link is. */
private fun resting(s: CarLinkStatus, base: CarLinkUiState): CarLinkUiState {
    val p = s.pairing
    return when {
        s.link == LinkState.LOADING -> base.copy(headline = "Reading the car link.", canPair = false)
        s.link == LinkState.BROKEN -> base.copy(
            phase = CarLinkPhase.BROKEN,
            headline = "Pairing broken: pair again.",
            detail = "This phone cannot read its car link key, so it sends the head unit nothing.",
        )
        s.link == LinkState.PAIRED -> base.copy(
            phase = CarLinkPhase.PAIRED,
            headline = if (p == PairingStage.Paired) "Paired with the head unit." else "Paired with a head unit.",
            detail = when {
                s.networkChanged -> "Not listening yet: the network it paired on changed its address range."
                s.unitInRange -> "The head unit is in range."
                else -> "The head unit is out of range now."
            },
        )
        s.revokePending -> base.copy(
            detail = "The head unit will be told to forget this phone the next time this phone is in " +
                "the car (for up to 90 days). Until then this phone sends it nothing else.",
        )
        else -> base
    }
}

/** Why there is no Scan button, when there is none. */
private fun scanNote(scannerAvailable: Boolean, debugPaste: Boolean): String? = when {
    scannerAvailable -> null
    debugPaste -> "No camera on this phone: this debug build pairs from pasted code text below."
    else -> "No camera on this phone, so it cannot read the pairing code."
}

/** The words for every outcome. */
object CarLinkWords {
    fun linkLine(s: CarLinkStatus): String = when (s.link) {
        LinkState.PAIRED -> "This phone is still paired with its earlier head unit."
        LinkState.BROKEN -> "Pairing broken: pair again."
        LinkState.NOT_PAIRED, LinkState.LOADING -> "This phone is not paired with a head unit."
    }

    const val SCANNER_FAILED = "The scanner could not start on this phone."
    const val NOT_A_PAIRING_CODE =
        "That is not a head unit pairing code. Point the camera at the code on the head unit."
    const val CAMERA_REFUSED = "LEGION uses the camera only to read the pairing code on the head unit. " +
        "To allow it: Android Settings, Apps, LEGION, Permissions, Camera, \"Allow only while using the app\". " +
        "Then tap Scan again."
    const val NO_VEHICLE = "No car in LEGION yet: the head unit will send no car data until it is tied to one."

    /** What pairing hands the car, in words; what this phone lacks is said, never hidden. */
    fun handsOver(gaps: Set<HandoverGap>): String {
        val noKey = HandoverGap.NO_GEMINI_KEY in gaps
        val noMap = HandoverGap.NO_MAP_TOKEN in gaps
        return when {
            noKey && noMap -> "This phone has no Gemini key and no map token, so the car will get neither."
            noKey -> "This phone has no Gemini key, so the car will get none. It gets this phone's map token."
            noMap -> "This phone has no map token, so the car will get none. It gets this phone's Gemini key."
            else -> "Pairing hands the car this phone's Gemini key and map token."
        }
    }

    @Suppress("CyclomaticComplexMethod") // one sentence per failure code; a table, not logic
    fun failure(f: PairingStage.Failed): String = when (f.why) {
        PairingFailure.BAD_QR -> "That is not a head unit pairing code. Nothing was sent."
        PairingFailure.UNREACHABLE ->
            "Could not reach the head unit after four tries. Is this phone on the same Wi-Fi or hotspot?"
        PairingFailure.UNIT_NOT_PROVEN -> "The head unit could not prove it is the one on the screen. Nothing was sent."
        PairingFailure.UNEXPECTED_MESSAGE ->
            "The head unit sent something this phone could not read. Pairing stopped; nothing was saved."
        PairingFailure.CONNECTION_LOST -> "The connection dropped before the head unit saved anything. Nothing changed."
        PairingFailure.NO_ANSWER -> "The head unit stopped answering. Nothing changed."
        PairingFailure.PHONE_STORE_FAILED ->
            "This phone could not save the new pairing securely, so it kept the earlier one."
        PairingFailure.CANCELLED_HERE -> "Pairing cancelled on this phone."
        PairingFailure.UNIT_BAD_VERSION -> "The head unit uses a different pairing version. Update one of them."
        PairingFailure.UNIT_BAD_MESSAGE -> "The head unit could not read this phone's message. Pairing stopped."
        PairingFailure.UNIT_TIMEOUT -> "The head unit timed out. Show a new code and scan again."
        PairingFailure.UNIT_CODE_WRONG -> "The digits were typed wrong three times. Show a new code and scan again."
        PairingFailure.UNIT_CANCELLED -> "Pairing was cancelled on the head unit."
        PairingFailure.UNIT_STORE_FAILED -> "The head unit could not save the pairing. Nothing changed."
        PairingFailure.UNIT_STOPPED -> "The head unit stopped pairing."
        PairingFailure.NOT_COMPLETED ->
            if (f.hadEarlierPairing) {
                "The new pairing did not complete. The earlier pairing stays."
            } else {
                "The pairing did not complete. This phone is not paired."
            }
    }

    @Suppress("CyclomaticComplexMethod") // one sentence per note; a table, not logic
    fun note(n: CarLinkNote): String = when (n) {
        CarLinkNote.CONFIRMED -> "The head unit confirmed the new pairing."
        CarLinkNote.REVERTED_QUIET ->
            "The head unit did not keep the new pairing, so this phone went back to the earlier one."
        CarLinkNote.FORGOT_REVOKE_SENT -> "Forgotten."
        CarLinkNote.FORGOT_NOT_IN_RANGE ->
            "Forgotten on this phone. The head unit was not in range, so it still lists this pairing " +
                "until you forget it there."
        CarLinkNote.FORGET_FAILED -> "Could not forget the head unit: this phone could not change its saved pairing."
        CarLinkNote.PORT_UNAVAILABLE -> "Could not open the car link port, so no location goes to the head unit."
        CarLinkNote.SETTING_NOT_SAVED -> "That setting could not be saved."
        CarLinkNote.NETWORK_NOT_RECORDED ->
            "Paired, but this phone could not tell which network the head unit is on, so it will send it " +
                "nothing. Pair again with this phone's hotspot on and the head unit joined to it."
        CarLinkNote.SERVICE_NO_LOCATION_PERMISSION ->
            "Android stopped the screen-off sending: LEGION has no location permission right now. Allow " +
                "location for LEGION (\"While using the app\"), then turn \"Send location\" off and on."
        CarLinkNote.SERVICE_NOT_STARTED ->
            "Android did not let LEGION keep sending with the screen off. Open LEGION with \"Send location\" " +
                "on to start it."
        CarLinkNote.PORT_TAKEN ->
            "Another app is using the car link's port (17311), so this phone cannot hear the car. It does " +
                "not move to another port. Close that app or restart the phone."
        CarLinkNote.FORGET_TELLING -> "Telling the car to forget this phone..."
        CarLinkNote.NETWORK_CHANGED_HEALING ->
            "This phone's hotspot came back with a different address range. Waiting for the car to " +
                "confirm it (two messages from the car), then this phone listens there again."
        CarLinkNote.NETWORK_CHANGED_REPAIR ->
            "The network this phone paired on now has a different address range. For safety this phone " +
                "does not follow it: pair again."
        CarLinkNote.FORGOT_WILL_TELL ->
            "The car has not heard yet; it will be told next time it is in range (for up to 90 days)."
    }
}
