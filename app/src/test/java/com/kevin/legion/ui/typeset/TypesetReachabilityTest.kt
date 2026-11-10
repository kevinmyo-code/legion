package com.kevin.legion.ui.typeset

import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.shellShowsStatusLine
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every user-facing destination is reachable by TAP from the Typeset front page (ADR 0050: a home
 * app owes every screen a way in; Kevin, 2026-11-10: "make sure there's a button to access it").
 *
 * The destination list is not typed in here: it is every `LegionRoute` constant (by reflection) and
 * every `<activity>` / `<activity-alias>` in the manifest. A new route or activity with no entry in
 * [taps] or [internalOnly] fails this test. Each tap path ends on the front page, and the front-page
 * words it names must be on the page ([TypesetHomeDerive.CONTENTS], now under the hero orb; the
 * shell's StatusLine above it; the masthead). `TypesetFitScreenshotTest` checks each word is drawn
 * at every size after the orb moved up (Kevin, 2026-11-10).
 */
class TypesetReachabilityTest {

    /** Where a tap starts: a word on the front page, or another destination's screen. */
    private sealed interface From
    private data class Page(val word: String) : From
    private data class Screen(val destination: String, val row: String) : From

    /** destination -> how a tap gets there. Destinations are route strings or activity class names. */
    private val taps: Map<String, From> = mapOf(
        LegionRoute.HOME to Page("LEGION"),
        LegionRoute.CALENDAR to Page("Today"),
        LegionRoute.MONEY to Page("Money"),
        LegionRoute.FLEET to Page("Car"),
        LegionRoute.APPS to Page("All apps"),
        LegionRoute.SETTINGS to Page("Settings"),
        LegionRoute.CLAUDE_SESSION to Screen("sheet:claude-watch", "See all"),
        LegionRoute.SETTINGS_SPOTIFY_MEDIA to Page("Media"),
        LegionRoute.CHECKLISTS to Screen("sheet:lists", "a list"),
        LegionRoute.BOUGHT to Screen(LegionRoute.CHECKLISTS, "Bought"),
        LegionRoute.BODY to Screen("sheet:more", "Body"),
        LegionRoute.NEWS to Screen("sheet:more", "News"),
        LegionRoute.ASK to Screen("sheet:more", "Reports"),
        LegionRoute.SETTINGS_VOICE_NOTES to Screen("sheet:more", "Recordings"),
        LegionRoute.SETTINGS_DUMB_MODE to Screen("sheet:more", "Dumb phone mode"),
        LegionRoute.FLEET_PLACES to Screen(LegionRoute.FLEET, "Saved places"),
        LegionRoute.FLEET_CARS to Screen(LegionRoute.FLEET, "Cars"),
        LegionRoute.DRIVING to Screen(LegionRoute.FLEET, "Driving mode"),
        LegionRoute.NAVIGATE to Screen(LegionRoute.FLEET_PLACES, "Navigate"),
        LegionRoute.CONDITIONS to Screen(LegionRoute.FLEET_PLACES, "Conditions"),
        LegionRoute.MONEY_PANTRY to Screen(LegionRoute.MONEY, "Groceries"),
        LegionRoute.MONEY_PANTRY_IMPORT to Screen(LegionRoute.MONEY_PANTRY, "Import"),
        LegionRoute.TETRIS to Screen("sheet:games", "Tetris"),
        LegionRoute.DASHBOARD to Screen(LegionRoute.SETTINGS, "Dashboard"),
        LegionRoute.SETTINGS_ASSISTANT to Screen(LegionRoute.SETTINGS, "Assistant"),
        LegionRoute.SETTINGS_PROACTIVE_SPEECH to Screen(LegionRoute.SETTINGS, "Proactive speech"),
        LegionRoute.SETTINGS_CONNECTIONS to Screen(LegionRoute.SETTINGS, "Connections"),
        LegionRoute.SETTINGS_DATA_PRIVACY to Screen(LegionRoute.SETTINGS, "Data and privacy"),
        LegionRoute.SETTINGS_PERMISSIONS_DIAGNOSTICS to Screen(LegionRoute.SETTINGS, "Permissions"),
        LegionRoute.SETTINGS_UPDATES to Screen(LegionRoute.SETTINGS, "Updates"),
        LegionRoute.SETTINGS_HELP to Screen(LegionRoute.SETTINGS, "Voice guide"),
        LegionRoute.SETTINGS_COMPANIONS to Screen(LegionRoute.SETTINGS, "Companions"),
        LegionRoute.SETTINGS_PLAYBOOKS to Screen(LegionRoute.SETTINGS, "Playbooks"),
        LegionRoute.SETTINGS_WAKE_TUNING to Screen(LegionRoute.SETTINGS, "Wake word"),
        LegionRoute.SETTINGS_WATCHES to Screen(LegionRoute.SETTINGS, "Watches"),
        LegionRoute.SETTINGS_MEMORY to Screen(LegionRoute.SETTINGS, "Memory"),
        LegionRoute.SETTINGS_KEY to Screen(LegionRoute.SETTINGS, "Key"),
        LegionRoute.SETTINGS_GOOGLE to Screen(LegionRoute.SETTINGS_CONNECTIONS, "Google"),
        LegionRoute.SETTINGS_SPOTIFY to Screen(LegionRoute.SETTINGS_CONNECTIONS, "Spotify"),
        LegionRoute.SETTINGS_DRIVE_SYNC to Screen(LegionRoute.SETTINGS_DATA_PRIVACY, "Drive sync"),
        LegionRoute.SETTINGS_PHONE to Screen(LegionRoute.SETTINGS_CONNECTIONS, "Phone"),
        LegionRoute.SETTINGS_CAR_PROBE to Screen(LegionRoute.SETTINGS_CONNECTIONS, "Car probe"),
        // Sheets drawn over the page (not routes): each opened by a word on the page.
        "sheet:games" to Page("Games"),
        "sheet:books" to Page("Books"),
        "sheet:lists" to Page("Lists"),
        "sheet:ideas" to Page("Ideas"),
        "sheet:todos" to Page("due today"),
        "sheet:brief" to Page("Brief"),
        "sheet:more" to Page("More"),
        "sheet:controls" to Page("CONTROLS"),
        "sheet:claude-watch" to Page("Claude sessions"),
        "sheet:groups" to Page("BANK"),
        // Activities.
        "MainActivity" to Page("LEGION"),
        "ClockActivity" to Page("Clock"),
        "FieldActivity" to Page("Field"),
        "FieldLauncher" to Screen(LegionRoute.APPS, "Field"),
        "BreathActivity" to Page("Breathe"),
        "BrowserActivity" to Screen(LegionRoute.APPS, "Browser"),
        "BrowserLauncher" to Screen(LegionRoute.APPS, "Browser"),
        "ReaderActivity" to Screen("sheet:books", "a book"),
        "LookActivity" to Screen(LegionRoute.FLEET, "Show"),
        "VaultActivity" to Screen(LegionRoute.SETTINGS_DATA_PRIVACY, "ID vault"),
        "ClaudePageActivity" to Screen(LegionRoute.CLAUDE_SESSION, "a page"),
        "ClaudeFileActivity" to Screen(LegionRoute.CLAUDE_SESSION, "files"),
    )

    /** Not a place a person goes: route arguments, aliases, system entry points, gates and alarms. */
    private val internalOnly: Map<String, String> = mapOf(
        LegionRoute.NAVIGATE_PLACE_ARG to "an argument name",
        LegionRoute.NAVIGATE_PATTERN to "NAVIGATE with a place",
        LegionRoute.TETRIS_USER_ARG to "an argument name",
        LegionRoute.TETRIS_MATCH_ARG to "an argument name",
        LegionRoute.TETRIS_INVITE_PATTERN to "TETRIS against a household member (from the Games sheet)",
        LegionRoute.TETRIS_MATCH_PATTERN to "a match link from a notification",
        LegionRoute.MONEY_DETAILS to "an alias of MONEY for deep links",
        LegionRoute.FLEET_TELEMETRY to "registered, no caller by design (FLEET_TELEMETRY's own comment)",
        "AssistActivity" to "the system assist gesture",
        "RingActivity" to "an alarm ringing",
        "DumbBounceActivity" to "the feed blocker's bounce screen",
        "MirrorSyncActivity" to "the engine mirror's sync host",
        "ClockLauncher" to "the phone launcher's Clock icon; Clock is on the contents line",
    )

    /**
     * The shell's StatusLine (`ui/common/DeckPanels.kt`), drawn above HOME as on every other route:
     * the gear ("Settings", LEGION or phone), the Claude button ("Claude sessions"), Apps, Quiet.
     */
    private val statusLineWords = setOf("Settings", "Claude sessions", "Apps", "Quiet")

    private val pageWords: Set<String> =
        TypesetHomeDerive.CONTENTS.toSet() + statusLineWords +
            setOf("LEGION", "CONTROLS", "All apps", "due today", "BANK")

    private fun routes(): Set<String> = LegionRoute::class.java.declaredFields
        .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.isAccessible = true; it.get(null) as String }
        .toSet()

    private fun activities(): Set<String> {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.exists() }.readText()
        return Regex("""<activity(?:-alias)?\s[^>]*?android:name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.groupValues[1].substringAfterLast('.') }.toSet()
    }

    @Test
    fun `every route and activity has a tap path or is named internal`() {
        val all = routes() + activities()
        val missing = all.filter { it !in taps && it !in internalOnly }
        assertTrue("No tap path and not internal: $missing", missing.isEmpty())
        val both = all.filter { it in taps && it in internalOnly }
        assertTrue("Both tapped and internal: $both", both.isEmpty())
    }

    @Test
    fun `every tap path ends on a word that is on the front page`() {
        taps.forEach { (destination, from) ->
            var at: From = from
            val seen = mutableSetOf(destination)
            while (at is Screen) {
                val parent = at.destination
                assertTrue("$destination: loop through $parent", seen.add(parent))
                at = taps[parent] ?: error("$destination: $parent has no tap path")
            }
            val word = (at as Page).word
            assertTrue("$destination: '$word' is not on the front page", word in pageWords)
        }
    }

    @Test
    fun `games, books and the old home's chips are on the contents line`() {
        listOf("Games", "Books", "Lists", "Ideas", "Brief", "Media", "Field", "Breathe", "More").forEach {
            assertTrue("$it missing from the contents line", it in TypesetHomeDerive.CONTENTS)
        }
    }

    @Test
    fun `the shell draws its StatusLine on HOME, as on All apps`() {
        assertTrue(shellShowsStatusLine(isDrivingMode = false, isDumbHome = false))
        assertFalse("DRIVING is full-bleed", shellShowsStatusLine(isDrivingMode = true, isDumbHome = false))
        assertFalse("dumb-phone HOME is the list alone", shellShowsStatusLine(isDrivingMode = false, isDumbHome = true))
    }
}
