package com.kevin.legion.ui.bought

import com.kevin.legion.purchases.Purchase
import com.kevin.legion.purchases.PurchaseMatch

/** The two screens of the bought log: search-first (variant C, purchase-log ticket 05) and the form. */
enum class BoughtMode { SEARCH, LOG }

/**
 * What the search screen shows. **There is deliberately no "empty list" that could mean "could not
 * read"**: [Unavailable] is its own state and carries the sentence (ticket 04, online only).
 */
sealed interface BoughtView {
    data object Loading : BoughtView

    /** The newest entries, shown while the search box is empty. [emptyMessage] is for no entries. */
    data class Recent(val entries: List<Purchase>, val truncated: Boolean, val emptyMessage: String?) : BoughtView

    /** An answer to "when did we last buy [query]": [headline] is a sentence naming the entry, and
     * [matches] are the rows beneath it. Empty [matches] is "no record", said in [headline]. */
    data class Answer(val query: String, val headline: String, val matches: List<PurchaseMatch>) : BoughtView

    /** The log could not be read. [sentence] says so in words; no list is drawn. */
    data class Unavailable(val sentence: String) : BoughtView
}

/** The "Log it" form. Every field is text because that is what the fields hold; [error] is why a
 * save did not happen, in words. [dateText] is `YYYY-MM-DD`. */
data class LogFormState(
    val item: String = "",
    val dateText: String = "",
    val store: String = "",
    val price: String = "",
    val note: String = "",
    val isPrivate: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** The entry being edited; null means this form is logging a new one. */
    val editing: Purchase? = null,
)

data class BoughtUiState(
    val mode: BoughtMode = BoughtMode.SEARCH,
    val query: String = "",
    val view: BoughtView = BoughtView.Loading,
    val form: LogFormState = LogFormState(),
    /** Local epoch day, so rows can say "Sep 20" and the form can default to today. */
    val today: Int = 0,
    /** The sentence shown after a save that returned 2xx; cleared on the next keystroke. */
    val savedMessage: String? = null,
    /** The entry the Delete confirm is asking about; null means no confirm is open. */
    val pendingDelete: Purchase? = null,
    /** Why an edit or delete did not happen, in words; cleared on the next keystroke or action. */
    val problem: String? = null,
)
