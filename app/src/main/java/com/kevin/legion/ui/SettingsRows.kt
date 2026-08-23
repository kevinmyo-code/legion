package com.kevin.legion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.TempUnit
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.AssistantIgnition

/**
 * Plain UI half of [SettingsScreen] (the state-holder/UI split,
 * `.claude/skills/compose-state-holder-ui-split`, same shape as
 * `ui/sync/DriveSyncRows.kt` and `ui/spotify/SpotifyRows.kt`). Everything here
 * is display plus callbacks - no `Context`, no permission launcher, no
 * [com.kevin.legion.service.AssistantIgnition] - so every state previews.
 *
 * **Each row states its own current status in words**, not just its name. The
 * old screen was four bare M3 `Button`s with nothing but a label, so the only
 * way to find out whether the Gemini key was set, Drive was connected, or
 * Spotify was half-configured was to open each screen in turn and look. That
 * matters more here than on most screens: this menu is the entry point for
 * every credential the app holds, and CLAUDE.md §7's worded-state rule applies
 * to "is this configured" exactly as it does to ingestion provenance.
 */

/**
 * One tappable settings destination: what it is, what state it is in right
 * now, and a chevron. [status] is the worded state ("Not set", "Connected");
 * [attention] draws it in [LegionSemantics.estimated] for a state that
 * genuinely blocks something, rather than the ordinary faint - ADVISORY per
 * ticket 04's tiers (mission-control ticket 13 re-home: "not configured" was
 * never a failed gate or an active fault), not [LegionSemantics.quarantined].
 */
@Composable
fun SettingsNavRow(
    label: String,
    status: String,
    onClick: () -> Unit,
    attention: Boolean = false,
) {
    val sem = LocalLegionSemantics.current
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    status,
                    style = LegionType.stamp,
                    color = if (attention) sem.estimated else sem.faint,
                )
            }
            Text(">", style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The assistant ignition toggle. Unchanged in substance from the version that
 * lived inside [SettingsScreen] - the permission chain still belongs to the
 * state holder; this only draws the switch and the refusal line.
 */
@Composable
fun IgnitionRow(
    enabled: Boolean,
    refusalReason: String?,
    onToggle: (Boolean) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Assistant", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        // Report REALITY, not the flag. Those two came apart on the A25 on
                        // 2026-08-21: the process restarted in the background, Android refused the
                        // foreground-service start, and this row said "On" for 45 minutes while
                        // nothing ran - a call came in with no announcement and nobody found out
                        // until the log was read. A switch that describes a stored boolean rather
                        // than the running system is the same class of lie as a kill switch that
                        // does not silence.
                        when {
                            !enabled -> "Off"
                            AriaForegroundService.isRunning -> "On - tap to talk strip is showing"
                            AssistantIgnition.startRefused ->
                                "On, but NOT running - Android blocked it from starting in the " +
                                    "background. Reopening the app fixes it."
                            else -> "On - starting..."
                        },
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (refusalReason != null) {
                // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
                Text(refusalReason, style = LegionType.stamp, color = sem.estimated)
            }
        }
    }
}

/**
 * Whether Zero proactively mentions open NHTSA recalls once at startup
 * ([com.kevin.legion.service.AriaForegroundService.checkRecallsOnce]). Off by default - a network
 * call made on the driver's behalf every launch, not on request - and, since mission-control
 * ticket 12 (`.scratch/fleet-maintenance/issues/12-a-recall-button.md`), gated the same
 * identity-present way as the on-request check under Fleet -> Specs and the `check_recalls` voice
 * tool. Ticket 12's finding: [com.kevin.legion.service.DebugSettings.setRecallAlerts] had zero
 * callers before this row - a preference nobody could change, gating a proactive nobody had seen.
 */
