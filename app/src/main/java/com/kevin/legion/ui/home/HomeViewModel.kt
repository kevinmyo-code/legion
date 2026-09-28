package com.kevin.legion.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.backend.EventKind
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.location.AirNow
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MealController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.fleet.buildDueRows
import com.kevin.legion.ui.notes.InboxRowView
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.formatDateTime
import com.kevin.legion.ui.notes.toAppointmentEvent
import com.kevin.legion.ui.weatherLine
import com.kevin.legion.util.documentDateCompact
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.voice.VoiceNoteController
import com.kevin.legion.weather.WeatherController
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val OVERDUE_LOOKBACK_DAYS = 30L

/**
 * HOME's own ViewModel (home-launcher ticket 03, CLAUDE.md sec 8 - `AndroidViewModel`, no Hilt yet
 * per that section's own migration order). `refresh()` is called on `ON_RESUME` from [HomeScreen];
 * everything it reads is the same controllers the retired `HomeMeterBands`/`ui/MeterReadings.kt`
 * read, just reshaped into [HomeUiState] instead of a `MetersUiState` a Composable rendered
 * directly.
 *
 * **HOME must never crash (ADR 0050).** Each domain's reads below sit inside its own `try`/`catch` -
 * a throwing weather call must not also blank the money tile, and a throwing ledger read must not
 * strand the whole home screen (a crash here now leaves the phone with no home screen at all until
 * Android restarts it). **[refresh] itself is wrapped too (audit finding 4)**: [CarDatabase.getDatabase]
 * sat outside every one of [loadState]'s own guards, in a `launch` block with no handler, so a
 * throw there (or anywhere else this function does not individually catch) would have crashed the
 * coroutine and left [HomeUiState] stuck at `loading = true` forever - a blank grid, exactly what
 * ADR 0050 forbids. A throw escaping [loadState] now lands on [crashedHomeUiState] instead: every
 * tile's own failed wording, not a stranded loading spinner.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // ADR 0050: HOME must never crash - loadState spans CarDatabase.getDatabase plus every
    // controller it wires (audit finding 4), and any of them throwing must still land on
    // crashedHomeUiState below, whatever exception type it happened to be.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now(ZoneId.systemDefault())
            _state.value = try {
                loadState(getApplication())
            } catch (e: Exception) {
                crashedHomeUiState(today)
            }
        }
    }
}

/** Every tile's own failed wording, today's date still shown (a `LocalDate.now()` call, no
 * database involved) - what [HomeViewModel.refresh] falls back to when [loadState] itself throws
 * (audit finding 4), rather than leaving [HomeUiState.loading] stuck at `true`. */
private fun crashedHomeUiState(today: LocalDate): HomeUiState {
    val fullStyle = java.time.format.TextStyle.FULL
    val locale = java.util.Locale.ENGLISH
    return HomeUiState(
        loading = false,
        weekdayLabel = today.dayOfWeek.getDisplayName(fullStyle, locale),
        dateLabel = today.month.getDisplayName(fullStyle, locale) + " " + today.dayOfMonth,
        weatherText = weatherLine(null),
        weatherIconRes = weatherIconRes(null),
        areaAqiLine = "Couldn't check the area.",
        nextLine = "Couldn't read the calendar",
        chips = buildTodayChips(0, 0, calendarReadFailed = true),
        checklistCount = 0,
        listsFailed = true,
        budget = null,
        moneyFailed = true,
        mealGap = DailyMealGap.NotLogged,
        hasMealTarget = false,
        bodyFailed = true,
        maintenanceRows = emptyList(),
        maintenanceUnknownCount = 0,
        fleetFailed = true,
        voiceNotesCount = 0,
        recordingsFailed = true,
    )
}

