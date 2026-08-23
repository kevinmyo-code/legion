package com.kevin.legion.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckMotionEnabled
import com.kevin.legion.ui.theme.legionPressScale
import kotlin.math.roundToInt

/**
 * The control vocabulary (mission-control ticket 09's widened Answer, section 1: "deck-native
 * look, M3 machinery underneath"). Every form control an app-wide M3 default cannot supply -
 * switch, checkbox, radio, button, text field, dialog - rebuilt as an outlined-rectangle,
 * stencil-caps custom composable, because the deck's flattened shape scale and mono type would
 * make a stock `Switch`/`Checkbox`/`Button` read as visibly wrong Material rather than as this
 * app's own language.
 *
 * **The constraint that makes this safe, verbatim from ticket 09/[com.kevin.legion.ui.theme.LegionTheme]:**
 * `Theme.kt` keeps M3 for component behaviour, touch targets and accessibility semantics; only the
 * token layer was ever meant to change. So every control here carries the real M3 interaction
 * modifier for its role - [androidx.compose.foundation.selection.toggleable] with
 * `role = Role.Switch`/`Role.Checkbox`, [androidx.compose.foundation.selection.selectable] with
 * `role = Role.RadioButton`, [androidx.compose.foundation.clickable] with `role = Role.Button` -
 * and a real 48dp touch target via [androidx.compose.foundation.layout.sizeIn]. **A control built
 * as a bare [Box] with an `onClick` is a regression, not a restyle**: it is invisible in a
 * screenshot and only shows up in TalkBack, which cannot be checked in this environment (see the
 * per-control notes below and the build report's assumptions ledger).
 *
 * **Destructive controls follow ticket 04's answer (`.scratch/mission-control/issues/04-alarm-without-hue.md`,
 * section 4): neutral `ink` outline is the everyday state; full `chrome` fill is spent ONLY on a
 * confirming step, never as the default.** [DeckButton] therefore takes `destructive` and
 * `confirming` as two separate, explicit booleans rather than inferring "this is dangerous, make it
 * red" from `destructive` alone - a delete button on a settings screen must not be red.
 *
 * **Built and previewed only** (ticket 13's scope) - see [com.kevin.legion.ui.theme.ThemePreview]
 * for the render proof. Migrating an existing M3 control on a real screen to one of these is a
 * later build ticket's job, not this file's.
 */

// ------------------------------------------------------------------- DeckSwitch

/**
 * Two-state segmented toggle, `ON` / `OFF`, active segment inverted (solid
 * [MaterialTheme.colorScheme.primary] (amber) fill, [MaterialTheme.colorScheme.onPrimary] (ground)
 * text), inactive segment plain with [LegionSemantics.faint] text. **Not a sliding thumb** - ticket
 * 09's table is explicit that this reads as two labelled positions, not an animated Material switch
 * track.
 *
 * The whole control (both segments plus the divider between them) carries a single
 * [androidx.compose.foundation.selection.toggleable] with `role = Role.Switch` - tapping either
 * segment toggles the same boolean, matching how a real hardware two-position switch works (there
 * is no "tap OFF to do nothing" state). An explicit [androidx.compose.ui.semantics.stateDescription]
 * is set even though `toggleable`'s own `Role.Switch` + boolean state already announces on/off,
 * because the visible shape here is a compound two-segment control rather than a single native
 * switch glyph - belt and suspenders for TalkBack, not a workaround for a gap.
 *
 * **The visible chrome is deliberately shorter than the 48dp touch target** ([SWITCH_SEGMENT_HEIGHT],
 * 32dp) - ticket 09's "not by making the visible shape 48dp tall" rule. The outer [Box] carries the
 * `sizeIn(minHeight = 48.dp)` and the `toggleable` modifier; the bordered, filled segments sit
 * centered inside it at their own natural height, same shape as [StatusLine]'s SETUP stamp (small
 * glyph, generous invisible hit area).
 */
private val SWITCH_SEGMENT_HEIGHT = 32.dp

