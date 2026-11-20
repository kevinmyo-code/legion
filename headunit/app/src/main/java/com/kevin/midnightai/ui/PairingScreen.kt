package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kevin.midnightai.diag.DriveLog
import com.kevin.midnightai.link.AndroidHandoverSink
import com.kevin.midnightai.link.CarLink
import com.kevin.midnightai.link.CarLinkRules
import com.kevin.midnightai.link.LinkKeyStore
import com.kevin.midnightai.link.LinkStatus
import com.kevin.midnightai.link.OwnerPin
import com.kevin.midnightai.link.OwnerPinGate
import com.kevin.midnightai.link.PairingCrypto
import com.kevin.midnightai.link.PairingServer
import com.kevin.midnightai.link.PairingSession
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.update.DrivingState
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The number pad's key edge for the space it gets (pure; tested at the unit's 800x456 pane). */
internal object PairingPad {
    const val GAP_DP = 8f

    /** 3 columns x 4 rows; the largest square key that fits, or null if a drive-size key does not. */
    fun keyEdge(widthDp: Float, heightDp: Float): Float? {
        val edge = minOf((widthDp - 2 * GAP_DP) / 3f, (heightDp - 3 * GAP_DP) / 4f, 72f)
        return edge.takeIf { it >= AriaDimens.driveTouchMin.value }
    }
}

/**
 * Settings > Phones & audio > Car link (HU-1 T3, protocol revision 2). The unit shows a QR (never
 * while the car moves); the phone scans it and shows six digits; the driver TYPES them here on the
 * pad (three tries). Nothing on this screen takes keyboard input and nothing secret is drawn or
 * logged. Every network call runs on the pairing worker, never the UI thread. Forget and Pair again,
 * when already paired, need a hold.
 */
@Composable
internal fun CarLinkSection(context: Context, onBack: () -> Unit) {
    val store = remember { CarLink.store as? LinkKeyStore ?: LinkKeyStore.forApp(context) }
    val ownerPin = remember { OwnerPin(context) }
    val status by CarLink.status.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    // Owner PIN (2026-11-20): which guarded action waits for the PIN, the setup pad, a message.
    var asking by remember { mutableStateOf<GuardedAction?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var pinSet by remember { mutableStateOf(false) }
    var holdsToken by remember { mutableStateOf(false) }
    var facts by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(facts, status) {
        withContext(Dispatchers.IO) {
            CarLink.refresh()
            pinSet = ownerPin.isSet()
            holdsToken = runCatching { store.holdsLinkEngineToken() }.getOrDefault(false)
        }
    }
    fun perform(action: GuardedAction) {
        when (action) {
            GuardedAction.PAIR_AGAIN -> { attempt++; pairing = true }
            GuardedAction.FORGET, GuardedAction.RESET -> scope.launch(Dispatchers.IO) {
                store.forget() // the pairing, every handed-over value and the owner PIN, together
                DriveLog.breadcrumb(if (action == GuardedAction.RESET) "car link reset" else "car link forgotten")
                CarLink.changed(context)
                facts++
            }
        }
    }
    fun request(action: GuardedAction) {
        note = null
        when (OwnerPinGate.gate(pinSet, DrivingState.isDriving())) {
            OwnerPinGate.Gate.ALLOWED -> perform(action)
            OwnerPinGate.Gate.PARK_FIRST -> note = OwnerPinGate.PARK_FIRST
            OwnerPinGate.Gate.NEEDS_PIN -> asking = action
        }
    }
    SectionScaffold("Car link", onBack) {
        val waiting = asking
        when {
            pairing || status == LinkStatus.NOT_PAIRED -> PairingPane(context, store, attempt) { pairing = false; facts++ }
            settingPin -> OwnerPinSetup(ownerPin, onDone = { settingPin = false; facts++; note = "Owner PIN set." }, onCancel = { settingPin = false })
            waiting != null -> OwnerPinPrompt(
                ownerPin,
                prompt = if (waiting == GuardedAction.FORGET) "Owner PIN to forget the phone." else "Owner PIN to pair again.",
                onOk = { asking = null; perform(waiting) },
                onCancel = { asking = null },
            )
            else -> {
                Text(CarLinkRules.statusLine(status, store.phoneName()), style = RowTitle, color = AriaColors.TextPrimary)
                Spacer(Modifier.height(AriaDimens.s3))
                Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
                    HoldButton("HOLD TO PAIR AGAIN") { request(GuardedAction.PAIR_AGAIN) }
                    HoldButton("HOLD TO FORGET") { request(GuardedAction.FORGET) }
                }
                Text(
                    "Forget removes the link and everything the phone sent over it (sign-in, keys), and the owner " +
                        "PIN. Anything you typed into this unit by hand stays.",
                    style = RowSubtitle, color = AriaColors.TextDim,
                )
                note?.let { Text(it, style = RowSubtitle, color = AriaColors.accentText(AriaColors.Amber)) }
                if (holdsToken && !pinSet) {
                    Spacer(Modifier.height(AriaDimens.s2))
                    Text(
                        "This unit holds an engine sign-in from your phone. Set an owner PIN so nobody else in the car " +
                            "can forget, re-pair or change it.",
                        style = RowSubtitle, color = AriaColors.TextPrimary,
                    )
                    OutlinedButton(
                        onClick = { if (DrivingState.isDriving()) note = OwnerPinGate.PARK_FIRST else settingPin = true },
                        modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
                    ) { Text("SET OWNER PIN") }
                }
                if (pinSet) {
                    Spacer(Modifier.height(AriaDimens.s2))
                    Text(
                        "Forgot the PIN? Reset removes the pairing, the engine sign-in from the phone and the PIN " +
                            "together; pair your phone again afterwards.",
                        style = RowSubtitle, color = AriaColors.TextDim,
                    )
                    HoldButton("HOLD TO RESET THE CAR LINK") { perform(GuardedAction.RESET) }
                }
            }
        }
    }
}

