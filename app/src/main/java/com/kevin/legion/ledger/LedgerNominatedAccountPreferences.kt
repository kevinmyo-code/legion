package com.kevin.legion.ledger

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which ONE [AccountBalance.accountId] HOME's CRED tile shows a balance for (Kevin, 2026-08-18:
 * "no need for the line graph. just how much I've used so far and what's the balance"). Asked
 * which balance, given LEGION cannot tell a cash account from a card, Kevin chose: one account he
 * nominates himself, never a guessed default (a bare "first account in the list" would silently
 * change every time a new account got mapped or a new statement landed - see
 * [com.kevin.legion.ui.TodayGapResolvers.buildCredBalanceLine]'s own doc comment for the four
 * distinct states this produces).
 *
 * Plain app-global SharedPreferences, same shape as [LedgerFolderPreferences]/
 * [LedgerAccountMappingPreferences]: a [StateFlow] seeded from disk on [init] so HOME reads the
 * current nomination immediately on first composition, never a blank flash before a suspend read
 * completes. Same L12 seeding discipline (`playbook-coding.md`'s "Application initialization and
 * process-global state") - [init] must run from
 * [com.kevin.legion.MidnightApplication.onCreate], never a conditionally-started service.
 *
 * **Storing a bare accountId string, on purpose, never validated against [LedgerController.accountBalances]
 * at write time.** The account a driver nominates today can legitimately stop showing up tomorrow -
 * a Drive folder gets renamed, an account's rows get purged - and CLAUDE.md §4 rule 7's disclosure
 * discipline says that gap must be stated in words the next time HOME renders, never silently
 * repaired by picking a different account out from under the driver. [buildCredBalanceLine] is
 * where that "still nominated, no longer present" state is actually surfaced; this object only
 * ever answers "what was picked", never "is it still valid".
 */
object LedgerNominatedAccountPreferences {
    private const val PREFS = "ledger_nominated_account"
    private const val KEY_ACCOUNT_ID = "account_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _nominatedAccountId = MutableStateFlow<String?>(null)
    val nominatedAccountId: StateFlow<String?> = _nominatedAccountId.asStateFlow()

    /** Call once, early (see this object's doc comment), to seed [nominatedAccountId] from disk - same convention as [LedgerFolderPreferences.init]. */
    fun init(context: Context) {
        _nominatedAccountId.value = prefs(context).getString(KEY_ACCOUNT_ID, null)
    }

    /** Sets the nominated account, or clears the nomination when [accountId] is null/blank - the picker UI's CLEAR action. */
    fun setNominated(context: Context, accountId: String?) {
        val editor = prefs(context).edit()
        if (accountId.isNullOrBlank()) {
            editor.remove(KEY_ACCOUNT_ID)
            _nominatedAccountId.value = null
        } else {
            editor.putString(KEY_ACCOUNT_ID, accountId)
            _nominatedAccountId.value = accountId
        }
        editor.apply()
    }
}
