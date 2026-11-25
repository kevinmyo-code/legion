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
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.turn.AudioRoute
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.VoiceOutput
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The Call button's face: the word it shows, whether it can be pressed, and what TalkBack says. Pure,
 * so every case is a unit test. Public because the page that draws it ([TypesetHomeContent]) is.
 */
data class CallButtonUi(val label: String, val enabled: Boolean, val detail: String)

/** What a call's face says for the route it would really use. Pure: no Android types. */
internal object CallButtonRule {

    /**
     * Null hides the button: a phone with no earpiece has nothing to hold to an ear. On a headset the
     * call still behaves as a call (it is just not hands-held), so it says which and stays pressable;
     * a real phone call means hands off, as everything else in the voice path does.
     */
    fun face(name: String, hasEarpiece: Boolean, route: AudioRoute, inCall: Boolean): CallButtonUi? = when {
        !hasEarpiece -> null
        inCall -> CallButtonUi("Call", enabled = false, detail = "On a phone call. Call $name after it")
        route == AudioRoute.BLUETOOTH -> CallButtonUi(
            "Call on headset",
            enabled = true,
            detail = "Call $name on the connected headset. He cannot be interrupted mid-sentence over Bluetooth",
        )
        route == AudioRoute.WIRED_HEADSET -> CallButtonUi(
            "Call on headphones",
            enabled = true,
            detail = "Call $name on the connected headphones",
        )
        else -> CallButtonUi("Call", enabled = true, detail = "Call $name. He answers at your ear, like a phone call")
    }
}

/**
 * "Call" beside "Type" under the orb (Kevin, 2026-11-24: a phone-shaped button that calls Alfred).
 * 48 dp tall like "Type", so it never covers the orb and changes no fit tier. Ink, never the accent
 * (ADR 0081: the one accent is for flags), square, the same glyph call as the control it replaced.
 */
@Composable
internal fun CallButton(call: CallButtonUi, onCall: () -> Unit, modifier: Modifier = Modifier) {
    val ink = if (call.enabled) SoftColors.text2 else SoftColors.text3
    Row(
        modifier.heightIn(min = TapTarget)
            .clickable(enabled = call.enabled, role = Role.Button, onClick = onCall)
            .semantics { contentDescription = call.detail }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(R.drawable.ms_smartphone, contentDescription = null, tint = ink, size = 20.dp)
        Spacer(Modifier.width(4.dp))
        Text(call.label, style = TypesetType.small, color = ink, maxLines = 1)
    }
}

/**
 * The button's live state: what is connected (re-read on every device change) and the call state
 * (re-read with [key], the voice phase). Null on a phone with no earpiece.
 */
@Composable
internal fun rememberCallButton(key: Any?): CallButtonUi? {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
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
    // The persona's own name, never a literal (CLAUDE.md sec 1): what TalkBack reads is his name.
    val name = remember { CompanionProfile.name(context) }
    return remember(tick, key, name) {
        CallButtonRule.face(
            name,
            VoiceOutput.hasEarpiece(audio),
            CallAudioMode.route(audio),
            TelephonyController.isInCall,
        )
    }
}
