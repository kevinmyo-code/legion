package com.kevin.legion.service.cascade

import com.kevin.legion.service.GeminiLiveSession
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.ReadThroughWriteGuard
import com.kevin.legion.service.tools.ToolReads
import org.json.JSONArray
import org.json.JSONObject

/**
 * What this phone tells the engine it can run for it (`POST /api/agents/limb`, relay contract
 * section 1), built from ONE source: [RELAYED] names the tools and the two facts only a person can
 * decide (what kind of text a result is, and who confirms); every other flag is derived from the
 * lists the phone's own guards already use, so the manifest and the guards cannot drift:
 * - `writes` / `actuates`: not a read by [ToolReads]; a moment's action the after-read guard
 *   allows ([ReadThroughWriteGuard.ALLOWED_AFTER_READ]) is `actuates`, anything else lasts: `writes`;
 * - `read_through`: an outside class, or a tool the phone already treats as outside text
 *   ([LiveToolbox.EPISODIC_EXCLUDED_TOOLS]);
 * - `description` / `parameters`: the tool's own declaration ([LiveToolbox.sourceDeclarations]).
 *
 * Names are RESOLVED tool names, never the cascade's folded action tools: the flags above, the
 * read-through guard and `TestTurnPolicy` all key on resolved names, and a folded tool mixes reads,
 * writes and mail in one name, which one set of flags cannot describe truthfully.
 *
 * Deny by default, as on the engine (`relay.relayable`): a tool not in [RELAYED] is never offered.
 * The household's data (to-dos, calendar, money, body, records, memory) is not relayed: the engine
 * has its own twins over Postgres. Mail and messages never are (ticket 05).
 */
internal object EngineLimbManifest {
    const val DEVICE = "device"
    const val VEHICLE = "vehicle"
    const val PLACE = "place"
    const val WEB = "web"
    const val CALENDAR = "calendar"

    /** The engine's `relay.PLAIN_CLASSES` and `relay.OUTSIDE_CLASSES`. */
    val PLAIN_CLASSES = setOf(DEVICE, VEHICLE, PLACE, "household")
    val OUTSIDE_CLASSES = setOf(
        WEB, CALENDAR, "notifications", "contacts", "clipboard", "screen_text", "camera", "files",
    )

    /** `confirm: limb`: the phone runs its own gate, or the action is one he plainly asked for now. */
    const val LIMB = "limb"

    /** `confirm: engine`: the engine asks him first (a read never needs it). */
    const val ENGINE = "engine"

    data class Relay(
        val toolClass: String,
        val confirm: String,
        val needs: List<String> = emptyList(),
        val deadlineMs: Long = EngineEventParse.DEFAULT_DEADLINE_MS,
    )

    private val SCREEN = listOf("screen", "unlock")

    /**
     * The relayed tools, by resolved name. Classes:
     * - `device`: the phone's own state (timers, music, volume, its screens);
     * - `vehicle`: the car through the OBD adapter;
     * - `place`: the phone's own trip state;
     * - `calendar`: his agenda on screen carries invites other people wrote (outside text);
     * - `web`: map search results (a destination or the place he is at is named by a map service,
     *   text other people wrote): every call taints the turn.
     * `confirm: limb` on every write or action the phone gates or he asked for in the same breath:
     * timers, music, volume, navigation, the screens, and clear codes (its own read-back and his
     * voiced yes, `ClearCodesVoiceGate`).
     */
    val RELAYED: Map<String, Relay> = linkedMapOf(
        "clock" to Relay(DEVICE, LIMB),
        "control_music" to Relay(DEVICE, LIMB),
        "play_music" to Relay(DEVICE, LIMB),
        "control_volume" to Relay(DEVICE, LIMB),
        "get_music_queue" to Relay(DEVICE, ENGINE),
        "browse_my_music" to Relay(DEVICE, ENGINE),
        "show_app" to Relay(DEVICE, LIMB, SCREEN),
        "show_agenda_modal" to Relay(CALENDAR, LIMB, SCREEN),
        "show_saved_places" to Relay(DEVICE, LIMB, SCREEN),
        "navigate" to Relay(WEB, LIMB, listOf("screen")),
        "change_trip" to Relay(WEB, LIMB, listOf("screen")),
        "end_trip" to Relay(PLACE, LIMB),
        "trip_status" to Relay(PLACE, ENGINE),
        "get_current_location" to Relay(WEB, ENGINE),
        "get_codes" to Relay(VEHICLE, ENGINE),
        "read_vehicle_sensor" to Relay(VEHICLE, ENGINE),
        "get_vehicle_data" to Relay(VEHICLE, ENGINE),
        "clear_codes" to Relay(VEHICLE, LIMB),
    )

