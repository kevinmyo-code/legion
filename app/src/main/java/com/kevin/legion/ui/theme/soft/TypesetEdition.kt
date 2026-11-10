package com.kevin.legion.ui.theme.soft

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which edition of the page is set: paper (day) or cream on warm black (night). ADR 0081. */
enum class TypesetEdition {
    DAY,
    NIGHT,
    ;

    fun flipped(): TypesetEdition = if (this == DAY) NIGHT else DAY
}

/**
 * The edition an activity resolved ([ProvideTypesetEdition]: phone theme, bedtime, the one-tap
 * switch). Null where nothing provides it (previews, screenshot tests): `SoftTheme` then follows
 * the phone's own dark theme alone.
 */
val LocalTypesetEdition = staticCompositionLocalOf<TypesetEdition?> { null }

/**
 * The one-tap switch for the edition in force, provided beside [LocalTypesetEdition] by
 * [ProvideTypesetEdition], which alone knows the automatic answer. A no-op where nothing provides it.
 */
val LocalTypesetEditionToggle = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * The automatic edition, pure: night when the phone is in dark theme, and night while bedtime is
 * on whatever the phone says (paper white at night is the risk the review named).
 */
fun automaticEdition(systemDark: Boolean, bedtimeActive: Boolean): TypesetEdition =
    if (systemDark || bedtimeActive) TypesetEdition.NIGHT else TypesetEdition.DAY

/**
 * The one-tap day/night switch. A tap sets the other edition; the choice holds until the automatic
 * answer next changes (the phone goes dark, bedtime starts or ends), and then the automatic answer
 * wins again. So a manual "day" at 23:00 does not outlive bedtime, and a phone that follows its own
 * theme is never pinned by an old tap. Process-wide, not persisted. Build choice recorded in ADR 0081.
 */
class EditionSwitch {
    private data class Override(val chosen: TypesetEdition, val madeAgainst: TypesetEdition)

    private val override = MutableStateFlow<Override?>(null)

    /** Changes whenever a tap lands or an override is dropped, so a collector re-resolves. */
    val version: StateFlow<Any?> get() = override.asStateFlow()

    /** The edition to set, given the automatic one now. Pure: safe to call in composition. */
    fun resolve(auto: TypesetEdition): TypesetEdition {
        val o = override.value ?: return auto
        return if (o.madeAgainst == auto) o.chosen else auto
    }

    /**
     * Drops an override made against a different automatic answer, so a later return to that answer
     * does not revive an old tap. Called whenever the automatic answer is seen to change.
     */
    fun settle(auto: TypesetEdition) {
        val o = override.value ?: return
        if (o.madeAgainst != auto) override.compareAndSet(o, null)
    }

    /** The tap: sets the other edition from the one showing. */
    fun toggle(auto: TypesetEdition) {
        settle(auto)
        val next = resolve(auto).flipped()
        override.value = if (next == auto) null else Override(next, auto)
    }

    companion object {
        /** The app's one switch. */
        val shared = EditionSwitch()
    }
}