// ADR 0050: HOME must never crash - the weather and area/AQI reads below are caught broadly on
// purpose, so a location or network failure never takes the whole today card down with it.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun loadState(context: Context): HomeUiState {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = LocalDate.now(zone)
    val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndExclusive = dayStart + DAY_MS
    val lookbackStart = dayStart - OVERDUE_LOOKBACK_DAYS * DAY_MS
    val db = CarDatabase.getDatabase(context)

    val calendar = loadCalendar(context, db, zone, now, dayStart, dayEndExclusive, lookbackStart)
    val (weatherText, weatherIconResId) = try {
        val info = WeatherController.refresh()
        weatherLine(info) to weatherIconRes(info?.description)
    } catch (e: Exception) {
        weatherLine(null) to weatherIconRes(null)
    }
    val areaAqi = try {
        when (val readout = LiveToolbox.resolveCurrentLocation(context)) {
            is LiveToolbox.LocationReadout.Available -> areaAqiLine(readout, AirNow.current(readout.lat, readout.lon))
            else -> areaAqiLine(readout, null)
        }
    } catch (e: Exception) {
        "Couldn't check the area."
    }

    return assembleHomeState(
        today = today,
        calendar = calendar,
        weatherText = weatherText,
        weatherIconResId = weatherIconResId,
        areaAqi = areaAqi,
        readChecklistCount = { ChecklistController.allChecklists(context).size },
        readBudget = { LedgerController.budgetVsActual(context, LedgerEntity.US, YearMonth.now()) },
        readMealGap = {
            MealController.dayGap(context, now) to (db.mealTargetDao().currentTarget(dayStartEpoch(now)) != null)
        },
        readMaintenance = {
            val vehicle = VehicleController.currentVehicle(context)
            val currentMileage = VehicleController.currentMileage(vehicle)
            val items = FleetEngineStore.getForVehicle(context, vehicle.obdMac)
            buildDueRows(items, currentMileage, vehicle.odometerBaseline == 0, now) to
                items.count { VehicleController.isUnknown(it) }
        },
        readVoiceNotesCount = { VoiceNoteController.listNotes(context).size },
    )
}

/**
 * The assembly step audit finding 1 asked for: every domain read that can throw arrives as an
 * injected suspend lambda, so [HomeViewModelTest] can feed a throwing fake straight in and assert
 * on [HomeUiState] without a `Context`/Robolectric/Room round trip. [loadState] wires the real
 * controllers above; this function owns nothing but the try/catch-and-word step for each one -
 * a thrown read gets its own `*Failed` flag and worded tile status, never the same value a
 * genuinely empty read produces (CLAUDE.md sec 1's "unreadable and empty are different sentences").
 */
// ADR 0050: HOME must never crash - each read is caught broadly and worded, on purpose, never
// left to whatever specific exception type a given controller happens to throw.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal suspend fun assembleHomeState(
    today: LocalDate,
    calendar: CalendarReading,
    weatherText: String,
    weatherIconResId: Int,
    areaAqi: String,
    readChecklistCount: suspend () -> Int,
    readBudget: suspend () -> com.kevin.legion.ledger.BudgetVsActual?,
    readMealGap: suspend () -> Pair<DailyMealGap, Boolean>,
    readMaintenance: suspend () -> Pair<List<DueRowView>, Int>,
    readVoiceNotesCount: suspend () -> Int,
): HomeUiState {
    val (checklistCount, listsFailed) = try {
        readChecklistCount() to false
    } catch (e: Exception) {
        0 to true
    }
    val (budget, moneyFailed) = try {
        readBudget() to false
    } catch (e: Exception) {
        null to true
    }
    val (mealPair, bodyFailed) = try {
        readMealGap() to false
    } catch (e: Exception) {
        (DailyMealGap.NotLogged to false) to true
    }
    val (mealGap, hasMealTarget) = mealPair
    val (maintenancePair, fleetFailed) = try {
        readMaintenance() to false
    } catch (e: Exception) {
        (emptyList<DueRowView>() to 0) to true
    }
    val (maintenanceRows, maintenanceUnknownCount) = maintenancePair
    val (voiceNotesCount, recordingsFailed) = try {
        readVoiceNotesCount() to false
    } catch (e: Exception) {
        0 to true
    }

    val fullStyle = java.time.format.TextStyle.FULL
    val locale = java.util.Locale.ENGLISH
    return HomeUiState(
        loading = false,
        weekdayLabel = today.dayOfWeek.getDisplayName(fullStyle, locale),
        dateLabel = today.month.getDisplayName(fullStyle, locale) + " " + today.dayOfMonth,
        weatherText = weatherText,
        weatherIconRes = weatherIconResId,
        areaAqiLine = areaAqi,
        nextLine = calendar.nextLine,
        chips = calendar.chips,
        checklistCount = checklistCount,
        listsFailed = listsFailed,
        budget = budget,
        moneyFailed = moneyFailed,
        mealGap = mealGap,
        hasMealTarget = hasMealTarget,
        bodyFailed = bodyFailed,
        maintenanceRows = maintenanceRows,
        maintenanceUnknownCount = maintenanceUnknownCount,
        fleetFailed = fleetFailed,
        voiceNotesCount = voiceNotesCount,
        recordingsFailed = recordingsFailed,
    )
}

