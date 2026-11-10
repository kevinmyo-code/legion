package com.kevin.legion.ui.openloops

import com.kevin.legion.openloops.Proposal

/**
 * What the rows draw. [note] is the last tap's words, or why the list could not be read.
 * [listed]: the engine's listing was read, so an empty list may be drawn as "none". [notYet]: the
 * engine has no open-loops routes yet; no switch, no list, the reason in words.
 */
data class OpenLoopsUi(
    val loaded: Boolean = false,
    val listed: Boolean = false,
    val notYet: Boolean = false,
    val proposing: Boolean = true,
    val on: Boolean = true,
    val proposals: List<Proposal> = emptyList(),
    val note: String? = null,
)
