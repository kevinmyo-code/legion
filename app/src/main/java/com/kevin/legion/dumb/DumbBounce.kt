package com.kevin.legion.dumb

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.telecom.TelecomManager

/** The Android answers the bounce rule needs, behind a seam so [DumbBounce] is testable. */
interface BounceEnvironment {
    fun keptPackages(): Set<String>
    fun systemPackages(): Set<String>
    fun inCall(): Boolean
    fun hasLauncherEntry(packageName: String): Boolean
}

/**
 * Decides, per window change, whether to bounce (ADR 0062): [BounceRule] plus a per-package
 * debounce, because one app opening fires several window-state events and the bounce screen should
 * appear once. Never throws into the accessibility service: a failed lookup means no bounce.
 */
class DumbBounce(
    private val isOn: () -> Boolean,
    private val ownPackage: String,
    private val env: BounceEnvironment,
    val allowance: BounceAllowance,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val lastBounce = HashMap<String, Long>()

    fun shouldBounce(packageName: String?): Boolean {
        if (packageName.isNullOrBlank() || !isOn()) return false
        return ruleSaysBounce(packageName) && notJustBounced(packageName)
    }

    private fun ruleSaysBounce(packageName: String): Boolean {
        val context = BounceContext(
            modeOn = true,
            ownPackage = ownPackage,
            keptPackages = env.keptPackages(),
            systemPackages = env.systemPackages(),
            inCall = env.inCall(),
            graceActive = allowance.graceActive(),
            allowedAfterChosenPerson = BounceAllowance.ALLOW_APP_AFTER_CHOSEN_PERSON &&
                allowance.packageAllowed(packageName),
        )
        return BounceRule.shouldBounce(packageName, context, env::hasLauncherEntry)
    }

    /** True the first time within [DEBOUNCE_MS]; records the bounce. */
    private fun notJustBounced(packageName: String): Boolean {
        return synchronized(lock) {
            val now = clock()
            val last = lastBounce[packageName]
            if (last != null && now - last in 0 until DEBOUNCE_MS) {
                false
            } else {
                lastBounce[packageName] = now
                true
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 1_500L
    }
}

/** A value recomputed at most once per [ttlMs]. Not thread-safe by itself; callers hold their own lock. */
class TtlCache<T>(private val ttlMs: Long, private val clock: () -> Long, private val load: () -> T) {
    private var value: T? = null
    private var loadedAt = 0L

    @Synchronized
    fun get(): T {
        val now = clock()
        val cached = value
        if (cached != null && now - loadedAt in 0 until ttlMs) return cached
        return load().also {
            value = it
            loadedAt = now
        }
    }
}

/**
 * The real phone. The kept set, the Settings package and the per-package launcher check are cached
 * briefly: window changes arrive in bursts and each lookup is a package-manager call.
 */
class AndroidBounceEnvironment(
    private val context: Context,
    private val resolver: KeptAppResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) : BounceEnvironment {
    private val kept = TtlCache(CACHE_MS, clock) { resolver.keptPackages() }
    private val system = TtlCache(CACHE_MS, clock) { systemPackages(context) }
    private val launcherCache = HashMap<String, Pair<Boolean, Long>>()

    override fun keptPackages(): Set<String> = kept.get()

    override fun systemPackages(): Set<String> = system.get()

    override fun inCall(): Boolean = runCatching {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        telecom?.isInCall == true ||
            audio?.mode == AudioManager.MODE_IN_CALL ||
            audio?.mode == AudioManager.MODE_IN_COMMUNICATION
    }.getOrDefault(false)

    @Synchronized
    override fun hasLauncherEntry(packageName: String): Boolean {
        val now = clock()
        val cached = launcherCache[packageName]
        if (cached != null && now - cached.second in 0 until CACHE_MS) return cached.first
        val found = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }.getOrDefault(false)
        launcherCache[packageName] = found to now
        return found
    }

    private companion object {
        const val CACHE_MS = 60_000L

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
    }
}
