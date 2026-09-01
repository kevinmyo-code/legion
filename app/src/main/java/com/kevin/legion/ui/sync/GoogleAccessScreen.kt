package com.kevin.legion.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.gmail.GmailAuth
import com.kevin.legion.gmail.GmailClient
import com.kevin.legion.gmail.GmailToolLogic
import com.kevin.legion.sync.DriveAuth
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `settings/google` - the GOOGLE row's destination: one place to read what LEGION currently has
 * access to in the driver's Google account, across all three independent grants (ticket 06's
 * Answer §2 - "the question 'what does this app have access to in my Google account' deserves
 * exactly one place to read the answer"). Feature surfaces may still *offer* a grant in their own
 * context (the agenda offering `READ_CALENDAR`, per ticket 08, not built yet); this screen is the
 * authoritative status, not the only place a grant can be requested.
 *
 * **Drive's status is a LIVE PROBE, not a stored flag.** [probeDrive] calls [DriveAuth.authorize]
 * fresh every time this screen resumes, exactly the way [DriveSyncScreen] itself would - a stored
 * "connected" boolean can go stale the moment the driver revokes access in their Google account
 * (or a Testing-status grant lapses after 7 days, ticket 01), and this repo already has a bug on
 * record from state published into a StateFlow nobody re-read (commit 31e1a6f). Whether the probe
 * reads as GRANTED, NOT GRANTED, or NEEDS RE-AUTHORISING is derived from TWO signals together,
 * neither sufficient alone: [DriveAuth.authorize]'s live [DriveAuth.Outcome] (Authorized vs.
 * NeedsConsent - the on-device truth right now) and [CompanionProfile.isSyncEnabled] (whether this
 * device has EVER completed the consent round trip before - the only way to tell "never connected"
 * apart from "was connected and it lapsed or was revoked", since [DriveAuth.Outcome.NeedsConsent]
 * is the exact same outcome for both).
 *
 * **Calendar and Gmail are placeholders, not stubs.** They state plainly they are not set up yet
 * (tickets 13 and 15 build them) rather than offering an action that does nothing - CLAUDE.md's
 * worded-state rule applies to "not built" exactly as it does to "not granted".
 *
 * **Drive's own action opens [DriveSyncScreen]**, which already owns the full connect/disconnect/
 * consent round trip (and the backup/restore panel hanging off a connected grant) - this screen
 * does not duplicate that flow, it reads the same underlying grant and points at where to act on
 * it, the same relationship [com.kevin.legion.ui.SettingsScreen]'s nav rows already have with
 * every sub-screen they open.
 */
