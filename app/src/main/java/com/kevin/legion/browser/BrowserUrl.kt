package com.kevin.legion.browser

import java.net.URI
import java.net.URLEncoder

/** What a search shows: ordinary results, or Google Images. */
enum class SearchKind { WEB, IMAGES }

/**
 * Turns what was typed or said into the URL the browser loads (ADR 0071). Something that looks like a
 * web address opens as one (https when no scheme is given); anything else is a Google search. Only
 * http and https are ever loaded: `file:`, `content:`, `javascript:` and `intent:` become a search
 * for the words, never a load.
 */
object BrowserUrl {
    private const val SEARCH = "https://www.google.com/search?q="
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val HOST_LIKE = Regex(
        "^(localhost|([a-z0-9-]+\\.)+[a-z][a-z0-9-]*|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$",
    )

    fun target(input: String, kind: SearchKind = SearchKind.WEB): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (kind == SearchKind.IMAGES) return search(text, kind)
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return text.takeIf { isWebUrl(it) } ?: search(text, kind)
        }
        if (SCHEME.containsMatchIn(text) && !HOST_LIKE.matches(lower)) return search(text, kind)
        if (!text.contains(' ') && HOST_LIKE.matches(lower)) return "https://$text"
        return search(text, kind)
    }

    fun search(words: String, kind: SearchKind = SearchKind.WEB): String {
        val q = URLEncoder.encode(words.trim(), "UTF-8")
        return if (kind == SearchKind.IMAGES) "$SEARCH$q&tbm=isch" else "$SEARCH$q"
    }

    /** True for an http or https URL with a host. Everything else is refused by the browser. */
    fun isWebUrl(url: String?): Boolean {
        val uri = runCatching { URI(url?.trim().orEmpty()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    fun host(url: String?): String? = runCatching { URI(url?.trim().orEmpty()).host }.getOrNull()
}
