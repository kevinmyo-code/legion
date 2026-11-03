package com.kevin.legion.browser

/**
 * What the browser must ask before doing (ADR 0071), pure. Returns the question to put to the user, or
 * null when the action may go ahead. The words lists are the ruling; a label is matched word by word,
 * so "Facebook" never matches "book" and "Posts" never matches "post".
 */
object AskFirst {
    /** Labels that commit something: money, a message, a booking, a deletion. */
    val RISKY_WORDS: Set<String> = setOf(
        "pay", "payment", "buy", "purchase", "order", "checkout", "send", "post", "publish", "tweet",
        "reply", "delete", "remove", "book", "reserve", "confirm", "subscribe", "transfer", "donate",
        "submit",
    )

    /** Two-word labels that commit something even though neither word alone is on the list. */
    private val RISKY_PHRASES = listOf("check out", "sign up", "add to cart", "complete purchase")

    /**
     * Host or path words that make a page a checkout or payment page. Never the query string or the
     * title: a Google search for "how to pay a parking ticket" is not a payment page.
     */
    private val PAYMENT_PAGE_WORDS = setOf("checkout", "payment", "payments", "pay", "billing", "purchase", "cart")

    private val WORD = Regex("[a-z]+")

    fun forClick(element: PageElement, page: PageSnapshot): String? {
        val label = element.label.ifBlank { "that ${element.kind.word}" }
        return when {
            risky(element.label) -> "That ${element.kind.word} says \"$label\". Shall I tap it?"
            element.submit && !element.searchForm -> "\"$label\" submits a form on ${hostOf(page)}. Shall I tap it?"
            isPaymentPage(page) && element.kind == ElementKind.BUTTON ->
                "This looks like a checkout or payment page. Shall I tap \"$label\"?"
            else -> null
        }
    }

    /** [submit] is typing then submitting the form (Enter). */
    fun forType(element: PageElement, page: PageSnapshot, submit: Boolean): String? = when {
        isPaymentPage(page) || element.sensitive ->
            "This looks like a checkout or payment page. Shall I type into " +
                "\"${element.label.ifBlank { "that field" }}\"?"
        submit && element.inForm && !element.searchForm ->
            "That would submit a form on ${hostOf(page)}. Shall I type it and submit?"
        else -> null
    }

    fun risky(label: String): Boolean {
        val lower = label.lowercase()
        if (RISKY_PHRASES.any { lower.contains(it) }) return true
        return WORD.findAll(lower).any { it.value in RISKY_WORDS }
    }

    fun isPaymentPage(page: PageSnapshot): Boolean {
        if (page.paymentFields) return true
        val uri = runCatching { java.net.URI(page.url) }.getOrNull() ?: return false
        val words = WORD.findAll("${uri.host.orEmpty()} ${uri.path.orEmpty()}".lowercase()).map { it.value }.toSet()
        return words.any { it in PAYMENT_PAGE_WORDS }
    }

    private fun hostOf(page: PageSnapshot): String = BrowserUrl.host(page.url) ?: "this page"
}