@Composable
fun RecallAlertsRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Recall alerts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (enabled) {
                        "On - Zero mentions it once at startup if NHTSA lists any open recall"
                    } else {
                        "Off - check any time under Fleet -> Specs"
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * The proactive master switch. **Backed by [com.kevin.legion.service.ProactiveSettings.master] in
 * Room since 2026-08-21** (ticket 04); it used to read
 * [com.kevin.legion.service.ProactivePreferences]'s inverted `muted` boolean, which is now a
 * migration source and gates nothing. The row still reads "Proactive speech", not "Muted". `.scratch/proactive-mode/issues/
 * 01-one-gate-not-three.md` (2026-08-18): `setMuted`/`toggle` had zero callers anywhere before this
 * row, so the kill switch this whole effort depends on had never been reachable by a human. Uses
 * [DeckSwitch] rather than [RecallAlertsRow]/[IgnitionRow]'s raw Material `Switch` - those predate
 * ticket 09's deck control set; this is new work and adopts it directly rather than copying the
 * pattern this map is here to move away from.
 *
 * **A true kill switch, stated as one** (settled decision 2, `.scratch/proactive-mode/map.md`):
 * flipping this off does not just quiet nudges. It silences every line Alfred would otherwise say
 * unprompted - openers, alerts, reminders, and the incoming-call announcement alike, all now routed
 * through [com.kevin.legion.service.ProactiveBus.speakIfAllowed] - and separately stops
 * ambient listening at all, not merely from reacting (Kevin's explicit requirement at the time).
 * **Ambient listening was RETIRED 2026-08-21** (`.scratch/proactive-mode/issues/
 * 12-retire-ambient-listening.md`), so the status line no longer claims to stop it - a switch that
 * promises to silence something that no longer exists is the same class of lie this row was
 * written to avoid. The kill-switch semantics for SPEECH are unchanged.
 *
 * **The name is not hardcoded any more (2026-08-21).** This row said "Alfred" in both states, which
 * CLAUDE.md §1 forbids - LEGION is the app and the companion is user-named per profile. It now takes
 * [companionName] the same way [WakeWordRow] already did, and falls back to "your companion" rather
 * than inventing one when no profile is active.
 *
 * **The off copy now names safety explicitly.** Settled decision 2: the master has NO exemptions,
 * safety included. A switch whose description lists "openers, alerts, reminders" and quietly omits
 * the one category a user would most expect to survive is not describing a kill switch honestly.
 *
 * Sits above the five [ProactiveCategoryRow]s, which it ANDs over.
 */
@Composable
fun ProactiveSpeechRow(proactiveOn: Boolean, companionName: String?, onToggle: (Boolean) -> Unit) {
    val who = companionName?.takeIf { it.isNotBlank() } ?: "your companion"
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Proactive speech", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (proactiveOn) {
                            "On - $who may speak first, in the categories you pick below"
                        } else {
                            "Off - stops every unprompted line, including safety warnings and " +
                                "incoming-call announcements. Talking to $who yourself still works."
                        },
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                DeckSwitch(checked = proactiveOn, onCheckedChange = onToggle)
            }
        }
    }
}

/**
 * One of the five category switches (ticket 04 call 4,
 * `.scratch/proactive-mode/issues/04-categories-storage-and-surface.md`). Governed by
 * [ProactiveSpeechRow]'s master, which ANDs over all of them.
 *
 * **A switch that governs nothing must not imply it does.** Any category with no raise wired to it
 * ([com.kevin.legion.service.ProactiveCategory.hasContent] false) gets this row **saying so in
 * words** - not by hiding, not by greying out, not by silence. Hiding it would cost the map of what
 * is coming; a greyed control with no explanation is its own kind of confusing. Same posture as the
 * digest builders' "not logged, never 0" and as the master row's own status line. All five
 * categories carry content as of goal-plans ticket 05 (Wellbeing's own scheduled digest), but the
 * next category anyone adds will land here empty first, same as every one before it did.
 *
 * It stays TOGGLEABLE while empty, deliberately: the setting is real and persists, so a user who
 * turns an empty category on today gets its first nudge the day one is written, rather than having
 * to come back and find a switch that has quietly become live.
 */
