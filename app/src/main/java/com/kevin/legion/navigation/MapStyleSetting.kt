package com.kevin.legion.navigation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The nav map's Auto / Day / Night choice (mapbox-plus ticket 01, ruling 2), persisted so it
 * survives the process. A flow, so the map restyles the moment it changes, mid-trip included.
 * Auto is the default.
 */
class MapStyleSetting(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val _mode = MutableStateFlow(read())
    val mode: StateFlow<MapStyleMode> = _mode.asStateFlow()

    fun set(mode: MapStyleMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    /** The nav screen's single button: Auto, Day, Night, round. */
    fun cycle() = set(_mode.value.next())

    private fun read(): MapStyleMode =
        runCatching { MapStyleMode.valueOf(prefs.getString(KEY, null).orEmpty()) }.getOrDefault(MapStyleMode.AUTO)

    private companion object {
        const val PREFS = "nav_map_style"
        const val KEY = "mode"
    }
}
