package com.kevin.legion.service.tools

import org.json.JSONArray
import org.json.JSONObject

// The JSON schema helpers every tool declaration is built with. Moved verbatim out of
// LiveToolbox (architecture review 2026-11-10, item 8) so the per-domain Declarations files and
// LiveToolbox share one copy.

internal fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
    JSONObject()
        .put("name", name)
        .put("description", description)
        .put("parameters", JSONObject()
            .put("type", "object")
            .put("properties", params)
            .put("required", JSONArray(required)))

internal fun obj(vararg props: Pair<String, JSONObject>): JSONObject =
    JSONObject().apply { for ((k, v) in props) put(k, v) }

internal fun schema(type: String, description: String, enum: List<String>? = null): JSONObject =
    JSONObject().put("type", type).put("description", description)
        .apply { if (enum != null) put("enum", JSONArray(enum)) }

/**
 * The shared parameter every "category B" (stored-data) tool adds (fleet-wide voice, ticket 01
 * section 3, verbatim description shape). One constant so the wording can't drift between the
 * tools that add it.
 */
internal val VEHICLE_PARAM: JSONObject
    get() = schema("string", "Which car, by name or model. Omit for the car currently being driven.")