@Composable
fun ProactiveCategoryRow(
    category: ProactiveCategory,
    enabled: Boolean,
    masterOn: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    category.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        !category.hasContent -> category.blurb + " Nothing uses this yet."
                        !masterOn -> category.blurb + " Silent while proactive speech is off."
                        else -> category.blurb
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            DeckSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * One sitrep module switch (ticket 22, `.scratch/hands-and-senses/issues/22-build-the-sitrep.md`)
 * - shape lifted straight from [ProactiveCategoryRow], the ticket's own instruction to follow
 * that file "exactly as the pattern." No `masterOn` parameter here, unlike that row: the sitrep
 * has no master kill switch of its own ([com.kevin.legion.sitrep.SitrepSettings]'s own class doc
 * explains why - a sitrep is produced only when asked for, never gated at all, and since ticket 32
 * (Kevin: "sitreps stay tap only or via voice activation only") that is the ONLY path there is.
 */
@Composable
fun SitrepModuleRow(
    module: com.kevin.legion.sitrep.SitrepModule,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(module.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(module.blurb, style = LegionType.stamp, color = sem.faint)
            }
            DeckSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * The sitrep's curated newsletter sender list (ticket 22 part F, narrowed by ticket 32 - Kevin:
 * "sitreps stay tap only or via voice activation only"). Used to be one row with a schedule time
 * attached ([com.kevin.legion.data.local.SitrepSchedule]'s `hour`/`minute`); ticket 32 retired the
 * alarm those fields armed, so this row is senders only now. [onSave] is called with the row's
 * whole state at once so the caller can validate/persist it as one write, same as
 * [com.kevin.legion.ui.common.DeckTextField]'s callers elsewhere on this screen.
 *
 * **Newsletter senders are curated by Kevin, by hand** (ticket 08 §6) - this field is a plain
 * comma-separated text entry, never a picker or a suggestion list; nothing here infers a sender.
 * An empty list is a real, supported state, not an error - [com.kevin.legion.sitrep.SitrepBuilder]
 * falls back to Gmail's own newsletter/promo classification when nothing is curated.
 */
@Composable
fun SitrepNewsSendersRow(
    sendersText: String,
    onSendersChange: (String) -> Unit,
    onSave: () -> Unit,
    statusLine: String,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Sitrep newsletter senders", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(statusLine, style = LegionType.stamp, color = sem.faint)
            Spacer(Modifier.height(8.dp))
            com.kevin.legion.ui.common.DeckTextField(
                value = sendersText, onValueChange = onSendersChange,
                label = "Newsletter senders (comma-separated)",
            )
            Spacer(Modifier.height(8.dp))
            DeckButton(text = "Save senders", onClick = onSave)
        }
    }
}

/**
 * The wellbeing digest's schedule time (goal-plans ticket 05,
 * `.scratch/goal-plans/issues/05-wellbeing-digest.md`) - a two-field typed hour/minute, the
 * smallest thing that does not require inventing a time-picker component this app's clean-slate
 * `ui/` does not have yet (CLAUDE.md: STOP and surface a missing design primitive rather than
 * improvise one). **This is a different feature from the sitrep and is unaffected by ticket 32**
 * (Kevin, goal-plans 05: its own compulsion-test reasoning) - the wellbeing digest still fires on
 * a schedule Kevin sets, here.
 *
 * **No enable switch here** - the real kill switch is [ProactiveCategoryRow]'s own WELLBEING row,
 * which sits above this one on the settings screen. This row only configures WHEN the digest
 * fires, never WHETHER it may.
 */
@Composable
fun WellbeingDigestScheduleRow(
    hourText: String,
    minuteText: String,
    onHourChange: (String) -> Unit,
    onMinuteChange: (String) -> Unit,
    onSave: () -> Unit,
    statusLine: String,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Wellbeing digest schedule", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(statusLine, style = LegionType.stamp, color = sem.faint)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.kevin.legion.ui.common.DeckTextField(
                    value = hourText, onValueChange = onHourChange, label = "Hour (0-23)",
                    modifier = Modifier.weight(1f),
                )
                com.kevin.legion.ui.common.DeckTextField(
                    value = minuteText, onValueChange = onMinuteChange, label = "Minute (0-59)",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            DeckButton(text = "Save schedule", onClick = onSave)
        }
    }
}

/**
 * Caller ID and voice call control (2026-08-21, Kevin: *"i wanna know whos calling ... can i also
 * pick it up or decline via voice?"*; `CALL_PHONE` joined 2026-08-22 for `place_call`, ticket 26).
 *
 * **Four permissions, asked for together, because they are one feature to a human** - and the row
 * says what each buys rather than listing Android constants at someone. `READ_CALL_LOG` is what
 * actually delivers the caller's number (without it the platform substitutes an empty string, which
 * is why the announcement could only ever say "a call is ringing"); `READ_CONTACTS` turns that
 * number into a name (both ways - naming an incoming caller, and resolving a contact to dial);
 * `ANSWER_PHONE_CALLS` is what lets "answer it" and "decline it" do anything; `CALL_PHONE` is what
 * lets "call Mom" dial directly instead of only opening the dialer.
 *
 * **The status line degrades honestly**, listing exactly which of the three capabilities
 * (see-who's-calling, answer/decline, place a call) are actually on rather than collapsing to a
 * single on/off. A row claiming "on" while part of the feature is refused would be the same lie the
 * proactive kill-switch copy was fixed for.
 *
 * Kept deliberately quiet about one thing: the app cannot answer WhatsApp, Signal or Teams calls
 * whatever is granted here, because Android silently ignores those for a non-dialer app. That
 * belongs in the moment it happens - the tool result says it - not as fine print on a switch.
 */
