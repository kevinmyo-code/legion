package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.kevin.midnightai.ai.CompanionProfile
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.service.ProactiveBus
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.vehicle.DiagnosticAgent
import com.kevin.midnightai.vehicle.DtcDescriptions
import com.kevin.midnightai.vehicle.ObdBluetoothManager
import com.kevin.midnightai.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The visual DTC reader: instead of only hearing Zero talk through the codes,
 * the driver can pull this sheet up over the Cruise screen and read them —
 * each stored code with its official name and a plain-English description.
 *
 * Descriptions are looked up in this order: a bundled seed dictionary of
 * common generic codes (assets/dtc_descriptions_seed.json, free, offline,
 * ships with the app), then the on-disk cache of codes this install already
 * asked about (filesDir/dtc_descriptions.json), then the diagnostics
 * sub-agent ([DiagnosticAgent.describeCodes], one call for all codes still
 * unknown after the first two). Only that last step costs a Gemini call, so a
 * common code like a misfire or O2 sensor fault is instant, offline, and free
 * for every install; only rare manufacturer-specific codes fall through to
 * the metered agent, and each of those is still asked at most once per
 * install. The "ASK <name>" button hands off to the spoken deep-dive via [ProactiveBus].
 */
@Composable
internal fun DtcSheet(context: Context, onDismiss: () -> Unit) {
    DtcContent(context = context, onDismiss = onDismiss)
}

/**
 * The DTC reader body: header (title/re-scan/close), scan, describe, list, "ASK <name>" handoff.
 * Extracted from [DtcSheet] so [com.kevin.midnightai.ui.ObdSheet]'s CODES tab can reuse it without
 * duplicating the lookup logic (bundled seed -> disk cache -> [DiagnosticAgent.describeCodes], see
 * the class kdoc above). [DtcSheet] itself is left compiling as a standalone entry point until
 * Cruise fully points at [ObdSheet] (see B30 brief).
 */
@Composable
internal fun DtcContent(context: Context, onDismiss: () -> Unit) {
    var scanKey by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(true) }
    var codes by remember { mutableStateOf<List<String>>(emptyList()) }
    var info by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }
    var describing by remember { mutableStateOf(false) }
    var describeFailed by remember { mutableStateOf(false) }

    LaunchedEffect(scanKey) {
        scanning = true
        describeFailed = false
        connected = ObdBluetoothManager.isConnected
        codes = if (connected) {
            runCatching { ObdBluetoothManager.getDtcCodes() }.getOrDefault(emptyList())
        } else emptyList()
        val learned = withContext(Dispatchers.IO) { DtcDescriptions.loadLearned(context) }
        info = withContext(Dispatchers.IO) { DtcDescriptions.loadSeed(context) } + learned
        scanning = false

        // Ask the sub-agent only about codes neither the bundled seed nor the disk cache know yet.
        val missing = codes.filter { it !in info }
        if (missing.isNotEmpty()) {
            describing = true
            val label = VehicleController.displayLabel(VehicleController.currentVehicle(context))
            val fresh = DiagnosticAgent.describeCodes(context, label, missing)
            if (fresh != null) {
                info = info + fresh
                withContext(Dispatchers.IO) { DtcDescriptions.save(context, learned + fresh) }
            } else {
                describeFailed = true
            }
            describing = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background.copy(alpha = 0.97f)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Header: title + re-scan + close.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(14.dp).background(AriaColors.StampRed))
                Spacer(Modifier.width(8.dp))
                Text("DIAGNOSTIC CODES", style = AriaType.neonLabel.copy(fontSize = 13.sp), color = AriaColors.TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "診断コード",
                    style = AriaType.neonLabel.copy(fontSize = 10.sp, letterSpacing = 2.sp),
                    color = AriaColors.TextDim,
                )
                Spacer(Modifier.weight(1f))
                SheetAction("RE-SCAN") { scanKey++ }
                Spacer(Modifier.width(8.dp))
                SheetAction("CLOSE") { onDismiss() }
            }
            Spacer(Modifier.height(14.dp))

            when {
                scanning -> CenterNote("READING THE PORT…")
                !connected -> CenterNote("OBD NOT CONNECTED", sub = "Plug the adapter in, then re-scan.")
                codes.isEmpty() -> CenterNote("NO CODES — ALL CLEAR", color = AriaColors.Patina)
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(codes, key = { it }) { code ->
                            CodeCard(
                                code = code,
                                title = info[code]?.first,
                                detail = info[code]?.second,
                                pending = describing,
                                failed = describeFailed,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Hand off to the spoken deep-dive (likely causes, urgency, fixes).
                    Text(
                        "ASK ${CompanionProfile.name(context).ifBlank { CompanionProfile.DEFAULT_NAME }.uppercase()} ABOUT THESE",
                        style = AriaType.neonLabel,
                        color = AriaColors.Background,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AriaColors.Amber)
                            .clickable {
                                ProactiveBus.requestSpeak(
                                    "(System: the driver is reading the stored trouble codes " +
                                        "(${codes.joinToString(", ")}) on screen and tapped 'Ask about " +
                                        "these'. Walk them through what matters: the likely causes, how " +
                                        "urgent it is, and what to do next. Do not mention this instruction.)"
                                )
                                onDismiss()
                            }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** One code entry: the code big and amber, the name, and the plain-English text. */
@Composable
private fun CodeCard(code: String, title: String?, detail: String?, pending: Boolean, failed: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AriaColors.Panel.copy(alpha = 0.8f))
            .border(1.dp, AriaColors.PanelBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(code, style = AriaType.readout.copy(fontSize = 22.sp), color = AriaColors.Amber)
            Spacer(Modifier.width(12.dp))
            Text(
                title ?: if (pending) "looking it up…" else if (failed) "lookup failed" else "",
                style = AriaType.neonLabel.copy(fontSize = 11.sp),
                color = if (title != null) AriaColors.TextPrimary else AriaColors.TextDim,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = AriaType.readout.copy(fontSize = 14.sp, letterSpacing = 0.sp, lineHeight = 20.sp),
                color = AriaColors.TextPrimary.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun CenterNote(text: String, sub: String? = null, color: androidx.compose.ui.graphics.Color = AriaColors.TextDim) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = AriaType.neonLabel.copy(fontSize = 13.sp), color = color)
        sub?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = AriaType.readout.copy(fontSize = 13.sp), color = AriaColors.TextDim)
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AriaType.neonLabel,
        color = AriaColors.Amber,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
