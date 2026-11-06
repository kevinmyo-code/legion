package com.kevin.legion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.kevin.legion.bodycoach.BodyCoachController
import com.kevin.legion.bodycoach.BodyCoachWrites
import com.kevin.legion.bodycoach.EnergyWire
import com.kevin.legion.bodycoach.ProfileWire
import com.kevin.legion.bodycoach.SavedMealWire
import com.kevin.legion.bodycoach.TrendWire
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.ui.body.BodyEnergyCard
import com.kevin.legion.ui.body.BodyMoreList
import com.kevin.legion.ui.body.BodyProfileDialog
import com.kevin.legion.ui.body.BodyQuickLogRow
import com.kevin.legion.ui.body.BodyTodayCard
import com.kevin.legion.ui.body.BodyTrendCard
import com.kevin.legion.ui.body.BodyWeekCard
import com.kevin.legion.ui.body.LogWaistDialog
import com.kevin.legion.ui.body.SavedMealsDialog
import com.kevin.legion.ui.body.TodayIntake
import com.kevin.legion.bodycoach.WorkoutSessionController
import com.kevin.legion.bodycoach.WorkoutTodayWire
import com.kevin.legion.ui.body.BodyWorkoutCard
import com.kevin.legion.ui.body.WorkoutSessionHost
import com.kevin.legion.ui.body.weekSubtitle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.WorkoutSetLog
import com.kevin.legion.data.local.WorkoutSetLogDao
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MealController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.sleep.SleepController
import com.kevin.legion.sleep.SleepGap
import com.kevin.legion.ui.body.BodyAction
import com.kevin.legion.ui.body.BodyDeleteAction
import com.kevin.legion.ui.body.BodyRangeChips
import com.kevin.legion.ui.body.DeleteLogDialog
import com.kevin.legion.ui.body.LogBodyweightDialog
import com.kevin.legion.ui.body.LogMealDialog
import com.kevin.legion.ui.body.LogSleepDialog
import com.kevin.legion.ui.body.SetMealTargetDialog
import com.kevin.legion.ui.body.SetSleepTargetDialog
import com.kevin.legion.ui.body.softSentence
import com.kevin.legion.ui.common.DECK_SPARKLINE_MIN_POINTS
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckLineChart
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckPoint
import com.kevin.legion.ui.common.DeckRange
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.DrilldownHeader
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.ReadingRow
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.ui.common.deckRangeStartMs
import com.kevin.legion.ui.common.deckSparklineHasShape
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.util.shortDate
import com.kevin.legion.workouts.WorkoutController

/**
 * `body` tab, rebuilt for the cyberdeck (ticket 16, per ticket 07's grilled answer). Three
 * MILSPEC panels in a FIXED order - MASS, INTAKE, SLEEP - each a one-glance sparkline readout
 * ([DeckPane] + [DeckSparkline]), never a full chart inline (ticket 07 answer #1:
 * "full-chart-inline declined"). Depth is one tap in: every panel opens an IN-SCREEN drilldown (a
 * full-height [DeckLineChart]/[DeckBarChart] plus the history list that used to live on the module
 * screen directly), following [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]'s "owns its own
 * internal drill-down... rather than adding nav-graph sub-routes with arguments" precedent -
 * [BodyDrilldown] is a plain state var this file swaps on, not a new `LegionRoute`.
 *
 * **Training is the engine's workout now (workouts, 2026-11-06).** The "Today's plan" checklist
 * and its log-a-set entry are gone (Kevin: *"the old workout tickboxes left over from before and
 * its confusing"*): [BodyDrilldown.Workout] is the session tick-off view and "Training history"
 * under More opens the TRAINING drilldown, which still reads the logged sets.
 *
 * **Tiled to the mission-control grammar (ticket 16's BIO build, `.scratch/mission-control/issues/
 * 12-surface-inventories.md`).** MASS stays FULL (this surface's hero - latest reading, trend,
 * sparkline); INTAKE and SLEEP drop from their own FULL panels to a shared HALF-tile row via
 * [com.kevin.legion.ui.common.EqualHeightRow] / [com.kevin.legion.ui.common.HalfTile] - the exact
 * shell HOME's BIO/CRED/FLEET/LOG row already uses, moved to `ui/common/DeckTiles.kt` by that
 * ticket so both files read the same primitive instead of each hand-rolling one. Tap-through is
 * unchanged - each tile still opens the same [BodyDrilldown] its old FULL panel did; only the
 * panel-list SHAPE changed, not the drilldowns.
 *
 * **Header, once**: ticket 03's universal-state rule - Body's rows are ALL [TrustTier.REPORTED]
 * by construction (nothing here is verified against anything external), so that state is said
 * ONCE at the top of the screen (`UPLINK // SELF-REPORT`) rather than tagged on every row, per
 * ticket 16's brief.
 *
 * **Gaps, never zeros** (ticket 07 answer #5, CLAUDE.md §4 rule 6): every sparkline/bar-chart
 * series below is built by [bucketBodyweightDaily]/[bucketMealKcalDaily]/[bucketSleepMinutesDaily]
 * in `BodyGapResolvers.kt`, all of which put a `null` in an unlogged day's slot - the chart kit
 * ([com.kevin.legion.ui.common.DeckCharts.kt]) renders that as a gap by construction, never a
 * zero-height mark. Empty panels/drilldowns are worded (`NOT LOGGED`, `NO READINGS YET`), never a
 * blank space.
 *
 * **No longer read-only (ticket 03, `.scratch/command-center/issues/03-body-writes-by-hand.md`,
 * ADR 0035).** Voice was the ONLY way to write any of these streams until this ticket - a
 * misheard log, a dead socket, or no key at all meant the capability simply did not exist for that
 * moment. Every write affordance below (`+ LOG WEIGHT`/`+ LOG`/`EDIT TARGET`/`DEL`) calls the exact
 * controller function `LiveToolbox`'s matching voice tool dispatches to - see
 * `ui/body/BodyWriteDialogs.kt`'s own doc comment for the full trace. What is still true from the
 * old posture: nothing here re-derives a score, a streak, or a percentage, and every macro figure
 * a controller only estimates is spoken by the controller's own return string as an estimate, not
 * re-labelled here.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill: [BodyScreen] is the ONLY
 * state holder in this file (owns every Context/Room read, including the drilldowns' own
 * range-scoped loads) and swaps between [BodyContent] (the panel list) and one of three drilldown
 * composables, all of which are plain-data/callback UI with no controller reference of their own -
 * the same split [com.kevin.legion.ui.LedgerScreen] uses around
 * [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]. `@Preview`s below exercise [BodyContent]
 * and each drilldown independently.
 */
