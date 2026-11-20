package com.kevin.legion.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.BuildConfig
import com.kevin.legion.MidnightApplication
import com.kevin.legion.carlink.CarLinkController
import com.kevin.legion.carlink.CarLinkNote
import com.kevin.legion.carlink.CarLinkStatus
import com.kevin.legion.carlink.LinkState
import com.kevin.legion.carlink.protocol.PairingFailure
import com.kevin.legion.carlink.protocol.PairingStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads a pairing QR. A DECISION FOR KEVIN, not made here: ZXing was removed by ruling, and
 * CameraX + ML Kit, Google's code scanner and ZXing core are each a new dependency. Until he picks
 * one, [NoQrScannerChosen] is wired and debug builds offer a paste field instead.
 */
interface QrScanner {
    val available: Boolean
    fun scan(onResult: (String?) -> Unit)
}

/** What a build with no scanner and no paste field says instead of a scan button. */
const val SCAN_LATER = "Scanning arrives in a later update."

class NoQrScannerChosen : QrScanner {
    override val available = false
    override fun scan(onResult: (String?) -> Unit) = onResult(null)
}

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
    /** Whether the car's own Gemini key is set. The key itself is never shown. */
    val carKeySet: Boolean = false,
    val canPair: Boolean = true,
    val canCancel: Boolean = false,
    val canForget: Boolean = false,
    val scannerAvailable: Boolean = false,
    val debugPaste: Boolean = false,
    val scanNote: String? = null,
    val showScan: Boolean = true,
) {
    // A data class's toString would print the digits; nothing may log them.
    override fun toString() = "CarLinkUiState($phase)"
}

/**
 * The Car link screen's ViewModel (Settings, Car link): one [StateFlow] of [CarLinkUiState] over the
 * process's one [CarLinkController]. [Factory] is the injection point (no Hilt in the build yet).
 */
class CarLinkViewModel(
    private val controller: CarLinkController,
    private val scanner: QrScanner,
    private val debugPaste: Boolean,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val scanning = MutableStateFlow(false)
    private val _state = MutableStateFlow(CarLinkUiState())
    val state: StateFlow<CarLinkUiState> = _state.asStateFlow()

    init {
        this.scope.launch { controller.status.collect { s -> _state.value = render(s, scanning.value) } }
        this.scope.launch { scanning.collect { _state.value = render(controller.status.value, it) } }
        // Reads the stored pairing off the main thread if app start has not yet.
        controller.start()
    }

    fun scan() {
        controller.dismiss()
        scanning.value = true
        scanner.scan { text ->
            scanning.value = false
            text?.let(controller::pair)
        }
    }

    /** Debug builds only: the pasted `mnai-pair:` text stands in for a scan. */
    fun pasteCode(text: String) {
        if (!debugPaste) return
        controller.dismiss()
        controller.pair(text)
    }

    fun cancel() {
        scanning.value = false
        controller.cancel()
    }

    fun dismiss() = controller.dismiss()

    fun forget() {
        scope.launch { controller.forget() }
    }

    fun setSendLocation(on: Boolean) = controller.setSendLocation(on)

    fun setCarGeminiKey(key: String?) = controller.setCarGeminiKey(key)

    private fun render(s: CarLinkStatus, scanningNow: Boolean): CarLinkUiState {
        val base = CarLinkUiState(
            sendLocation = s.sendLocation,
            carKeySet = s.carKeySet,
            scannerAvailable = scanner.available,
            // No dead button: with no scanner the scan button is not drawn, and the words say why.
            showScan = scanner.available,
            scanNote = when {
                scanner.available -> null
                debugPaste -> "No code scanner yet: this debug build pairs from pasted code text below."
                else -> SCAN_LATER
            },
            debugPaste = debugPaste,
            note = s.note?.let(CarLinkWords::note),
            canForget = s.link == LinkState.PAIRED || s.link == LinkState.BROKEN,
        )
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
            p is PairingStage.Failed ->
                base.copy(phase = CarLinkPhase.FAILED, headline = CarLinkWords.failure(p), detail = linkLine(s))
            else -> resting(s, base)
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
                detail = if (s.unitInRange) "The head unit is in range." else "The head unit is out of range now.",
            )
            s.revokePending -> base.copy(
                detail = "The head unit will be told to forget this phone the next time this phone is in " +
                    "the car (for up to 90 days). Until then this phone sends it nothing else.",
            )
            else -> base
        }
    }

    private fun linkLine(s: CarLinkStatus): String = when (s.link) {
        LinkState.PAIRED -> "This phone is still paired with its earlier head unit."
        LinkState.BROKEN -> "Pairing broken: pair again."
        LinkState.NOT_PAIRED, LinkState.LOADING -> "This phone is not paired with a head unit."
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as Application

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CarLinkViewModel(
                controller = (app as MidnightApplication).carLink.controller,
                scanner = NoQrScannerChosen(),
                debugPaste = BuildConfig.DEBUG,
            ) as T
    }
}

/** The words for every outcome. */
object CarLinkWords {
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

    fun note(n: CarLinkNote): String = when (n) {
        CarLinkNote.CONFIRMED -> "The head unit confirmed the new pairing."
        CarLinkNote.REVERTED_QUIET ->
            "The head unit did not keep the new pairing, so this phone went back to the earlier one."
        CarLinkNote.FORGOT_REVOKE_SENT -> "Forgotten. The head unit was told to forget this phone too."
        CarLinkNote.FORGOT_NOT_IN_RANGE ->
            "Forgotten on this phone. The head unit was not in range, so it still lists this pairing " +
                "until you forget it there."
        CarLinkNote.FORGET_FAILED -> "Could not forget the head unit: this phone could not change its saved pairing."
        CarLinkNote.PORT_UNAVAILABLE -> "Could not open the car link port, so no location goes to the head unit."
        CarLinkNote.SETTING_NOT_SAVED -> "That setting could not be saved."
        CarLinkNote.NETWORK_NOT_RECORDED ->
            "Paired, but this phone could not tell which network the head unit is on, so it will send it " +
                "nothing. Pair again with this phone's hotspot on and the head unit joined to it."
        CarLinkNote.FORGOT_WILL_TELL ->
            "Forgotten on this phone. The head unit was not in range, so it will be told to forget this " +
                "phone the next time this phone is in the car (for up to 90 days)."
    }
}
