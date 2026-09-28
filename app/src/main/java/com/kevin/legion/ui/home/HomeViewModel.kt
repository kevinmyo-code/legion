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
import com.kevin.legion.meals.MealController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.AgendaSource
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
 * Android restarts it). `CarDatabase.getDatabase` itself is the one call this function does not
 * guard individually - every other caller in the app (`MainActivity.onResume`, `CalendarScreen`)
 * already treats it as infallible, and guarding it here alone would not change what happens if it
 * really did throw: every reader below needs it.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = loadState(getApplication())
        }
    }
}

private suspend fun loadState(context: Context): HomeUiState {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = LocalDate.now(zone)
    val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndExclusive = dayStart + DAY_MS
    val lookbackStart = dayStart - OVERDUE_LOOKBACK_DAYS * DAY_MS
    val db = CarDatabase.getDatabase(context)

    val calendar = loadCalendar(context, db, zone, now, dayStart, dayEndExclusive, lookbackStart)
    val checklistCount = try {
        ChecklistController.allChecklists(context).size
    } catch (e: Exception) {
        0
    }
    val budget = try {
        LedgerController.budgetVsActual(context, LedgerEntity.US, YearMonth.now())
    } catch (e: Exception) {
        null
    }
    val (mealGap, hasMealTarget) = try {
        MealController.dayGap(context, now) to (db.mealTargetDao().currentTarget(dayStartEpoch(now)) != null)
    } catch (e: Exception) {
        com.kevin.legion.meals.DailyMealGap.NotLogged to false
    }
    val (maintenanceRows, maintenanceUnknownCount) = try {
        val vehicle = VehicleController.currentVehicle(context)
        val currentMileage = VehicleController.currentMileage(vehicle)
        val items = FleetEngineStore.getForVehicle(context, vehicle.obdMac)
        buildDueRows(items, currentMileage, vehicle.odometerBaseline == 0, now) to
            items.count { VehicleController.isUnknown(it) }
    } catch (e: Exception) {
        emptyList<com.kevin.legion.ui.fleet.DueRowView>() to 0
    }
    val voiceNotesCount = try {
        VoiceNoteController.listNotes(context).size
    } catch (e: Exception) {
        0
    }
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
        budget = budget,
        mealGap = mealGap,
        hasMealTarget = hasMealTarget,
        maintenanceRows = maintenanceRows,
        maintenanceUnknownCount = maintenanceUnknownCount,
        voiceNotesCount = voiceNotesCount,
    )
}

private data class CalendarReading(val nextLine: String, val chips: TodayChips)

/**
 * The today card's calendar half - the SAME reads `CalendarScreen`'s own day view makes
 * ([activeByKindInLocalWindow], [NotesController.allItems], [buildInboxRows]), so the card and the
 * calendar can never disagree (ticket's own instruction). A failure anywhere in this block reads as
 * one worded chip ("Couldn't read the calendar", `caution`) - never a silent "Nothing due", which
 * would tell Kevin he is free when the app simply could not see.
 */
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
