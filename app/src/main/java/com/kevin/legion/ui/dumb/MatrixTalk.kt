package com.kevin.legion.ui.dumb

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.R
import com.kevin.legion.dumb.rain.animationsOff
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.MicLevel
import com.kevin.legion.service.Phase
import kotlinx.coroutines.delay

/** How long a flashed [CompanionPhase] notice holds the talk line; the same 4s the bottom strip uses. */
private const val NOTICE_DISPLAY_MS = 4_000L

/**
 * The live [MatrixTalkDisplay] for Matrix HOME: the same signals the bottom strip reads (phase,
 * silenced, the flashed notice, the RECORD_AUDIO grant), which Matrix HOME never showed because the
 * strip is hidden there. The notice handling mirrors `AssistantStrip.kt`: a replayed notice past
 * [CompanionPhase.NOTICE_REPLAY_MAX_AGE_MS] is dropped, a live one holds for 4s, and reaching
 * LISTENING clears it.
 */
@Composable
internal fun rememberMatrixTalkDisplay(): MatrixTalkDisplay {
    val context = LocalContext.current
    val phase by CompanionPhase.phase.collectAsStateWithLifecycle()
    val silenced by CompanionPhase.silenced.collectAsStateWithLifecycle()
    var micGranted by remember { mutableStateOf(hasRecordAudio(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { micGranted = hasRecordAudio(context) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        CompanionPhase.notice.collect { raised ->
            if (!CompanionPhase.noticeStillWorthShowing(raised.atMs, System.currentTimeMillis())) return@collect
            notice = raised.text
            delay(NOTICE_DISPLAY_MS)
            if (notice == raised.text) notice = null
        }
    }
    LaunchedEffect(phase) { if (phase == Phase.LISTENING) notice = null }
    return MatrixTalkResolver.resolve(phase, notice, micGranted, silenced)
}

private fun hasRecordAudio(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * The talk line with its motion running: one ticker drives the cursor, dots, spinner and wave, and
 * while LISTENING it samples [MicLevel] for the bar. With system animations off the motion holds its
 * still frame, but the level bar keeps updating: it is information, not decoration.
 */
@Composable
internal fun LiveMatrixTalkLine(display: MatrixTalkDisplay, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val still = remember(context) { animationsOff(context) }
    var tick by remember { mutableIntStateOf(0) }
    var cells by remember { mutableIntStateOf(0) }
    val needsTicker = display.motion != MatrixTalkMotion.NONE || display.showLevel
    LaunchedEffect(display.motion, display.showLevel) {
        cells = 0
        if (!needsTicker) return@LaunchedEffect
        while (true) {
            if (display.showLevel) {
                val reading = MicLevel.reading.value
                cells = MatrixTalkResolver.levelCells(reading.peak, System.currentTimeMillis() - reading.atMs)
            }
            delay(if (display.showLevel) LEVEL_SAMPLE_MS else MatrixTalkResolver.TICK_MS)
            tick++
        }
    }
    // The level samples four times a tick; the motion still moves once a tick.
    val motionTick = if (still) null else if (display.showLevel) tick / LEVEL_SAMPLES_PER_TICK else tick
    MatrixTalkLine(display, motionTick, if (display.showLevel) cells else null, modifier)
}

private const val LEVEL_SAMPLES_PER_TICK = 4
private const val LEVEL_SAMPLE_MS = MatrixTalkResolver.TICK_MS / LEVEL_SAMPLES_PER_TICK

/**
 * The talk line, stateless: `> LISTENING_ [|||||...]` and a dim detail under it. [tick] null is the
 * still frame; [levelCells] null hides the bar. TalkBack reads [MatrixTalkDisplay.spoken], once per
 * state, never the animation glyphs.
 */
@Composable
internal fun MatrixTalkLine(display: MatrixTalkDisplay, tick: Int?, levelCells: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = display.spoken
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                display.label + MatrixTalkResolver.frame(display.motion, tick),
                color = display.tone.color(),
                fontFamily = DumbMono,
                fontSize = 16.sp,
            )
            if (levelCells != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    MatrixTalkResolver.levelBar(levelCells),
                    color = MatrixColors.text,
                    fontFamily = DumbMono,
                    fontSize = 16.sp,
                )
            }
        }
        display.detail?.let { Text(it, color = MatrixColors.dim, fontFamily = DumbMono, fontSize = 13.sp) }
    }
}

/**
 * Round mic button: one tap starts a voice turn, the same path as the chat panel's Talk; a tap while
 * a conversation is live stops it (LiveSessionController.onTap). Filled green while live, outlined
 * otherwise, amber outline on a warning. The words beside it carry the meaning; this only echoes it.
 */
@Composable
internal fun TalkButton(onClick: () -> Unit, display: MatrixTalkDisplay = MatrixTalkResolver.IDLE) {
    val ring = if (display.tone == MatrixTone.WARNING) MatrixColors.warning else DumbColors.outline
    val label = if (display.live) "Stop the conversation" else "Talk to the assistant"
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (display.live) Modifier.background(DumbColors.text) else Modifier.border(1.dp, ring, CircleShape),
            )
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ms_mic),
            contentDescription = label,
            tint = if (display.live) DumbColors.ground else DumbColors.text,
        )
    }
}
