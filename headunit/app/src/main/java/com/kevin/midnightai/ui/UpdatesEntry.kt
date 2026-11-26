package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType
import com.kevin.midnightai.update.DrivingState
import com.kevin.midnightai.update.UpdateGraph
import com.kevin.midnightai.update.UpdateRules
import com.kevin.midnightai.update.UpdateState
import com.kevin.midnightai.update.WhatsNew
import com.kevin.midnightai.update.WhatsNewSeen
import com.kevin.midnightai.update.WhatsNewState
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The head unit's update surfaces (alfred-ota ticket 08): the Cruise chip, "UPDATE READY · TAP
 * TO INSTALL", and Settings, System, Updates. Both call the one [UpdateGraph] controller
 * (ADR 0035). No animation (frame-clock rule), Aria tokens only, 56dp targets.
 */

/** What the Cruise chip says, or null when it is not shown. */
internal data class UpdatePrompt(val title: String, val line: String, val tappable: Boolean, val whatsNew: Boolean = false)

/** Bumped when the driver has seen the what's-new chip, so [rememberUpdatePrompt] re-reads the stored version. */
private val whatsNewSeenTick = mutableIntStateOf(0)

/**
 * The chip's content for the current update state. Never while the car is moving
 * ([UpdateRules.mayPromptNow]), and only for the states a tap can act on or that a tap started.
 * Re-reads the driving state every 10 s, the cadence Cruise's clock already ticks at.
 */
@Composable
internal fun rememberUpdatePrompt(): UpdatePrompt? {
    val context = LocalContext.current
    val state by remember { UpdateGraph.controller(context).state }.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = System.currentTimeMillis()
        }
    }
    if (!UpdateRules.mayPromptNow(DrivingState.isDriving(now))) return null
    return when (val s = state) {
        is UpdateState.Ready -> UpdatePrompt(
            title = "UPDATE READY · TAP TO INSTALL",
            line = s.note ?: UpdateRules.describe(s.release),
            tappable = true,
        )
        is UpdateState.Waiting -> UpdatePrompt("UPDATE INSTALLS AFTER THIS CHAT", UpdateRules.describe(s.release), false)
        is UpdateState.Installing -> UpdatePrompt("INSTALLING UPDATE", "${UpdateRules.APP} closes and comes back on its own.", false)
        else -> whatsNewPrompt(context, whatsNewSeenTick.intValue)
    }
}

/** Once per update: the chip that says the app just changed and where the notes are. */
private fun whatsNewPrompt(context: Context, @Suppress("UNUSED_PARAMETER") tick: Int): UpdatePrompt? {
    val installed = UpdateGraph.platform(context).installed()
    if (!WhatsNew.due(installed.versionCode, WhatsNewSeen(context).code)) return null
    return UpdatePrompt(
        title = "UPDATED · TAP FOR WHAT'S NEW",
        line = "${UpdateRules.APP} ${installed.versionName}. Later: Settings, System, What's new.",
        tappable = true,
        whatsNew = true,
    )
}

