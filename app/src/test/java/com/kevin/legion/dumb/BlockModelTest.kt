package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blocklist decision (matrix-open ticket 01, ADR 0062 amendment 2026-10-24). */
class BlockModelTest {
    private val own = "com.kevin.legion"

    private class Sources(
        val kept: Set<String> = setOf(
            "dial.pkg", "sms.pkg", "cam.pkg", "com.spotify.music", "com.whatsapp", "clock.pkg",
        ),
        val system: Set<String> = setOf("com.android.settings", "home.pkg"),
        val exempt: Set<String> = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.google.android.gm", "sms.pkg",
        ),
        val categories: Map<String, AppCategoryTag> = mapOf(
            "com.android.chrome" to AppCategoryTag.SOCIAL,
            "org.mozilla.firefox" to AppCategoryTag.SOCIAL,
            "com.google.android.gm" to AppCategoryTag.SOCIAL,
            "com.discord" to AppCategoryTag.SOCIAL,
            "com.google.android.youtube" to AppCategoryTag.VIDEO,
            "some.news.app" to AppCategoryTag.NEWS,
            "me.magnum.melonds" to AppCategoryTag.GAME,
            "com.bank.app" to AppCategoryTag.OTHER,
            "com.whatsapp" to AppCategoryTag.SOCIAL,
            "dial.pkg" to AppCategoryTag.SOCIAL,
        ),
    ) : BlockSources {
        override fun keptPackages() = kept
        override fun systemPackages() = system
        override fun exemptPackages() = exempt
        override fun category(packageName: String) = categories[packageName] ?: AppCategoryTag.OTHER
    }

    private fun decide(pkg: String, o: AppOverride? = null, s: Sources = Sources()) =
        BlockModel.decide(pkg, own, s, o)

    @Test
    fun `social video and news categories are blocked, with the reason in words`() {
        assertEquals(BlockVerdict(true, BlockReason.CATEGORY_SOCIAL), decide("com.discord"))
        assertEquals(BlockVerdict(true, BlockReason.CATEGORY_NEWS), decide("some.news.app"))
        assertEquals("tagged Social", decide("com.discord").reason.words)
        assertEquals("tagged News", decide("some.news.app").reason.words)
        assertEquals("tagged Video", BlockReason.CATEGORY_VIDEO.words)
    }

    @Test
    fun `an unlisted app is judged by its category alone, so a stranger's phone works`() {
        val s = Sources(categories = mapOf("brand.new.feed" to AppCategoryTag.VIDEO))
        assertTrue(decide("brand.new.feed", s = s).blocked)
        assertFalse(decide("brand.new.tool", s = s).blocked)
    }

    @Test
    fun `games are not blocked`() {
        assertEquals(BlockVerdict(false, BlockReason.OPEN), decide("me.magnum.melonds"))
    }

    @Test
    fun `productivity and untagged apps such as the bank stay open`() {
        assertFalse(decide("com.bank.app").blocked)
        assertFalse(decide("no.category.at.all").blocked)
    }

    @Test
    fun `every seed package is blocked even when its category says nothing`() {
        assertEquals(11, BlockSeed.PACKAGES.size)
        BlockSeed.PACKAGES.forEach { pkg ->
            assertEquals(pkg, BlockVerdict(true, BlockReason.SEED), decide(pkg, s = Sources(categories = emptyMap())))
        }
        assertEquals("known feed app", BlockReason.SEED.words)
    }

    @Test
    fun `browsers and mail tagged Social are not blocked by category`() {
        assertEquals(BlockVerdict(false, BlockReason.EXEMPT), decide("com.android.chrome"))
        assertFalse(decide("org.mozilla.firefox").blocked)
        assertFalse(decide("com.google.android.gm").blocked)
    }

    @Test
    fun `the exemption beats category but never the seed list`() {
        val s = Sources(exempt = setOf("com.google.android.youtube", "com.instagram.android"))
        assertTrue(decide("com.google.android.youtube", s = s).blocked)
        assertTrue(decide("com.instagram.android", s = s).blocked)
    }

    @Test
    fun `the exemption never beats a manual block`() {
        assertEquals(BlockVerdict(true, BlockReason.MANUAL_BLOCK), decide("com.android.chrome", AppOverride.BLOCK))
    }

    @Test
    fun `a manual allow opens a seed app and a tagged app`() {
        assertEquals(BlockVerdict(false, BlockReason.MANUAL_ALLOW), decide("com.instagram.android", AppOverride.ALLOW))
        assertFalse(decide("com.discord", AppOverride.ALLOW).blocked)
    }

    @Test
    fun `a manual block closes an open app`() {
        assertEquals(BlockVerdict(true, BlockReason.MANUAL_BLOCK), decide("com.bank.app", AppOverride.BLOCK))
        assertEquals("you blocked it", BlockReason.MANUAL_BLOCK.words)
    }

    @Test
    fun `kept externals are never blocked by seed or category but can be blocked by hand`() {
        val seeded = Sources(kept = setOf("com.instagram.android", "dial.pkg"))
        assertFalse("dial.pkg is tagged Social", decide("dial.pkg").blocked)
        assertEquals(BlockVerdict(false, BlockReason.KEPT), decide("com.whatsapp"))
        assertFalse(decide("com.instagram.android", s = seeded).blocked)
        assertTrue(decide("com.whatsapp", AppOverride.BLOCK).blocked)
    }

    @Test
    fun `LEGION, Settings and the Home app are never blocked, not even by hand or seed`() {
        assertEquals(BlockVerdict(false, BlockReason.SYSTEM), decide(own))
        assertEquals(BlockVerdict(false, BlockReason.SYSTEM), decide("com.android.settings", AppOverride.BLOCK))
        assertFalse(decide("home.pkg", AppOverride.BLOCK).blocked)
        val seededHome = Sources(system = setOf("com.instagram.android"))
        assertFalse(decide("com.instagram.android", s = seededHome).blocked)
        assertFalse(decide(own, AppOverride.BLOCK).blocked)
    }

    @Test
    fun `flipping stores the opposite of the state and clears when it matches the natural verdict`() {
        assertEquals(AppOverride.ALLOW, BlockModel.flipTo(currentlyBlocked = true, naturallyBlocked = true))
        assertEquals(AppOverride.BLOCK, BlockModel.flipTo(currentlyBlocked = false, naturallyBlocked = false))
        assertNull(BlockModel.flipTo(currentlyBlocked = false, naturallyBlocked = true))
        assertNull(BlockModel.flipTo(currentlyBlocked = true, naturallyBlocked = false))
    }

    private class MemoryOverrides : AppOverrides {
        val map = HashMap<String, AppOverride>()
        override fun get(packageName: String) = map[packageName]
        override fun set(packageName: String, override: AppOverride?) {
            if (override == null) map.remove(packageName) else map[packageName] = override
        }
    }

    @Test
    fun `the policy flips an app both ways and returns to no stored choice`() {
        val store = MemoryOverrides()
        val policy = BlockPolicy(own, Sources(), store)
        assertTrue(policy.isBlocked("com.discord"))
        assertFalse(policy.flip("com.discord").blocked)
        assertEquals(AppOverride.ALLOW, store.get("com.discord"))
        assertTrue(policy.flip("com.discord").blocked)
        assertNull("flipping back to the natural verdict leaves no override", store.get("com.discord"))

        assertFalse(policy.isBlocked("com.bank.app"))
        assertTrue(policy.flip("com.bank.app").blocked)
        assertEquals(AppOverride.BLOCK, store.get("com.bank.app"))
    }

    @Test
    fun `the policy leaves an always-open app alone`() {
        val store = MemoryOverrides()
        val policy = BlockPolicy(own, Sources(), store)
        assertEquals(BlockReason.SYSTEM, policy.flip("com.android.settings").reason)
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun `every reason says something in words`() {
        BlockReason.entries.forEach { assertTrue(it.name, it.words.length > 5) }
    }
}