data class BodyUiState(
    val loading: Boolean = true,
    val mealGap: DailyMealGap = DailyMealGap.NotLogged,
    val hasMealTarget: Boolean = false,
    val mealTargetKcal: Int? = null,
    // Ticket 03: SetMealTargetDialog needs the whole current target to pre-fill an edit, not just
    // the calorie figure BodyPanelList's tile already read - kept as three plain fields (mirroring
    // the entity) rather than threading MealTarget itself, so a preview/test can build BodyUiState
    // without importing the Room entity.
    val mealTargetProteinG: Double? = null,
    val mealTargetCarbsG: Double? = null,
    val mealTargetFatG: Double? = null,
    val recentMeals: List<MealLog> = emptyList(),
    val latestBodyweight: BodyweightLog? = null,
    /** For [bodyweightTrendText] - null when [latestBodyweight] is the only reading on file. */
    val previousBodyweight: BodyweightLog? = null,
    // Sleep (Kevin, 2026-08-07) - same "gap first, recent list second" shape as workouts/meals.
    val sleepGap: SleepGap = SleepGap.NotLogged,
    val hasSleepTarget: Boolean = false,
    val sleepTargetMinutes: Int? = null,
    val recentSleep: List<SleepLog> = emptyList(),
    // Ticket 16: each panel's fixed-window sparkline series - MASS 30d, INTAKE/SLEEP 7d (ticket 07
    // answer #1's own defaults). One `Float?` per local day, a gap slot rendered as `null` per the
    // file doc's invariant - never flattened to `0f` here or anywhere downstream.
    val massSparkline: List<Float?> = emptyList(),
    val intakeSparkline: List<Float?> = emptyList(),
    val sleepSparkline: List<Float?> = emptyList(),
)

/**
 * The engine's body-coach figures for direction A's cards (fat-loss ticket 21), loaded AFTER the
 * Room state so the screen never waits on the network to show what the phone already has. Each
 * read is fresh, the saved copy with its age ([trendNote] and friends, in words), or absent with
 * the reason - never a blank standing in for a failure.
 */
data class BodyCoachUi(
    val loaded: Boolean = false,
    val trend: TrendWire? = null,
    val trendNote: String? = null,
    val energy: EnergyWire? = null,
    val energyNote: String? = null,
    val savedMeals: List<SavedMealWire> = emptyList(),
    val savedMealsNote: String? = null,
    val profile: ProfileWire? = null,
    /** workouts, 2026-11-06: today's workout and the week, from the engine's programme. */
    val workout: WorkoutTodayWire? = null,
    val workoutNote: String? = null,
)

/**
 * Which in-screen drilldown, if any, is open - the "selected-panel enum + BackHandler" shape the
 * brief calls for, except a sealed class rather than a bare enum because TRAINING's second level
 * carries data ([TrainingProgression.exercise]) the other three don't need. `null` (a plain state
 * var, not a member of this type - matching [com.kevin.legion.ui.ledger.CategoryDrilldownSelection]'s
 * own doc comment on why "not open" is deliberately kept out of the sealed type) means the panel
 * list is showing.
 */
internal sealed class BodyDrilldown {
    object Mass : BodyDrilldown()
    object Intake : BodyDrilldown()
    object Sleep : BodyDrilldown()
    object TrainingExercises : BodyDrilldown()

    /** workouts, 2026-11-06: the session tick-off view. It replaced the "Today's plan" checklist. */
    object Workout : BodyDrilldown()

    /** fat-loss ticket 21: the BIO goals panel, moved into "More". */
    object Goals : BodyDrilldown()
    data class TrainingProgression(val exercise: String) : BodyDrilldown()
}

/**
 * [onBack] is the header's back arrow (soft-body conversion: every drill-down opens with the same
 * [DeckScreenHeader]). It defaults to a no-op so the existing `BodyScreen()` call compiles; the
 * route should pass `{ navController.popBackStack() }`. System back is unaffected - the
 * [BackHandler] below handles in-screen drilldowns and the nav host handles the rest.
 */
