package com.kevin.legion.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * The vault's copy button (ADR 0076): the value goes on the clipboard **marked sensitive** (Android 13
 * hides it from the paste preview and the clipboard overlay; earlier versions read the same extra
 * key) and is **cleared after [CLEAR_AFTER_MS]**. A later copy of something else is left alone
 * when LEGION can still read the clipboard to tell; when it cannot (Android 10+ in the background),
 * the clipboard is cleared anyway.
 *
 * The clear runs on the main looper, in this process. **Known limit, stated:** if the process dies
 * inside the 60 seconds the value stays on the clipboard until something else replaces it. The
 * screen does not clear it on leaving: leaving LEGION is how the value gets pasted somewhere else.
 */
object VaultClipboard {
    const val CLEAR_AFTER_MS = 60_000L

    /** The clip's label: names the vault, never the item, so a clipboard history shows no label. */
    private const val CLIP_LABEL = "LEGION vault"

    // ClipDescription.EXTRA_IS_SENSITIVE's value, spelled out for API levels before 33.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var pendingClear: Runnable? = null

    fun copy(context: Context, secret: VaultSecret) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(CLIP_LABEL, secret.reveal())
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                EXTRA_IS_SENSITIVE
            }
            putBoolean(key, true)
        }
        clipboard.setPrimaryClip(clip)
        val app = context.applicationContext
        pendingClear?.let(main::removeCallbacks)
        val clear = Runnable { clearIfOurs(app) }
        pendingClear = clear
        main.postDelayed(clear, CLEAR_AFTER_MS)
    }

    /** Clears the clipboard if it still holds the vault's clip. Safe to call at any time. */
    fun clearIfOurs(context: Context) {
        pendingClear?.let(main::removeCallbacks)
        pendingClear = null
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        // From Android 10 a background app cannot read the clipboard, and the 60 seconds usually
        // end with LEGION in the background (the value was copied to paste somewhere else). Unread
        // is cleared: wiping a newer copy is the safe failure, leaving the value is not.
        val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
        if (description != null && description.label?.toString() != CLIP_LABEL) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
