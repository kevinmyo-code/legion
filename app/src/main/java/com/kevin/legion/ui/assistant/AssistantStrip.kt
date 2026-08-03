package com.kevin.legion.ui.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.AssistantIgnition
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.delay

/**
 * The persistent tap-to-talk affordance ticket 07 specified ("a global
 * toggle in Settings plus a persistent status affordance") and never shipped
 * - the toggle ([AssistantIgnition], `ui/SettingsScreen.kt`) landed, this did
 * not, and until now nothing anywhere could reach
 * [com.kevin.legion.service.LiveSessionController.onTap] except a dead
 * `CruiseScreen`. This is the entry point that makes the first execution of
 * the Gemini Live stack in this app possible.
 *
 * Sits inside [com.kevin.legion.ui.MainActivity]'s `Scaffold`, between the
 * `NavHost` content and the bottom nav. **The assistant is a MODE, not a
 * place** (ticket 07 resolution §5) - this is deliberately not a tab and
 * never navigates anywhere on its own; it either starts a turn in place or,
 * when the mic grant has gone stale, routes to Settings where the existing
 * permission chain (`ui/SettingsScreen.kt`) already lives.
 *
 * **Occupies zero space when the assistant is off** - not hidden behind an
 * `alpha`, not `Spacer(Modifier.height(0.dp))`; the composable returns before
 * emitting anything, so a driver who has never flipped the Settings toggle
 * sees exactly today's layout, unchanged.
 *
 * State-holder/UI split (`.claude/skills/compose-state-holder-ui-split`):
 * this function is the state holder - it owns [AssistantIgnition]'s live
 * flag, the three [CompanionPhase] flows, and the live RECORD_AUDIO
 * permission check; [AssistantStripContent] is the plain, previewable half
 * that only ever sees an [AssistantStripResolver.State].
 *
 * **Unexercised.** Nothing downstream of the tap - [LiveSessionController],
 * [com.kevin.legion.service.GeminiLiveSession], the Live socket itself - has
 * ever run in this app. This file is what makes that first run possible; it
 * is not evidence that voice works.
 */
@Composable
fun AssistantStrip(onOpenSettings: () -> Unit) {
    val context = LocalContext.current

    // Off by default and the ONLY thing that governs whether this composable
    // draws anything at all - see the "zero space when off" doc above.
    val assistantEnabled by AssistantIgnition.enabledState(context).collectAsStateWithLifecycle()
    if (!assistantEnabled) return

    val phase by CompanionPhase.phase.collectAsStateWithLifecycle()
    val caption by CompanionPhase.caption.collectAsStateWithLifecycle()

    // The permission is real Android state, not implied by the toggle - a
    // driver can revoke RECORD_AUDIO from system Settings at any point while
    // the assistant stays "on" (the service keeps running; it just fails the
    // next tap). Checked live rather than assumed from AssistantIgnition,
    // per the ticket's explicit instruction, and re-checked on ON_RESUME so
    // a driver who backs out to system Settings and grants/revokes it comes
    // back to an accurate strip without needing to leave and re-enter this
    // screen - same "can go stale for reasons outside this app" shape as
    // LedgerScreen's own ON_RESUME recheck of its Drive/key signals.
    var micGranted by remember { mutableStateOf(hasRecordAudio(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = hasRecordAudio(context)
    }

    // CompanionPhase.notice is a SharedFlow with no replay (see its own doc:
    // a frustrated double-tap must flash the same string twice, which a
    // StateFlow's conflation would swallow) - collect it here and hold the
    // latest one for a few seconds so the strip flashes it the same way the
    // now-dead Cruise/Lights Out screens did.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        CompanionPhase.notice.collect { text ->
            notice = text
            delay(NOTICE_DISPLAY_MS)
            // Only clear if nothing newer has already replaced it - a second
            // notice arriving mid-flash would otherwise have its own delayed
            // clear stomp on it early.
            if (notice == text) notice = null
        }
    }

    AssistantStripContent(
        state = AssistantStripResolver.resolve(phase, caption, notice, micGranted),
        onTap = {
            if (micGranted) {
                // Never binds, never constructs LiveSessionController here -
                // the controller is service-owned; this is the same
                // ACTION_TALK start-intent path AriaForegroundService.
                // onStartCommand already handles (the only prior caller was
                // the dead CruiseScreen).
                context.startService(
                    Intent(context, AriaForegroundService::class.java)
                        .setAction(AriaForegroundService.ACTION_TALK)
                )
            } else {
                onOpenSettings()
            }
        },
    )
}

