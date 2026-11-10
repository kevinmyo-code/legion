package com.kevin.legion.ui.openloops

import com.kevin.legion.openloops.Proposal

/** What the rows draw. [note] is the last tap's words, or why the list could not be read. */
data class OpenLoopsUi(
    val loaded: Boolean = false,
    val proposing: Boolean = true,
    val on: Boolean = true,
    val proposals: List<Proposal> = emptyList(),
    val note: String? = null,
)
