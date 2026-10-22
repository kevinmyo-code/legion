package com.kevin.legion.ui.dumb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.dumb.DumbFormat
import com.kevin.legion.dumb.DumbLeaveRule
import com.kevin.legion.dumb.DumbUiState
import com.kevin.legion.dumb.LeaveResult
import com.kevin.legion.dumb.LeaveUi

/**
 * The Leave flow (prototype `Leave.dc.html`, ADR 0062): a countdown that runs on the wall clock,
 * then a sentence to type. "Stay in dumb mode" is the primary button and Back does the same.
 * The wait and the sentence are checked again by the controller; this screen is only the view.
 */
@Composable
fun DumbLeaveScreen(
    ui: DumbUiState,
    leave: LeaveUi,
    onStay: () -> Unit,
    onLeave: (typed: String) -> LeaveResult,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    var refusal by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onStay)
    val matches = DumbLeaveRule.sentenceMatches(typed)
    val canLeave = !leave.waiting && matches

    Column(
        Modifier
            .fillMaxSize()
            .background(DumbColors.ground)
            .padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        LeaveHeader(ui.elapsed)
        if (leave.waiting) {
            LeaveCountdown(leave)
        } else {
            LeaveSentenceField(
                typed = typed,
                onTyped = {
                    typed = it
                    refusal = null
                },
                note = refusal ?: if (matches) "matches" else "not yet",
            )
        }
        Box(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canLeave) {
                PillButton(
                    "Leave dumb mode",
                    onClick = {
                        // The controller re-checks both rules; a refusal here means the screen was stale.
                        val result = onLeave(typed)
                        if (result != LeaveResult.Left) refusal = refusalWords(result)
                    },
                    primary = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PillButton("Stay in dumb mode", onStay, primary = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LeaveHeader(elapsed: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("dumb for $elapsed", color = DumbColors.dim, fontSize = 13.sp, fontFamily = DumbMono)
        Text("Leave dumb mode?", color = DumbColors.text, fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Text(
            "Wait out the timer, then type the sentence. The people you chose can reach you either way.",
            color = DumbColors.body,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}

@Composable
private fun LeaveCountdown(leave: LeaveUi) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            leave.remainingSeconds.toString(),
            color = DumbColors.text,
            fontFamily = DumbMono,
            fontSize = 96.sp,
            letterSpacing = (-4).sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(DumbColors.rule)) {
            Box(
                Modifier
                    .fillMaxWidth(leave.progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(DumbColors.text),
            )
        }
        Text("seconds. Put the phone down if you like.", color = DumbColors.dim, fontSize = 14.sp)
    }
}

@Composable
private fun LeaveSentenceField(typed: String, onTyped: (String) -> Unit, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Type: ${DumbLeaveRule.SENTENCE}", color = DumbColors.dim, fontSize = 14.sp)
        BasicTextField(
            value = typed,
            onValueChange = onTyped,
            singleLine = true,
            textStyle = TextStyle(color = DumbColors.text, fontSize = 17.sp),
            cursorBrush = SolidColor(DumbColors.text),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DumbColors.field)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Text(note, color = DumbColors.dim, fontSize = 13.sp, fontFamily = DumbMono)
    }
}

/** What did NOT happen, in words, for a refused leave. */
internal fun refusalWords(result: LeaveResult): String = when (result) {
    LeaveResult.Left -> ""
    LeaveResult.NotOn -> "Dumb mode is already off."
    LeaveResult.NotRequested -> "The wait has not started. Tap Leave on the home list first."
    is LeaveResult.TooEarly -> "Still waiting: ${DumbFormat.secondsCeil(result.remainingMs)} seconds left."
    LeaveResult.WrongSentence -> "That is not the sentence yet."
}
