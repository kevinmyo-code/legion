package com.kevin.legion.browser

/** How a page load ended. Only [Loaded] lets anything say "opened" (CLAUDE.md section 7). */
sealed interface LoadOutcome {
    data class Loaded(val url: String, val title: String, val httpStatus: Int? = null) : LoadOutcome
    data class Blocked(val block: FeedBlock) : LoadOutcome
    data class Failed(val reason: String) : LoadOutcome
    data object TimedOut : LoadOutcome
    data object NotOpen : LoadOutcome
}

/** What a click did. [Clicked.after] is the page load the click started, if one started. */
sealed interface ClickOutcome {
    data class Clicked(val after: LoadOutcome?) : ClickOutcome
    data object Missing : ClickOutcome
    data object NotOpen : ClickOutcome
}

sealed interface TypeOutcome {
    data class Typed(val after: LoadOutcome?) : TypeOutcome
    data object Missing : TypeOutcome
    data object PasswordRefused : TypeOutcome
    data object NotAField : TypeOutcome
    data object NotOpen : TypeOutcome
}

data class ScrollOutcome(val atTop: Boolean, val atEnd: Boolean)

/**
 * The browser as [BrowserTool] sees it: the seam that keeps every rule in the tool testable without a
 * WebView. [BrowserController] is the one real implementation; the hand path (the screen) calls the
 * same controller.
 */
interface BrowserPort {
    /** The URL on screen, or null when the browser is not open. */
    val currentUrl: String?

    /** Opens the browser if it is closed, then loads [url] and waits for it to finish. */
    suspend fun open(url: String): LoadOutcome
    suspend fun snapshot(): PageSnapshot?
    suspend fun click(n: Int): ClickOutcome
    suspend fun type(n: Int, text: String, submit: Boolean): TypeOutcome
    suspend fun scroll(direction: ScrollDirection): ScrollOutcome?
    suspend fun back(): LoadOutcome

    /** True only when the browser screen actually went away. */
    suspend fun close(): Boolean
}
