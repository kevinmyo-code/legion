package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.service.DebugSettings
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.sitrep.SitrepModule
import com.kevin.legion.sitrep.SitrepScheduler
import com.kevin.legion.sitrep.SitrepSettings
import com.kevin.legion.ui.ProactiveCategoryRow
import com.kevin.legion.ui.ProactiveSpeechRow
import com.kevin.legion.ui.RecallAlertsRow
import com.kevin.legion.ui.SitrepModuleRow
import com.kevin.legion.ui.SitrepScheduleRow
import com.kevin.legion.ui.WellbeingDigestScheduleRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.wellbeing.WellbeingDigestScheduler
import com.kevin.legion.wellbeing.WellbeingDigestSettings
import kotlinx.coroutines.launch

/**
 * "Proactive speech" - the second subscreen `settings/` split into (command-center ticket 02).
 * **One screen owns the whole question of when the assistant may speak first**: the recall-alert
 * toggle, the master kill switch, all five [ProactiveCategory] levers, the sitrep's module registry
 * and schedule, and the wellbeing digest's schedule. The ticket's own charter for this screen
 * ("ALL the category levers... quiet hours, the daily cap, sitrep schedule + modules, wellbeing
 * digest time") - quiet hours and the daily cap are noted here for future reference but are NOT
 * moved, because they were never a settings row to begin with: [com.kevin.legion.service
 * .ProactiveBus] enforces both as fixed constants with no writer anywhere in the app, so there is
 * nothing to relocate without inventing a control the old screen never had (out of this ticket's
 * scope - a reorganisation, not a rebuild).
 *
 * Every row is the same composable the old monolith called, unmoved in substance.
 */