@Composable
fun BodyScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BodyUiState()) }

    // Ticket 03: every write dialog below bumps this after a successful write/delete, and this
    // effect (plus the drilldown-load effect further down, which also keys on it) re-reads Room -
    // the same "one state holder, no local cache to go stale" shape LedgerScreen already uses.
    // `Unit` was the original key; a plain Int works identically for the first composition (0) and
    // additionally reruns on every bump.
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        val now = System.currentTimeMillis()
        val mealTarget = CarDatabase.getDatabase(context).mealTargetDao().currentTarget(dayStartEpoch(now))
        val sleepTarget = CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(dayStartEpoch(now))
        val recentWeights = WorkoutController.recentBodyweights(context, 2)

        val massStart = deckRangeStartMs(DeckRange.THIRTY_DAY, now)
        val massSamples = WorkoutController.bodyweightHistory(context, massStart, now)
        val massUnit = recentWeights.getOrNull(0)?.weightUnit
        val massSparkline = if (massUnit != null) {
            bucketBodyweightDaily(massSamples, massUnit, massStart, now).map { it?.y }
        } else {
            emptyList()
        }

        val sevenDayStart = deckRangeStartMs(DeckRange.SEVEN_DAY, now)
        val intakeMeals = MealController.mealsInWindow(context, sevenDayStart, now)
        val intakeSparkline = bucketMealKcalDaily(intakeMeals, sevenDayStart, now).map { it?.toFloat() }

        val sleepNights = SleepController.sleepInWindow(context, sevenDayStart, now)
        // Hours, not minutes - the sparkline is a silhouette, and a driver reads sleep in hours.
        val sleepSparkline = bucketSleepMinutesDaily(sleepNights, sevenDayStart, now).map { it?.let { m -> m / 60f } }

        state = BodyUiState(
            loading = false,
            mealGap = MealController.dayGap(context, now),
            hasMealTarget = mealTarget != null,
            mealTargetKcal = mealTarget?.caloriesKcal,
            mealTargetProteinG = mealTarget?.proteinG,
            mealTargetCarbsG = mealTarget?.carbsG,
            mealTargetFatG = mealTarget?.fatG,
            recentMeals = MealController.recentMeals(context),
            latestBodyweight = recentWeights.getOrNull(0),
            previousBodyweight = recentWeights.getOrNull(1),
            sleepGap = SleepController.gapFor(context, now),
            hasSleepTarget = sleepTarget != null,
            sleepTargetMinutes = sleepTarget?.targetMinutes,
            recentSleep = SleepController.recentSleep(context),
            massSparkline = massSparkline,
            intakeSparkline = intakeSparkline,
            sleepSparkline = sleepSparkline,
        )
    }

    // fat-loss ticket 21: the engine's figures, after Room, on the same reload key.
    var coach by remember { mutableStateOf(BodyCoachUi()) }
    LaunchedEffect(reloadKey) {
        val controller = BodyCoachController.forApp(context)
        val trend = controller.trend()
        val energy = controller.energy()
        val saved = controller.savedMeals()
        val profile = controller.profile()
        val workout = WorkoutSessionController.forApp(context).today()
        coach = BodyCoachUi(
            loaded = true,
            trend = trend.valueOrNull,
            trendNote = trend.noteOrNull,
            energy = energy.valueOrNull,
            energyNote = energy.noteOrNull,
            savedMeals = saved.valueOrNull?.results.orEmpty(),
            savedMealsNote = saved.noteOrNull,
            profile = profile.valueOrNull,
            workout = workout.valueOrNull,
            workoutNote = workout.noteOrNull,
        )
    }

    var drilldown by remember { mutableStateOf<BodyDrilldown?>(null) }
    var range by remember { mutableStateOf(DeckRange.THIRTY_DAY) }
    var drilldownLoading by remember { mutableStateOf(false) }

    var massSeries by remember { mutableStateOf<List<DeckPoint?>>(emptyList()) }
    var massHistory by remember { mutableStateOf<List<BodyweightLog>>(emptyList()) }
    var intakeBars by remember { mutableStateOf<List<DeckBar?>>(emptyList()) }
    var intakeHistory by remember { mutableStateOf<List<MealLog>>(emptyList()) }
    var sleepBars by remember { mutableStateOf<List<DeckBar?>>(emptyList()) }
    var sleepHistory by remember { mutableStateOf<List<SleepLog>>(emptyList()) }
    var trainingExercises by remember { mutableStateOf<List<WorkoutSetLogDao.ExerciseRecency>>(emptyList()) }
    var progressionSeries by remember { mutableStateOf<List<DeckPoint?>>(emptyList()) }
    var progressionSets by remember { mutableStateOf<List<WorkoutSetLog>>(emptyList()) }

    // Every top-level drilldown gets a sensible default range on OPEN, matching its own panel's
    // fixed window (ticket 07 answer #1) - re-opening after closing always starts from that
    // default rather than whatever range a previous visit left selected. The TRAINING progression
    // sub-level deliberately does NOT reset here (it is reached FROM the exercise list, which
    // carries no range of its own), so a driver who picks 90D on one exercise keeps it browsing to
    // the next.
    LaunchedEffect(drilldown) {
        when (drilldown) {
            BodyDrilldown.Mass -> range = DeckRange.THIRTY_DAY
            BodyDrilldown.Intake, BodyDrilldown.Sleep -> range = DeckRange.SEVEN_DAY
            else -> {}
        }
    }

    // One effect, one writer per drilldown's own state vars (never a shared `state.copy(...)`
    // across effects) - the exact race commit 4fd241e fixed in LedgerScreen was two effects
    // racing to copy-write the SAME `state` var; keeping each drilldown's load in its own
    // `var`s here means there is nothing for a second effect to race against.
    LaunchedEffect(drilldown, range, reloadKey) {
        val now = System.currentTimeMillis()
        val from = deckRangeStartMs(range, now)
        when (val current = drilldown) {
            // Plan and Goals load their own panels (fat-loss ticket 21).
            null, BodyDrilldown.Workout, BodyDrilldown.Goals -> {}
            BodyDrilldown.Mass -> {
                drilldownLoading = true
                val samples = WorkoutController.bodyweightHistory(context, from, now)
                val unit = samples.lastOrNull()?.weightUnit ?: state.latestBodyweight?.weightUnit
                massSeries = if (unit != null) bucketBodyweightDaily(samples, unit, from, now) else emptyList()
                massHistory = samples.sortedByDescending { it.loggedAt }
                drilldownLoading = false
            }
            BodyDrilldown.Intake -> {
                drilldownLoading = true
                val meals = MealController.mealsInWindow(context, from, now)
                val target = state.mealTargetKcal
                val days = dailyBuckets(from, now)
                val kcalPerDay = bucketMealKcalDaily(meals, from, now)
                intakeBars = kcalPerDay.mapIndexed { i, kcal ->
                    if (kcal == null) null else DeckBar(label = shortDate(days[i]), value = kcal.toFloat(), targetValue = target?.toFloat())
                }
                intakeHistory = meals.sortedByDescending { it.loggedAt }
                drilldownLoading = false
            }
            BodyDrilldown.Sleep -> {
                drilldownLoading = true
                val nights = SleepController.sleepInWindow(context, from, now)
                val target = state.sleepTargetMinutes
                val days = dailyBuckets(from, now)
                val minutesPerDay = bucketSleepMinutesDaily(nights, from, now)
                sleepBars = minutesPerDay.mapIndexed { i, minutes ->
                    if (minutes == null) null else DeckBar(label = shortDate(days[i]), value = minutes / 60f, targetValue = target?.let { it / 60f })
                }
                sleepHistory = nights.sortedByDescending { it.loggedAt }
                drilldownLoading = false
            }
            BodyDrilldown.TrainingExercises -> {
                drilldownLoading = true
                trainingExercises = WorkoutController.exercisesByRecency(context)
                drilldownLoading = false
            }
            is BodyDrilldown.TrainingProgression -> {
                drilldownLoading = true
                val sets = WorkoutController.setsForExercise(context, current.exercise)
                progressionSets = sets.sortedByDescending { it.loggedAt }
                // ALL means "from this exercise's own oldest set", not epoch 0 - deckRangeStartMs's
                // own ALL=0L is a fine `>=` bound for a DB query but would otherwise walk
                // `dailyBuckets` across every day since 1970 for a chart that only ever needed the
                // exercise's real history.
                val chartStart = if (range == DeckRange.ALL) (sets.minOfOrNull { it.loggedAt } ?: now) else from
                progressionSeries = buildExerciseProgression(sets, chartStart, now)
                drilldownLoading = false
            }
        }
    }

    BackHandler(enabled = drilldown != null) {
        drilldown = when (drilldown) {
            is BodyDrilldown.TrainingProgression -> BodyDrilldown.TrainingExercises
            else -> null
        }
    }

    SoftTheme {
    when (val current = drilldown) {
        null -> BodyContent(state, coach, onOpenPane = { drilldown = it }, onDataChanged = { reloadKey++ }, onBack = onBack)
        BodyDrilldown.Workout -> WorkoutSessionHost(onBack = { drilldown = null; reloadKey++ })
        BodyDrilldown.Goals -> BodyPanelDrilldown(title = "Goals", onBack = { drilldown = null }) {
            com.kevin.legion.ui.goals.GoalsPanel(aspect = "bio")
        }
        BodyDrilldown.Mass -> BodyMassDrilldown(
            latest = state.latestBodyweight,
            series = massSeries,
            history = massHistory,
            range = range,
            loading = drilldownLoading,
            onRangeChange = { range = it },
            onBack = { drilldown = null },
            onDelete = { log -> WorkoutController.deleteBodyweightLog(context, log) },
            onDeleted = { reloadKey++ },
        )
        BodyDrilldown.Intake -> BodyIntakeDrilldown(
            bars = intakeBars,
            history = intakeHistory,
            range = range,
            loading = drilldownLoading,
            onRangeChange = { range = it },
            onBack = { drilldown = null },
            onDelete = { log -> MealController.deleteMealLog(context, log) },
            onDeleted = { reloadKey++ },
        )
        BodyDrilldown.Sleep -> BodySleepDrilldown(
            sleepTargetMinutes = state.sleepTargetMinutes,
            onDataChanged = { reloadKey++ },
            bars = sleepBars,
            history = sleepHistory,
            range = range,
            loading = drilldownLoading,
            onRangeChange = { range = it },
            onBack = { drilldown = null },
            onDelete = { log -> SleepController.deleteSleepLog(context, log) },
            onDeleted = { reloadKey++ },
        )
        BodyDrilldown.TrainingExercises -> BodyTrainingExerciseListDrilldown(
            exercises = trainingExercises,
            loading = drilldownLoading,
            onSelect = { exercise -> drilldown = BodyDrilldown.TrainingProgression(exercise) },
            onBack = { drilldown = null },
        )
        is BodyDrilldown.TrainingProgression -> BodyExerciseProgressionDrilldown(
            exercise = current.exercise,
            series = progressionSeries,
            sets = progressionSets,
            range = range,
            loading = drilldownLoading,
            onRangeChange = { range = it },
            onBack = { drilldown = BodyDrilldown.TrainingExercises },
            onDelete = { log -> WorkoutController.deleteSetLog(context, log) },
            onDeleted = { reloadKey++ },
        )
    }
    }
}

