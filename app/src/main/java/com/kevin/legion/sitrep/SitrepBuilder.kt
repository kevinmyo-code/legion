package com.kevin.legion.sitrep

import android.content.Context
import com.kevin.legion.advisor.DigestText
import com.kevin.legion.advisor.digest.FleetDigestBuilder
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.calendar.CalendarProvider
import com.kevin.legion.gmail.GmailAuth
import com.kevin.legion.gmail.GmailClient
import com.kevin.legion.gmail.GmailToolLogic
import com.kevin.legion.weather.WeatherController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Composes the sitrep - ticket 22 (`.scratch/hands-and-senses/issues/22-build-the-sitrep.md`), the
 * BUILD for the decision ticket 08 (`08-morning-brief.md`) made. [build] takes no clock-of-day
 * opinion at all - "sitrep" is time-agnostic by ticket 08's own naming call. **Askable only, never
 * scheduled** - ticket 32 (`.scratch/hands-and-senses/issues/32-sitrep-on-demand-only.md`, Kevin:
 * "sitreps stay tap only or via voice activation only") deleted the `SitrepScheduler`/
 * `SitrepAlarmReceiver` pair that used to call this on a timer; the only callers left are
 * [com.kevin.legion.service.LiveToolbox]'s `get_sitrep` dispatch and the Home card's tap.
 *
 * **Three of four sections are deterministic; only [SitrepModule.NEWS] ever reaches an LLM**
 * (ticket 08's resolution §3, restated at [SitrepModule]'s own doc): CALENDAR/WEATHER/FLEET are
 * formatted directly off [CalendarProvider]/[WeatherController]/[FleetDigestBuilder], using
 * [DigestText]'s shared vocabulary - `[proven]`/`[reported]`, `(estimate)`, and "not logged, never
 * 0" - so a sitrep figure never disagrees with the same figure read anywhere else in the app. NEWS
 * is the one module whose source is genuinely prose (a newsletter body) rather than a number or a
 * schedule, so it is the one place a single [SubAgent] one-shot call summarizes rather than the app
 * composing a sentence itself.
 *
 * **Read-through holds for NEWS** (ticket 08 §4/ticket 22's hard rule): [newsSectionLive] fetches
 * message bodies, folds them into one prompt, gets a summary back, and lets every local variable
 * holding a body or the summary fall out of scope at the end of that function. **Nothing here
 * writes a Room row, a `CompanionMemory` entry, or an `EpisodicTurn` - not even the summary.** The
 * consequence ticket 08 accepted stands: "what did yesterday's sitrep say" cannot be answered from
 * storage, because nothing was stored to answer it from.
 *
 * **Background Gmail fetch is permitted ONLY here** (ticket 08's narrow amendment to the
 * google-account map's "no background/proactive Gmail fetch" rule) - inside a sitrep the user
 * scheduled or explicitly asked for, nowhere else in the app.
 *
 * **No-config default (command-center ticket 12, Kevin 2026-08-22: "take from my gmail >
 * summarize").** [SitrepSettings.newsletterSenders] used to be a hard requirement - an empty list
 * meant the module simply refused to run. It is now an OVERRIDE: when Kevin has curated senders,
 * [resolveNewsletterQuery] scopes the search to exactly them, unchanged from before; when he has
 * not, it falls back to [NO_CONFIG_NEWSLETTER_QUERY], which finds newsletter-shaped mail directly
 * off Gmail's own classification rather than refusing. Read-through and the message cap below are
 * unaffected either way - the only thing the default changes is which `q` string gets built.
 */
object SitrepBuilder {

    /** How far ahead the CALENDAR module looks - matches [com.kevin.legion.calendar
     * .OpenerCalendarBriefing]'s reasoning for a bounded window (long enough to catch "later
     * today", short enough that a sitrep reads as "right now" rather than narrating a week), widened
     * to a full day here because a sitrep, unlike the startup opener, is explicitly framed as a
     * status report rather than a greeting aside. */
    private const val CALENDAR_WINDOW_MS = 24L * 60 * 60 * 1000

    /** Hard cap on how many newsletter messages one sitrep will fetch and summarize - a status
     * report, not a mail archive dig. Mirrors [GmailToolLogic.SEARCH_CAP]'s own "a lookup, not a
     * survey" reasoning rather than reusing that constant directly, since this cap governs full
     * bodies fetched (real cost, real latency) where `SEARCH_CAP` governs metadata-only hits. */
    private const val NEWS_MESSAGE_CAP = 5

    private val EVENT_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /**
     * The full sitrep, respecting per-module settings AND an optional caller-supplied [modules]
     * filter - `get_sitrep`'s own contract (ticket 22 part C: "ONE tool, optional `modules` filter
     * param"). [modules] narrows what [SitrepSettings.enabledModules] already allows; it can never
     * WIDEN it - a module Kevin switched off in settings stays off even if asked for by name,
     * because a settings switch that a spoken request can override is not a switch.
     */
    suspend fun build(context: Context, modules: Set<SitrepModule>? = null): String {
        SitrepSettings.load(context)
        val requested = resolveRequestedModules(modules, SitrepSettings.enabledModules())

        if (requested.isEmpty()) {
            return if (modules != null && modules.isNotEmpty()) {
                "None of the modules you asked for are enabled in sitrep settings."
            } else {
                "Every sitrep module is switched off in settings."
            }
        }

        val sections = mutableMapOf<SitrepModule, String>()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        if (SitrepModule.CALENDAR in requested) {
            val hasPermission = CalendarProvider.hasReadPermission(context)
            val events = if (hasPermission) {
                CalendarProvider.eventsInWindow(context, now, now + CALENDAR_WINDOW_MS)
            } else {
                emptyList()
            }
            sections[SitrepModule.CALENDAR] = calendarSection(hasPermission, events, now, zone)
        }

        if (SitrepModule.WEATHER in requested) {
            WeatherController.refresh()
            sections[SitrepModule.WEATHER] = weatherSection(WeatherController.current())
        }

        // FleetDigestBuilder is reused WHOLESALE, per the ticket's own instruction ("reuse
        // advisor/digest/FleetDigestBuilder's reads or the same controllers") - its own class doc
        // already establishes every fleet figure's REPORTED tier and the "- guess, unconfirmed"
        // suffix discipline; re-deriving a second, thinner fleet summary here would be a second
        // place to get MaintenanceItem's due-axis semantics wrong (exactly what that file's own
        // class doc warns against for ITS callers).
        if (SitrepModule.FLEET in requested) {
            sections[SitrepModule.FLEET] = FleetDigestBuilder.build(context)
        }

        if (SitrepModule.NEWS in requested) {
            sections[SitrepModule.NEWS] = newsSectionLive(context)
        }

        return compose(SitrepModule.entries, sections)
    }

    /**
     * The module-filtering decision itself, pulled out of [build] as a pure function so it is a
     * direct unit test target (ticket 22's verification: "`get_sitrep` covered where it is pure ...
     * module filtering") without needing Room/[SitrepSettings] just to exercise the intersection
     * rule. [requested] narrows [enabled], never widens it - see [build]'s own doc comment for why.
     * `null` means "no filter was asked for" and passes [enabled] straight through.
     */
    internal fun resolveRequestedModules(requested: Set<SitrepModule>?, enabled: Set<SitrepModule>): Set<SitrepModule> =
        if (requested == null) enabled else requested.intersect(enabled)

    /** Pure join, in [SitrepModule] declaration order (CALENDAR, WEATHER, FLEET, NEWS) regardless
     * of [sections]' own map iteration order - a sitrep should read the same way every time, not
     * shuffle with whatever order its modules happened to resolve in. `internal` for direct unit
     * testing without Android. */
    internal fun compose(order: List<SitrepModule>, sections: Map<SitrepModule, String>): String =
        order.mapNotNull { sections[it] }.joinToString("\n\n")

    // ------------------------------------------------------------------------------- CALENDAR

    /**
     * CALENDAR section. Three real, distinct outcomes, same split
     * [com.kevin.legion.calendar.OpenerCalendarBriefing.forOpener] already makes for the same
     * underlying ambiguity: [CalendarProvider.eventsInWindow] returns an empty list BOTH for a
     * refused permission and for a genuinely clear window, and those must never collapse into one
     * sentence. `internal` for direct unit testing.
     */
    internal fun calendarSection(
        hasPermission: Boolean,
        events: List<CalendarProvider.GoogleCalendarEvent>,
        nowMs: Long,
        zone: ZoneId,
    ): String {
        if (!hasPermission) return DigestText.line("CALENDAR", "no permission to read it")
        val upcoming = events.filter { it.allDay || it.endMs > nowMs }.sortedBy { it.startMs }
        if (upcoming.isEmpty()) return DigestText.line("CALENDAR", "clear for the next 24h")
        val listed = upcoming.joinToString("; ") { event ->
            val title = event.title.trim().ifEmpty { "(untitled)" }
            if (event.allDay) "\"$title\" (all day)"
            else "\"$title\" at ${Instant.ofEpochMilli(event.startMs).atZone(zone).format(EVENT_TIME_FMT)}"
        }
        return DigestText.line("CALENDAR", listed)
    }

    // -------------------------------------------------------------------------------- WEATHER

    /** WEATHER section. [DigestText.notLogged] when there is no GPS fix or the app has never
     * successfully reached Open-Meteo yet - never a bare `0` or a fabricated "clear". `internal`
     * for direct unit testing. */
    internal fun weatherSection(info: WeatherController.WeatherInfo?): String {
        if (info == null) return DigestText.line("WEATHER", DigestText.notLogged())
        val caution = if (info.caution) " - drive safe" else ""
        return DigestText.line("WEATHER", "${info.tempF}F, ${info.description}$caution")
    }

    // ----------------------------------------------------------------------------------- NEWS

    /**
     * Every real outcome of trying to build the NEWS section - a sealed type rather than a nullable
     * `String?` for the same reason [com.kevin.legion.data.local.ProactiveRaise] stopped being a
     * bare `String`: "nothing happened" and "something happened but it was empty/blank" are
     * different facts, and only a named type keeps [newsSection] from having to guess which one a
     * bare null or empty string meant.
     */
    internal sealed interface NewsOutcome {
        /** The mailbox could not be checked at all - no Gmail grant,
         * a lapsed one, or a network/API failure. [reason] is one of [GmailToolLogic]'s own four
         * worded failure messages, reused rather than re-derived. */
        data class CouldNotCheck(val reason: String) : NewsOutcome
        /** The mailbox WAS checked and nothing matched the query in the lookback window - a real,
         * computed zero, distinct from [CouldNotCheck]. Same outcome whether the query came from a
         * curated sender list or [NO_CONFIG_NEWSLETTER_QUERY]; the caller has no way to tell the two
         * apart from a zero count, and does not need to. */
        data object Empty : NewsOutcome
        /** Messages came in but the summarization call itself failed (offline, bad key, rate
         * limit) - [count] is said in words rather than silently dropping the whole module. */
        data class SummaryFailed(val count: Int) : NewsOutcome
        /** The happy path: [text] is the sub-agent's summary, already fit for reading aloud. */
        data class Summarized(val text: String) : NewsOutcome
    }

    /** Renders one [NewsOutcome] into the same `DigestText` wire format every other module uses.
     * `internal` for direct unit testing - this is the pure half of the NEWS module; [newsSectionLive]
     * below is the half that actually touches Gmail and the network. */
    internal fun newsSection(outcome: NewsOutcome): String = when (outcome) {
        is NewsOutcome.CouldNotCheck -> DigestText.line("NEWS", "could not check - ${outcome.reason}")
        is NewsOutcome.Empty -> DigestText.line("NEWS", "no newsletters in the last day")
        is NewsOutcome.SummaryFailed ->
            DigestText.line("NEWS", "${outcome.count} newsletter(s) came in but the summary failed - not logged")
        is NewsOutcome.Summarized -> DigestText.line("NEWS", outcome.text)
    }

    /**
     * The Gmail query for a CURATED sender list: `from:(a OR b OR c) newer_than:1d`, ticket 08's
     * own worked example ("`GmailToolLogic` already passes a `q` query through, so `from:(...)
     * newer_than:1d` is nearly free"). Null when [senders] has nothing curated - [resolveNewsletterQuery]
     * is what turns that null into [NO_CONFIG_NEWSLETTER_QUERY] rather than an unfiltered "every
     * newsletter ever" query. `internal` for direct unit testing.
     */
    internal fun buildNewsletterQuery(senders: List<String>): String? {
        val cleaned = senders.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return null
        return "from:(${cleaned.joinToString(" OR ")}) newer_than:1d"
    }

    /**
     * The Gmail search run when [SitrepSettings.newsletterSenders] is empty (command-center ticket
     * 12, Kevin 2026-08-22: "take from my gmail > summarize"). `category:updates` and
     * `category:promotions` are Gmail's OWN classification - mail Gmail itself has already sorted
     * out of `category:primary`, which is where personal correspondence lives - so this query can
     * never widen into an actual conversation, only into mail Gmail already filed as bulk. The
     * `unsubscribe` term narrows further to messages that assert they ARE a subscription, which is
     * close to the one word a personal email never contains. Text pinned by
     * `SitrepBuilderTest` - changing this string is a deliberate, tested decision, not a drive-by
     * tweak.
     */
    internal const val NO_CONFIG_NEWSLETTER_QUERY =
        "(category:updates OR category:promotions) unsubscribe newer_than:1d"

    /**
     * The query [newsSectionLive] actually runs. A curated [senders] list is an OVERRIDE, not a
     * prerequisite - it wins whenever it is non-empty, unchanged from before this ticket. Only an
     * empty list falls through to [NO_CONFIG_NEWSLETTER_QUERY]. `internal` for direct unit testing
     * - this is the one new decision point command-center ticket 12 adds; everything downstream of
     * it (fetch, fold, summarize, drop) is untouched.
     */
    internal fun resolveNewsletterQuery(senders: List<String>): String =
        buildNewsletterQuery(senders) ?: NO_CONFIG_NEWSLETTER_QUERY

    /** What [SubAgent] is told to do with the folded newsletter bodies - plain instructions, no
     * persona, since this text is summarized and immediately handed back into the deterministic
     * sitrep rather than spoken by [SubAgent] itself. */
    private const val NEWS_SYSTEM_INSTRUCTION =
        "You summarize a batch of newsletter emails into a short briefing. Two to three plain " +
            "sentences, no bullet points, no headers. Only state what the emails actually say - " +
            "never invent a detail, a date, or a claim that is not in the text you were given."

    /**
     * The Gmail-touching half of NEWS - fetches, summarizes, and lets everything but the returned
     * [String] fall out of scope (see this file's class doc on read-through). Runs the network
     * calls off the main thread the same way [com.kevin.legion.service.LiveToolbox]'s own mail
     * tools do (`withContext(Dispatchers.IO)`), since [build] may be called from a live-tool
     * dispatch or from a plain Compose click handler (ticket 32 - there is no longer a third,
     * alarm-driven caller).
     */
    private suspend fun newsSectionLive(context: Context): String {
        val senders = SitrepSettings.newsletterSenders(context)
        val query = resolveNewsletterQuery(senders)

        return when (val tokenResult = GmailAuth.tokenOrReason(context)) {
            is GmailAuth.TokenResult.Token -> withContext(Dispatchers.IO) {
                val client = GmailClient(tokenResult.accessToken)
                when (val page = client.search(query, NEWS_MESSAGE_CAP)) {
                    is GmailClient.FetchResult.Ok -> {
                        val hits = page.value.messages
                        if (hits.isEmpty()) return@withContext newsSection(NewsOutcome.Empty)

                        // Read-through: each body is fetched, folded into [prompt], and then only
                        // ever referenced through that local val - nothing here is written to Room,
                        // CompanionMemory, or an EpisodicTurn. See this file's class doc.
                        val bodies = hits.mapNotNull { hit ->
                            (client.fetchFull(hit.id) as? GmailClient.FetchResult.Ok)?.value
                                ?.let { "${it.subject}: ${it.body}" }
                        }
                        if (bodies.isEmpty()) return@withContext newsSection(NewsOutcome.SummaryFailed(hits.size))

                        val prompt = bodies.joinToString("\n\n---\n\n")
                        val summary = SubAgent(systemInstruction = NEWS_SYSTEM_INSTRUCTION, useSearch = false)
                            .ask(context = prompt, question = "Summarize today's newsletters in 2-3 sentences.")

                        newsSection(
                            if (summary.isNullOrBlank()) NewsOutcome.SummaryFailed(hits.size)
                            else NewsOutcome.Summarized(summary),
                        )
                    }
                    is GmailClient.FetchResult.Failed ->
                        newsSection(NewsOutcome.CouldNotCheck(GmailToolLogic.message(GmailToolLogic.causeForFailure(page.networkFailure))))
                }
            }
            is GmailAuth.TokenResult.NeedsConsent ->
                newsSection(NewsOutcome.CouldNotCheck(
                    GmailToolLogic.message(GmailToolLogic.causeForNeedsConsent(CompanionProfile.isGmailEnabled(context))),
                ))
            is GmailAuth.TokenResult.Failed ->
                newsSection(NewsOutcome.CouldNotCheck(GmailToolLogic.message(GmailToolLogic.causeForFailure(GmailAuth.looksLikeNetworkFailure(tokenResult.error)))))
        }
    }
}
