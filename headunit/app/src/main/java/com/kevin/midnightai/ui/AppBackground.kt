package com.kevin.midnightai.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tiny change-signal for the companion avatar/wallpaper. The art lives on
 * disk ([com.kevin.midnightai.ai.AvatarStudio], now backed by bundled seed
 * art rather than generation - see [com.kevin.midnightai.ai.SeedArt]); this
 * just bumps a version so any screen showing it re-reads, e.g. after a car
 * switch or a companion sync pulls in a different identity.
 */
object AppBackground {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyChanged() {
        _version.value++
    }
}
