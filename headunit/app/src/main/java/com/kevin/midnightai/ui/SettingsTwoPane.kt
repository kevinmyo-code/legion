package com.kevin.midnightai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens

/*
 * Settings, two-pane (prototype A, Kevin 2026-10-11): a category rail on the left, the selected
 * category's rows on the right. Replaces the three-level hub -> group menu -> leaf walk, which
 * cost two taps and a full-screen swap before a driver could see what was in a category.
 *
 * This file is PURE presentation. It is stateless (selection and rows come in as parameters) and
 * touches no singleton, so it renders in the preview JVM. Everything that reads real state lives in
 * ControlPanelScreen.kt, which owns the leaves and the status sources.
 *
 * Colour roles, tokens only (the app swaps palettes, and SUMMER_COAST is a LIGHT palette, so a
 * role that is right on the night teal can be unreadable on sky blue):
 *   rail + card ground ........ Panel          (cream on coast, dark on the night palettes)
 *   pane ground ............... Background     (sky blue on coast)
 *   selected rail item ........ TextPrimary fill with Background text (inverted; 9.7:1 coast)
 *   body / dim text ........... TextPrimary / TextDim (never Amber: butter yellow on cream or sky
 *                               is ~1.4:1, which is the bug the 7a23d07 darkening was fixing)
 *   status colours ............ see [statusColor]
 */

/** The seven rail entries, in rail order. */
internal enum class SettingsCategory(
    val title: String,
    val kana: String,
    val blurb: String,
    val icon: SettingsIcon,
) {
    PHONES("Phones & audio", "フォン", "Which phone is connected, and whether Bluetooth is behaving.", SettingsIcon.PHONE),
    MUSIC("Music", "ミュージック", "Spotify and how music apps are opened.", SettingsIcon.MUSIC),
    ENGINE("Engine", "エンジン", "Your LEGION engine: lists, calendar, notes.", SettingsIcon.ENGINE),
    COMPANION("Companion", "コンパニオン", "Who you are talking to, how they sound, and what they know.", SettingsIcon.COMPANION),
    CAR("Your car", "クルマ", "The car itself: details, adapter, logbook, places, garage.", SettingsIcon.CAR),
    LOOK("Look", "ルック", "Colors, wallpaper, gauges, and the screen.", SettingsIcon.LOOK),
    SYSTEM("System", "システム", "Permissions, preferences, and starting over.", SettingsIcon.SYSTEM),
}

internal enum class SettingsIcon { PHONE, MUSIC, ENGINE, COMPANION, CAR, LOOK, SYSTEM }

/** PRIMARY = filled, HOT = accent fill for the thing that fixes a problem, QUIET = outlined. */
internal enum class ButtonWeight { PRIMARY, HOT, QUIET }

internal sealed interface RowAction {
    data class Button(val label: String, val weight: ButtonWeight, val onClick: () -> Unit) : RowAction
    data class Toggle(val checked: Boolean, val onChange: (Boolean) -> Unit) : RowAction
}

/** Status chip next to a row title. [dot] picks the colour; text is the words ("OK", "ACTION NEEDED"). */
internal data class SettingsChip(val text: String, val dot: SettingsDot)

internal data class SettingsRowSpec(
    val title: String,
    val subtitle: String,
    val action: RowAction,
    val chip: SettingsChip? = null,
)

/** Below this width the two panes do not fit side by side and Settings becomes one pane at a time. */
internal const val SETTINGS_TWO_PANE_MIN_DP = 600

private val RailWidth = 300.dp
private val RailItemMin = 60.dp
private val ButtonMin = 48.dp
private val Corner = RoundedCornerShape(4.dp)

/**
 * Status colour for text and dots, chosen against the PANEL it sits on (rail and cards).
 *
 * The constant paper tokens carry the light palette: Patina 4.5:1 and StampRed 6.1:1 on SUMMER
 * COAST's cream. Those two would be unreadable on the dark palettes (StampRed on 0xFF172229 is
 * ~1.9:1), so dark palettes use their own cyan / amber / magenta accents, which are 4.7:1 or
 * better there. The branch is on the panel's own luminance rather than on a palette key, so a
 * future palette picks the right side without being listed here.
 */