@Composable
fun ProactiveSpeechScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recallAlertsOn by remember { mutableStateOf(DebugSettings.recallAlertsEnabled(context)) }
    var proactiveOn by remember { mutableStateOf(true) }
    var proactiveCategories by remember {
        mutableStateOf<Map<ProactiveCategory, Boolean>>(emptyMap())
    }
    var companionName by remember { mutableStateOf<String?>(null) }
    var sitrepModules by remember { mutableStateOf<Map<SitrepModule, Boolean>>(emptyMap()) }
    var sitrepHourText by remember { mutableStateOf("") }
    var sitrepMinuteText by remember { mutableStateOf("") }
    var sitrepSendersText by remember { mutableStateOf("") }
    var sitrepScheduleStatus by remember { mutableStateOf("No schedule set - the sitrep is askable any time, but never fires on its own.") }
    var wellbeingHourText by remember { mutableStateOf("") }
    var wellbeingMinuteText by remember { mutableStateOf("") }
    var wellbeingScheduleStatus by remember { mutableStateOf("No schedule set - the wellbeing digest never fires on its own.") }
    var reloadNonce by remember { mutableStateOf(0) }

    // Ticket 22 part F: loads the module switches plus whatever schedule is stored, formatting
    // hour/minute as plain zero-padded digits for the two DeckTextFields. A never-set schedule
    // (SitrepSettings.schedule returns null) leaves the three fields blank rather than seeding a
    // fake default time - an empty field is what tells Kevin nothing has been saved yet.
    suspend fun reloadSitrepSettings() {
        SitrepSettings.load(context)
        sitrepModules = SitrepSettings.modules.value
        val schedule = SitrepSettings.schedule(context)
        if (schedule != null) {
            sitrepHourText = schedule.hour.toString()
            sitrepMinuteText = schedule.minute.toString()
            sitrepSendersText = schedule.senders
            sitrepScheduleStatus = "Fires daily at %02d:%02d".format(schedule.hour, schedule.minute)
        } else {
            sitrepScheduleStatus = "No schedule set - the sitrep is askable any time, but never fires on its own."
        }
    }

    // goal-plans ticket 05 - same shape as reloadSitrepSettings above, minus the module switches
    // that domain has and this one does not.
    suspend fun reloadWellbeingDigestSettings() {
        val schedule = WellbeingDigestSettings.schedule(context)
        if (schedule != null) {
            wellbeingHourText = schedule.hour.toString()
            wellbeingMinuteText = schedule.minute.toString()
            wellbeingScheduleStatus = "Fires daily at %02d:%02d".format(schedule.hour, schedule.minute)
        } else {
            wellbeingScheduleStatus = "No schedule set - the wellbeing digest never fires on its own."
        }
    }

    LaunchedEffect(reloadNonce) {
        reloadSitrepSettings()
        reloadWellbeingDigestSettings()
        // Only needed to personalise ProactiveSpeechRow/WakeWordRow-style copy ("Alfred may speak
        // first") - a lighter read than AssistantSettingsScreen's own reloadActiveProfile, which
        // also carries the persona blurb that only the Companion row needs.
        companionName = CompanionProfileStore.activeProfile(context)?.assistantName
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reloadNonce++
        recallAlertsOn = DebugSettings.recallAlertsEnabled(context)
        scope.launch {
            ProactiveSettings.load(context)
            proactiveOn = ProactiveSettings.master.value
            proactiveCategories = ProactiveSettings.categories.value
        }
    }

    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Proactive speech", onBack = onBack)
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Everything about when it may speak without being asked - lives here, one place.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                Spacer(Modifier.height(12.dp))

                // Mission-control ticket 12: DebugSettings.setRecallAlerts had zero callers before
                // this row - the toggle governed AriaForegroundService.checkRecallsOnce but nothing
                // could ever turn it on.
                RecallAlertsRow(
                    enabled = recallAlertsOn,
                    onToggle = { on ->
                        DebugSettings.setRecallAlerts(context, on)
                        recallAlertsOn = on
                    },
                )

                // Ticket `.scratch/proactive-mode/issues/01-one-gate-not-three.md`: the master kill
                // switch this whole effort depends on.
                Spacer(Modifier.height(8.dp))
                ProactiveSpeechRow(
                    proactiveOn = proactiveOn,
                    companionName = companionName,
                    onToggle = { on ->
                        proactiveOn = on
                        scope.launch { ProactiveSettings.setMaster(context, on) }
                    },
                )

                // The five category switches (ticket 04). They render whether or not the master is
                // on - a user turning proactive back on should find the choices they already made,
                // not a blank slate - and each row says in words when its category has no content
                // yet.
                ProactiveCategory.entries.forEach { category ->
                    Spacer(Modifier.height(4.dp))
                    ProactiveCategoryRow(
                        category = category,
                        enabled = proactiveCategories[category] == true,
                        masterOn = proactiveOn,
                        onToggle = { on ->
                            proactiveCategories = proactiveCategories + (category to on)
                            scope.launch { ProactiveSettings.setCategory(context, category, on) }
                        },
                    )
                }

                // Ticket 22: the sitrep module registry, plus its schedule. Sits right below
                // Digest's own switch/category row above, since a scheduled sitrep is delivered
                // THROUGH that category (ticket 08 §1) - these rows configure WHAT it says, not
                // WHETHER it may.
                Spacer(Modifier.height(8.dp))
                SitrepModule.entries.forEach { module ->
                    Spacer(Modifier.height(4.dp))
                    SitrepModuleRow(
                        module = module,
                        enabled = sitrepModules[module] == true,
                        onToggle = { on ->
                            sitrepModules = sitrepModules + (module to on)
                            scope.launch { SitrepSettings.setModule(context, module, on) }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SitrepScheduleRow(
                    hourText = sitrepHourText,
                    minuteText = sitrepMinuteText,
                    sendersText = sitrepSendersText,
                    onHourChange = { sitrepHourText = it.filter(Char::isDigit).take(2) },
                    onMinuteChange = { sitrepMinuteText = it.filter(Char::isDigit).take(2) },
                    onSendersChange = { sitrepSendersText = it },
                    statusLine = sitrepScheduleStatus,
                    onSave = {
                        val hour = sitrepHourText.toIntOrNull()?.coerceIn(0, 23)
                        val minute = sitrepMinuteText.toIntOrNull()?.coerceIn(0, 59)
                        if (hour == null || minute == null) {
                            sitrepScheduleStatus = "Enter a valid hour (0-23) and minute (0-59) first."
                            return@SitrepScheduleRow
                        }
                        val senders = sitrepSendersText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        scope.launch {
                            SitrepSettings.setSchedule(context, hour, minute, senders)
                            // Re-arms immediately (ticket 22 part D) - a saved schedule with no
                            // armed alarm until the next reboot would be a setting that lies about
                            // being live.
                            SitrepScheduler.schedule(context, hour, minute)
                            sitrepScheduleStatus = "Fires daily at %02d:%02d".format(hour, minute)
                        }
                    },
                )

                // goal-plans ticket 05: the wellbeing digest's own schedule. Sits right below the
                // WELLBEING category row above, same "configures WHEN, not WHETHER" split
                // SitrepScheduleRow's own doc states for DIGEST.
                Spacer(Modifier.height(4.dp))
                WellbeingDigestScheduleRow(
                    hourText = wellbeingHourText,
                    minuteText = wellbeingMinuteText,
                    onHourChange = { wellbeingHourText = it.filter(Char::isDigit).take(2) },
                    onMinuteChange = { wellbeingMinuteText = it.filter(Char::isDigit).take(2) },
                    statusLine = wellbeingScheduleStatus,
                    onSave = {
                        val hour = wellbeingHourText.toIntOrNull()?.coerceIn(0, 23)
                        val minute = wellbeingMinuteText.toIntOrNull()?.coerceIn(0, 59)
                        if (hour == null || minute == null) {
                            wellbeingScheduleStatus = "Enter a valid hour (0-23) and minute (0-59) first."
                            return@WellbeingDigestScheduleRow
                        }
                        scope.launch {
                            WellbeingDigestSettings.setSchedule(context, hour, minute)
                            // Re-arms immediately, same reasoning as the sitrep schedule save above.
                            WellbeingDigestScheduler.schedule(context, hour, minute)
                            wellbeingScheduleStatus = "Fires daily at %02d:%02d".format(hour, minute)
                        }
                    },
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
