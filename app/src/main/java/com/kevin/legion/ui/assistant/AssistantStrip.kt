package com.kevin.legion.ui.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.R
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.AssistantIgnition
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.theme.legionPressScale
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
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
 * **No longer occupies zero space when the assistant is off (2026-09-01 calendar-home cutover,
 * Kevin: "AssistantStrip must always render... a bottom bar that vanishes is not acceptable").**
 * Before this cutover the composable returned before emitting anything when the Settings toggle
 * was off, so a driver who had never flipped it saw exactly the pre-existing layout with no bottom
 * bar at all - now that push-to-talk is the WHOLE bottom bar (`ui/MainActivity.kt`'s
 * `LegionHardKeyRow` was deleted the same cutover, see its own comment), a bar that can silently
 * disappear is a primary surface disappearing, not a neutral default. The off state now renders
 * [AssistantOffRow] - a quiet, tappable row that opens Settings - instead of nothing. The ENABLED
 * behaviour below this point is byte-for-byte unchanged.
 *
 * **RESTYLED for the soft-Material shell (home-launcher ticket 02, ADR 0051).** `MainActivity.kt`
 * wraps this whole composable's call site in [SoftTheme] - see that call site's own comment - so
 * every [MaterialTheme.colorScheme]/[MaterialTheme.typography] read below resolves against
 * [com.kevin.legion.ui.theme.soft.SoftTypography]/the soft colour scheme, not
 * [com.kevin.legion.ui.theme.LegionTheme]'s. Only [AssistantStripContent] and [AssistantOffRow]
 * change - this state holder, [AssistantStripResolver], and the tap/permission/notice plumbing
 * below are untouched.
 *
 * State-holder/UI split (`.claude/skills/compose-state-holder-ui-split`):
 * this function is the state holder - it owns [AssistantIgnition]'s live
 * flag, the three [CompanionPhase] flows, and the live RECORD_AUDIO
 * permission check; [AssistantStripContent] is the plain, previewable half
 * that only ever sees an [AssistantStripResolver.State].
 *
 * **Exercised as of 2026-09-01** (Kevin, in daily use: *"ive been using the phone and voice paths
 * all work"*). The chain below this tap - [LiveSessionController],
 * [com.kevin.legion.service.GeminiLiveSession], the Live socket - runs.
 *
 * The superseded claim is kept because it dated a real gap rather than describing one that never
 * existed: from this file's creation until 2026-09-01 nothing downstream of the tap had ever run,
 * and every plan made in that window was made without that evidence. It read:
 * *"**Unexercised.** Nothing downstream of the tap has ever run in this app. This file is what
 * makes that first run possible; it is not evidence that voice works."*
 */
@Composable
fun AssistantStrip(onOpenSettings: () -> Unit) {
    val context = LocalContext.current

    // Off by default. Used to be the sole gate on whether this composable drew anything at all
    // (see this file's own class doc for why that changed 2026-09-01) - now it only chooses which
    // of the two rows below renders.
    val assistantEnabled by AssistantIgnition.enabledState(context).collectAsStateWithLifecycle()
    if (!assistantEnabled) {
        AssistantOffRow(onTap = onOpenSettings)
        return
    }

    val phase by CompanionPhase.phase.collectAsStateWithLifecycle()
    val caption by CompanionPhase.caption.collectAsStateWithLifecycle()

    // Ticket 15's signal, finally reaching the driver. It used to stop at
    // CarProbeLog and the Settings diagnostic page, which meant the one state
    // where LEGION cannot hear was the one state it never said out loud.
    // False on API 28 and below means "not known to be silenced" - the platform
    // offers no signal there at all - never "confirmed hearing you".
    val silenced by CompanionPhase.silenced.collectAsStateWithLifecycle()

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

    // CompanionPhase.notice is a SharedFlow, not a StateFlow (see its own doc: a frustrated
    // double-tap must flash the same string twice, which a StateFlow's conflation would swallow) -
    // collect it here and hold the latest one for a few seconds so the strip flashes it the same
    // way the now-dead Cruise/Lights Out screens did.
    //
    // **The freshness check is the 2026-09-07 half.** That flow now replays its last value to a
    // late subscriber, precisely so a refusal raised by a wake word while this composable did not
    // exist is still readable when the app is opened. The cost of replay is that the value can be
    // OLD, and a stale flash reads as a live failure - so a replayed notice past
    // CompanionPhase.NOTICE_REPLAY_MAX_AGE_MS is dropped rather than shown. A live emission always
    // passes; this only ever rejects the buffer's own leftovers.
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        CompanionPhase.notice.collect { raised ->
            if (!CompanionPhase.noticeStillWorthShowing(raised.atMs, System.currentTimeMillis())) {
                return@collect
            }
            val text = raised.text
            notice = text
            delay(NOTICE_DISPLAY_MS)
            // Only clear if nothing newer has already replaced it - a second
            // notice arriving mid-flash would otherwise have its own delayed
            // clear stomp on it early.
            if (notice == text) notice = null
        }
    }

    // The resolver lets a notice outrank the phase, so a failure flashed just before a conversation
    // connected would hide "Listening" until its timer ran out. Listening means the socket really
    // connected; whatever error was on screen is over (2026-10-02).
    LaunchedEffect(phase) { if (phase == Phase.LISTENING) notice = null }

    // The typed box and reply panel (web-assistant ticket 09) ride in on a CompositionLocal so the
    // strip's own signatures stay as they were. See [TypedChatUi].
    val chatViewModel: AssistantChatViewModel = viewModel()
    val chat by chatViewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { chatViewModel.refreshCompanionName() }
    val typedChat = TypedChatUi(
        companionName = chat.companionName,
        entries = chat.entries,
        pendingReply = chat.pendingReply,
        onSend = chatViewModel::send,
        onNewConversation = chatViewModel::newConversation,
    )

    CompositionLocalProvider(LocalTypedChat provides typedChat) {
        AssistantStripContent(
            state = AssistantStripResolver.resolve(phase, caption, notice, micGranted, silenced),
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
}

/**
 * The shared outer chrome both [AssistantStripContent] and [AssistantOffRow] sit in (home-launcher
 * ticket 02): [SoftColors.barLow] surface with a 1dp [SoftColors.barRule] hairline drawn along the
 * TOP edge (matching the prototype canvas's `border-top`, not a full border), inner padding 16dp
 * horizontal / 9dp vertical - ticket 02 gives "8-10 vertical" as a range rather than two named
 * values, and 9dp is the midpoint. Factored out once rather than duplicated in both call sites,
 * which render mutually exclusively (enabled vs off) but each own the WHOLE bottom bar's content
 * when they render, so each needs the identical outer wrapper.
 */
@Composable
private fun AssistantStripBar(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SoftColors.barLow)
            .drawBehind {
                drawLine(
                    color = SoftColors.barRule,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        content()
    }
}

/**
 * The assistant-off state of [AssistantStrip] (2026-09-01 calendar-home cutover) - a quiet,
 * tappable pill that opens Settings on tap, replacing the old zero-space return.
 *
 * **RESTYLED (home-launcher ticket 02)**: the same 52dp, fully-rounded pill shape
 * [AssistantStripContent] uses, but OUTLINED (1dp [SoftColors.outline], transparent fill) rather
 * than filled - deliberately faint/muted, matching the ENABLED state's own IDLE-phase tone rather
 * than an estimate/caution colour, since the assistant being off is a setting, not a fault. Copy is
 * ticket 02's own words, and never names a persona (CLAUDE.md §1: "never hardcode an assistant name
 * into copy").
 *
 * `internal`, not `private` - `screenshot.AssistantStripScreenshotTest` (a different package)
 * renders it directly, the same cross-package `internal` visibility `SoftTheme.kt`'s
 * `SoftColorScheme` and `MainActivity.kt`'s `formatShellStatusLine` already rely on for their own
 * tests.
 */
@Composable
internal fun AssistantOffRow(onTap: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    AssistantStripBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .legionPressScale(interactionSource)
                .clip(RoundedCornerShape(percent = 50))
                .border(1.dp, SoftColors.outline, RoundedCornerShape(percent = 50))
                .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onTap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Assistant off. Tap to turn it on in Settings.",
                style = MaterialTheme.typography.labelLarge,
                color = SoftColors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The blocked/silenced pill tone (home-launcher ticket 02) - a literal the ticket names inline,
 * separate from the shared [SoftColors] table, since nothing else in this ticket's scope reads it. */
private val MicBlockedContainer = Color(0xFF3A2E12)

/**
 * Plain UI half of [AssistantStrip] - immutable [AssistantStripResolver.State]
 * plus a single callback, previewable without a `Context` or any of the
 * service flows.
 *
 * **RESTYLED (home-launcher ticket 02)**: a full-width, 52dp, fully-rounded pill
 * ([SoftColors.primaryContainer] fill, `ms_mic`/[SoftColors.onPrimaryContainer] icon+label,
 * centred) with [state.subtitle] beneath it, outside the pill, in [MaterialTheme.typography.bodySmall]
 * / [SoftColors.text2], max 2 lines. `micBlocked`/`silenced` swap the pill to a caution tone
 * ([MicBlockedContainer] fill, [SoftColors.caution] content, `ms_mic_off`) - colour is reinforcement
 * only, per CLAUDE.md §7: [state.label] itself already says "Microphone permission needed" or
 * "Can't hear you...", so nothing here depends on the driver seeing the colour to know something is
 * wrong.
 *
 * Motion is unchanged in kind, only in WHERE it lives: the old dot's pulse becomes the mic icon's
 * own alpha pulse for LISTENING/SPEAKING, still the same [rememberInfiniteTransition] this file has
 * always used, still built CONDITIONALLY rather than merely read conditionally - constructing it
 * unconditionally and picking between two alpha values afterwards would drive the frame clock for
 * as long as this pill is composed, in every phase, on every tab, which is exactly the mistake an
 * earlier version of the retired dot made and a review caught before it shipped. That is why
 * [state.active] gates the WHOLE `rememberInfiniteTransition` call below, not just its output.
 *
 * `internal`, not `private` - see [AssistantOffRow]'s own doc for why (the same screenshot test
 * renders this one too).
 */
@Composable
internal fun AssistantStripContent(state: AssistantStripResolver.State, onTap: () -> Unit) {
    // The typed box (web-assistant ticket 09) arrives on [LocalTypedChat]; null is the strip exactly
    // as it was. Beside the box the pill is narrow, so a long label (a notice, "Microphone
    // permission needed") moves out of the pill into the line beneath it, in words, and the pill
    // keeps only its icon. Phase labels ("Tap to talk", "Listening...") are short and stay inside.
    val typed = LocalTypedChat.current
    val iconOnly = typed != null && pillIsIconOnly(state)
    val subtitle = stripSubtitle(state, iconOnly)
    AssistantStripBar {
        if (typed != null && typed.hasConversation) {
            AssistantReplyPanel(typed, Modifier.padding(bottom = 8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (typed != null) {
                TypedMessageField(typed.companionName, typed.onSend, Modifier.weight(1f))
            }
            TalkPill(state, onTap, narrow = typed != null, iconOnly = iconOnly)
        }
        if (subtitle != null) {
            val unavailable = state.micBlocked || state.silenced
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (unavailable && typed != null) SoftColors.caution else SoftColors.text2,
                maxLines = if (typed != null) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Whether the pill beside the typed box shows only its icon: blocked/silenced states and any label
 * longer than [COMPACT_LABEL_MAX] (a notice) are said in the line under the strip instead.
 */
internal fun pillIsIconOnly(state: AssistantStripResolver.State): Boolean =
    state.micBlocked || state.silenced || state.label.length > COMPACT_LABEL_MAX

/**
 * The line under the strip. [iconOnly] (typed box present, label moved out of the pill) folds the
 * label in and, when the mic is unavailable, says typing still works - true because typing never
 * touches the mic. Otherwise exactly [AssistantStripResolver.State.subtitle], as before.
 */
internal fun stripSubtitle(state: AssistantStripResolver.State, iconOnly: Boolean): String? =
    if (iconOnly) {
        listOfNotNull(
            state.label.trimEnd('.'),
            state.subtitle?.trimEnd('.'),
            "Typing still works".takeIf { state.micBlocked || state.silenced },
        ).joinToString(". ") + "."
    } else {
        state.subtitle
    }

/**
 * The talk pill itself - the part of [AssistantStripContent] that used to be inline. [narrow] is the
 * typed-box layout (wraps its content, 14dp side padding); false is the original full-width pill.
 */
@Composable
private fun TalkPill(state: AssistantStripResolver.State, onTap: () -> Unit, narrow: Boolean, iconOnly: Boolean) {
    val blocked = state.micBlocked || state.silenced
    val pillContainer = if (blocked) MicBlockedContainer else SoftColors.primaryContainer
    val pillContent = if (blocked) SoftColors.caution else SoftColors.onPrimaryContainer
    val iconRes = if (blocked) R.drawable.ms_mic_off else R.drawable.ms_mic
    // Called here, at the composable level, because graphicsLayer's own lambda below is a
    // DRAW-phase closure and cannot call a @Composable function itself. [pulseAlpha] hands back
    // the raw State<Float>, NOT destructured with `by` - only its `.value`, read inside the
    // graphicsLayer lambda further down, is what stays draw-phase-only. Destructuring it here
    // instead (`val iconAlpha by pulseAlpha(...)`) would subscribe THIS composable's own
    // recomposition scope to every animation tick, recomposing the whole pill on each frame - the
    // exact deferred-read discipline [com.kevin.legion.ui.common.StatusLine]'s own cursor already
    // follows (`cursorAlpha.value` inside its `graphicsLayer` lambda, never destructured earlier).
    val iconAlpha = pulseAlpha(active = state.active)

    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = (if (narrow) Modifier.widthIn(min = 52.dp) else Modifier.fillMaxWidth())
            .height(52.dp)
            .legionPressScale(interactionSource)
            .clip(RoundedCornerShape(percent = 50))
            .background(pillContainer)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onTap)
            .padding(horizontal = if (narrow) 14.dp else 0.dp)
            .semantics(mergeDescendants = true) { if (iconOnly) contentDescription = state.label },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(
            res = iconRes,
            // Decorative - state.label (below) already carries the meaning in words, and the
            // whole pill is one clickable region TalkBack reads as a unit.
            contentDescription = null,
            tint = pillContent,
            modifier = Modifier.graphicsLayer { alpha = iconAlpha.value },
        )
        if (!iconOnly) {
            Text(
                state.label,
                style = MaterialTheme.typography.labelLarge,
                color = pillContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The mic icon's alpha while [active] (LISTENING/SPEAKING) - a cheap `infiniteRepeatable`, not a
 * frame-clock-gated one (that restriction was head-unit only; see CLAUDE.md §6/§7). Returns the raw
 * [State] rather than a destructured `Float` so the caller can defer the actual read to draw phase
 * (see the call site's own comment) - and, when not [active], a constant `1f` [State] with no
 * [rememberInfiniteTransition] created at all, same conditional-construction discipline this strip's
 * retired dot used: constructing the transition unconditionally and merely choosing between two
 * alpha values afterwards would drive the frame clock for as long as this pill is composed, in every
 * phase, on every tab. An earlier version made exactly that mistake while its own comment claimed
 * the opposite; a review caught it before it shipped.
 */
@Composable
private fun pulseAlpha(active: Boolean): State<Float> {
    return if (active) {
        val transition = rememberInfiniteTransition(label = "assistant-strip-pulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(com.kevin.legion.ui.theme.LegionMotion.PULSE_MS), RepeatMode.Reverse),
            label = "assistant-strip-pulse-alpha",
        )
    } else {
        remember { mutableStateOf(1f) }
    }
}

private fun hasRecordAudio(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Longest label that stays inside the narrow pill beside the typed box. The longest phase label,
 * "Connecting...", is 11 characters; anything longer is a notice or a blocked state.
 */
private const val COMPACT_LABEL_MAX = 14

/** How long a flashed [CompanionPhase] notice stays on the strip before clearing. */
private const val NOTICE_DISPLAY_MS = 4_000L

// --- previews ---------------------------------------------------------
// Wrapped in SoftTheme, not LegionTheme, since that is what MainActivity.kt's real call site wraps
// this composable in (home-launcher ticket 02) - a preview under the wrong theme would render the
// wrong font/colours and silently stop matching what actually ships.

@Preview(name = "Assistant strip: off (2026-09-01 - was zero-space)", widthDp = 384)
@Composable
private fun PreviewAssistantOffRow() = SoftTheme {
    AssistantOffRow(onTap = {})
}

@Preview(name = "Assistant strip: idle", widthDp = 384)
@Composable
private fun PreviewAssistantStripIdle() = SoftTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.IDLE, "", null, micGranted = true, silenced = false,
        ),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: listening", widthDp = 384)
@Composable
private fun PreviewAssistantStripListening() = SoftTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.LISTENING, "how's the oil holding up?", null, micGranted = true,
            silenced = false,
        ),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: speaking", widthDp = 384)
@Composable
private fun PreviewAssistantStripSpeaking() = SoftTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.SPEAKING, "your oil change is about two weeks overdue", null, micGranted = true,
            silenced = false,
        ),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: notice", widthDp = 384)
@Composable
private fun PreviewAssistantStripNotice() = SoftTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.IDLE, "", "NO SIGNAL OUT HERE", micGranted = true, silenced = false,
        ),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: mic permission needed", widthDp = 384)
@Composable
private fun PreviewAssistantStripMicBlocked() = SoftTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.IDLE, "", null, micGranted = false, silenced = false,
        ),
        onTap = {},
    )
}

@Preview(name = "Assistant strip: silenced by another app", widthDp = 384)
@Composable
private fun PreviewAssistantStripSilenced() = SoftTheme {
    AssistantStripContent(
        state = AssistantStripResolver.resolve(
            Phase.LISTENING, "go ahead", null, micGranted = true, silenced = true,
        ),
        onTap = {},
    )
}