@Composable
fun DeckSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sem = LocalLegionSemantics.current
    val stateWord = if (checked) "ON" else "OFF"
    Box(
        modifier
            .sizeIn(minHeight = 48.dp)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                enabled = enabled,
                role = Role.Switch,
            )
            .semantics { stateDescription = stateWord },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.border(1.dp, sem.chromeDim)) {
            DeckSwitchSegment(label = "ON", active = checked, enabled = enabled)
            Box(Modifier.width(1.dp).height(SWITCH_SEGMENT_HEIGHT).background(sem.chromeDim))
            DeckSwitchSegment(label = "OFF", active = !checked, enabled = enabled)
        }
    }
}

/** One half of [DeckSwitch]. Not exported - every caller reaches it through [DeckSwitch]. */
@Composable
private fun DeckSwitchSegment(label: String, active: Boolean, enabled: Boolean) {
    val sem = LocalLegionSemantics.current
    val textColor = when {
        !enabled -> sem.ghost
        active -> MaterialTheme.colorScheme.onPrimary
        else -> sem.faint
    }
    Box(
        Modifier
            .height(SWITCH_SEGMENT_HEIGHT)
            .let { if (active && enabled) it.background(MaterialTheme.colorScheme.primary) else it }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = textColor)
    }
}

// ----------------------------------------------------------------- DeckCheckbox

/**
 * `[X]` / `[ ]` in mono before a stencil-caps label. The whole row (glyph plus label) is one
 * [androidx.compose.foundation.selection.toggleable] target with `role = Role.Checkbox` - tapping
 * the label toggles it too, matching every M3 checkbox-row convention in the app already (e.g.
 * [SettingsNavRow]'s tap-anywhere-in-the-row pattern). No explicit `stateDescription` is set: unlike
 * [DeckSwitch]'s compound shape, `toggleable`'s own `Role.Checkbox` + boolean state is exactly what
 * a single native checkbox announces, so adding one here would be a redundant, driftable duplicate
 * of state the framework already tracks correctly.
 *
 * The 48dp touch target comes from `sizeIn(minHeight = 48.dp)` on the Row itself with
 * `verticalAlignment = Alignment.CenterVertically` - the Row's bounding/hit box grows, the glyph and
 * label stay at their natural (smaller) drawn height, centered inside it.
 */
@Composable
fun DeckCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sem = LocalLegionSemantics.current
    val glyph = if (checked) "[X]" else "[ ]"
    Row(
        modifier
            .sizeIn(minHeight = 48.dp)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                enabled = enabled,
                role = Role.Checkbox,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else sem.ghost,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else sem.ghost,
        )
    }
}

// -------------------------------------------------------------------- DeckRadio

/**
 * `(*)` / `( )` in mono before a stencil-caps label - [DeckCheckbox]'s exact shape with
 * [androidx.compose.foundation.selection.selectable]/`Role.RadioButton` in place of
 * `toggleable`/`Role.Checkbox`, for the same "toggleable's own state announcement already covers
 * it" reason.
 *
 * **Caller responsibility, not handled here**: a group of [DeckRadio]s needs its containing layout
 * wrapped in `Modifier.selectableGroup()` (`androidx.compose.foundation.selection.selectableGroup`)
 * for TalkBack to announce them as one radio group with a position ("2 of 4") rather than as
 * unrelated selectable rows - the same requirement M3's own `RadioButton` places on its callers.
 * [DeckRadio] cannot add this itself because it has no visibility into its siblings.
 */
@Composable
fun DeckRadio(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val sem = LocalLegionSemantics.current
    val glyph = if (selected) "(*)" else "( )"
    Row(
        modifier
            .sizeIn(minHeight = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else sem.ghost,
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else sem.ghost,
        )
    }
}

// ------------------------------------------------------------------- DeckButton

/**
 * Outlined rectangle, stencil caps - the hard-key shape at row scale (ticket 09's table). Unlike
 * [DeckSwitch]/[DeckCheckbox]/[DeckRadio], the 48dp floor here IS the visible shape: a button is
 * meant to read as a real key at row height, the same 48dp [DeckRow] already uses, not a compact
 * glyph with padding around it.
 *
 * **Colour is driven by [destructive] and [confirming] together, never by [destructive] alone**
 * (ticket 04 answer §4): the default (`destructive = false`) reads [LegionSemantics.chromeDim]
 * outline / [MaterialTheme.colorScheme.primary] (amber) text, like every other action label in the
 * app. `destructive = true, confirming = false` (the everyday state of a delete/purge/end button)
 * reads a plain [MaterialTheme.colorScheme.onSurface] (ink) outline and text - a control, not a
 * warning. Only `destructive = true, confirming = true` - the point of no return - spends full
 * [LegionSemantics.chrome]: filled background, [LegionSemantics.chrome] outline,
 * [MaterialTheme.colorScheme.onError] (ground) text, matching [QuarantineTag]'s own fill treatment
 * so a confirming destructive button and an ALARM tag read as the same weight of "this is real."
 * [enabled] overrides both to [LegionSemantics.ghost].
 */
