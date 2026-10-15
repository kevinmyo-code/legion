package com.kevin.midnightai.media

import android.os.Handler
import android.os.Looper

/**
 * Runs a block on the main looper, inline when the caller is already there. Extracted from
 * [SpotifyController.startConnect] so the one decision that matters (F1, 2026-10-15) is a pure
 * function a JVM test can prove, while the Looper plumbing stays a thin default.
 *
 * Why it exists: the App Remote SDK's `ConnectionTask.onPreExecute` creates a `Handler()` on the
 * CALLING thread. Called from `Dispatchers.IO` (the reconnect schedule, the consent retry, every
 * `withContext(IO) { ensureConnected }`) it threw "Can't create handler inside thread that has not
 * called Looper.prepare()" synchronously, the attempt failed in about 0 ms, and the 60 s recent
 * failure window then skipped App Remote. Auto-reconnect therefore never worked off the main
 * thread. Whether the SDK stops throwing once it is called from main is confirmed only on the head
 * unit; a JVM test can only prove the call is handed to the main looper.
 */
internal object MainThreadHop {

    /** Pure: run inline when already on main, otherwise hand to [post]. */
    internal fun run(onMain: Boolean, post: (Runnable) -> Unit, block: Runnable) {
        if (onMain) block.run() else post(block)
    }

    /** Test seam: replaced in unit tests, where there is no Looper. */
    @Volatile internal var onMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }

    @Volatile internal var postToMain: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }

    fun onMain(block: Runnable) = run(onMainThread(), postToMain, block)
}
