package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.R
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.NowPlayingInfo
import com.kevin.legion.ui.media.MediaTransport
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.voice.VoiceNoteController
import com.kevin.legion.voice.VoiceNoteRecordingState
import com.kevin.legion.voice.VoiceNoteStartResult
import kotlinx.coroutines.launch

/** The A25's content box under the status line/talk bar (ticket's own figure) is the "fits" case;
 * below this, [HomeContent] falls back to a vertical scroll rather than clipping a tile. */
private val GRID_FITS_MIN_HEIGHT = 560.dp

/** Every navigation HOME's grid/rows reach - one bag so [HomeScreen] (stateful) and [HomeContent]
 * (stateless, Roborazzi-renderable with fakes) share one parameter shape. */
data class HomeCallbacks(
    val onOpenCalendar: () -> Unit,
    val onOpenLists: () -> Unit,
    val onOpenMoney: () -> Unit,
    val onOpenBody: () -> Unit,
    val onOpenFleet: () -> Unit,
    val onOpenRecordings: () -> Unit,
    val onOpenNews: () -> Unit,
    val onOpenReports: () -> Unit,
    val onOpenMedia: () -> Unit,
    val onStartRecording: () -> Unit,
    val onStopRecording: () -> Unit,
)

/**
 * HOME, the launcher's landing screen (home-launcher ticket 03, ADR 0050/0051). Stateful wrapper:
 * owns [HomeViewModel] and the two live flows [HomeContent] cannot own itself
 * ([VoiceNoteController.recordingState], [NowPlayingController.state]) - both tick on their own
 * schedule, independent of [HomeViewModel.refresh]'s `ON_RESUME` cadence, same split the retired
 * `ui/HomeMeterBands.kt` already drew between its own polled `MetersUiState` and these two live
 * reads.
 */
@Composable
fun HomeScreen(
    onOpenCalendar: () -> Unit,
    onOpenLists: () -> Unit,
    onOpenMoney: () -> Unit,
    onOpenBody: () -> Unit,
    onOpenFleet: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenMedia: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: HomeViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val recordingState by VoiceNoteController.recordingState(context).collectAsStateWithLifecycle()
    val recording = recordingState is VoiceNoteRecordingState.Recording
    var recordRefusal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { NowPlayingController.init(context) }
    val nowPlaying by NowPlayingController.state.collectAsStateWithLifecycle()

    HomeContent(
        state = state,
        recording = recording,
        recordRefusal = recordRefusal,
        nowPlaying = nowPlaying,
        callbacks = HomeCallbacks(
            onOpenCalendar = onOpenCalendar,
            onOpenLists = onOpenLists,
            onOpenMoney = onOpenMoney,
            onOpenBody = onOpenBody,
            onOpenFleet = onOpenFleet,
            onOpenRecordings = onOpenRecordings,
            onOpenNews = onOpenNews,
            onOpenReports = onOpenReports,
            onOpenMedia = onOpenMedia,
            onStartRecording = {
                scope.launch {
                    when (val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO)) {
                        is VoiceNoteStartResult.Started -> recordRefusal = null
                        is VoiceNoteStartResult.Refused -> recordRefusal = started.reason
                    }
                }
            },
            onStopRecording = {
                recordRefusal = null
                scope.launch { VoiceNoteController.stop(context) }
            },
        ),
    )
}