@Composable
fun CallHandlingRow(
    canSeeCaller: Boolean,
    canAnswer: Boolean,
    canPlace: Boolean,
    onGrant: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Calls",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        canSeeCaller && canAnswer && canPlace ->
                            "On - says who is calling, you can answer or decline by voice, and " +
                                "place calls by voice."
                        !canSeeCaller && !canAnswer && !canPlace ->
                            "Off - announces that a call is ringing, but not who, and cannot pick " +
                                "it up or place one for you."
                        else -> {
                            val on = buildList {
                                if (canSeeCaller) add("see who's calling")
                                if (canAnswer) add("answer or decline")
                                if (canPlace) add("place calls")
                            }
                            val off = buildList {
                                if (!canSeeCaller) add("see who's calling")
                                if (!canAnswer) add("answer or decline")
                                if (!canPlace) add("place calls")
                            }
                            "Partly on - can ${on.joinToString(", ")}, but not ${off.joinToString(", ")}."
                        }
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            if (!canSeeCaller || !canAnswer || !canPlace) {
                TextButton(onClick = onGrant) { Text("GRANT") }
            }
        }
    }
}

/**
 * Background location access (`.scratch/location-intelligence/issues/01-background-location.md`,
 * settled decision 11) - `location.LocationAccessState`'s three states, worded honestly rather
 * than collapsed into a switch. Shape lifted straight from [CallHandlingRow] per the ticket's own
 * instruction to match it: `Surface` + `tonalElevation`, title in `bodyMedium`, status in
 * [LegionType.stamp]/[sem.faint], one `TextButton` when there's still something to grant.
 *
 * **[LocationAccessState.ForegroundOnly] is not an error state and the copy says so.** Declining
 * "Allow all the time" is a real, reasonable choice - most people do, the first time they're asked
 * for it - and the row's job is to report what that choice actually costs (geofences and hazard
 * checks only run while the app is open), never to shame the choice or hide the cost. This is the
 * exact same "refusal degrades in words, never silently" posture [CallHandlingRow] already carries.
 *
 * `onGrant` is a single callback regardless of state - the caller ([SettingsScreen]) is the one
 * that knows whether the next tap should launch the foreground `RequestMultiplePermissions` dialog
 * or the background follow-up or send the driver to the app's Settings page, because that decision
 * needs `shouldShowRequestPermissionRationale`, which only an `Activity` can answer. This row stays
 * plain UI with no `Context` in it, same split as every other row here.
 */
@Composable
fun LocationAccessRow(state: com.kevin.legion.location.LocationAccessState, onGrant: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Location",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when (state) {
                        is com.kevin.legion.location.LocationAccessState.Granted ->
                            "On - place reminders and hazard alerts work even with the app closed."
                        is com.kevin.legion.location.LocationAccessState.ForegroundOnly ->
                            "Partly on - place reminders and hazard alerts only work while LEGION " +
                                "is open. \"Allow all the time\" is what fixes that."
                        is com.kevin.legion.location.LocationAccessState.None ->
                            "Off - place reminders and hazard alerts can't work at all."
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            if (state != com.kevin.legion.location.LocationAccessState.Granted) {
                TextButton(onClick = onGrant) { Text("GRANT") }
            }
        }
    }
}

/**
 * The custom wake word ([com.kevin.legion.service.WakeWordEngine]), reachable by a human for the
 * first time. `.scratch/wake-word/issues/02-the-settings-toggle.md`: the engine has been complete
 * and wired into [com.kevin.legion.service.AriaForegroundService] since the port, but
 * [com.kevin.legion.service.WakeWordPreferences.setEnabled] had **zero callers anywhere**, so
 * `WakeWordEngine.start` always returned at its first line and the feature could not be turned on
 * at all. Same shape as [ProactiveSpeechRow]'s finding, and the same fix.
 *
 * **The name is not hardcoded.** The grammar Vosk matches is built at runtime from the active
 * [com.kevin.legion.ai.CompanionProfile]'s name, so this row renders whatever the driver actually
 * called their companion (CLAUDE.md sec 1: LEGION is the app, the companion is user-named). A null
 * name means no profile is active yet, and the row says so rather than inventing one.
 *
 * **Off by default, and a supplement rather than a replacement** - push-to-talk keeps working
 * whatever this says. Kept deliberately quiet about battery cost: the engine's only on-hardware
 * validation (2026-07-19) measured a head unit on permanent shore power, a premise phone-only
 * retired, and `.scratch/wake-word/issues/03-measure-the-battery-cost.md` exists to produce the
 * real number. Until it does, this row states no cost, because CLAUDE.md sec 7 forbids presenting
 * an unmeasured figure as a fact.
 */
