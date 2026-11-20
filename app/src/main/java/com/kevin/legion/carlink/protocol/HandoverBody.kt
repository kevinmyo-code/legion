package com.kevin.legion.carlink.protocol

import kotlinx.serialization.json.JsonPrimitive

/**
 * Section 3.5's hand-over body, version 1. Every field optional; absent or blank is not sent.
 *
 * Deliberately has no field for an engine URL (3.5: the unit's engine address is its own build
 * constant) and none for the phone's own engine token (section 7: "never send the phone's own engine
 * token"); [engineToken] is a token minted FOR the unit. `wifi` is omitted until T8 (3.5).
 *
 * Not a data class: a generated toString would print the token and the key.
 */
class HandoverBody(
    val engineToken: String? = null,
    val engineUserId: String? = null,
    val geminiKey: String? = null,
    val mapboxToken: String? = null,
    phoneName: String? = null,
    /** STUB (red step): not yet sent. */
    val engineVehicleId: String? = null,
) {
    /** 3.5: "<display name, <= 40 chars>". */
    val phoneName: String? = phoneName?.trim()?.take(MAX_NAME)

    override fun toString(): String = "HandoverBody(redacted)"

    /** Compact JSON in the document's field order, blank fields omitted. */
    fun toJson(): String = buildString {
        append("{\"v\":1")
        field("engine_token", engineToken)
        field("engine_user_id", engineUserId)
        field("gemini_key", geminiKey)
        field("mapbox_token", mapboxToken)
        field("phone_name", phoneName)
        append('}')
    }

    private fun StringBuilder.field(name: String, value: String?) {
        if (value.isNullOrBlank()) return
        append(",\"").append(name).append("\":").append(JsonPrimitive(value).toString())
    }

    companion object {
        const val MAX_NAME = 40
    }
}
