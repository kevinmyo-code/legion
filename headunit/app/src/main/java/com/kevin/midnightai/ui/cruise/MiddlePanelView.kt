package com.kevin.midnightai.ui.cruise

import android.location.Location
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.location.LocationController
import com.kevin.midnightai.ui.DeckBold
import com.kevin.midnightai.ui.DeckMetrics
import com.kevin.midnightai.ui.GaugeReading
import com.kevin.midnightai.ui.deckCard
import com.kevin.midnightai.ui.deckLineColor
import com.kevin.midnightai.ui.deckTileColor
import com.kevin.midnightai.ui.driveTarget
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay

/*
 * The middle panel's look (HU-1 T1): CAR / NAV keys and the GPS readout on top, then the setup and
 * update chips when they show, then the CAR face or the NAV face. State is [MiddlePanel]; the
 * words are [MiddlePanelText]. No animation: a face change is a cut.
 */

/**
 * The CAR / NAV panel. [speedMph] is GPS speed (null = no fresh fix), [gauges] the Cruise gauge
 * picks already filtered ([MiddlePanelText.carGauges]), [storedCodes] the stored-code line or null.
 * [prompts] draws the setup and update chips (they used to sit in the rail).
 */
@Composable
internal fun CruiseMiddlePanel(
    u: Dp,
    m: DeckMetrics,
    location: Location?,
    gauges: List<GaugeReading>,
    storedCodes: String?,
    modifier: Modifier = Modifier,
    prompts: @Composable ColumnScope.() -> Unit = {},
) {
    val state by MiddlePanel.state.collectAsState()
    val host by MiddlePanel.host.collectAsState()
    // One 1 s tick on the monotonic clock drives the fix age and the speed's freshness.
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
    val ageMs = location?.let { (nowNanos - it.elapsedRealtimeNanos) / 1_000_000L }
    val speedMph = MiddlePanelText.speedMph(location?.takeIf { it.hasSpeed() }?.speed, ageMs)
    Column(modifier.deckCard(m)) {
        Row(
            Modifier.fillMaxWidth().padding(u),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentPair(
                u = u,
                left = "CAR",
                right = "NAV",
                rightSelected = state.face == MiddleFace.NAV,
                onLeft = { MiddlePanel.select(MiddleFace.CAR) },
                onRight = { MiddlePanel.select(MiddleFace.NAV) },
                keyMinWidth = u * 10.5f,
            )
            Spacer(Modifier.weight(1f))
            GpsReadout(u, location?.provider, ageMs)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(deckLineColor()))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(u),
            verticalArrangement = Arrangement.spacedBy(u),
        ) {
            prompts()
            when (state.face) {
                MiddleFace.CAR -> CarFace(
                    u = u,
                    speedMph = speedMph,
                    gauges = gauges,
                    storedCodes = storedCodes,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                MiddleFace.NAV -> host.Content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** "GPS from phone / fix 1 s ago": source over age. */
@Composable
private fun GpsReadout(u: Dp, provider: String?, ageMs: Long?) {
    val gps = MiddlePanelText.gps(provider, ageMs, LocationController.BEACON_PROVIDER)
    val size = uSp(u, 1.5f, 11.sp)
    Column(horizontalAlignment = Alignment.End) {
        Text(
            gps.source,
            style = AriaType.neonLabel.copy(fontSize = size, letterSpacing = 0.3.sp),
            color = AriaColors.accentText(AriaColors.NeonCyan),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
        Text(
            gps.age,
            style = AriaType.neonLabel.copy(fontSize = size, letterSpacing = 0.3.sp),
            color = if (gps.stale) AriaColors.accentText(AriaColors.Amber) else AriaColors.TextDim,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Big GPS speed, the driver's Cruise gauges as 2 x 2 cards, and the stored-code line. Scrolls by
 * hand if the setup and update chips both show on a short frame, so nothing is clipped.
 */
@Composable
private fun CarFace(
    u: Dp,
    speedMph: Int?,
    gauges: List<GaugeReading>,
    storedCodes: String?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(u)) {
        Row(verticalAlignment = Alignment.Bottom) {
            val big = with(density) { (u * 11.5f).toSp() }
            Text(
                speedMph?.toString() ?: "––",
                style = AriaType.readout.copy(fontSize = big, lineHeight = big, letterSpacing = 0.sp, fontWeight = DeckBold),
                color = AriaColors.accentText(AriaColors.Amber),
                maxLines = 1,
            )
            Spacer(Modifier.width(u))
            Text(
                "MPH",
                style = AriaType.neonLabel.copy(fontSize = uSp(u, 2.25f, 14.sp), letterSpacing = 1.sp),
                color = AriaColors.TextDim,
                modifier = Modifier.padding(bottom = u),
            )
        }
        if (gauges.isEmpty()) {
            Text(
                "No gauges picked. Settings, Look, Gauges.",
                style = AriaType.readout.copy(fontSize = uSp(u, 1.6f, 12.sp), letterSpacing = 0.sp),
                color = AriaColors.TextDim,
            )
        }
        gauges.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u)) {
                pair.forEach { g -> GaugeCard(u, g, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        storedCodes?.let { line ->
            val shape = RoundedCornerShape(u * 1.5f)
            Text(
                line,
                style = AriaType.readout.copy(fontSize = uSp(u, 1.75f, 12.sp), letterSpacing = 0.sp),
                color = AriaColors.accentText(AriaColors.Amber),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, AriaColors.Amber, shape)
                    .padding(horizontal = u * 1.5f, vertical = u * 1.2f),
            )
        }
    }
}

@Composable
private fun GaugeCard(u: Dp, g: GaugeReading, modifier: Modifier) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(u * 1.5f)
    Column(
        modifier
            .clip(shape)
            .background(deckTileColor())
            .border(1.dp, deckLineColor(), shape)
            .padding(horizontal = u * 1.5f, vertical = u * 1.2f),
    ) {
        Text(
            g.label,
            style = AriaType.neonLabel.copy(fontSize = uSp(u, 1.5f, 11.sp), letterSpacing = 1.sp),
            color = AriaColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            g.value,
            style = AriaType.readout.copy(fontSize = with(density) { (u * 3.5f).toSp() }, letterSpacing = 0.sp, fontWeight = DeckBold),
            color = AriaColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Two keys side by side, the right one or the left one selected (CAR / NAV here, NOW / LISTS on
 * the music card). Selected = text-colour fill with panel ink, the same idiom as the PHONE /
 * SPOTIFY switch. Every key at least the drive touch size; a tap is a cut, no animation.
 */
@Composable
internal fun SegmentPair(
    u: Dp,
    left: String,
    right: String,
    rightSelected: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    keyMinWidth: Dp,
    labelSize: TextUnit = uSp(u, 1.75f, 12.sp),
) {
    val shape = RoundedCornerShape(u * 1.5f)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, deckLineColor(), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentKey(left, !rightSelected, keyMinWidth, labelSize, onLeft)
        SegmentKey(right, rightSelected, keyMinWidth, labelSize, onRight)
    }
}

@Composable
private fun SegmentKey(label: String, selected: Boolean, minWidth: Dp, size: TextUnit, onClick: () -> Unit) {
    Box(
        Modifier
            .driveTarget()
            .widthIn(min = minWidth)
            .background(if (selected) AriaColors.TextPrimary else Color.Transparent)
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AriaType.neonLabel.copy(fontSize = size, letterSpacing = 1.sp, fontWeight = DeckBold),
            color = if (selected) AriaColors.Panel else AriaColors.TextPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [factor] u as sp, never below [floor] (glanceable at arm's length). */
@Composable
internal fun uSp(u: Dp, factor: Float, floor: TextUnit): TextUnit {
    val v = with(LocalDensity.current) { (u * factor).toSp() }
    return if (v.value < floor.value) floor else v
}

/** The NAV face until navigation lands: says so plainly. T5 replaces it through [MiddlePanel.install]. */
object NoTripNavHost : NavPanelHost {
    @Composable
    override fun Content(modifier: Modifier) {
        Column(
            modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                MiddlePanelText.NAV_PLACEHOLDER_TITLE,
                style = AriaType.readout.copy(fontSize = 22.sp, letterSpacing = 0.sp, fontWeight = DeckBold),
                color = AriaColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                MiddlePanelText.NAV_PLACEHOLDER_LINE,
                style = AriaType.readout.copy(fontSize = 15.sp, letterSpacing = 0.sp),
                color = AriaColors.TextDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}
