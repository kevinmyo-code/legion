package com.kevin.legion.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.kevin.legion.ui.theme.soft.LocalSoftActive

/**
 * Case rule for strings a shared primitive OWNS (a literal it writes itself, or a caller string it has
 * always uppercased as a stamp). Mission-control stamps are uppercase, so under
 * [com.kevin.legion.ui.theme.LegionTheme] this is `uppercase()` - byte-for-byte the old behaviour.
 * Under [com.kevin.legion.ui.theme.soft.SoftTheme] it returns the string untouched: ADR 0051 wants
 * sentence case, and the primitive must not shout at copy the screen builder wrote in sentence case.
 *
 * A primitive's own literal is therefore written in sentence case at its call site
 * (`"Not built".deckCase()`), which renders `NOT BUILT` under LegionTheme exactly as before.
 */
@Composable
@ReadOnlyComposable
fun String.deckCase(): String = if (LocalSoftActive.current) this else uppercase()
