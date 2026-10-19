package com.kevin.legion.games.race

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** [InviteSeenStore] on SharedPreferences, so a restarted process does not re-raise an invite already shown. */
class PrefsInviteSeenStore(context: Context) : InviteSeenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val PREFS = "game_invites_seen"
        const val KEY = "ids"
    }
}

/**
 * The ONE [InvitePoller] for the process, and the two places that run it.
 *
 * **Neither is always on, and together they still are not.** (1) [bindForeground]: while any
 * LEGION activity is in front, via the process lifecycle. (2) `AriaForegroundService`: while the
 * assistant is switched on and Android let it start. A phone with the assistant off and the app in
 * the background polls nothing, so an invite waits until LEGION is next opened. Ruled out: a
 * second foreground service (a permanent notification for a rare event) and WorkManager (its
 * floor is 15 minutes, against a 120 second invite).
 */
object GameInvitePolling {
    @Volatile private var poller: InvitePoller? = null

    fun poller(context: Context): InvitePoller {
        poller?.let { return it }
        return synchronized(this) {
            poller ?: build(context.applicationContext).also { poller = it }
        }
    }

    private fun build(app: Context): InvitePoller {
        val notifier = GameInviteNotifier(app)
        return InvitePoller(
            backend = {
                val http = EngineHttp(EngineConfig(app))
                if (http.isUsable()) DjangoGamesBackend(http) else null
            },
            seen = PrefsInviteSeenStore(app),
            raise = notifier::raise,
            retract = notifier::retract,
        )
    }

    /** Polls while the app is in front; call once from `Application.onCreate` (main thread). */
    fun bindForeground(context: Context, scope: CoroutineScope) {
        val poller = poller(context)
        var loop: Job? = null
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (loop?.isActive != true) loop = scope.launch { poller.run() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    loop?.cancel()
                    loop = null
                }
            },
        )
    }
}
