package com.kevin.midnightai.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kevin.midnightai.diag.DriveLog
import com.kevin.midnightai.link.AndroidHandoverSink
import com.kevin.midnightai.link.CarLink
import com.kevin.midnightai.link.LinkKeyStore
import com.kevin.midnightai.link.PairingCrypto
import com.kevin.midnightai.link.PairingServer
import com.kevin.midnightai.link.PairingSession
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType
import java.net.Inet4Address
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Settings > Phones & audio > Car link (HU-1 T3): pair this unit with the driver's phone once.
 * The unit shows a QR; the phone scans it, both show six digits, the driver checks they match and
 * taps here; the phone then hands over the engine sign-in (and, for now, the Gemini key). Nothing
 * on this screen takes typed input, and nothing secret is ever drawn or logged: only the QR (which
 * dies with the session) and the check digits.
 */
@Composable
internal fun CarLinkSection(context: Context, onBack: () -> Unit) {
    val store = remember { LinkKeyStore(context) }
    var paired by remember { mutableStateOf(store.linkKey() != null) }
    var attempt by remember { mutableIntStateOf(0) }
    SectionScaffold("Car link", onBack) {
        if (paired && attempt == 0) {
            Text(
                "Paired with ${store.phoneName() ?: "your phone"}. Location and sign-in come from it.",
                style = RowTitle, color = AriaColors.TextPrimary,
            )
            Spacer(Modifier.height(AriaDimens.s3))
            Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
                OutlinedButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) {
                    Text("PAIR AGAIN")
                }
                OutlinedButton(
                    onClick = {
                        store.forget()
                        CarLink.keys = store
                        DriveLog.breadcrumb("car link forgotten")
                        paired = false
                    },
                    modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
                ) { Text("FORGET PHONE") }
            }
            Text(
                "Forget removes the link key and the map and hotspot details the phone sent. The engine " +
                    "sign-in stays until you sign out under Engine.",
                style = RowSubtitle, color = AriaColors.TextDim,
            )
        } else {
            PairingPane(context, store, attempt) { done ->
                paired = done
                if (done) {
                    attempt = 0
                } else {
                    attempt++
                }
            }
        }
    }
}

@Composable
private fun PairingPane(context: Context, store: LinkKeyStore, attempt: Int, onFinished: (Boolean) -> Unit) {
    val ip = remember { lanIpv4(context) }
    if (ip == null) {
        Text(
            "This unit is not on Wi-Fi, so the phone cannot reach it. Join the phone's hotspot, then open this again.",
            style = RowTitle, color = AriaColors.TextPrimary,
        )
        return
    }
    val pairing = remember(attempt) {
        val secret = ByteArray(PairingCrypto.SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        val session = PairingSession(PairingCrypto.generateKeyPair(), secret, AndroidHandoverSink(context, store), System::currentTimeMillis)
        // Bound to the Wi-Fi address only, never every interface.
        val server = runCatching { PairingServer(session, java.net.InetAddress.getByName(ip)) }.getOrNull()
        val qr = server?.let { runCatching { qrBitmap(session.qrText(ip, it.port)) }.getOrNull() }
        Triple(session, server, qr)
    }
    val (_, maybeServer, qr) = pairing
    val server = maybeServer ?: run {
        Text("Could not open the pairing port on this unit. Try again in a moment.", style = RowTitle, color = AriaColors.TextPrimary)
        return
    }
    DisposableEffect(pairing) {
        val worker = thread(name = "car-link-pairing", isDaemon = true) { server.run() }
        DriveLog.breadcrumb("car link pairing opened")
        onDispose {
            server.cancel()
            server.close()
            worker.interrupt()
        }
    }
    val stage by server.stage.collectAsState()
    DisposableEffect(stage::class) {
        // State names only: never a code, a key or a hand-over value.
        DriveLog.breadcrumb("car link pairing stage=${stage::class.simpleName}")
        onDispose {}
    }
    when (val s = stage) {
        PairingSession.Stage.WaitingForPhone -> {
            Text("On your phone: Settings, Car link, Scan. Point it at this code.", style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(AriaDimens.s3))
            qr?.let { Image(it.asImageBitmap(), contentDescription = "Pairing code for the phone to scan", modifier = Modifier.size(240.dp)) }
            Spacer(Modifier.height(AriaDimens.s2))
            OutlinedButton(onClick = { server.cancel() }, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("CANCEL") }
        }
        is PairingSession.Stage.CompareCodes -> {
            Text("Does your phone show the same number?", style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(AriaDimens.s2))
            Text(
                s.code.chunked(3).joinToString(" "),
                style = AriaType.readout.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                color = AriaColors.TextPrimary,
            )
            Spacer(Modifier.height(AriaDimens.s3))
            if (s.unitConfirmed) {
                Text("Confirmed here. Now confirm on the phone.", style = RowSubtitle, color = AriaColors.TextDim)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
                    Button(onClick = { server.confirm() }, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("SAME NUMBER") }
                    OutlinedButton(onClick = { server.mismatch() }, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("DIFFERENT") }
                }
            }
        }
        is PairingSession.Stage.Paired -> {
            Text("Paired with ${s.phoneName ?: "your phone"}.", style = RowTitle, color = AriaColors.TextPrimary)
            Text(
                if (s.received.isEmpty()) "The phone sent nothing else." else "Received: ${s.received.joinToString()}.",
                style = RowSubtitle, color = AriaColors.TextDim,
            )
            Spacer(Modifier.height(AriaDimens.s2))
            Button(onClick = { onFinished(true) }, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("DONE") }
        }
        is PairingSession.Stage.Failed -> {
            Text(s.reason.words, style = RowTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.height(AriaDimens.s2))
            Button(onClick = { onFinished(false) }, modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin)) { Text("TRY AGAIN") }
        }
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

private fun qrBitmap(text: String, size: Int = 480): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
    return bmp
}
