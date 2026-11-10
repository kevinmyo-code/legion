package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.update.Consent
import com.kevin.legion.update.UpdateController
import com.kevin.legion.update.UpdateGraph
import com.kevin.legion.update.UpdateRules
import com.kevin.legion.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * LEGION's own updates by voice (alfred-ota ticket 06, ADR 0072): ONE `app_update` tool with an
 * `action`, the trade `books` and `browser` made, because [LiveSetupPayloadSizeTest] bills every
 * declaration on every reconnect. Every action calls [UpdateController], the same controller the
 * Updates screen and the notification's Install call (ADR 0035).
 *
 * **Install asks every time (map decision 3).** `install` without `confirmed=true` installs
 * nothing and tells the model to ask; with it, the controller still refuses a yes that does not
 * follow an offer made moments ago (the tool's own ask or the spoken announcement), refuses Matrix
 * mode and the car in words, and holds a yes given mid-conversation until it ends. The result says
 * what did and did not happen, and `success` is true only when PackageInstaller took the file.
 */
object AppUpdateToolbox {

    const val TOOL_NAME = "app_update"

    /** Test seam: a controller over fakes. Production is the process's one [UpdateGraph] controller. */
    internal var controllerFactory: (Context) -> UpdateController = { UpdateGraph.controller(it) }

    private const val DESCRIPTION =
        "LEGION's own app update. action=check (is one ready), whats_new (its notes), install. " +
            "install needs confirmed=true, sent only after the user said yes to installing in their reply. " +
            "Say only what the result says."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "app_update",
            description = DESCRIPTION,
            params = JSONObject()
                .put("action", prop("string", null, listOf("check", "whats_new", "install")))
                .put("confirmed", prop("boolean", "True only after the user's yes.")),
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
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name != TOOL_NAME) return null
        val controller = controllerFactory(context)
        return when (args.optString("action")) {
            "check" -> check(controller)
            "whats_new" -> result(true, controller.whatsNew())
            "install" -> install(controller, args.optBoolean("confirmed", false))
            else -> result(false, "Say whether to check for an update, read what is new, or install. Nothing was done.")
        }
    }

    /** How long a voice `check` waits for the controller, well inside the 10 s tool timeout. */
    internal const val CHECK_WAIT_MS = 6_000L

    /** Test seam for [CHECK_WAIT_MS]. */
    internal var checkWaitMs = CHECK_WAIT_MS

    /**
     * Where a voice check runs. Not the tool call's own coroutine: a check that finds a newer
     * release downloads it inline, and the tool runner's timeout would cancel that download.
     */
    private val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Phone audit 2026-11-10: the first "check for updates" ran the whole download inside the
     * tool call and hit the runner's 10 s timeout, so the user heard "timed out" while the update
     * was in fact arriving; the retry found the file. Now a download already under way is
     * answered from the state at once (never waiting on the controller's lock), and a check is
     * awaited for at most [checkWaitMs] while it carries on in [checkScope]. A check still
     * running says so in words; the ready notification and the spoken offer follow on their own.
     */
    internal suspend fun check(controller: UpdateController): JSONObject {
        (controller.state.value as? UpdateState.Downloading)?.let { return stillWorking(it) }
        val running = checkScope.async { controller.check() }
        val state = withTimeoutOrNull(checkWaitMs) { running.await() } ?: return stillWorking(controller.state.value)
        return describe(controller, state)
    }

    private fun stillWorking(state: UpdateState): JSONObject = when (state) {
        is UpdateState.Downloading -> result(
            true,
            "An update is downloading now: ${UpdateRules.describe(state.release)} (${mb(state.bytes)} of " +
                "${mb(state.release.sizeBytes)} MB so far). Nothing is installed yet; once it is downloaded and " +
                "checked it is offered, or ask again in a minute.",
        )
        else -> result(
            true,
            "The update check is still running. Nothing is installed yet; ask again in a minute.",
        )
    }

    private fun mb(bytes: Long): Long = bytes / BYTES_PER_MB

    private const val BYTES_PER_MB = 1_048_576L

    private fun describe(controller: UpdateController, state: UpdateState): JSONObject =
        when (state) {
            UpdateState.DebugBuild -> result(true, "This is a debug build (version code 1); it never updates itself.")
            is UpdateState.UpToDate -> result(true, "LEGION is up to date. ${controller.whatsNew()}")
            is UpdateState.Available -> result(
                true,
                "An update is published, ${UpdateRules.describe(state.release)}, but it is not ready to install. ${state.why}",
            )
            is UpdateState.Ready -> {
                controller.offered()
                result(
                    true,
                    "An update is downloaded and checked: ${UpdateRules.describe(state.release)}. Nothing is " +
                        "installed yet. Ask whether to install it now; installing closes LEGION for about a minute.",
                )
            }
            is UpdateState.Waiting -> result(true, "LEGION ${state.release.versionName} installs as soon as this conversation ends.")
            is UpdateState.Installing -> result(true, "LEGION ${state.release.versionName} is installing now.")
            is UpdateState.Failed -> result(false, state.sentence)
            else -> result(false, "The update check did not finish. Nothing was installed.")
        }

    internal suspend fun install(controller: UpdateController, confirmed: Boolean): JSONObject {
        if (!confirmed) {
            controller.offered()
            return result(
                false,
                "Nothing was installed. Ask the user whether to install the update now, then call install " +
                    "with confirmed=true only if they say yes.",
            )
        }
        val outcome = controller.install(Consent.VOICE)
        return result(outcome.started, outcome.sentence).put("waiting_for_conversation_end", outcome.waiting)
    }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