@Composable
fun GoogleAccessScreen(onBack: () -> Unit, onOpenDriveSync: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var driveStatus by remember { mutableStateOf<DriveGrantStatus?>(null) } // null = probe in flight
    var probing by remember { mutableStateOf(true) }

    fun probeDrive() {
        probing = true
        scope.launch {
            // Read BEFORE the probe, not after: the probe itself never mutates this flag (only
            // DriveSyncScreen's own connect/disconnect actions do), so ordering doesn't matter for
            // correctness, but reading first keeps the intent explicit - "was this device EVER
            // connected" is a question about the past, independent of what the live call below finds.
            val everConnected = CompanionProfile.isSyncEnabled(context)
            driveStatus = when (val outcome = DriveAuth.authorize(context)) {
                is DriveAuth.Outcome.Authorized -> DriveGrantStatus.Granted
                is DriveAuth.Outcome.NeedsConsent ->
                    if (everConnected) DriveGrantStatus.NeedsReauthorising else DriveGrantStatus.NotGranted
                is DriveAuth.Outcome.Failed -> DriveGrantStatus.Error(
                    GoogleGrantResolver.diagnose(
                        grant = GoogleGrantResolver.Grant.DRIVE,
                        statusCode = DriveAuth.statusCodeOf(outcome.error),
                        isNetworkException = DriveAuth.looksLikeNetworkFailure(outcome.error),
                        fallbackMessage = outcome.error.message,
                    ).message,
                )
            }
            probing = false
        }
    }

    // Re-probe on every resume, not just at first composition - this screen is left and
    // returned to (a trip through DriveSyncScreen to reconnect is the whole point of the
    // NEEDS_REAUTHORISING action), which is exactly the moment the probe needs to be fresh.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { probeDrive() }

    // Calendar row REMOVED - one-today ticket 01, "cut Google entirely" (2026-09-01). LEGION's
    // calendar is its own local `events` table now, not a Google Calendar `READ_CALENDAR`/
    // `WRITE_CALENDAR` runtime permission, so there is no grant left for this screen to report on -
    // see `.scratch/one-today/issues/01-one-agenda-source.md` for the full account of what replaced
    // the retired `calendar/CalendarProvider.kt`.

    // Gmail (ticket 15): same live-probe shape as Drive above, but there is no sub-screen to
    // open - Gmail is voice-only (ticket 08 point 4), so this row IS the whole surface. Tapping
    // it either completes the round trip silently (already granted - GmailAuth.authorize just
    // confirms it, no re-prompt) or launches the same interactive consent flow DriveSyncScreen
    // uses, inline, since there is no second screen to host it.
    var gmailStatus by remember { mutableStateOf<GmailGrantStatus?>(null) }
    var gmailProbing by remember { mutableStateOf(true) }
    var gmailWorking by remember { mutableStateOf(false) }

    fun probeGmail() {
        gmailProbing = true
        scope.launch {
            val everGranted = CompanionProfile.isGmailEnabled(context)
            gmailStatus = when (val outcome = GmailAuth.authorize(context)) {
                is GmailAuth.Outcome.Authorized -> GmailGrantStatus.Granted
                is GmailAuth.Outcome.NeedsConsent ->
                    if (everGranted) GmailGrantStatus.NeedsReauthorising else GmailGrantStatus.NotGranted
                is GmailAuth.Outcome.Failed -> GmailGrantStatus.Error(
                    GoogleGrantResolver.diagnose(
                        grant = GoogleGrantResolver.Grant.GMAIL,
                        statusCode = GmailAuth.statusCodeOf(outcome.error),
                        isNetworkException = GmailAuth.looksLikeNetworkFailure(outcome.error),
                        fallbackMessage = outcome.error.message,
                    ).message,
                )
            }
            gmailProbing = false
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { probeGmail() }

    // Step 2 of Gmail's own consent round trip - same pattern as DriveSyncScreen's
    // consentLauncher, a separate instance because it is a separate, independent grant
    // (ticket 06's Answer §1: incremental, never bundled with Drive's).
    val gmailConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        when (val outcome = GmailAuth.tokenFromConsent(context, result.data)) {
            is GmailAuth.ConsentResult.Token -> {
                CompanionProfile.setGmailEnabled(context, true)
                gmailStatus = GmailGrantStatus.Granted
            }
            is GmailAuth.ConsentResult.Cancelled -> {
                // Re-probe rather than assume: a cancel leaves the grant exactly where it was
                // (NotGranted or NeedsReauthorising), and re-probing is what keeps that accurate
                // without duplicating DriveGrantStatus's three-way mapping a second time here.
                probeGmail()
            }
            is GmailAuth.ConsentResult.Failed -> {
                val failure = GoogleGrantResolver.diagnose(
                    grant = GoogleGrantResolver.Grant.GMAIL,
                    statusCode = GmailAuth.statusCodeOf(outcome.error),
                    isNetworkException = GmailAuth.looksLikeNetworkFailure(outcome.error),
                    fallbackMessage = outcome.error.message,
                )
                MidnightEvents.recordError("gmail_connect_consent", outcome.error)
                gmailStatus = GmailGrantStatus.Error(failure.message)
            }
        }
        gmailWorking = false
    }

    fun grantGmail() {
        gmailWorking = true
        scope.launch {
            when (val outcome = GmailAuth.authorize(context)) {
                is GmailAuth.Outcome.Authorized -> {
                    CompanionProfile.setGmailEnabled(context, true)
                    gmailStatus = GmailGrantStatus.Granted
                    gmailWorking = false
                }
                is GmailAuth.Outcome.NeedsConsent -> {
                    // gmailWorking stays true across the launch - the launcher callback above
                    // clears it once the round trip actually resolves, same as Drive's connect().
                    gmailConsentLauncher.launch(
                        IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build(),
                    )
                }
                is GmailAuth.Outcome.Failed -> {
                    val failure = GoogleGrantResolver.diagnose(
                        grant = GoogleGrantResolver.Grant.GMAIL,
                        statusCode = GmailAuth.statusCodeOf(outcome.error),
                        isNetworkException = GmailAuth.looksLikeNetworkFailure(outcome.error),
                        fallbackMessage = outcome.error.message,
                    )
                    MidnightEvents.recordError("gmail_connect", outcome.error)
                    gmailStatus = GmailGrantStatus.Error(failure.message)
                    gmailWorking = false
                }
            }
        }
    }

    // --- Ticket 20's TEST panel -------------------------------------------------------------
    // A PERMANENT diagnostic affordance, not scaffolding left behind by this ticket. It exists
    // because this handset (Oppo A17K, memory/MEMORY.md) filters LEGION's own logcat output -
    // reconfirmed on 2026-08-13 with a 4000-line dump containing zero app lines - so the screen
    // is the only place a real failure can ever be read. GmailToolLogic's four spoken failure
    // messages (ticket 10) are right for Alfred mid-conversation and deliberately collapse a lot
    // of detail into one short sentence each; this panel is the other half, and does the exact
    // opposite on purpose - it renders every signal GmailAuth/GmailClient can produce, verbatim,
    // unmapped, uncollapsed, including the raw HTTP body Google actually put the refusal reason
    // in (unverified-app refusal vs. a disabled API vs. a wrong scope are indistinguishable from
    // a status code alone). Nothing here feeds a live tool call or a spoken message - it is a
    // second, independent probe the driver triggers by hand.
    var gmailTestRunning by remember { mutableStateOf(false) }
    var gmailTestOutcome by remember { mutableStateOf<GmailTestOutcome?>(null) }

    fun runGmailTest() {
        gmailTestRunning = true
        gmailTestOutcome = null
        scope.launch {
            gmailTestOutcome = when (val tokenResult = GmailAuth.tokenOrReason(context)) {
                is GmailAuth.TokenResult.Token -> {
                    // A real briefing call, same query/cap GmailToolLogic actually uses for
                    // `search_mail` with no query - this is not a synthetic probe request, it is
                    // the same call the assistant would make.
                    when (
                        val page = withContext(Dispatchers.IO) {
                            GmailClient(tokenResult.accessToken)
                                .search(GmailToolLogic.BRIEFING_QUERY, GmailToolLogic.BRIEFING_CAP)
                        }
                    ) {
                        is GmailClient.FetchResult.Ok -> GmailTestOutcome.Success(
                            query = GmailToolLogic.BRIEFING_QUERY,
                            count = page.value.messages.size,
                            totalEstimate = page.value.totalEstimate,
                        )
                        is GmailClient.FetchResult.Failed -> GmailTestOutcome.HttpFailed(
                            networkFailure = page.networkFailure,
                            statusCode = page.statusCode,
                            body = page.body,
                        )
                    }
                }
                is GmailAuth.TokenResult.NeedsConsent -> GmailTestOutcome.NeedsConsent(
                    everGranted = CompanionProfile.isGmailEnabled(context),
                )
                is GmailAuth.TokenResult.Failed -> GmailTestOutcome.TokenFailed(
                    statusCode = GmailAuth.statusCodeOf(tokenResult.error),
                    networkFailure = GmailAuth.looksLikeNetworkFailure(tokenResult.error),
                    exception = tokenResult.error.toString(),
                )
            }
            gmailTestRunning = false
        }
    }

    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Google", onBack = onBack)

            Column(
                Modifier
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "What LEGION can reach in your Google account. Each grant is requested only " +
                        "the first time its feature is actually used, never bundled - a Kevin who " +
                        "never asks about mail never grants Gmail.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(12.dp))
                GoogleGrantLine(
                    label = "Google Drive",
                    meaning = "A private folder in your Drive that only LEGION can see - not your real files.",
                    status = driveStatusLabel(probing, driveStatus),
                    attention = driveStatus is DriveGrantStatus.NeedsReauthorising || driveStatus is DriveGrantStatus.Error,
                    onClick = onOpenDriveSync,
                )

                // Calendar row removed - one-today ticket 01, see the comment above where it used
                // to be set up.

                Spacer(Modifier.height(8.dp))
                GoogleGrantLine(
                    label = "Gmail",
                    meaning = "Read your Gmail. LEGION can read mail and search it. It cannot send, reply, or delete.",
                    status = gmailStatusLabel(gmailProbing || gmailWorking, gmailStatus),
                    attention = gmailStatus is GmailGrantStatus.NeedsReauthorising || gmailStatus is GmailGrantStatus.Error,
                    // Granted already: tapping again just re-confirms silently (no re-prompt).
                    // Not granted / lapsed: tapping launches the interactive consent round trip.
                    // There is no separate Gmail screen to route to - this row IS the surface
                    // (ticket 08 point 4, voice-only).
                    onClick = { grantGmail() },
                )

                // TEST: ticket 20's on-screen diagnostic, see the comment on runGmailTest above.
                // Deliberately its own row rather than folded into the GoogleGrantLine tap target
                // above - GRANTED/NOT GRANTED is a status read, TEST is a distinct action that
                // spends a real network call, and the two must not be one tap target.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { runGmailTest() }, enabled = !gmailTestRunning) {
                        Text(
                            if (gmailTestRunning) "TESTING..." else "TEST",
                            style = LegionType.stamp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                gmailTestOutcome?.let {
                    Spacer(Modifier.height(4.dp))
                    GmailTestPanel(it)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The three live states [GoogleAccessScreen.probeDrive] can resolve Drive's grant to, plus the
 * diagnosed-error arm for a probe that failed outright (offline, DEVELOPER_ERROR, etc). */
private sealed interface DriveGrantStatus {
    data object Granted : DriveGrantStatus
    data object NotGranted : DriveGrantStatus
    data object NeedsReauthorising : DriveGrantStatus
    data class Error(val message: String) : DriveGrantStatus
}

private fun driveStatusLabel(probing: Boolean, status: DriveGrantStatus?): String = when {
    probing || status == null -> "Checking..."
    status is DriveGrantStatus.Granted -> "Granted"
    status is DriveGrantStatus.NotGranted -> "Not granted"
    status is DriveGrantStatus.NeedsReauthorising ->
        GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.DRIVE)
    status is DriveGrantStatus.Error -> status.message
    else -> "Checking..."
}

/** The three live states [GoogleAccessScreen.probeGmail] can resolve Gmail's grant to, plus the
 * diagnosed-error arm for a probe or consent round trip that failed outright - including,
 * empirically, a restricted-scope refusal from Google (ticket 09's open question). */
private sealed interface GmailGrantStatus {
    data object Granted : GmailGrantStatus
    data object NotGranted : GmailGrantStatus
    data object NeedsReauthorising : GmailGrantStatus
    data class Error(val message: String) : GmailGrantStatus
}

private fun gmailStatusLabel(busy: Boolean, status: GmailGrantStatus?): String = when {
    busy || status == null -> "Checking..."
    status is GmailGrantStatus.Granted -> "Granted"
    status is GmailGrantStatus.NotGranted -> "Not granted - tap to grant"
    status is GmailGrantStatus.NeedsReauthorising ->
        GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.GMAIL)
    status is GmailGrantStatus.Error -> status.message
    else -> "Checking..."
}

/**
 * The four outcomes a single ticket-20 TEST run can land on, held apart deliberately - this is
 * the sealed hierarchy [GmailTestPanel] renders, and it exists precisely so nothing collapses
 * three distinct [GmailAuth.TokenResult] arms plus [GmailClient.FetchResult]'s HTTP-failure arm
 * into one "something went wrong" string (ticket 20 point 3: a single collapsed line "destroys
 * the only signal this device can give us"). Not shared with [GmailGrantStatus] above on
 * purpose - that type is [probeGmail]'s status-row derivation with its own friendly labels
 * ([gmailStatusLabel]); this type exists only to carry the raw diagnostic fields a debugging
 * session actually needs (status codes, exception text, the raw response body).
 */
private sealed interface GmailTestOutcome {
    /** [GmailAuth.TokenResult.Token] followed by a real [GmailClient.search] briefing call that
     * returned [GmailClient.FetchResult.Ok]. */
    data class Success(val query: String, val count: Int, val totalEstimate: Int) : GmailTestOutcome

    /** [GmailAuth.TokenResult.NeedsConsent] - the grant was never completed, lapsed, or was
     * revoked. [everGranted] is [CompanionProfile.isGmailEnabled], the only signal that tells
     * "never granted" apart from "granted once, lapsed or revoked since" (same distinction
     * [GmailToolLogic.causeForNeedsConsent] draws for the spoken failure message). */
    data class NeedsConsent(val everGranted: Boolean) : GmailTestOutcome

    /** [GmailAuth.TokenResult.Failed] - [GmailAuth.authorize] itself threw, before any HTTP call
     * to Gmail was even attempted (offline, Play Services missing, an unregistered signing
     * cert, or - the live question ticket 09 left open - a restricted-scope refusal from the
     * Identity Authorization API itself, which throws rather than returning NeedsConsent). */
    data class TokenFailed(val statusCode: Int?, val networkFailure: Boolean, val exception: String) : GmailTestOutcome

    /** A token was obtained but the real briefing call through [GmailClient.search] came back
     * [GmailClient.FetchResult.Failed] - the one arm this ticket exists for: [statusCode] and
     * [body] are Gmail's own REST response, verbatim, because that response body is the only
     * place that distinguishes an unverified-app refusal from a disabled API from a wrong scope. */
    data class HttpFailed(val networkFailure: Boolean, val statusCode: Int?, val body: String?) : GmailTestOutcome
}

/**
 * Renders exactly what [GoogleAccessScreen.runGmailTest] got back, unmapped and uncollapsed -
 * see that function's doc comment for why this exists and why it is permanent. The headline
 * names which of [GmailTestOutcome]'s four arms fired (never a generic "something went wrong"),
 * coloured with [LocalLegionSemantics.credit] for the one outcome that actually worked and
 * [LocalLegionSemantics.estimated] for the other three (ADVISORY per ticket 04's tiers,
 * mission-control ticket 13 re-home - a lapsed grant or a failed HTTP call is a blocked
 * capability, not a failed reconciliation gate) - all of which need the driver's attention,
 * whether that's "go grant it" or "here's a body to paste into the ticket". The body text sits
 * in a [SelectionContainer] so it can be long-pressed and copied straight into the ticket file,
 * which is the whole reason this exists on a handset that filters its own logcat.
 */
@Composable
private fun GmailTestPanel(outcome: GmailTestOutcome) {
    val sem = LocalLegionSemantics.current
    val (headline, color, body) = when (outcome) {
        is GmailTestOutcome.Success -> Triple(
            "TokenResult.Token -> briefing call SUCCEEDED",
            sem.credit,
            "query: ${outcome.query}\n" +
                "messages returned: ${outcome.count}\n" +
                "resultSizeEstimate: ${outcome.totalEstimate}",
        )
        is GmailTestOutcome.NeedsConsent -> Triple(
            "TokenResult.NeedsConsent",
            sem.estimated,
            "CompanionProfile.isGmailEnabled (ever granted before): ${outcome.everGranted}\n" +
                if (outcome.everGranted) {
                    "-> grant lapsed (7-day Testing-status expiry) or was revoked"
                } else {
                    "-> this device has never completed the consent round trip"
                },
        )
        is GmailTestOutcome.TokenFailed -> Triple(
            "TokenResult.Failed - GmailAuth.authorize threw",
            sem.estimated,
            "GmailAuth.statusCodeOf: ${outcome.statusCode?.toString() ?: "null (not an ApiException)"}\n" +
                "GmailAuth.looksLikeNetworkFailure: ${outcome.networkFailure}\n" +
                "exception: ${outcome.exception}",
        )
        is GmailTestOutcome.HttpFailed -> Triple(
            "TokenResult.Token -> briefing call FAILED (HTTP)",
            sem.estimated,
            "networkFailure: ${outcome.networkFailure}\n" +
                "HTTP status: ${outcome.statusCode?.toString() ?: "unknown"}\n" +
                "response body:\n${outcome.body ?: "(no body)"}",
        )
    }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(headline, style = MaterialTheme.typography.bodyMedium, color = color)
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(body, style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * One grant's line: what it is, what it means in plain words (ticket 06 point 3's wording,
 * verbatim per grant), and its live status. [onClick] is null for a grant with no action yet
 * (Calendar) - a plain, non-interactive line rather than a chevron promising a screen
 * that doesn't exist. Same deck styling as [com.kevin.legion.ui.SettingsNavRow]: a tonal
 * Surface, the label at body weight, everything else at [LegionType.stamp].
 */
@Composable
private fun GoogleGrantLine(
    label: String,
    meaning: String,
    status: String,
    attention: Boolean,
    onClick: (() -> Unit)?,
) {
    val sem = LocalLegionSemantics.current
    Surface(
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(meaning, style = LegionType.stamp, color = sem.faint)
                }
                if (onClick != null) {
                    Text(">", style = LegionType.stamp, color = sem.faint)
                }
            }
            Spacer(Modifier.height(6.dp))
            // ADVISORY (ticket 13 re-home): a grant needing attention, not a failed gate.
            Text(status, style = LegionType.stamp, color = if (attention) sem.estimated else sem.faint)
        }
    }
}