@Composable
internal fun statusColor(dot: SettingsDot): Color {
    val lightPanel = AriaColors.Panel.luminance() > 0.5f
    return when (dot) {
        SettingsDot.OK -> if (lightPanel) AriaColors.Patina else AriaColors.NeonCyan
        SettingsDot.WARN -> if (lightPanel) AriaColors.StampRed else AriaColors.Amber
        SettingsDot.ERROR -> if (lightPanel) AriaColors.StampRed else AriaColors.ScanRed
        SettingsDot.NONE -> Color.Transparent
    }
}

/**
 * The whole two-pane screen.
 *
 * @param selected the highlighted category (always non-null; the caller picks a default).
 * @param paneOpen COMPACT only: false shows the rail full width, true shows [selected]'s pane
 *   with a back row. Ignored when [compact] is false.
 * @param rows the selected category's rows, already built by the caller.
 * @param footer optional note under the rows (the CARS pointer under "Your car").
 */
@Composable
internal fun SettingsTwoPane(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    statuses: Map<SettingsCategory, CategoryStatus>,
    rows: List<SettingsRowSpec>,
    compact: Boolean,
    paneOpen: Boolean,
    onClosePane: () -> Unit,
    onExit: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().background(AriaColors.Background)) {
        if (!compact) {
            Row(Modifier.fillMaxSize()) {
                SettingsRail(
                    selected = selected,
                    onSelect = onSelect,
                    statuses = statuses,
                    onExit = onExit,
                    modifier = Modifier.width(RailWidth).fillMaxHeight(),
                )
                SettingsPane(selected, rows, footer, backLabel = null, onBack = {}, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else if (!paneOpen) {
            SettingsRail(selected, onSelect, statuses, onExit, Modifier.fillMaxSize())
        } else {
            SettingsPane(selected, rows, footer, backLabel = "SETTINGS", onBack = onClosePane, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SettingsRail(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    statuses: Map<SettingsCategory, CategoryStatus>,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .background(AriaColors.Panel)
            .border(AriaDimens.hairline, AriaColors.PanelBorder),
    ) {
        Column(Modifier.padding(start = AriaDimens.s3, end = AriaDimens.s3, top = AriaDimens.s2)) {
            BackAffordance("‹ CRUISE", onExit)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Settings", style = ScreenTitle, color = AriaColors.TextPrimary)
                Spacer(Modifier.width(AriaDimens.s2))
                Text(
                    "設定",
                    style = Label.copy(letterSpacing = 3.sp),
                    color = AriaColors.TextDim,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Spacer(Modifier.height(AriaDimens.s2))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AriaDimens.s2, vertical = AriaDimens.s1),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsCategory.entries.forEach { cat ->
                RailItem(cat, cat == selected, statuses[cat], onClick = { onSelect(cat) })
            }
        }
    }
}

/** "‹ CRUISE" / "‹ SETTINGS": a 48dp-tall touch target, TextPrimary (Amber text is unreadable on coast). */
@Composable
private fun BackAffordance(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = ButtonMin)
            .clip(Corner)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = AriaDimens.s1),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = Label.copy(fontSize = 12.sp), color = AriaColors.TextPrimary)
    }
}

@Composable
private fun RailItem(
    cat: SettingsCategory,
    isSelected: Boolean,
    status: CategoryStatus?,
    onClick: () -> Unit,
) {
    // Inverted when selected: the fill is the primary text colour and the text takes the ground
    // colour, so contrast is the same pair the rest of the app already trusts (9.7:1 on coast).
    val fill = if (isSelected) AriaColors.TextPrimary else Color.Transparent
    val titleColor = if (isSelected) AriaColors.Background else AriaColors.TextPrimary
    val lineColor = if (isSelected) AriaColors.Background else AriaColors.TextDim
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RailItemMin)
            .clip(Corner)
            .background(fill)
            .clickable(role = Role.Tab) { onClick() }
            .padding(horizontal = AriaDimens.s3, vertical = AriaDimens.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(cat.icon, titleColor)
        Spacer(Modifier.width(AriaDimens.s3))
        Column(Modifier.weight(1f)) {
            Text(cat.title, style = RowTitle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) {
                Text(
                    status.line,
                    style = RowSubtitle.copy(fontSize = 11.sp, letterSpacing = 0.3.sp),
                    color = lineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (status != null && status.dot != SettingsDot.NONE) {
            Spacer(Modifier.width(AriaDimens.s2))
            StatusDot(status.dot, onInverted = isSelected)
        }
    }
}

/**
 * [onInverted] seats the dot on a Panel-coloured disc: the status colours are picked against the
 * panel, and on the selected item's TextPrimary fill they would lose contrast (Patina on navy is
 * 3.5:1, StampRed worse).
 */
@Composable
private fun StatusDot(dot: SettingsDot, onInverted: Boolean = false) {
    if (onInverted) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(AriaColors.Panel),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(dot))) }
    } else {
        Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(dot)))
    }
}

