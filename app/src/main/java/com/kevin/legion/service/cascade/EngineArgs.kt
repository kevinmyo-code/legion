package com.kevin.legion.service.cascade

import org.json.JSONArray
import org.json.JSONObject

/**
 * A relayed call's arguments against the tool's own declared `parameters` (the JSON schema the phone
 * sent in its manifest), all the way down (review R6): object properties, `required`, a type for
 * every value, `enum`, nested objects and array items. Fails closed: a schema type it does not know,
 * or none, refuses the value. Types are read case-blind (`STRING` and `string` both appear in the
 * phone's declarations); an enum is compared by value (`5` and `5.0`, a string by its text).
 */
internal object EngineArgs {
    private const val MAX_DEPTH = 8

    /** What is wrong with [args], in a few words, or null when they fit [schema]. */
    fun problem(schema: JSONObject, args: JSONObject): String? = objectProblem("", schema, args, 0)

    private fun objectProblem(path: String, schema: JSONObject, value: JSONObject, depth: Int): String? {
        val props = schema.optJSONObject("properties") ?: JSONObject()
        val unknown = value.keys().asSequence().firstOrNull { !props.has(it) }
        val missing = EngineEventParse.strings(schema.optJSONArray("required")).firstOrNull { !value.has(it) }
        return when {
            depth > MAX_DEPTH -> "arguments nested too deep"
            unknown != null -> "an argument it does not take (${path}${EngineCallGate.safeName(unknown)})"
            missing != null -> "a required argument missing ($path$missing)"
            else -> value.keys().asSequence().mapNotNull { key ->
                valueProblem("$path$key", props.optJSONObject(key) ?: JSONObject(), value.get(key), depth + 1)
            }.firstOrNull()
        }
    }

    /** The schema types this check knows, and what fits each; any other type fails closed. */
    private val KINDS: Map<String, (Any) -> Boolean> = mapOf(
        "string" to { v -> v is String },
        "integer" to { v -> v is Number && v.toDouble() % 1.0 == 0.0 },
        "number" to { v -> v is Number },
        "boolean" to { v -> v is Boolean },
        "object" to { v -> v is JSONObject },
        "array" to { v -> v is JSONArray },
    )

    private fun valueProblem(path: String, prop: JSONObject, value: Any, depth: Int): String? {
        val name = EngineCallGate.safeName(path)
        val fits = KINDS[prop.optString("type").lowercase()]
            ?: return "a value of a kind this phone does not check for $name"
        return when {
            !fits(value) -> "the wrong kind of value for $name"
            value is JSONObject -> objectProblem("$path.", prop, value, depth)
            value is JSONArray -> arrayProblem(path, prop, value, depth)
            else -> enumProblem(path, prop.optJSONArray("enum"), value)
        }
    }

    private fun arrayProblem(path: String, prop: JSONObject, value: JSONArray, depth: Int): String? {
        val items = prop.optJSONObject("items") ?: return "a list for ${EngineCallGate.safeName(path)} it cannot check"
        return (0 until value.length()).asSequence()
            .mapNotNull { valueProblem("$path.$it", items, value.get(it), depth + 1) }
            .firstOrNull()
    }

    private fun enumProblem(path: String, allowed: JSONArray?, value: Any): String? {
        if (allowed == null) return null
        val fits = (0 until allowed.length()).any { same(allowed.get(it), value) }
        return if (fits) null else "a value for ${EngineCallGate.safeName(path)} it does not accept"
    }

    /** Equal by value: numbers by their value whatever their box, anything else by its text. */
    private fun same(a: Any, b: Any): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is Number || b is Number -> false
        else -> a.toString() == b.toString()
    }
}
