package com.kevin.legion.ui.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Everything the drawer renders, captured at one moment. */
internal class Loaded(
    val apps: List<DrawerApp>,
    val icons: Map<String, ImageBitmap>,
    val handles: Map<Int, UserHandle>,
    val workProfile: UserHandle?,
    val workPaused: Boolean,
)

internal fun iconKey(profileKey: Int, packageName: String, className: String) = "$profileKey/$packageName/$className"

/**
 * The app drawer, kept in memory (2026-09-27). Kevin: *"need to cache the apps tray because right now
 * it takes too long to load"* - about five seconds on the A25, because every open re-read every app
 * and redrew all ~80 icons from scratch.
 *
 * LEGION is the home app, so this process stays alive, and the list and icons survive between opens.
 * [warm] fills it at startup so even the first open is instant. [refresh] re-reads the app list,
 * which is cheap, and redraws only the icons it doesn't already hold. An install, update or removal
 * evicts that package's icons through a [LauncherApps.Callback], so a changed app never shows a stale
 * icon. The work-profile pause state is re-read on every refresh, because it flips outside LEGION.
 */
internal object AppDrawerCache {
    private const val TAG = "AppDrawerCache"

    @Volatile private var cached: Loaded? = null
    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var callbackRegistered = false

    /** What the drawer can show right now, with no work at all. Null only before the first load. */
    fun peek(): Loaded? = cached

    /** Fill the cache in the background, and start watching for package changes. Safe to repeat. */
    fun warm(context: Context) {
        val app = context.applicationContext
        registerCallback(app)
        scope.launch { runCatching { refresh(app) }.onFailure { Log.w(TAG, "warm failed", it) } }
    }

    /** Re-read the app list, reusing every icon still valid. One refresh at a time. */
    suspend fun refresh(context: Context): Loaded = mutex.withLock {
        val app = context.applicationContext
        registerCallback(app)
        load(app).also { cached = it }
    }

    private fun registerCallback(context: Context) {
        if (callbackRegistered) return
        synchronized(this) {
            if (callbackRegistered) return
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
            launcherApps.registerCallback(object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(context, listOf(packageName))
                override fun onPackageAdded(packageName: String, user: UserHandle) = changed(context, listOf(packageName))
                override fun onPackageChanged(packageName: String, user: UserHandle) = changed(context, listOf(packageName))
                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                    changed(context, packageNames.toList())
                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                    changed(context, packageNames.toList())
            }, Handler(Looper.getMainLooper()))
            callbackRegistered = true
        }
    }

    /** A package changed: drop its icons so they are redrawn, and refresh in the background. */
    private fun changed(context: Context, packageNames: List<String>) {
        evictIcons(icons, packageNames)
        scope.launch { runCatching { refresh(context) } }
    }

    private fun load(context: Context): Loaded {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val me = android.os.Process.myUserHandle()
        val apps = mutableListOf<DrawerApp>()
        val handles = mutableMapOf<Int, UserHandle>()
        var work: UserHandle? = null
        val profiles = runCatching { launcherApps.profiles }.getOrDefault(listOf(me))
        for (profile in profiles) {
            val key = userManager.getSerialNumberForUser(profile).toInt()
            handles[key] = profile
            val isWork = profile != me
            if (isWork) work = profile
            val activities = runCatching { launcherApps.getActivityList(null, profile) }.getOrDefault(emptyList())
            for (info in activities) {
                val component = info.componentName
                if (!isWork && component.packageName == context.packageName) continue // already here
                apps += DrawerApp(info.label.toString(), component.packageName, component.className, isWork, key)
                val k = iconKey(key, component.packageName, component.className)
                // The slow part, and now only done for icons not already held.
                if (!icons.containsKey(k)) {
                    runCatching { info.getBadgedIcon(0).toBitmap(96, 96).asImageBitmap() }.getOrNull()?.let { icons[k] = it }
                }
            }
        }
        // Forget icons for apps that are gone, so the map can't grow forever.
        val live = apps.mapTo(HashSet()) { iconKey(it.profileKey, it.packageName, it.className) }
        icons.keys.retainAll(live)
        val paused = work?.let { runCatching { userManager.isQuietModeEnabled(it) }.getOrDefault(false) } ?: false
        return Loaded(apps, HashMap(icons), handles, work, paused)
    }
}

/** Drops every cached icon belonging to [packageNames]. Pure, so the eviction rule is testable. */
internal fun evictIcons(icons: MutableMap<String, *>, packageNames: Collection<String>) {
    val names = packageNames.toSet()
    icons.keys.removeAll { key -> key.split('/').getOrNull(1) in names }
}