/**
 * The stateless render (Roborazzi's own entry point, fakes for [state]/[nowPlaying]). Never
 * scrolls or clips in the A25's content box - [BoxWithConstraints] picks the fixed-grid layout
 * above [GRID_FITS_MIN_HEIGHT] and a scrolling one below it (a small phone, a big font scale),
 * per this ticket's own "Fit, and the fallback" section.
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    recording: Boolean,
    recordRefusal: String?,
    nowPlaying: NowPlayingInfo?,
    callbacks: HomeCallbacks,
) {
    SoftTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(SoftColors.ground)) {
            val fitsGrid = maxHeight >= GRID_FITS_MIN_HEIGHT
            val outer = if (fitsGrid) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            }
            Column(outer.padding(12.dp)) {
                TodayCard(state = state, onClick = callbacks.onOpenCalendar)
                Spacer(Modifier.height(10.dp))
                TileGrid(
                    state = state,
                    recording = recording,
                    recordRefusal = recordRefusal,
                    callbacks = callbacks,
                    fillRemaining = fitsGrid,
                    modifier = if (fitsGrid) Modifier.weight(1f) else Modifier,
                )
                NowPlayingRow(nowPlaying = nowPlaying, onOpenMedia = callbacks.onOpenMedia)
            }
        }
    }
}

@Composable
private fun TodayCard(state: HomeUiState, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SoftColors.card, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(state.weekdayLabel, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
                Text(state.dateLabel, style = MaterialTheme.typography.headlineSmall, color = SoftColors.text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsIcon(state.weatherIconRes, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
                Spacer(Modifier.width(4.dp))
                Text(state.weatherText, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            }
        }
        Text(
            state.areaAqiLine,
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            state.nextLine,
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayChip(text = state.chips.dueTodayText, alert = state.chips.readFailed)
            state.chips.overdueText?.let { TodayChip(text = it, alert = true) }
        }
    }
}

@Composable
private fun TodayChip(text: String, alert: Boolean) {
    Box(
        Modifier
            .background(if (alert) SoftColors.alertContainer else SoftColors.cardHigh, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (alert) SoftColors.onAlert else SoftColors.text2,
        )
    }
}

/** The 2 x 4 grid, order fixed by ticket 01: Calendar, Lists, Money, Body, Fleet, Recordings,
 * News, Reports. [fillRemaining] picks weighted rows (fits the content box, no scroll) or
 * natural-height rows (the scrolling fallback) - see [HomeContent]'s own doc comment. */
@Composable
private fun TileGrid(
    state: HomeUiState,
    recording: Boolean,
    recordRefusal: String?,
    callbacks: HomeCallbacks,
    fillRemaining: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Declared INSIDE this Column's own body so `.weight(1f)` resolves against ITS
        // ColumnScope receiver, not the caller's - a per-row weight only means something relative
        // to the rows sharing this Column.
        val rowModifier = if (fillRemaining) {
            Modifier.fillMaxWidth().weight(1f)
        } else {
            Modifier.fillMaxWidth().height(96.dp)
        }

        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.CALENDAR,
                iconRes = R.drawable.ms_calendar_month,
                title = "Calendar",
                status = calendarTileStatus(state.chips),
                onClick = callbacks.onOpenCalendar,
            )
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.LISTS,
                iconRes = R.drawable.ms_checklist,
                title = "Lists",
                status = listsTileStatus(state.checklistCount, failed = state.listsFailed),
                onClick = callbacks.onOpenLists,
            )
        }
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.MONEY,
                iconRes = R.drawable.ms_account_balance_wallet,
                title = "Money",
                status = moneyTileStatus(state.budget, failed = state.moneyFailed),
                disclosure = moneyTileDisclosure(state.budget),
                onClick = callbacks.onOpenMoney,
            )
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.BODY,
                iconRes = R.drawable.ms_monitor_heart,
                title = "Body",
                status = bodyTileStatus(state.mealGap, state.hasMealTarget, failed = state.bodyFailed),
                disclosure = bodyDisclosureLine(state.mealGap),
                onClick = callbacks.onOpenBody,
            )
        }
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.FLEET,
                iconRes = R.drawable.ms_directions_car,
                title = "Fleet",
                status = fleetTileStatus(state.maintenanceRows, state.maintenanceUnknownCount, failed = state.fleetFailed),
                onClick = callbacks.onOpenFleet,
            )
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.RECORDINGS,
                iconRes = R.drawable.ms_graphic_eq,
                title = "Recordings",
                status = recordingsTileStatus(state.voiceNotesCount, recording, failed = state.recordingsFailed),
                disclosure = recordRefusal,
                onClick = callbacks.onOpenRecordings,
                trailing = {
                    RecordButton(
                        recording = recording,
                        onStart = callbacks.onStartRecording,
                        onStop = callbacks.onStopRecording,
                    )
                },
            )
        }
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.NEWS,
                iconRes = R.drawable.ms_newspaper,
                title = "News",
                status = TileStatus("Feeds and newsletters"),
                onClick = callbacks.onOpenNews,
            )
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.REPORTS,
                iconRes = R.drawable.ms_bar_chart,
                title = "Reports",
                status = TileStatus("Spending and groceries"),
                onClick = callbacks.onOpenReports,
            )
        }
    }
}

