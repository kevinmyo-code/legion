package com.kevin.legion.ui.clock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.clock.ClockDefaults
import com.kevin.legion.clock.RingKind
import com.kevin.legion.clock.RingRequest
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * What rings, big. Dismiss is the largest target; Brief me sits under it (ticket 07) and only for
 * an alarm, only after the first unlock. A [problem] sentence replaces the buttons when Brief me
 * could not start, so the reason is read before the screen closes.
 */
@Composable
fun RingScreen(now: String, ring: RingRequest?, canBrief: Boolean, problem: String?, actions: RingActions) {
    Column(
        Modifier.fillMaxSize().background(SoftColors.ground).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text(now, color = SoftColors.text, fontSize = 88.sp, fontWeight = FontWeight.Light)
        val title = when (ring?.kind) {
            RingKind.TIMER -> "Timer done"
            RingKind.ALARM -> "Alarm"
            RingKind.ALERT -> "Emergency alert"
            null -> ""
        }
        Text(title, color = SoftColors.primary, fontSize = 20.sp)
        ring?.label?.let { Text(it, color = SoftColors.text2, fontSize = 18.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.weight(1f))
        if (problem != null) {
            Text(problem, color = SoftColors.onAlert, fontSize = 17.sp, textAlign = TextAlign.Center)
            return@Column
        }
        if (ring?.kind == RingKind.ALERT) {
            // ADR 0082: the facts are the label above; one Dismiss, then the line is said.
            BigButton("Dismiss", actions.onDismiss)
        } else if (ring?.kind == RingKind.TIMER) {
            BigButton("Stop", actions.onDismiss)
            WideOutline("+${ClockDefaults.TIMER_EXTRA_MIN} min", actions.onAddMinute)
        } else if (ring != null) {
            BigButton("Dismiss", actions.onDismiss)
            if (canBrief) WideOutline("Brief me", actions.onBrief)
            WideOutline("Snooze ${ClockDefaults.SNOOZE_MIN} min", actions.onSnooze)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(32.dp),
        colors = primaryColors(),
        modifier = Modifier.fillMaxWidth().height(72.dp),
    ) { Text(text, fontSize = 22.sp) }
}

@Composable
private fun WideOutline(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(text, color = SoftColors.text, fontSize = 18.sp)
    }
}