/** What the owner PIN guards on this screen; RESET is the no-PIN way out that wipes everything. */
internal enum class GuardedAction { PAIR_AGAIN, FORGET, RESET }

/** A drive-size button that acts only on a long press (review M6: no one-tap destructive action). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HoldButton(label: String, onHeld: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = AriaDimens.driveTouchMin)
            .clip(shape)
            .border(1.dp, AriaColors.PanelBorder, shape)
            .combinedClickable(onClick = {}, onLongClick = onHeld, onLongClickLabel = label),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = RowTitle, color = AriaColors.TextPrimary, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

private class Pairing(val server: PairingServer, val qr: Bitmap?)

@Composable
private fun PairingPane(context: Context, store: LinkKeyStore, attempt: Int, onFinished: () -> Unit) {
    // Re-checked every second (recheck 2faecc19): the QR goes away if the car starts moving
    // mid-pairing. Leaving the pairing branch disposes its effects, which closes the server, so the
    // session ENDS; parking again starts a fresh one with a new QR (the simpler of the two options).
    var driving by remember { mutableStateOf(DrivingState.isDriving()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            driving = DrivingState.isDriving()
        }
    }
    if (driving) {
        Text("Park to pair: the pairing code is never shown while the car moves.", style = RowTitle, color = AriaColors.TextPrimary)
        return
    }
    var pairing by remember(attempt) { mutableStateOf<Pairing?>(null) }
    var problem by remember(attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        // Socket bind, key generation and the QR all happen off the UI thread.
        // Review P1: if the driver backs out mid-build, the started server is closed, never leaked.
        val built = PairingLaunch.build<Pair<Pairing?, String?>>(Dispatchers.IO, discard = { it.first?.server?.close() }) build@{
            val ip = lanIpv4(context) ?: return@build null to "This unit is not on Wi-Fi, so the phone cannot reach it. Join the phone's hotspot, then open this again."
            runCatching {
                val secret = ByteArray(PairingCrypto.SECRET_BYTES).also { SecureRandom().nextBytes(it) }
                val sink = AndroidHandoverSink(context, store)
                val session = PairingSession(PairingCrypto.generateKeyPair(), secret, sink, SystemClock::elapsedRealtime)
                val server = PairingServer(session, InetAddress.getByName(ip))
                server.start()
                Pairing(server, runCatching { qrBitmap(session.qrText(ip, server.port)) }.getOrNull()) to null
            }.getOrElse { null to "Could not open the pairing port on this unit. Try again in a moment." }
        }
        pairing = built.first
        problem = built.second
    }
    DisposableEffect(attempt) {
        onDispose { pairing?.server?.close() }
    }
    problem?.let {
        Text(it, style = RowTitle, color = AriaColors.TextPrimary)
        return
    }
    val p = pairing ?: run {
        Text("Starting...", style = RowSubtitle, color = AriaColors.TextDim)
        return
    }
    val stage by p.server.stage.collectAsState()
    DisposableEffect(stage::class) {
        // State names only: never a code, a key or a hand-over value.
        DriveLog.breadcrumb("car link pairing stage=${stage::class.simpleName}")
        onDispose {}
    }
    PairingStageView(
        stage, p.qr, store.lastNote,
        onCancel = { p.server.close(); onFinished() },
        onCode = { p.server.enterCode(it) },
        onFinished = onFinished,
    )
}

/**
 * What each pairing stage shows (split out so each stage renders in a test at 800x456; it emits
 * into the Settings pane's column).
 */
