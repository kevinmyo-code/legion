package com.kevin.legion.ui.call

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.R
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.cascade.CallControl
import com.kevin.legion.service.cascade.CallGlyph
import com.kevin.legion.service.cascade.CallLine
import com.kevin.legion.service.cascade.InCallRule
import com.kevin.legion.service.turn.VoiceOutlet
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType
import kotlinx.coroutines.delay

/**
 * The call's own screen (Kevin, 2026-11-24: in public it should look like he is on the phone). Up for
 * the duration of a call, over whatever route the app was on, as a dialer is.
 *
 * It carries four facts and nothing else: who answered, how long it has been, and the three controls.
 * The screen is dark at his ear for most of a call, so this is not where work goes.
 *
 * Typeset (ADR 0081) for the words: one face, the ink tokens. The controls are a phone's own (Kevin,
 * 2026-11-28): round icon buttons, Mute and Speaker lit while on, and a red Hang up.
 */
@Composable
internal fun InCallOverlay() {
    val call by CallLine.line.collectAsStateWithLifecycle()
    val crisis by CompanionPhase.crisis.collectAsStateWithLifecycle()
    val line = call ?: return
    // Crisis takes the screen (CLAUDE.md sec 7). The call's own teardown removes this screen anyway
    // (the crisis path closes the session), but it does so a beat later, and none of a call's chrome
    // may sit over crisis content for that beat.
    if (crisis) return
    val context = LocalContext.current
    val name = remember { CompanionProfile.name(context) }
    InCallScreen(
        name = name,
        answeredAtMs = line.answeredAtMs,
        muted = line.muted,
        outlet = line.outlet,
        onMute = { CallControls.mute(context, !line.muted) },
        onSpeaker = { CallControls.speaker(context, earpiece = line.outlet != VoiceOutlet.EARPIECE) },
        onHangUp = { CallControls.hangUp(context) },
    )
}

/** The screen itself, given its facts: no service, no system call, so every state is a test. */
@Composable
internal fun InCallScreen(
    name: String,
    answeredAtMs: Long?,
    muted: Boolean,
    outlet: VoiceOutlet,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHangUp: () -> Unit,
    /** The wall clock, so a test can hold it still. */
    clock: () -> Long = { System.currentTimeMillis() },
) {
    var nowMs by remember { mutableLongStateOf(clock()) }
    // One tick a second while the screen is composed: the timer is the only thing on it that moves.
    // It does not tick while the screen is dark at his ear, which is correct and free.
    LaunchedEffect(answeredAtMs) {
        while (answeredAtMs != null) {
            nowMs = clock()
            delay(TICK_MS)
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Every touch that lands off a control stops here, so a tap on the screen's empty middle never
        // reaches what is under it (on the home page that is the orb, which would interrupt or end the
        // call he is on). It is a SIBLING under the controls, never their parent (Kevin, 2026-11-28:
        // the buttons did not work): as a parent it consumed each move of his finger before the
        // control's own tap detector saw it, and a press that moved a pixel was cancelled, so on a
        // real hand almost every tap was lost. A control is hit first and never reaches this layer.
        Box(
            Modifier.matchParentSize()
                .background(SoftColors.ground)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                    }
                },
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, style = TypesetType.hero, color = SoftColors.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    InCallRule.status(answeredAtMs, nowMs),
                    style = TypesetType.smallItalic,
                    color = SoftColors.text2,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(InCallRule.mute(muted), onMute)
                    ControlButton(InCallRule.speaker(outlet), onSpeaker)
                }
                Spacer(Modifier.height(40.dp))
                ControlButton(InCallRule.hangUp(), onHangUp)
            }
        }
    }
}

/**
 * One control as a dialer draws it: a round button with the call icon, no word (Kevin, 2026-11-28).
 * Mute and Speaker are lit while on, as a phone lights them; Hang up is the red one. TalkBack reads
 * [CallControl.detail], so the icon alone is never the only way to know what a button does.
 */
@Composable
private fun ControlButton(control: CallControl, onClick: () -> Unit) {
    val hangUp = control.glyph == CallGlyph.HANG_UP
    val fill = when {
        hangUp -> HANG_UP_RED
        control.active -> SoftColors.text
        else -> SoftColors.cardHighest
    }
    val ink = when {
        hangUp -> Color.White
        !control.enabled -> SoftColors.text3
        control.active -> SoftColors.ground
        else -> SoftColors.text
    }
    Box(
        Modifier.size(if (hangUp) HANG_UP_SIZE else CONTROL_SIZE)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = control.enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = control.detail },
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(glyphRes(control.glyph), contentDescription = null, tint = ink, size = ICON_SIZE)
    }
}

@DrawableRes
private fun glyphRes(glyph: CallGlyph): Int = when (glyph) {
    CallGlyph.MIC_OFF -> R.drawable.ms_mic_off
    CallGlyph.SPEAKER -> R.drawable.ms_volume_up
    CallGlyph.HEADPHONES -> R.drawable.ms_headphones
    CallGlyph.HANG_UP -> R.drawable.ms_call_end
}

/**
 * The three controls' way into the open conversation: the foreground service, as every other control
 * in the app reaches it ([com.kevin.legion.ui.assistant.rememberAssistantVoice]). The service holds
 * the session; the Activity holds no reference to it.
 */
internal object CallControls {
    fun mute(context: Context, on: Boolean) = send(context, AriaForegroundService.ACTION_CALL_MUTE, on)

    fun speaker(context: Context, earpiece: Boolean) =
        send(context, AriaForegroundService.ACTION_CALL_ROUTE, earpiece)

    fun hangUp(context: Context) = send(context, AriaForegroundService.ACTION_CALL_HANG_UP, false)

    private fun send(context: Context, action: String, on: Boolean) {
        context.startService(
            Intent(context, AriaForegroundService::class.java).setAction(action)
                .putExtra(AriaForegroundService.EXTRA_CALL_CONTROL_ON, on),
        )
    }
}

private val CONTROL_SIZE = 64.dp
private val HANG_UP_SIZE = 72.dp
private val ICON_SIZE = 30.dp

/** A dialer's end-call red, the one fixed colour on this screen. */
@Suppress("MagicNumber")
private val HANG_UP_RED = Color(0xFFE5393B)
private const val TICK_MS = 1_000L
