package com.kevin.legion.ui.controlroom

import com.kevin.legion.board.ActionRecord
import java.util.Locale

/**
 * An action record in words, made by CODE from its typed fields (control room C3). Nothing here
 * comes from a model or an agent's free text: [ActionRecord.payee] and [ActionRecord.purpose] are
 * shown only inside quotation marks, as data.
 */
object ActionWords {
    private const val SHORT_SHA = 8
    private const val CENTS = 100.0

    fun headline(a: ActionRecord): String = when (a.kind) {
        "deploy_engine" -> "Deploy the engine at ${short(a.sha)}"
        "publish_phone" -> "Publish a phone update from ${short(a.sha)}"
        "publish_headunit" -> "Publish a head unit update from ${short(a.sha)}"
        "flag_on" -> "Switch on ${a.flags.joinToString(", ")}"
        "flag_off" -> "Switch off ${a.flags.joinToString(", ")}"
        "spend" -> "Spend ${money(a.amountCents)}"
        else -> "An unknown request. Do not allow it."
    }

    fun facts(a: ActionRecord): List<String> = buildList {
        if (a.sha.isNotBlank()) {
            add("Commit ${short(a.sha)}, ${a.tier} tier")
            add(if (a.reviewOkSha == a.sha) "Review confirmed this commit" else "Review is NOT of this commit")
            add(if (a.ciGreen) "Every check green" else "Checks NOT green")
        }
        if (a.kind == "spend") {
            add("To “${a.payee}”")
            add("For “${a.purpose}”")
        }
        add("Expires ${a.expiresAt.take(16).replace('T', ' ')} UTC")
    }

    fun status(status: String): String = when (status) {
        ActionRecord.PENDING -> "Waiting on you"
        "approved" -> "Allowed; nothing runs until the laptop re-checks it"
        "declined" -> "Declined"
        "pulled" -> "Picked up by the laptop"
        "expired" -> "Expired"
        else -> status
    }

    /** The confirm prompt's title: the same words as the card. */
    fun confirmTitle(a: ActionCardUi): String = a.headline

    private fun short(sha: String) = sha.take(SHORT_SHA)

    private fun money(cents: Long?): String =
        if (cents == null) "an unknown amount" else "$" + String.format(Locale.US, "%.2f", cents / CENTS)
}