    /** One tool of the manifest, as the engine's `LimbToolSerializer` takes it. */
    data class Tool(
        val name: String,
        val relay: Relay,
        val writes: Boolean,
        val actuates: Boolean,
        val readThrough: Boolean,
    )

    /** [name]'s flags, derived (class doc). */
    fun tool(name: String, relay: Relay): Tool {
        val read = ToolReads.isRead(name, JSONObject())
        val moment = name in ReadThroughWriteGuard.ALLOWED_AFTER_READ
        return Tool(
            name = name,
            relay = relay,
            writes = !read && !moment,
            actuates = !read && moment,
            readThrough = relay.toolClass in OUTSIDE_CLASSES || GeminiLiveSession.isEpisodicExcludedTool(name),
        )
    }

    fun tools(): List<Tool> = RELAYED.map { (name, relay) -> tool(name, relay) }

    /**
     * What this phone offers for one conversation: the upload body, and the tools and their
     * parameter schemas as sent, which are all the phone will ever run for the engine
     * ([EngineCallGate]). A relayed name with no declaration of its own is left out.
     */
    class Offer(val json: JSONObject, val tools: Map<String, Tool>, val params: Map<String, JSONObject>)

    fun offer(appVersion: String, declarations: Map<String, JSONObject> = sourceDeclarations()): Offer {
        val sent = tools().filter { declarations.containsKey(it.name) }
        val params = sent.associate { t ->
            t.name to (declarations.getValue(t.name).optJSONObject("parameters") ?: JSONObject())
        }
        val list = JSONArray()
        for (t in sent) {
            list.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", describe(declarations.getValue(t.name)))
                    .put("parameters", params.getValue(t.name))
                    .put("writes", t.writes)
                    .put("read_through", t.readThrough)
                    .put("actuates", t.actuates)
                    .put("needs", JSONArray(t.relay.needs))
                    .put("confirm", t.relay.confirm)
                    .put("deadline_ms", t.relay.deadlineMs)
                    .put("tool_class", t.relay.toolClass),
            )
        }
        val json = JSONObject()
            .put("contract_version", EngineBrainRules.CONTRACT_VERSION)
            .put("kind", "phone")
            .put("app_version", appVersion.take(MAX_APP_VERSION_CHARS))
            .put("brain_asked", "engine")
            .put("tools", list)
            .put("streams", JSONArray())
        return Offer(json, sent.associateBy { it.name }, params)
    }

    private fun describe(declaration: JSONObject): String =
        declaration.optString("description").take(MAX_DESCRIPTION_CHARS)

    /** The upload body alone ([offer]). */
    fun manifest(appVersion: String, declarations: Map<String, JSONObject> = sourceDeclarations()): JSONObject =
        offer(appVersion, declarations).json

    fun sourceDeclarations(): Map<String, JSONObject> {
        val all = LiveToolbox.sourceDeclarations()
        return (0 until all.length()).mapNotNull { all.optJSONObject(it) }.associateBy { it.optString("name") }
    }

    /** The engine's `limbs.MAX_DESCRIPTION_CHARS` and `app_version` length. */
    const val MAX_DESCRIPTION_CHARS = 4000
    const val MAX_APP_VERSION_CHARS = 64
}