@Composable
fun DeckButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    confirming: Boolean = false,
) {
    val sem = LocalLegionSemantics.current
    val outline: Color
    val fill: Color?
    val content: Color
    when {
        !enabled -> {
            outline = sem.ghost
            fill = null
            content = sem.ghost
        }
        destructive && confirming -> {
            outline = sem.chrome
            fill = sem.chrome
            content = MaterialTheme.colorScheme.onError
        }
        destructive -> {
            outline = MaterialTheme.colorScheme.onSurface
            fill = null
            content = MaterialTheme.colorScheme.onSurface
        }
        else -> {
            outline = sem.chromeDim
            fill = null
            content = MaterialTheme.colorScheme.primary
        }
    }
    // Ticket 14's uniform press response ("scale 0.97 or a surface shift, one spec in the Deck
    // components"): built here, once, so every DeckButton caller inherits it without an edit -
    // the scale is applied to the whole Box (fill, border, padding, and text together) rather than
    // just the text, so the pressed key visibly shrinks as one unit the way a real hard key would.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .sizeIn(minHeight = 48.dp, minWidth = 88.dp)
            .legionPressScale(interactionSource)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .let { if (fill != null) it.background(fill) else it }
            .border(1.dp, outline)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = content)
    }
}

// --------------------------------------------------------------- DeckTextField

/** Block-cursor width - roughly one mono glyph at [LegionType.reading]'s size, not a measured face metric. */
private val BLOCK_CURSOR_WIDTH: Dp = 7.dp

/**
 * A `labelSmall` caps label above, the value on a 1dp [LegionSemantics.chromeDim] rule, a block
 * cursor at the caret - ticket 09's table. Wraps [BasicTextField] rather than reimplementing text
 * editing, so IME behaviour, selection, and the editable-text accessibility node all come free;
 * nothing here calls `clearAndSetSemantics`, so [BasicTextField]'s own semantics survive untouched
 * ("keep its semantics", ticket 09's own words).
 *
 * **The default cursor is hidden** (`cursorBrush = SolidColor(Color.Transparent)`) and replaced with
 * a hand-drawn filled [Box] positioned at [TextLayoutResult.getCursorRect] - Compose's built-in
 * cursor is a thin I-beam with no public API to widen it into a block, so the only way to get "a
 * block cursor at the caret" is to draw one.
 *
 * **Known simplification, stated rather than hidden (this ticket builds this control previewed
 * only, not wired to a real screen):** the caret is always drawn at the END of [value]
 * (`TextFieldValue(text = value, selection = TextRange(value.length))` is reconstructed fresh every
 * call, not threaded from the caller), so editing in the MIDDLE of existing text will show the block
 * at the wrong position until whoever wires this to a real field switches the caller to hold a
 * [TextFieldValue] (selection included) rather than a bare [String]. Every other property - label,
 * rule, focus-gated blink, 48dp floor - is unaffected by this and is real.
 *
 * The blink reuses [StatusLine]'s cursor pattern (an [androidx.compose.animation.core.InfiniteTransition]
 * gated on both focus and [deckMotionEnabled]) - per [com.kevin.legion.ui.theme.DeckMotion]'s
 * ticket-13 doc, ambient motion is now budgeted PER SURFACE rather than exactly one element
 * app-wide, so a focused text field spending its own blinking caret alongside [StatusLine]'s cursor
 * elsewhere on the same screen is allowed under the superseding rule.
 */