@Composable
fun WakeWordRow(enabled: Boolean, companionName: String?, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wake word", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        when {
                            !enabled ->
                                "Off - press to talk. Turning this on listens for a wake phrase " +
                                    "continuously while the assistant is running."
                            companionName.isNullOrBlank() ->
                                "On - but no companion is active yet, so there is no name to listen " +
                                    "for. Pick one in Companions."
                            else ->
                                "On - say \"hey ${companionName.lowercase()}\" to start a turn. " +
                                    "Press to talk still works."
                        },
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                DeckSwitch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

/**
 * The driver's chosen temperature unit ([com.kevin.legion.util.Temp]), a two-way choice with no
 * destination screen of its own - ticket 07, amended 2026-08-18 to make the unit a setting rather
 * than fixed Celsius. Uses [DeckRadio] rather than a Material `RadioButton`/`Switch` pair,
 * matching the deck control set the other new mission-control rows on this screen already prefer
 * over raw Material controls.
 */
@Composable
fun TemperatureUnitRow(unit: TempUnit, onSelect: (TempUnit) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Temperature unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Coolant and intake-air readings, everywhere they're shown or spoken.",
                style = LegionType.stamp,
                color = sem.faint,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                DeckRadio(selected = unit == TempUnit.CELSIUS, onClick = { onSelect(TempUnit.CELSIUS) }, label = TempUnit.CELSIUS.spokenWord)
                DeckRadio(selected = unit == TempUnit.FAHRENHEIT, onClick = { onSelect(TempUnit.FAHRENHEIT) }, label = TempUnit.FAHRENHEIT.spokenWord)
            }
        }
    }
}

/**
 * The "who is active" line. [name] null means the roster hasn't loaded yet or
 * (a genuinely fresh install, pre-onboarding) no profile is active on this
 * device at all; both render as "No companion set up yet" rather than blank
 * space, matching CLAUDE.md's say-plainly-what-is-not-set posture.
 */
@Composable
fun ActiveCompanionRow(name: String?, blurb: String?, onOpenCompanions: () -> Unit) {
    SettingsNavRow(
        label = "Companion",
        status = if (name != null) listOfNotNull(name, blurb).joinToString(" - ") else "No companion set up yet",
        onClick = onOpenCompanions,
    )
}

/**
 * The ledger purge, re-homed from CRED's own root (mission-control ticket 16, ticket 12's ruling:
 * "a destructive purge does not belong on a surface you open daily"). Content and confirm shape
 * are UNCHANGED from the old `ui.LedgerScreen.PurgeLedgerRow` - two taps, never one, the second one
 * saying exactly what it destroys, `armed` resetting on a fresh `remember` so leaving Setup and
 * coming back lands on the safe state - only the CONTROL migrated, from a bare [TextButton] to
 * [DeckButton] (ticket 16's binding: "migrate any control you touch to DeckControls"), which is
 * also what lets this row carry ticket 04 answer §4's real destructive treatment for the first
 * time: neutral `ink` outline every day (`destructive = true, confirming = false`), full `chrome`
 * fill spent only on the confirming second tap (`confirming = true`) - the bare [TextButton] this
 * replaces had no such distinction to begin with.
 */
