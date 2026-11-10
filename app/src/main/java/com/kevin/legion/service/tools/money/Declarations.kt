@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.money

import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The money tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        add(fn(
            name = "get_balance",
            description = "Report the latest known balance for a ledger account, LEADING WITH THE " +
                "AVAILABLE FIGURE the way the user's own bank app does. If the user doesn't " +
                "name one and only one account is on file, use that one; if several exist, ask " +
                "which, or report all of them. The available figure can include TWO different " +
                "kinds of unconfirmed activity, and both must be said out loud, not just reported " +
                "as a final number: pending_delta_cents is card activity read from a mid-cycle " +
                "export that hasn't been confirmed by a statement yet (CLAUDE.md §4 rule 7), and " +
                "pending_count is charges the USER logged by voice that the bank hasn't posted " +
                "or confirmed at all. The response marks the account verified=false when either is " +
                "nonzero - say the figure is pending or unverified rather than presenting it as final.",
            params = obj("account" to schema("string", "Which account, if the user named one. " +
                "Leave empty to get all known accounts.")),
            required = listOf(),
        ))

        add(fn(
            name = "list_recent_transactions",
            description = "Read back the most recent ledger transactions (amounts, plus two names for " +
                "each: merchant is the name the person gave it, description is the bank's raw text. " +
                "Say the merchant; quote the bank's text only when asked what the bank printed. " +
                "There's no spend-by-category breakdown yet, so don't imply insight this " +
                "doesn't have). Use when the user asks what they've spent money on recently or " +
                "wants to review recent transactions. Some rows are pending card activity read from a " +
                "mid-cycle export, not yet confirmed by a statement (CLAUDE.md §4 rule 7) - each such " +
                "row has verified=false and a note; say it's pending, don't present it as confirmed.",
            params = obj("count" to schema("integer", "How many to return. Default 10.")),
            required = listOf(),
        ))

        // --- Categorisation (ticket 07 D14-D19, wired 2026-08-07) -------------
        //
        // CategoryAgent.guessBatch/LedgerController.applyCategoryGuesses/
        // confirmCategoryGuess/applyCategoryRules were all built and unit-tested
        // (ticket 07) but had ZERO callers outside the ledger package itself -
        // no tool, no button - the same structurally-unreachable shape sync/
        // sat in until a screen finally called setSyncEnabled. These two tools
        // are the trigger.

        add(fn(
            name = "categorize_transactions",
            description = "Have the AI guess a spending category for every transaction that has " +
                "none yet, batched once per distinct merchant (never per transaction - cheap even " +
                "for a large backlog). Every guess is a GUESS, not a fact, until the user " +
                "confirms or corrects it with set_category - a budget figure built on unconfirmed " +
                "guesses is REPORTED, not PROVEN, and must be said that way. Use when the user " +
                "asks to categorize their transactions, sort out their spending, or asks why so " +
                "much is 'uncategorised'.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "set_category",
            description = "Confirm or correct the spending category for a merchant - applies to " +
                "every transaction from that merchant on file, past and future (correcting rewrites " +
                "history: last month's budget figure was wrong and is now right). Use when the " +
                "user confirms an AI-guessed category is right, or tells you the right one, e.g. " +
                "'yes, Kroger is groceries' or 'no, that Amazon charge was actually a gift, not " +
                "shopping'. Call list_budget_categories first if unsure of the exact category name - " +
                "only a category from that fixed list is accepted.",
            params = obj(
                "merchant" to schema("string", "The merchant name, in the user's own words, e.g. 'Kroger'."),
                "category" to schema("string", "The category to assign, spelled exactly as list_budget_categories gives it."),
            ),
            required = listOf("merchant", "category"),
        ))

        // --- Voice-logged pending transactions ------------------------------
        //
        // The driver's own bank nets still-processing card activity into an
        // "available" balance that no BofA export ever prints - the CSV
        // parser requires a printed running balance on every row, so a
        // pending charge cannot even be represented in a file. These three
        // tools are the voice-only path around that gap. Every row they
        // write is REPORTED tier (CLAUDE.md §4 rule 7's vocabulary): the
        // driver's own word, never confirmed by anything printed. Say so.

        add(fn(
            name = "log_pending_transaction",
            description = "Log a charge or credit the user knows about but that hasn't shown up " +
                "on any statement or export yet - a still-processing/pending card transaction. " +
                "This is the user's OWN REPORT, not confirmed by the bank; the reply must say " +
                "so out loud, not present it as a normal logged transaction. Use when the user " +
                "says something like 'I just spent forty dollars at the hardware store, put it on " +
                "the BofA card' or 'log a pending charge of twelve fifty for coffee'.",
            params = obj(
                "description" to schema("string", "What the charge or credit was for."),
                "amount" to schema("number", "The MAGNITUDE in the account's own currency, always " +
                    "positive - never a negative number, direction is its own separate field."),
                "direction" to schema(
                    "string",
                    "Whether this is money leaving the account (a charge) or arriving (a credit/refund). " +
                        "Defaults to debit if the user doesn't say.",
                    enum = listOf("debit", "credit"),
                ),
                "account" to schema("string", "Which account this belongs to, if the user named " +
                    "one or only one account is on file. If it's unclear which account, ASK rather " +
                    "than guessing - this tool will never invent an account."),
                "date" to schema("string", "MM/DD/YYYY. Defaults to today if the user doesn't say."),
            ),
            required = listOf("description", "amount"),
        ))

        add(fn(
            name = "list_pending_transactions",
            description = "Read back every pending transaction the user has logged by voice - " +
                "none of these are confirmed by the bank, every one carries verified=false. " +
                "Each row carries merchant (the name the person gave it) and description (the raw " +
                "text it was logged as). Use " +
                "when the user asks what pending charges they've logged, or wants to review or " +
                "clear one.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "clear_pending_transaction",
            description = "Remove a voice-logged pending transaction, e.g. because it posted for " +
                "real and will now show up in a statement, or the user logged it in error. " +
                "Matches by description - if more than one pending row matches, ask which rather " +
                "than guessing. This can only ever remove a pending row, never a real imported one.",
            params = obj("description" to schema("string", "Which pending transaction to remove, " +
                "matched against what it was logged as.")),
            required = listOf("description"),
        ))

        // The counterpart to set_meal_target, and it exists because the UI
        // promised it. TodayScreen's empty budget row says, in words, 'say
        // "set a grocery budget"' - and until this tool existed,
        // LedgerController.setBudget had no caller anywhere: not a screen, not
        // a tool. An empty state that names a command the app cannot perform is
        // the same class of failure as a figure the app cannot defend.
        add(fn(
            name = "set_budget",
            description = "Set a monthly spending budget for one category. Use when the user " +
                "states a limit, e.g. 'budget four hundred a month for groceries'. Applies from " +
                "the current month onward until changed - it does not need setting again each " +
                "month. Call list_budget_categories first if unsure of the exact category name.",
            params = obj(
                "category" to schema("string", "The spending category, e.g. 'groceries'."),
                "amount" to schema("number", "The monthly limit, in dollars."),
            ),
            required = listOf("category", "amount"),
        ))

        add(fn(
            name = "list_budget_categories",
            description = "List the spending categories a budget can be set against. Categories " +
                "are a fixed set, so this is the way to find the right name before calling " +
                "set_budget.",
            params = obj(),
            required = emptyList(),
        ))

        // 2026-08-13: the voice path's own copy of the screen's US BUDGET disclosure. Before this
        // tool existed there was NO voice-callable spend total for the ledger at all - a driver
        // asking "how much have I spent this month" had nothing to call. CLAUDE.md §4 rule 7
        // requires this figure to carry the SAME "own-account movements excluded" caveat the screen
        // states in words next to the number - never a total that quietly reads more honest out loud
        // than it looks on screen.
        add(fn(
            name = "get_monthly_spend",
            description = "Report this month's categorised operating spend for the US ledger " +
                "entity - the SAME figure the Money screen's SPEND pane shows. Money moved between " +
                "the user's own accounts (a card payment, a savings transfer) is excluded from " +
                "this total; ALWAYS say how many transactions and how much were excluded, exactly " +
                "as the response's excluded_own_account_movements fields state - never present the " +
                "total as if nothing was left out. Transactions with no category are ALSO excluded " +
                "from this total: whenever uncategorized_cents is above zero, say so out loud using " +
                "uncategorized_note - that money was spent, it is simply not classified yet, and a " +
                "total presented without it would understate the month. Transactions filed under a " +
                "category that is not spending (Transfers) are excluded too: whenever " +
                "excluded_not_spending_count is above zero, say so using not_spending_note. " +
                "A Housing charge (rent) made in the last 3 days of a month counts in the NEXT " +
                "month's spend: whenever early_housing_notes is non-empty, say each note out loud. " +
                "If the response's verified field is false, say " +
                "the figure is not fully confirmed (pending bank data, an unconfirmed AI-guessed " +
                "category, or a month not fully covered by an imported statement).",
            params = obj(),
            required = emptyList(),
        ))
    }
}
