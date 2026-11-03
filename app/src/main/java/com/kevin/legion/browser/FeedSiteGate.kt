package com.kevin.legion.browser

import com.kevin.legion.dumb.FeedDomains
import com.kevin.legion.dumb.FeedSite

/** A navigation the feed blocker stopped: which app's site, and whether Matrix mode was on (it picks the words). */
data class FeedBlock(val site: FeedSite, val url: String, val matrix: Boolean)

/**
 * The browser's half of the feed blocker (ADR 0071), pure. The app bounce's own question, asked about
 * a website: is the bounce running (Matrix mode, or "Block feed apps always", ADR 0066), and does
 * `BlockPolicy` say this site's app is blocked? So a 15 minute pass, Matrix mode ignoring a pass, and a
 * manual allow or block all apply with no second list. Main-frame navigations only: the caller never
 * asks about a sub-frame.
 */
object FeedSiteGate {
    fun check(url: String, bounceActive: Boolean, matrixOn: Boolean, isBlocked: (String) -> Boolean): FeedBlock? {
        if (!bounceActive) return null
        val site = FeedDomains.siteFor(BrowserUrl.host(url)) ?: return null
        return if (isBlocked(site.packageName)) FeedBlock(site, url, matrixOn) else null
    }
}
