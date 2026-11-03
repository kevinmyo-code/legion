package com.kevin.legion.browser

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** What a numbered element on the page is. [PASSWORD] is its own kind so nothing can mistake one for a field. */
enum class ElementKind(val word: String) {
    LINK("link"),
    BUTTON("button"),
    FIELD("field"),
    PASSWORD("password field"),
    CHECKBOX("checkbox"),
    SELECT("menu"),
}

/**
 * One numbered element from [PageScripts.SNAPSHOT]. [value] is null for a password or card field: the
 * script never reads one, so the model can never see it. [submit] is a button that submits its form;
 * [searchForm] marks a form that is only a search box (submitting it is not asked first).
 */
data class PageElement(
    val n: Int,
    val kind: ElementKind,
    val label: String,
    val submit: Boolean = false,
    val inForm: Boolean = false,
    val searchForm: Boolean = false,
    val sensitive: Boolean = false,
    val host: String? = null,
    val value: String? = null,
    val checked: Boolean? = null,
)

/**
 * The page as the reader saw it. Held in memory only, for the numbers in the next call, and dropped on
 * the next read, navigation or close: page text is third-party content (CLAUDE.md section 7).
 */
data class PageSnapshot(
    val url: String,
    val title: String,
    val text: String,
    val textCut: Boolean,
    val elements: List<PageElement>,
    val moreElements: Boolean,
    /** The page has card-number or card-code fields. */
    val paymentFields: Boolean,
) {
    fun element(n: Int): PageElement? = elements.firstOrNull { it.n == n }

    /**
     * The element [words] names: an exact label first, then a label that contains the words. Null when
     * nothing matches or two different elements match equally well (the caller asks which).
     */
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    fun match(words: String): MatchResult {
        val want = words.trim().lowercase()
        if (want.isEmpty()) return MatchResult.None
        val exact = elements.filter { it.label.lowercase() == want }
        if (exact.size == 1) return MatchResult.One(exact.single())
        if (exact.size > 1) return MatchResult.Several(exact)
        val partial = elements.filter { it.label.lowercase().contains(want) }
        return when (partial.size) {
            0 -> MatchResult.None
            1 -> MatchResult.One(partial.single())
            else -> MatchResult.Several(partial)
        }
    }

    /** One line per element, the words the tool result carries: `3 button "Order now" (submits a form)`. */
    fun elementLines(): String = elements.joinToString("\n") { line(it) }

    companion object {
        /**
         * Parses what `evaluateJavascript` handed back for [PageScripts.SNAPSHOT]: a JSON-encoded string
         * holding the JSON object. Null for anything else (a page that blocked the script, "null").
         */
        fun parse(evaluated: String?): PageSnapshot? = runCatching {
            val inner = JSONTokener(evaluated ?: return null).nextValue() as? String ?: return null
            fromJson(JSONObject(inner))
        }.getOrNull()

        fun fromJson(o: JSONObject): PageSnapshot {
            val list = o.optJSONArray("elements") ?: JSONArray()
            val elements = (0 until list.length()).mapNotNull { i ->
                val e = list.getJSONObject(i)
                val kind = when (e.optString("kind")) {
                    "link" -> ElementKind.LINK
                    "field" -> ElementKind.FIELD
                    "password" -> ElementKind.PASSWORD
                    "checkbox" -> ElementKind.CHECKBOX
                    "select" -> ElementKind.SELECT
                    "button" -> ElementKind.BUTTON
                    else -> return@mapNotNull null
                }
                val sensitive = kind == ElementKind.PASSWORD || e.optBoolean("sensitive")
                PageElement(
                    n = e.getInt("n"),
                    kind = kind,
                    label = e.optString("label"),
                    submit = e.optBoolean("submit"),
                    inForm = e.optBoolean("form"),
                    searchForm = e.optBoolean("search"),
                    sensitive = sensitive,
                    host = e.optString("host").ifBlank { null },
                    // Belt and braces: a sensitive field's value is dropped here even if a page script sent one.
                    value = if (sensitive || !e.has("value")) null else e.optString("value"),
                    checked = if (e.has("checked")) e.optBoolean("checked") else null,
                )
            }
            return PageSnapshot(
                url = o.optString("url"),
                title = o.optString("title"),
                text = o.optString("text"),
                textCut = o.optBoolean("textCut"),
                elements = elements,
                moreElements = o.optBoolean("more"),
                paymentFields = o.optBoolean("payment"),
            )
        }

        private fun line(e: PageElement): String = buildString {
            append(e.n).append(' ').append(e.kind.word)
            append(" \"").append(e.label.ifBlank { "(no label)" }).append('"')
            e.host?.let { append(" (").append(it).append(')') }
            if (e.kind == ElementKind.FIELD || e.kind == ElementKind.SELECT) {
                when {
                    e.sensitive -> append(" (card field)")
                    !e.value.isNullOrEmpty() -> append(" = \"").append(e.value).append('"')
                }
            }
            e.checked?.let { append(if (it) " (checked)" else " (not checked)") }
            if (e.submit && !e.searchForm) append(" (submits a form)")
        }
    }
}

sealed interface MatchResult {
    data object None : MatchResult
    data class One(val element: PageElement) : MatchResult
    data class Several(val elements: List<PageElement>) : MatchResult
}
