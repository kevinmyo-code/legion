package com.kevin.legion.ui.apps

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Pinned shortcuts with LEGION as the home app (2026-11-18). An app asks the default launcher to
 * pin a shortcut (`ShortcutManager.requestPinShortcut`; Chrome's "Add to Home screen" for a web
 * app), and `isRequestPinShortcutSupported()` is true only when the default launcher has an activity
 * for `LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT`. LEGION had none, so Chrome hid its "Add to Home
 * screen" / "Install app" item outright (`WebappsUtils.isAddToHomeIntentSupported`). Now
 * [PinShortcutActivity] confirms the request and `PinItemRequest.accept()` pins it to LEGION.
 *
 * The pins live with the system, not here: `LauncherApps.getShortcuts` with
 * `ShortcutQuery.FLAG_MATCH_PINNED` returns the shortcuts pinned to this launcher, so they survive a
 * reboot and an update, and an uninstalled package's go with it. They show as drawer rows (a
 * [DrawerApp] with a [DrawerApp.shortcutId]): in All apps under their letter and after the pinned
 * apps on the home row, as monochrome marks like every app (Kevin, 2026-11-18). Widgets
 * (`CONFIRM_PIN_APPWIDGET`) are not handled.
 */
internal object PinnedShortcuts {

    /** The class-name slot of a shortcut row: never a real activity, so keys never collide with the app's. */
    private const val CLASS_PREFIX = "shortcut:"

    /** The home row: the pinned apps first, in their order, then the shortcuts, at most this many. */
    const val HOME_ROW_MAX = 6

    fun asDrawerApp(packageName: String, id: String, label: String, isWork: Boolean, profileKey: Int) =
        DrawerApp(label, packageName, CLASS_PREFIX + id, isWork, profileKey, shortcutId = id)

    /**
     * The ids still pinned for [remove]'s package and profile once it is gone.
     * `LauncherApps.pinShortcuts` replaces a package's whole set, so removing one means re-pinning
     * the rest.
     */
    fun remainingIds(pinned: List<DrawerApp>, remove: DrawerApp): List<String> = pinned
        .filter { it.packageName == remove.packageName && it.profileKey == remove.profileKey }
        .mapNotNull { it.shortcutId }
        .filter { it != remove.shortcutId }

    fun <T> homeRow(apps: List<T>, shortcuts: List<T>, max: Int = HOME_ROW_MAX): List<T> = (apps + shortcuts).take(max)
}

/** The parts of a `LauncherApps.PinItemRequest` the confirm sheet uses, so the rules test off-device. */
internal interface PinRequest {
    val isShortcut: Boolean
    val isValid: Boolean
    val label: String
    val fromPackage: String

    /** True when the system pinned it. */
    fun accept(): Boolean
}

/**
 * The confirm sheet's rules. A stock launcher asks before it adds (Launcher3's add-item sheet, the
 * Pixel's "Add to home screen" dialog), so LEGION asks too: an app should not put things on the
 * home screen unseen.
 */
internal object PinConfirm {
    enum class Outcome { ADDED, REFUSED, GONE }

    /** Whether to show the sheet at all: shortcut requests only (no widgets), still valid. */
    fun offer(request: PinRequest): Boolean = request.isShortcut && request.isValid

    /** "Add": accepts a still-valid request once. A stale one adds nothing and says so. */
    fun add(request: PinRequest): Outcome = when {
        !request.isValid -> Outcome.GONE
        request.accept() -> Outcome.ADDED
        else -> Outcome.REFUSED
    }
}

/** [PinRequest] over the platform request. */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
internal class PlatformPinRequest(private val request: LauncherApps.PinItemRequest) : PinRequest {
    override val isShortcut: Boolean get() = request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
    override val isValid: Boolean get() = request.isValid
    override val label: String get() = request.shortcutInfo?.shortLabel?.toString().orEmpty()
    override val fromPackage: String get() = request.shortcutInfo?.`package`.orEmpty()
    override fun accept(): Boolean = runCatching { request.accept() }.getOrDefault(false)
}

/** The Android side: read, open and remove the shortcuts pinned to LEGION. */
internal object PinnedShortcutsAndroid {
    private const val TAG = "PinnedShortcuts"

    /** The request a CONFIRM_PIN_SHORTCUT intent carries, or null (not ours, or too old an Android). */
    fun request(context: Context, intent: Intent?): PinRequest? {
        if (intent == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val apps = context.getSystemService(LauncherApps::class.java) ?: return null
        return apps.getPinItemRequest(intent)?.let(::PlatformPinRequest)
    }

    /**
     * Every enabled shortcut pinned to LEGION in [profile], as drawer rows, with their marks put in
     * [marks] by [iconKey]. Empty when LEGION is not the default home app (only the default launcher
     * may read shortcuts; the pins are kept and come back when it is the home app again).
     */
    fun load(
        launcherApps: LauncherApps,
        profile: UserHandle,
        profileKey: Int,
        isWork: Boolean,
        densityDpi: Int,
        iconPx: Int,
        marks: MutableMap<String, ImageBitmap>,
    ): List<DrawerApp> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val infos = runCatching { launcherApps.getShortcuts(query, profile) }
            .onFailure { Log.w(TAG, "pinned shortcuts unreadable", it) }
            .getOrNull().orEmpty()
        return infos.filter { it.isEnabled }.map { info ->
            val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()
            val row = PinnedShortcuts.asDrawerApp(info.`package`, info.id, label, isWork, profileKey)
            val key = iconKey(profileKey, row.packageName, row.className)
            if (!marks.containsKey(key)) {
                runCatching { launcherApps.getShortcutIconDrawable(info, densityDpi) }.getOrNull()
                    ?.let { AppMonochrome.mark(it, iconPx) }
                    ?.let { marks[key] = it }
            }
            row
        }
    }

    /** Opens [shortcut], or says why it could not. */
    fun start(context: Context, shortcut: DrawerApp, handle: UserHandle): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return TOO_OLD
        return runCatching {
            context.getSystemService(LauncherApps::class.java)
                .startShortcut(shortcut.packageName, shortcut.shortcutId.orEmpty(), null, null, handle)
            null
        }.getOrElse { "Couldn't open ${shortcut.label}: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** Removes [shortcut] from LEGION's home, keeping the package's other pins; or says why not. */
    fun unpin(context: Context, shortcut: DrawerApp, all: List<DrawerApp>, handle: UserHandle): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return TOO_OLD
        return runCatching {
            context.getSystemService(LauncherApps::class.java)
                .pinShortcuts(shortcut.packageName, PinnedShortcuts.remainingIds(all, shortcut), handle)
            null
        }.getOrElse { "Couldn't remove ${shortcut.label}: ${it.message ?: it.javaClass.simpleName}" }
    }

    private const val TOO_OLD = "Shortcuts need Android 7.1 or later."
}
