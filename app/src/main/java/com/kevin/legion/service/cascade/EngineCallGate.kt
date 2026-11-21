package com.kevin.legion.service.cascade

import com.kevin.legion.service.ReadThroughWriteGuard
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the phone runs for the engine (review 1121, B-1 and B-2), decided on the PHONE's facts:
 * - only a tool in the [offer] this phone sent for this conversation; any other name (a mail tool,
 *   a tool the engine invented) is refused as `not_offered` and nothing runs;
 * - its flags (`writes`, `actuates`, `read_through`, `needs`) are the phone's own from that offer,
 *   OR'd with the engine's (an engine flag can add caution, never remove it);
 * - its arguments must fit the tool's own parameter schema ([EngineArgs]): no unknown argument,
 *   no wrong type, no value outside an enum, no missing required one;
 * - taint is kept on the phone too: once a call that reads outside text has started in this turn,
 *   a later write in the turn is refused here whatever the engine says.
 */
internal class EngineCallGate(private val offer: EngineLimbManifest.Offer) {
    @Volatile private var tainted = false

    /** A new engine turn: the phone's own taint starts clear (the engine's still applies). */
    fun newTurn() {
        tainted = false
    }

    sealed interface Decision {
        /** Run [name] with [args]; the phone's after-read guard gets [readThrough]. */
        data class Run(
            val name: String,
            val args: JSONObject,
            val readThrough: Boolean,
            val outside: Boolean,
        ) : Decision

        data class Refuse(val words: String) : Decision
    }

    fun decide(call: EngineCall, state: EngineBrainRules.DeviceState): Decision {
        val tool = offer.tools[call.name]
        val taintedBefore = call.tainted || tainted || call.afterReply
        val writes = call.writes || tool?.writes == true
        val needs = (call.needs + tool?.relay?.needs.orEmpty()).distinct()
        val badArgs = tool?.let { EngineArgs.problem(offer.params[it.name] ?: JSONObject(), call.args) }
        val refusal = when {
            tool == null -> "$NOT_OFFERED: ${safeName(call.name)} is not a tool this phone offered; nothing ran."
            badArgs != null -> "Nothing ran: ${tool.name} was asked with $badArgs."
            taintedBefore && writes -> ReadThroughWriteGuard.WRITE_REFUSAL.format(tool.name)
            else -> EngineBrainRules.needsRefusal(needs, state)
        }
        if (refusal != null || tool == null) {
            return Decision.Refuse((refusal ?: NOT_OFFERED).take(EngineBrainRules.MAX_WORDS_CHARS))
        }
        val outside = tool.readThrough || call.readThrough
        // From here every later call of the turn is marked: the outside text is on its way to the model.
        if (outside) tainted = true
        return Decision.Run(tool.name, call.args, taintedBefore, outside)
    }

    companion object {
        const val NOT_OFFERED = "not_offered"

        /** An engine-supplied name in words and logs: lower-case word characters only, bounded. */
        fun safeName(name: String): String =
            name.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_NAME).ifBlank { "unnamed" }

        private const val MAX_NAME = 64
    }
}

/**
 * A relayed call's arguments against the tool's own declared `parameters` (the JSON schema the
 * phone sent in its manifest): object properties, `required`, a type per property and `enum`.
 * Types are read case-blind (`STRING` and `string` both appear in the phone's declarations).
 */
internal object EngineArgs {
    /** What is wrong with [args], in a few words, or null when they fit [schema]. */
    fun problem(schema: JSONObject, args: JSONObject): String? {
        val props = schema.optJSONObject("properties") ?: JSONObject()
        val required = EngineEventParse.strings(schema.optJSONArray("required"))
        val unknown = args.keys().asSequence().firstOrNull { !props.has(it) }
        val missing = required.firstOrNull { !args.has(it) }
        return when {
            unknown != null -> "an argument it does not take (${EngineCallGate.safeName(unknown)})"
            missing != null -> "a required argument missing ($missing)"
            else -> args.keys().asSequence().mapNotNull { key ->
                props.optJSONObject(key)?.let { wrong(key, it, args.get(key)) }
            }.firstOrNull()
        }
    }

    private fun wrong(key: String, prop: JSONObject, value: Any): String? {
        val type = prop.optString("type").lowercase()
        val fits = when (type) {
            "string" -> value is String
            "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
            "number" -> value is Number
            "boolean" -> value is Boolean
            "array" -> value is JSONArray
            "object" -> value is JSONObject
            else -> true
        }
        val allowed = prop.optJSONArray("enum")?.let { e -> (0 until e.length()).map { e.get(it) } }
        return when {
            !fits -> "the wrong kind of value for $key"
            allowed != null && value !in allowed -> "a value for $key it does not accept"
            else -> null
        }
    }
}
