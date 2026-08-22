package com.kevin.legion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kevin.legion.data.local.BodyweightLog
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
import com.kevin.legion.ui.body.DeleteLogDialog
import com.kevin.legion.ui.body.LogBodyweightDialog
import com.kevin.legion.ui.body.LogMealDialog
import com.kevin.legion.ui.body.LogSleepDialog
import com.kevin.legion.ui.body.LogWorkoutSetDialog
import com.kevin.legion.ui.body.SetMealTargetDialog
import com.kevin.legion.ui.body.SetSleepTargetDialog
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckLineChart
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckPoint
import com.kevin.legion.ui.common.DeckRange
import com.kevin.legion.ui.common.DeckRangeSelector
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.ReadingRow
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.ui.common.deckRangeStartMs
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import com.kevin.legion.workouts.WorkoutController

/**
 * `body` tab, rebuilt for the cyberdeck (ticket 16, per ticket 07's grilled answer). Four
 * MILSPEC panels in a FIXED order - MASS, INTAKE, SLEEP, TRAINING - each a one-glance sparkline
 * readout ([DeckPane] + [DeckSparkline]/[DeckRow]), never a full chart inline (ticket 07 answer
 * #1: "full-chart-inline declined"). Depth is one tap in: every panel opens an IN-SCREEN
 * drilldown (a full-height [DeckLineChart]/[DeckBarChart] plus the history list that used to live
 * on the module screen directly), following [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]'s
 * "owns its own internal drill-down... rather than adding nav-graph sub-routes with arguments"
 * precedent - [BodyDrilldown] is a plain state var this file swaps on, not a new `LegionRoute`.
 * TRAINING drills TWICE (ticket 07 answer #2): panel -> exercise list -> per-exercise progression
 * chart, the "biohacker's payoff chart".
 *
 * **Tiled to the mission-control grammar (ticket 16's BIO build, `.scratch/mission-control/issues/
 * 12-surface-inventories.md`).** MASS stays FULL (this surface's hero - latest reading, trend,
 * sparkline) and TRAINING stays FULL (a set list is rows, not a figure); INTAKE and SLEEP drop from
 * their own FULL panels to a shared HALF-tile row via [com.kevin.legion.ui.common.EqualHeightRow] /
 * [com.kevin.legion.ui.common.HalfTile] - the exact shell HOME's BIO/CRED/FLEET/LOG row already
 * uses, moved to `ui/common/DeckTiles.kt` by this ticket so both files read the same primitive
 * instead of each hand-rolling one. Tap-through is unchanged - each tile still opens the same
 * [BodyDrilldown] its old FULL panel did; only the panel-list SHAPE changed, not the drilldowns.
 *
 * **Header, once**: ticket 03's universal-state rule - Body's rows are ALL [TrustTier.REPORTED]
 * by construction (nothing here is verified against anything external), so that state is said
 * ONCE at the top of the screen (`UPLINK // SELF-REPORT`) rather than tagged on every row, per
 * ticket 16's brief.
 *
 * **Gaps, never zeros** (ticket 07 answer #5, CLAUDE.md §4 rule 6): every sparkline/bar-chart
 * series below is built by [bucketBodyweightDaily]/[bucketMealKcalDaily]/[bucketSleepMinutesDaily]/
 * [buildExerciseProgression] in `BodyGapResolvers.kt`, all of which put a `null` in an unlogged
 * day's slot - the chart kit ([com.kevin.legion.ui.common.DeckCharts.kt]) renders that as a gap by
 * construction, never a zero-height mark. Empty panels/drilldowns are worded (`NOT LOGGED`,
 * `NO READINGS YET`), never a blank space.
 *
 * **No longer read-only (ticket 03, `.scratch/command-center/issues/03-body-writes-by-hand.md`,
 * ADR 0035).** Voice was the ONLY way to write any of these four streams until this ticket - a
 * misheard log, a dead socket, or no key at all meant the capability simply did not exist for that
 * moment. Every write affordance below (`+ LOG WEIGHT`/`+ LOG`/`+ LOG SET`/`EDIT TARGET`/`DEL`)
 * calls the exact controller function `LiveToolbox`'s matching voice tool dispatches to - see
 * `ui/body/BodyWriteDialogs.kt`'s own doc comment for the full trace. What is still true from the
 * old posture: nothing here re-derives a score, a streak, or a percentage, and every macro figure
 * a controller only estimates is spoken by the controller's own return string as an estimate, not
 * re-labelled here.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill: [BodyScreen] is the ONLY
 * state holder in this file (owns every Context/Room read, including the drilldowns' own
 * range-scoped loads) and swaps between [BodyContent] (the panel list) and one of five drilldown
 * composables, all of which are plain-data/callback UI with no controller reference of their own -
 * the same split [com.kevin.legion.ui.LedgerScreen] uses around
 * [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]. `@Preview`s below exercise [BodyContent]
 * and each drilldown independently.
 */
