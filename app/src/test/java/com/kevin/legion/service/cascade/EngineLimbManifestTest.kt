package com.kevin.legion.service.cascade

import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.ReadThroughWriteGuard
import com.kevin.legion.service.tools.ToolReads
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The limb manifest the phone uploads (relay contract section 1), pinned tool by tool, and checked
 * against the one source it is built from. The engine trusts the class this app declares, so a wrong
 * class here is a hole the engine does not catch: a new relayed tool fails [pinned] until its class
 * and confirm are decided here. Robolectric for `org.json` and the declarations.
 */
@RunWith(RobolectricTestRunner::class)
class EngineLimbManifestTest {
    private val manifest = EngineLimbManifest.manifest("2026-11-21-test")
    private val tools = manifest.getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val byName = tools.associateBy { it.getString("name") }

    /** Every relayed tool: its class and who confirms. Changing a row is a decision, not a refactor. */
    private val pinned = mapOf(
        "clock" to ("device" to "limb"),
        "control_music" to ("device" to "limb"),
        "play_music" to ("device" to "limb"),
        "control_volume" to ("device" to "limb"),
        "get_music_queue" to ("device" to "engine"),
        "browse_my_music" to ("device" to "engine"),
        "show_app" to ("device" to "limb"),
        "show_agenda_modal" to ("device" to "limb"),
        "show_saved_places" to ("device" to "limb"),
        "navigate" to ("web" to "limb"),
        "change_trip" to ("web" to "limb"),
        "end_trip" to ("place" to "limb"),
        "trip_status" to ("place" to "engine"),
        "get_current_location" to ("web" to "engine"),
        "get_codes" to ("vehicle" to "engine"),
        "read_vehicle_sensor" to ("vehicle" to "engine"),
        "get_vehicle_data" to ("vehicle" to "engine"),
        "clear_codes" to ("vehicle" to "limb"),
    )

    @Test
    fun `every relayed tool has its pinned class and confirm, and nothing else is offered`() {
        assertEquals(pinned.keys, byName.keys)
        for ((name, want) in pinned) {
            val t = byName.getValue(name)
            assertEquals(name, want.first, t.getString("tool_class"))
            assertEquals(name, want.second, t.getString("confirm"))
        }
    }

    @Test
    fun `every relayed tool has the phone's own declaration`() {
        val source = EngineLimbManifest.sourceDeclarations()
        for ((name, t) in byName) {
            val decl = source.getValue(name)
            assertEquals(name, decl.optString("description").take(4000), t.getString("description"))
            assertEquals(name, decl.getJSONObject("parameters").toString(), t.getJSONObject("parameters").toString())
        }
    }

    @Test
    fun `the flags equal the phone's own read list and after-read guard`() {
        for ((name, t) in byName) {
            val read = ToolReads.isRead(name, JSONObject())
            val moment = name in ReadThroughWriteGuard.ALLOWED_AFTER_READ
            assertEquals("$name writes", !read && !moment, t.getBoolean("writes"))
            assertEquals("$name actuates", !read && moment, t.getBoolean("actuates"))
            val outside = t.getString("tool_class") in OUTSIDE || name in LiveToolbox.EPISODIC_EXCLUDED_TOOLS
            assertEquals("$name read_through", outside, t.getBoolean("read_through"))
        }
    }

    @Test
    fun `a write or action the phone does not gate itself waits for his yes on the engine`() {
        val phoneGated = setOf(
            "clock", "control_music", "play_music", "control_volume", "show_app", "show_agenda_modal",
            "show_saved_places", "navigate", "change_trip", "end_trip", "clear_codes",
        )
        for ((name, t) in byName) {
            val acts = t.getBoolean("writes") || t.getBoolean("actuates")
            if (acts) assertTrue("$name acts: confirm limb only if the phone gates it", name in phoneGated)
            val limb = t.getString("confirm") == "limb"
            if (limb) assertTrue("$name is limb-confirmed but not gated", name in phoneGated)
        }
    }

    @Test
    fun `no mail, message or unknown class is offered, and no outside-sounding name hides behind a plain class`() {
        for ((name, t) in byName) {
            assertFalse("$name is mail-class", name in ENGINE_MAIL_CLASS || MAIL_NAME.containsMatchIn(name))
            assertFalse("$name reads mail on the phone", name in MAIL_ON_PHONE)
            val cls = t.getString("tool_class")
            assertTrue("$name has an unknown class $cls", cls in PLAIN || cls in OUTSIDE)
            if (OUTSIDE_NAME.containsMatchIn(name)) assertTrue("$name sounds outside", cls in OUTSIDE)
        }
    }

    @Test
    fun `the manifest is the engine's shape and well under its size caps`() {
        assertEquals(1, manifest.getInt("contract_version"))
        assertEquals("phone", manifest.getString("kind"))
        assertEquals("engine", manifest.getString("brain_asked"))
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8).size
        assertTrue("manifest $bytes bytes", bytes < MANIFEST_CEILING)
        for (t in tools) {
            assertTrue(t.getString("name").matches(Regex("^[a-z][a-z0-9_]{0,63}$")))
            assertTrue(t.getString("description").length <= 4000)
            assertTrue(t.getLong("deadline_ms") in 500L..120_000L)
            val needs = t.getJSONArray("needs")
            for (i in 0 until needs.length()) assertTrue(needs.getString(i) in setOf("screen", "unlock", "parked"))
        }
    }

    @Test
    fun `building the manifest leaves the phone loop's own tool list as it was`() {
        val before = LiveToolbox.declarations().toString()
        EngineLimbManifest.manifest("x")
        assertEquals(before, LiveToolbox.declarations().toString())
    }

    private companion object {
        val PLAIN = setOf("device", "vehicle", "place", "household")
        val OUTSIDE = setOf("web", "notifications", "contacts", "clipboard", "screen_text", "camera", "files")

        /** The engine's `relay.MAIL_CLASS`, `_MAIL_NAME` and `_OUTSIDE_NAME` (relay branch 562410af), copied. */
        val ENGINE_MAIL_CLASS = setOf(
            "ask_mail", "search_mail", "read_mail", "email", "gmail", "get_sitrep", "track_package", "flight_status",
        )
        val MAIL_NAME = Regex(
            "(^|_)(mail|gmail|email|inbox|sms|mms|message|messages|chat|whatsapp|telegram|signal|" +
                "imessage|dm|dms)(_|$)",
            RegexOption.IGNORE_CASE,
        )
        val OUTSIDE_NAME = Regex(
            "(^|_)(notification|notifications|clipboard|contact|contacts|browser|web|page|pages|" +
                "screen_text|ocr|camera|look|photo|photos|file|files|feed|news)(_|$)",
            RegexOption.IGNORE_CASE,
        )

        /** The phone's own mail-reading tools (`ToolReads`): never relayed. */
        val MAIL_ON_PHONE = setOf(
            "ask_mail", "search_mail", "read_mail", "get_sitrep", "track_package", "flight_status",
        )

        /** Far under the engine's 256 KiB `MAX_MANIFEST_BYTES`: a relayed subset is small. */
        const val MANIFEST_CEILING = 64 * 1024
    }
}
