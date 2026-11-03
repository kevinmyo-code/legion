package com.kevin.legion.browser

import com.kevin.legion.dumb.AppCategoryTag
import com.kevin.legion.dumb.AppOverride
import com.kevin.legion.dumb.AppOverrides
import com.kevin.legion.dumb.BlockPolicy
import com.kevin.legion.dumb.BlockSeed
import com.kevin.legion.dumb.BlockSources
import com.kevin.legion.dumb.FeedDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0071: feed sites bounce in LEGION's browser exactly when their app would, through the same
 * [BlockPolicy] (ADR 0062/0066), so a pass, Matrix mode and a manual choice carry over.
 */
class FeedSiteGateTest {
    private class Sources : BlockSources {
        override fun keptPackages() = emptySet<String>()
        override fun systemPackages() = emptySet<String>()
        override fun exemptPackages() = emptySet<String>()
        override fun category(packageName: String) = AppCategoryTag.OTHER
    }

    private class Overrides : AppOverrides {
        val map = mutableMapOf<String, AppOverride>()
        override fun get(packageName: String) = map[packageName]
        override fun set(packageName: String, override: AppOverride?) {
            if (override == null) map.remove(packageName) else map[packageName] = override
        }
    }

    private val overrides = Overrides()
    private var matrix = false
    private var passLeft: Long? = null
    private val policy = BlockPolicy("com.kevin.legion", Sources(), overrides, { matrix }, { passLeft })

    private fun check(url: String, bounceActive: Boolean = true) =
        FeedSiteGate.check(url, bounceActive, matrix, policy::isBlocked)

    @Test fun `every seed feed app names its web domains, and only seed apps do`() {
        assertEquals(BlockSeed.PACKAGES - "com.ss.android.ugc.trill", FeedDomains.SITES.map { it.packageName }.toSet())
    }

    @Test fun `the ten feed sites are blocked while the feed block is on`() {
        val urls = listOf(
            "https://www.instagram.com/", "https://www.tiktok.com/@x", "https://old.reddit.com/r/jeep",
            "https://x.com/home", "https://twitter.com/x", "https://m.facebook.com/", "https://www.threads.net/",
            "https://www.snapchat.com/", "https://m.youtube.com/watch?v=1", "https://youtu.be/abc",
            "https://www.pinterest.com/pin/1", "https://www.linkedin.com/feed/",
        )
        for (url in urls) assertNotNull(url, check(url))
    }

    @Test fun `a lookalike or an unrelated site is open`() {
        assertNull(check("https://notreddit.com/"))
        assertNull(check("https://instagram.com.evil.example/"))
        assertNull(check("https://www.google.com/search?q=instagram"))
        assertNull(check("https://jeepforum.com/xj"))
    }

    @Test fun `nothing is blocked when neither Matrix mode nor the always switch is on`() {
        assertNull(check("https://www.instagram.com/", bounceActive = false))
    }

    @Test fun `a running 15 minute pass opens the site, outside Matrix mode only`() {
        passLeft = 600_000L
        assertNull(check("https://www.instagram.com/"))
        matrix = true
        val block = check("https://www.instagram.com/")
        assertNotNull("Matrix mode ignores a pass", block)
        assertTrue(block!!.matrix)
    }

    @Test fun `a manual allow opens the site, as it opens the app`() {
        overrides.map["com.reddit.frontpage"] = AppOverride.ALLOW
        assertNull(check("https://www.reddit.com/"))
        assertNotNull(check("https://www.youtube.com/"))
    }

    @Test fun `the block names the app and the page it stopped`() {
        val block = check("https://www.youtube.com/watch?v=1")!!
        assertEquals("YouTube", block.site.label)
        assertEquals("com.google.android.youtube", block.site.packageName)
        assertEquals("https://www.youtube.com/watch?v=1", block.url)
        assertFalse(block.matrix)
    }

    @Test fun `hosts match without case or a trailing dot`() {
        assertEquals("Reddit", FeedDomains.siteFor("WWW.Reddit.COM.")?.label)
        assertNull(FeedDomains.siteFor(null))
        assertNull(FeedDomains.siteFor(""))
    }
}