/**
 * Plain UI: [state] and [onOpenPane] only, no controller/DB reference - see the file doc comment.
 * [onOpenPane] is typed to [BodyDrilldown], and this composable is `internal` (not `public`) so
 * that typing is legal - Kotlin's visibility checker treats "a broader-visibility declaration
 * exposing a narrower-visibility type" as an error, not a warning, whether the gap is
 * public-vs-private or public-vs-internal, so [BodyDrilldown] being `internal` only clears the
 * check if this function is `internal` too. Every real caller ([BodyScreen] itself) and every
 * `@Preview` below already live in this module, so nothing outside it ever needed [BodyContent]
 * to be `public` in the first place.
 */
@Composable
internal fun BodyContent(
    state: BodyUiState,
    coach: BodyCoachUi = BodyCoachUi(),
    onOpenPane: (BodyDrilldown) -> Unit = {},
    onDataChanged: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Body", onBack = onBack, accent = AreaAccent.BODY)
            // Ticket 03's universal-state rule: every row on this screen is TrustTier.REPORTED by
            // construction (nothing here is ever verified against anything external), so the
            // header says so ONCE rather than tagging every single row with it. Said in a full
            // sentence now that the "UPLINK // SELF-REPORT" stamp is gone - still words, still
            // always on screen, never collapsed.
            Text(
                "Everything here is self-reported, not verified.",
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (state.loading) {
                Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = sem.ghost, modifier = Modifier.padding(16.dp))
            } else {
                BodyCardList(state, coach, onOpenPane, onDataChanged)
            }
        }
    }
}

/**
 * Direction A of the fat-loss revamp (Kevin picked it 2026-11-05, tickets 20 and 21): one scroll of
 * cards. Quick log, Trend, Today, Energy, This week, More. The checklist, sleep, goals and the raw
 * histories moved behind "More" rows (Kevin: "today it has a bunch of checklists and the visuals are
 * a bit hard to understand"). Every write here is a dialog that calls the same controller or engine
 * route its voice tool does (ADR 0035); the dialogs are siblings of the list, not items.
 */
