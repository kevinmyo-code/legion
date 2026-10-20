@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import com.kevin.legion.ui.theme.soft.SoftColors

private val DPAD_KEY = 52.dp
private val ACTION_KEY = 60.dp
private val DROP_KEY_H = 56.dp
private val B_DROP = 32.dp
private val MIN_TARGET = 48.dp
private const val DPAD_SPAN = 3
private const val DISABLED_ALPHA = 0.4f

/**
 * The handheld layout: Hold shoulder above a D-pad on the left, B, A and DROP on the right, Select and
 * Start small in the middle. Start is the pause button; in a race it is shown dimmed and does
 * nothing, because the screen says in words that races cannot pause.
 */
@Composable
fun TetrisGamepad(
    startEnabled: Boolean,
    holdEnabled: Boolean,
    onDown: (PadButton) -> Unit,
    onUp: (PadButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        PadKey(
            PadButton.HOLD, "HOLD", "Hold piece", RoundedCornerShape(12.dp), Modifier.width(96.dp).height(MIN_TARGET),
            onDown, onUp, enabled = holdEnabled,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DPad(onDown, onUp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PadKey(
                    PadButton.SELECT, "SELECT", "Select, next theme", RoundedCornerShape(16.dp),
                    Modifier.width(68.dp).height(MIN_TARGET), onDown, onUp, small = true,
                )
                PadKey(
                    PadButton.START, "START", "Start, pause", RoundedCornerShape(16.dp),
                    Modifier.width(68.dp).height(MIN_TARGET), onDown, onUp, small = true, enabled = startEnabled,
                )
            }
            ActionKeys(onDown, onUp)
        }
    }
}

@Composable
private fun DPad(onDown: (PadButton) -> Unit, onUp: (PadButton) -> Unit) {
    Box(Modifier.size(DPAD_KEY * DPAD_SPAN)) {
        val shape = RoundedCornerShape(10.dp)
        val key = Modifier.size(DPAD_KEY)
        PadKey(PadButton.UP, "\u25B2", "Hard drop", shape, key.align(Alignment.TopCenter), onDown, onUp)
        PadKey(PadButton.LEFT, "\u25C0", "Move left", shape, key.align(Alignment.CenterStart), onDown, onUp)
        PadKey(PadButton.RIGHT, "\u25B6", "Move right", shape, key.align(Alignment.CenterEnd), onDown, onUp)
        PadKey(PadButton.DOWN, "\u25BC", "Soft drop", shape, key.align(Alignment.BottomCenter), onDown, onUp)
    }
}

/**
 * A (rotate clockwise) upper right and B (rotate back) a step lower on the left, the way a handheld
 * sets them, and under both a wide DROP key for the right thumb (Kevin, 2026-10-19). The cluster is
 * as tall as the D-pad, so the pad did not grow. The PWA puts its Drop in the same place.
 */
@Composable
private fun ActionKeys(onDown: (PadButton) -> Unit, onUp: (PadButton) -> Unit) {
    Box(Modifier.width(ACTION_KEY * 2 - 4.dp).height(DPAD_KEY * DPAD_SPAN)) {
        val key = Modifier.size(ACTION_KEY)
        PadKey(PadButton.A, "A", "Rotate", CircleShape, key.align(Alignment.TopEnd), onDown, onUp, round = true)
        PadKey(
            PadButton.B, "B", "Rotate back", CircleShape,
            key.align(Alignment.TopStart).offset(y = B_DROP), onDown, onUp, round = true,
        )
        PadKey(
            PadButton.DROP, "DROP", "Drop", RoundedCornerShape(percent = 50),
            Modifier.fillMaxWidth().height(DROP_KEY_H).align(Alignment.BottomCenter), onDown, onUp, round = true,
        )
    }
}

/**
 * One key. Press calls [onDown] at once, lifting (or the finger leaving) calls [onUp], so the
 * ViewModel's auto-repeat knows exactly how long it was held. TalkBack gets a plain click that
 * does a press and a release.
 */
@Composable
private fun PadKey(
    button: PadButton,
    label: String,
    description: String,
    shape: Shape,
    modifier: Modifier,
    onDown: (PadButton) -> Unit,
    onUp: (PadButton) -> Unit,
    enabled: Boolean = true,
    small: Boolean = false,
    round: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    val body: Color = when {
        pressed -> SoftColors.primary
        round -> SoftColors.primaryContainer
        else -> SoftColors.cardHighest
    }
    Box(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(body)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick(description) {
                    if (enabled) {
                        down(button)
                        up(button)
                    }
                    enabled
                }
            }
            .pointerInput(button, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        down(button)
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                            up(button)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) SoftColors.ground else SoftColors.text,
            fontSize = if (small) 10.sp else 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

