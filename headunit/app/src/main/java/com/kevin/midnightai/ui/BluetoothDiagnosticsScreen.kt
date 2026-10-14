package com.kevin.midnightai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.bluetooth.BluetoothProbe
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay

/**
 * Plain-text dump of [BluetoothProbe], refreshed live, meant to be
 * photographed off the head unit (ADB is blocked there). Settles which
 * Bluetooth stack owns the phone link before the two-phone switcher is
 * designed - see [BluetoothProbe]'s kdoc. Read-only: nothing on this screen
 * changes any connection.
 */
@Composable
internal fun BluetoothDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val probe = remember { BluetoothProbe(context) }
    DisposableEffect(probe) {
        probe.open()
        onDispose { probe.close() }
    }
    var lines by remember { mutableStateOf(probe.report()) }
    // Live, because the useful read is watching the lines change while a phone
    // connects, starts music, or gets knocked off by the other one.
    LaunchedEffect(probe) {
        while (true) {
            lines = probe.report()
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "‹ BACK",
                style = AriaType.neonLabel,
                color = AriaColors.Amber,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 8.dp, horizontal = 4.dp),
            )
            Text(
                "BLUETOOTH DIAGNOSTICS",
                style = AriaType.stamp.copy(fontSize = 16.sp),
                color = AriaColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(52.dp))
        }
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            lines.forEach { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (line.startsWith("==")) AriaColors.Amber else AriaColors.TextPrimary,
                )
            }
        }
    }
}
