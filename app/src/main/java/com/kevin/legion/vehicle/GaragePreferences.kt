package com.kevin.legion.vehicle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The driver's configured garage/gate doors, stored locally - plain app-global
 * SharedPreferences. The Shelly Cloud device id and channel per door are not
 * sensitive (they identify hardware, not grant access) and the account's
 * server host is likewise not a secret, so none of it goes through
 * [com.kevin.legion.ai.KeyVault]. The account's `auth_key` IS a secret -
 * that lives in [com.kevin.legion.ai.CompanionProfile], encrypted, same
 * as the Gemini/Mapbox keys.
 */
object GaragePreferences {
    private const val PREFS = "garage_preferences"
    private const val KEY_DOORS = "doors"
    private const val KEY_DEFAULT_DOOR_ID = "default_door_id"
    private const val KEY_SERVER_HOST = "server_host"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every saved door, enabled or not - the Settings screen edits this full list. */
    fun doors(context: Context): List<GarageDoorConfig> {
        val raw = prefs(context).getString(KEY_DOORS, null) ?: return emptyList()
        return runCatching { parse(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun defaultDoorId(context: Context): String? = prefs(context).getString(KEY_DEFAULT_DOOR_ID, null)

    fun setDefaultDoorId(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(KEY_DEFAULT_DOOR_ID) else putString(KEY_DEFAULT_DOOR_ID, id)
        }.apply()
    }

    /** The account's Shelly Cloud server host (e.g. "shelly-12-eu.shelly.cloud"), or null if unset. */
    fun serverHost(context: Context): String? =
        prefs(context).getString(KEY_SERVER_HOST, null)?.takeIf { it.isNotBlank() }

    fun setServerHost(context: Context, host: String?) {
        val trimmed = host?.trim()
        prefs(context).edit().apply {
            if (trimmed.isNullOrBlank()) remove(KEY_SERVER_HOST) else putString(KEY_SERVER_HOST, trimmed)
        }.apply()
    }

    /** Adds a new door (a fresh id is generated) and returns it. The first door saved becomes the default. */
    fun addDoor(context: Context, deviceId: String, channel: Int, friendlyName: String, enabled: Boolean = true): GarageDoorConfig {
        val door = GarageDoorConfig(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            relayId = channel,
            friendlyName = friendlyName,
            enabled = enabled,
        )
        save(context, doors(context) + door)
        if (defaultDoorId(context) == null) setDefaultDoorId(context, door.id)
        return door
    }

    fun updateDoor(context: Context, door: GarageDoorConfig) {
        save(context, doors(context).map { if (it.id == door.id) door else it })
    }

    /** Removes a door; if it was the default, the default falls to whatever's left (or clears). */
    fun removeDoor(context: Context, id: String) {
        save(context, doors(context).filterNot { it.id == id })
        if (defaultDoorId(context) == id) setDefaultDoorId(context, doors(context).firstOrNull()?.id)
    }

    private fun save(context: Context, doors: List<GarageDoorConfig>) {
        val arr = JSONArray()
        for (d in doors) {
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("deviceId", d.deviceId)
                    .put("relayId", d.relayId)
                    .put("friendlyName", d.friendlyName)
                    .put("enabled", d.enabled)
            )
        }
        prefs(context).edit().putString(KEY_DOORS, arr.toString()).apply()
    }

    private fun parse(arr: JSONArray): List<GarageDoorConfig> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(
                GarageDoorConfig(
                    id = o.getString("id"),
                    deviceId = o.getString("deviceId"),
                    relayId = o.optInt("relayId", 0),
                    friendlyName = o.getString("friendlyName"),
                    enabled = o.optBoolean("enabled", true),
                )
            )
        }
    }
}
