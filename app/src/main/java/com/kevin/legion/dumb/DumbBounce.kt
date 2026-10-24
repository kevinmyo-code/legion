package com.kevin.legion.dumb

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telecom.TelecomManager

/** The Android answers the bounce rule needs, behind a seam so [DumbBounce] is testable. */
interface BounceEnvironment {
    /** [BlockPolicy.isBlocked]: the one blocking decision. */
    fun isBlocked(packageName: String): Boolean
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
            isBlocked = env::isBlocked,
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
 * The real phone. The per-package launcher check is cached briefly: window changes arrive in bursts
 * and each lookup is a package-manager call. What is blocked comes from [policy].
 */
class AndroidBounceEnvironment(
    private val context: Context,
    private val policy: BlockPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) : BounceEnvironment {
    private val launcherCache = HashMap<String, Pair<Boolean, Long>>()

    override fun isBlocked(packageName: String): Boolean = policy.isBlocked(packageName)

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
    }
}