@Composable
internal fun PairingStageView(
    stage: PairingSession.Stage,
    qr: Bitmap?,
    note: String?,
    onCancel: () -> Unit,
    onCode: (String) -> Unit,
    onFinished: () -> Unit,
) {
    when (val s = stage) {
        PairingSession.Stage.WaitingForPhone -> {
            Text("On your phone: Settings, Car link, Scan. Point it at this code.", style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(AriaDimens.s3))
            qr?.let { Image(it.asImageBitmap(), contentDescription = "Pairing code for the phone to scan", modifier = Modifier.size(220.dp)) }
            Spacer(Modifier.height(AriaDimens.s2))
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("CANCEL") }
        }
        is PairingSession.Stage.EnterCode -> CodePad(s.triesLeft, onSubmit = onCode)
        PairingSession.Stage.Receiving -> Text("Numbers match. Receiving from the phone...", style = RowTitle, color = AriaColors.TextPrimary)
        PairingSession.Stage.AwaitingPhone -> Text("Saved. Waiting for the phone to confirm...", style = RowTitle, color = AriaColors.TextPrimary)
        is PairingSession.Stage.Paired -> {
            Text(
                if (s.confirmed) "Paired with ${s.phoneName ?: "your phone"}."
                else "Paired, waiting for the phone. It completes the next time the phone sends location.",
                style = RowTitle, color = AriaColors.TextPrimary,
            )
            Text(
                listOfNotNull(
                    if (s.received.isEmpty()) "The phone sent nothing else." else "Received: ${s.received.joinToString()}.",
                    note,
                ).joinToString(" "),
                style = RowSubtitle, color = AriaColors.TextDim,
            )
            Spacer(Modifier.height(AriaDimens.s2))
            Button(onClick = onFinished, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("DONE") }
        }
        is PairingSession.Stage.Failed -> {
            Text(s.reason.words, style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(AriaDimens.s2))
            Button(onClick = onFinished, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("BACK") }
        }
    }
}

/** Six digits typed on an on-screen pad; nothing goes through the keyboard or the key log. */
@Composable
internal fun CodePad(
    triesLeft: Int,
    onSubmit: (String) -> Unit,
    onKeyPlaced: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
) {
    DigitPad(
        prompt = "Type the six numbers your phone shows.",
        minDigits = 6,
        maxDigits = 6,
        masked = false,
        feedback = if (triesLeft < PairingSession.MAX_CODE_TRIES) "That did not match. Tries left: $triesLeft." else null,
        resetKey = triesLeft,
        onSubmit = onSubmit,
        onKeyPlaced = onKeyPlaced,
    )
}

/**
 * A number pad on the unit's own screen (nothing through the keyboard or the key log): the pairing
 * digits and the owner PIN. [masked] shows dots, never the digits. [extra] sits under the prompt.
 */