@Composable
private fun SettingsPane(
    selected: SettingsCategory,
    rows: List<SettingsRowSpec>,
    footer: (@Composable () -> Unit)?,
    backLabel: String?,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val sizeClass = LocalSizeClass.current
    Column(modifier.padding(screenEdge(sizeClass))) {
        if (backLabel != null) {
            BackAffordance("‹ $backLabel", onBack)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(selected.title, style = ScreenTitle, color = AriaColors.TextPrimary)
            Spacer(Modifier.width(AriaDimens.s3))
            Text(
                selected.kana,
                style = Label.copy(letterSpacing = 3.sp),
                color = AriaColors.TextDim,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(AriaDimens.s1))
        Text(selected.blurb, style = RowSubtitle, color = AriaColors.TextDim)
        Spacer(Modifier.height(AriaDimens.s3))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AriaDimens.s2),
        ) {
            rows.forEach { SettingsRowCard(it) }
            footer?.let {
                Spacer(Modifier.height(AriaDimens.s1))
                it()
            }
        }
    }
}

@Composable
internal fun SettingsRowCard(spec: SettingsRowSpec) {
    val action = spec.action
    // A button row is tappable anywhere on the card (a head-unit finger lands loosely); a toggle
    // row is NOT, so a stray tap on the text can never flip a preference.
    val cardClick = (action as? RowAction.Button)?.onClick
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(Corner)
            .background(AriaColors.Panel)
            .border(AriaDimens.hairline, AriaColors.PanelBorder, Corner)
            .then(if (cardClick != null) Modifier.clickable(role = Role.Button) { cardClick() } else Modifier)
            .padding(horizontal = AriaDimens.s3, vertical = AriaDimens.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.title, style = RowTitle, color = AriaColors.TextPrimary, modifier = Modifier.weight(1f, fill = false))
                spec.chip?.let {
                    Spacer(Modifier.width(AriaDimens.s2))
                    StatusChip(it)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(spec.subtitle, style = RowSubtitle, color = AriaColors.TextDim)
        }
        Spacer(Modifier.width(AriaDimens.s3))
        when (action) {
            is RowAction.Button -> ActionButton(action)
            is RowAction.Toggle -> Box(Modifier.heightIn(min = ButtonMin), contentAlignment = Alignment.Center) {
                Switch(checked = action.checked, onCheckedChange = action.onChange)
            }
        }
    }
}

/**
 * Outlined pill. Text is TextPrimary for OK (Patina text on cream is 4.49:1, a hair under AA, so
 * the green lives in the border and only the border) and the status colour for problems, which
 * clear AA on their own panel (StampRed 6.1:1 on coast cream).
 */
@Composable
private fun StatusChip(chip: SettingsChip) {
    val color = statusColor(chip.dot)
    Text(
        chip.text,
        style = Label.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        color = if (chip.dot == SettingsDot.OK) AriaColors.TextPrimary else color,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(AriaDimens.border, color, RoundedCornerShape(10.dp))
            .padding(horizontal = AriaDimens.s2, vertical = 2.dp),
    )
}