/**
 * One tile: a header row (32dp [AreaAccent] icon chip, title), then the status below at full tile
 * width, up to 2 lines (ticket's own "Tile layout, computed to fit"). [disclosure] is the caution-
 * toned second line a trust disclosure moves with (CLAUDE.md §4 rules 5/7) - `null` renders
 * nothing, never an empty line. [trailing] is Recordings' own record button, on the status row's
 * right edge.
 */
@Composable
private fun TileCard(
    modifier: Modifier,
    accent: AreaAccent,
    iconRes: Int,
    title: String,
    status: TileStatus,
    onClick: () -> Unit,
    disclosure: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .background(SoftColors.card, MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).background(accent.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(iconRes, contentDescription = null, tint = accent.onContainer, size = 18.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.alert) SoftColors.onAlert else SoftColors.text2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                disclosure?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftColors.caution,
                        // Never truncated: a trust disclosure is not furniture (CLAUDE.md sec 4
                        // rules 5 and 7). It wraps as far as it needs to.
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * Recordings' own one-tap record control (ticket's own "Record button" section) - the same
 * [VoiceNoteController.start]/[stop] calls the retired `HomeMeterBands`'s `RecordControlRow` made,
 * restyled as a 48dp dot/stop toggle rather than a text button. A [VoiceNoteStartResult.Refused]
 * reason renders on the TILE itself, in `caution`, where the tap happened - never a toast
 * ([HomeScreen]'s own `recordRefusal` state, rendered through [TileCard]'s `disclosure` slot).
 */
@Composable
private fun RecordButton(recording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(if (recording) SoftColors.recordingContainer else Color.Transparent, CircleShape)
            .clickable(onClick = { if (recording) onStop() else onStart() }),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(14.dp).background(SoftColors.text, RoundedCornerShape(3.dp)))
        } else {
            Box(Modifier.size(14.dp).background(SoftColors.recordDot, CircleShape))
        }
    }
}

/**
 * Above the talk bar when music is playing (ticket's own "Now playing" section) - title, artist,
 * a play/pause control, tap-anywhere-else opens [onOpenMedia]. Reads [NowPlayingController] and
 * calls [MediaTransport] the same way the retired `MediaMiniBar` did (that composable's
 * `DeckButton` restyled as an icon here); `null` renders nothing at all, matching its early return.
 */
@Composable
private fun NowPlayingRow(nowPlaying: NowPlayingInfo?, onOpenMedia: () -> Unit) {
    val info = nowPlaying ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(SoftColors.cardHigh, MaterialTheme.shapes.medium)
            .clickable(onClick = onOpenMedia)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                info.title,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                info.artist,
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .background(SoftColors.primaryContainer, CircleShape)
                .clickable(enabled = !working) {
                    working = true
                    scope.launch {
                        val action = if (info.isPlaying) MediaTransport.Action.PAUSE else MediaTransport.Action.PLAY
                        MediaTransport.run(context, action)
                        working = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (info.isPlaying) "II" else "▶",
                style = MaterialTheme.typography.labelLarge,
                color = SoftColors.onPrimaryContainer,
            )
        }
    }
}
