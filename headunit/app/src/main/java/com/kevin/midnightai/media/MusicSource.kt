package com.kevin.midnightai.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Source { PHONE, MIXTAPE, SPOTIFY }

/**
 * Which player the transport row (Cruise, CompanionBadge, LiveToolbox) should
 * control. Defaults to PHONE (the existing MusicController remote-control
 * path); [MixtapePlayer] flips this to MIXTAPE while a tape is loaded and
 * reverts to PHONE on stop. SPOTIFY is set while an App Remote connection is
 * live (BYO client ID, 2026-07-21); it reverts to PHONE on disconnect.
 * [MusicRouter] is the thing that actually reads it.
 */
object MusicSource {
    private val _current = MutableStateFlow(Source.PHONE)
    val current: StateFlow<Source> = _current.asStateFlow()

    internal fun set(source: Source) {
        _current.value = source
    }
}