internal data class CalendarReading(val nextLine: String, val chips: TodayChips)

/**
 * The today card's calendar half - the SAME reads `CalendarScreen`'s own day view makes
 * ([activeByKindInLocalWindow], [NotesController.allItems], [buildInboxRows]), so the card and the
 * calendar can never disagree (ticket's own instruction). A failure anywhere in this block reads as
 * one worded chip ("Couldn't read the calendar", `caution`) - never a silent "Nothing due", which
 * would tell Kevin he is free when the app simply could not see.
 */
// ADR 0050: HOME must never crash - this block spans three DAO/controller reads and any of them
// throwing must still land on the worded fallback below, whatever exception type it threw.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun loadCalendar(
    context: Context,
    db: CarDatabase,
    zone: ZoneId,
    now: Long,
    dayStart: Long,
    dayEndExclusive: Long,
    lookbackStart: Long,
): CalendarReading {
    return try {
        val items = NotesController.allItems(context)
        val tasksToday = db.eventDao().activeByKindInLocalWindow(EventKind.TASK, dayStart, dayEndExclusive - 1, zone)
        val scheduleToday =
            db.eventDao().activeByKindInLocalWindow(EventKind.EVENT, dayStart, dayEndExclusive - 1, zone)
        val tasksOverdue = db.eventDao().activeByKindInLocalWindow(EventKind.TASK, lookbackStart, dayStart - 1, zone)

        // Same [buildInboxRows] merge, same day window, `CalendarScreen`'s own YET TO DO section -
        // reused rather than re-derived, so the card and the calendar's day view read the exact
        // same rows.
        val todayRows = buildInboxRows(items, now, tasksToday.map(Event::toAppointmentEvent))
            .filter { it.instantMs != null && it.instantMs >= dayStart && it.instantMs < dayEndExclusive }
        val dueTodayCount = todayRows.count { !it.done }

        // SCHEDULE - built the same plain way `CalendarScreen`'s own SCHEDULE section is, never
        // through [buildInboxRows] (that merge is for tickable rows; an event has no completion
        // state - one-today ticket 08, "events are not todos").
        val scheduleRows = scheduleToday.sortedBy { it.startsAt ?: 0L }.map { event ->
            InboxRowView(
                id = event.id,
                text = event.title,
                done = false,
                tickable = false,
                recurring = false,
                dateLabel = event.startsAt?.let { at ->
                    if (event.allDay) documentDateCompact(at) else formatDateTime(at)
                },
                overdue = false,
                placeLabel = null,
                exactDowngraded = false,
                source = AgendaSource.GOOGLE,
                instantMs = event.startsAt,
            )
        }

        // OVERDUE - the same merge, widened to a 30-day lookback ending yesterday, then filtered to
        // [InboxRowView.overdue] rows only (past-due, open, non-recurring - that field's own doc
        // comment). Reminders can be dated anywhere in the past, so [items] (unwindowed) is reused
        // rather than re-queried; only the TASK half needs its own widened query.
        val overdueRows = buildInboxRows(items, now, tasksOverdue.map(Event::toAppointmentEvent))
            .filter { it.overdue && it.instantMs != null && it.instantMs < dayStart && it.instantMs >= lookbackStart }

        CalendarReading(
            nextLine = nextThingLine(now, scheduleRows, todayRows),
            chips = buildTodayChips(dueTodayCount, overdueRows.size, calendarReadFailed = false),
        )
    } catch (e: Exception) {
        CalendarReading(
            nextLine = "Couldn't read the calendar",
            chips = buildTodayChips(0, 0, calendarReadFailed = true),
        )
    }
}