data class BodyUiState(
    val loading: Boolean = true,
    val recentSets: List<WorkoutSetLog> = emptyList(),
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
 * Which in-screen drilldown, if any, is open - the "selected-panel enum + BackHandler" shape the
 * brief calls for, except a sealed class rather than a bare enum because TRAINING's second level
 * carries data ([TrainingProgression.exercise]) the other four don't need. `null` (a plain state
 * var, not a member of this type - matching [com.kevin.legion.ui.ledger.CategoryDrilldownSelection]'s
 * own doc comment on why "not open" is deliberately kept out of the sealed type) means the panel
 * list is showing.
 */
internal sealed class BodyDrilldown {
    object Mass : BodyDrilldown()
    object Intake : BodyDrilldown()
    object Sleep : BodyDrilldown()
    object TrainingExercises : BodyDrilldown()
    data class TrainingProgression(val exercise: String) : BodyDrilldown()
}

@Composable
fun BodyScreen() {
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
            recentSets = WorkoutController.recentSets(context),
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
            null -> {}
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

    when (val current = drilldown) {
        null -> BodyContent(state, onOpenPane = { drilldown = it }, onDataChanged = { reloadKey++ })
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
internal fun BodyContent(state: BodyUiState, onOpenPane: (BodyDrilldown) -> Unit = {}, onDataChanged: () -> Unit = {}) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "BODY",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            // Ticket 03's universal-state rule: every row on this screen is TrustTier.REPORTED by
            // construction (nothing here is ever verified against anything external), so the
            // header says so ONCE rather than tagging every single row with it.
            Text(
                "UPLINK // SELF-REPORT",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (state.loading) {
                Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
            } else {
                BodyPanelList(state, onOpenPane, onDataChanged)
            }
        }
    }
}

/**
 * Ticket 03: the four `+ LOG ...`/`EDIT TARGET` affordances below are the ONLY place this file
 * calls a controller write directly (each dialog does, from `ui/body/BodyWriteDialogs.kt`) -
 * everything else in [BodyPanelList] stays the plain read this file's doc comment describes.
 * [GoalsPanel]/[GoalChecklistPanel] already break that same "read-only" posture on purpose (see
 * this file's own doc comment on why GOALS is "the one panel on this screen that is read-AND-edit"),
 * so this is the second deliberate exception, not the first - both exist because ADR 0035 makes a
 * voice-only write a defect, not a feature.
 */