private fun hasRecordAudio(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/** How long a flashed [CompanionPhase] notice stays on the strip before clearing. */
private const val NOTICE_DISPLAY_MS = 4_000L

/**
 * Plain UI half of [AssistantStrip] - immutable [AssistantStripResolver.State]
 * plus a single callback, previewable without a `Context` or any of the
 * service flows.
 *
 * Phase is legible from the text alone (CLAUDE.md §7: colour is never
 * sufficient) - the dot is a secondary, motion-carrying cue, not the only
 * signal. Motion is allowed on the phone pivot (the frame-clock-only ban was
 * head-unit only), so LISTENING/SPEAKING get a cheap pulse; every other state
 * is a static dot.
 */
@Composable
private fun AssistantStripContent(state: AssistantStripResolver.State, onTap: () -> Unit) {
    val sem = LocalLegionSemantics.current
    val labelColor = if (state.micBlocked) sem.quarantined else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhaseDot(active = state.active, blocked = state.micBlocked)
            Column(Modifier.weight(1f)) {
                Text(state.label, style = MaterialTheme.typography.titleMedium, color = labelColor)
                if (state.subtitle != null) {
                    Text(state.subtitle, style = LegionType.stamp, color = sem.faint)
                }
            }
        }
    }
}

/**
 * The dot beside the label. A static colour swatch for every phase except
 * LISTENING/SPEAKING, which pulse - a cheap `infiniteRepeatable` alpha
 * animation, not a frame-clock-gated one (that restriction was head-unit
 * only; see CLAUDE.md §6/§7).
 */
@Composable
private fun PhaseDot(active: Boolean, blocked: Boolean) {
    val sem = LocalLegionSemantics.current
    val color = when {
        blocked -> sem.quarantined
        else -> MaterialTheme.colorScheme.primary
    }
    // The transition is CONSTRUCTED conditionally, not merely read
    // conditionally. `rememberInfiniteTransition` + `animateFloat` tick for as
    // long as they are composed, whatever any `active` flag downstream says, so
    // registering them unconditionally and then picking between two values
    // would drive the frame clock and recompose this dot forever - on every
    // tab, in IDLE, for the entire time the assistant is switched on, because
    // this strip lives in Scaffold's bottomBar slot outside the NavHost.
    //
    // An earlier version did exactly that while its comment claimed the
    // opposite. Caught in review before it shipped.
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "assistant-strip-pulse")
        val pulsingAlpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "assistant-strip-pulse-alpha",
        )
        pulsingAlpha
    } else {
        1f
    }

    Box(
        Modifier
            .size(10.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape)
    )
}

// --- previews ---------------------------------------------------------

@Preview(name = "Assistant strip: idle", widthDp = 360)
@Composable
private fun PreviewAssistantStripIdle() = LegionTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = true),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: listening", widthDp = 360)
@Composable
private fun PreviewAssistantStripListening() = LegionTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.LISTENING, "how's the oil holding up?", null, micGranted = true,
        ),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: speaking", widthDp = 360)
@Composable
private fun PreviewAssistantStripSpeaking() = LegionTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.SPEAKING, "your oil change is about two weeks overdue", null, micGranted = true,
        ),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: notice", widthDp = 360)
@Composable
private fun PreviewAssistantStripNotice() = LegionTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(Phase.IDLE, "", "NO SIGNAL OUT HERE", micGranted = true),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: mic permission needed", widthDp = 360)
@Composable
private fun PreviewAssistantStripMicBlocked() = LegionTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(Phase.IDLE, "", null, micGranted = false),
        onTap = {},
    )
}
