package com.kevin.legion.ui.typeset

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.kevin.legion.service.turn.OutputToggle
import com.kevin.legion.service.turn.VoiceOutlet
import com.kevin.legion.service.turn.VoiceOutput
import com.kevin.legion.service.turn.VoiceOutputRule
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * Where Alfred's voice comes out, as an icon and a word: "Speaker", "Call" (the phone held to the
 * ear), or what actually holds the route. A tap flips it, mid-conversation too. While headphones,
 * Bluetooth or a phone call decide the route it says which and does not respond. It sits in the line
 * under the orb, 48dp tall like "Type", so it never covers the orb and changes no fit tier.
 */
@Composable
internal fun OutputToggleButton(toggle: OutputToggle, onFlip: () -> Unit, modifier: Modifier = Modifier) {
    val ink = if (toggle.enabled) SoftColors.text2 else SoftColors.text3
    Row(
        modifier.heightIn(min = TapTarget)
            .clickable(enabled = toggle.enabled, role = Role.Button, onClick = onFlip)
            .semantics { contentDescription = toggle.detail }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = if (toggle.outlet == VoiceOutlet.EARPIECE) R.drawable.ms_smartphone else R.drawable.ms_nav_unmute
        MsIcon(icon, contentDescription = null, tint = ink, size = 20.dp)
        Spacer(Modifier.width(4.dp))
        Text(toggle.label, style = TypesetType.small, color = ink, maxLines = 1)
    }
}

/**
 * The toggle's live state: the saved choice, what is connected (re-read on every device change), the
 * call state (re-read with [key], the voice phase) and what an open conversation actually does
 * ([VoiceOutput.live], the platform's readback). Null on a phone with no earpiece.
 */
@Composable
internal fun rememberOutputToggle(key: Any?): OutputToggle? {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    remember { VoiceOutput.choice(context) }
    val choice by VoiceOutput.flow.collectAsState()
    val live by VoiceOutput.live.collectAsState()
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
    return remember(choice, live, tick, key) {
        VoiceOutputRule.toggle(
            choice,
            CallAudioMode.route(audio),
            TelephonyController.isInCall,
            VoiceOutput.hasEarpiece(audio),
            live,
        )
    }
}
