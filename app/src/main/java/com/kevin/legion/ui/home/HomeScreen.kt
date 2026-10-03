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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.R
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.NowPlayingInfo
import com.kevin.legion.ui.apps.AppDrawerCache
import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DockPins
import com.kevin.legion.ui.apps.CategoryPicksStore
import com.kevin.legion.ui.apps.DockPinsStore
import com.kevin.legion.ui.apps.HomeCategory
import com.kevin.legion.ui.apps.launchDrawerApp
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
 * below this, [HomeContent] falls back to a vertical scroll rather than clipping a tile.
 * **620dp, was 560dp (ticket 07):** the category row added about 62dp of fixed chrome under the
 * grid, so the box height at which the grid still gets the ~300dp its disclosures need moved up by
 * the same amount. The recorded 384 x 636 shots sit just above it and are the check. */
private val GRID_FITS_MIN_HEIGHT = 620.dp

/** Extra box height the now-playing row needs before the fixed grid still fits. With music playing
 * the 384 x 636 box does NOT fit it (home-categories-now-playing.png showed Calendar, News and Reports
 * clipped when it was tried), so HOME scrolls for as long as something is playing. */
private val NOW_PLAYING_RESERVE = 64.dp

// Tile-row heights, see [TileGrid]. 2026-10-02: the Money row (its bars and trust disclosures) takes
// WHATEVER the other rows do not need, instead of a weight. Calendar, Fleet and News hold a header and
// one status line (62dp: 6 + 28 + 4 + 18 + 6); Fleet holds Recordings' two-line mic refusal only
// when one is showing (96dp). Weights tuned by eye let a tall dock clip Reports' subtitle.
private val ROW_COMPACT_HEIGHT = 62.dp
private val ROW_REFUSAL_HEIGHT = 96.dp