@Composable
fun DeckTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val sem = LocalLegionSemantics.current
    val motionEnabled = deckMotionEnabled()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current

    val cursorAlpha = if (isFocused && enabled && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "deck-textfield-cursor")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    1f at 0
                    1f at 450
                    0f at 500
                    0f at 950
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "deck-textfield-cursor-alpha",
        )
        alpha
    } else {
        1f
    }

    Column(modifier.sizeIn(minHeight = 48.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = sem.faint)
        Spacer(Modifier.height(2.dp))
        Box {
            BasicTextField(
                value = TextFieldValue(text = value, selection = TextRange(value.length)),
                onValueChange = { onValueChange(it.text) },
                enabled = enabled,
                singleLine = singleLine,
                textStyle = LegionType.reading.copy(color = if (enabled) MaterialTheme.colorScheme.onSurface else sem.ghost),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = keyboardOptions,
                interactionSource = interactionSource,
                onTextLayout = { layoutResult = it },
                modifier = Modifier.fillMaxWidth(),
            )
            // The block cursor - only drawn while focused and enabled, so a blank unfocused field
            // does not show a stray mark at offset 0.
            if (isFocused && enabled) {
                layoutResult?.let { layout ->
                    // CLAMP to the layout's OWN text length, never `value.length` directly.
                    //
                    // `layoutResult` is captured in `onTextLayout` and lags `value` by a frame:
                    // on the recomposition that follows a keystroke, `value` already carries the
                    // new text while `layout` still describes the old. Asking it for a cursor at
                    // an offset it has never measured throws
                    // `IllegalArgumentException: offset(1) is out of bounds [0, 0]` and takes the
                    // whole app down.
                    //
                    // Found on-device 2026-08-15, by typing into the maintenance item-detail
                    // screen's interval field - the first editable text field any shipped LEGION
                    // surface has ever actually had (the only other caller is `ThemePreview`,
                    // which nobody types into). It is a crash in a SHARED mission-control control,
                    // not in the screen that surfaced it, and 1180 unit tests plus two review
                    // passes could not see it: it exists only as a frame-timing interaction
                    // between two recompositions.
                    //
                    // Clamping is right rather than merely safe. A cursor drawn one frame behind
                    // the text is invisible to a human; a crash is not.
                    val caret = layout.getCursorRect(
                        value.length.coerceIn(0, layout.layoutInput.text.length),
                    )
                    Box(
                        Modifier
                            .offset { IntOffset(caret.left.roundToInt(), caret.top.roundToInt()) }
                            .width(BLOCK_CURSOR_WIDTH)
                            .height(with(density) { (caret.bottom - caret.top).toDp() })
                            .graphicsLayer { alpha = cursorAlpha }
                            .background(sem.chromeText),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(sem.chromeDim))
    }
}

// ------------------------------------------------------------------- DeckDialog

/**
 * A pane with a pill title, inside the bezel (ticket 09's table). Wraps
 * [androidx.compose.ui.window.Dialog] - the low-level window primitive M3's own `AlertDialog`
 * itself wraps - rather than a plain overlay [Box], so back-press dismiss, outside-touch dismiss,
 * focus trapping and the dialog's own accessibility WINDOW semantics (TalkBack announcing "dialog"
 * and confining exploration to it) all come free. Reusing [DeckPane] for the frame is what actually
 * produces "a pane with a pill title" - no new frame-drawing code, the same [DeckLabelPill] every
 * other pane in the app uses.
 *
 * **[DeckPane]'s `pillBackground` default (`MaterialTheme.colorScheme.background`, pure black) is a
 * stated approximation, not a measured one**: the real pixel behind the pill is the platform
 * [Dialog]'s own scrim, not literally the app's screen background - reading as "close enough" on a
 * dark-only, near-black scrim is a call for whoever ships this on a real screen to confirm on
 * device, same L11 posture as every other unrendered claim in this file.
 *
 * "Inside the bezel" is read here as PLACEMENT (the dialog appears over an already-bezelled screen),
 * not an instruction to nest a second [DeckBezel] frame inside the dialog itself - ticket 09's table
 * does not describe bezel geometry for a dialog, and doubling the frame would visually compete with
 * [DeckPane]'s own border. Flagged as an inference, not read as settled.
 */
@Composable
fun DeckDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        DeckPane(
            header = title,
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            content = content,
        )
    }
}
