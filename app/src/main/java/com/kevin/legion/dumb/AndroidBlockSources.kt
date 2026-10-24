package com.kevin.legion.dumb

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's answers for [BlockModel]. Everything is resolved through the package manager at run
 * time; no OEM package is named (clone-and-run). The `<queries>` entries in the manifest for https
 * links and mailto are what make the exemption visible on API 30+.
 *
 * Package-manager calls are cached briefly because the bounce asks on every window change. The kept
 * set is [LiveKeptPackages], so the Maps setting still takes effect on the next window change.
 */
class AndroidBlockSources(
    private val context: Context,
    private val resolver: KeptAppResolver,
    mapsChoice: () -> MapsChoice,
    private val clock: () -> Long = System::currentTimeMillis,
) : BlockSources {
    private val kept = LiveKeptPackages(resolver, mapsChoice, CACHE_MS, clock)
    private val system = TtlCache(CACHE_MS, clock) { systemPackages(context) }
    private val exempt = TtlCache(CACHE_MS, clock) { exemptPackages(context, resolver) }
    private val categories = ConcurrentHashMap<String, Pair<AppCategoryTag, Long>>()

    override fun keptPackages(): Set<String> = kept.get()

    override fun systemPackages(): Set<String> = system.get()

    override fun exemptPackages(): Set<String> = exempt.get()

    override fun category(packageName: String): AppCategoryTag {
        val now = clock()
        categories[packageName]?.let { (tag, at) -> if (now - at in 0 until CATEGORY_CACHE_MS) return tag }
        val tag = runCatching { tagOf(context.packageManager.getApplicationInfo(packageName, 0).category) }
            .getOrDefault(AppCategoryTag.OTHER)
        categories[packageName] = tag to now
        return tag
    }

    internal companion object {
        const val CACHE_MS = 60_000L
        const val CATEGORY_CACHE_MS = 600_000L

        /** The maps from Android's constants to ours. Pure; the A25's measured values are in [AppCategoryTag]. */
        fun tagOf(category: Int): AppCategoryTag = when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> AppCategoryTag.SOCIAL
            ApplicationInfo.CATEGORY_VIDEO -> AppCategoryTag.VIDEO
            ApplicationInfo.CATEGORY_NEWS -> AppCategoryTag.NEWS
            ApplicationInfo.CATEGORY_GAME -> AppCategoryTag.GAME
            else -> AppCategoryTag.OTHER
        }

        /** Settings, plus whoever answers a Home press (LEGION when it is the home app; the launcher if not). */
        fun systemPackages(context: Context): Set<String> = buildSet {
            val pm = context.packageManager
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            listOf(Intent(Settings.ACTION_SETTINGS), Intent(Settings.ACTION_APPLICATION_SETTINGS), home).forEach {
                pm.resolveActivity(it, 0)?.activityInfo?.packageName?.let(::add)
            }
            // "android" is the system's resolver/chooser, which is what an unset default resolves to.
            remove("android")
        }

        /**
         * Every package that answers an https link or a mailto, plus the default SMS app. These are
         * exempt from the CATEGORY rule only: on the A25 Play tags Chrome, Firefox, Samsung Internet
         * and Gmail as Social, and blocking them would cut off links, mail and the bank's web login.
         */
        fun exemptPackages(context: Context, resolver: KeptAppResolver): Set<String> = buildSet {
            val pm = context.packageManager
            val probes = listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")),
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:someone@example.com")),
            )
            probes.forEach { probe ->
                runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
                    .forEach { add(it.activityInfo.packageName) }
            }
            resolver.smsPackage()?.let(::add)
            remove("android")
        }
    }
}