/** The scrolling fallback has height to spare: enough for 3 bars, both lines and a two-line disclosure. */
private val MONEY_ROW_SCROLL_HEIGHT = 168.dp

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

    // Home-launcher ticket 06's own dock - pins are read fresh on every resume (a pin/unpin made
    // from Apps must show up here without a process restart), and against AppDrawerCache's own
    // PEEK, never a fresh LauncherApps query of its own (ticket's own "Boundaries": the dock must
    // not add a second slow query to HOME's first frame - AppDrawerCache.warm already primed this
    // at app start, in MainActivity).
    var pins by remember { mutableStateOf(DockPinsStore.read(context)) }
    var drawerSnapshot by remember { mutableStateOf(AppDrawerCache.peek()) }
    var dockMessage by remember { mutableStateOf<String?>(null) }
    // Set when AppDrawerCache.refresh threw or read no apps at all: said once, in words, instead of
    // every slot claiming "Not installed".
    var appsUnreadable by remember { mutableStateOf(false) }
    // Ticket 07's category row: picks per category, read on every resume like the dock's own, so a
    // change made elsewhere never shows stale. Same peek-only posture - no second LauncherApps read.
    var categoryPicks by remember { mutableStateOf(CategoryPicksStore.readAll(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        pins = DockPinsStore.read(context)
        categoryPicks = CategoryPicksStore.readAll(context)
        drawerSnapshot = AppDrawerCache.peek()
        // Warm the cache ourselves: peek() is null on a cold start until something refreshes it, and
        // MainActivity's warm may not have finished. Same refresh AppsScreen uses; cheap when warm.
        scope.launch {
            val fresh = runCatching { AppDrawerCache.refresh(context) }.getOrNull()
            if (fresh != null && fresh.apps.isNotEmpty()) {
                drawerSnapshot = fresh
                appsUnreadable = false
            } else if (drawerSnapshot == null) {
                appsUnreadable = true
            }
        }
    }

    val recordingState by VoiceNoteController.recordingState(context).collectAsStateWithLifecycle()
    val recording = recordingState is VoiceNoteRecordingState.Recording
    var recordRefusal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { NowPlayingController.init(context) }
    val nowPlaying by NowPlayingController.state.collectAsStateWithLifecycle()

    // One launch path for the dock and the category buttons: the drawer's own `launchDrawerApp` and
    // its failure sentence, with a missing app or an unreadable snapshot said in words.
    val launchSlot: (DockSlotUi) -> Unit = { slot ->
        val app = slot.app
        val snapshot = drawerSnapshot
        if (slot.loading || snapshot == null) {
            // Not read yet: do the refresh, then re-resolve this pin against it and launch.
            dockMessage = "Still loading your apps."
            scope.launch {
                val fresh = runCatching { AppDrawerCache.refresh(context) }.getOrNull()
                if (fresh == null || fresh.apps.isEmpty()) {
                    appsUnreadable = true
                    dockMessage = "Couldn't read your apps."
                } else {
                    drawerSnapshot = fresh
                    appsUnreadable = false
                    val resolved = buildDockSlots(listOf(slot.pin), fresh).single()
                    val found = resolved.app
                    dockMessage = if (found == null) {
                        "${resolved.label} is not installed."
                    } else {
                        launchDrawerApp(context, found, fresh)
                    }
                }
            }
        } else {
            dockMessage = if (app == null) {
                "${slot.label} is not installed."
            } else {
                launchDrawerApp(context, app, snapshot)
            }
        }
    }

    HomeContent(
        state = state,
        recording = recording,
        recordRefusal = recordRefusal,
        nowPlaying = nowPlaying,
        dockSlots = buildDockSlots(pins, drawerSnapshot),
        dock = DockCallbacks(
            onLaunch = launchSlot,
            onUnpin = { pin ->
                pins = DockPins.unpin(pins, pin)
                DockPinsStore.write(context, pins)
            },
            onMoveLeft = { pin ->
                pins = DockPins.move(pins, pin, delta = -1)
                DockPinsStore.write(context, pins)
            },
            onMoveRight = { pin ->
                pins = DockPins.move(pins, pin, delta = 1)
                DockPinsStore.write(context, pins)
            },
        ),
        dockMessage = if (appsUnreadable && drawerSnapshot == null) "Couldn't read your apps." else dockMessage,
        categories = HomeCategory.entries.map {
            CategoryUi(it, buildDockSlots(categoryPicks[it].orEmpty(), drawerSnapshot))
        },
        chooserRows = buildChooserRows(drawerSnapshot),
        categoryCallbacks = CategoryCallbacks(
            onLaunch = launchSlot,
            onSave = { category, picks ->
                CategoryPicksStore.write(context, category, picks)
                categoryPicks = categoryPicks + (category to picks)
            },
        ),
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
    dockSlots: List<DockSlotUi> = emptyList(),
    dock: DockCallbacks = DockCallbacks(onLaunch = {}, onUnpin = {}, onMoveLeft = {}, onMoveRight = {}),
    dockMessage: String? = null,
    categories: List<CategoryUi> = emptyList(),
    chooserRows: List<ChooserRow> = emptyList(),
    categoryCallbacks: CategoryCallbacks = CategoryCallbacks(onLaunch = {}, onSave = { _, _ -> }),
) {
    SoftTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(SoftColors.ground)) {
            val fitsGrid = maxHeight >= GRID_FITS_MIN_HEIGHT + if (nowPlaying != null) NOW_PLAYING_RESERVE else 0.dp
            val outer = if (fitsGrid) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            }
            Column(outer.padding(12.dp)) {
                TodayCard(state = state, onClick = callbacks.onOpenCalendar)
                // 6dp, not the original 10dp - ticket 06's own dock takes real height back from
                // this Column's fixed budget ("the tile grid gives it up"), and this 4dp is part of
                // what is given back, alongside TileCard's own tightened padding below.
                Spacer(Modifier.height(6.dp))
                TileGrid(
                    state = state,
                    recording = recording,
                    recordRefusal = recordRefusal,
                    callbacks = callbacks,
                    fillRemaining = fitsGrid,
                    modifier = if (fitsGrid) Modifier.weight(1f) else Modifier,
                )
                // Home-launcher ticket 06: between the tile grid and the talk bar - the talk bar
                // itself (AssistantStrip) is mounted outside this composable as MainActivity's
                // Scaffold bottomBar, so this row being the last thing drawn here already puts it
                // directly above it.
                AppDock(slots = dockSlots, callbacks = dock)
                // Ticket 07: five category buttons as a new row under the pinned dock.
                CategoryRow(categories = categories, chooserRows = chooserRows, callbacks = categoryCallbacks)
                dockMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftColors.caution,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
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
            // 12dp top and bottom, not 16dp: ticket 07's category row takes another ~60dp from the
            // fixed budget, and this card gives some of it back before any tile loses a line.
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            .padding(horizontal = 10.dp, vertical = 2.dp),
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
    // 4dp between rows (was 8, then 6) - the same height-back-to-the-grid reasoning as
    // HomeContent's own tightened Spacer (ticket 06's dock, then ticket 07's category row).
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Declared INSIDE this Column's own body so `.weight(1f)` resolves against ITS
        // ColumnScope receiver, not the caller's - a per-row weight only means something relative
        // to the rows sharing this Column.
        // Rows are NOT equal-height any more (ticket 07). The category row took ~60dp, and at equal
        // weights Money's two-line figure plus its two-line trust disclosure was clipped - a
        // disclosure must never be (CLAUDE.md sec 4 rules 5 and 7). Weight follows what each row can
        // carry: Money/Body hold the disclosures, News/Reports only a one-line label.
        // [growsToContent]: in the scrolling fallback the Money row is tall enough for its bars and disclosure
        // (a fixed height: a SubcomposeLayout inside cannot be intrinsically measured).
        // [fixed]: the row's own height when the grid fills its box; null is the one flexible row (Money).
        fun rowModifier(fixed: Dp?, growsToContent: Boolean = false) = when {
            fillRemaining && fixed != null -> Modifier.fillMaxWidth().height(fixed)
            fillRemaining -> Modifier.fillMaxWidth().weight(1f)
            growsToContent -> Modifier.fillMaxWidth().height(MONEY_ROW_SCROLL_HEIGHT)
            else -> Modifier.fillMaxWidth().height(96.dp)
        }

        Row(rowModifier(ROW_COMPACT_HEIGHT), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        // Bars when the per-category month was read; the old budget figure otherwise (loading, tests).
        val money = if (state.moneyMonth != null) {
            moneyTileModel(state.moneyMonth, state.moneyFailed, state.budget, state.moneySyncLine)
        } else {
            MoneyTileModel(
                moneyTileStatus(state.budget, failed = state.moneyFailed), null, emptyList(), 0,
                moneyTileDisclosure(state.budget, state.moneySyncLine),
            )
        }
        Row(
            rowModifier(null, growsToContent = true),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TileCard(
                modifier = Modifier.weight(1f).fillMaxSize(),
                accent = AreaAccent.MONEY,
                iconRes = R.drawable.ms_account_balance_wallet,
                title = "Money",
                status = money.status,
                disclosure = money.disclosure,
                onClick = callbacks.onOpenMoney,
                content = if (money.bars.isEmpty() && money.currentPeriodLine == null) null else {
                    { MoneyBars(money, disclosureLines = disclosureLineCount(money.disclosure)) }
                },
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
        val fleetRowHeight = if (recordRefusal != null) ROW_REFUSAL_HEIGHT else ROW_COMPACT_HEIGHT
        Row(rowModifier(fleetRowHeight), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(rowModifier(ROW_COMPACT_HEIGHT), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    content: (@Composable () -> Unit)? = null,
) {
    // The card is a Box so [trailing] (Recordings' 48dp record button) can sit at the CARD's top-end
    // corner, beside the title, needing none of the compact row's height (a 48dp control in the
    // status area was squashed to a pill by the 62dp row). Its 14dp dot is centred in the 48dp
    // touch target.
    Box(
        modifier
            .background(SoftColors.card, MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    ) {
    Column(
        Modifier
            .fillMaxSize()
            // 6dp, not the original 12dp - the dock takes real height from the row this card sits
            // in; this gives it back to the STATUS/DISCLOSURE text rather than to padding.
            .padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(accent.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(iconRes, contentDescription = null, tint = accent.onContainer, size = 16.dp)
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
        Spacer(Modifier.height(4.dp))
        // [trailing] (Recordings' own record button, 48dp - taller than any one text line) used to
        // sit in-line beside the status text, forcing this WHOLE area 48dp tall before a single
        // word of the disclosure below it was drawn. Found looking at home-failures.png during
        // ticket 06's own screenshot check: "Microphone permission not granted." was bleeding into
        // the Fleet card below. [trailing] now floats as a `Box` overlay at this area's own top-end
        // corner instead, so it costs no LAYOUT height here - Recordings' own short status text
        // ("0 saved"/"Recording") leaves that corner clear, and the disclosure below gets the
        // card's full remaining height to wrap into, same as every other tile's own disclosure.
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.alert) SoftColors.onAlert else SoftColors.text2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Narrower ONLY for the status line, and only when there is a trailing control
                    // to clear (Recordings alone) - the DISCLOSURE below keeps the card's full
                    // width regardless, since [trailing] never draws that low.
                    modifier = Modifier.fillMaxWidth(if (trailing != null) 0.75f else 1f),
                )
                content?.invoke()
                disclosure?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftColors.caution,
                        // Never truncated: a trust disclosure is not furniture (CLAUDE.md sec 4
                        // rules 5 and 7). It wraps as far as it needs to - full width, even under
                        // [trailing]: narrowing it to clear the button (tried, reverted) pushed it
                        // to a THIRD line the row still could not fit, the exact bleed this whole
                        // rework exists to stop. Full width needs only two lines, and the button's
                        // small 14dp dot (inside its own 48dp touch target) crosses one word on the
                        // first line at most - a minor visual overlap, never a missing line.
                    )
                }
            }
        }
    }
    trailing?.let { Box(Modifier.align(Alignment.TopEnd)) { it() } }
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
