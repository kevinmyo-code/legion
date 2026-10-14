package com.kevin.midnightai.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One-time removal of files left on existing installs by features that no longer exist.
 *
 * The wake word and ambient listening (both Vosk-based, removed 2026-10-11) copied the bundled
 * ~40-50MB model out of assets into `filesDir/vosk-model` on first use. Nothing reads it now and
 * nothing else would ever delete it, so it would sit there for the life of the install.
 */
object LegacyCleanup {
    private const val TAG = "LegacyCleanup"
    private const val PREFS = "legacy_cleanup"
    private const val KEY_VOSK_MODEL = "vosk_model_deleted"
    private const val VOSK_MODEL_DIR = "vosk-model"

    /**
     * Fires and forgets on a worker thread. EVERYTHING, including the SharedPreferences read,
     * runs off the main thread: startup has a white-screen history and a recursive delete of a
     * ~50MB tree is real disk work. Safe to call from Application.onCreate.
     */
    fun runAsync(context: Context) {
        val appContext = context.applicationContext
        Thread({
            try {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_VOSK_MODEL, false)) return@Thread
                val dir = File(appContext.filesDir, VOSK_MODEL_DIR)
                // Only mark done when the tree is actually gone, so a partial failure retries
                // next launch instead of being recorded as success.
                if (!dir.exists() || dir.deleteRecursively()) {
                    prefs.edit().putBoolean(KEY_VOSK_MODEL, true).apply()
                } else {
                    Log.w(TAG, "vosk-model delete incomplete; will retry next launch")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "legacy cleanup failed: ${t.message}")
            }
        }, "legacy-cleanup").apply { isDaemon = true }.start()
    }
}
