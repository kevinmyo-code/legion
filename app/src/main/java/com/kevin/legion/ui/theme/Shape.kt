package com.kevin.legion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale, flattened almost to zero.
 *
 * This is the single most load-bearing override in the whole theme. Material 3
 * ships large radii (8dp to 28dp) and a card-first layout habit, both of which
 * cost vertical space and soften exactly the quality the Instrument direction
 * is after. Squaring the scale is what stops M3 components from reading as
 * generic Material while keeping every one of their behaviours.
 *
 * The residual 2dp on the larger roles is deliberate rather than lazy: at 0dp a
 * dialog or bottom sheet reads as a rendering artefact against a near-black
 * ground, because there is no shadow to define its edge.
 */
val LegionShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)
