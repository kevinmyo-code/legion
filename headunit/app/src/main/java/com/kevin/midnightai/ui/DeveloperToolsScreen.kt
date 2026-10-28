package com.kevin.midnightai.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.diag.DeveloperTools
import com.kevin.midnightai.diag.DeveloperTools.IntentSpec
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/*
 * Settings > System > Developer tools. All decisions and wording live in the pure
 * [DeveloperTools] (unit-tested); this file is the thin Android glue and the leaf's layout.
 * Hands-only by design: none of this has a voice tool.
 */

private const val NOTE_SETTINGS = "settings"
private const val NOTE_DEV = "dev"
private const val NOTE_ABOUT = "about"
private const val NOTE_WIRELESS = "wireless"

private data class RootResult(val message: String, val detail: String)

@Composable
internal fun DeveloperToolsSection(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = Build.VERSION.SDK_INT
    var status by remember { mutableStateOf<DeveloperTools.StatusInputs?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val notes = remember { mutableStateMapOf<String, String>() }
    var copied by remember { mutableStateOf(false) }
    var rootArmed by remember { mutableStateOf(false) }
    var rootRunning by remember { mutableStateOf(false) }
    var rootResult by remember { mutableStateOf<RootResult?>(null) }

    LaunchedEffect(refreshTick) {
        status = withContext(Dispatchers.IO) { DeveloperToolsAndroid.readStatus(context) }
    }

    fun launch(key: String, spec: IntentSpec) {
        if (DeveloperToolsAndroid.start(context, spec)) notes.remove(key) else notes[key] = DeveloperTools.NO_SCREEN
    }

    fun withNote(subtitle: String, key: String) = notes[key]?.let { "$subtitle\n\n$it" } ?: subtitle

    SectionScaffold("Developer tools", onBack) {
        Text(
            "Android's own screens this unit's skin hides, and what ADB looks like right now. " +
                "Nothing here changes a setting except the root button, which asks first.",
            style = RowSubtitle,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(AriaDimens.s3))

        val rows = buildList {
            add(SettingsRowSpec(
                "Open Android Settings",
                withNote("The full Android Settings app.", NOTE_SETTINGS),
                RowAction.Button("OPEN", ButtonWeight.QUIET) { launch(NOTE_SETTINGS, DeveloperTools.androidSettings) },
            ))
            add(SettingsRowSpec(
                "Open Developer options",
                withNote(
                    "If the screen is missing or nothing opens, tap Build number seven times in About " +
                        "device (Open About device, below), then come back here.",
                    NOTE_DEV,
                ),
                RowAction.Button("OPEN", ButtonWeight.QUIET) { launch(NOTE_DEV, DeveloperTools.developerOptions) },
            ))
            add(SettingsRowSpec(
                "Open About device",
                withNote("Build number is on this screen. Tapping it seven times turns on Developer options.", NOTE_ABOUT),
                RowAction.Button("OPEN", ButtonWeight.QUIET) { launch(NOTE_ABOUT, DeveloperTools.aboutDevice) },
            ))
            if (DeveloperTools.showWirelessDebuggingRow(api)) {
                add(SettingsRowSpec(
                    "Open Wireless debugging",
                    withNote("Android 11 and later: pair a laptop with a code, no cable.", NOTE_WIRELESS),
                    RowAction.Button("OPEN", ButtonWeight.QUIET) {
                        val index = DeveloperTools.wirelessDebuggingChain()
                            .indexOfFirst { DeveloperToolsAndroid.start(context, it) }
                            .takeIf { it >= 0 }
                        notes[NOTE_WIRELESS] = DeveloperTools.wirelessDebuggingNote(index)
                    },
                ))
            }
        }
        rows.forEach {
            SettingsRowCard(it)
            Spacer(Modifier.height(AriaDimens.s2))
        }

        val s = status
        SettingsRowCard(SettingsRowSpec(
            "ADB status",
            s?.let { DeveloperTools.statusLines(it).joinToString("\n") } ?: "Reading...",
            RowAction.Button("REFRESH", ButtonWeight.QUIET) { copied = false; refreshTick++ },
        ))
        Spacer(Modifier.height(AriaDimens.s2))
        ConnectCommandCard(
            command = DeveloperTools.connectCommand(s?.ipv4),
            copied = copied,
            onCopy = {
                DeveloperToolsAndroid.copy(context, DeveloperTools.connectCommand(s?.ipv4))
                copied = true
            },
        )
        Spacer(Modifier.height(AriaDimens.s2))

        val rootSubtitle = when {
            rootRunning -> "Running as root, up to ${DeveloperTools.ROOT_TIMEOUT_MS / 1000} s per command..."
            rootArmed -> "This changes system settings on the unit: it restarts ADB listening on port " +
                "${DeveloperTools.ADB_TCP_PORT}. Tap RUN AS ROOT to go ahead."
            rootResult != null -> "${rootResult!!.message}\n${rootResult!!.detail}"
            else -> "Works only on a rooted unit. Sets ADB to listen on port ${DeveloperTools.ADB_TCP_PORT}, " +
                "then checks the port."
        }
        SettingsRowCard(SettingsRowSpec(
            "Try to enable ADB over Wi-Fi (needs root)",
            rootSubtitle,
            RowAction.Button(
                when {
                    rootRunning -> "RUNNING"
                    rootArmed -> "RUN AS ROOT"
                    else -> "TRY"
                },
                if (rootArmed) ButtonWeight.HOT else ButtonWeight.PRIMARY,
            ) {
                when {
                    rootRunning -> Unit
                    !rootArmed -> { rootArmed = true; rootResult = null }
                    else -> {
                        rootArmed = false
                        rootRunning = true
                        scope.launch {
                            rootResult = withContext(Dispatchers.IO) { DeveloperToolsAndroid.tryRootEnable(context, api) }
                            rootRunning = false
                            refreshTick++
                        }
                    }
                }
            },
        ))
        Spacer(Modifier.height(AriaDimens.s2))
        Text(DeveloperTools.EXPOSURE_NOTE, style = RowSubtitle, color = AriaColors.TextDim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectCommandCard(command: String, copied: Boolean, onCopy: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(AriaColors.Panel)
            .border(AriaDimens.hairline, AriaColors.PanelBorder, shape)
            .combinedClickable(onClick = {}, onLongClick = onCopy)
            .padding(horizontal = AriaDimens.s3, vertical = AriaDimens.s2),
    ) {
        Text("On a laptop on the same network, type:", style = RowSubtitle, color = AriaColors.TextDim)
        Spacer(Modifier.height(2.dp))
        Text(command, style = RowTitle, color = AriaColors.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(if (copied) "Copied" else "Long-press to copy", style = RowSubtitle, color = AriaColors.TextDim)
    }
}

/** The Android side. Every method is a direct platform call; the decisions are in [DeveloperTools]. */
private object DeveloperToolsAndroid {

    fun toIntent(spec: IntentSpec): Intent {
        val intent = if (spec.action != null) Intent(spec.action) else Intent()
        if (spec.componentPackage != null && spec.componentClass != null) {
            intent.setClassName(spec.componentPackage, spec.componentClass)
        }
        spec.fragmentArgKey?.let { key ->
            intent.putExtra(DeveloperTools.EXTRA_FRAGMENT_ARG_KEY, key)
            intent.putExtra(
                DeveloperTools.EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                Bundle().apply { putString(DeveloperTools.EXTRA_FRAGMENT_ARG_KEY, key) },
            )
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * True when Android accepted the start. try/catch rather than resolveActivity: on API 30+
     * package visibility can hide the Settings app from resolveActivity while startActivity still
     * works, and a non-exported component throws SecurityException, not ActivityNotFoundException.
     */
    fun start(context: Context, spec: IntentSpec): Boolean = try {
        context.startActivity(toIntent(spec))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun readStatus(context: Context): DeveloperTools.StatusInputs {
        val cr = context.contentResolver
        val api = Build.VERSION.SDK_INT
        return DeveloperTools.StatusInputs(
            release = Build.VERSION.RELEASE ?: "?",
            apiLevel = api,
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            fingerprint = Build.FINGERPRINT ?: "",
            adbEnabled = Settings.Global.getInt(cr, DeveloperTools.ADB_ENABLED, -1),
            adbWifiEnabled = if (api >= DeveloperTools.API_WIRELESS_DEBUGGING) {
                Settings.Global.getInt(cr, DeveloperTools.ADB_WIFI_ENABLED, -1)
            } else -1,
            ipv4 = ipv4(context),
            port5555Open = probePort(),
        )
    }

    /** The first IPv4 on a Wi-Fi or Ethernet network, active network first. */
    fun ipv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = buildList {
            cm.activeNetwork?.let { add(it) }
            @Suppress("DEPRECATION")
            addAll(cm.allNetworks)
        }.distinct()
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue
            val address = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (address != null) return address.hostAddress
        }
        return null
    }

    /** Off the main thread only. */
    fun probePort(): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", DeveloperTools.ADB_TCP_PORT), DeveloperTools.PORT_PROBE_TIMEOUT_MS)
            true
        }
    } catch (e: IOException) {
        false
    }

    private class SuRun(val suExists: Boolean, val exitCode: Int?)

    /**
     * `su -c "<command>"` with a 5 s cap. minSdk is 24 and Process.waitFor(long, TimeUnit) is API 26,
     * so the cap is a poll on exitValue() plus destroy(). Output is drained on a daemon thread so a
     * chatty su cannot block on a full pipe.
     */
    private fun runSu(command: String): SuRun {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return SuRun(suExists = false, exitCode = null)
        }
        thread(isDaemon = true, name = "su-drain") {
            runCatching { process.inputStream.use { it.readBytes() } }
        }
        val deadline = SystemClock.elapsedRealtime() + DeveloperTools.ROOT_TIMEOUT_MS
        while (true) {
            try {
                return SuRun(suExists = true, exitCode = process.exitValue())
            } catch (e: IllegalThreadStateException) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    process.destroy()
                    return SuRun(suExists = true, exitCode = null)
                }
                Thread.sleep(50)
            }
        }
    }

    /** Off the main thread only. The port probe decides the verdict, never the exit code. */
    fun tryRootEnable(context: Context, api: Int): RootResult {
        var suExists = true
        var exitCode: Int? = 0
        for (command in DeveloperTools.rootCommands(api)) {
            val run = runSu(command)
            if (!run.suExists) { suExists = false; exitCode = null; break }
            if (run.exitCode != 0) { exitCode = run.exitCode; break }
        }
        // adbd takes a moment to come back after `start adbd`: probe a few times over ~3 s.
        var open = false
        if (suExists) {
            repeat(6) {
                if (!open) {
                    open = probePort()
                    if (!open) Thread.sleep(500)
                }
            }
        }
        val outcome = DeveloperTools.rootOutcome(suExists, exitCode, open)
        return RootResult(
            message = DeveloperTools.rootMessage(outcome, ipv4(context)),
            detail = DeveloperTools.rootDetail(suExists, exitCode),
        )
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("adb connect", text))
    }
}
