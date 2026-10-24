package com.kevin.legion.dumb

import android.content.SharedPreferences

/**
 * What Android's [android.content.pm.ApplicationInfo.category] says about a package, reduced to the
 * cases Matrix mode cares about. Measured on the A25 2026-10-24: the value is Play's installer
 * override, so Chrome, Firefox, Samsung Internet and Gmail read [SOCIAL], X reads [NEWS], YouTube
 * [VIDEO]; emulators read [GAME]. [GAME] is its own case so a test can say games are not blocked.
 */
enum class AppCategoryTag { SOCIAL, VIDEO, NEWS, GAME, OTHER }

/** A per-package choice on the Matrix mode settings screen. No entry means the natural verdict stands. */
enum class AppOverride { ALLOW, BLOCK }

/** Why an app is blocked or open, in the words the settings list shows (never a colour alone). */
enum class BlockReason(val words: String) {
    SYSTEM("always open: LEGION, Settings or your Home app"),
    MANUAL_BLOCK("you blocked it"),
    MANUAL_ALLOW("you allowed it"),
    KEPT("on the Matrix home list"),
    SEED("known feed app"),
    EXEMPT("opens links, mail or messages"),
    CATEGORY_SOCIAL("tagged Social"),
    CATEGORY_VIDEO("tagged Video"),
    CATEGORY_NEWS("tagged News"),
    OPEN("not tagged as a feed"),
}

data class BlockVerdict(val blocked: Boolean, val reason: BlockReason)

/** Feed apps blocked whatever their category says (ADR 0062, 2026-10-24). A seed: unknown apps fall to category. */
object BlockSeed {
    val PACKAGES: Set<String> = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.reddit.frontpage",
        "com.twitter.android",
        "com.facebook.katana",
        "com.instagram.barcelona",
        "com.snapchat.android",
        "com.google.android.youtube",
        "com.pinterest",
        "com.linkedin.android",
    )
}

/** The facts the decision reads, all resolved at run time (clone-and-run: no OEM package is named). */
interface BlockSources {
    /** The resolved externals on the Matrix home list: dialer, SMS, camera, music, WhatsApp, clock, Google Maps. */
    fun keptPackages(): Set<String>

    /** Settings plus whoever answers a Home press. */
    fun systemPackages(): Set<String>

    /** Whoever answers an https link, a mailto, or is the default SMS app. */
    fun exemptPackages(): Set<String>

    fun category(packageName: String): AppCategoryTag
}

/** Where the per-package choices live. Read live: a change must reach the bounce at once. */
interface AppOverrides {
    fun get(packageName: String): AppOverride?

    /** Null clears the choice. */
    fun set(packageName: String, override: AppOverride?)
}

/** [AppOverrides] over the Matrix mode preferences file. */
class PrefsAppOverrides(private val prefs: SharedPreferences) : AppOverrides {
    override fun get(packageName: String): AppOverride? =
        prefs.getString(KEY + packageName, null)?.let { runCatching { AppOverride.valueOf(it) }.getOrNull() }

    override fun set(packageName: String, override: AppOverride?) {
        prefs.edit().apply {
            if (override == null) remove(KEY + packageName) else putString(KEY + packageName, override.name)
        }.commit()
    }

    private companion object {
        const val KEY = "app_override."
    }
}

/**
 * The one blocking decision (ADR 0062, 2026-10-24), pure so every case is a unit test. The bounce,
 * the settings list and the All apps drawer all ask this and nothing else.
 *
 * Order matters: LEGION, Settings and the Home app are never blocked (bouncing Home would loop);
 * then a manual block, then a manual allow; then kept externals (never blocked by seed or category);
 * then the seed list; then the link, mail and SMS exemption (beats category, never seed or a manual
 * block); then category Social, Video and News. Anything else, games included, is open.
 */
object BlockModel {
    @Suppress("ReturnCount") // an ordered ladder; one worded exit per rung reads better than a nested when
    fun decide(
        packageName: String,
        ownPackage: String,
        sources: BlockSources,
        override: AppOverride?,
    ): BlockVerdict {
        if (packageName == ownPackage || packageName in sources.systemPackages()) {
            return BlockVerdict(false, BlockReason.SYSTEM)
        }
        if (override == AppOverride.BLOCK) return BlockVerdict(true, BlockReason.MANUAL_BLOCK)
        if (override == AppOverride.ALLOW) return BlockVerdict(false, BlockReason.MANUAL_ALLOW)
        if (packageName in sources.keptPackages()) return BlockVerdict(false, BlockReason.KEPT)
        if (packageName in BlockSeed.PACKAGES) return BlockVerdict(true, BlockReason.SEED)
        if (packageName in sources.exemptPackages()) return BlockVerdict(false, BlockReason.EXEMPT)
        return when (sources.category(packageName)) {
            AppCategoryTag.SOCIAL -> BlockVerdict(true, BlockReason.CATEGORY_SOCIAL)
            AppCategoryTag.VIDEO -> BlockVerdict(true, BlockReason.CATEGORY_VIDEO)
            AppCategoryTag.NEWS -> BlockVerdict(true, BlockReason.CATEGORY_NEWS)
            AppCategoryTag.GAME, AppCategoryTag.OTHER -> BlockVerdict(false, BlockReason.OPEN)
        }
    }

    /**
     * The override a tap should store to flip an app. Flipping to what the app would be anyway
     * clears the choice, so the list never carries an override that changes nothing.
     */
    fun flipTo(currentlyBlocked: Boolean, naturallyBlocked: Boolean): AppOverride? {
        val wantBlocked = !currentlyBlocked
        return when {
            wantBlocked == naturallyBlocked -> null
            wantBlocked -> AppOverride.BLOCK
            else -> AppOverride.ALLOW
        }
    }
}

/** [BlockModel] bound to a phone's sources and stored choices. Held by the graph; one instance. */
class BlockPolicy(
    private val ownPackage: String,
    private val sources: BlockSources,
    private val overrides: AppOverrides,
) {
    fun verdict(packageName: String): BlockVerdict =
        BlockModel.decide(packageName, ownPackage, sources, overrides.get(packageName))

    /** The verdict with no stored choice: what the app would be if left alone. */
    fun naturalVerdict(packageName: String): BlockVerdict =
        BlockModel.decide(packageName, ownPackage, sources, null)

    fun isBlocked(packageName: String): Boolean = verdict(packageName).blocked

    /** Flips [packageName]'s state. A package that is always open is left alone. Returns the new verdict. */
    fun flip(packageName: String): BlockVerdict {
        val now = verdict(packageName)
        if (now.reason == BlockReason.SYSTEM) return now
        overrides.set(packageName, BlockModel.flipTo(now.blocked, naturalVerdict(packageName).blocked))
        return verdict(packageName)
    }
}