@Composable
fun PurgeLedgerRow(onPurge: () -> Unit) {
    val sem = LocalLegionSemantics.current
    var armed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text("Ledger", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (armed) {
                "This deletes every imported transaction and forgets every file already scanned. " +
                    "Fleet and pantry data are not touched. This cannot be undone."
            } else {
                "Delete every imported transaction and rescan the folder from scratch."
            },
            style = LegionType.stamp,
            color = if (armed) sem.chrome else sem.faint,
        )
        Spacer(Modifier.height(6.dp))
        // `Modifier.fillMaxWidth()` on the Row plus `Modifier.weight(1f)` on each armed-state
        // button is load-bearing, not decoration - caught on-device (mission-control ticket 16):
        // without it, a bare `Row` gives each child an effectively unbounded measuring pass, "YES,
        // PURGE THE LEDGER" claims almost the entire row on its own natural width, and CANCEL is
        // left so little room its own Text wraps one glyph per line. Even width split, never
        // wrap-per-glyph. The everyday (unarmed) single-button state is left un-weighted so it
        // keeps its old compact, content-sized box rather than stretching full width for no reason.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DeckButton(
                text = if (armed) "YES, PURGE THE LEDGER" else "PURGE LEDGER",
                onClick = { if (armed) onPurge() else armed = true },
                destructive = true,
                confirming = armed,
                modifier = if (armed) Modifier.weight(1f) else Modifier,
            )
            if (armed) {
                DeckButton(text = "CANCEL", onClick = { armed = false }, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Settings row: key not set", widthDp = 360)
@Composable
private fun PreviewRowAttention() = LegionTheme {
    Surface { SettingsNavRow(label = "Gemini key", status = "Not set", onClick = {}, attention = true) }
}

@Preview(name = "Settings row: ordinary", widthDp = 360)
@Composable
private fun PreviewRowOrdinary() = LegionTheme {
    Surface { SettingsNavRow(label = "Google", status = "Drive connected", onClick = {}) }
}

@Preview(name = "Settings: ignition off", widthDp = 360)
@Composable
private fun PreviewIgnitionOff() = LegionTheme {
    Surface { IgnitionRow(enabled = false, refusalReason = null, onToggle = {}) }
}

@Preview(name = "Settings: ignition on", widthDp = 360)
@Composable
private fun PreviewIgnitionOn() = LegionTheme {
    Surface { IgnitionRow(enabled = true, refusalReason = null, onToggle = {}) }
}

@Preview(name = "Settings: ignition refused", widthDp = 360)
@Composable
private fun PreviewIgnitionRefused() = LegionTheme {
    Surface {
        IgnitionRow(
            enabled = false,
            refusalReason = "Microphone permission was refused. The assistant needs it to hear " +
                "you - ledger, pantry, and fleet are unaffected.",
            onToggle = {},
        )
    }
}

@Preview(name = "Settings: no companion yet", widthDp = 360)
@Composable
private fun PreviewCompanionNone() = LegionTheme {
    Surface { ActiveCompanionRow(name = null, blurb = null, onOpenCompanions = {}) }
}

@Preview(name = "Settings: companion active", widthDp = 360)
@Composable
private fun PreviewCompanionActive() = LegionTheme {
    Surface { ActiveCompanionRow(name = "Aria", blurb = "dry, competent, unbothered", onOpenCompanions = {}) }
}

@Preview(name = "Settings: purge ledger, everyday state", widthDp = 360)
@Composable
private fun PreviewPurgeLedgerRowNeutral() = LegionTheme {
    Surface { PurgeLedgerRow(onPurge = {}) }
}

@Preview(name = "Settings: recall alerts off", widthDp = 360)
@Composable
private fun PreviewRecallAlertsOff() = LegionTheme {
    Surface { RecallAlertsRow(enabled = false, onToggle = {}) }
}

@Preview(name = "Settings: recall alerts on", widthDp = 360)
@Composable
private fun PreviewRecallAlertsOn() = LegionTheme {
    Surface { RecallAlertsRow(enabled = true, onToggle = {}) }
}

@Preview(name = "Settings: proactive speech on", widthDp = 360)
@Composable
private fun PreviewProactiveSpeechOn() = LegionTheme {
    Surface { ProactiveSpeechRow(proactiveOn = true, companionName = "Alfred", onToggle = {}) }
}

@Preview(name = "Settings: wake word off", widthDp = 360)
@Composable
private fun PreviewWakeWordOff() = LegionTheme {
    Surface { WakeWordRow(enabled = false, companionName = "Alfred", onToggle = {}) }
}

@Preview(name = "Settings: wake word on", widthDp = 360)
@Composable
private fun PreviewWakeWordOn() = LegionTheme {
    Surface { WakeWordRow(enabled = true, companionName = "Dorothy", onToggle = {}) }
}

// The state the row exists to not lie about: on, but nothing to listen for.
@Preview(name = "Settings: wake word on, no companion", widthDp = 360)
@Composable
private fun PreviewWakeWordNoCompanion() = LegionTheme {
    Surface { WakeWordRow(enabled = true, companionName = null, onToggle = {}) }
}

@Preview(name = "Settings: proactive speech off", widthDp = 360)
@Composable
private fun PreviewProactiveSpeechOff() = LegionTheme {
    Surface { ProactiveSpeechRow(proactiveOn = false, companionName = "Alfred", onToggle = {}) }
}
