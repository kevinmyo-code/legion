package com.kevin.legion.ui.dumb

import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.pantry.PANTRY_ESTIMATE_SENTENCE
import com.kevin.legion.ui.pantry.formatMacros
import com.kevin.legion.util.documentDate

/** One line on a receipt: what was bought and its printed price, and the separate estimate for it. */
internal data class MatrixReceiptLine(val name: String, val price: String, val estimate: String)

/** One receipt, worded. [unverified] is the Soft screen's own sentence when the total is not fully explained. */
internal data class MatrixReceipt(
    val id: Long,
    val title: String,
    val summary: String,
    val unverified: String?,
    val lines: List<MatrixReceiptLine>,
)

/** One currency's grocery spend, with the caveat sentence when any receipt behind it was not fully verified. */
internal data class MatrixSpend(val line: String, val caveat: String?)

/**
 * The words on the Matrix pantry lookups (matrix-open ticket 06). CLAUDE.md section 4 rule 5: a
 * receipt never prints nutrition, so every macro figure is an ESTIMATE and says "estimate" on its own
 * line, apart from the printed prices; the Soft screen's mandated estimate sentence is reused, not
 * reworded. Rule 7: a receipt whose total is not fully explained says "Unverified" in words. There is
 * no stock count in LEGION (it records receipts, not the shelf), and [NO_STOCK] says so.
 */
internal object MatrixPantryText {
    const val LOADING = "Reading your receipts..."
    const val NO_STOCK =
        "LEGION records what you bought on receipts, not what is on the shelf. There is no stock count."
    const val UNREADABLE = "Couldn't read your receipts. This is not an empty list: nothing could be read."
    const val STALE = "Couldn't refresh your receipts. These are the last ones read and may be out of date."
    const val EMPTY = "No receipts yet."
    const val SPEND_UNREADABLE = "Couldn't read the grocery spend. This is not zero: nothing could be read."
    const val ESTIMATES_HEADING = "Estimates - not on the receipt"

    fun receiptOf(receipt: PantryReceipt, items: List<PantryLineItem>): MatrixReceipt {
        val unaccounted = receipt.unaccountedCents
        return MatrixReceipt(
            id = receipt.id,
            title = "${receipt.store}  ${documentDate(receipt.purchaseDate)}",
            summary = "${formatMoney(receipt.totalCents, receipt.currency)}, ${items.size} item(s)",
            unverified = unaccounted?.let {
                "Unverified: ${formatMoney(it, receipt.currency)} of this total isn't explained by the items below. " +
                    "The original photo is gone, so this couldn't be re-checked - it will be confirmed against " +
                    "the bank statement instead."
            },
            lines = items.map {
                MatrixReceiptLine(
                    name = it.name,
                    price = formatMoney(it.totalPriceCents, receipt.currency),
                    estimate = "estimate: ${formatMacros(it)}",
                )
            },
        )
    }

    fun spendOf(total: PantryCurrencyTotal): MatrixSpend = MatrixSpend(
        line = "${total.currency.name}  ${formatMoney(total.totalCents, total.currency)}",
        caveat = if (total.hasUnreconciled) {
            "includes at least one receipt that couldn't be fully verified - this ${total.currency.name} " +
                "total is not confirmed accurate"
        } else {
            null
        },
    )

    /** The line above the receipts, or null when there are rows and the read was fine. */
    fun listNote(state: MatrixPantryUiState): MatrixTextLine? = when {
        state.loading -> MatrixTextLine(LOADING, MatrixTone.DIM)
        state.readFailed && state.receipts.isEmpty() -> MatrixTextLine(UNREADABLE, MatrixTone.ALERT)
        state.readFailed -> MatrixTextLine(STALE, MatrixTone.WARNING)
        state.receipts.isEmpty() -> MatrixTextLine(EMPTY, MatrixTone.DIM)
        else -> null
    }

    /** One receipt's detail as lines: printed prices first, then the estimates apart under their own heading. */
    fun detailLines(receipt: MatrixReceipt): List<MatrixTextLine> = buildList {
        add(MatrixTextLine(receipt.summary, MatrixTone.BODY))
        receipt.unverified?.let { add(MatrixTextLine(it, MatrixTone.UNVERIFIED)) }
        add(MatrixTextLine("On the receipt", MatrixTone.HEADING))
        receipt.lines.forEach { add(MatrixTextLine("${it.name}  ${it.price}", MatrixTone.BODY)) }
        add(MatrixTextLine(ESTIMATES_HEADING, MatrixTone.HEADING))
        add(MatrixTextLine(PANTRY_ESTIMATE_SENTENCE, MatrixTone.UNVERIFIED))
        receipt.lines.forEach { add(MatrixTextLine("${it.name}: ${it.estimate}", MatrixTone.UNVERIFIED)) }
    }
}
