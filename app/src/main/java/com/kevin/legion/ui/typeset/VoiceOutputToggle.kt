package com.kevin.legion.ui.typeset

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.OutputChoice
import com.kevin.legion.service.turn.OutputToggle
import com.kevin.legion.service.turn.VoiceOutlet
import com.kevin.legion.service.turn.VoiceOutput
import com.kevin.legion.service.turn.VoiceOutputRule
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The orb with the SPEAKER / CALL toggle at the end of its row: the same height as the orb alone, so
 * [FitTiers] lays the page out exactly as before. Null [output] (a tablet: no earpiece) draws the orb only.
 */
@Composable
internal fun OrbWithOutput(output: OutputToggle?, onFlip: () -> Unit, orb: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        orb()
        if (output != null) OutputToggleButton(output, onFlip, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * Where Alfred's voice comes out, as an icon over a word: "Speaker" or "Call" (the phone held to the
 * ear). A tap flips it, mid-conversation too. While headphones, Bluetooth or a phone call decide the
 * route it says which and does not respond.
 */
@Composable
internal fun OutputToggleButton(toggle: OutputToggle, onFlip: () -> Unit, modifier: Modifier = Modifier) {
    val ink = if (toggle.enabled) SoftColors.text2 else SoftColors.text3
    val description = outputDescription(toggle)
    Column(
        modifier.widthIn(min = TapTarget).heightIn(min = TapTarget)
            .clickable(enabled = toggle.enabled, role = Role.Button, onClick = onFlip)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val icon = if (toggle.outlet == VoiceOutlet.EARPIECE) R.drawable.ms_smartphone else R.drawable.ms_nav_unmute
        MsIcon(icon, contentDescription = null, tint = ink, size = 20.dp)
        Text(toggle.label, style = TypesetType.small, color = ink, maxLines = 1)
    }
}

/** What TalkBack says: where the voice is now and what a tap does, or what holds the route. */
internal fun outputDescription(t: OutputToggle): String = when (t.outlet) {
    VoiceOutlet.LOUDSPEAKER -> "Alfred's voice on the speaker. Tap for call mode, phone at your ear"
    VoiceOutlet.EARPIECE -> "Alfred's voice on the earpiece, call mode. Tap for the speaker"
    VoiceOutlet.WIRED -> "Alfred's voice on headphones. Speaker or call mode applies when they are unplugged"
    VoiceOutlet.BLUETOOTH -> "Alfred's voice on Bluetooth. Speaker or call mode applies when it disconnects"
    VoiceOutlet.PHONE_CALL -> "On a phone call. Speaker or call mode applies after it"
}

/** The other choice: what a tap on the toggle saves. */
internal fun flipped(choice: OutputChoice): OutputChoice =
    if (choice == OutputChoice.SPEAKER) OutputChoice.CALL else OutputChoice.SPEAKER

/**
 * The toggle's live state: the saved choice, what is connected (re-read on every device change) and
 * the call state (re-read with [key], the voice phase). Null on a phone with no earpiece.
 */
@Composable
internal fun rememberOutputToggle(key: Any?): OutputToggle? {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    remember { VoiceOutput.choice(context) }
    val choice by VoiceOutput.flow.collectAsState()
    val devices = remember { mutableIntStateOf(0) }
    DisposableEffect(audio) {
        val watch = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { devices.intValue++ }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { devices.intValue++ }
        }
        runCatching { audio.registerAudioDeviceCallback(watch, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { audio.unregisterAudioDeviceCallback(watch) } }
    }
    val tick = devices.intValue
    return remember(choice, tick, key) {
        VoiceOutputRule.toggle(
            choice,
            CallAudioMode.route(audio),
            TelephonyController.isInCall,
            VoiceOutput.hasEarpiece(audio),
        )
    }
}
