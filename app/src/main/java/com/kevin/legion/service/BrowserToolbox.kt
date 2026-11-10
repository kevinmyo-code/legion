package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.MidnightApplication
import org.json.JSONArray
import org.json.JSONObject

/**
 * The browser's voice surface (ADR 0071): ONE `browser` tool with an `action`, for the same reason
 * `books` is one ([LiveSetupPayloadSizeTest] re-bills every declaration every turn). Lives in its own
 * file like [BooksToolbox]; `tools/voice_guide.py` and `VoiceGuideDataTest` scan this file too.
 *
 * Every action runs [com.kevin.legion.browser.BrowserTool] over the same
 * [com.kevin.legion.browser.BrowserController] the browser screen's buttons call (ADR 0035). The
 * description carries only what must reach the model: outcome words only on success, never a
 * password, ask first, and questions answered in words go to `ask_engine`. The ask-first protocol
 * itself rides in the tool result.
 *
 * Read-through (CLAUDE.md section 7): `browser` is in [LiveToolbox.EPISODIC_EXCLUDED_TOOLS].
 */
object BrowserToolbox {
    const val TOOL_NAME = "browser"

    private const val DESCRIPTION =
        "LEGION's on-screen web browser, to SHOW pages (pictures, menus, sites). Open it ONLY when the " +
            "user asks to see something: show, pull up, open, picture, photo, look at, go to a site. " +
            "NEVER open it to answer a question (news, facts, what/why/how/who): answer that out loud " +
            "from your own Google Search. open: text is a web " +
            "address or a Google search (kind=images for pictures). read: page text plus numbered " +
            "links, buttons and fields. click: by number or text. type: text into a numbered field, " +
            "submit=true to submit; never passwords (autofill fills them). To ANSWER a question in " +
            "words use ask_engine instead. If the result " +
            "asks a question, ask it and call again with confirm only after the user's next reply is yes."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "browser",
            description = DESCRIPTION,
            params = JSONObject()
                .put(
                    "action",
                    prop(
                        "string",
                        enum = listOf("open", "read", "click", "type", "scroll", "back", "close"),
                    ),
                )
                .put("text", prop("string", "open: address or search words. click: words on it. type: the text."))
                .put("number", prop("integer", "Element number from read."))
                .put("kind", prop("string", enum = listOf("web", "images")))
                .put("direction", prop("string", enum = listOf("down", "up", "top")))
                .put("submit", prop("boolean"))
                .put("confirm", prop("string", "Token from the question, only after the user said yes.")),
            required = listOf("action"),
        ),
    )

    // `name = "..."` written out in the call above, not via TOOL_NAME: tools/voice_guide.py and
    // VoiceGuideDataTest find tool names by scanning for that exact shape.
    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
            )

    private fun prop(type: String, description: String? = null, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type)
            .apply { if (description != null) put("description", description) }
            .apply { if (enum != null) put("enum", JSONArray(enum)) }

    /** Null when [name] is not this tool, so [LiveToolbox.dispatch] can fall through. */
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name != TOOL_NAME) return null
        val app = context.applicationContext as? MidnightApplication
            ?: return JSONObject().put("success", false).put("message", "Nothing was done: no browser here.")
        return app.browserTool.run(args)
    }
}
