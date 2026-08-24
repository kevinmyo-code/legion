package com.kevin.legion.engine.mirror

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.engine.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Wires [MirrorSync]'s promised triggers to real events (senior review of ticket 20, MUST-FIX 2 -
 * "scheduleExport/exportNow/importOnly have zero callers outside the debug button"). [bind] is
 * called once from `MidnightApplication.onCreate`, gated the same way every other Room-touching
 * app-start block in that file is (never under Robolectric - see [MidnightApplication]'s own doc
 * comment for why).
 *
 * Two independent triggers, both gated on a mirror folder actually being configured
 * ([MirrorFolderPreferences.treeUri] non-null) so a driver who never picked a folder pays zero
 * cost for either subscription:
 *
 * (a) **Every successful [RecordStore] write schedules a debounced export.** [RecordStore.afterWrite]
 *     is a companion-level [kotlinx.coroutines.flow.SharedFlow] - see that property's own doc
 *     comment for why a companion-level bus is what call sites that each construct their own
 *     [RecordStore] instance need. Subscribing here, once, for the whole process, means EVERY write
 *     from EVERY call site (`EngineToolbox`, `GeneratedFormScreen`, `MirrorSync` itself importing a
 *     foreign row) reaches the mirror, not just writes that happen to go through a `MirrorSync`
 *     instance directly.
 * (b) **App foreground imports, app background exports** (ticket 12 answer point 2: "debounced
 *     after writes plus on app background"; ticket 13: "import runs on app foreground"), via
 *     [ProcessLifecycleOwner] - the whole-process foreground/background signal, not a single
 *     Activity's, so it fires correctly across `MainActivity`/`MirrorSyncActivity`/any future screen
 *     without each one wiring its own `onStart`/`onStop`.
 */
object MirrorLifecycleBinder {
    private const val TAG = "MirrorLifecycleBinder"

    @Volatile private var bound = false

    /** Idempotent - safe to call more than once (it will not double-register). */
    fun bind(context: Context, appScope: CoroutineScope) {
        if (bound) return
        bound = true

        val appContext = context.applicationContext
        val mirrorSync = MirrorSync(appContext)

        RecordStore.afterWrite
            .onEach {
                if (MirrorFolderPreferences.treeUri.value != null) mirrorSync.scheduleExport()
            }
            .launchIn(appScope)

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (MirrorFolderPreferences.treeUri.value == null) return
                    appScope.launch {
                        runCatching { mirrorSync.importOnly() }
                            .onFailure { Log.w(TAG, "foreground import failed: ${it.message}", it) }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    if (MirrorFolderPreferences.treeUri.value == null) return
                    appScope.launch {
                        runCatching { mirrorSync.exportNow() }
                            .onFailure { Log.w(TAG, "background export failed: ${it.message}", it) }
                    }
                }
            },
        )
    }
}