@Composable
private fun ActionButton(button: RowAction.Button) {
    val (fill, textColor, outline) = when (button.weight) {
        ButtonWeight.PRIMARY -> Triple(AriaColors.TextPrimary, AriaColors.Background, Color.Transparent)
        // Black on the accent matches AriaTheme's own onPrimary; 8.2:1 on coast coral, 6.1:1 on the
        // night magenta, where TextPrimary-on-magenta would be ~3:1.
        ButtonWeight.HOT -> Triple(AriaColors.ScanRed, Color.Black, Color.Transparent)
        ButtonWeight.QUIET -> Triple(Color.Transparent, AriaColors.TextPrimary, AriaColors.TextPrimary)
    }
    Box(
        Modifier
            .heightIn(min = ButtonMin)
            .widthIn(min = 96.dp)
            .clip(Corner)
            .background(fill)
            .then(if (outline != Color.Transparent) Modifier.border(AriaDimens.border, outline, Corner) else Modifier)
            .clickable(role = Role.Button) { button.onClick() }
            .padding(horizontal = AriaDimens.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(button.label, style = Label.copy(fontSize = 12.sp), color = textColor, maxLines = 1)
    }
}

// --- Icons -----------------------------------------------------------------
// No icon library in this project (material-icons is not a dependency and one icon family would
// add ~tens of MB of classes for seven glyphs), so these are 24-unit stroked Canvas drawings.

@Composable
private fun CategoryIcon(icon: SettingsIcon, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        val s = Stroke(width = 1.75f * u, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (icon) {
            SettingsIcon.PHONE -> {
                drawRoundRect(color, p(7f, 2.5f), Size(10f * u, 19f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = s)
                drawLine(color, p(10.5f, 18.5f), p(13.5f, 18.5f), 1.75f * u, StrokeCap.Round)
            }
            SettingsIcon.MUSIC -> {
                drawCircle(color, 3.2f * u, p(8f, 18f), style = s)
                drawLine(color, p(11.2f, 18f), p(11.2f, 4.5f), 1.75f * u, StrokeCap.Round)
                drawLine(color, p(11.2f, 4.5f), p(18f, 7f), 1.75f * u, StrokeCap.Round)
            }
            SettingsIcon.ENGINE -> {
                drawRoundRect(color, p(3f, 4f), Size(18f * u, 6.5f * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = s)
                drawRoundRect(color, p(3f, 13.5f), Size(18f * u, 6.5f * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u), style = s)
                drawCircle(color, 0.9f * u, p(7f, 7.25f))
                drawCircle(color, 0.9f * u, p(7f, 16.75f))
            }
            SettingsIcon.COMPANION -> {
                drawCircle(color, 4f * u, p(12f, 8f), style = s)
                drawArc(color, 200f, 140f, false, p(4.5f, 13f), Size(15f * u, 15f * u), style = s)
            }
            SettingsIcon.CAR -> {
                drawRoundRect(color, p(2.5f, 11f), Size(19f * u, 6f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = s)
                drawLine(color, p(5.5f, 11f), p(8f, 6.5f), 1.75f * u, StrokeCap.Round)
                drawLine(color, p(8f, 6.5f), p(16f, 6.5f), 1.75f * u, StrokeCap.Round)
                drawLine(color, p(16f, 6.5f), p(18.5f, 11f), 1.75f * u, StrokeCap.Round)
                drawCircle(color, 1.9f * u, p(7f, 18f), style = s)
                drawCircle(color, 1.9f * u, p(17f, 18f), style = s)
            }
            SettingsIcon.LOOK -> {
                drawCircle(color, 8f * u, p(12f, 12f), style = s)
                drawLine(color, p(12f, 4f), p(12f, 20f), 1.75f * u, StrokeCap.Round)
                drawCircle(color, 2.2f * u, p(8f, 12f))
            }
            SettingsIcon.SYSTEM -> {
                drawCircle(color, 3.2f * u, p(12f, 12f), style = s)
                for (i in 0 until 8) tick(color, u, i * 45f)
            }
        }
    }
}

/** One gear tooth: a short radial line at [degrees] around the 24-unit icon centre. */
private fun DrawScope.tick(color: Color, u: Float, degrees: Float) {
    val r = Math.toRadians(degrees.toDouble())
    val c = Offset(12f * u, 12f * u)
    val dx = kotlin.math.cos(r).toFloat()
    val dy = kotlin.math.sin(r).toFloat()
    drawLine(color, Offset(c.x + dx * 6.4f * u, c.y + dy * 6.4f * u), Offset(c.x + dx * 9.4f * u, c.y + dy * 9.4f * u), 1.75f * u, StrokeCap.Round)
}