@Composable
internal fun DigitPad(
    prompt: String,
    minDigits: Int,
    maxDigits: Int,
    masked: Boolean,
    feedback: String?,
    resetKey: Any?,
    onSubmit: (String) -> Unit,
    onKeyPlaced: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    extra: @Composable () -> Unit = {},
) {
    var digits by remember(resetKey) { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s4)) {
        Column(Modifier.width(260.dp)) {
            Text(prompt, style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(AriaDimens.s2))
            val shown = if (masked) "\u2022".repeat(digits.length).padEnd(maxDigits, '_') else digits.padEnd(maxDigits, '_')
            Text(
                if (maxDigits == 6 && !masked) shown.chunked(3).joinToString(" ") else shown,
                style = AriaType.readout.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                color = AriaColors.TextPrimary,
            )
            feedback?.let { Text(it, style = RowSubtitle, color = AriaColors.accentText(AriaColors.Amber)) }
            extra()
        }
        // Sized from the pane's visible height, not the scroll column's unbounded one: the OK key
        // must be on screen without scrolling (recheck render test at 800x456).
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = minOf(320.dp, LocalSectionViewportHeight.current))) {
            val edge = (PairingPad.keyEdge(maxWidth.value, maxHeight.value) ?: AriaDimens.driveTouchMin.value).dp
            Column(verticalArrangement = Arrangement.spacedBy(PairingPad.GAP_DP.dp)) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("DEL", "0", "OK")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PairingPad.GAP_DP.dp)) {
                        row.forEach { k ->
                            PadKey(k, edge, onKeyPlaced) {
                                when (k) {
                                    "DEL" -> digits = digits.dropLast(1)
                                    "OK" -> if (digits.length in minDigits..maxDigits) onSubmit(digits).also { if (masked) digits = "" }
                                    else -> if (digits.length < maxDigits) digits += k
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asks for the owner PIN (2026-11-20) before a guarded action. Never while driving: the caller
 * checks [OwnerPinGate] first. The check (PBKDF2) runs off the main thread.
 */
@Composable
internal fun OwnerPinPrompt(pin: OwnerPin, prompt: String, onOk: () -> Unit, onCancel: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableIntStateOf(0) }
    DigitPad(
        prompt = prompt,
        minDigits = OwnerPin.MIN_DIGITS,
        maxDigits = OwnerPin.MAX_DIGITS,
        masked = true,
        feedback = feedback,
        resetKey = tries,
        onSubmit = { typed ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { pin.check(typed) }
                if (result == OwnerPin.Check.Ok) onOk() else {
                    feedback = OwnerPin.words(result, System.currentTimeMillis())
                    tries++
                }
            }
        },
        extra = {
            onCancel?.let {
                Spacer(Modifier.height(AriaDimens.s2))
                OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("CANCEL") }
            }
        },
    )
}

/** Sets the owner PIN: typed twice on the pad. Keystore failure is said in words; nothing is set. */
@Composable
internal fun OwnerPinSetup(pin: OwnerPin, onDone: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    DigitPad(
        prompt = if (first == null) "Choose an owner PIN, 4 to 6 numbers." else "Type the same PIN again.",
        minDigits = OwnerPin.MIN_DIGITS,
        maxDigits = OwnerPin.MAX_DIGITS,
        masked = true,
        feedback = feedback,
        resetKey = round,
        onSubmit = { typed ->
            val chosen = first
            if (chosen == null) {
                first = typed
                feedback = null
            } else if (chosen != typed) {
                first = null
                feedback = "The two PINs did not match. Start again."
            } else {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { pin.set(typed) }
                    if (ok) onDone() else {
                        first = null
                        feedback = "This unit's keystore is not working, so no PIN was set."
                    }
                }
            }
            round++
        },
        extra = {
            Spacer(Modifier.height(AriaDimens.s2))
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("NOT NOW") }
        },
    )
}

@Composable
private fun PadKey(
    label: String,
    edge: androidx.compose.ui.unit.Dp,
    onPlaced: (String, androidx.compose.ui.geometry.Rect) -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(edge)
            .onGloballyPositioned { onPlaced(label, it.boundsInWindow()) }
            .clip(shape)
            .background(AriaColors.Panel)
            .border(1.dp, AriaColors.PanelBorder, shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AriaType.readout.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = AriaColors.TextPrimary)
    }
}

/** This unit's IPv4 on Wi-Fi (the phone's hotspot), or null when it is not on Wi-Fi. */
private fun lanIpv4(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    for (network in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        val address = cm.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        if (address != null) return address.hostAddress
    }
    return null
}

private fun qrBitmap(text: String, size: Int = 440): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
    return bmp
}
