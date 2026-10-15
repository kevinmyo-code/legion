package com.kevin.midnightai.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Source { PHONE, MIXTAPE, SPOTIFY }

/**
 * Which player the transport row (Cruise, LiveToolbox) should
 * control. Defaults to PHONE (the existing MusicController remote-control
 * path); [MixtapePlayer] flips this to MIXTAPE while a tape is loaded and
 * reverts to PHONE on stop. SPOTIFY is set by a played song, an explicit switch, or a connect that
 * finds the phone idle (BYO client ID, 2026-07-21; a connect used to set it unconditionally until
 * 2026-10-15); a deliberate disconnect reverts it to PHONE.
 * [MusicRouter] is the thing that actually reads it.
 */
object MusicSource {
    private val _current = MutableStateFlow(Source.PHONE)
    val current: StateFlow<Source> = _current.asStateFlow()

    internal fun set(source: Source) {
        _current.value = source
    }
}