/** The Cruise chip, in the console column above the telemetry chips (the setup prompt's slot). */
@Composable
internal fun UpdateChip(prompt: UpdatePrompt, modifier: Modifier = Modifier, onWhatsNew: () -> Unit = {}) {
    val context = LocalContext.current
    var said by remember(prompt) { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .heightIn(min = AriaDimens.driveTouchMin)
            .background(AriaColors.Panel.copy(alpha = 0.88f), shape)
            .border(1.dp, AriaColors.Amber.copy(alpha = 0.6f), shape)
            .then(
                if (prompt.tappable) {
                    Modifier.clickable(onClickLabel = if (prompt.whatsNew) "Open settings" else "Install update") {
                        if (prompt.whatsNew) {
                            WhatsNewSeen(context).code = UpdateGraph.platform(context).installed().versionCode
                            whatsNewSeenTick.intValue++
                            onWhatsNew()
                            return@clickable
                        }
                        UpdateGraph.launch {
                            val outcome = UpdateGraph.tapInstall(context)
                            if (!outcome.started) said = outcome.sentence
                        }
                    }
                } else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            prompt.title,
            style = AriaType.neonLabel.copy(fontSize = 12.sp, letterSpacing = 1.sp),
            color = AriaColors.accentText(AriaColors.Amber),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            said ?: prompt.line,
            style = AriaType.neonLabel.copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
            color = AriaColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Settings, System, Updates: the full hands path. */
@Composable
internal fun SystemUpdatesSection(context: Context, onBack: () -> Unit) {
    val controller = remember { UpdateGraph.controller(context) }
    val platform = remember { UpdateGraph.platform(context) }
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    var installed by remember { mutableStateOf(platform.installed()) }
    val installedAbis = remember { platform.installedAbis() }
    var mayInstall by remember { mutableStateOf(platform.mayInstallPackages()) }
    var allowMetered by remember { mutableStateOf(controller.allowMetered) }
    var said by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    // Coming back from Android's "Install unknown apps" screen recomposes this leaf: re-read.
    LaunchedEffect(state) {
        mayInstall = platform.mayInstallPackages()
        installed = platform.installed()
    }

    SectionScaffold("Updates", onBack) {
        Text("This unit", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "${UpdateRules.APP} ${installed.versionName}, version code ${installed.versionCode}. " +
                "Built for ${installedAbis.sorted().joinToString().ifEmpty { "any ABI" }}; this unit runs " +
                "${platform.deviceAbis.joinToString()}. A release must be the same ABI's APK.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(AriaDimens.s3))

        Text("Newest release", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(stateLine(state), style = RowSubtitle, color = stateColor(state))
        said?.let {
            Spacer(Modifier.height(AriaDimens.s1))
            Text(it, style = RowSubtitle, color = AriaColors.TextPrimary)
        }
        Spacer(Modifier.height(AriaDimens.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(AriaDimens.s2)) {
            OutlinedButton(
                onClick = {
                    if (working) return@OutlinedButton
                    working = true
                    said = null
                    scope.launch {
                        controller.check()
                        working = false
                    }
                },
                enabled = !working,
                modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
            ) { Text(if (working) "CHECKING..." else "CHECK NOW") }
            Button(
                onClick = {
                    scope.launch {
                        val outcome = UpdateGraph.tapInstall(context)
                        if (!outcome.started) said = outcome.sentence
                    }
                },
                enabled = state is UpdateState.Ready,
                modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
            ) { Text("INSTALL") }
            // Review round edd3a320, item 8: a large update over the hotspot waits for this tap.
            if ((state as? UpdateState.Available)?.needsTap == true) {
                Button(
                    onClick = {
                        if (working) return@Button
                        working = true
                        scope.launch {
                            controller.approveDownload()
                            working = false
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
                ) { Text("DOWNLOAD") }
            }
        }
        Spacer(Modifier.height(AriaDimens.s4))

        Text("Allow installs", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            if (mayInstall) {
                "Allowed. ${UpdateRules.APP} may install its own updates, and only after your tap."
            } else {
                "Not allowed yet. Android asks once: switch on \"Allow from this source\" for ${UpdateRules.APP}."
            },
            style = RowSubtitle,
            color = if (mayInstall) AriaColors.TextDim else AriaColors.accentText(AriaColors.Amber),
        )
        Spacer(Modifier.height(AriaDimens.s2))
        OutlinedButton(
            onClick = { UpdateGraph.openInstallPermission(context) },
            modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
        ) { Text("OPEN ANDROID SETTING") }
        Spacer(Modifier.height(AriaDimens.s4))

        Text("Download over the hotspot", style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(AriaDimens.s1))
        Text(
            "On: updates download on any network, including the phone's hotspot, which uses the " +
                "phone's data (an update was about 17 MB in November 2026). Off: Wi-Fi without a meter only.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(AriaDimens.s1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = allowMetered,
                onCheckedChange = { allowMetered = it; controller.allowMetered = it },
            )
            Spacer(Modifier.width(AriaDimens.s2))
            Text(if (allowMetered) "Any network" else "Wi-Fi only", style = RowTitle, color = AriaColors.TextPrimary)
        }
        Spacer(Modifier.height(AriaDimens.s3))
        Text(
            "Nothing installs while the car is moving, and never without your tap. Every download is " +
                "checked against the release's checksum and this app's signing key first.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
    }
}

private fun stateLine(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Not checked yet since this start. It checks on its own shortly after the unit starts."
    UpdateState.Checking -> "Checking..."
    UpdateState.DebugBuild -> "This is a debug build (version code 1), and a debug build never updates itself."
    is UpdateState.UpToDate -> "Up to date."
    is UpdateState.Available -> "${UpdateRules.describe(state.release)}. ${state.why}"
    is UpdateState.Downloading -> "Downloading ${UpdateRules.describe(state.release)}: " +
        "${mb(state.bytes)} of ${mb(state.release.sizeBytes)}."
    is UpdateState.Ready -> "${UpdateRules.describe(state.release)}. Downloaded and checked, ready to install." +
        (state.note?.let { " $it" } ?: "")
    is UpdateState.Waiting -> "${UpdateRules.describe(state.release)}. Installs when this conversation ends."
    is UpdateState.Installing -> "Installing ${UpdateRules.describe(state.release)}."
    is UpdateState.Failed -> state.sentence
}

@Composable
private fun stateColor(state: UpdateState) = when (state) {
    is UpdateState.Failed -> AriaColors.ScanRed
    is UpdateState.Ready -> AriaColors.accentText(AriaColors.Amber)
    else -> AriaColors.TextDim
}

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

/** Settings, System, What's new: every release's notes, newest first, the installed one marked. */
@Composable
internal fun SystemWhatsNewSection(context: Context, onBack: () -> Unit) {
    val platform = remember { UpdateGraph.platform(context) }
    val installedCode = remember { platform.installed().versionCode }
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState<WhatsNewState>(WhatsNewState.Loading, reload) {
        value = WhatsNewState.Loading
        value = WhatsNew.load(UpdateGraph.history(context), installedCode)
    }
    // Opening this section is seeing the notes, so the Cruise chip stops.
    LaunchedEffect(Unit) { WhatsNewSeen(context).code = installedCode }

    SectionScaffold("What's new", onBack) {
        when (val s = state) {
            WhatsNewState.Loading -> Text("Loading the release notes.", style = RowSubtitle, color = AriaColors.TextDim)
            is WhatsNewState.Unavailable -> {
                Text(s.sentence, style = RowSubtitle, color = AriaColors.ScanRed)
                Spacer(Modifier.height(AriaDimens.s2))
                OutlinedButton(
                    onClick = { reload++ },
                    modifier = Modifier.heightIn(min = AriaDimens.driveTouchMin),
                ) { Text("TRY AGAIN") }
            }
            is WhatsNewState.Loaded -> {
                if (s.releases.isEmpty()) Text(WhatsNew.NO_RELEASES, style = RowSubtitle, color = AriaColors.TextDim)
                s.releases.forEach { note ->
                    val installed = note.versionCode == s.installedCode
                    Text(
                        if (installed) "${note.versionName} (installed)" else note.versionName,
                        style = RowTitle,
                        color = AriaColors.TextPrimary,
                    )
                    Text(note.publishedAt.take(DATE_CHARS), style = RowSubtitle, color = AriaColors.TextDim)
                    Text(WhatsNew.notesLine(note), style = RowSubtitle, color = AriaColors.TextPrimary)
                    Spacer(Modifier.height(AriaDimens.s3))
                }
            }
        }
    }
}

private const val DATE_CHARS = 10 // "2026-11-26" off an ISO timestamp