@Composable
private fun BodyPanelList(state: BodyUiState, onOpenPane: (BodyDrilldown) -> Unit, onDataChanged: () -> Unit) {
    val sem = LocalLegionSemantics.current
    var showLogWeight by remember { mutableStateOf(false) }
    var showLogMeal by remember { mutableStateOf(false) }
    var showEditMealTarget by remember { mutableStateOf(false) }
    var showLogSleep by remember { mutableStateOf(false) }
    var showEditSleepTarget by remember { mutableStateOf(false) }
    var showLogSet by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- MASS
        item(key = "pane-mass") {
            DeckPane(header = "MASS", modifier = Modifier.clickable { onOpenPane(BodyDrilldown.Mass) }) {
                val latest = state.latestBodyweight
                if (latest == null) {
                    GapEmptyRow(label = "Bodyweight", message = "Nothing logged yet - say \"log my weight\" and a number.")
                } else {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // MASS's hero is a VALUE, mint like every other reading in the app (ticket
                        // 01's "mint is every value, amber is every highlight") - caught here by
                        // this ticket's own pixel-sampling pass, the same class of bug HOME shipped
                        // with (all four heroes reading MaterialTheme.colorScheme.primary/amber
                        // instead of sem.data/mint) before it was fixed there. Pre-dated this
                        // ticket's own INTAKE/SLEEP tiling; fixed here rather than left in place
                        // since it sits directly on the BIO surface this ticket builds.
                        Text(formatWeight(latest.weightValue, latest.weightUnit), style = MaterialTheme.typography.displaySmall, color = sem.data)
                        Text(bodyweightTrendText(latest, state.previousBodyweight) ?: shortDate(latest.loggedAt), style = LegionType.stamp, color = sem.faint)
                    }
                    if (state.massSparkline.any { it != null }) {
                        DeckSparkline(state.massSparkline, modifier = Modifier.padding(horizontal = 12.dp))
                    } else {
                        Text("NO READINGS YET", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
                Text(
                    "+ LOG WEIGHT",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clickable { showLogWeight = true },
                )
            }
        }
        item(key = "pane-spacer-1") { Spacer(Modifier.height(10.dp)) }

        // ------------------------------------------------------ INTAKE / SLEEP (HALF tiles)
        // Mission-control ticket 16's BIO build: both panels drop from FULL to HALF per ticket 12's
        // inventory, sharing one row via EqualHeightRow - the same mechanism HOME's BIO/CRED/FLEET/LOG
        // row uses (see that composable's own doc for why a bare `Row(IntrinsicSize.Min)` crashes on
        // a DeckPane child). MASS stays FULL above (the surface's hero) and TRAINING stays FULL below
        // (a set list is rows, not a figure) - ticket 12's own reasoning, unchanged by this tiling.
        // Tap-through is unchanged: each tile still opens the exact drilldown its old FULL panel did.
        item(key = "tile-row-intake-sleep") {
            val intakeTile = buildIntakeTile(state.mealGap, state.hasMealTarget)
            val sleepTile = buildSleepTile(state.sleepGap, state.hasSleepTarget)
            EqualHeightRow(Modifier.fillMaxWidth(), horizontalGap = 9.dp) {
                HalfTile(
                    header = "Intake",
                    hero = intakeTile.hero,
                    caption = intakeTile.caption,
                    modifier = Modifier.clickable { onOpenPane(BodyDrilldown.Intake) },
                ) {
                    if (state.intakeSparkline.any { it != null }) {
                        DeckSparkline(state.intakeSparkline, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    Text(
                        "+ LOG",
                        style = LegionType.stamp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable { showLogMeal = true },
                    )
                    Text(
                        "EDIT TARGET",
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable { showEditMealTarget = true },
                    )
                }
                HalfTile(
                    header = "Sleep",
                    hero = sleepTile.hero,
                    caption = sleepTile.caption,
                    modifier = Modifier.clickable { onOpenPane(BodyDrilldown.Sleep) },
                ) {
                    if (state.sleepSparkline.any { it != null }) {
                        DeckSparkline(state.sleepSparkline, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    Text(
                        "+ LOG",
                        style = LegionType.stamp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable { showLogSleep = true },
                    )
                    Text(
                        "EDIT TARGET",
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable { showEditSleepTarget = true },
                    )
                }
            }
        }
        item(key = "pane-spacer-3") { Spacer(Modifier.height(10.dp)) }

        // ------------------------------------------------------------ TRAINING
        //
        // No "Workouts this week" gap row here any more (ticket 07, `goal-plans`, Kevin
        // 2026-08-22: "retire workouts this week. redundant.") - the daily checklist below already
        // states today's session, and two sections both answering "what training am I doing" is
        // exactly how they end up disagreeing (one derived from the plan, the other from logged
        // sets). This pane is now the logged-sets record only.
        item(key = "pane-training") {
            DeckPane(header = "TRAINING", modifier = Modifier.clickable { onOpenPane(BodyDrilldown.TrainingExercises) }) {
                if (state.recentSets.isEmpty()) {
                    Text("NOT LOGGED", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                } else {
                    state.recentSets.take(5).forEach { log ->
                        DeckRow(label = log.exercise, value = workoutSetValueText(log))
                    }
                }
                Text(
                    "+ LOG SET",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clickable { showLogSet = true },
                )
            }
        }
        item(key = "pane-spacer-4") { Spacer(Modifier.height(14.dp)) }

        // ---------------------------------------------------------------- CHECKLIST
        // Ticket 04, `goal-plans` (Kevin: "revamp of BIO/body tab..."). Full mode - every line,
        // plus the recent-skip record - self-contained the same way GOALS below is (its own
        // LaunchedEffect; not part of BodyUiState's batched load). Placed after TRAINING and
        // before GOALS: the checklist is the day-to-day habit surface derived FROM the targets and
        // goals above it, read top-to-bottom as "here's where you stand, here's today's list,
        // here's the standing goal it all points at".
        item(key = "pane-checklist") {
            com.kevin.legion.ui.goals.GoalChecklistPanel(compact = false)
        }
        item(key = "pane-spacer-4b") { Spacer(Modifier.height(14.dp)) }

        // ---------------------------------------------------------------- GOALS
        // Ticket 19: the one panel on this screen that is read-AND-edit, by design - see
        // GoalsPanel's own doc comment for why it breaks this file's "voice writes, screen reads"
        // posture and why it manages its own load rather than joining BodyUiState's batch fetch.
        item(key = "pane-goals") {
            com.kevin.legion.ui.goals.GoalsPanel(aspect = "bio")
        }
        item(key = "pane-spacer-5") { Spacer(Modifier.height(14.dp)) }
    }

    // Rendered as siblings of the LazyColumn, not list items - an AlertDialog paints into its own
    // window regardless of where in the composition it is called from, so nesting these inside a
    // scrolling `item {}` would only have made them scroll offscreen with the list for no benefit.
    if (showLogWeight) LogBodyweightDialog(onDismiss = { showLogWeight = false }, onDone = { showLogWeight = false; onDataChanged() })
    if (showLogMeal) LogMealDialog(onDismiss = { showLogMeal = false }, onDone = { showLogMeal = false; onDataChanged() })
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
    if (showLogSleep) LogSleepDialog(onDismiss = { showLogSleep = false }, onDone = { showLogSleep = false; onDataChanged() })
    if (showEditSleepTarget) {
        SetSleepTargetDialog(
            currentHours = state.sleepTargetMinutes?.let { it / 60.0 },
            onDismiss = { showEditSleepTarget = false },
            onDone = { showEditSleepTarget = false; onDataChanged() },
        )
    }
    if (showLogSet) LogWorkoutSetDialog(onDismiss = { showLogSet = false }, onDone = { showLogSet = false; onDataChanged() })
}

// ------------------------------------------------------------------- drilldowns

/** Shared "< BACK" + title header every drilldown below opens with - same shape as [com.kevin.legion.ui.ledger.CategoryDrilldownScreen]'s own header row. */
@Composable
private fun DrilldownHeader(title: String, onBack: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/**
 * MASS drilldown: full-height [DeckLineChart] + [DeckRangeSelector] + the bodyweight history list
 * (moved here from the module screen per ticket 07 answer #2). Ticket 03 build item 3: [onDelete]
 * is `WorkoutController.deleteBodyweightLog`, the SAME row-scoped delete `undo_last_log` reaches
 * for a bodyweight log (see `service/LiveToolbox.kt`'s `undoLastLog`) - only the selection differs.
 */
@Composable
private fun BodyMassDrilldown(
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
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "MASS", onBack = onBack)
            DeckRangeSelector(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
                series.all { it == null } -> Text("NO READINGS YET", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(12.dp))
                else -> DeckLineChart(series = series, yLabel = { v -> "%.0f $unit".format(v) }, xLabels = series.map { "" })
            }
            Hairline()
            when {
                history.isEmpty() -> GapEmptyRow(label = "History", message = "Nothing logged yet - say \"log my weight\" and a number.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(history, key = { "weight-${it.id}" }) { log ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = "Bodyweight", value = formatWeight(log.weightValue, log.weightUnit), sub = shortDate(log.loggedAt), modifier = Modifier.weight(1f))
                            Text("DEL", style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(end = 12.dp).clickable { pendingDelete = log })
                        }
                        Hairline()
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
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "INTAKE", onBack = onBack)
            DeckRangeSelector(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
                bars.all { it == null } -> Text("NOT LOGGED", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(12.dp))
                else -> DeckBarChart(bars)
            }
            Hairline()
            when {
                history.isEmpty() -> GapEmptyRow(label = "History", message = "Nothing logged yet - say \"log a meal\" and describe what you ate.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(history, key = { "meal-${it.id}" }) { log ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = log.description, value = mealValueText(log), sub = shortDate(log.loggedAt), modifier = Modifier.weight(1f))
                            Text("DEL", style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(end = 12.dp).clickable { pendingDelete = log })
                        }
                        Hairline()
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
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "SLEEP", onBack = onBack)
            DeckRangeSelector(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
                bars.all { it == null } -> Text("NOT LOGGED", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(12.dp))
                else -> DeckBarChart(bars)
            }
            Hairline()
            when {
                history.isEmpty() -> GapEmptyRow(label = "History", message = "Nothing logged yet - say \"I slept 7 hours\" (or however long).")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(history, key = { "sleep-${it.id}" }) { log ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = "Sleep", value = sleepValueText(log), sub = shortDate(log.sleepDate), modifier = Modifier.weight(1f))
                            Text("DEL", style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(end = 12.dp).clickable { pendingDelete = log })
                        }
                        Hairline()
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

/** TRAINING's first drilldown level: every distinct exercise, most recently worked first - taps into [BodyExerciseProgressionDrilldown]. */
@Composable
private fun BodyTrainingExerciseListDrilldown(
    exercises: List<WorkoutSetLogDao.ExerciseRecency>,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "TRAINING // EXERCISES", onBack = onBack)
            Hairline()
            when {
                loading -> Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
                exercises.isEmpty() -> GapEmptyRow(
                    label = "Exercises",
                    message = "Nothing logged yet - say \"three sets of squats at 225\" (or however you did it).",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(exercises, key = { it.exercise }) { entry ->
                        ReadingRow(
                            label = entry.exercise,
                            value = shortDate(entry.lastLoggedAt),
                            modifier = Modifier.clickable { onSelect(entry.exercise) },
                        )
                        Hairline()
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
private fun BodyExerciseProgressionDrilldown(
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
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = exercise.uppercase(), onBack = onBack)
            DeckRangeSelector(selected = range, onSelect = onRangeChange, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            when {
                loading -> Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
                series.all { it == null } -> Text("NO READINGS YET", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(12.dp))
                else -> DeckLineChart(series = series, yLabel = { v -> "%.0f $unit".format(v) }, xLabels = series.map { "" })
            }
            Hairline()
            when {
                sets.isEmpty() -> GapEmptyRow(label = "Sets", message = "Nothing logged yet for $exercise.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(sets, key = { "set-${it.id}" }) { log ->
                        // Ticket 16: "sets without weight excluded from the chart but listed" -
                        // this row is where a bodyweight/no-number set still shows up, even though
                        // buildExerciseProgression never plotted it above.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ReadingRow(label = workoutSetValueText(log), value = shortDate(log.loggedAt), modifier = Modifier.weight(1f))
                            Text("DEL", style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(end = 12.dp).clickable { pendingDelete = log })
                        }
                        Hairline()
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
private fun PreviewBodyLoading() = LegionTheme {
    BodyContent(BodyUiState(loading = true))
}

@Preview(name = "Body: everything empty (fresh install)", widthDp = 360, heightDp = 800)
@Composable
private fun PreviewBodyAllEmpty() = LegionTheme {
    BodyContent(BodyUiState(loading = false))
}

@Preview(name = "Body: populated - four panels, sparklines and gaps", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewBodyPopulated() = LegionTheme {
    val now = System.currentTimeMillis()
    BodyContent(
        BodyUiState(
            loading = false,
            recentSets = listOf(
                WorkoutSetLog(id = 1, exercise = "Squat", sets = 3, reps = 5, weightValue = 225.0, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED),
                WorkoutSetLog(id = 2, exercise = "Pushups", sets = 3, reps = 15, weightValue = null, weightUnit = null, loggedAt = now, trustTier = TrustTier.REPORTED),
            ),
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
private fun PreviewBodyMassDrilldown() = LegionTheme {
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
private fun PreviewBodyTrainingExercises() = LegionTheme {
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
private fun PreviewBodyExerciseProgression() = LegionTheme {
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