@Composable
private fun BodyCardList(
    state: BodyUiState,
    coach: BodyCoachUi,
    onOpenPane: (BodyDrilldown) -> Unit,
    onDataChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogWeight by remember { mutableStateOf(false) }
    var showLogMeal by remember { mutableStateOf(false) }
    var showEditMealTarget by remember { mutableStateOf(false) }
    var workoutBusy by remember { mutableStateOf(false) }
    var workoutMessage by remember { mutableStateOf<String?>(null) }
    val startWorkout: () -> Unit = {
        workoutBusy = true
        scope.launch {
            val step = WorkoutSessionController.forApp(context).start()
            workoutBusy = false
            if (step.session != null) onOpenPane(BodyDrilldown.Workout) else workoutMessage = step.sentence
        }
    }
    val openWorkout: () -> Unit = {
        if (WorkoutSessionController.forApp(context).current()?.inProgress == true) onOpenPane(BodyDrilldown.Workout) else startWorkout()
    }
    var showWaist by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showSavedMeals by remember { mutableStateOf(false) }
    var proposalBusy by remember { mutableStateOf(false) }
    var proposalResult by remember { mutableStateOf<String?>(null) }
    var planBusy by remember { mutableStateOf(false) }
    var planResult by remember { mutableStateOf<String?>(null) }

    val today = todayIntake(state)
    val fallback = state.latestBodyweight?.let { "Latest weigh-in: ${formatWeight(it.weightValue, it.weightUnit)}, ${shortDate(it.loggedAt)}." }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "quick-log") {
            BodyQuickLogRow(
                onWeight = { showLogWeight = true },
                onMeal = { showLogMeal = true },
                onWaist = { showWaist = true },
                onWorkout = openWorkout,
            )
        }
        item(key = "workout") {
            BodyWorkoutCard(
                today = coach.workout,
                note = if (coach.loaded) coach.workoutNote else null,
                busy = workoutBusy,
                message = workoutMessage,
                onStart = startWorkout,
                onOpen = { onOpenPane(BodyDrilldown.Workout) },
                // Body coach 2: a programme change Alfred proposed, accepted or dismissed by hand.
                proposalBusy = planBusy,
                proposalResult = planResult,
                onAcceptProposal = { id ->
                    planBusy = true
                    scope.launch {
                        planResult = BodyCoachWrites.forApp(context).acceptProgrammeProposal(id).sentence
                        planBusy = false
                        onDataChanged()
                    }
                },
                onDismissProposal = { id ->
                    planBusy = true
                    scope.launch {
                        planResult = BodyCoachWrites.forApp(context).dismissProgrammeProposal(id).sentence
                        planBusy = false
                        onDataChanged()
                    }
                },
                onLockPlan = { id ->
                    planBusy = true
                    scope.launch {
                        val out = BodyCoachWrites.forApp(context).lockPlan(id)
                        planResult = out.sentence
                        // The new target is an engine row; pull it into Room so the Today bars use it.
                        if (out.ok) runCatching { com.kevin.legion.backend.BodySync.pullNow(context) }
                        planBusy = false
                        onDataChanged()
                    }
                },
                onDiscardPlan = { id ->
                    planBusy = true
                    scope.launch {
                        planResult = BodyCoachWrites.forApp(context).discardPlan(id).sentence
                        planBusy = false
                        onDataChanged()
                    }
                },
            )
        }
        item(key = "trend") {
            BodyTrendCard(
                trend = coach.trend,
                note = if (coach.loaded) coach.trendNote else "Reading the trend...",
                fallback = fallback,
                onOpenHistory = { onOpenPane(BodyDrilldown.Mass) },
            )
        }
        item(key = "today") {
            BodyTodayCard(
                today = today,
                onSavedMeals = { showSavedMeals = true },
                onLogMeal = { showLogMeal = true },
                onEditTarget = { showEditMealTarget = true },
            )
        }
        item(key = "energy") {
            BodyEnergyCard(
                energy = coach.energy,
                note = if (coach.loaded) coach.energyNote else "Reading the estimate...",
                busy = proposalBusy,
                result = proposalResult,
                onAccept = { id ->
                    proposalBusy = true
                    scope.launch {
                        val out = BodyCoachWrites.forApp(context).acceptProposal(id)
                        proposalResult = out.sentence
                        // The new target is an engine row; pull it into Room now so the bars use it.
                        if (out.ok) runCatching { com.kevin.legion.backend.BodySync.pullNow(context) }
                        proposalBusy = false
                        onDataChanged()
                    }
                },
                onDismiss = { id ->
                    proposalBusy = true
                    scope.launch {
                        proposalResult = BodyCoachWrites.forApp(context).dismissProposal(id).sentence
                        proposalBusy = false
                        onDataChanged()
                    }
                },
                onEditProfile = { showProfile = true },
            )
        }
        item(key = "week") {
            BodyWeekCard(
                sessionsDone = coach.workout?.week?.done,
                sessionsPlanned = coach.workout?.week?.planned,
                subtitle = weekSubtitle(coach.workout?.week),
                waistIn = coach.trend?.waist?.firstOrNull()?.waistIn,
                waistChange28d = coach.trend?.waistChangeIn28d,
                onOpenTraining = { onOpenPane(BodyDrilldown.TrainingExercises) },
            )
        }
        item(key = "more") {
            BodyMoreList(
                onTraining = { onOpenPane(BodyDrilldown.TrainingExercises) },
                onSleep = { onOpenPane(BodyDrilldown.Sleep) },
                onGoals = { onOpenPane(BodyDrilldown.Goals) },
                onWeighIns = { onOpenPane(BodyDrilldown.Mass) },
                onMeals = { onOpenPane(BodyDrilldown.Intake) },
            )
        }
    }

    if (showLogWeight) LogBodyweightDialog(onDismiss = { showLogWeight = false }, onDone = { showLogWeight = false; onDataChanged() })
    if (showLogMeal) LogMealDialog(onDismiss = { showLogMeal = false }, onDone = { showLogMeal = false; onDataChanged() }, savedMeals = coach.savedMeals)
    if (showEditMealTarget) {
        SetMealTargetDialog(
            currentCalories = state.mealTargetKcal,
            currentProteinG = state.mealTargetProteinG,
            currentCarbsG = state.mealTargetCarbsG,
            currentFatG = state.mealTargetFatG,
            onDismiss = { showEditMealTarget = false },
            onDone = { showEditMealTarget = false; onDataChanged() },
        )
    }
    if (showWaist) LogWaistDialog(onDismiss = { showWaist = false }, onDone = { showWaist = false; onDataChanged() })
    if (showProfile) BodyProfileDialog(current = coach.profile, onDismiss = { showProfile = false }, onDone = { showProfile = false; onDataChanged() })
    if (showSavedMeals) {
        SavedMealsDialog(
            meals = coach.savedMeals,
            note = coach.savedMealsNote,
            onDismiss = { showSavedMeals = false },
            onChanged = onDataChanged,
        )
    }
}

