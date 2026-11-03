package com.kevin.legion.dumb

/** One seed feed app's website: [label] is what the bounce says when the app is not installed. */
data class FeedSite(val packageName: String, val label: String, val domains: Set<String>)

/**
 * The feed blocker's websites (ADR 0071): each [BlockSeed.PACKAGES] app names its web domains here and
 * nowhere else, so LEGION's browser bounces the same feeds the app bounce does. A host matches a domain
 * when it IS the domain or ends with "." + the domain (`m.facebook.com`, `old.reddit.com`), never a
 * lookalike (`notreddit.com`).
 *
 * Android's Social/Video/News tag belongs to an installed package, not to a host, so category intent
 * cannot reach a website: only these sites block in the browser.
 */
object FeedDomains {
    val SITES: List<FeedSite> = listOf(
        FeedSite("com.instagram.android", "Instagram", setOf("instagram.com", "instagr.am")),
        FeedSite("com.zhiliaoapp.musically", "TikTok", setOf("tiktok.com")),
        FeedSite("com.reddit.frontpage", "Reddit", setOf("reddit.com", "redd.it")),
        FeedSite("com.twitter.android", "X", setOf("x.com", "twitter.com", "t.co")),
        FeedSite("com.facebook.katana", "Facebook", setOf("facebook.com", "fb.com", "fb.watch")),
        FeedSite("com.instagram.barcelona", "Threads", setOf("threads.net", "threads.com")),
        FeedSite("com.snapchat.android", "Snapchat", setOf("snapchat.com")),
        FeedSite("com.google.android.youtube", "YouTube", setOf("youtube.com", "youtu.be")),
        FeedSite("com.pinterest", "Pinterest", setOf("pinterest.com", "pin.it")),
        FeedSite("com.linkedin.android", "LinkedIn", setOf("linkedin.com", "lnkd.in")),
    )

    /** The feed site [host] belongs to, or null. Case-insensitive; a trailing dot is ignored. */
    fun siteFor(host: String?): FeedSite? {
        val h = host?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (h.isEmpty()) return null
        return SITES.firstOrNull { site -> site.domains.any { h == it || h.endsWith(".$it") } }
    }
}
