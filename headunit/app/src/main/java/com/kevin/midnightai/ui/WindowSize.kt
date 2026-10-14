package com.kevin.midnightai.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import com.kevin.midnightai.ui.theme.AriaDimens

/**
 * The three width buckets Midnight AI reflows against (2026-07-12 dual-target
 * work - CLAUDE.md's "the app now also runs as a normal phone app"). Coarser
 * than Material's own [WindowWidthSizeClass] on purpose: this app has exactly
 * one reflow decision point per screen (today's fixed-landscape head-unit
 * layout vs. a phone-portrait rebuild), not a spectrum of breakpoints.
 *
 *  - COMPACT: phone portrait. The only bucket that reflows in this pass.
 *  - MEDIUM: phone landscape / small tablet. Kept identical to EXPANDED for
 *    now (a phone turned sideways already resembles a small head unit) -
 *    revisit only if real devices prove otherwise.
 *  - EXPANDED: the head unit's fixed landscape. Byte-for-byte today's layout.
 */
internal enum class SizeClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Threaded via [androidx.compose.runtime.CompositionLocalProvider] from
 * [MainActivity]'s `setContent`, not a parameter - the app threads plain
 * composables with no nav-graph/ViewModel layer to carry one down cleanly,
 * and a param would touch every intermediate composable between MainActivity
 * and the leaf that actually needs to branch.
 *
 * Defaults to EXPANDED so any composable that never sees the provider
 * (Compose previews, a screen not yet migrated to read this) keeps today's
 * head-unit behavior rather than silently reflowing.
 */
internal val LocalSizeClass = compositionLocalOf { SizeClass.EXPANDED }

/** Maps Material's width bucket onto this app's coarser 3-value [SizeClass]. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
internal fun WindowWidthSizeClass.toSizeClass(): SizeClass = when (this) {
    WindowWidthSizeClass.Compact -> SizeClass.COMPACT
    WindowWidthSizeClass.Medium -> SizeClass.MEDIUM
    else -> SizeClass.EXPANDED
}

// --- size-class spacing helpers (2026-07-29 UI coherence pass) ------------
// Cruise wrote `if (sizeClass == SizeClass.EXPANDED) 20.dp else 12.dp` (and
// the sibling section-gap pair) inline about seven times. These live here
// rather than in ui/theme/AriaTokens.kt because [SizeClass] is a ui/-layer
// concept (screen reflow), and today's import direction is ui/ -> ui/theme/,
// never the reverse - theme/ importing this file would invert that layering
// for two one-line functions. AriaDimens still owns the actual dp values.

/** Screen-edge padding: [AriaDimens.s4] on the head unit, [AriaDimens.s3] on phone. */
internal fun screenEdge(sizeClass: SizeClass): Dp =
    if (sizeClass == SizeClass.EXPANDED) AriaDimens.s4 else AriaDimens.s3

/** Gap between major sections: [AriaDimens.s4] on the head unit, [AriaDimens.s3] on phone. */
internal fun sectionGap(sizeClass: SizeClass): Dp =
    if (sizeClass == SizeClass.EXPANDED) AriaDimens.s4 else AriaDimens.s3