/** Today's kcal and protein against the target, from Room. Nothing logged is null, never 0. */
internal fun todayIntake(state: BodyUiState): TodayIntake = when (val gap = state.mealGap) {
    is DailyMealGap.Logged -> TodayIntake(
        kcalEaten = gap.gap.actual.caloriesKcal,
        kcalTarget = gap.gap.target.caloriesKcal.takeIf { state.hasMealTarget },
        proteinEaten = gap.gap.actual.proteinG.roundToInt(),
        proteinTarget = gap.gap.target.proteinG.takeIf { state.hasMealTarget && it > 0 }?.roundToInt(),
    )
    DailyMealGap.NotLogged -> TodayIntake(
        kcalEaten = null,
        kcalTarget = state.mealTargetKcal,
        proteinEaten = null,
        proteinTarget = state.mealTargetProteinG?.takeIf { it > 0 }?.roundToInt(),
    )
}

/** A "More" row's destination: a header and one panel in a scroll, nothing else. */
@Composable
private fun BodyPanelDrilldown(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = title, onBack = onBack, accent = AreaAccent.BODY)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = BODY_LIST_PADDING) {
                item(key = "panel") { content() }
            }
        }
    }
}

// ------------------------------------------------------------------- drilldowns

private val BODY_LIST_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

/** A chart sits on a rounded card like every other soft pane, inset 16dp like HOME's content. */
@Composable
private fun BodyChartCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(SoftColors.card, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) { content() }
}
//
// DrilldownHeader used to be a private composable in this file; ticket 08 (goal-plans) lifted it
// to `ui/common/DrilldownHeader.kt` so the relocated TRAINING drilldown
// (`ui/goals/GoalChecklistPanel.kt`, a different package) can open with the same header instead of
// a second hand-rolled copy - see that shared file's own doc comment.

/**
 * MASS drilldown: full-height [DeckLineChart] + [DeckRangeSelector] + the bodyweight history list
 * (moved here from the module screen per ticket 07 answer #2). Ticket 03 build item 3: [onDelete]
 * is `WorkoutController.deleteBodyweightLog`, the SAME row-scoped delete `undo_last_log` reaches
 * for a bodyweight log (see `service/LiveToolbox.kt`'s `undoLastLog`) - only the selection differs.
 */
@Composable
internal fun BodyMassDrilldown(
    latest: BodyweightLog?,
    series: List<DeckPoint?>,
    history: List<BodyweightLog>,
    range: DeckRange,
    loading: Boolean,
    onRangeChange: (DeckRange) -> Unit,
    onBack: () -> Unit,
    onDelete: suspend (BodyweightLog) -> String,
    onDeleted: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val unit = latest?.weightUnit ?: "lbs"
    var pendingDelete by remember { mutableStateOf<BodyweightLog?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "Mass", onBack = onBack, accent = AreaAccent.BODY)
            BodyRangeChips(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = sem.ghost, modifier = Modifier.padding(16.dp))
                series.all { it == null } -> Text("No readings yet", style = MaterialTheme.typography.bodyMedium, color = sem.faint, modifier = Modifier.padding(16.dp))
                else -> BodyChartCard { DeckLineChart(series = series, yLabel = { v -> "%.0f $unit".format(v) }, xLabels = series.map { "" }) }
            }
            Spacer(Modifier.height(8.dp))
            when {
                history.isEmpty() -> GapEmptyRow(
                    label = "History",
                    message = "Nothing logged yet.",
                    voiceHint = "log my weight",
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = BODY_LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { "weight-${it.id}" }) { log ->
                        Row(Modifier.fillMaxWidth().background(SoftColors.card, MaterialTheme.shapes.medium), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = "Bodyweight", value = formatWeight(log.weightValue, log.weightUnit), sub = shortDate(log.loggedAt), modifier = Modifier.weight(1f))
                            BodyDeleteAction(onClick = { pendingDelete = log }, tint = sem.quarantined, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { log ->
        DeleteLogDialog(
            subtitle = "Bodyweight ${formatWeight(log.weightValue, log.weightUnit)} logged ${shortDate(log.loggedAt)}.",
            onDelete = { onDelete(log) },
            onDismiss = { pendingDelete = null },
            onDone = { pendingDelete = null; onDeleted() },
        )
    }
}

/**
 * INTAKE drilldown: full-height [DeckBarChart] (daily kcal vs target) + range selector + the meal
 * history list. Ticket 03 build item 3: [onDelete] is `MealController.deleteMealLog`, the SAME
 * row-scoped delete `undo_last_log` reaches for a meal log.
 */
@Composable
private fun BodyIntakeDrilldown(
    bars: List<DeckBar?>,
    history: List<MealLog>,
    range: DeckRange,
    loading: Boolean,
    onRangeChange: (DeckRange) -> Unit,
    onBack: () -> Unit,
    onDelete: suspend (MealLog) -> String,
    onDeleted: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    var pendingDelete by remember { mutableStateOf<MealLog?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "Intake", onBack = onBack, accent = AreaAccent.BODY)
            BodyRangeChips(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = sem.ghost, modifier = Modifier.padding(16.dp))
                bars.all { it == null } -> Text("Not logged", style = MaterialTheme.typography.bodyMedium, color = sem.faint, modifier = Modifier.padding(16.dp))
                else -> BodyChartCard { DeckBarChart(bars) }
            }
            Spacer(Modifier.height(8.dp))
            when {
                history.isEmpty() -> GapEmptyRow(
                    label = "History",
                    message = "Nothing logged yet.",
                    voiceHint = "log a meal",
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = BODY_LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { "meal-${it.id}" }) { log ->
                        Row(Modifier.fillMaxWidth().background(SoftColors.card, MaterialTheme.shapes.medium), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = log.description, value = mealValueText(log), sub = shortDate(log.loggedAt), modifier = Modifier.weight(1f))
                            BodyDeleteAction(onClick = { pendingDelete = log }, tint = sem.quarantined, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { log ->
        DeleteLogDialog(
            subtitle = "${log.description}, logged ${shortDate(log.loggedAt)}.",
            onDelete = { onDelete(log) },
            onDismiss = { pendingDelete = null },
            onDone = { pendingDelete = null; onDeleted() },
        )
    }
}

/**
 * SLEEP drilldown: full-height [DeckBarChart] (nightly hours vs target) + range selector + the
 * sleep history list. Ticket 03 build item 3: [onDelete] is `SleepController.deleteSleepLog`, the
 * SAME row-scoped delete `undo_last_log` reaches for a sleep log.
 */
@Composable
private fun BodySleepDrilldown(
    sleepTargetMinutes: Int?,
    onDataChanged: () -> Unit,
    bars: List<DeckBar?>,
    history: List<SleepLog>,
    range: DeckRange,
    loading: Boolean,
    onRangeChange: (DeckRange) -> Unit,
    onBack: () -> Unit,
    onDelete: suspend (SleepLog) -> String,
    onDeleted: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    var pendingDelete by remember { mutableStateOf<SleepLog?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "Sleep", onBack = onBack, accent = AreaAccent.BODY)
            // fat-loss ticket 21: the Sleep tile's two actions moved here with it (ADR 0035).
            var showLogSleep by remember { mutableStateOf(false) }
            var showTarget by remember { mutableStateOf(false) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyAction("Log sleep", onClick = { showLogSleep = true })
                BodyAction("Sleep target", onClick = { showTarget = true }, quiet = true)
            }
            if (showLogSleep) LogSleepDialog(onDismiss = { showLogSleep = false }, onDone = { showLogSleep = false; onDataChanged() })
            if (showTarget) {
                SetSleepTargetDialog(
                    currentHours = sleepTargetMinutes?.let { it / 60.0 },
                    onDismiss = { showTarget = false },
                    onDone = { showTarget = false; onDataChanged() },
                )
            }
            BodyRangeChips(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = sem.ghost, modifier = Modifier.padding(16.dp))
                bars.all { it == null } -> Text("Not logged", style = MaterialTheme.typography.bodyMedium, color = sem.faint, modifier = Modifier.padding(16.dp))
                else -> BodyChartCard { DeckBarChart(bars) }
            }
            Spacer(Modifier.height(8.dp))
            when {
                history.isEmpty() -> GapEmptyRow(
                    label = "History",
                    message = "Nothing logged yet.",
                    voiceHint = "I slept 7 hours",
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = BODY_LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { "sleep-${it.id}" }) { log ->
                        Row(Modifier.fillMaxWidth().background(SoftColors.card, MaterialTheme.shapes.medium), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = "Sleep", value = sleepValueText(log), sub = shortDate(log.sleepDate), modifier = Modifier.weight(1f))
                            BodyDeleteAction(onClick = { pendingDelete = log }, tint = sem.quarantined, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { log ->
        DeleteLogDialog(
            subtitle = "Sleep ${sleepValueText(log)} logged ${shortDate(log.sleepDate)}.",
            onDelete = { onDelete(log) },
            onDismiss = { pendingDelete = null },
            onDone = { pendingDelete = null; onDeleted() },
        )
    }
}

/** TRAINING's first drilldown level: every distinct exercise, most recently worked first - taps into [BodyExerciseProgressionDrilldown]. Opened from More > Training history. */
@Composable
internal fun BodyTrainingExerciseListDrilldown(
    exercises: List<WorkoutSetLogDao.ExerciseRecency>,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "Training exercises", onBack = onBack, accent = AreaAccent.BODY)
            when {
                loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = sem.ghost, modifier = Modifier.padding(16.dp))
                exercises.isEmpty() -> GapEmptyRow(
                    label = "Exercises",
                    message = "Nothing logged yet.",
                    voiceHint = "three sets of squats at 225",
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = BODY_LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(exercises, key = { it.exercise }) { entry ->
                        ReadingRow(
                            label = entry.exercise,
                            value = shortDate(entry.lastLoggedAt),
                            modifier = Modifier.background(SoftColors.card, MaterialTheme.shapes.medium).clickable { onSelect(entry.exercise) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * TRAINING's second drilldown level: the per-exercise progression chart plus the raw set history
 * (sets without a weight are listed here even though [series] excludes them from the plot - see
 * [buildExerciseProgression]'s doc comment). Ticket 03 build item 3: [onDelete] is
 * `WorkoutController.deleteSetLog`, the SAME row-scoped delete `undo_last_log` reaches for a
 * workout-set log.
 */
@Composable
internal fun BodyExerciseProgressionDrilldown(
    exercise: String,
    series: List<DeckPoint?>,
    sets: List<WorkoutSetLog>,
    range: DeckRange,
    loading: Boolean,
    onRangeChange: (DeckRange) -> Unit,
    onBack: () -> Unit,
    onDelete: suspend (WorkoutSetLog) -> String,
    onDeleted: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val unit = sets.firstOrNull { it.weightValue != null }?.weightUnit ?: "lbs"
    var pendingDelete by remember { mutableStateOf<WorkoutSetLog?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = exercise, onBack = onBack, accent = AreaAccent.BODY)
            BodyRangeChips(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = sem.ghost, modifier = Modifier.padding(16.dp))
                series.all { it == null } -> Text("No readings yet", style = MaterialTheme.typography.bodyMedium, color = sem.faint, modifier = Modifier.padding(16.dp))
                else -> BodyChartCard { DeckLineChart(series = series, yLabel = { v -> "%.0f $unit".format(v) }, xLabels = series.map { "" }) }
            }
            Spacer(Modifier.height(8.dp))
            when {
                sets.isEmpty() -> GapEmptyRow(label = "Sets", message = "Nothing logged yet for $exercise.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = BODY_LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sets, key = { "set-${it.id}" }) { log ->
                        // Ticket 16: "sets without weight excluded from the chart but listed" -
                        // this row is where a bodyweight/no-number set still shows up, even though
                        // buildExerciseProgression never plotted it above.
                        Row(Modifier.fillMaxWidth().background(SoftColors.card, MaterialTheme.shapes.medium), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = workoutSetValueText(log), value = shortDate(log.loggedAt), modifier = Modifier.weight(1f))
                            BodyDeleteAction(onClick = { pendingDelete = log }, tint = sem.quarantined, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { log ->
        DeleteLogDialog(
            subtitle = "${workoutSetValueText(log)}, logged ${shortDate(log.loggedAt)}.",
            onDelete = { onDelete(log) },
            onDismiss = { pendingDelete = null },
            onDone = { pendingDelete = null; onDeleted() },
        )
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Body: loading", widthDp = 360, heightDp = 800)
@Composable
private fun PreviewBodyLoading() = SoftTheme {
    BodyContent(BodyUiState(loading = true))
}

@Preview(name = "Body: everything empty (fresh install)", widthDp = 360, heightDp = 800)
@Composable
private fun PreviewBodyAllEmpty() = SoftTheme {
    BodyContent(BodyUiState(loading = false))
}

@Preview(name = "Body: populated - panels, sparklines and gaps", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewBodyPopulated() = SoftTheme {
    val now = System.currentTimeMillis()
    BodyContent(
        BodyUiState(
            loading = false,
            mealGap = DailyMealGap.Logged(
                PlanGap(
                    target = com.kevin.legion.meals.MacroTotals(2200, 150.0, 220.0, 70.0),
                    actual = com.kevin.legion.meals.MacroTotals(1650, 90.0, 160.0, 55.0),
                    gap = com.kevin.legion.meals.MacroTotals(550, 60.0, 60.0, 15.0),
                    tier = TrustTier.REPORTED,
                ),
            ),
            hasMealTarget = true,
            mealTargetKcal = 2200,
            recentMeals = listOf(
                MealLog(id = 1, description = "Chicken burrito bowl", caloriesKcal = 720, proteinG = 45.0, carbsG = 80.0, fatG = 20.0, loggedAt = now, trustTier = TrustTier.REPORTED),
                MealLog(id = 2, description = "Protein shake", caloriesKcal = null, proteinG = null, carbsG = null, fatG = null, loggedAt = now, trustTier = TrustTier.REPORTED),
            ),
            latestBodyweight = BodyweightLog(id = 2, weightValue = 183.5, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
            previousBodyweight = BodyweightLog(id = 1, weightValue = 185.0, weightUnit = "lbs", loggedAt = now - 7L * 24 * 60 * 60 * 1000, trustTier = TrustTier.REPORTED),
            massSparkline = listOf(185f, 184.5f, null, 184f, 183.8f, null, 183.5f),
            intakeSparkline = listOf(2100f, null, 1980f, 2250f, 1650f, null, null),
            sleepSparkline = listOf(7.5f, 7f, null, 6.5f, 8f, null, 7.2f),
        ),
    )
}

@Preview(name = "Body: MASS drilldown", widthDp = 360, heightDp = 800)
@Composable
private fun PreviewBodyMassDrilldown() = SoftTheme {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    BodyMassDrilldown(
        latest = BodyweightLog(id = 3, weightValue = 183.5, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
        series = (0 until 10).map { i -> if (i == 3) null else DeckPoint(xMs = now - (9 - i) * dayMs, y = 186f - i * 0.3f) },
        history = listOf(
            BodyweightLog(id = 3, weightValue = 183.5, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
            BodyweightLog(id = 2, weightValue = 184.0, weightUnit = "lbs", loggedAt = now - dayMs, trustTier = TrustTier.REPORTED),
        ),
        range = DeckRange.THIRTY_DAY,
        loading = false,
        onRangeChange = {},
        onBack = {},
        onDelete = { "" },
        onDeleted = {},
    )
}

@Preview(name = "Body: TRAINING exercise list", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewBodyTrainingExercises() = SoftTheme {
    val now = System.currentTimeMillis()
    BodyTrainingExerciseListDrilldown(
        exercises = listOf(
            WorkoutSetLogDao.ExerciseRecency("Squat", now),
            WorkoutSetLogDao.ExerciseRecency("Bench Press", now - 2L * 24 * 60 * 60 * 1000),
        ),
        loading = false,
        onSelect = {},
        onBack = {},
    )
}

@Preview(name = "Body: exercise progression", widthDp = 360, heightDp = 800)
@Composable
private fun PreviewBodyExerciseProgression() = SoftTheme {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    BodyExerciseProgressionDrilldown(
        exercise = "Squat",
        series = (0 until 6).map { i -> DeckPoint(xMs = now - (5 - i) * dayMs, y = 205f + i * 5f) },
        sets = listOf(
            WorkoutSetLog(id = 1, exercise = "Squat", sets = 3, reps = 5, weightValue = 225.0, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
            WorkoutSetLog(id = 2, exercise = "Squat", sets = 5, reps = null, weightValue = null, weightUnit = null, loggedAt = now - dayMs, trustTier = TrustTier.REPORTED),
        ),
        range = DeckRange.THIRTY_DAY,
        loading = false,
        onRangeChange = {},
        onBack = {},
        onDelete = { "" },
        onDeleted = {},
    )
}
